package com.realme.vancedpatcher;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Rewrites one exact string in the global resources.arsc string pool. */
final class ArscRename {

    static byte[] rename(byte[] data, String oldText, String newText) {
        int tableHeader = le16(data, 2);
        int po = tableHeader;
        int poolType = le16(data, po);
        int phdr = le16(data, po + 2);
        int psize = le32(data, po + 4);
        int scount = le32(data, po + 8);
        int stycount = le32(data, po + 12);
        int flags = le32(data, po + 16);
        int sstart = le32(data, po + 20);
        int stystart = le32(data, po + 24);
        if (poolType != 0x0001) {
            throw new IllegalArgumentException("arsc has no global string pool");
        }
        boolean utf8 = (flags & 0x100) != 0;

        int found = -1;
        int foundStart = -1;
        int foundEnd = -1;
        for (int i = 0; i < scount; i++) {
            int off = le32(data, po + phdr + 4 * i);
            int start = po + sstart + off;
            int end = stringEnd(data, utf8, start);
            String s = readString(data, utf8, start);
            if (s.equals(oldText)) {
                if (found >= 0) {
                    throw new IllegalStateException("label string occurs more than once");
                }
                found = i;
                foundStart = start;
                foundEnd = end;
            }
        }
        if (found < 0) {
            throw new IllegalStateException("label string not found");
        }

        byte[] encoded = encode(newText, utf8);
        int delta = encoded.length - (foundEnd - foundStart);
        int pad = (4 - (delta & 3)) & 3;

        byte[] out = new byte[data.length + delta + pad];
        System.arraycopy(data, 0, out, 0, foundStart);
        System.arraycopy(encoded, 0, out, foundStart, encoded.length);
        System.arraycopy(data, foundEnd, out, foundStart + encoded.length,
                data.length - foundEnd);

        int insertAt = stystart + delta;
        if (pad > 0) {
            System.arraycopy(out, insertAt, out, insertAt + pad,
                    out.length - pad - insertAt);
        }

        // Shift offsets of all strings that follow the edited one.
        for (int j = found + 1; j < scount; j++) {
            int offPos = po + phdr + 4 * j;
            put32(out, offPos, le32(out, offPos) + delta);
        }
        put32(out, po + 4, psize + delta + pad);
        put32(out, po + 24, stystart + delta + pad);
        put32(out, 4, out.length);
        return out;
    }

    private static int stringEnd(byte[] b, boolean utf8, int start) {
        int q = start;
        if (utf8) {
            int[] r = readLen8(b, q);
            q = r[1];
            r = readLen8(b, q);
            return r[1] + r[0] + 1;
        }
        int[] r = readLen16(b, q);
        return r[1] + r[0] * 2 + 2;
    }

    private static String readString(byte[] b, boolean utf8, int start) {
        int q = start;
        if (utf8) {
            int[] r = readLen8(b, q);
            q = r[1];
            r = readLen8(b, q);
            return new String(b, r[1], r[0], StandardCharsets.UTF_8);
        }
        int[] r = readLen16(b, q);
        return new String(b, r[1], r[0] * 2, StandardCharsets.UTF_16LE);
    }

    private static byte[] encode(String s, boolean utf8) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (utf8) {
            byte[] raw = s.getBytes(StandardCharsets.UTF_8);
            int units = s.getBytes(StandardCharsets.UTF_16LE).length / 2;
            writeLen8(out, units);
            writeLen8(out, raw.length);
            out.write(raw, 0, raw.length);
            out.write(0);
        } else {
            byte[] raw = s.getBytes(StandardCharsets.UTF_16LE);
            writeLen16(out, raw.length / 2);
            out.write(raw, 0, raw.length);
            out.write(0);
            out.write(0);
        }
        return out.toByteArray();
    }

    private static int[] readLen8(byte[] b, int q) {
        int n = b[q] & 0xFF;
        if ((n & 0x80) != 0) {
            return new int[]{((n & 0x7F) << 8) | (b[q + 1] & 0xFF), q + 2};
        }
        return new int[]{n, q + 1};
    }

    private static int[] readLen16(byte[] b, int q) {
        int n = le16(b, q);
        if ((n & 0x8000) != 0) {
            return new int[]{((n & 0x7FFF) << 16) | le16(b, q + 2), q + 4};
        }
        return new int[]{n, q + 2};
    }

    private static void writeLen8(ByteArrayOutputStream out, int n) {
        if (n > 0x7F) {
            out.write(0x80 | (n >> 8));
            out.write(n & 0xFF);
        } else {
            out.write(n);
        }
    }

    private static void writeLen16(ByteArrayOutputStream out, int n) {
        if (n > 0x7FFF) {
            out.write((0x8000 | (n >> 16)) & 0xFF);
            out.write(((0x8000 | (n >> 16)) >> 8) & 0xFF);
            out.write(n & 0xFF);
            out.write((n >> 8) & 0xFF);
        } else {
            out.write(n & 0xFF);
            out.write((n >> 8) & 0xFF);
        }
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static void put32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private ArscRename() {
    }
}
