package com.realme.vancedpatcher;

import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Pulls the installed realme Link APKs, patches them, signs and installs. */
final class PatchEngine {

    static final String TARGET_PACKAGE = "com.realme.link";
    static final String TARGET_VERSION = "5.8.811.2814";

    interface Callback {
        void progress(String message);

        void error(String message);
    }

    static void run(Context ctx, PendingIntent installerCallback, Callback cb) {
        try {
            doRun(ctx, installerCallback, cb);
        } catch (Throwable e) {
            android.util.Log.e("vanced-patcher", "patch failed", e);
            cb.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void doRun(Context ctx, PendingIntent installerCallback,
                              Callback cb) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        PackageInfo info;
        try {
            info = pm.getPackageInfo(TARGET_PACKAGE, 0);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("realme Link is not installed");
        }
        if (!TARGET_VERSION.equals(info.versionName)) {
            throw new IllegalStateException("unsupported realme Link version "
                    + info.versionName + " (need " + TARGET_VERSION + ")");
        }

        ApplicationInfo ai = info.applicationInfo;
        List<String> sources = new ArrayList<>();
        sources.add(ai.sourceDir);
        if (ai.splitSourceDirs != null) {
            for (String split : ai.splitSourceDirs) {
                if (split != null) {
                    sources.add(split);
                }
            }
        }

        File work = new File(ctx.getCacheDir(), "vanced");
        deleteRecursive(work);
        File unsigned = new File(work, "unsigned");
        File signed = new File(work, "signed");
        if (!unsigned.mkdirs() || !signed.mkdirs()) {
            throw new IOException("cannot create work directories");
        }

        Map<String, String> renameMap = RenameMap.build();
        List<File> signedApks = new ArrayList<>();
        int index = 0;
        for (String source : sources) {
            index++;
            File src = new File(source);
            String name = src.getName();
            cb.progress("[" + index + "/" + sources.size() + "] " + name);

            Map<String, byte[]> replacements = new HashMap<>();
            if ("base.apk".equals(name)) {
                cb.progress("  manifest + package rename");
                replacements.put("AndroidManifest.xml", AxmlRename.rename(
                        readEntry(src, "AndroidManifest.xml"), renameMap));

                cb.progress("  app label");
                replacements.put("resources.arsc", ArscRename.rename(
                        readEntry(src, "resources.arsc"),
                        "realme Link", "Realme Link DeVanced"));

                cb.progress("  settings icon");
                replacements.put("res/drawable/setting_img.xml", DrawablePatch.patch(
                        readEntry(src, "res/drawable/setting_img.xml")));

                cb.progress("  patched code");
                replacements.put("classes36.dex", readAsset(ctx, "classes36.dex"));
                replacements.put("classes38.dex", readAsset(ctx, "classes38.dex"));
                replacements.put("classes40.dex", readAsset(ctx, "classes40.dex"));
            } else {
                cb.progress("  split manifest");
                replacements.put("AndroidManifest.xml", AxmlRename.rename(
                        readEntry(src, "AndroidManifest.xml"), renameMap));
            }

            File unsignedApk = new File(unsigned, name);
            cb.progress("  repacking");
            ZipRewriter.rewrite(src, unsignedApk, replacements, null);

            File signedApk = new File(signed, name);
            cb.progress("  signing");
            Signer.sign(ctx, unsignedApk, signedApk);
            signedApks.add(signedApk);
        }

        cb.progress("Opening installer…");
        Installer.install(ctx, signedApks, installerCallback);
    }

    private static byte[] readEntry(File apk, String entryName) throws IOException {
        ZipFile zip = new ZipFile(apk);
        try {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new IOException("missing " + entryName + " in " + apk.getName());
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return readAll(in);
            }
        } finally {
            zip.close();
        }
    }

    private static byte[] readAsset(Context ctx, String name) throws IOException {
        try (InputStream in = ctx.getAssets().open(name)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1 << 16];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private PatchEngine() {
    }
}
