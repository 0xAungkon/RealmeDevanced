package com.realme.vancedpatcher;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Rebuilds an APK zip, replacing only the requested entries.  STORED native
 * libraries keep their storage method and are re-aligned to 4 KiB with a
 * padding extra field, because the app uses extractNativeLibs=false.
 */
final class ZipRewriter {

    private static final int PAGE = 4096;
    private static final byte[] BUFFER = new byte[1 << 16];

    interface Progress {
        void onEntry(String name);
    }

    static void rewrite(File srcFile, File dstFile,
                        Map<String, byte[]> replacements,
                        Progress progress) throws IOException {
        ZipFile src = new ZipFile(srcFile);
        CountingOutputStream counting = new CountingOutputStream(
                new BufferedOutputStream(new FileOutputStream(dstFile), 1 << 16));
        ZipOutputStream out = new ZipOutputStream(counting);
        out.setLevel(Deflater.BEST_SPEED);
        try {
            Enumeration<? extends ZipEntry> entries = src.entries();
            while (entries.hasMoreElements()) {
                ZipEntry srcEntry = entries.nextElement();
                String name = srcEntry.getName();
                byte[] replacement = replacements.get(name);
                ZipEntry dstEntry = new ZipEntry(name);
                if (srcEntry.getTime() != -1) {
                    dstEntry.setTime(srcEntry.getTime());
                }
                if (srcEntry.getComment() != null) {
                    dstEntry.setComment(srcEntry.getComment());
                }

                boolean dir = name.endsWith("/");
                long position = counting.getCount();
                if (replacement != null) {
                    int method = srcEntry.getMethod() == ZipEntry.STORED
                            ? ZipEntry.STORED : ZipEntry.DEFLATED;
                    dstEntry.setMethod(method);
                    if (method == ZipEntry.STORED) {
                        dstEntry.setSize(replacement.length);
                        dstEntry.setCompressedSize(replacement.length);
                        CRC32 crc = new CRC32();
                        crc.update(replacement);
                        dstEntry.setCrc(crc.getValue());
                        if (!dir) {
                            alignExtra(dstEntry, name, position);
                        }
                    }
                    out.putNextEntry(dstEntry);
                    out.write(replacement);
                    out.closeEntry();
                } else {
                    int method = srcEntry.getMethod();
                    dstEntry.setMethod(method);
                    if (method == ZipEntry.STORED) {
                        dstEntry.setSize(srcEntry.getSize());
                        dstEntry.setCompressedSize(srcEntry.getSize());
                        dstEntry.setCrc(srcEntry.getCrc());
                        if (!dir) {
                            alignExtra(dstEntry, name, position);
                        }
                    }
                    out.putNextEntry(dstEntry);
                    InputStream in = src.getInputStream(srcEntry);
                    try {
                        int n;
                        while ((n = in.read(BUFFER)) > 0) {
                            out.write(BUFFER, 0, n);
                        }
                    } finally {
                        in.close();
                    }
                    out.closeEntry();
                }
                if (progress != null) {
                    progress.onEntry(name);
                }
            }
        } finally {
            out.close();
            src.close();
        }
    }

    /**
     * Adds a padding extra field so the local file header + name + extra ends
     * on the required boundary.  resources.arsc needs 4 bytes (targetSdk 30+),
     * shared libraries need 4 KiB.  Extra length must be 0 or at least 4.
     */
    private static void alignExtra(ZipEntry entry, String name, long position) {
        int alignment = name.endsWith(".so") ? PAGE : 4;
        int nameLen = name.getBytes(StandardCharsets.UTF_8).length;
        long base = position + 30 + nameLen;
        int needed = (int) ((alignment - (base % alignment)) % alignment);
        int extraLen;
        if (needed == 0) {
            extraLen = 0;
        } else if (needed < 4) {
            extraLen = needed + alignment;
        } else {
            extraLen = needed;
        }
        if (extraLen == 0) {
            return;
        }
        byte[] extra = new byte[extraLen];
        int payload = extraLen - 4;
        extra[0] = (byte) 0xD9;
        extra[1] = 0x35;
        extra[2] = (byte) (payload & 0xFF);
        extra[3] = (byte) ((payload >> 8) & 0xFF);
        entry.setExtra(extra);
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        private long count;

        CountingOutputStream(OutputStream out) {
            super(out);
        }

        long getCount() {
            return count;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }

    private ZipRewriter() {
    }
}
