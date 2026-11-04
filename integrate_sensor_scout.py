#!/usr/bin/env python3
# Merge read-only Sensor Scout Activity into existing Cart app ZIP.
# Runs once at start of Gradle in the existing GitHub IMPORT job.
# Never rewrite the original v0.3 ZIP. Fail closed on incomplete archives.
import os, re, sys, shutil, zipfile, tempfile
from pathlib import Path

HERE=Path(__file__).resolve().parent
MARKER=HERE/'.rnr_sensor_scout_v04_integrated'
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

manifest.write_text(s)
# Guardrail: core Cart resources and app build config must have been preserved.
assert (HERE/'app/build.gradle.kts').is_file()
assert (HERE/'app/src/main/AndroidManifest.xml').is_file()
MARKER.write_text('v0.4 Sensors integration complete; source from original v0.3 ZIP\n')
print('RNR Sensor Scout: merged into original app; no controller configuration writes')
