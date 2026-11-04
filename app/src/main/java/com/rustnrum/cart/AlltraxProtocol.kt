package com.rustnrum.cart

/**
 * SR/SPM family (USB VID 23D4 PID 0001) read-only HID protocol.
 * Proven bytes were captured against SR48400 on Linux; on Android the USB
 * transport is different, but these 64-byte application reports are identical.
 * No controller write/configuration reports exist anywhere in this module.
 */
object AlltraxProtocol {
    const val VENDOR_ID = 0x23D4
    const val PRODUCT_ID = 0x0001
    const val REPORT_SIZE = 64
    private const val MAX_READ = 56

    fun readRequest(address: Long, count: Int): ByteArray {
        require(count in 1..MAX_READ) { "Read size must be 1..56" }
        require(isAllowedRead(address, count)) { "Address not in permitted read-only range" }
        val b = ByteArray(REPORT_SIZE)
        b[0] = 0x01
        b[1] = count.toByte()
        b[4] = (address shr 24).toByte()
        b[5] = (address shr 16).toByte()
        b[6] = (address shr 8).toByte()
        b[7] = address.toByte()
        var checksum = 0
        for (i in b.indices) {
            if (i == 2 || i == 3) continue
            checksum += b[i].toInt() and 0xFF
        }
        b[2] = checksum.toByte()
        b[3] = (checksum shr 8).toByte()
        return b
    }

    fun parseReadResponse(packet: ByteArray, requestedLength: Int): ByteArray {
        require(packet.size == REPORT_SIZE) { "Short HID report: ${packet.size} bytes" }
        require(u8(packet, 0) == 4) { "Unexpected HID report ID ${u8(packet, 0)}" }
        require(u8(packet, 4) == 1) { "Unexpected response type ${u8(packet, 4)}" }
        require(u8(packet, 6) == 0) { "Controller returned failure 0x${u8(packet, 6).toString(16)}" }
        val actual = u8(packet, 1)
        require(actual in requestedLength..MAX_READ) { "Response payload size $actual, expected $requestedLength" }
        return packet.copyOfRange(8, 8 + requestedLength)
    }

    fun isAllowedRead(address: Long, count: Int): Boolean {
        if (count !in 1..MAX_READ) return false
        val end = address + count
        return (address >= 0x08000000L && end <= 0x08040000L) ||
            (address >= 0x20000000L && end <= 0x20020000L)
    }

    fun u8(b: ByteArray, at: Int) = b[at].toInt() and 0xFF
    fun u16(b: ByteArray, at: Int) = u8(b, at) or (u8(b, at + 1) shl 8)
    fun s16(b: ByteArray, at: Int) = u16(b, at).let { if (it >= 0x8000) it - 65536 else it }
    fun s32(b: ByteArray, at: Int): Int = (u8(b, at) or (u8(b, at + 1) shl 8) or
        (u8(b, at + 2) shl 16) or (u8(b, at + 3) shl 24))
    fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
}
