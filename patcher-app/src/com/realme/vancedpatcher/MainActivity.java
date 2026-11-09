package com.realme.vancedpatcher;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    static final String ACTION_RESULT = "com.realme.vancedpatcher.INSTALL_RESULT";

    private static final String PREFS = "patcher";
    private static final String KEY_LOG = "log";
    private static final String KEY_PENDING = "pending";
    private static final String KEY_START = "start";
    private static final String SUCCESS_MESSAGE =
            "\nInstalled as com.realme.devanced.\n\n"
                    + "Now you can delete the original realme Link if you want.\n";

    private TextView statusView;
    private Button patchButton;
    private Button grantButton;
    private SharedPreferences prefs;
    private volatile boolean running;

    private final BroadcastReceiver resultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    Integer.MIN_VALUE);
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    startActivity(confirm);
                    append("Confirm the install in the system dialog.\n");
                } else {
                    append("Installer needs confirmation, but no dialog intent was given.\n");
                }
                return;
            }
            running = false;
            patchButton.setEnabled(true);
            if (status == PackageInstaller.STATUS_SUCCESS) {
                finishSuccess();
            } else {
                prefs.edit().putBoolean(KEY_PENDING, false).apply();
                append("\nInstall failed (" + status + ")"
                        + (message == null ? "" : ": " + message) + "\n");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        statusView.setText(prefs.getString(KEY_LOG, ""));
        IntentFilter filter = new IntentFilter(ACTION_RESULT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(resultReceiver, filter);
        }
        refreshInstallPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshInstallPermission();
        checkPendingResult();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(resultReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        super.onDestroy();
    }

    private View buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("RealmeVanced Patcher");
        title.setTextSize(24);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Patches the installed realme Link " + PatchEngine.TARGET_VERSION
                + "\ninto a de-noised com.realme.devanced clone.");
        subtitle.setTextSize(14);
        subtitle.setPadding(0, dp(6), 0, dp(12));
        root.addView(subtitle);

        patchButton = new Button(this);
        patchButton.setText("Patch installed realme Link");
        patchButton.setOnClickListener(v -> startPatch());
        root.addView(patchButton);

        grantButton = new Button(this);
        grantButton.setText("Allow installs from this app");
        grantButton.setOnClickListener(v -> openInstallPermission());
        root.addView(grantButton);

        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scroll.setLayoutParams(scrollParams);

        statusView = new TextView(this);
        statusView.setTextSize(13);
        statusView.setTextIsSelectable(true);
        statusView.setGravity(Gravity.START);
        statusView.setPadding(0, dp(16), 0, 0);
        scroll.addView(statusView);
        root.addView(scroll);

        root.setBackgroundColor(Color.WHITE);
        return root;
    }

    private void startPatch() {
        if (running) {
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            append("Allow \"Install unknown apps\" for this patcher first.\n");
            openInstallPermission();
            return;
        }
        running = true;
        patchButton.setEnabled(false);
        statusView.setText("");
        prefs.edit()
                .putString(KEY_LOG, "")
                .putBoolean(KEY_PENDING, true)
                .putLong(KEY_START, System.currentTimeMillis())
                .apply();
        PendingIntent callback = buildCallbackIntent();
        Thread worker = new Thread(() -> PatchEngine.run(this, callback,
                new PatchEngine.Callback() {
                    @Override
                    public void progress(String message) {
                        append(message + "\n");
                    }

                    @Override
                    public void error(String message) {
                        runOnUiThread(() -> {
                            running = false;
                            patchButton.setEnabled(true);
                            append("\nERROR: " + message + "\n");
                        });
                    }
                }), "vanced-patch");
        worker.start();
    }

    private PendingIntent buildCallbackIntent() {
        Intent intent = new Intent(ACTION_RESULT).setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        return PendingIntent.getBroadcast(this, 0, intent, flags);
    }

    private void openInstallPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void refreshInstallPermission() {
        boolean allowed = getPackageManager().canRequestPackageInstalls();
        grantButton.setVisibility(allowed ? View.GONE : View.VISIBLE);
    }

    private void checkPendingResult() {
        if (prefs == null || !prefs.getBoolean(KEY_PENDING, false)) {
            return;
        }
        try {
            PackageInfo info = getPackageManager().getPackageInfo(
                    Installer.NEW_PACKAGE, 0);
            long started = prefs.getLong(KEY_START, 0);
            if (info.lastUpdateTime >= started - 5000) {
                finishSuccess();
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // still installing, or the install failed
        }
    }

    private void finishSuccess() {
        running = false;
        patchButton.setEnabled(true);
        if (!statusView.getText().toString()
                .contains("Installed as com.realme.devanced")) {
            append(SUCCESS_MESSAGE);
        }
        prefs.edit().putBoolean(KEY_PENDING, false).apply();
    }

    private void append(String text) {
        runOnUiThread(() -> {
            statusView.append(text);
            if (statusView.getParent() instanceof ScrollView) {
                ScrollView scroll = (ScrollView) statusView.getParent();
                scroll.fullScroll(View.FOCUS_DOWN);
            }
            prefs.edit().putString(KEY_LOG, statusView.getText().toString()).apply();
        });
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
