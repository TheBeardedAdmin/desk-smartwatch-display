package com.fossil.deskalert;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.Locale;

public class MainActivity extends Activity {
    private static final String TAG = "DeskTimerMain";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SettingsManager settings;

    private LinearLayout viewPresets;
    private LinearLayout gridPresets;
    private LinearLayout viewCountdown;
    private LinearLayout viewTimesUp;

    private TextClock clockTime;
    private TextClock clockDate;
    private TextView btnModeToggle;

    private TextView tvP1Val, tvP1Lbl;
    private TextView tvP2Val, tvP2Lbl;
    private TextView tvP3Val, tvP3Lbl;
    private TextView tvP4Val, tvP4Lbl;

    private TextView tvCountdownLabel;
    private TextView tvCountdownDigits;
    private TextView tvCompletedLabel;

    private boolean isStandby = true;
    private boolean manualNightOverride = false;
    private boolean hasManualOverride = false;

    private int totalSeconds = 0;
    private int remainingSeconds = 0;
    private boolean isTimerRunning = false;
    private String currentTimerLabel = "";

    private final Runnable ambientDismissRunnable = this::enterStandbyMode;

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isTimerRunning) return;

            remainingSeconds--;
            if (remainingSeconds <= 0) {
                onTimerFinished();
            } else {
                updateCountdownDisplay();
                handler.postDelayed(this, 1000L);
            }
        }
    };

    private final Runnable scheduleCheckRunnable = new Runnable() {
        @Override
        public void run() {
            applyNightDayTheme();
            handler.postDelayed(this, 60000L); // Check schedule every 60s
        }
    };

    private final Runnable burnInShiftRunnable = new Runnable() {
        @Override
        public void run() {
            if (viewPresets != null && viewPresets.getVisibility() == View.VISIBLE) {
                int dx = (int) (Math.random() * 5) - 2;
                int dy = (int) (Math.random() * 5) - 2;
                viewPresets.setPadding(16 + dx, 16 + dy, 16 - dx, 16 - dy);
            }
            handler.postDelayed(this, 180000L); // Shift every 3 minutes
        }
    };

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("com.fossil.deskalert.START_TIMER".equals(action)) {
                int seconds = intent.getIntExtra("seconds", 1500);
                String label = intent.getStringExtra("label");
                if (label == null) label = (seconds / 60) + " MIN TIMER";
                startTimer(seconds, label);
            } else if ("com.fossil.deskalert.SETTINGS_UPDATED".equals(action)) {
                hasManualOverride = false;
                loadPresetLabels();
                applyNightDayTheme();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new SettingsManager(this);

        // Keep screen on continuously
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );

        setContentView(R.layout.activity_main);

        viewPresets = findViewById(R.id.view_presets);
        gridPresets = findViewById(R.id.grid_presets);
        viewCountdown = findViewById(R.id.view_countdown);
        viewTimesUp = findViewById(R.id.view_times_up);

        clockTime = findViewById(R.id.clock_time);
        clockDate = findViewById(R.id.clock_date);
        btnModeToggle = findViewById(R.id.btn_mode_toggle);

        tvP1Val = findViewById(R.id.tv_p1_val);
        tvP1Lbl = findViewById(R.id.tv_p1_lbl);
        tvP2Val = findViewById(R.id.tv_p2_val);
        tvP2Lbl = findViewById(R.id.tv_p2_lbl);
        tvP3Val = findViewById(R.id.tv_p3_val);
        tvP3Lbl = findViewById(R.id.tv_p3_lbl);
        tvP4Val = findViewById(R.id.tv_p4_val);
        tvP4Lbl = findViewById(R.id.tv_p4_lbl);

        tvCountdownLabel = findViewById(R.id.tv_countdown_label);
        tvCountdownDigits = findViewById(R.id.tv_countdown_digits);
        tvCompletedLabel = findViewById(R.id.tv_completed_label);

        // Start background HTTP server
        Intent serviceIntent = new Intent(this, AlertService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // Tap screen in Standby wakes to Active mode
        viewPresets.setOnClickListener(v -> {
            if (isStandby) {
                enterActiveMode();
            } else {
                resetAmbientTimeout();
            }
        });

        // Quick timer preset clicks
        findViewById(R.id.btn_timer_p1).setOnClickListener(v -> startTimer(settings.getPreset1Min() * 60, settings.getPreset1Min() + " MIN " + settings.getPreset1Label().toUpperCase()));
        findViewById(R.id.btn_timer_p2).setOnClickListener(v -> startTimer(settings.getPreset2Min() * 60, settings.getPreset2Min() + " MIN " + settings.getPreset2Label().toUpperCase()));
        findViewById(R.id.btn_timer_p3).setOnClickListener(v -> startTimer(settings.getPreset3Min() * 60, settings.getPreset3Min() + " MIN " + settings.getPreset3Label().toUpperCase()));
        findViewById(R.id.btn_timer_p4).setOnClickListener(v -> startTimer(settings.getPreset4Min() * 60, settings.getPreset4Min() + " MIN " + settings.getPreset4Label().toUpperCase()));

        // Day/Night manual toggle
        btnModeToggle.setOnClickListener(v -> {
            boolean currentNight = isCurrentlyNight();
            hasManualOverride = true;
            manualNightOverride = !currentNight;
            applyNightDayTheme();
            resetAmbientTimeout();
        });

        btnModeToggle.setOnLongClickListener(v -> {
            hasManualOverride = false;
            applyNightDayTheme();
            resetAmbientTimeout();
            return true;
        });

        // Stop timer button
        findViewById(R.id.btn_cancel_timer).setOnClickListener(v -> stopTimer());

        // Dismiss Time's Up screen
        viewTimesUp.setOnClickListener(v -> enterStandbyMode());

        // Register broadcast receivers
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.fossil.deskalert.START_TIMER");
        filter.addAction("com.fossil.deskalert.SETTINGS_UPDATED");
        registerReceiver(updateReceiver, filter);

        loadPresetLabels();
        enterStandbyMode();

        handler.post(scheduleCheckRunnable);
        handler.postDelayed(burnInShiftRunnable, 180000L);
    }

    private boolean isCurrentlyNight() {
        if (hasManualOverride) return manualNightOverride;
        return settings.isNightTime();
    }

    private void loadPresetLabels() {
        tvP1Val.setText(settings.getPreset1Min() + "m");
        tvP1Lbl.setText(settings.getPreset1Label());

        tvP2Val.setText(settings.getPreset2Min() + "m");
        tvP2Lbl.setText(settings.getPreset2Label());

        tvP3Val.setText(settings.getPreset3Min() + "m");
        tvP3Lbl.setText(settings.getPreset3Label());

        tvP4Val.setText(settings.getPreset4Min() + "m");
        tvP4Lbl.setText(settings.getPreset4Label());
    }

    private void enterStandbyMode() {
        isStandby = true;
        handler.removeCallbacks(ambientDismissRunnable);

        gridPresets.setVisibility(View.GONE);
        clockTime.setTextSize(44);

        applyNightDayTheme();
    }

    private void enterActiveMode() {
        isStandby = false;

        // Reveal timer preset buttons
        gridPresets.setVisibility(View.VISIBLE);
        clockTime.setTextSize(36);

        applyNightDayTheme();
        resetAmbientTimeout();
    }

    private void resetAmbientTimeout() {
        handler.removeCallbacks(ambientDismissRunnable);
        int timeoutSec = settings.getAmbientTimeout();
        handler.postDelayed(ambientDismissRunnable, Math.max(timeoutSec, 8) * 1000L);
    }

    private void applyNightDayTheme() {
        boolean night = isCurrentlyNight();
        int nightCol = Color.parseColor(settings.getNightColor());

        // Keep screen brightness normal & consistent (do not drop to 1%)
        setScreenBrightness(1.0f);

        if (night) {
            // Night Mode: warm red / custom night color for night vision
            clockTime.setTextColor(nightCol);
            clockDate.setTextColor(Color.parseColor("#AA4444"));
            btnModeToggle.setText("Mode: Night");
            btnModeToggle.setTextColor(nightCol);
        } else {
            // Day Mode: crisp white and gray
            clockTime.setTextColor(Color.WHITE);
            clockDate.setTextColor(Color.parseColor("#8E8E93"));
            btnModeToggle.setText("Mode: Day");
            btnModeToggle.setTextColor(Color.parseColor("#30D158"));
        }
    }

    private void setScreenBrightness(float brightness) {
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = Math.max(0.01f, Math.min(1.0f, brightness));
            getWindow().setAttributes(lp);
        } catch (Exception ignored) {}
    }

    private void startTimer(int seconds, String label) {
        handler.removeCallbacks(timerRunnable);
        handler.removeCallbacks(ambientDismissRunnable);

        setScreenBrightness(0.85f);
        totalSeconds = seconds;
        remainingSeconds = seconds;
        currentTimerLabel = label;
        isTimerRunning = true;

        tvCountdownLabel.setText(label);
        updateCountdownDisplay();

        viewPresets.setVisibility(View.GONE);
        viewTimesUp.setVisibility(View.GONE);
        viewCountdown.setVisibility(View.VISIBLE);

        handler.postDelayed(timerRunnable, 1000L);
    }

    private void updateCountdownDisplay() {
        int m = remainingSeconds / 60;
        int s = remainingSeconds % 60;
        tvCountdownDigits.setText(String.format(Locale.US, "%02d:%02d", m, s));
    }

    private void stopTimer() {
        isTimerRunning = false;
        handler.removeCallbacks(timerRunnable);
        viewCountdown.setVisibility(View.GONE);
        viewTimesUp.setVisibility(View.GONE);
        viewPresets.setVisibility(View.VISIBLE);
        enterActiveMode();
    }

    private void onTimerFinished() {
        isTimerRunning = false;
        handler.removeCallbacks(timerRunnable);

        // Wake screen to full brightness
        setScreenBrightness(1.0f);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "DeskAlert:TimerWakeLock"
            );
            wl.acquire(15000L);
        }

        // Pulsing haptics
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            long[] pattern = new long[]{0, 500, 200, 500, 200, 800, 200, 800};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                v.vibrate(pattern, -1);
            }
        }

        tvCompletedLabel.setText(currentTimerLabel + " COMPLETED");
        viewCountdown.setVisibility(View.GONE);
        viewTimesUp.setVisibility(View.VISIBLE);

        // Return to standby after 30s
        handler.postDelayed(this::enterStandbyMode, 30000L);
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(updateReceiver);
        } catch (Exception ignored) {}
        handler.removeCallbacks(timerRunnable);
        handler.removeCallbacks(ambientDismissRunnable);
        handler.removeCallbacks(scheduleCheckRunnable);
        handler.removeCallbacks(burnInShiftRunnable);
        super.onDestroy();
    }
}
