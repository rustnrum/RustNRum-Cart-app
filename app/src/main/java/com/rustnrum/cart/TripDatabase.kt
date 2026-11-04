package com.rustnrum.cart

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/** Local-only storage. Existing sessions survive app restart/update. */
class TripDatabase(context: Context) : SQLiteOpenHelper(context, "rnr_cart_trips.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE trips(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            start_ms INTEGER NOT NULL, end_ms INTEGER,
            start_v REAL, end_v REAL,
            min_v REAL, peak_motor_a REAL, distance_m REAL DEFAULT 0,
            note TEXT DEFAULT '')""")
        db.execSQL("""CREATE TABLE samples(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            trip_id INTEGER NOT NULL, at_ms INTEGER NOT NULL,
            voltage REAL, motor_a REAL, throttle_pct REAL,
            temp_f REAL, speed_mph REAL, distance_m REAL, faults TEXT,
            sensors_json TEXT,
            FOREIGN KEY(trip_id) REFERENCES trips(id))""")
        createBatteryTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createBatteryTables(db)
    }

    private fun createBatteryTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS battery_samples(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            at_ms INTEGER NOT NULL,
            voltage REAL NOT NULL,
            motor_a REAL,
            throttle_pct REAL,
            temp_f REAL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_battery_samples_time ON battery_samples(at_ms)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS battery_checks(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            at_ms INTEGER NOT NULL,
            rested_voltage REAL NOT NULL,
            note TEXT NOT NULL DEFAULT '')""")
    }

    /** Samples only live, freshly decoded controller values. Never infer pack amperage from motor amperage. */
    @Synchronized fun addBatterySample(telemetry: JSONObject) {
        val v = (telemetry.opt("volts") as? Number)?.toDouble() ?: return
        if (!v.isFinite() || v !in 30.0..65.0) return
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("at_ms", now); put("voltage", v)
            for ((field,key) in listOf("motor_a" to "amps", "throttle_pct" to "throttlePct", "temp_f" to "controllerTempF")) {
                val n = (telemetry.opt(key) as? Number)?.toDouble()
                if (n != null && n.isFinite()) put(field,n)
            }
        }
        writableDatabase.insertOrThrow("battery_samples",null,values)
        // 30 days of history, sampled about once every 10 seconds, is retained on-device.
        writableDatabase.delete("battery_samples", "at_ms < ?",arrayOf((now - 30L*86400000L).toString()))
    }

    @Synchronized fun batteryHistory(hours: Int): JSONArray {
        val arr=JSONArray()
        val since=System.currentTimeMillis()-hours.coerceIn(1,720)*3600000L
        // Aggregate into time buckets so 30-day chart shows the entire period rather than
        // silently truncating to the most recent 3000 samples. MIN(V) preserves sag events.
        val bucketMs = when {
            hours <= 24 -> 30000L
            hours <= 168 -> 300000L
            else -> 1800000L
        }
        readableDatabase.rawQuery("""SELECT MIN(at_ms),MIN(voltage),MAX(ABS(motor_a)),
            AVG(throttle_pct),AVG(temp_f) FROM battery_samples WHERE at_ms >= ?
            GROUP BY (at_ms / ?) ORDER BY MIN(at_ms)""",
            arrayOf(since.toString(),bucketMs.toString())).use { c ->
            while(c.moveToNext()) {
                fun cell(i:Int): Any = if(c.isNull(i)) JSONObject.NULL else c.getDouble(i)
                arr.put(JSONObject().put("at",c.getLong(0)).put("v",c.getDouble(1))
                    .put("a",cell(2)).put("throttle",cell(3)).put("temp",cell(4)))
            }
        }
        return arr
    }

    @Synchronized fun addRestCheck(voltage: Double, note: String): Boolean {
        if (!voltage.isFinite() || voltage !in 42.0..55.0 || note.length>100) return false
        val v=ContentValues().apply {
            put("at_ms",System.currentTimeMillis());put("rested_voltage",voltage);put("note",note)
        }
        return writableDatabase.insert("battery_checks",null,v)!=-1L
    }

    @Synchronized fun restChecks(): JSONArray {
        val arr=JSONArray()
        readableDatabase.rawQuery("SELECT at_ms,rested_voltage,note FROM battery_checks ORDER BY id DESC LIMIT 20",null).use { c ->
            while(c.moveToNext())arr.put(JSONObject().put("at",c.getLong(0))
                .put("v",c.getDouble(1)).put("note",c.getString(2)))
        }
        return arr
    }

    @Synchronized fun currentTripId(): Long? {
        readableDatabase.rawQuery("SELECT id FROM trips WHERE end_ms IS NULL ORDER BY id DESC LIMIT 1", null).use {
            return if (it.moveToFirst()) it.getLong(0) else null
        }
    }

    @Synchronized fun start(voltage: Double?): Long {
        currentTripId()?.let { return it }
        val values = ContentValues().apply {
            put("start_ms", System.currentTimeMillis())
            voltage?.let { put("start_v", it); put("min_v", it) }
        }
        return writableDatabase.insertOrThrow("trips", null, values)
    }

    @Synchronized fun addSample(id: Long, telemetry: JSONObject?, speedMph: Double?, distanceMeters: Double) {
        if (currentTripId() != id) return
        fun num(key: String): Double? = telemetry?.opt(key).let { if (it is Number) it.toDouble() else null }
        val v = num("volts")
        val a = num("amps")
        val content = ContentValues().apply {
            put("trip_id", id); put("at_ms", System.currentTimeMillis())
            v?.let { put("voltage", it) }; a?.let { put("motor_a", it) }
            num("throttlePct")?.let { put("throttle_pct", it) }
            num("controllerTempF")?.let { put("temp_f", it) }
            speedMph?.let { put("speed_mph", it) }
            put("distance_m", distanceMeters)
            put("faults", telemetry?.optJSONArray("faults")?.toString() ?: "[]")
            put("sensors_json", telemetry?.optJSONArray("sensors")?.toString() ?: "[]")
        }
        writableDatabase.insertOrThrow("samples", null, content)
        writableDatabase.rawQuery("SELECT min_v,peak_motor_a,distance_m FROM trips WHERE id=?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) {
                val oldMin = if (it.isNull(0)) null else it.getDouble(0)
                val oldPeak = if (it.isNull(1)) null else it.getDouble(1)
                val update = ContentValues().apply {
                    if (v != null) put("min_v", if (oldMin == null) v else minOf(v, oldMin))
                    if (a != null) put("peak_motor_a", if (oldPeak == null) a else max(kotlin.math.abs(a), oldPeak))
                    put("distance_m", distanceMeters)
                }
                writableDatabase.update("trips", update, "id=?", arrayOf(id.toString()))
            }
        }
    }

    @Synchronized fun finish(id: Long, voltage: Double?) {
        if (currentTripId() != id) return
        val values = ContentValues().apply {
            put("end_ms", System.currentTimeMillis())
            voltage?.let { put("end_v", it) }
        }
        writableDatabase.update("trips", values, "id=?", arrayOf(id.toString()))
    }

    @Synchronized fun history(): JSONArray {
        val arr = JSONArray()
        readableDatabase.rawQuery("""SELECT id,start_ms,end_ms,start_v,end_v,min_v,peak_motor_a,distance_m
            FROM trips ORDER BY id DESC LIMIT 100""", null).use { c ->
            while (c.moveToNext()) {
                fun cell(i:Int): Any = if(c.isNull(i)) JSONObject.NULL else c.getDouble(i)
                arr.put(JSONObject().put("id",c.getLong(0)).put("started",c.getLong(1))
                    .put("ended", if(c.isNull(2)) JSONObject.NULL else c.getLong(2))
                    .put("startV", cell(3)).put("endV", cell(4))
                    .put("minV", cell(5)).put("peakAmps",cell(6)).put("distanceMeters",c.getDouble(7)))
            }
        }
        return arr
    }

    @Synchronized fun samples(tripId: Long): JSONArray {
        val arr = JSONArray()
        readableDatabase.rawQuery("""SELECT at_ms,voltage,motor_a,throttle_pct,temp_f,speed_mph,distance_m
            FROM samples WHERE trip_id=? ORDER BY id LIMIT 5000""",arrayOf(tripId.toString())).use { c ->
            while(c.moveToNext()){
                fun valAt(i:Int): Any = if(c.isNull(i)) JSONObject.NULL else c.getDouble(i)
                arr.put(JSONObject().put("at",c.getLong(0)).put("v",valAt(1))
                    .put("a",valAt(2)).put("throttle",valAt(3))
                    .put("temp",valAt(4)).put("mph",valAt(5)).put("meters",valAt(6)))
            }
        }
        return arr
    }
}
