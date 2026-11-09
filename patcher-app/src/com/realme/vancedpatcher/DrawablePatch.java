package com.realme.vancedpatcher;

/**
 * res/drawable/setting_img.xml is a compiled vector with a green circle
 * (0xff78da59) and a white gear (0xffffffff).  For 5.8.811.2814 the typed
 * values sit at fixed offsets; both were verified against the original APK.
 */
final class DrawablePatch {

    private static final int CIRCLE_DATA = 1716;
    private static final int CIRCLE_TYPE = 1715;
    private static final int GEAR_DATA = 1816;
    private static final int GEAR_TYPE = 1815;

    static byte[] patch(byte[] data) {
        if (data.length <= GEAR_DATA + 4) {
            return data; // unexpected file, leave untouched
        }
        if (!(bytes(data, CIRCLE_DATA, 0xFF78DA59)
                && bytes(data, GEAR_DATA, 0xFFFFFFFF)
                && (data[CIRCLE_TYPE] & 0xFF) == 0x1D
                && (data[GEAR_TYPE] & 0xFF) == 0x1D)) {
            return data;
        }
        data[CIRCLE_TYPE] = 0x1C; // ARGB8 so zero alpha is honoured
        data[GEAR_TYPE] = 0x1C;
        put32(data, CIRCLE_DATA, 0x00000000);
        put32(data, GEAR_DATA, 0xFF000000);
        return data;
    }

    private static boolean bytes(byte[] b, int off, long value) {
        return (b[off] & 0xFF) == (value & 0xFF)
                && (b[off + 1] & 0xFF) == ((value >> 8) & 0xFF)
                && (b[off + 2] & 0xFF) == ((value >> 16) & 0xFF)
                && (b[off + 3] & 0xFF) == ((value >> 24) & 0xFF);
    }

    private static void put32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private DrawablePatch() {
    }
}
