package com.carina.picker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQ_IMPORT = 601;
    private static final int NAVY = Color.rgb(11,53,97);
    private static final int GREEN = Color.rgb(10,143,45);
    private static final int RED = Color.rgb(214,31,38);
    private static final int ORANGE = Color.rgb(232,117,0);
    private static final int PALE = Color.rgb(234,242,251);

    private DatabaseHelper db;
    private LinearLayout root, content;
    private EditText scanInput;
    private TextView orderText, branchText, locationText, skuText, reqText, pickedText, damageText, nfText, progressText, lineText, statusText, elapsedText;
    private Models.PickLine current;
    private boolean damageScanMode = false;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable timerTick = new Runnable() { @Override public void run() { updateElapsed(); timerHandler.postDelayed(this,1000); } };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new DatabaseHelper(this);
        buildShell();
        showControl();
    }

    @Override protected void onResume() { super.onResume(); timerHandler.removeCallbacks(timerTick); timerHandler.post(timerTick); }
    @Override protected void onPause() { super.onPause(); timerHandler.removeCallbacks(timerTick); }

    private void buildShell() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.WHITE);
        TextView title = text("CARINA PICKER - ANDROID",22,Color.WHITE,true); title.setGravity(Gravity.CENTER); title.setBackgroundColor(NAVY); title.setPadding(8,18,8,18); root.addView(title, matchWrap());
        HorizontalScrollView hsv = new HorizontalScrollView(this); hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout nav = new LinearLayout(this); nav.setOrientation(LinearLayout.HORIZONTAL); nav.setPadding(4,4,4,4);
        addNav(nav,"CONTROL",v->showControl()); addNav(nav,"PICKING",v->showPicking()); addNav(nav,"PICK DATA",v->showData());
        addNav(nav,"PICK LOG",v->showLog()); addNav(nav,"EXCEPTIONS",v->showExceptions()); addNav(nav,"SETTINGS",v->showSettings()); addNav(nav,"HELP",v->showHelp());
        hsv.addView(nav); root.addView(hsv, matchWrap());
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(14,14,14,14);
        ScrollView sv = new ScrollView(this); sv.addView(content); root.addView(sv, new LinearLayout.LayoutParams(-1,0,1)); setContentView(root);
    }

    private void addNav(LinearLayout nav,String label,View.OnClickListener l){Button b=button(label,NAVY);b.setOnClickListener(l);nav.addView(b,new LinearLayout.LayoutParams(dp(125),dp(46)));}
    private void clear(){ content.removeAllViews(); }

    private void showControl(){
        clear(); header("CONTROL");
        Button imp=button("IMPORT PICK FILE",Color.rgb(37,99,235)); imp.setOnClickListener(v->importFile()); content.addView(imp,buttonLp());
        Button start=button("START / RESUME PICKING",GREEN); start.setOnClickListener(v->startPicking()); content.addView(start,buttonLp());
        Button reset=button("RESET SESSION",Color.DKGRAY); reset.setOnClickListener(v->confirm("Reset current session? Imported orders will remain.",()->{db.resetSession();current=null;toast("Session reset.");})); content.addView(reset,buttonLp());
        Button clearBtn=button("CLEAR HISTORY",RED); clearBtn.setOnClickListener(v->confirm("Delete PICK DATA, PICK LOG and EXCEPTIONS?",()->{db.clearHistory();current=null;toast("History cleared.");})); content.addView(clearBtn,buttonLp());
        TextView info=text("Single-device mode\nOffline database: " + getDatabasePath(DatabaseHelper.DB_NAME).getAbsolutePath(),14,Color.DKGRAY,false); info.setPadding(8,18,8,8); content.addView(info,matchWrap());
    }

    private void importFile(){
        if("YES".equalsIgnoreCase(db.getSetting("picker_started"))){toast("Import is blocked during active picking.");return;}
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"text/csv","text/comma-separated-values","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"});
        startActivityForResult(i,REQ_IMPORT);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=REQ_IMPORT||resultCode!=RESULT_OK||data==null)return;
        Uri uri=data.getData(); if(uri==null)return;
        String name=fileName(uri); SpreadsheetImporter.Result r=SpreadsheetImporter.read(getContentResolver(),uri,name);
        if(!r.error.isEmpty()){alert("IMPORT ERROR",r.error);return;}
        if(r.lines.isEmpty()){alert("IMPORT","No valid picking rows found.");return;}
        Set<String> existing=db.existingOrders(); Map<String,String> incoming=new HashMap<>();
        for(Models.PickLine l:r.lines){
            if(existing.contains(l.orderNo)){alert("DUPLICATE ORDER","Order " + l.orderNo + " was imported before. Existing data was not changed.");return;}
            String old=incoming.putIfAbsent(l.orderNo,l.branch); if(old!=null&&!old.equalsIgnoreCase(l.branch)){alert("BRANCH CONFLICT","Order " + l.orderNo + " has two different branches.");return;}
        }
        db.insertImportedLines(r.lines); toast(r.lines.size()+" lines imported / "+incoming.size()+" orders.");
    }

    private void startPicking(){
        Models.PickLine l=null; long saved=db.getSettingLong("current_id");
        if("YES".equalsIgnoreCase(db.getSetting("picker_started"))&&saved>0){Models.PickLine x=db.line(saved);if(x!=null&&!x.complete())l=x;}
        if(l==null)l=db.firstOpenLine();
        if(l==null){alert("PICKING","No open picking lines.");return;}
        db.setSetting("picker_started","YES"); loadLine(l,true); showPicking();
    }

    private void showPicking(){
        clear(); header("PICKER - ORDER PICKING");
        LinearLayout row1=new LinearLayout(this); row1.setOrientation(LinearLayout.HORIZONTAL);
        orderText=card(row1,"ORDER NUMBER"); branchText=card(row1,"BRANCH"); progressText=card(row1,"ORDER PROGRESS"); content.addView(row1,matchWrap());
        elapsedText=text("ELAPSED: 00:00:00",13,NAVY,true); elapsedText.setGravity(Gravity.CENTER); elapsedText.setPadding(4,8,4,8);content.addView(elapsedText,matchWrap());
        LinearLayout row2=new LinearLayout(this); row2.setOrientation(LinearLayout.HORIZONTAL);
        locationText=card(row2,"NEXT LOCATION"); skuText=card(row2,"CURRENT SKU"); lineText=card(row2,"LINE"); content.addView(row2,matchWrap());
        LinearLayout row3=new LinearLayout(this); row3.setOrientation(LinearLayout.HORIZONTAL);
        reqText=card(row3,"REQUIRED QTY"); pickedText=card(row3,"SCANNED QTY"); damageText=card(row3,"DAMAGE QTY"); nfText=card(row3,"NOT FOUND QTY");content.addView(row3,matchWrap());
        TextView scanLabel=text("SCAN BARCODE HERE",15,Color.rgb(90,50,0),true);scanLabel.setGravity(Gravity.CENTER);scanLabel.setBackgroundColor(Color.rgb(245,185,115));content.addView(scanLabel,matchWrap());
        scanInput=new EditText(this);scanInput.setSingleLine(true);scanInput.setTextSize(24);scanInput.setGravity(Gravity.CENTER);scanInput.setBackgroundColor(Color.rgb(255,248,220));scanInput.setShowSoftInputOnFocus(false);content.addView(scanInput,new LinearLayout.LayoutParams(-1,dp(62)));
        scanInput.setOnEditorActionListener((v,a,e)->{if(a==EditorInfo.IME_ACTION_DONE || (e!=null&&e.getKeyCode()==KeyEvent.KEYCODE_ENTER)){processScan();return true;}return false;});
        scanInput.setOnKeyListener((v,key,event)->{if(key==KeyEvent.KEYCODE_ENTER&&event.getAction()==KeyEvent.ACTION_UP){processScan();return true;}return false;});
        statusText=text("PRESS START PICKING",18,NAVY,true);statusText.setGravity(Gravity.CENTER);statusText.setPadding(8,20,8,20);statusText.setBackgroundColor(PALE);content.addView(statusText,matchWrap());
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
        Button nf=button("NOT FOUND",ORANGE);nf.setOnClickListener(v->notFound());actions.addView(nf,new LinearLayout.LayoutParams(0,dp(54),1));
        Button dmg=button("DAMAGE",RED);dmg.setOnClickListener(v->beginDamage());actions.addView(dmg,new LinearLayout.LayoutParams(0,dp(54),1)); content.addView(actions,matchWrap());
        Button reset=button("RESET SESSION",Color.DKGRAY);reset.setOnClickListener(v->confirm("Reset current picking session?",()->{db.resetSession();current=null;showControl();}));content.addView(reset,buttonLp());
        long id=db.getSettingLong("current_id"); if(id>0){Models.PickLine x=db.line(id);if(x!=null&&!x.complete())current=x;}
        if(current!=null)renderCurrent(); else setStatus("PRESS START PICKING",NAVY,PALE);
        focusScanner();
    }

    private void processScan(){
        if(scanInput==null)return; String scanned=scanInput.getText().toString().trim();scanInput.setText(""); if(scanned.isEmpty())return;
        if(current==null || !"YES".equalsIgnoreCase(db.getSetting("picker_started"))){setStatus("PRESS START PICKING FIRST",NAVY,PALE);return;}
        if(damageScanMode){processDamageScan(scanned);return;}
        if(!"YES".equalsIgnoreCase(db.getSetting("location_confirmed"))){
            if(norm(scanned).equals(norm(current.location))){db.setSetting("location_confirmed","YES");db.writeLog(current,"LOCATION CONFIRMED",0);setStatus("LOCATION CONFIRMED - Scan SKU " + current.sku,GREEN,Color.rgb(226,244,229));}
            else setStatus("WRONG LOCATION - Expected " + current.location,RED,Color.rgb(255,230,233)); focusScanner();return;
        }
        if(!norm(scanned).equals(norm(current.sku))){setStatus("WRONG SKU - Expected " + current.sku,RED,Color.rgb(255,230,233));focusScanner();return;}
        db.incrementPicked(current.id); current=db.line(current.id); db.writeLog(current,"SKU PICK",1);
        if(current.complete()) advanceAfterLine(); else {renderCurrent();setStatus("SCAN OK - Classified " + current.classified()+" / "+current.qty,GREEN,Color.rgb(226,244,229));focusScanner();}
    }

    private void beginDamage(){
        if(current==null||!"YES".equalsIgnoreCase(db.getSetting("location_confirmed"))){toast("Confirm Location first.");return;}
        if(current.remaining()<=0)return; damageScanMode=true; setStatus("DAMAGE MODE - Scan damaged SKU",ORANGE,Color.rgb(255,238,221));focusScanner();
    }
    private void processDamageScan(String s){
        damageScanMode=false; if(!norm(s).equals(norm(current.sku))){setStatus("WRONG SKU - Damage not recorded",RED,Color.rgb(255,230,233));focusScanner();return;}
        db.incrementDamage(current.id); current=db.line(current.id); db.writeLog(current,"DAMAGE",1); db.writeException(current,1,"Damage");
        if(current.complete())advanceAfterLine(); else {renderCurrent();setStatus("DAMAGE RECORDED - Remaining " + current.remaining(),ORANGE,Color.rgb(255,238,221));focusScanner();}
    }
    private void notFound(){
        if(current==null||!"YES".equalsIgnoreCase(db.getSetting("location_confirmed"))){toast("Confirm Location first.");return;}
        int rem=current.remaining();if(rem<=0)return;
        confirm("Record remaining "+rem+" unit(s) as NOT FOUND?",()->{int q=db.markRemainingNotFound(current.id);current=db.line(current.id);db.writeLog(current,"NOT FOUND",q);db.writeException(current,q,"Not Found");advanceAfterLine();});
    }

    private void advanceAfterLine(){
        String finishedOrder=current.orderNo; Models.PickLine same=db.firstOpenLineForOrder(finishedOrder);
        if(same!=null){loadLine(same,false);renderCurrent();setStatus("NEXT LINE - Scan " + ("YES".equalsIgnoreCase(db.getSetting("location_confirmed"))?"SKU":"LOCATION"),NAVY,PALE);focusScanner();return;}
        if(db.isOrderComplete(finishedOrder)){showCompletion(finishedOrder);return;}
        Models.PickLine next=db.firstOpenLine(); if(next!=null){loadLine(next,false);renderCurrent();focusScanner();}else finishAll();
    }

    private void loadLine(Models.PickLine l, boolean freshStart){
        String oldLoc=db.getSetting("current_location"); String oldOrder=db.getSetting("current_order"); current=l;
        db.setPicking(l.id); db.setSetting("current_id",String.valueOf(l.id)); db.setSetting("current_order",l.orderNo); db.setSetting("current_location",l.location);
        if(!norm(oldLoc).equals(norm(l.location))) db.setSetting("location_confirmed","NO");
        if(!l.orderNo.equals(oldOrder) || db.getSettingLong("order_start_ms")<=0) db.setSetting("order_start_ms",String.valueOf(System.currentTimeMillis()));
        if(freshStart && oldOrder.isEmpty()) db.setSetting("location_confirmed","NO");
    }

    private void showCompletion(String orderNo){
        long start=db.getSettingLong("order_start_ms"); long elapsed=start>0?System.currentTimeMillis()-start:0;
        Models.OrderSummary s=db.summary(orderNo,elapsed); current=null;
        clear();header("ORDER COMPLETE"); TextView ok=text("ORDER COMPLETED SUCCESSFULLY!",22,GREEN,true);ok.setGravity(Gravity.CENTER);ok.setPadding(8,18,8,18);content.addView(ok,matchWrap());
        addSummary("ORDER NUMBER",s.orderNo);addSummary("BRANCH",s.branch);addSummary("PICKER","Mohamed Mostafa");
        LinearLayout quantities=new LinearLayout(this); quantities.setOrientation(LinearLayout.HORIZONTAL);
        summaryCard(quantities,"ORDER QTY",s.required,Color.rgb(30,80,170));summaryCard(quantities,"PICKED",s.picked,GREEN);summaryCard(quantities,"DAMAGE",s.damage,ORANGE);summaryCard(quantities,"NOT FOUND",s.notFound,RED);content.addView(quantities,matchWrap());
        addSummary("COMPLETION",s.completionPct()+"%");addSummary("COMPLETION TIME",formatElapsed(s.elapsedMs));addSummary("TOTAL LINES",s.doneLines+" / "+s.totalLines);
        Button done=button("OK",GREEN);done.setOnClickListener(v->{Models.PickLine next=db.firstOpenExcludingOrder(orderNo);if(next!=null){db.setSetting("location_confirmed","NO");db.setSetting("current_location","");loadLine(next,false);showPicking();}else finishAll();});content.addView(done,buttonLp());
    }
    private void finishAll(){db.resetSession();current=null;clear();header("PICKING COMPLETE");TextView t=text("ALL PICKING COMPLETED\nNo open orders remaining.",22,GREEN,true);t.setGravity(Gravity.CENTER);t.setPadding(10,40,10,40);content.addView(t,matchWrap());Button b=button("CONTROL",NAVY);b.setOnClickListener(v->showControl());content.addView(b,buttonLp());}

    private void renderCurrent(){
        if(current==null)return; if(orderText!=null)orderText.setText(current.orderNo);if(branchText!=null)branchText.setText(current.branch);if(locationText!=null)locationText.setText(current.location);if(skuText!=null)skuText.setText(current.sku);
        if(reqText!=null)reqText.setText(String.valueOf(current.qty));if(pickedText!=null)pickedText.setText(String.valueOf(current.picked));if(damageText!=null)damageText.setText(String.valueOf(current.damage));if(nfText!=null)nfText.setText(String.valueOf(current.notFound));
        int[] p=orderProgress(current.orderNo);if(progressText!=null)progressText.setText(p[0]+" / "+p[1]+" | "+(p[1]==0?0:Math.round(p[0]*100f/p[1]))+"%");if(lineText!=null)lineText.setText(current.seq+"");updateElapsed();
    }
    private int[] orderProgress(String orderNo){int done=0,total=0;for(Models.PickLine l:db.allLines())if(l.orderNo.equals(orderNo)){total++;if(l.complete())done++;}return new int[]{done,total};}
    private void updateElapsed(){if(elapsedText==null)return;long start=db.getSettingLong("order_start_ms");elapsedText.setText("ELAPSED: "+formatElapsed(start>0?System.currentTimeMillis()-start:0));}

    private void showData(){clear();header("PICK DATA");StringBuilder s=new StringBuilder();for(Models.PickLine l:db.allLines())s.append(l.seq).append(" | ").append(l.orderNo).append(" | ").append(l.branch).append(" | ").append(l.sku).append(" | QTY ").append(l.qty).append(" | ").append(l.location).append(" | Pick ").append(l.picked).append(" | Dmg ").append(l.damage).append(" | NF ").append(l.notFound).append(" | ").append(l.status).append('\n');content.addView(text(s.length()==0?"No data.":s.toString(),13,Color.DKGRAY,false),matchWrap());}
    private void showLog(){clear();header("PICK LOG");content.addView(text(db.dumpTable("pick_log",500),12,Color.DKGRAY,false),matchWrap());}
    private void showExceptions(){clear();header("EXCEPTIONS LOG");content.addView(text(db.dumpTable("exceptions_log",500),12,Color.DKGRAY,false),matchWrap());}
    private void showSettings(){clear();header("SETTINGS");content.addView(text("Mode: Single device / Offline\nDatabase: SQLite\nScanner: Hardware keyboard-wedge barcode reader\nImport: CSV or XLSX first worksheet\nRequired columns: Order Number | Branch | SKU | QTY | Location",15,Color.DKGRAY,false),matchWrap());}
    private void showHelp(){clear();header("HELP");content.addView(text("1. Import a CSV/XLSX pick file.\n2. Press START / RESUME PICKING.\n3. Scan Location.\n4. Scan SKU piece by piece.\n5. Use DAMAGE then scan the damaged SKU.\n6. Use NOT FOUND to classify all remaining units.\n7. The Order Complete screen appears automatically.\n\nData is stored locally on this Android device.",15,Color.DKGRAY,false),matchWrap());}

    private TextView card(LinearLayout parent,String label){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(4,4,4,4);TextView h=text(label,11,Color.WHITE,true);h.setGravity(Gravity.CENTER);h.setBackgroundColor(NAVY);box.addView(h,new LinearLayout.LayoutParams(-1,dp(30)));TextView v=text("-",20,NAVY,true);v.setGravity(Gravity.CENTER);v.setBackgroundColor(PALE);box.addView(v,new LinearLayout.LayoutParams(-1,dp(52)));parent.addView(box,new LinearLayout.LayoutParams(0,dp(90),1));return v;}
    private void summaryCard(LinearLayout p,String label,int value,int color){LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);TextView h=text(label,11,NAVY,true);h.setGravity(Gravity.CENTER);TextView v=text(String.valueOf(value),28,color,true);v.setGravity(Gravity.CENTER);b.addView(h,new LinearLayout.LayoutParams(-1,dp(30)));b.addView(v,new LinearLayout.LayoutParams(-1,dp(62)));p.addView(b,new LinearLayout.LayoutParams(0,dp(96),1));}
    private void addSummary(String k,String v){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);TextView a=text(k,14,NAVY,true);TextView b=text(v,16,Color.BLACK,true);r.addView(a,new LinearLayout.LayoutParams(0,dp(44),1));r.addView(b,new LinearLayout.LayoutParams(0,dp(44),1));content.addView(r,matchWrap());}
    private void setStatus(String msg,int fg,int bg){if(statusText!=null){statusText.setText(msg);statusText.setTextColor(fg);statusText.setBackgroundColor(bg);}}
    private void focusScanner(){if(scanInput!=null){scanInput.requestFocus();scanInput.setSelection(scanInput.length());}}
    private String norm(String s){return s==null?"":s.trim().toUpperCase(Locale.US).replace("\r","").replace("\n","");}
    private String formatElapsed(long ms){long sec=Math.max(0,ms/1000);return String.format(Locale.US,"%02d:%02d:%02d",sec/3600,(sec%3600)/60,sec%60);}
    private String fileName(Uri uri){String result="";try(Cursor c=getContentResolver().query(uri,null,null,null,null)){if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0)result=c.getString(i);}}return result;}
    private void header(String s){TextView h=text(s,20,Color.WHITE,true);h.setGravity(Gravity.CENTER);h.setBackgroundColor(NAVY);h.setPadding(6,12,6,12);content.addView(h,matchWrap());}
    private TextView text(String s,float sp,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setPadding(6,6,6,6);return t;}
    private Button button(String s,int bg){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setTextSize(14);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setBackgroundColor(bg);return b;}
    private LinearLayout.LayoutParams buttonLp(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(58));p.setMargins(0,6,0,6);return p;}
    private LinearLayout.LayoutParams matchWrap(){return new LinearLayout.LayoutParams(-1,-2);}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void alert(String title,String msg){new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK",null).show();}
    private void confirm(String msg,Runnable yes){new AlertDialog.Builder(this).setTitle("Confirm").setMessage(msg).setNegativeButton("Cancel",null).setPositiveButton("OK",(d,w)->yes.run()).show();}
}
