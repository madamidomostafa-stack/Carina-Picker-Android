package com.carina.picker;

import android.content.ContentResolver;
import android.net.Uri;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class SpreadsheetImporter {
    static final class Result {
        final List<Models.PickLine> lines = new ArrayList<>();
        String error = "";
    }

    static Result read(ContentResolver resolver, Uri uri, String displayName) {
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) return error("Cannot open selected file.");
            String n = displayName == null ? "" : displayName.toLowerCase(Locale.US);
            if (n.endsWith(".xlsx")) return readXlsx(in);
            return readCsv(in);
        } catch (Exception e) { return error(e.getMessage()); }
    }

    private static Result readCsv(InputStream in) throws Exception {
        Result r = new Result();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String header = br.readLine();
        if (header == null) return error("Empty file.");
        List<String> hs = parseCsvLine(header);
        int cOrder=find(hs,"ORDER NUMBER","ORDER","ORDER NO","ORDER_NO");
        int cBranch=find(hs,"BRANCH","STORE","BRANCH NAME");
        int cSku=find(hs,"SKU","BARCODE","ITEM","ITEM CODE");
        int cQty=find(hs,"QTY","QUANTITY","REQUIRED QTY");
        int cLoc=find(hs,"LOCATION","LOC","BIN","BIN LOCATION");
        if (cOrder<0||cBranch<0||cSku<0||cQty<0||cLoc<0) return error("Required columns: Order Number | Branch | SKU | QTY | Location");
        String line; int seq=0;
        while((line=br.readLine())!=null){
            List<String> v=parseCsvLine(line);
            String sku=get(v,cSku).trim(); int qty=parseInt(get(v,cQty));
            if(sku.isEmpty()||qty<=0) continue;
            Models.PickLine p=new Models.PickLine();
            p.orderNo=get(v,cOrder).trim(); p.branch=get(v,cBranch).trim(); p.sku=sku;
            p.qty=qty; p.location=get(v,cLoc).trim(); p.seq=++seq;
            if(p.orderNo.isEmpty()||p.branch.isEmpty()||p.location.isEmpty()) return error("Blank Order/Branch/Location at source line " + (seq+1));
            r.lines.add(p);
        }
        return r;
    }

    private static Result readXlsx(InputStream in) throws Exception {
        Map<String,byte[]> files=new HashMap<>();
        try(ZipInputStream zin=new ZipInputStream(in)){
            ZipEntry e; byte[] buf=new byte[8192];
            while((e=zin.getNextEntry())!=null){
                if(e.isDirectory()) continue;
                String name=e.getName();
                if(name.equals("xl/sharedStrings.xml") || name.equals("xl/worksheets/sheet1.xml")){
                    ByteArrayOutputStream out=new ByteArrayOutputStream(); int n;
                    while((n=zin.read(buf))>0) out.write(buf,0,n);
                    files.put(name,out.toByteArray());
                }
            }
        }
        byte[] sheet=files.get("xl/worksheets/sheet1.xml");
        if(sheet==null) return error("XLSX first worksheet was not found.");
        List<String> shared=files.containsKey("xl/sharedStrings.xml") ? parseShared(files.get("xl/sharedStrings.xml")) : new ArrayList<>();
        List<Map<Integer,String>> rows=parseSheet(sheet,shared);
        if(rows.isEmpty()) return error("Empty XLSX sheet.");
        Map<Integer,String> header=rows.get(0);
        int cOrder=findMap(header,"ORDER NUMBER","ORDER","ORDER NO","ORDER_NO");
        int cBranch=findMap(header,"BRANCH","STORE","BRANCH NAME");
        int cSku=findMap(header,"SKU","BARCODE","ITEM","ITEM CODE");
        int cQty=findMap(header,"QTY","QUANTITY","REQUIRED QTY");
        int cLoc=findMap(header,"LOCATION","LOC","BIN","BIN LOCATION");
        if(cOrder<0||cBranch<0||cSku<0||cQty<0||cLoc<0) return error("Required columns: Order Number | Branch | SKU | QTY | Location");
        Result result=new Result(); int seq=0;
        for(int i=1;i<rows.size();i++){
            Map<Integer,String> row=rows.get(i); String sku=val(row,cSku).trim(); int qty=parseInt(val(row,cQty));
            if(sku.isEmpty()||qty<=0) continue;
            Models.PickLine p=new Models.PickLine(); p.orderNo=val(row,cOrder).trim(); p.branch=val(row,cBranch).trim();
            p.sku=sku; p.qty=qty; p.location=val(row,cLoc).trim(); p.seq=++seq;
            if(p.orderNo.isEmpty()||p.branch.isEmpty()||p.location.isEmpty()) return error("Blank Order/Branch/Location at worksheet row " + (i+1));
            result.lines.add(p);
        }
        return result;
    }

    private static List<String> parseShared(byte[] xml) throws Exception {
        List<String> out=new ArrayList<>(); XmlPullParser x=parser(xml); StringBuilder cur=null;
        int event=x.getEventType();
        while(event!=XmlPullParser.END_DOCUMENT){
            if(event==XmlPullParser.START_TAG && x.getName().equals("si")) cur=new StringBuilder();
            else if(event==XmlPullParser.TEXT && cur!=null) cur.append(x.getText());
            else if(event==XmlPullParser.END_TAG && x.getName().equals("si") && cur!=null){out.add(cur.toString());cur=null;}
            event=x.next();
        }
        return out;
    }

    private static List<Map<Integer,String>> parseSheet(byte[] xml,List<String> shared) throws Exception {
        List<Map<Integer,String>> rows=new ArrayList<>(); XmlPullParser x=parser(xml);
        Map<Integer,String> row=null; String cellRef=null, type=null, value=""; boolean inV=false, inT=false;
        int event=x.getEventType();
        while(event!=XmlPullParser.END_DOCUMENT){
            if(event==XmlPullParser.START_TAG){
                String n=x.getName();
                if(n.equals("row")) row=new HashMap<>();
                else if(n.equals("c")){cellRef=x.getAttributeValue(null,"r"); type=x.getAttributeValue(null,"t"); value="";}
                else if(n.equals("v")) inV=true;
                else if(n.equals("t")) inT=true;
            } else if(event==XmlPullParser.TEXT && (inV||inT)) value += x.getText();
            else if(event==XmlPullParser.END_TAG){
                String n=x.getName();
                if(n.equals("v")) inV=false; else if(n.equals("t")) inT=false;
                else if(n.equals("c") && row!=null){
                    String v=value;
                    if("s".equals(type)) { try { v=shared.get(Integer.parseInt(value)); } catch(Exception ignored){} }
                    row.put(columnIndex(cellRef),v);
                } else if(n.equals("row") && row!=null){rows.add(row);row=null;}
            }
            event=x.next();
        }
        return rows;
    }

    private static XmlPullParser parser(byte[] xml) throws Exception {
        XmlPullParserFactory f=XmlPullParserFactory.newInstance(); f.setNamespaceAware(false); XmlPullParser x=f.newPullParser();
        x.setInput(new ByteArrayInputStream(xml),"UTF-8"); return x;
    }
    private static int columnIndex(String ref){ if(ref==null)return 0; int v=0; for(int i=0;i<ref.length();i++){char c=ref.charAt(i); if(c<'A'||c>'Z')break; v=v*26+(c-'A'+1);} return Math.max(0,v-1); }
    private static int find(List<String> h,String... names){for(int i=0;i<h.size();i++){String x=norm(h.get(i));for(String n:names)if(x.equals(norm(n)))return i;}return -1;}
    private static int findMap(Map<Integer,String> h,String... names){for(Map.Entry<Integer,String> e:h.entrySet()){String x=norm(e.getValue());for(String n:names)if(x.equals(norm(n)))return e.getKey();}return -1;}
    private static String norm(String s){return s==null?"":s.trim().toUpperCase(Locale.US).replace('.',' ').replace('_',' ').replaceAll("\\s+"," ");}
    private static String get(List<String> v,int i){return i>=0&&i<v.size()?v.get(i):"";}
    private static String val(Map<Integer,String> v,int i){String s=v.get(i);return s==null?"":s;}
    private static int parseInt(String s){try{return (int)Math.round(Double.parseDouble(s.trim()));}catch(Exception e){return 0;}}
    private static Result error(String s){Result r=new Result();r.error=s==null?"Unknown import error.":s;return r;}
    private static List<String> parseCsvLine(String line){
        List<String> out=new ArrayList<>(); StringBuilder b=new StringBuilder(); boolean q=false;
        for(int i=0;i<line.length();i++){char c=line.charAt(i); if(c=='"'){if(q&&i+1<line.length()&&line.charAt(i+1)=='"'){b.append('"');i++;}else q=!q;} else if(c==','&&!q){out.add(b.toString());b.setLength(0);} else b.append(c);} out.add(b.toString()); return out;
    }
}
