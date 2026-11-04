package com.rustnrum.cart

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/** Field definitions extracted from Alltrax USBPort.dll SPM_Variables map.
 * An address being documented does not establish hardware availability.
 * Only battery V, motor A, throttle MU, two temperatures and flags were
 * previously observed working; all other fields are labeled unverified.
 */
object SensorDecoder {
    private val faultNames = listOf(
        "Global shutdown", "Hardware overcurrent", "Overcurrent retry running",
        "Low battery", "High battery", "Precharge failure", "Overtemperature",
        "Undertemperature", "Throttle range alarm", "High throttle above range",
        "High-throttle input below range", "Low throttle above range",
        "Low throttle below range", "Start delay", "Invalid variables"
    )

    data class Def(val name: String, val block: String, val offset: Int, val kind: Char,
                   val scale: Double = 1.0, val unit: String = "raw", val bias: Double = 0.0)
    val blocks = linkedMapOf(
        "power" to Pair(0x20004110L, 18),
        "temps" to Pair(0x200040E6L, 4),
        "errors" to Pair(0x20004000L, 15),
        "keys" to Pair(0x20004090L, 10),
        "raw" to Pair(0x200040B0L, 20),
        "avg" to Pair(0x200040E0L, 20),
        "extended" to Pair(0x20004122L, 30),
        "history" to Pair(0x20004030L, 30)
    )

    val definitions = listOf(
        Def("Battery voltage", "power", 0, 's', .1, "V"),
        Def("Key-switch voltage", "power", 2, 's', .1, "V"),
        Def("Reverse voltage", "power", 4, 's', .1, "V"),
        Def("Local throttle", "power", 6, 's'),
        Def("Throttle position", "power", 8, 's', 1.0, "MU"),
        Def("Motor output current", "power", 10, 'i', .1, "A"),
        Def("A2 plug-brake current (not battery current)", "power", 14, 'i', .1, "A"),
        Def("B+ temperature", "temps", 0, 't', .1289, "°F", 527.0),
        Def("M− temperature", "temps", 2, 't', .1289, "°F", 527.0),
        Def("Key input", "keys", 0, 'b', 1.0, "state"),
        Def("Reverse input", "keys", 1, 'b', 1.0, "state"),
        Def("Solenoid relay state", "keys", 2, 'u', 1.0, "raw"),
        Def("Cooling fan state", "keys", 4, 'b', 1.0, "state"),
        Def("HPD ran", "keys", 5, 'b', 1.0, "state"),
        Def("Bad variables code", "keys", 6, 'u'),
        Def("User1 input", "keys", 8, 'b', 1.0, "state"),
        Def("Raw key ADC", "raw", 0, 's', .0429, "V"),
        Def("Raw reverse ADC", "raw", 2, 's', .0429, "V"),
        Def("Raw battery ADC", "raw", 4, 's', .04, "V"),
        Def("Raw B+ temperature", "raw", 6, 't', .1289, "°F", 527.0),
        Def("Raw M− temperature", "raw", 8, 't', .1289, "°F", 527.0),
        Def("Raw motor Hall", "raw", 10, 's'),
        Def("Raw A2 Hall", "raw", 12, 's'),
        Def("Raw throttle high", "raw", 14, 's'),
        Def("Raw throttle low", "raw", 16, 's'),
        Def("Raw User1", "raw", 18, 's'),
        Def("Averaged key ADC", "avg", 0, 's', .0429, "V"),
        Def("Averaged reverse ADC", "avg", 2, 's', .0429, "V"),
        Def("Averaged battery ADC", "avg", 4, 's', .04, "V"),
        Def("Averaged B+ temperature", "avg", 6, 't', .1289, "°F", 527.0),
        Def("Averaged M− temperature", "avg", 8, 't', .1289, "°F", 527.0),
        Def("Averaged motor Hall", "avg", 10, 's'),
        Def("Averaged A2 Hall", "avg", 12, 's'),
        Def("Averaged throttle high", "avg", 14, 's'),
        Def("Averaged throttle low", "avg", 16, 's'),
        Def("Averaged User1", "avg", 18, 's'),
        Def("Linearized throttle", "extended", 0, 's', 1.0, "MU"),
        Def("Throttle voltage target", "extended", 2, 's'),
        Def("Throttle current target", "extended", 4, 's'),
        Def("Throttle pointer", "extended", 6, 's'),
        Def("Motor Hall zero", "extended", 8, 's'),
        Def("Motor Hall temp zero", "extended", 10, 's'),
        Def("Motor Hall temperature correction", "extended", 12, 's'),
        Def("A2 Hall zero", "extended", 14, 's'),
        Def("A2 Hall temp zero", "extended", 16, 's'),
        Def("A2 temperature correction", "extended", 18, 's'),
        Def("Overtemperature limiting", "extended", 20, 's'),
        Def("Motor RPM if supported", "extended", 22, 's', 1.0, "RPM"),
        Def("User1 voltage", "extended", 24, 's', .1, "V"),
        Def("User1 internal", "extended", 26, 's'),
        Def("Throttle pre-linearization", "extended", 28, 's', 1.0, "MU")
    )

