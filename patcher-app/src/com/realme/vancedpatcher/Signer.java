package com.realme.vancedpatcher;

import android.content.Context;

import com.android.apksig.ApkSigner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/** Signs an APK with the embedded personal key (assets/rea-mod.p12). */
final class Signer {

    private static final String ALIAS = "rea-mod";
    private static final String KEYSTORE = "rea-mod.p12";
    private static final String PASSFILE = "ks.pass";

    static void sign(Context ctx, File input, File output) throws Exception {
        char[] password = readPassword(ctx).toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = ctx.getAssets().open(KEYSTORE)) {
            ks.load(in, password);
        }
        String alias = ALIAS;
        if (!ks.containsAlias(alias)) {
            Enumeration<String> aliases = ks.aliases();
            if (!aliases.hasMoreElements()) {
                throw new IllegalStateException("empty keystore");
            }
            alias = aliases.nextElement();
        }
        PrivateKey key = (PrivateKey) ks.getKey(alias, password);
        Certificate[] chain = ks.getCertificateChain(alias);
        if (key == null || chain == null || chain.length == 0) {
            throw new IllegalStateException("bad signing key");
        }
        List<X509Certificate> certs = new ArrayList<>(chain.length);
        for (Certificate c : chain) {
            certs.add((X509Certificate) c);
        }

        ApkSigner.SignerConfig config = new ApkSigner.SignerConfig.Builder(
                alias, key, certs).build();

        ApkSigner signer = new ApkSigner.Builder(Collections.singletonList(config))
                .setInputApk(input)
                .setOutputApk(output)
                .setMinSdkVersion(26)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .setAlignmentPreserved(true)
                .setLibraryPageAlignmentBytes(4096)
                .build();
        signer.sign();
    }

    private static String readPassword(Context ctx) throws Exception {
        try (InputStream in = ctx.getAssets().open(PASSFILE)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8").trim();
        }
    }

    private Signer() {
    }
}
