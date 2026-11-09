package com.realme.vancedpatcher;

import java.util.HashMap;
import java.util.Map;

/** Exact string-pool replacements for the cloned package. */
final class RenameMap {

    static final String OLD_PACKAGE = "com.realme.link";
    static final String NEW_PACKAGE = "com.realme.devanced";

    private static final String[] AUTHORITIES = {
            "AcAccountBaseSdkInitProvider",
            "account.ams.AcAccountSDKInitProvider",
            "account.open.sdk.AcOpenDBContentProvider",
            "androidx-startup",
            "bg_process",
            "EventContentProvider",
            "FacebookInitProvider",
            "fileprovider",
            "firebaseinitprovider",
            "hybrid.imageCrop",
            "MicrosoftCognitiveServicesSpeech.InternalContentProvider",
            "MyDeviceAppProvider",
            "mydevice.shortcuts",
            "MyProcessProvider",
            "oplus.epona",
            "provider",
            "share_preference",
            "telecomprovider",
            "Track.BalanceEventProvider",
            "Track.MultiProcessSharedPreferencesProvider",
            "Track.TrackCommonProvider",
            "Track.TrackEventProvider",
            "TuyaMonitorProcessInitContentProvider",
            "TuyaOptimusProvider",
            "ty_share.provider",
            "UCAccountSDKInitProvider",
            "verify_sys_fileprovider",
            "WebPro.fileProvider",
    };

    // Class names, broadcast actions and the signing metadata blob are
    // intentionally NOT touched: only the package attribute, provider
    // authorities and the dynamic-receiver permission are remapped.
    static Map<String, String> build() {
        Map<String, String> map = new HashMap<>();
        map.put(OLD_PACKAGE, NEW_PACKAGE);
        map.put(OLD_PACKAGE + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                NEW_PACKAGE + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        for (String authority : AUTHORITIES) {
            map.put(OLD_PACKAGE + "." + authority, NEW_PACKAGE + "." + authority);
        }
        map.put("com.oplus.statistics.provider",
                NEW_PACKAGE + ".oplus.stats.s");
        map.put("com.facebook.app.FacebookContentProvider589687081742846",
                NEW_PACKAGE + "s.FacebookContentProvider589687081742846");
        return map;
    }

    private RenameMap() {
    }
}
