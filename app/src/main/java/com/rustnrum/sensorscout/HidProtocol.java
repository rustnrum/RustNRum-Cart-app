package com.rustnrum.sensorscout;

import java.util.Arrays;

/** Alltrax SR/SPM VID=23d4 PID=0001 HID read-only framing. No write report is defined. */
public final class HidProtocol {
    private HidProtocol() {}
    public static final int VENDOR = 0x23d4, PRODUCT = 0x0001;
    public static final int REPORT_SIZE = 64, MAX_BYTES = 56;

    public static byte[] readRequest(long address, int count) {
        if (address < 0 || address > 0xffffffffL || count < 1 || count > MAX_BYTES)
            throw new IllegalArgumentException("Invalid read address/count");
        // Deliberate address allowlist: manufacturer-defined SR/SR48400 data/identity only.
        if (!allowed(address, count)) throw new IllegalArgumentException("Address not in read-only allowlist");
        byte[] req = new byte[REPORT_SIZE];
        req[0] = 0x01; req[1] = (byte) count;
        req[4] = (byte)(address >>> 24); req[5] = (byte)(address >>> 16);
        req[6] = (byte)(address >>> 8); req[7] = (byte) address;
        int sum = 0;
        for (int i = 0; i < req.length; i++) if (i != 2 && i != 3) sum += req[i] & 0xff;
        req[2] = (byte) sum; req[3] = (byte)(sum >>> 8);
        return req;
    }

    private static boolean allowed(long a, int n) {
        long end = a + n;
        return (a >= 0x20004000L && end <= 0x20004160L)
            || (a >= 0x08000400L && end <= 0x08000498L);
    }

    /** Verified ACK with valid result code before exposing payload as telemetry. */
    public static byte[] parseResponse(byte[] report, int length) {
        if (report == null || report.length != REPORT_SIZE)
            throw new IllegalArgumentException("Expected 64-byte HID response, got " + (report == null ? "null" : report.length));
        if ((report[0] & 0xff) != 4 || (report[4] & 0xff) != 1)
            throw new IllegalArgumentException("Unexpected report/type: " + hex(report, 0, 8));
        if ((report[6] & 0xff) != 0)
            throw new IllegalArgumentException("Controller rejected read, status=" + (report[6] & 0xff));
        if ((report[1] & 0xff) < length || (report[1] & 0xff) > MAX_BYTES)
            throw new IllegalArgumentException("Invalid returned byte count=" + (report[1] & 0xff));
        return Arrays.copyOfRange(report, 8, 8 + length);
    }

    public static String hex(byte[] b, int offset, int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n && i + offset < b.length; ++i) {
            if (i > 0) s.append(' ');
            s.append(String.format(java.util.Locale.US, "%02x", b[offset + i] & 0xff));
        }
        return s.toString();
    }
}
