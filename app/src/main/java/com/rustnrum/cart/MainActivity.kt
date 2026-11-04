package com.rustnrum.cart

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : Activity() {

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

    private lateinit var web: WebView
    private lateinit var usbManager: UsbManager
    private lateinit var usb: UsbController
    private lateinit var database: TripDatabase
    private lateinit var locationManager: LocationManager
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val permissionAction = "com.rustnrum.cart.USB_PERMISSION"
    private var uiReady = false
    private var connected = false
    private var pollBusy = false
    private var tick = 0
    private var latestTelemetry: JSONObject? = null
    private var lastGps: Location? = null
    private var lastGpsMs = 0L
    private var gpsSpeedMph: Double? = null
    private var tripId: Long? = null
    private var distanceMeters = 0.0
    private var lastSavedMs = 0L
    private var lastBatterySavedMs = 0L
    private var locationActive = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                permissionAction -> {
                    val allowed = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (allowed) connectController() else showStatus("USB permission denied", false)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val d = if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (d?.vendorId == AlltraxProtocol.VENDOR_ID && d.productId == AlltraxProtocol.PRODUCT_ID) {
                        connected = false
                        latestTelemetry = null
                        io.execute { usb.close() }
                        showStatus("Controller unplugged", false)
                        send("onTelemetry", JSONObject().put("unavailable",true))
                    }
                }
            }
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val now = System.currentTimeMillis()
            if (tripId != null && location.accuracy <= 35f) {
                val prev = lastGps
                if (prev != null && prev.accuracy <= 35f) {
                    val dt = (location.time - prev.time).coerceAtLeast(0L)
                    val meters = prev.distanceTo(location).toDouble()
                    if (dt in 500..15000 && meters <= dt/1000.0 * 30.0 + 15.0) {
                        distanceMeters += meters
                    }
                }
            }
            lastGps = location
            lastGpsMs = now
            gpsSpeedMph = if (location.hasSpeed() && location.accuracy < 75f)
                max(0.0, location.speed.toDouble() * 2.2369362921) else null
            send("onGps", JSONObject()
                .put("speedMph", gpsSpeedMph ?: JSONObject.NULL)
                .put("accuracyMeters",location.accuracy.toDouble())
                .put("distanceMiles",distanceMeters / 1609.344)
                .put("atMs",now))
        }

        override fun onProviderDisabled(provider: String) {
            gpsSpeedMph = null
            send("onGps", JSONObject().put("error","Location provider disabled"))
        }
    }

    private val pollTask = object : Runnable {
        override fun run() {
            if (connected && !pollBusy) {
                pollBusy = true
                io.execute {
                    try {
                        val reads = linkedMapOf<String, ByteArray?>()
                        // Basic readings are mandatory; optional fields cannot blank out core gauges.
                        val power = usb.read(0x20004110L,18)
                        reads["power"] = power
                        for ((tag, pair) in SensorDecoder.blocks) {
                            if (tag == "power") continue
                            // Fault history changes slowly; don't read it every poll.
                            if (tag == "history" && tick % 6 != 0) continue
                            reads[tag] = try { usb.read(pair.first,pair.second) } catch (_: Exception) { null }
                        }
                        val telemetry = SensorDecoder.decode(reads)
                        latestTelemetry = telemetry
                        val now = System.currentTimeMillis()
                        if (now - lastBatterySavedMs >= 10000L && telemetry.opt("volts") is Number) {
                            database.addBatterySample(telemetry)
                            lastBatterySavedMs = now
                        }
                        send("onTelemetry", telemetry)
                    } catch (e: Exception) {
                        showStatus("Controller read failed: ${e.message}", false)
                        connected = false
                        latestTelemetry = null
                        send("onTelemetry", JSONObject().put("unavailable",true))
                    } finally {
                        tick++
                        pollBusy = false
                    }
                }
            }
            // Logging is independent of whether USB currently responds; GPS-only sessions remain possible.
            val id = tripId
            val now = System.currentTimeMillis()
            if (id != null && now - lastSavedMs >= 2000L) {
                lastSavedMs = now
                val speed = gpsSpeedMph?.takeIf { now - lastGpsMs <= 10000 }
                val meters = distanceMeters
                val copy = latestTelemetry?.let { JSONObject(it.toString()) }
                io.execute { database.addSample(id,copy,speed,meters) }
            }
            main.postDelayed(this, 1750L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scoutHost = this
        

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
window.statusBarColor = Color.rgb(8,14,18)
        window.navigationBarColor = Color.rgb(8,14,18)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        usb = UsbController(usbManager)
        database = TripDatabase(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        tripId = database.currentTripId()
        web = WebView(this)
        web.setBackgroundColor(Color.rgb(8,14,18))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = false
        web.settings.setSupportZoom(false)
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return request?.url?.toString()?.startsWith("file:///android_asset/") != true
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                uiReady = true
                send("onConnection", JSONObject().put("connected",false)
                    .put("message","Ready to connect over USB"))
                send("onTripState", tripState())
                send("onTrips", JSONObject().put("trips", database.history()))
            }
        }
        web.addJavascriptInterface(NativeBridge(),"Cart")
        setContentView(web)
        if (Build.VERSION.SDK_INT >= 30) {
            web.setOnApplyWindowInsetsListener { view,insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(0,bars.top,0,bars.bottom)
                insets
            }
        }
        val filter = IntentFilter(permissionAction).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver,filter)
        web.loadUrl("file:///android_asset/index.html")
        main.post(pollTask)
        if (tripId != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun send(function: String, data: JSONObject) {
        if (!uiReady) return
        runOnUiThread {
            if (uiReady) web.evaluateJavascript("window.RNR && RNR.$function(${data})", null)
        }
    }

    private fun showStatus(message: String, success: Boolean) {
        if (!success) connected = false
        send("onConnection", JSONObject().put("connected",success).put("message",message))
    }

    private fun connectController() {
        val d = usb.find()
        if (d == null) {
            showStatus("Alltrax 23D4:0001 not detected. Connect USB OTG cable.",false)
            return
        }
        if (!usbManager.hasPermission(d)) {
            val intent = Intent(permissionAction).setPackage(packageName)
            val pending = PendingIntent.getBroadcast(this,0,intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            usbManager.requestPermission(d,pending)
            showStatus("Allow Android USB permission, then connect",false)
            return
        }
        showStatus("Reading controller identification…",false)
        io.execute {
            try {
                usb.connect(d)
                val id = usb.read(0x08000400L,52)
                val hardware = usb.read(0x08000480L,24)
                val name = String(id.copyOfRange(0,15),Charsets.US_ASCII).trim('\u0000',' ')
                val ratedV = AlltraxProtocol.u16(hardware,0)
                val ratedA = AlltraxProtocol.u16(hardware,2)
                require(ratedV in 1..144 && ratedA in 1..1500) { "Controller rated values are implausible" }
                connected = true
                send("onConnection", JSONObject().put("connected",true)
                    .put("model",name).put("voltage",ratedV).put("amps",ratedA)
                    .put("message","$name connected · $ratedV V / $ratedA A"))
            } catch (e: Exception) {
                usb.close()
                showStatus("USB: ${e.message}",false)
            }
        }
    }

    private fun disconnectController() {
        connected = false
        latestTelemetry = null
        io.execute { usb.close() }
        showStatus("Disconnected",false)
        send("onTelemetry", JSONObject().put("unavailable",true))
    }

    private fun tripState(): JSONObject = JSONObject()
        .put("recording",tripId != null)
        .put("id",tripId ?: JSONObject.NULL)
        .put("distanceMiles",distanceMeters / 1609.344)

    private fun startTrip() {
        if (tripId == null) {
            val v = latestTelemetry?.opt("volts").let { if(it is Number) it.toDouble() else null }
            tripId = database.start(v)
            distanceMeters = 0.0
            lastGps = null
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        send("onTripState",tripState())
    }

    private fun finishTrip() {
        val id = tripId ?: return
        val v = latestTelemetry?.opt("volts").let { if(it is Number) it.toDouble() else null }
        io.execute {
            database.finish(id,v)
            send("onTrips", JSONObject().put("trips",database.history()))
        }
        tripId = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        send("onTripState",tripState())
    }

    private fun startGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION),7)
            return
        }
        if (locationActive) return
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000L,0f,
                locationListener,Looper.getMainLooper())
            locationActive = true
            send("onGps", JSONObject().put("message","Waiting for GPS satellites…"))
        } catch(e: Exception) {
            send("onGps", JSONObject().put("error",e.message ?: "GPS not available"))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startGps()
            else send("onGps", JSONObject().put("error","Location permission denied"))
        }
    }

    private fun readThrottle(kind: String) {
        if (!connected) { send("onThrottle",JSONObject().put("error","Connect controller first")); return }
        io.execute {
            try {
                val payload = JSONObject().put("kind",kind)
                if (kind == "settings") {
                    // Settings block discovered in SR flash; field offsets still require real-device validation.
                    val data = usb.read(0x08000860L,48)
                    payload.put("address","0x08000860").put("raw",AlltraxProtocol.hex(data))
                        .put("throttleTypeRaw",AlltraxProtocol.u8(data,0))
                        .put("responseRateRaw",AlltraxProtocol.s16(data,2))
                        .put("hpdRaw",AlltraxProtocol.u8(data,4))
                        .put("relayZeroRaw",AlltraxProtocol.u8(data,6))
                        .put("note","Flash layout needs confirmation against Alltrax Toolkit before programming")
                } else {
                    val curves = JSONArray()
                    for ((name,base) in listOf("Linearization" to 0x08000900L,
                            "Speed" to 0x08000940L,"Torque" to 0x08000980L)) {
                        val x = usb.read(base,32)
                        val y = usb.read(base+32,32)
                        val values = JSONArray()
                        repeat(16) { idx ->
                            values.put(JSONObject().put("x",AlltraxProtocol.u16(x,idx*2))
                                .put("y",AlltraxProtocol.u16(y,idx*2)))
                        }
                        curves.put(JSONObject().put("name",name).put("base","0x%08X".format(base))
                            .put("rawX",AlltraxProtocol.hex(x)).put("rawY",AlltraxProtocol.hex(y))
                            .put("points",values))
                    }
                    payload.put("curves",curves).put("note","Raw manufacturer flash points; not yet verified for writing")
                }
                send("onThrottle",payload)
            } catch(e:Exception) {
                send("onThrottle",JSONObject().put("error","Read failed: ${e.message}"))
            }
        }
    }

    private fun readMemory(address: String, count: Int) {
        io.execute {
            try {
                val a = address.removePrefix("0x").removePrefix("0X").toLong(16)
                val bytes = usb.read(a,count)
                send("onMemory",JSONObject().put("address",address)
                    .put("hex",AlltraxProtocol.hex(bytes)))
            } catch(e: Exception) {
                send("onMemory",JSONObject().put("error",e.message ?: "Read failed"))
            }
        }
    }

    // Manual, bounded SRAM-only inspection; read operations cannot program controller.
    private fun discoverySnapshot(label: String) {
        if (!connected) { send("onDiscovery", JSONObject().put("error", "Connect controller first")); return }
        io.execute {
            val result = JSONObject().put("label",label.take(40)).put("at",System.currentTimeMillis())
            val rows = JSONArray()
            val locations = longArrayOf(0x20004010L,0x20004020L,0x20004050L,0x20004060L,
                0x20004070L,0x20004080L,0x200040A0L,0x200040C4L,
                0x200040D4L,0x200040F4L,0x20004140L,0x20004150L)
            for (addr in locations) {
                val row = JSONObject().put("address", "0x%08X".format(addr))
                try {
                    val bytes=usb.read(addr,16)
                    val values=JSONArray()
                    bytes.forEach { values.put(it.toInt() and 255) }
                    row.put("bytes",values).put("hex",AlltraxProtocol.hex(bytes))
                } catch(e:Exception) { row.put("error",e.message ?: "Read unavailable") }
                rows.put(row)
            }
            result.put("blocks",rows)
            try {
                val prefs=getSharedPreferences("discovery", MODE_PRIVATE)
                val old=JSONArray(prefs.getString("snapshots","[]"))
                old.put(result)
                val kept=JSONArray()
                for (i in (old.length()-12).coerceAtLeast(0) until old.length()) kept.put(old.getJSONObject(i))
                prefs.edit().putString("snapshots",kept.toString()).apply()
            } catch(e:Exception) { result.put("saveError",e.message ?: "Storage unavailable") }
            send("onDiscovery",result)
        }
    }

    inner class NativeBridge {
        @JavascriptInterface fun discover(label:String) = runOnUiThread { discoverySnapshot(label) }
        @JavascriptInterface fun discoveryHistory():String =
            getSharedPreferences("discovery",MODE_PRIVATE).getString("snapshots","[]") ?: "[]"
        @JavascriptInterface fun connect() = runOnUiThread { connectController() }
        @JavascriptInterface fun disconnect() = runOnUiThread { disconnectController() }
        @JavascriptInterface fun gps() = runOnUiThread { startGps() }
        @JavascriptInterface fun beginTrip() = runOnUiThread { startTrip() }
        @JavascriptInterface fun endTrip() = runOnUiThread { finishTrip() }
        @JavascriptInterface fun trips(): String = database.history().toString()
        @JavascriptInterface fun samples(id: Long): String = database.samples(id).toString()
        @JavascriptInterface fun batterySamples(hours: Int): String = database.batteryHistory(hours).toString()
        @JavascriptInterface fun batteryChecks(): String = database.restChecks().toString()
        @JavascriptInterface fun saveBatteryCheck(voltage: Double, note: String): Boolean =
            database.addRestCheck(voltage,note)
        @JavascriptInterface fun throttleScreen(name: String) = runOnUiThread {
            readThrottle(if (name == "settings") "settings" else "curves")
        }
        @JavascriptInterface fun readRaw(address: String, count: Int) = runOnUiThread {
            if (count in 1..56) readMemory(address,count)
            else send("onMemory",JSONObject().put("error","Read length must be 1..56"))
        }
        @JavascriptInterface fun savePreset(name:String, content:String): Boolean {
            if (name.length !in 1..50 || content.length > 30000) return false
            return try {
                JSONObject(content)
                getSharedPreferences("profiles",MODE_PRIVATE).edit().putString(name,content).commit()
            } catch (_:Exception){false}
        }
        @JavascriptInterface fun presets(): String {
            val p = getSharedPreferences("profiles",MODE_PRIVATE).all
            val list = JSONArray()
            for ((name,content) in p) {
                list.put(JSONObject().put("name",name).put("content",content.toString()))
            }
            return list.toString()
        }
    }

    override fun onDestroy() {
        if (scoutHost === this) scoutHost = null
        main.removeCallbacks(pollTask)
        if (locationActive) locationManager.removeUpdates(locationListener)
        try { unregisterReceiver(receiver) } catch (_:Exception) {}
        io.execute { usb.close() }
        io.shutdown()
        web.removeJavascriptInterface("Cart")
        web.destroy()
        database.close()
        super.onDestroy()
    }
}
