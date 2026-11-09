package com.realme.vancedpatcher;

import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageInstaller;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** Installs the signed base + splits through a PackageInstaller session. */
final class Installer {

    static final String NEW_PACKAGE = "com.realme.devanced";

    static void install(Context ctx, List<File> apks, PendingIntent callback)
            throws IOException {
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(NEW_PACKAGE);

        long total = 0;
        for (File f : apks) {
            total += f.length();
        }
        params.setSize(total);

        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try {
            for (File apk : apks) {
                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = session.openWrite(apk.getName(), 0, apk.length())) {
                    byte[] buffer = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                    out.flush();
                }
            }
            session.commit(callback.getIntentSender());
        } finally {
            session.close();
        }
    }

    private Installer() {
    }
}
