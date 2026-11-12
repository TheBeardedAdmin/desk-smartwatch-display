package com.fossil.deskalert;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class AlertActivity extends Activity {
    private static final String TAG = "DeskAlertActivity";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable dismissRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        setContentView(R.layout.activity_alert);

        View root = findViewById(R.id.alert_root);
        root.setOnClickListener(v -> finish());

        displayAlert(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        displayAlert(intent);
    }

    private void displayAlert(Intent intent) {
        if (dismissRunnable != null) {
            handler.removeCallbacks(dismissRunnable);
        }

        if (intent == null) return;

        String title = intent.getStringExtra("title");
        String message = intent.getStringExtra("message");
        String imageUrl = intent.getStringExtra("image");
        int duration = intent.getIntExtra("duration", 12);

        TextView tvTitle = findViewById(R.id.alert_title);
        TextView tvMessage = findViewById(R.id.alert_message);
        ImageView ivImage = findViewById(R.id.iv_image);

        if (title != null && !title.isEmpty()) {
            tvTitle.setText(title);
        } else {
            tvTitle.setText("Alert");
        }

        if (message != null && !message.isEmpty()) {
            tvMessage.setText(message);
        } else {
            tvMessage.setText("");
        }

        if (imageUrl != null && !imageUrl.isEmpty()) {
            ivImage.setVisibility(View.VISIBLE);
            loadImageAsync(imageUrl, ivImage);
        } else {
            ivImage.setVisibility(View.GONE);
        }

        dismissRunnable = this::finish;
        handler.postDelayed(dismissRunnable, Math.max(duration, 3) * 1000L);
    }

    private void loadImageAsync(String urlStr, ImageView imageView) {
        new Thread(() -> {
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setDoInput(true);
                conn.connect();
                try (InputStream is = conn.getInputStream()) {
                    Bitmap bmp = BitmapFactory.decodeStream(is);
                    if (bmp != null) {
                        handler.post(() -> imageView.setImageBitmap(bmp));
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to load alert image: " + e.getMessage());
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        if (dismissRunnable != null) {
            handler.removeCallbacks(dismissRunnable);
        }
        super.onDestroy();
    }
}
