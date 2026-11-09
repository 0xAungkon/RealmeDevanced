package com.realme.vancedpatcher;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

/**
 * Resizes the string pool of a binary AndroidManifest.xml and rewrites every
 * pool string found in the supplied exact-match map.  Attribute indices stay
 * the same; only offsets and the XML size are recalculated.
 */
final class AxmlRename {

    static byte[] rename(byte[] data, Map<String, String> map) {
        if (data.length < 8) {
            throw new IllegalArgumentException("AXML too small");
        }
        int fileType = le16(data, 0);
        int fileHeader = le16(data, 2);
        if (fileType != 0x0003) {
            throw new IllegalArgumentException("not binary XML");
        }

        int po = fileHeader;
        int poolType = le16(data, po);
        int phdr = le16(data, po + 2);
        int psize = le32(data, po + 4);
        int scount = le32(data, po + 8);
        int stycount = le32(data, po + 12);
        int flags = le32(data, po + 16);
        int sstart = le32(data, po + 20);
        int stystart = le32(data, po + 24);
        if (poolType != 0x0001) {
            throw new IllegalArgumentException("manifest has no leading string pool");
        }
        boolean utf8 = (flags & 0x100) != 0;

        String[] strings = new String[scount];
        boolean changed = false;
        for (int i = 0; i < scount; i++) {
            int off = le32(data, po + phdr + 4 * i);
            String s = readString(data, utf8, po + sstart + off);
            String n = map.get(s);
            if (n != null) {
                s = n;
                changed = true;
            }
            strings[i] = s;
        }
        if (!changed) {
            return data;
        }

        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        int[] offsets = new int[scount];
        for (int i = 0; i < scount; i++) {
            offsets[i] = blob.size();
            byte[] enc = encodeString(strings[i], utf8);
            blob.write(enc, 0, enc.length);
        }
        while ((blob.size() & 3) != 0) {
            blob.write(0);
        }

        int styleOffsetsStart = po + phdr + 4 * scount;
        byte[] styleOffsets = Arrays.copyOfRange(data, styleOffsetsStart,
                styleOffsetsStart + 4 * stycount);
        byte[] stylesData = stycount > 0
                ? Arrays.copyOfRange(data, po + stystart, po + psize)
                : new byte[0];

        int stringsStart = phdr + 4 * scount + 4 * stycount;
        int stylesStart = stycount > 0 ? stringsStart + blob.size() : 0;
        int chunkSize = phdr + 4 * scount + 4 * stycount
                + blob.size() + stylesData.length;

        ByteArrayOutputStream chunk = new ByteArrayOutputStream(chunkSize + 8);
        write16(chunk, 0x0001);
        write16(chunk, phdr);
        write32(chunk, chunkSize);
        write32(chunk, scount);
        write32(chunk, stycount);
        write32(chunk, flags);
        write32(chunk, stringsStart);
        write32(chunk, stylesStart);
        for (int off : offsets) {
            write32(chunk, off);
        }
        chunk.write(styleOffsets, 0, styleOffsets.length);
        byte[] blobBytes = blob.toByteArray();
        chunk.write(blobBytes, 0, blobBytes.length);
        chunk.write(stylesData, 0, stylesData.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 256);
        out.write(data, 0, po);
        byte[] pool = chunk.toByteArray();
        out.write(pool, 0, pool.length);
        out.write(data, po + psize, data.length - (po + psize));

        byte[] result = out.toByteArray();
        put32(result, 4, result.length);
        return result;
    }

    private static String readString(byte[] b, boolean utf8, int start) {
        int q = start;
        if (utf8) {
            int[] r = readLen8(b, q);
            q = r[1];
            r = readLen8(b, q);
            int n = r[0];
            q = r[1];
            return new String(b, q, n, StandardCharsets.UTF_8);
        }
        int[] r = readLen16(b, q);
        int n = r[0];
        q = r[1];
        return new String(b, q, n * 2, StandardCharsets.UTF_16LE);
    }

    private static byte[] encodeString(String s, boolean utf8) {
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

    private static void writeLen8(ByteArrayOutputStream out, int n) {
        if (n > 0x7F) {
            out.write(0x80 | (n >> 8));
            out.write(n & 0xFF);
        } else {
            out.write(n);
        }
    }

    private static int[] readLen16(byte[] b, int q) {
        int n = le16(b, q);
        if ((n & 0x8000) != 0) {
            return new int[]{((n & 0x7FFF) << 16) | le16(b, q + 2), q + 4};
        }
        return new int[]{n, q + 2};
    }

    private static void writeLen16(ByteArrayOutputStream out, int n) {
        if (n > 0x7FFF) {
            write16(out, 0x8000 | (n >> 16));
            write16(out, n & 0xFFFF);
        } else {
            write16(out, n);
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

    private static void write16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private static void write32(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private AxmlRename() {
    }
}
