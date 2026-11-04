#!/usr/bin/env python3
# Merge read-only Sensor Scout Activity into existing Cart app ZIP.
# Runs once at start of Gradle in the existing GitHub IMPORT job.
# Never rewrite the original v0.3 ZIP. Fail closed on incomplete archives.
import os, re, sys, shutil, zipfile, tempfile
from pathlib import Path

HERE=Path(__file__).resolve().parent
MARKER=HERE/'.rnr_sensor_scout_v05_integrated'
if MARKER.exists():
    print('RNR Sensor Scout: already integrated')
    sys.exit(0)
archive=HERE/'RustNRum-Cart-app-Android-v0.3.0-source.zip'
if not archive.is_file():
    raise SystemExit('Missing existing v0.3 Cart source ZIP; refusing to build scanner-only APK')
with tempfile.TemporaryDirectory(prefix='rnr-cart-base-') as temp:
    with zipfile.ZipFile(archive) as z:
        z.extractall(temp)
    source=Path(temp)/'RustNRum-Cart-app'
    if not (source/'app/build.gradle.kts').exists() or not (source/'app/src/main/AndroidManifest.xml').exists():
        raise SystemExit('v0.3 source structure unexpected; refusing to replace Cart app')
    # Preserve OUR bootstrap settings / integration source until the build finishes.
    for item in source.iterdir():
        if item.name in {'.github','settings.gradle.kts'}: continue
        dest=HERE/item.name
        if item.is_dir():
            shutil.copytree(item,dest,dirs_exist_ok=True)
        elif item.is_file():
            shutil.copy2(item,dest)
    print('RNR Sensor Scout: restored original Cart app source from v0.3 ZIP')

java_dir=HERE/'app/src/main/java/com/rustnrum/sensorscout'
java_dir.mkdir(parents=True,exist_ok=True)
for name in ['HidProtocol.java','SensorCatalog.java','ScoutActivity.java']:
    shutil.copy2(HERE/'sensor_scout'/name,java_dir/name)

manifest=HERE/'app/src/main/AndroidManifest.xml'
s=manifest.read_text()
if not re.search(r'<application\b',s):
    raise SystemExit('No <application> in original manifest')
feature='<uses-feature android:name="android.hardware.usb.host" android:required="false" />'
if 'android.hardware.usb.host' not in s:
    s=re.sub(r'(<application\b)',feature+'\n    '+r'\1',s,count=1)
activity='''<activity android:name="com.rustnrum.sensorscout.ScoutActivity"
        android:exported="false" android:theme="@android:style/Theme.Material.NoActionBar" />'''
if 'com.rustnrum.sensorscout.ScoutActivity' not in s:
    s=s.replace('</application>',activity+'\n    </application>',1)

# Hook the existing launcher's onCreate, without changing its navigation or state.
# Android's android.R.id.content is the activity FrameLayout. Button opens a second
# activity within the SAME APK, and appears only in the main screen.
java_inject='''
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            android.widget.FrameLayout rnrRoot = findViewById(android.R.id.content);
            if (rnrRoot == null || rnrRoot.findViewWithTag("rnr_scout_link") != null) return;
            android.widget.Button rnrScout = new android.widget.Button(this);
            rnrScout.setTag("rnr_scout_link");
            rnrScout.setText("Sensors"); rnrScout.setAllCaps(false);
            rnrScout.setTextColor(0xffe9fcfc);
            rnrScout.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff176d75));
            rnrScout.setOnClickListener(v -> startActivity(new android.content.Intent(this,
                com.rustnrum.sensorscout.ScoutActivity.class)));
            android.widget.FrameLayout.LayoutParams rnrLayout =
                new android.widget.FrameLayout.LayoutParams(-2, -2,
                    android.view.Gravity.END | android.view.Gravity.BOTTOM);
            int rnrDp = (int)(getResources().getDisplayMetrics().density * 12);
            rnrLayout.setMargins(rnrDp,rnrDp,rnrDp,rnrDp*6);
            rnrRoot.addView(rnrScout,rnrLayout);
        }, 900L);
'''
kt_inject='''
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val rnrRoot = findViewById<android.widget.FrameLayout>(android.R.id.content)
            if (rnrRoot != null && rnrRoot.findViewWithTag<android.view.View>("rnr_scout_link") == null) {
                val rnrScout = android.widget.Button(this)
                rnrScout.tag = "rnr_scout_link"
                rnrScout.text = "Sensors"
                rnrScout.isAllCaps = false
                rnrScout.setTextColor(0xffe9fcfc.toInt())
                rnrScout.backgroundTintList = android.content.res.ColorStateList.valueOf(0xff176d75.toInt())
                rnrScout.setOnClickListener { startActivity(android.content.Intent(this,
                    com.rustnrum.sensorscout.ScoutActivity::class.java)) }
                val rnrLayout = android.widget.FrameLayout.LayoutParams(-2,-2,
                    android.view.Gravity.END or android.view.Gravity.BOTTOM)
                val rnrDp = (resources.displayMetrics.density * 12).toInt()
                rnrLayout.setMargins(rnrDp,rnrDp,rnrDp,rnrDp*6)
                rnrRoot.addView(rnrScout,rnrLayout)
            }
        }, 900L)
'''

