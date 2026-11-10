package com.realme.vancedpatcher;

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

/** Builds a patched, signed split bundle and stores it in the patcher. */
final class PatchEngine {

    static final String TARGET_PACKAGE = "com.realme.link";
    static final String TARGET_VERSION = "5.8.811.2814";

    /**
     * Bump whenever the embedded dex payload changes. Old bundles stored by a
     * previous patcher build no longer count as ready, so the user is forced to
     * re-patch instead of installing stale code.
     */
    static final String PAYLOAD_VERSION = "6";

    static final String[] STEP_LABELS = {
            "Reading installed realme Link",
            "Renaming package and authorities",
            "Renaming app label",
            "Patching settings icon",
            "Injecting de-noised code",
            "Repacking base APK",
            "Signing base APK",
            "Patching splits",
            "Saving patched bundle",
    };

    interface Callback {
        void step(int index, String detail);

        void stepDone(int index);

        void error(String message);

        void done();
    }

    static File patchedDir(Context ctx) {
        return new File(ctx.getFilesDir(), "patched");
    }

    static boolean patchedReady(Context ctx) {
        File dir = patchedDir(ctx);
        if (!new File(dir, "base.apk").isFile()) {
            return false;
        }
        File marker = new File(dir, "version.txt");
        if (!marker.isFile()) {
            return false;
        }
        try {
            byte[] raw = readAll(new java.io.FileInputStream(marker));
            return stamp().equals(new String(raw, "UTF-8").trim());
        } catch (IOException e) {
            return false;
        }
    }

    private static String stamp() {
        return TARGET_VERSION + "|" + PAYLOAD_VERSION;
    }

    static List<File> patchedFiles(Context ctx) {
        File dir = patchedDir(ctx);
        List<File> files = new ArrayList<>();
        File base = new File(dir, "base.apk");
        if (base.isFile()) {
            files.add(base);
        }
        File[] children = dir.listFiles();
        if (children != null) {
            java.util.Arrays.sort(children);
            for (File f : children) {
                if (f.isFile() && f.getName().startsWith("split_")
                        && f.getName().endsWith(".apk")) {
                    files.add(f);
                }
            }
        }
        return files;
    }

    static void prepare(Context ctx, Callback cb) {
        try {
            doPrepare(ctx, cb);
        } catch (Throwable e) {
            android.util.Log.e("vanced-patcher", "patch failed", e);
            cb.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void doPrepare(Context ctx, Callback cb) throws Exception {
        cb.step(0, null);
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
        cb.stepDone(0);

        File tmp = new File(ctx.getFilesDir(), "patched-tmp");
        deleteRecursive(tmp);
        if (!tmp.mkdirs()) {
            throw new IOException("cannot create work directory");
        }

        Map<String, String> renameMap = RenameMap.build();
        int splitCount = sources.size() - 1;
        int splitIndex = 0;

        for (String source : sources) {
            File src = new File(source);
            String name = src.getName();
            Map<String, byte[]> replacements = new HashMap<>();

            if ("base.apk".equals(name)) {
                cb.step(1, null);
                replacements.put("AndroidManifest.xml", AxmlRename.rename(
                        readEntry(src, "AndroidManifest.xml"), renameMap));
                cb.stepDone(1);

                cb.step(2, null);
                replacements.put("resources.arsc", ArscRename.rename(
                        readEntry(src, "resources.arsc"),
                        "realme Link", "Realme Link DeVanced"));
                cb.stepDone(2);

                cb.step(3, null);
                replacements.put("res/drawable/setting_img.xml", DrawablePatch.patch(
                        readEntry(src, "res/drawable/setting_img.xml")));
                cb.stepDone(3);

                cb.step(4, null);
                replacements.put("classes36.dex", readAsset(ctx, "classes36.dex"));
                replacements.put("classes37.dex", readAsset(ctx, "classes37.dex"));
                replacements.put("classes38.dex", readAsset(ctx, "classes38.dex"));
                replacements.put("classes40.dex", readAsset(ctx, "classes40.dex"));
                cb.stepDone(4);

                cb.step(5, null);
                File unsigned = new File(tmp, name + ".unsigned");
                ZipRewriter.rewrite(src, unsigned, replacements, null);
                cb.stepDone(5);

                cb.step(6, null);
                Signer.sign(ctx, unsigned, new File(tmp, name));
                unsigned.delete();
                cb.stepDone(6);
            } else {
                splitIndex++;
                cb.step(7, name + " (" + splitIndex + "/" + splitCount + ")");
                replacements.put("AndroidManifest.xml", AxmlRename.rename(
                        readEntry(src, "AndroidManifest.xml"), renameMap));
                File unsigned = new File(tmp, name + ".unsigned");
                ZipRewriter.rewrite(src, unsigned, replacements, null);
                Signer.sign(ctx, unsigned, new File(tmp, name));
                unsigned.delete();
            }
        }
        if (splitCount > 0) {
            cb.stepDone(7);
        } else {
            cb.step(7, "no splits installed");
            cb.stepDone(7);
        }

        cb.step(8, null);
        File out = patchedDir(ctx);
        deleteRecursive(out);
        if (!tmp.renameTo(out)) {
            throw new IOException("cannot store patched bundle");
        }
        try (FileOutputStream fos = new FileOutputStream(
                new File(out, "version.txt"))) {
            fos.write(stamp().getBytes("UTF-8"));
        }
        cb.stepDone(8);
        cb.done();
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