    fun decode(received: Map<String, ByteArray?>): JSONObject {
        val result = JSONObject()
        val sensorRows = JSONArray()
        for (def in definitions) {
            val packet = received[def.block]
            val pair = blocks.getValue(def.block)
            val row = JSONObject()
                .put("name", def.name)
                .put("address", "0x%08X".format(pair.first + def.offset))
                .put("unit", def.unit)
            val needed = if (def.kind == 'i') 4 else if (def.kind == 'b') 1 else 2
            if (packet == null || packet.size < def.offset + needed) {
                row.put("value", JSONObject.NULL).put("raw", JSONObject.NULL)
                    .put("status", "unavailable")
            } else {
                val raw = when (def.kind) {
                    'b' -> AlltraxProtocol.u8(packet, def.offset)
                    'u' -> AlltraxProtocol.u16(packet, def.offset)
                    'i' -> AlltraxProtocol.s32(packet, def.offset)
                    else -> AlltraxProtocol.s16(packet, def.offset)
                }
                var numeric = (raw - def.bias) * def.scale
                if (def.kind == 't') numeric = numeric * 1.8 + 32
                row.put("raw", raw)
                row.put("value", if (def.unit == "state" && raw in 0..1)
                    if (raw == 1) "ON" else "OFF" else Math.round(numeric * 10.0) / 10.0)
                    .put("status", if (def.block == "power" || def.block == "temps")
                        "reported" else "unverified")
            }
            sensorRows.put(row)
        }
        val active = JSONArray()
        val flags = received["errors"]
        for (i in 0 until 15) {
            val raw = flags?.getOrNull(i)?.toInt()?.and(0xFF)
            val row = JSONObject().put("name", faultNames[i])
                .put("address", "0x%08X".format(0x20004000L + i))
                .put("unit", "flag").put("raw", raw ?: JSONObject.NULL)
                .put("value", when (raw) { 1 -> "ACTIVE"; 0 -> "clear"; else -> "—" })
                .put("status", if (raw in 0..1) "reported" else "unavailable")
            if (raw == 1) active.put(faultNames[i])
            sensorRows.put(row)
        }
        val hist = received["history"]
        for (i in 0 until 15) {
            val x = if (hist != null && hist.size >= i * 2 + 2)
                AlltraxProtocol.u16(hist, i * 2) else null
            sensorRows.put(JSONObject().put("name", "Fault history: ${faultNames[i]}")
                .put("address", "0x%08X".format(0x20004030L + 2*i))
                .put("raw", x ?: JSONObject.NULL).put("value", x ?: JSONObject.NULL)
                .put("unit", "raw counter")
                .put("status", if (x != null) "unverified" else "unavailable"))
        }
        result.put("sensors", sensorRows).put("faults", active)
        fun value(block: String, off: Int, kind: Char): Double? {
            val p = received[block] ?: return null
            return try {
                when (kind) {
                    'i' -> AlltraxProtocol.s32(p, off).toDouble()
                    else -> AlltraxProtocol.s16(p, off).toDouble()
                }
            } catch (_: IndexOutOfBoundsException) { null }
        }
        val volts = value("power", 0, 's')?.times(.1)?.takeIf { it in 0.0..100.0 }
        val amps = value("power", 10, 'i')?.times(.1)?.takeIf { it in -1000.0..1200.0 }
        val throttle = value("power", 8, 's')?.takeIf { it in 0.0..4095.0 }
        val b = value("temps",0,'s')?.let { ((it-527)*.1289)*1.8+32 }?.takeIf { it in -31.0..320.0 }
        val m = value("temps",2,'s')?.let { ((it-527)*.1289)*1.8+32 }?.takeIf { it in -31.0..320.0 }
        result.put("volts", volts ?: JSONObject.NULL)
            .put("amps", amps ?: JSONObject.NULL)
            .put("throttleRaw", throttle ?: JSONObject.NULL)
            .put("throttlePct", throttle?.times(100.0 / 4095.0) ?: JSONObject.NULL)
            .put("tempBusF", b ?: JSONObject.NULL)
            .put("tempMinusF", m ?: JSONObject.NULL)
            .put("controllerTempF", if (b != null && m != null) max(b, m) else b ?: m ?: JSONObject.NULL)
            .put("atMs", System.currentTimeMillis())
        return result
    }
}
