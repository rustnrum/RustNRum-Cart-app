package com.rustnrum.cart

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

/**
 * USB HID interrupt OUT/IN, NOT a serial/Bluetooth driver.
 * Android requires UsbRequest for interrupt endpoints; bulkTransfer is not
 * applicable to this SR controller's HID interrupt endpoints.
 * All calls are made on a single background executor by MainActivity.
 */
class UsbController(private val manager: UsbManager) : Closeable {
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var incoming: UsbEndpoint? = null
    private var outgoing: UsbEndpoint? = null
    var device: UsbDevice? = null
        private set

    fun find(): UsbDevice? = manager.deviceList.values.firstOrNull {
        it.vendorId == AlltraxProtocol.VENDOR_ID && it.productId == AlltraxProtocol.PRODUCT_ID
    }

    fun connect(d: UsbDevice) {
        require(d.vendorId == AlltraxProtocol.VENDOR_ID && d.productId == AlltraxProtocol.PRODUCT_ID)
        require(manager.hasPermission(d)) { "USB permission not yet granted" }
        close()
        val c = manager.openDevice(d) ?: throw IOException("Unable to open USB device")
        var claimed = false
        try {
            for (i in 0 until d.interfaceCount) {
                val intf = d.getInterface(i)
                var epIn: UsbEndpoint? = null
                var epOut: UsbEndpoint? = null
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    // SR48400 descriptor: interrupt IN 0x81, interrupt OUT 0x01, 64 bytes.
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
                    if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
                    if (ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
                }
                if (epIn != null && epOut != null && c.claimInterface(intf, true)) {
                    connection = c
                    usbInterface = intf
                    incoming = epIn
                    outgoing = epOut
                    device = d
                    claimed = true
                    break
                }
            }
            if (!claimed) throw IOException("No claimable HID interrupt IN/OUT interface")
        } finally {
            if (!claimed) c.close()
        }
    }

    fun read(address: Long, count: Int): ByteArray {
        val c = connection ?: throw IOException("Controller disconnected")
        val epIn = incoming ?: throw IOException("HID input endpoint missing")
        val epOut = outgoing ?: throw IOException("HID output endpoint missing")
        val payload = AlltraxProtocol.readRequest(address, count)
        val inBuf = ByteBuffer.allocateDirect(64)
        val outBuf = ByteBuffer.allocateDirect(64)
        outBuf.put(payload)
        outBuf.flip()
        val inReq = UsbRequest()
        val outReq = UsbRequest()
        try {
            if (!inReq.initialize(c, epIn) || !outReq.initialize(c, epOut)) {
                throw IOException("Unable to initialize interrupt transfer requests")
            }
            // Queue input first to avoid dropping an immediate controller reply.
            if (!inReq.queue(inBuf)) throw IOException("USB IN queue failed")
            if (!outReq.queue(outBuf)) throw IOException("USB OUT queue failed")
            var inputDone = false
            var outputDone = false
            repeat(2) {
                val completed = try {
                    c.requestWait(2000L)
                } catch (e: TimeoutException) {
                    throw IOException("Alltrax HID timed out", e)
                } ?: throw IOException("HID request timed out")
                when (completed) {
                    inReq -> inputDone = true
                    outReq -> outputDone = true
                    else -> throw IOException("Unexpected USB request completion")
                }
            }
            if (!inputDone || !outputDone) throw IOException("Incomplete HID exchange")
            if (inBuf.position() != 64) throw IOException("Received ${inBuf.position()} HID bytes, expected 64")
            inBuf.flip()
            val response = ByteArray(64)
            inBuf.get(response)
            return AlltraxProtocol.parseReadResponse(response, count)
        } finally {
            inReq.cancel()
            outReq.cancel()
            inReq.close()
            outReq.close()
        }
    }

    override fun close() {
        val c = connection
        val intf = usbInterface
        connection = null
        usbInterface = null
        device = null
        incoming = null
        outgoing = null
        if (c != null) {
            if (intf != null) c.releaseInterface(intf)
            c.close()
        }
    }
}
