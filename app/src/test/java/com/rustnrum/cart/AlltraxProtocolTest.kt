package com.rustnrum.cart

import org.junit.Assert.*
import org.junit.Test

class AlltraxProtocolTest {
    @Test fun srPowerReadUsesMeasuredChecksum() {
        val p = AlltraxProtocol.readRequest(0x20004110L, 18)
        assertArrayEquals(byteArrayOf(0x01,0x12,0x84.toByte(),0,0x20,0,0x41,0x10), p.copyOfRange(0,8))
        assertEquals(64,p.size)
    }

    @Test fun srIdentityReadMatchesRealCapture() {
        val p = AlltraxProtocol.readRequest(0x08000400L,52)
        assertArrayEquals(byteArrayOf(0x01,0x34,0x41,0,0x08,0,0x04,0),p.copyOfRange(0,8))
    }

    @Test fun readOnlyRestrictsAddresses() {
        assertTrue(AlltraxProtocol.isAllowedRead(0x20004110L,18))
        assertFalse(AlltraxProtocol.isAllowedRead(0x40000000L, 4))
        assertFalse(AlltraxProtocol.isAllowedRead(0x20004110L,65))
    }

    @Test fun responseDecodesLittleEndianValues() {
        val response = ByteArray(64)
        response[0] = 4
        response[1] = 18
        response[4] = 1
        response[8] = 0x0c
        response[9] = 0x02
        val power = AlltraxProtocol.parseReadResponse(response,18)
        assertEquals(524, AlltraxProtocol.s16(power,0))
        assertEquals(52.4, AlltraxProtocol.s16(power,0)*0.1,0.001)
    }
}
