package com.rustnrum.sensorscout;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.os.Build;
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

import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

/** Integrated sensor diagnostic. Never sends programming/flash/throttle commands. */
public final class ScoutActivity extends Activity {
    private static final String USB_PERMISSION = "com.rustnrum.sensorscout.USB_PERMISSION";
    private static final int BG = 0xff101b26, PANEL = 0xff1a2b3a, TEAL = 0xff3bd7ca,
        WHITE = 0xfff3f7fc, MUTED = 0xffaabccb, ORANGE = 0xfff8a353;
    private UsbManager manager;
    private TextView status, report;
    private Button scanButton, copyButton, shareButton;
    private JSONObject lastCapture;
    private boolean busy;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if(!USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device;
            if(Build.VERSION.SDK_INT>=33) device=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
            else device=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false) && device!=null) runScan(device);
            else status.setText("USB permission denied. No scan performed.");
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        manager=(UsbManager)getSystemService(USB_SERVICE);
        if(Build.VERSION.SDK_INT>=33) registerReceiver(permissionReceiver,new IntentFilter(USB_PERMISSION),Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(permissionReceiver,new IntentFilter(USB_PERMISSION));
        buildUi();
        status.setText(findDevice()!=null ? "Alltrax 23D4:0001 found. Ready for read-only scan." :
            "No Alltrax SR/SPM 23D4:0001 connected over USB. Connect it and press Scan.");
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
        root.addView(text("Uses named registers from your Alltrax USBPort.dll. Rejects invalid packets and impossible sensor values. No memory sweep, programming, or motor commands.",13,MUTED,false));
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
        UsbDevice d=findDevice();
        if(d==null){status.setText("No 23D4:0001 USB device detected. Connect controller first.");return;}
        if(manager.hasPermission(d)){runScan(d);return;}
        Intent i=new Intent(USB_PERMISSION).setPackage(getPackageName());
        PendingIntent pending=PendingIntent.getBroadcast(this,0,i,PendingIntent.FLAG_MUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        status.setText("Authorize USB access to read Alltrax data.");
        manager.requestPermission(d,pending);
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
        try(Transport t=new Transport(manager,device)){
            for(SensorCatalog.Block b:SensorCatalog.BLOCKS){
                JSONObject block=new JSONObject();block.put("name",b.id);
                block.put("address",String.format(Locale.US,"0x%08X",b.address));
                block.put("count",b.length);
                try{
                    byte[] bytes=t.read(b.address,b.length);
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

    /** Supports HID SET_REPORT to output report 1, receives HID input report 4. */
    private static final class Transport implements AutoCloseable {
        private final UsbDeviceConnection conn; private final UsbInterface iface; private final UsbEndpoint in;
        private Transport(UsbManager manager, UsbDevice device) {
            UsbInterface chosen=null;UsbEndpoint ep=null;
            for(int i=0;i<device.getInterfaceCount();i++){
                UsbInterface curr=device.getInterface(i);
                for(int j=0;j<curr.getEndpointCount();j++){
                    UsbEndpoint e=curr.getEndpoint(j);
                    if(e.getDirection()==UsbConstants.USB_DIR_IN && e.getType()==UsbConstants.USB_ENDPOINT_XFER_INT){
                        chosen=curr;ep=e;break;
                    }
                }
                if(chosen!=null)break;
            }
            if(chosen==null)throw new IllegalStateException("No USB HID interrupt-IN interface found");
            UsbDeviceConnection c=manager.openDevice(device);
            if(c==null)throw new IllegalStateException("Cannot open USB device");
            if(!c.claimInterface(chosen,true)){c.close();throw new IllegalStateException("Cannot claim USB interface");}
            conn=c;iface=chosen;in=ep;
        }
        byte[] read(long address, int count) throws Exception {
            byte[] request=HidProtocol.readRequest(address,count);
            // Queue input BEFORE output so fast controllers cannot outrun the receive listener.
            UsbRequest input=new UsbRequest();
            if(!input.initialize(conn,in))throw new IllegalStateException("USB input request init failed");
            try{
                ByteBuffer buffer=ByteBuffer.allocateDirect(HidProtocol.REPORT_SIZE+1);
                if(!input.queue(buffer))throw new IllegalStateException("USB input queue failed");
                // HID SET_REPORT, output report type (2), report ID (1). NOT a programmer command.
                int written=conn.controlTransfer(0x21,0x09,0x0201,iface.getId(),
                    request,request.length,1300);
                if(written!=request.length)
                    throw new IllegalStateException("Output report transport failed ("+written+" bytes)");
                UsbRequest done;
                try{done=conn.requestWait(1800L);}
                catch(TimeoutException e){throw new IllegalStateException("No HID response within 1.8s",e);}
                if(done!=input)throw new IllegalStateException("Unexpected USB transfer completion");
                int received=buffer.position();
                if(received<=0)throw new IllegalStateException("Empty HID input response");
                buffer.flip(); byte[] response=new byte[received];buffer.get(response);
                // Some Android controllers expose a leading 0 padding byte in 65-byte packets.
                if(response.length==65 && response[0]==0 && response[1]==4){
                    byte[] trimmed=new byte[64];System.arraycopy(response,1,trimmed,0,64);response=trimmed;
                }
                return HidProtocol.parseResponse(response,count);
            }finally{input.close();}
        }
        @Override public void close(){try{conn.releaseInterface(iface);}finally{conn.close();}}
    }

    @Override public void onDestroy(){
        try{unregisterReceiver(permissionReceiver);}catch(Exception ignored){}
        worker.shutdownNow();super.onDestroy();
    }
}
