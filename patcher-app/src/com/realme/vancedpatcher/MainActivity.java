package com.realme.vancedpatcher;

import android.animation.ValueAnimator;
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
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    static final String ACTION_RESULT = "com.realme.vancedpatcher.INSTALL_RESULT";

    private static final String PREFS = "patcher";
    private static final String KEY_NOTE = "note";
    private static final String KEY_PENDING = "pending";
    private static final String KEY_START = "start";
    private static final String SUCCESS_MESSAGE =
            "Installed as com.realme.devanced.\n\n"
                    + "Now you can delete the original realme Link if you want.";

    private SharedPreferences prefs;
    private TextView noteView;
    private Button actionButton;
    private LinearLayout stepContainer;
    private final List<StepRow> rows = new ArrayList<>();
    private volatile boolean running;
    private boolean installMode;

    private static final class StepRow {
        LinearLayout root;
        TextView indicator;
        TextView label;
        ValueAnimator pulse;
    }

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
                    setNote("Confirm the install in the system dialog.");
                }
                return;
            }
            running = false;
            updateAction();
            if (status == PackageInstaller.STATUS_SUCCESS) {
                prefs.edit().putBoolean(KEY_PENDING, false).apply();
                setNote(SUCCESS_MESSAGE);
            } else {
                prefs.edit().putBoolean(KEY_PENDING, false).apply();
                setNote("Install failed (" + status + ")"
                        + (message == null ? "" : ": " + message));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildUi());

        String savedNote = prefs.getString(KEY_NOTE, "");
        if (!savedNote.isEmpty()) {
            setNote(savedNote);
        } else if (PatchEngine.patchedReady(this)) {
            setNote("Patched bundle ready.");
        }
        refreshState();

        IntentFilter filter = new IntentFilter(ACTION_RESULT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(resultReceiver, filter);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkPendingResult();
        refreshState();
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("Realme Devanced Patcher");
        title.setTextSize(26);
        title.setTextColor(Color.BLACK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(dp(24), dp(28), dp(24), 0);
        root.addView(title);

        TextView target = new TextView(this);
        target.setText("realme Link " + PatchEngine.TARGET_VERSION);
        target.setTextSize(13);
        target.setTextColor(Color.parseColor("#8A8A8A"));
        target.setPadding(dp(24), dp(6), dp(24), 0);
        root.addView(target);

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#EEEEEE"));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.topMargin = dp(20);
        divider.setLayoutParams(dividerParams);
        root.addView(divider);

        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scroll.setLayoutParams(scrollParams);
        scroll.setFillViewport(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(14), 0, dp(14));

        stepContainer = new LinearLayout(this);
        stepContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(stepContainer);

        noteView = new TextView(this);
        noteView.setTextSize(13);
        noteView.setTextColor(Color.parseColor("#555555"));
        noteView.setLineSpacing(0, 1.2f);
        noteView.setPadding(dp(24), dp(16), dp(24), 0);
        noteView.setVisibility(View.GONE);
        content.addView(noteView);

        scroll.addView(content);
        root.addView(scroll);

        actionButton = new Button(this);
        actionButton.setText("Patch realme Link");
        actionButton.setTextSize(15);
        actionButton.setAllCaps(false);
        actionButton.setTextColor(Color.WHITE);
        actionButton.setTypeface(null, android.graphics.Typeface.BOLD);
        actionButton.setBackground(rounded(Color.parseColor("#111111"), 16));
        actionButton.setStateListAnimator(null);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        buttonParams.setMargins(dp(24), dp(10), dp(24), dp(28));
        actionButton.setLayoutParams(buttonParams);
        actionButton.setOnClickListener(v -> onAction());
        root.addView(actionButton);

        return root;
    }

    private void refreshState() {
        installMode = PatchEngine.patchedReady(this);
        updateAction();
    }

    private void updateAction() {
        actionButton.setEnabled(!running);
        actionButton.setAlpha(running ? 0.4f : 1f);
        if (installMode) {
            actionButton.setText("Install");
            actionButton.setBackground(rounded(Color.parseColor("#0B57D0"), 16));
        } else {
            actionButton.setText("Patch realme Link");
            actionButton.setBackground(rounded(Color.parseColor("#111111"), 16));
        }
    }

    private void onAction() {
        if (running) {
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            setNote("Allow \"Install unknown apps\" for this patcher first.");
            openInstallPermission();
            return;
        }
        if (PatchEngine.patchedReady(this)) {
            installPatched();
        } else {
            startPatch();
        }
    }

    private void startPatch() {
        running = true;
        updateAction();
        rows.clear();
        stepContainer.removeAllViews();
        setNote("");
        prefs.edit().putString(KEY_NOTE, "").apply();

        new Thread(() -> PatchEngine.prepare(this, new PatchEngine.Callback() {
            @Override
            public void step(int index, String detail) {
                runOnUiThread(() -> showStep(index, detail, false));
            }

            @Override
            public void stepDone(int index) {
                runOnUiThread(() -> showStep(index, null, true));
            }

            @Override
            public void error(String message) {
                runOnUiThread(() -> {
                    running = false;
                    updateAction();
                    setNote("ERROR: " + message);
                });
            }

            @Override
            public void done() {
                runOnUiThread(() -> {
                    running = false;
                    installMode = true;
                    updateAction();
                    setNote("Patched and signed. Tap Install to continue.");
                });
            }
        }), "vanced-patch").start();
    }

    private void installPatched() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            setNote("Allow \"Install unknown apps\" for this patcher first.");
            openInstallPermission();
            return;
        }
        running = true;
        updateAction();
        setNote("Installing…");
        prefs.edit()
                .putBoolean(KEY_PENDING, true)
                .putLong(KEY_START, System.currentTimeMillis())
                .apply();
        PendingIntent callback = buildCallbackIntent();
        new Thread(() -> {
            try {
                Installer.install(this, PatchEngine.patchedFiles(this), callback);
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    running = false;
                    updateAction();
                    prefs.edit().putBoolean(KEY_PENDING, false).apply();
                    setNote("Install error: " + e.getMessage());
                });
            }
        }, "vanced-install").start();
    }

    private PendingIntent buildCallbackIntent() {
        Intent intent = new Intent(ACTION_RESULT).setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        return PendingIntent.getBroadcast(this, 0, intent, flags);
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
                prefs.edit().putBoolean(KEY_PENDING, false).apply();
                running = false;
                updateAction();
                setNote(SUCCESS_MESSAGE);
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // still installing, or the install failed
        }
    }

    private void showStep(int index, String detail, boolean done) {
        StepRow row = ensureRow(index);
        String label = PatchEngine.STEP_LABELS[index]
                + (detail == null ? "" : " — " + detail);
        row.label.setText(label);
        if (done) {
            stopPulse(row);
            row.indicator.setText("✓");
            row.indicator.setTextColor(Color.parseColor("#1E8E3E"));
            row.indicator.setAlpha(1f);
            row.root.setAlpha(1f);
        } else {
            row.indicator.setText("●");
            row.indicator.setTextColor(Color.parseColor("#0B57D0"));
            row.root.setAlpha(0f);
            row.root.setTranslationY(dp(6));
            row.root.animate().alpha(1f).translationY(0f)
                    .setDuration(220).start();
            startPulse(row);
        }
    }

    private StepRow ensureRow(int index) {
        while (rows.size() <= index) {
            rows.add(createRow());
        }
        return rows.get(index);
    }

    private StepRow createRow() {
        StepRow row = new StepRow();
        row.root = new LinearLayout(this);
        row.root.setOrientation(LinearLayout.HORIZONTAL);
        row.root.setGravity(Gravity.CENTER_VERTICAL);
        row.root.setPadding(dp(24), dp(11), dp(24), dp(11));

        row.indicator = new TextView(this);
        row.indicator.setTextSize(13);
        row.indicator.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams indicatorParams = new LinearLayout.LayoutParams(
                dp(28), ViewGroup.LayoutParams.WRAP_CONTENT);
        row.indicator.setLayoutParams(indicatorParams);
        row.indicator.setText("○");
        row.indicator.setTextColor(Color.parseColor("#BDBDBD"));
        row.root.addView(row.indicator);

        row.label = new TextView(this);
        row.label.setTextSize(14);
        row.label.setTextColor(Color.parseColor("#222222"));
        row.label.setLineSpacing(0, 1.15f);
        row.root.addView(row.label);

        stepContainer.addView(row.root);
        return row;
    }

    private void startPulse(StepRow row) {
        stopPulse(row);
        ValueAnimator animator = ValueAnimator.ofFloat(0.35f, 1f);
        animator.setDuration(650);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.addUpdateListener(a -> row.indicator.setAlpha((float) a.getAnimatedValue()));
        animator.start();
        row.pulse = animator;
    }

    private void stopPulse(StepRow row) {
        if (row.pulse != null) {
            row.pulse.cancel();
            row.pulse = null;
        }
    }

    private void openInstallPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void setNote(String text) {
        noteView.setText(text);
        noteView.setVisibility(text == null || text.isEmpty()
                ? View.GONE : View.VISIBLE);
        prefs.edit().putString(KEY_NOTE, text == null ? "" : text).apply();
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
