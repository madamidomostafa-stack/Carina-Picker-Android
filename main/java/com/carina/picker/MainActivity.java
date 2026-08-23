package com.carina.picker;

import android.media.MediaPlayer;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.FrameLayout;
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
    private FrameLayout body;
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
        root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        TextView title=text("CARINA PICKER - ANDROID",17,Color.WHITE,true);
        title.setGravity(Gravity.CENTER);
        title.setBackgroundColor(NAVY);
        root.addView(title,new LinearLayout.LayoutParams(-1,dp(36)));

        HorizontalScrollView hsv=new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout nav=new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(dp(2),0,dp(2),0);
        addNav(nav,"CONTROL",v->showControl());
        addNav(nav,"PICKING",v->showPicking());
        addNav(nav,"PICK DATA",v->showData());
        addNav(nav,"PICK LOG",v->showLog());
        addNav(nav,"EXCEPTIONS",v->showExceptions());
        addNav(nav,"SETTINGS",v->showSettings());
        addNav(nav,"HELP",v->showHelp());
        hsv.addView(nav);
        root.addView(hsv,new LinearLayout.LayoutParams(-1,dp(32)));

        body=new FrameLayout(this);
        root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        clear();
    }

    private void addNav(LinearLayout nav,String label,View.OnClickListener l){
        Button b=button(label,NAVY);
        b.setTextSize(10);
        b.setPadding(dp(1),0,dp(1),0);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setOnClickListener(l);
        nav.addView(b,new LinearLayout.LayoutParams(dp(88),dp(32)));
    }
    private void clear(){
        body.removeAllViews();
        ScrollView sv=new ScrollView(this);
        sv.setFillViewport(true);
        content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(5),dp(4),dp(5),dp(4));
        sv.addView(content,new ScrollView.LayoutParams(-1,-2));
        body.addView(sv,new FrameLayout.LayoutParams(-1,-1));
    }

    private void clearFixed(){
        body.removeAllViews();
        content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(4),dp(3),dp(4),dp(3));
        body.addView(content,new FrameLayout.LayoutParams(-1,-1));
    }

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
        clearFixed();

        TextView pickTitle=text("PICKER - ORDER PICKING",14,Color.WHITE,true);
        pickTitle.setGravity(Gravity.CENTER);
        pickTitle.setBackgroundColor(NAVY);
        content.addView(pickTitle,weighted(0.065f));

        LinearLayout identityRow=new LinearLayout(this);
        identityRow.setOrientation(LinearLayout.HORIZONTAL);
        orderText=responsiveCard(identityRow,"ORDER NUMBER",0.30f,16,NAVY);
        branchText=responsiveCard(identityRow,"BRANCH",0.40f,15,NAVY);
        progressText=responsiveCard(identityRow,"ORDER PROGRESS",0.30f,14,NAVY);
        content.addView(identityRow,weighted(0.125f));

        LinearLayout infoRow=new LinearLayout(this);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        elapsedText=responsiveMetric(infoRow,"ELAPSED",0.62f);
        lineText=responsiveMetric(infoRow,"LINE",0.38f);
        content.addView(infoRow,weighted(0.055f));

        locationText=responsiveFullCard("NEXT LOCATION",18,Color.rgb(255,244,204),0.110f);
        skuText=responsiveFullCard("CURRENT SKU",18,PALE,0.110f);

        LinearLayout qtyRow=new LinearLayout(this);
        qtyRow.setOrientation(LinearLayout.HORIZONTAL);
        reqText=responsiveQty(qtyRow,"REQUIRED",Color.rgb(30,80,170));
        pickedText=responsiveQty(qtyRow,"SCANNED",GREEN);
        damageText=responsiveQty(qtyRow,"DAMAGE",RED);
        nfText=responsiveQty(qtyRow,"NOT FOUND",ORANGE);
        content.addView(qtyRow,weighted(0.105f));

        LinearLayout scanBox=new LinearLayout(this);
        scanBox.setOrientation(LinearLayout.VERTICAL);

        TextView scanLabel=text("SCAN BARCODE HERE",12,Color.rgb(90,50,0),true);
        scanLabel.setGravity(Gravity.CENTER);
        scanLabel.setBackgroundColor(Color.rgb(245,185,115));
        scanBox.addView(scanLabel,new LinearLayout.LayoutParams(-1,0,0.34f));

        scanInput=new EditText(this);
        scanInput.setSingleLine(true);
        scanInput.setTextSize(17);
        scanInput.setGravity(Gravity.CENTER);
        scanInput.setBackgroundColor(Color.rgb(255,248,220));
        scanInput.setShowSoftInputOnFocus(false);
        scanInput.setPadding(dp(3),0,dp(3),0);
        scanInput.setMinHeight(0); scanInput.setMinimumHeight(0);
        scanBox.addView(scanInput,new LinearLayout.LayoutParams(-1,0,0.66f));
        content.addView(scanBox,weighted(0.120f));

        scanInput.setOnEditorActionListener((v,a,e)->{
            if(a==EditorInfo.IME_ACTION_DONE || (e!=null&&e.getKeyCode()==KeyEvent.KEYCODE_ENTER)){
                processScan(); return true;
            }
            return false;
        });
        scanInput.setOnKeyListener((v,key,event)->{
            if(key==KeyEvent.KEYCODE_ENTER&&event.getAction()==KeyEvent.ACTION_UP){
                processScan(); return true;
            }
            return false;
        });

        statusText=text("PRESS START PICKING",12,NAVY,true);
        statusText.setGravity(Gravity.CENTER);
        statusText.setSingleLine(true);
        statusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusText.setBackgroundColor(PALE);
        content.addView(statusText,weighted(0.060f));

        LinearLayout actions=new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button nf=compactAction("NOT FOUND",ORANGE);
        nf.setOnClickListener(v->notFound());
        Button dmg=compactAction("DAMAGE",RED);
        dmg.setOnClickListener(v->beginDamage());
        actions.addView(nf,new LinearLayout.LayoutParams(0,-1,1));
        actions.addView(dmg,new LinearLayout.LayoutParams(0,-1,1));
        content.addView(actions,weighted(0.070f));

        Button reset=compactAction("RESET SESSION",Color.DKGRAY);
        reset.setOnClickListener(v->confirm("Reset current picking session?",()->{
            db.resetSession(); current=null; showControl();
        }));
        content.addView(reset,weighted(0.075f));

        // Safety margin for Android navigation/status areas on 480x800 devices.
        content.addView(new android.view.View(this),weighted(0.105f));

        long id=db.getSettingLong("current_id");
        if(id>0){
            Models.PickLine x=db.line(id);
            if(x!=null&&!x.complete())current=x;
        }
        if(current!=null){
            renderCurrent();
            setStatus(currentGuidance(),NAVY,PALE);
        }else{
            setStatus("PRESS START PICKING",NAVY,PALE);
        }
        focusScanner();
    }

    private LinearLayout.LayoutParams weighted(float weight){
        return new LinearLayout.LayoutParams(-1,0,weight);
    }

    private TextView responsiveCard(LinearLayout parent,String label,float weight,float valueSp,int valueColor){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView h=text(label,8,Color.WHITE,true);
        h.setGravity(Gravity.CENTER);
        h.setBackgroundColor(NAVY);
        h.setSingleLine(true);
        TextView v=text("-",valueSp,valueColor,true);
        v.setGravity(Gravity.CENTER);
        v.setBackgroundColor(Color.rgb(241,246,252));
        v.setMaxLines(2);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        box.addView(h,new LinearLayout.LayoutParams(-1,0,0.34f));
        box.addView(v,new LinearLayout.LayoutParams(-1,0,0.66f));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,weight);
        lp.setMargins(dp(1),dp(1),dp(1),dp(1));
        parent.addView(box,lp);
        return v;
    }

    private TextView responsiveMetric(LinearLayout parent,String label,float weight){
        TextView v=text(label+": -",10,NAVY,true);
        v.setGravity(Gravity.CENTER);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setBackgroundColor(Color.rgb(246,249,252));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,weight);
        lp.setMargins(dp(1),dp(1),dp(1),dp(1));
        parent.addView(v,lp);
        return v;
    }

    private TextView responsiveFullCard(String label,float valueSp,int bg,float weight){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView h=text(label,8,Color.WHITE,true);
        h.setGravity(Gravity.CENTER);
        h.setSingleLine(true);
        h.setPadding(dp(2),0,dp(2),0);
        h.setBackgroundColor(NAVY);

        TextView v=text("-",valueSp,NAVY,true);
        v.setGravity(Gravity.CENTER);
        v.setSingleLine(true);
        v.setPadding(dp(3),0,dp(3),0);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setBackgroundColor(bg);

        box.addView(h,new LinearLayout.LayoutParams(-1,0,0.40f));
        box.addView(v,new LinearLayout.LayoutParams(-1,0,0.60f));
        content.addView(box,weighted(weight));
        return v;
    }

    private TextView responsiveQty(LinearLayout parent,String label,int color){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView h=text(label,8,Color.WHITE,true);
        h.setGravity(Gravity.CENTER);
        h.setBackgroundColor(NAVY);
        h.setMaxLines(2);
        TextView v=text("-",16,color,true);
        v.setGravity(Gravity.CENTER);
        v.setBackgroundColor(Color.rgb(246,249,252));
        box.addView(h,new LinearLayout.LayoutParams(-1,0,0.43f));
        box.addView(v,new LinearLayout.LayoutParams(-1,0,0.57f));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);
        lp.setMargins(dp(1),dp(1),dp(1),dp(1));
        parent.addView(box,lp);
        return v;
    }

    private Button compactAction(String label,int color){
        Button b=button(label,color);
        b.setTextSize(10);
        b.setPadding(dp(2),0,dp(2),0);
        b.setMinHeight(0); b.setMinimumHeight(0);
        return b;
    }

    private void fitIdentifier(TextView v,String value,int largeSp,int mediumSp,int smallSp){
        if(v==null)return;
        String s=value==null?"":value;
        int n=s.length();
        int size=n<=12?largeSp:(n<=18?mediumSp:smallSp);
        v.setTextSize(size);
        v.setText(s);
    }

    private String currentGuidance(){
        if(current==null || !"YES".equalsIgnoreCase(db.getSetting("picker_started"))){
            return "PRESS START PICKING";
        }
        if(!"YES".equalsIgnoreCase(db.getSetting("location_confirmed"))){
            return "SCAN LOCATION";
        }
        return "SCAN SKU";
    }

    private void processScan(){
        if(scanInput==null)return; String scanned=scanInput.getText().toString().trim();scanInput.setText(""); if(scanned.isEmpty())return;
        if(current==null || !"YES".equalsIgnoreCase(db.getSetting("picker_started"))){setStatus("PRESS START PICKING FIRST",NAVY,PALE);return;}
        if(damageScanMode){processDamageScan(scanned);return;}
        if(!"YES".equalsIgnoreCase(db.getSetting("location_confirmed"))){
            if(norm(scanned).equals(norm(current.location))){db.setSetting("location_confirmed","YES");db.writeLog(current,"LOCATION CONFIRMED",0);playScanOk();setStatus("SCAN SKU",GREEN,Color.rgb(226,244,229));}
            else {playScanError();setStatus("WRONG LOCATION - Expected " + current.location,RED,Color.rgb(255,230,233));} focusScanner();return;
        }
        if(!norm(scanned).equals(norm(current.sku))){playScanError();setStatus("WRONG SKU - Expected " + current.sku,RED,Color.rgb(255,230,233));focusScanner();return;}
        db.incrementPicked(current.id); current=db.line(current.id); db.writeLog(current,"SKU PICK",1); playScanOk();
        if(current.complete()) advanceAfterLine(); else {renderCurrent();setStatus("SCAN OK - Classified " + current.classified()+" / "+current.qty,GREEN,Color.rgb(226,244,229));focusScanner();}
    }

    private void beginDamage(){
        if(current==null||!"YES".equalsIgnoreCase(db.getSetting("location_confirmed"))){toast("Confirm Location first.");return;}
        if(current.remaining()<=0)return; damageScanMode=true; setStatus("DAMAGE MODE - Scan damaged SKU",ORANGE,Color.rgb(255,238,221));focusScanner();
    }
    private void processDamageScan(String s){
        damageScanMode=false; if(!norm(s).equals(norm(current.sku))){playScanError();setStatus("WRONG SKU - Damage not recorded",RED,Color.rgb(255,230,233));focusScanner();return;}
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
        if(same!=null){loadLine(same,false);renderCurrent();setStatus(currentGuidance(),NAVY,PALE);focusScanner();return;}
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
        long start=db.getSettingLong("order_start_ms");
        long elapsed=start>0?System.currentTimeMillis()-start:0;
        Models.OrderSummary s=db.summary(orderNo,elapsed);

        current=null;
        playOrderComplete();
        clearFixed();

        TextView title=text("ORDER COMPLETE",16,Color.WHITE,true);
        title.setGravity(Gravity.CENTER);
        title.setBackgroundColor(NAVY);
        content.addView(title,weighted(0.070f));

        TextView success=text("ORDER COMPLETED SUCCESSFULLY!",13,GREEN,true);
        success.setGravity(Gravity.CENTER);
        success.setSingleLine(true);
        success.setBackgroundColor(Color.rgb(238,250,240));
        content.addView(success,weighted(0.065f));

        LinearLayout idRow=new LinearLayout(this);
        idRow.setOrientation(LinearLayout.HORIZONTAL);
        completeInfoCard(idRow,"ORDER NUMBER",s.orderNo,0.32f,14,NAVY);
        completeInfoCard(idRow,"BRANCH",s.branch,0.43f,13,NAVY);
        completeInfoCard(idRow,"PICKER","Mohamed Mostafa",0.25f,11,NAVY);
        content.addView(idRow,weighted(0.130f));

        LinearLayout qtyRow=new LinearLayout(this);
        qtyRow.setOrientation(LinearLayout.HORIZONTAL);
        completeQtyCard(qtyRow,"ORDER QTY",String.valueOf(s.required),Color.rgb(30,80,170));
        completeQtyCard(qtyRow,"PICKED",String.valueOf(s.picked),GREEN);
        completeQtyCard(qtyRow,"DAMAGE",String.valueOf(s.damage),ORANGE);
        completeQtyCard(qtyRow,"NOT FOUND",String.valueOf(s.notFound),RED);
        completeQtyCard(qtyRow,"COMPLETION",s.completionPct()+"%",GREEN);
        content.addView(qtyRow,weighted(0.155f));

        LinearLayout statusRow=new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        completeInfoCard(statusRow,"STATUS",
                (s.damage==0 && s.notFound==0)?"COMPLETED":"WITH EXCEPTIONS",
                0.52f,13,(s.damage==0 && s.notFound==0)?GREEN:ORANGE);
        completeInfoCard(statusRow,"COMPLETION TIME",formatElapsed(s.elapsedMs),
                0.48f,13,Color.rgb(30,80,170));
        content.addView(statusRow,weighted(0.110f));

        LinearLayout detailRow=new LinearLayout(this);
        detailRow.setOrientation(LinearLayout.HORIZONTAL);
        completeInfoCard(detailRow,"TOTAL LINES",s.doneLines+" / "+s.totalLines,0.34f,12,NAVY);
        completeInfoCard(detailRow,"PICKED QTY",String.valueOf(s.picked),0.33f,12,GREEN);
        completeInfoCard(detailRow,"EXCEPTIONS",String.valueOf(s.damage+s.notFound),0.33f,12,RED);
        content.addView(detailRow,weighted(0.100f));

        TextView msg=text(
                (s.damage==0 && s.notFound==0)
                        ?"COMPLETED SUCCESSFULLY - ALL PIECES PICKED"
                        :"COMPLETED WITH EXCEPTIONS",
                11,
                (s.damage==0 && s.notFound==0)?GREEN:ORANGE,
                true);
        msg.setGravity(Gravity.CENTER);
        msg.setSingleLine(true);
        msg.setEllipsize(android.text.TextUtils.TruncateAt.END);
        msg.setBackgroundColor(Color.rgb(242,248,242));
        content.addView(msg,weighted(0.075f));

        Button done=compactAction("OK",GREEN);
        done.setTextSize(14);
        done.setOnClickListener(v->{
            Models.PickLine next=db.firstOpenExcludingOrder(orderNo);
            if(next!=null){
                db.setSetting("location_confirmed","NO");
                db.setSetting("current_location","");
                loadLine(next,false);
                showPicking();
            }else{
                finishAll();
            }
        });
        content.addView(done,weighted(0.095f));

        // ORDER COMPLETE bottom safety margin for 480x800 handhelds.
        content.addView(new android.view.View(this),weighted(0.200f));
    }

    private TextView completeInfoCard(LinearLayout parent,String label,String value,float weight,float valueSp,int valueColor){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView h=text(label,7,Color.WHITE,true);
        h.setGravity(Gravity.CENTER);
        h.setSingleLine(true);
        h.setPadding(dp(1),0,dp(1),0);
        h.setBackgroundColor(NAVY);

        TextView v=text(value==null?"":value,valueSp,valueColor,true);
        v.setGravity(Gravity.CENTER);
        v.setMaxLines(2);
        v.setPadding(dp(2),0,dp(2),0);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setBackgroundColor(Color.rgb(246,249,252));

        box.addView(h,new LinearLayout.LayoutParams(-1,0,0.34f));
        box.addView(v,new LinearLayout.LayoutParams(-1,0,0.66f));

        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,weight);
        lp.setMargins(dp(1),dp(1),dp(1),dp(1));
        parent.addView(box,lp);
        return v;
    }

    private void completeQtyCard(LinearLayout parent,String label,String value,int valueColor){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView h=text(label,7,Color.WHITE,true);
        h.setGravity(Gravity.CENTER);
        h.setMaxLines(2);
        h.setPadding(dp(1),0,dp(1),0);
        h.setBackgroundColor(NAVY);

        TextView v=text(value,14,valueColor,true);
        v.setGravity(Gravity.CENTER);
        v.setSingleLine(true);
        v.setBackgroundColor(Color.rgb(246,249,252));

        box.addView(h,new LinearLayout.LayoutParams(-1,0,0.42f));
        box.addView(v,new LinearLayout.LayoutParams(-1,0,0.58f));

        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);
        lp.setMargins(dp(1),dp(1),dp(1),dp(1));
        parent.addView(box,lp);
    }
    private void finishAll(){db.resetSession();current=null;clear();header("PICKING COMPLETE");TextView t=text("ALL PICKING COMPLETED\nNo open orders remaining.",22,GREEN,true);t.setGravity(Gravity.CENTER);t.setPadding(10,40,10,40);content.addView(t,matchWrap());Button b=button("CONTROL",NAVY);b.setOnClickListener(v->showControl());content.addView(b,buttonLp());}

    private void renderCurrent(){
        if(current==null)return;
        fitIdentifier(orderText,current.orderNo,16,14,12);
        fitIdentifier(branchText,current.branch,15,13,11);
        fitIdentifier(locationText,current.location,19,17,14);
        fitIdentifier(skuText,current.sku,19,16,13);
        if(reqText!=null)reqText.setText(String.valueOf(current.qty));
        if(pickedText!=null)pickedText.setText(String.valueOf(current.picked));
        if(damageText!=null)damageText.setText(String.valueOf(current.damage));
        if(nfText!=null)nfText.setText(String.valueOf(current.notFound));
        int[] p=orderProgress(current.orderNo);
        if(progressText!=null)progressText.setText(p[0]+" / "+p[1]+" | "+(p[1]==0?0:Math.round(p[0]*100f/p[1]))+"%");
        if(lineText!=null)lineText.setText("LINE "+current.seq);
        updateElapsed();
    }
    private int[] orderProgress(String orderNo){int done=0,total=0;for(Models.PickLine l:db.allLines())if(l.orderNo.equals(orderNo)){total++;if(l.complete())done++;}return new int[]{done,total};}
    private void updateElapsed(){if(elapsedText==null)return;long start=db.getSettingLong("order_start_ms");elapsedText.setText("ELAPSED: "+formatElapsed(start>0?System.currentTimeMillis()-start:0));}

    private void showData(){
        clear(); header("PICK DATA");
        List<String[]> rows=db.pickDataRows();
        showDataTable(
                new String[]{"LINE","ORDER NUMBER","BRANCH","LOCATION","SKU","REQUIRED","PICKED","DAMAGE","NOT FOUND","STATUS"},
                new int[]{54,112,145,105,145,72,68,68,78,190},
                rows,
                "PICK DATA"
        );
    }

    private void showLog(){
        clear(); header("PICK LOG");
        List<String[]> rows=db.pickLogRows(1000);
        showDataTable(
                new String[]{"DATE","TIME","ORDER","BRANCH","LOCATION","SKU","ACTION","QTY","REQUIRED","PICKED","DAMAGE","NOT FOUND","STATUS"},
                new int[]{88,92,105,145,105,145,120,54,72,68,68,78,190},
                rows,
                "PICK LOG"
        );
    }

    private void showExceptions(){
        clear(); header("EXCEPTIONS LOG");
        List<String[]> rows=db.exceptionRows(1000);
        showDataTable(
                new String[]{"DATE","TIME","ORDER","BRANCH","LOCATION","SKU","REQUIRED","PICKED","DAMAGE","NOT FOUND","EXCEPTION QTY","REASON","FINAL STATUS","LINE"},
                new int[]{88,92,105,145,105,145,72,68,68,78,92,100,190,54},
                rows,
                "EXCEPTIONS LOG"
        );
    }

    private void showDataTable(String[] headers,int[] widths,List<String[]> rows,String tableName){
        TextView count=text("RECORDS: " + rows.size(),11,NAVY,true);
        count.setGravity(Gravity.CENTER_VERTICAL);
        count.setBackgroundColor(Color.rgb(238,244,251));
        count.setPadding(8,5,8,5);
        content.addView(count,new LinearLayout.LayoutParams(-1,dp(30)));

        if(rows.isEmpty()){
            TextView empty=text("No data recorded yet.",15,Color.DKGRAY,true);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(8,30,8,30);
            content.addView(empty,matchWrap());
            return;
        }

        HorizontalScrollView horizontal=new HorizontalScrollView(this);
        horizontal.setFillViewport(false);
        horizontal.setHorizontalScrollBarEnabled(true);

        LinearLayout table=new LinearLayout(this);
        table.setOrientation(LinearLayout.VERTICAL);

        table.addView(tableRow(headers,widths,true,0));

        int rowIndex=0;
        for(String[] row:rows){
            table.addView(tableRow(row,widths,false,rowIndex++));
        }

        horizontal.addView(table,new HorizontalScrollView.LayoutParams(-2,-2));
        content.addView(horizontal,matchWrap());
    }

    private LinearLayout tableRow(String[] values,int[] widths,boolean headerRow,int rowIndex){
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        for(int i=0;i<widths.length;i++){
            String value=(values!=null && i<values.length && values[i]!=null)?values[i]:"";
            int bg;
            int fg;
            boolean bold=headerRow;

            if(headerRow){
                bg=NAVY; fg=Color.WHITE;
            }else{
                bg=(rowIndex%2==0)?Color.WHITE:Color.rgb(246,249,252);
                fg=Color.rgb(25,35,45);
            }

            TextView cell=tableCell(value,widths[i],bg,fg,bold);
            row.addView(cell,new LinearLayout.LayoutParams(dp(widths[i]),dp(headerRow?36:38)));
        }
        return row;
    }

    private TextView tableCell(String value,int widthDp,int bgColor,int textColor,boolean bold){
        TextView cell=text(value,bold?10:10,textColor,bold);
        cell.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
        cell.setSingleLine(true);
        cell.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cell.setPadding(dp(4),dp(2),dp(4),dp(2));

        GradientDrawable border=new GradientDrawable();
        border.setColor(bgColor);
        border.setStroke(dp(1),Color.rgb(185,195,205));
        cell.setBackground(border);

        if(android.os.Build.VERSION.SDK_INT>=26){
            cell.setAutoSizeTextTypeUniformWithConfiguration(8,bold?11:10,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        }
        return cell;
    }

    private void testSounds(){
        playScanOk();
        new android.os.Handler().postDelayed(this::playScanError,500);
        new android.os.Handler().postDelayed(this::playOrderComplete,1200);
    }

    private void showSettings(){clear();header("SETTINGS");content.addView(text("Mode: Single device / Offline\nDatabase: SQLite\nScanner: Hardware keyboard-wedge barcode reader\nImport: CSV or XLSX first worksheet\nRequired columns: Order Number | Branch | SKU | QTY | Location",15,Color.DKGRAY,false),matchWrap());}
    private void showHelp(){clear();header("HELP");content.addView(text("1. Import a CSV/XLSX pick file.\n2. Press START / RESUME PICKING.\n3. Scan Location.\n4. Scan SKU piece by piece.\n5. Use DAMAGE then scan the damaged SKU.\n6. Use NOT FOUND to classify all remaining units.\n7. The Order Complete screen appears automatically.\n\nData is stored locally on this Android device.",15,Color.DKGRAY,false),matchWrap());}

    private void compactHeader(String s){
        TextView h=text(s,16,Color.WHITE,true); h.setGravity(Gravity.CENTER); h.setBackgroundColor(NAVY); h.setPadding(4,7,4,7);
        content.addView(h,new LinearLayout.LayoutParams(-1,dp(38)));

        Button testSound=button("TEST SOUNDS",NAVY);
        testSound.setOnClickListener(v->testSounds());
        content.addView(testSound,buttonLp());
    }

    private TextView handheldCard(LinearLayout parent,String label,float weight,float valueSize,int maxLines){
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(2,2,2,2);
        TextView h=text(label,9,Color.WHITE,true); h.setGravity(Gravity.CENTER); h.setBackgroundColor(NAVY); h.setMaxLines(1);
        box.addView(h,new LinearLayout.LayoutParams(-1,dp(24)));
        TextView v=text("-",valueSize,NAVY,true); v.setGravity(Gravity.CENTER); v.setBackgroundColor(PALE); v.setMaxLines(maxLines); v.setEllipsize(android.text.TextUtils.TruncateAt.END); v.setPadding(3,2,3,2);
        if(android.os.Build.VERSION.SDK_INT>=26) v.setAutoSizeTextTypeUniformWithConfiguration(12,(int)valueSize,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        box.addView(v,new LinearLayout.LayoutParams(-1,dp(52)));
        parent.addView(box,new LinearLayout.LayoutParams(0,dp(80),weight));
        return v;
    }

    private TextView compactMetric(LinearLayout parent,String label,float weight){
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(1,1,1,1);
        TextView h=text(label,8,NAVY,true); h.setGravity(Gravity.CENTER); h.setMaxLines(1);
        TextView v=text("-",13,NAVY,true); v.setGravity(Gravity.CENTER); v.setMaxLines(1);
        if(android.os.Build.VERSION.SDK_INT>=26) v.setAutoSizeTextTypeUniformWithConfiguration(9,14,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        box.addView(h,new LinearLayout.LayoutParams(-1,dp(20))); box.addView(v,new LinearLayout.LayoutParams(-1,dp(28)));
        parent.addView(box,new LinearLayout.LayoutParams(0,dp(50),weight));
        return v;
    }

    private TextView fullWidthScanCard(String label,float valueSize,int background){
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(2,2,2,2);
        TextView h=text(label,10,Color.WHITE,true); h.setGravity(Gravity.CENTER); h.setBackgroundColor(NAVY); h.setMaxLines(1);
        TextView v=text("-",valueSize,NAVY,true); v.setGravity(Gravity.CENTER); v.setBackgroundColor(background); v.setMaxLines(1); v.setEllipsize(android.text.TextUtils.TruncateAt.END); v.setPadding(4,1,4,1);
        if(android.os.Build.VERSION.SDK_INT>=26) v.setAutoSizeTextTypeUniformWithConfiguration(15,(int)valueSize,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        box.addView(h,new LinearLayout.LayoutParams(-1,dp(24))); box.addView(v,new LinearLayout.LayoutParams(-1,dp(46)));
        content.addView(box,new LinearLayout.LayoutParams(-1,dp(74)));
        return v;
    }

    private TextView quantityCard(LinearLayout parent,String label,int valueColor){
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(1,1,1,1);
        TextView h=text(label,8,Color.WHITE,true); h.setGravity(Gravity.CENTER); h.setBackgroundColor(NAVY); h.setMaxLines(1);
        if(android.os.Build.VERSION.SDK_INT>=26) h.setAutoSizeTextTypeUniformWithConfiguration(6,9,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        TextView v=text("0",22,valueColor,true); v.setGravity(Gravity.CENTER); v.setBackgroundColor(Color.WHITE); v.setMaxLines(1);
        box.addView(h,new LinearLayout.LayoutParams(-1,dp(24))); box.addView(v,new LinearLayout.LayoutParams(-1,dp(44)));
        parent.addView(box,new LinearLayout.LayoutParams(0,dp(70),1));
        return v;
    }

    private TextView card(LinearLayout parent,String label){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(4,4,4,4);TextView h=text(label,11,Color.WHITE,true);h.setGravity(Gravity.CENTER);h.setBackgroundColor(NAVY);box.addView(h,new LinearLayout.LayoutParams(-1,dp(30)));TextView v=text("-",20,NAVY,true);v.setGravity(Gravity.CENTER);v.setBackgroundColor(PALE);box.addView(v,new LinearLayout.LayoutParams(-1,dp(52)));parent.addView(box,new LinearLayout.LayoutParams(0,dp(90),1));return v;}
    private void summaryCard(LinearLayout p,String label,int value,int color){LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);TextView h=text(label,11,NAVY,true);h.setGravity(Gravity.CENTER);TextView v=text(String.valueOf(value),28,color,true);v.setGravity(Gravity.CENTER);b.addView(h,new LinearLayout.LayoutParams(-1,dp(30)));b.addView(v,new LinearLayout.LayoutParams(-1,dp(62)));p.addView(b,new LinearLayout.LayoutParams(0,dp(96),1));}
    private void addSummary(String k,String v){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);TextView a=text(k,14,NAVY,true);TextView b=text(v,16,Color.BLACK,true);r.addView(a,new LinearLayout.LayoutParams(0,dp(44),1));r.addView(b,new LinearLayout.LayoutParams(0,dp(44),1));content.addView(r,matchWrap());}
    private void playAppSound(int resId){
        try{
            final MediaPlayer mp=MediaPlayer.create(this,resId);
            if(mp==null)return;

            mp.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
            mp.setVolume(1.0f,1.0f);

            mp.setOnCompletionListener(x->{
                try{x.release();}catch(Exception ignored){}
            });
            mp.setOnErrorListener((x,w,e)->{
                try{x.release();}catch(Exception ignored){}
                return true;
            });

            mp.start();
        }catch(Exception ignored){}
    }
    private void playScanOk(){playAppSound(R.raw.scan_ok);}
    private void playScanError(){playAppSound(R.raw.scan_error);}
    private void playOrderComplete(){playAppSound(R.raw.order_complete);}

    private void setStatus(String msg,int fg,int bg){if(statusText!=null){statusText.setText(msg);statusText.setTextColor(fg);statusText.setBackgroundColor(bg);}}
    private void focusScanner(){if(scanInput!=null){scanInput.requestFocus();scanInput.setSelection(scanInput.length());}}
    private String norm(String s){return s==null?"":s.trim().toUpperCase(Locale.US).replace("\r","").replace("\n","");}
    private String formatElapsed(long ms){long sec=Math.max(0,ms/1000);return String.format(Locale.US,"%02d:%02d:%02d",sec/3600,(sec%3600)/60,sec%60);}
    private String fileName(Uri uri){String result="";try(Cursor c=getContentResolver().query(uri,null,null,null,null)){if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0)result=c.getString(i);}}return result;}
    private void header(String s){TextView h=text(s,18,Color.WHITE,true);h.setGravity(Gravity.CENTER);h.setBackgroundColor(NAVY);h.setPadding(5,9,5,9);content.addView(h,matchWrap());}
    private TextView text(String s,float sp,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setPadding(6,6,6,6);return t;}
    private Button button(String s,int bg){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setTextSize(14);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setBackgroundColor(bg);return b;}
    private LinearLayout.LayoutParams buttonLp(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(58));p.setMargins(0,6,0,6);return p;}
    private LinearLayout.LayoutParams matchWrap(){return new LinearLayout.LayoutParams(-1,-2);}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void alert(String title,String msg){new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK",null).show();}
    private void confirm(String msg,Runnable yes){new AlertDialog.Builder(this).setTitle("Confirm").setMessage(msg).setNegativeButton("Cancel",null).setPositiveButton("OK",(d,w)->yes.run()).show();}
}
