package com.rustnrum.sensorscout;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Integrated sensor diagnostic. Never sends programming/flash/throttle commands. */
public final class ScoutActivity extends Activity {
    private static final int BG = 0xff101b26, PANEL = 0xff1a2b3a, TEAL = 0xff3bd7ca,
        WHITE = 0xfff3f7fc, MUTED = 0xffaabccb, ORANGE = 0xfff8a353;
    private UsbManager manager;
    private TextView status, report;
    private Button scanButton, copyButton, shareButton;
    private JSONObject lastCapture;
    private boolean busy;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        manager=(UsbManager)getSystemService(USB_SERVICE);
        buildUi();
        status.setText(com.rustnrum.cart.MainActivity.scoutConnected() ?
            "Connected through Cart USB session. Ready for read-only scan." :
            "Open Drive, connect the Alltrax controller, then return to Sensors.");
    }

    private TextView text(String s,int size,int color,boolean bold) {
        TextView t=new TextView(this); t.setText(s); t.setTextColor(color); t.setTextSize(size);
        t.setPadding(dp(4),dp(6),dp(4),dp(6));
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }
    private int dp(int d){ return Math.round(getResources().getDisplayMetrics().density*d); }
    private Button button(String s) {
        Button b=new Button(this); b.setText(s); b.setAllCaps(false); b.setTextColor(WHITE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(PANEL));
        return b;
    }
    private void buildUi() {
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG); root.setPadding(dp(14),dp(12),dp(14),dp(12));
        root.addView(text("RUST N RUM · SENSOR SCOUT",22,TEAL,true));
        root.addView(text("ALLTRAX SR / SPM  ·  PASSIVE USB READS ONLY",12,ORANGE,true));
        root.addView(text("Uses the SAME verified USB session as Drive, with read requests queued behind existing telemetry. No separate USB claim, memory sweep, programming, or motor commands.",13,MUTED,false));
        status=text("",14,WHITE,true);root.addView(status);
        scanButton=button("Scan known sensors");
        scanButton.setOnClickListener(v-> requestScan());root.addView(scanButton);
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
        copyButton=button("Copy JSON"); shareButton=button("Share JSON");
        actions.addView(copyButton,new LinearLayout.LayoutParams(0,dp(54),1));
        actions.addView(shareButton,new LinearLayout.LayoutParams(0,dp(54),1));
        root.addView(actions);
        copyButton.setEnabled(false); shareButton.setEnabled(false);
        copyButton.setOnClickListener(v->{
            if(lastCapture==null)return;
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(
                ClipData.newPlainText("RNR sensor scan",lastCapture.toString()));
            status.setText("JSON copied. Paste it into our chat for analysis.");
        });
        shareButton.setOnClickListener(v->{
            if(lastCapture==null)return;
            Intent send=new Intent(Intent.ACTION_SEND); send.setType("application/json");
            send.putExtra(Intent.EXTRA_TEXT,lastCapture.toString());
            startActivity(Intent.createChooser(send,"Share RNR scan JSON"));
        });
        ScrollView scroll=new ScrollView(this);
        report=text("Scan results will appear here. No pedal manipulation required.",14,MUTED,false);
        report.setTextIsSelectable(true);
        scroll.addView(report); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }

    private UsbDevice findDevice(){
        if(manager==null)return null;
        for(UsbDevice device:manager.getDeviceList().values())
            if(device.getVendorId()==HidProtocol.VENDOR && device.getProductId()==HidProtocol.PRODUCT) return device;
        return null;
    }
    private void requestScan(){
        if(busy)return;
        if(!com.rustnrum.cart.MainActivity.scoutConnected()){
            status.setText("Connect the Alltrax controller on the Drive screen first. Sensor Scout reuses that working USB connection.");
            return;
        }
        runScan(findDevice());
    }
    private void runScan(UsbDevice device){
        busy=true;scanButton.setEnabled(false);copyButton.setEnabled(false);shareButton.setEnabled(false);
        lastCapture=null;status.setText("Reading manufacturer-defined sensor blocks…");
        report.setText("Reading known addresses. Unsupported blocks will be marked as failed, not guessed.\n");
        worker.execute(()->{
            try {
                JSONObject capture=performScan(device);
                ui.post(()->{
                    lastCapture=capture;busy=false;scanButton.setEnabled(true);
                    copyButton.setEnabled(true);shareButton.setEnabled(true);
                    status.setText("Scan complete. Copy JSON to share the full result.");
                    report.setText(formatReport(capture));
                });
            }catch(Exception e){
                ui.post(()->{
                    busy=false;scanButton.setEnabled(true);
                    status.setText("Scan failed: " + e.getMessage());
                    report.setText("USB read failure, not a valid sensor result.\n"+e.toString());
                });
            }
        });
    }
    private JSONObject performScan(UsbDevice device) throws Exception {
        JSONObject out=new JSONObject();out.put("format","RNR targeted sensor scout v2");
        out.put("at",System.currentTimeMillis());out.put("model","Alltrax SR/SPM USB PID 0001");
        out.put("vidpid","23d4:0001");out.put("readOnly",true);
        out.put("source","User-supplied Alltrax Toolkit USBPort.dll SPM_Variables map");
        JSONArray blocks=new JSONArray(), measurements=new JSONArray();
        Map<String,byte[]> data=new HashMap<>();
        {
            for(SensorCatalog.Block b:SensorCatalog.BLOCKS){
                JSONObject block=new JSONObject();block.put("name",b.id);
                block.put("address",String.format(Locale.US,"0x%08X",b.address));
                block.put("count",b.length);
                try{
                    byte[] bytes=com.rustnrum.cart.MainActivity.scoutRead(b.address,b.length);
                    data.put(b.id,bytes); block.put("validPacket",true);
                    block.put("hex",HidProtocol.hex(bytes,0,bytes.length));
                }catch(Exception e){block.put("validPacket",false);block.put("error",e.toString());}
                blocks.put(block);
            }
        }
        for(SensorCatalog.Field f:SensorCatalog.FIELDS){
            SensorCatalog.Reading r=SensorCatalog.decode(f,data.get(f.block));
            JSONObject o=new JSONObject();o.put("name",f.name);o.put("block",f.block);
            o.put("status",r.status);o.put("unit",f.unit);o.put("display",r.display());
            if(r.raw!=null)o.put("raw",r.raw);
            if(r.value!=null)o.put("value",r.value);
            long base=0;
            for(SensorCatalog.Block b:SensorCatalog.BLOCKS) if(b.id.equals(f.block))base=b.address;
            o.put("address",String.format(Locale.US,"0x%08X",base+f.offset));
            measurements.put(o);
        }
        out.put("blocks",blocks);out.put("measurements",measurements);
        JSONObject notes=new JSONObject();
        notes.put("batteryCurrent","NOT identified: motor output amps must not be used as battery amps or amp-hours");
        notes.put("speed","Motor speed requires installed speed sensor; otherwise unverified raw");
        notes.put("labels","Plausible means decoded and in a reasonable range, NOT bench-calibrated");
        notes.put("faultHistory","Units/meaning unverified; raw counts only");
        out.put("limitations",notes);
        return out;
    }
    private String formatReport(JSONObject result) {
        StringBuilder s=new StringBuilder();
        JSONArray bs=result.optJSONArray("blocks"), ms=result.optJSONArray("measurements");
        int ok=0,failed=0,plausible=0,invalid=0,raw=0,missing=0;
        if(bs!=null)for(int i=0;i<bs.length();i++)if(bs.optJSONObject(i).optBoolean("validPacket"))ok++;else failed++;
        if(ms!=null)for(int i=0;i<ms.length();i++){
            String status=ms.optJSONObject(i).optString("status");
            if(status.equals("plausible"))plausible++;
            else if(status.equals("invalid-range"))invalid++;
            else if(status.equals("raw-unverified"))raw++;
            else missing++;
        }
        s.append("VALID USB BLOCKS: ").append(ok).append(" / ").append(ok+failed).append('\n');
        s.append("PLAUSIBLE: ").append(plausible).append(" | RAW (unverified): ").append(raw)
          .append(" | INVALID: ").append(invalid).append(" | MISSING: ").append(missing).append("\n\n");
        s.append("CORE TELEMETRY\n");
        if(ms!=null)for(int i=0;i<ms.length();i++){
            JSONObject m=ms.optJSONObject(i);String name=m.optString("name");
            if(name.equals("Battery voltage")||name.equals("Key switch voltage")||name.equals("Motor output current (NOT battery current)")
               ||name.equals("Throttle position")||name.equals("Average B+ temp ADC")||name.equals("Average M- temp ADC") )
                s.append(name).append(": ").append(m.optString("display")).append(" [")
                 .append(m.optString("status")).append("]\n");
        }
        s.append("\nFAULTS (only active / invalid)\n");
        if(ms!=null)for(int i=0;i<ms.length();i++){
            JSONObject m=ms.optJSONObject(i);String name=m.optString("name");
            if(name.startsWith("Fault: ") && (m.optString("display").equals("ON") || m.optString("status").equals("invalid-range")))
                s.append(name).append(": ").append(m.optString("display")).append('\n');
        }
        s.append("\nBLOCK FAILURES\n");
        if(bs!=null)for(int i=0;i<bs.length();i++){
            JSONObject b=bs.optJSONObject(i);
            if(!b.optBoolean("validPacket"))s.append(b.optString("name")).append(": ").append(b.optString("error")).append('\n');
        }
        s.append("\nALL MEASUREMENTS\n");
        if(ms!=null)for(int i=0;i<ms.length();i++){
            JSONObject m=ms.optJSONObject(i);
            s.append(m.optString("address")).append(" · ").append(m.optString("name"))
                .append(" = ").append(m.optString("display")).append(" [")
                .append(m.optString("status")).append("]\n");
        }
        s.append("\nBattery AH/health cannot be calculated from motor current.\n");
        return s.toString();
    }

    @Override public void onDestroy(){
        worker.shutdownNow();super.onDestroy();
    }
}
