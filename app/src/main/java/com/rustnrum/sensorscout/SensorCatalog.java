package com.rustnrum.sensorscout;

import java.util.*;

/** Manufacturer-name SR/SPM register catalog recovered from supplied USBPort.dll.
 * Entries remain unvalidated on this particular cart until protocol and value checks pass.
 */
public final class SensorCatalog {
    private SensorCatalog() {}
    public static final class Block {
        public final String id; public final long address; public final int length;
        public Block(String id, long address, int length) { this.id=id; this.address=address; this.length=length; }
    }
    public static final List<Block> BLOCKS = Collections.unmodifiableList(Arrays.asList(
        new Block("power", 0x20004110L, 18),
        new Block("keys", 0x20004090L, 10),
        new Block("raw", 0x200040B0L, 20),
        new Block("average", 0x200040E0L, 20),
        new Block("extended", 0x20004122L, 30),
        new Block("faults", 0x20004000L, 15),
        new Block("history", 0x20004030L, 30),
        new Block("hardware", 0x08000480L, 24),
        new Block("identity", 0x08000400L, 52),
        new Block("trip_flags", 0x2000415AL, 4)
    ));
    public enum Kind { S8, U8, S16, U16, S32 }
    public enum Interpretation { ENGINEERING, RAW, BOOL, TEMP_C }
    public static final class Field {
        public final String name, block, unit; public final int offset; public final Kind kind;
        public final Interpretation interpretation; public final double multiplier, bias, min, max;
        public Field(String name, String block, int offset, Kind kind, Interpretation interpretation,
                     double multiplier, double bias, String unit, double min, double max) {
            this.name=name; this.block=block; this.offset=offset; this.kind=kind;
            this.interpretation=interpretation; this.multiplier=multiplier;
            this.bias=bias; this.unit=unit; this.min=min; this.max=max;
        }
    }
    private static Field f(String name, String block, int offset, Kind kind,
                           Interpretation interp, double multiplier, double bias,
                           String unit, double min, double max) {
        return new Field(name, block, offset, kind, interp, multiplier, bias, unit, min, max);
    }
    public static final List<Field> FIELDS;
    public static final String[] FAULTS = {
        "Global shutdown", "Hardware overcurrent", "Overcurrent retry", "Low battery",
        "High battery", "Precharge failure", "Overtemperature", "Undertemperature",
        "Throttle range alarm", "High throttle overrange", "High throttle underrange",
        "Low throttle overrange", "Low throttle underrange", "Start delay", "Bad configuration"
    };
    static {
        List<Field> a = new ArrayList<>();
        a.add(f("Battery voltage", "power", 0, Kind.S16, Interpretation.ENGINEERING, .1, 0, "V", 0, 100));
        a.add(f("Key switch voltage", "power", 2, Kind.S16, Interpretation.ENGINEERING, .1, 0, "V", 0, 100));
        a.add(f("Reverse voltage", "power", 4, Kind.S16, Interpretation.ENGINEERING, .1, 0, "V", 0, 100));
        a.add(f("Throttle local", "power", 6, Kind.S16, Interpretation.RAW, 1, 0, "MU", 0, 4095));
        a.add(f("Throttle position", "power", 8, Kind.S16, Interpretation.RAW, 1, 0, "MU", 0, 4095));
        a.add(f("Motor output current (NOT battery current)", "power", 10, Kind.S32, Interpretation.ENGINEERING, .1, 0, "A", -1000, 1200));
        a.add(f("Plug-braking current (SR unsupported)", "power", 14, Kind.S32, Interpretation.RAW, 1, 0, "raw", Integer.MIN_VALUE, Integer.MAX_VALUE));
        a.add(f("Key input", "keys", 0, Kind.U8, Interpretation.BOOL, 1, 0, "state", 0, 1));
        a.add(f("Reverse input", "keys", 1, Kind.U8, Interpretation.BOOL, 1, 0, "state", 0, 1));
        a.add(f("Relay on", "keys", 2, Kind.U16, Interpretation.BOOL, 1, 0, "state", 0, 1));
        a.add(f("Fan status", "keys", 4, Kind.U8, Interpretation.BOOL, 1, 0, "state", 0, 1));
        a.add(f("HPD ran", "keys", 5, Kind.U8, Interpretation.BOOL, 1, 0, "state", 0, 1));
        a.add(f("Bad variables code", "keys", 6, Kind.U16, Interpretation.RAW, 1, 0, "raw", 0, 65535));
        a.add(f("User1 input", "keys", 8, Kind.U8, Interpretation.BOOL, 1, 0, "state", 0, 1));
        String[] raw = {"Key ADC", "Reverse ADC", "Battery ADC", "B+ temp ADC", "M- temp ADC",
            "Motor Hall ADC", "A2 Hall ADC", "Throttle high ADC", "Throttle low ADC", "User1 ADC"};
        for(int i=0;i<raw.length;i++) a.add(f("Raw " + raw[i], "raw", i*2, Kind.S16,
            Interpretation.RAW, 1, 0, "ADC raw", Short.MIN_VALUE, Short.MAX_VALUE));
        for(int i=0;i<raw.length;i++) {
            boolean temp=(i==3||i==4);
            a.add(f("Average " + raw[i], "average", i*2, Kind.S16,
                temp ? Interpretation.TEMP_C : Interpretation.RAW,
                temp ? .1289 : 1, temp ? -527 : 0,
                temp ? "°C" : "ADC raw", temp ? -35 : Short.MIN_VALUE, temp ? 160 : Short.MAX_VALUE));
        }
        String[] ext={"Linearized throttle", "Throttle target voltage (internal)",
          "Throttle target current (internal)", "Throttle pointer", "Motor Hall zero",
          "Motor Hall zero temperature", "Hall temp correction", "A2 Hall zero",
          "A2 Hall zero temperature", "A2 Hall correction", "Thermal output limit", "Motor speed",
          "User1 voltage", "User1 input factory units", "Throttle before linearization"};
        for(int i=0;i<ext.length;i++) a.add(f(ext[i], "extended", i*2, Kind.S16,
            Interpretation.RAW, 1, 0, "controller units", Short.MIN_VALUE, Short.MAX_VALUE));
        for(int i=0;i<FAULTS.length;i++) {
            a.add(f("Fault: " + FAULTS[i], "faults", i, Kind.U8, Interpretation.BOOL,1,0,"state",0,1));
            a.add(f("Fault-history raw: " + FAULTS[i], "history", i*2, Kind.U16,
                Interpretation.RAW,1,0,"counter (unverified)",0,65535));
        }
        // DLL-derived names/addresses are known, but boolean width is not yet established.
        // Preserve raw 8-bit and 16-bit interpretations for investigation; DO NOT claim a trip.
        a.add(f("Hardware overcurrent flag candidate (byte)", "trip_flags", 0, Kind.U8, Interpretation.RAW,1,0,"raw; flag width unverified",0,255));
        a.add(f("Hardware overcurrent candidate (word)", "trip_flags", 0, Kind.U16, Interpretation.RAW,1,0,"raw; flag width unverified",0,65535));
        a.add(f("dI/dt overcurrent flag candidate (byte)", "trip_flags", 2, Kind.U8, Interpretation.RAW,1,0,"raw; flag width unverified",0,255));
        a.add(f("dI/dt overcurrent candidate (word)", "trip_flags", 2, Kind.U16, Interpretation.RAW,1,0,"raw; flag width unverified",0,65535));
        FIELDS=Collections.unmodifiableList(a);
    }
    public static final class Reading {
        public final Field field; public final Long raw; public final Double value; public final String status;
        public Reading(Field field, Long raw, Double value, String status){
            this.field=field; this.raw=raw; this.value=value; this.status=status;
        }
        public String display() {
            if (raw == null) return "unavailable";
            if (!status.equals("plausible") && !status.equals("raw-unverified")) return "INVALID: " + raw;
            if (field.interpretation==Interpretation.BOOL) return value==1 ? "ON" : "OFF";
            return (field.interpretation==Interpretation.RAW ? Long.toString(raw) :
                String.format(Locale.US, "%.2f", value)) + " " + field.unit;
        }
    }
    public static Reading decode(Field f, byte[] data) {
        int n=(f.kind==Kind.S32 ? 4 : (f.kind==Kind.U8 || f.kind==Kind.S8 ? 1 : 2));
        if (data==null || f.offset+n>data.length) return new Reading(f,null,null,"unavailable");
        long r;
        int b0=data[f.offset] & 0xff;
        if(f.kind==Kind.U8) r=b0;
        else if(f.kind==Kind.S8) r=(byte)b0;
        else if(f.kind==Kind.U16 || f.kind==Kind.S16){
            int v=b0 | (data[f.offset+1]&0xff)<<8;
            r=f.kind==Kind.S16 ? (short)v : v;
        } else {
            int v=b0 | (data[f.offset+1]&0xff)<<8 | (data[f.offset+2]&0xff)<<16 | (data[f.offset+3]&0xff)<<24;
            r=v;
        }
        double value=(r+f.bias)*f.multiplier;
        if(value<f.min || value>f.max || !Double.isFinite(value)) return new Reading(f,r,value,"invalid-range");
        return new Reading(f,r,value,f.interpretation==Interpretation.RAW ? "raw-unverified" : "plausible");
    }
}