patched=None
sources=[p for p in (HERE/'app/src/main').rglob('*') if p.suffix in {'.java','.kt'}]
# Prefer class MainActivity, then manifest-specified launcher class if named differently
launcher=re.search(r'<activity\b[^>]*android:name\s*=\s*["\']([^"\']+)["\'][^>]*>\s*<intent-filter',s,re.S)
launcher_name=(launcher.group(1).split('.')[-1] if launcher else 'MainActivity')
priority=lambda p:(0 if p.stem==launcher_name else 1 if p.stem=='MainActivity' else 2,str(p))
for p in sorted(sources,key=priority):
    if not (p.stem==launcher_name or p.stem=='MainActivity'): continue
    body=p.read_text()
    if 'rnr_scout_link' in body: patched=p;break
    pattern=r'super\.onCreate\s*\(\s*savedInstanceState\s*\)\s*;?' 
    match=re.search(pattern,body)
    if not match: continue
    inject=kt_inject if p.suffix=='.kt' else java_inject
    body=body[:match.end()]+'\n'+inject+body[match.end():]
    p.write_text(body)
    patched=p
    print('RNR Sensor Scout: integrated Sensors button into',p.relative_to(HERE))
    break

if patched is None:
    # Fallback: accessible as a second launcher activity within the SAME APK.
    # This is safer than silently producing an APK without any access to sensors.
    print('RNR Sensor Scout: launcher source not identifiable; exposing Sensors as second icon')
    s=s.replace('android:exported="false" android:theme', 'android:exported="true" android:theme',1)
    s=s.replace('android:theme="@android:style/Theme.Material.NoActionBar" />',
        '''android:theme="@android:style/Theme.Material.NoActionBar" android:label="RNR Cart Sensors">
        <intent-filter><action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" /></intent-filter>
        </activity>''',1)

# Bind this scanner to the existing working Cart USB instance, not a new HID transport.
# The original app's UsbController uses interrupt OUT 0x01 and IN 0x81 (64 bytes).
# A separate SET_REPORT call failed -1 on this hardware; sharing the existing
# connection is necessary to prevent duplicate interface claims and concurrent I/O.
kt_main=HERE/'app/src/main/java/com/rustnrum/cart/MainActivity.kt'
if not kt_main.is_file():
    raise SystemExit('Expected Kotlin Cart MainActivity missing; refusing incomplete scanner integration')
kt=kt_main.read_text()
if 'scoutRead(address: Long, count: Int)' not in kt:
    cls=re.search(r'class\s+MainActivity\s*:\s*Activity\s*\(\s*\)\s*\{',kt)
    if not cls: raise SystemExit('Cannot identify original MainActivity class for shared USB access')
    shared="""
    companion object {
        @Volatile private var scoutHost: MainActivity? = null

        /** Read-only, serialized with Drive's existing UsbController + trip polling. */
        @JvmStatic fun scoutConnected(): Boolean = scoutHost?.connected == true
        @JvmStatic fun scoutRead(address: Long, count: Int): ByteArray {
            val owner = scoutHost ?: throw IllegalStateException("Cart USB session unavailable")
            val job = owner.io.submit<ByteArray> {
                check(owner.connected) { "Connect Alltrax on Drive screen first" }
                owner.usb.read(address, count)
            }
            return try {
                job.get(6500L, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.ExecutionException) {
                throw IllegalStateException("Alltrax USB read failed: ${e.cause?.message ?: e.message}",e.cause ?: e)
            } catch (e: java.util.concurrent.TimeoutException) {
                job.cancel(true)
                throw IllegalStateException("Alltrax USB request timed out",e)
            }
        }
    }
"""
    kt=kt[:cls.end()] + '\n' + shared + kt[cls.end():]
    begin=re.search(r'super\.onCreate\s*\(\s*savedInstanceState\s*\)',kt)
    if not begin:raise SystemExit('Missing onCreate for sensor host registration')
    kt=kt[:begin.end()]+'\n        scoutHost = this'+kt[begin.end():]
    cleanup='override fun onDestroy() {'
    if cleanup not in kt: raise SystemExit('No onDestroy cleanup point')
    kt=kt.replace(cleanup,cleanup+'\n        if (scoutHost === this) scoutHost = null',1)
    kt_main.write_text(kt)
    print('RNR Sensor Scout: scanner now shares Drive USB connection and serialized worker')

manifest.write_text(s)
# Guardrail: core Cart resources and app build config must have been preserved.
assert (HERE/'app/build.gradle.kts').is_file()
assert (HERE/'app/src/main/AndroidManifest.xml').is_file()
MARKER.write_text('v0.5 Sensors transport fix: shared working Cart USB session\n')
print('RNR Sensor Scout: merged into original app; no controller configuration writes')
