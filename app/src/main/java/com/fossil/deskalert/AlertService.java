package com.fossil.deskalert;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class AlertService extends Service {
    private static final String TAG = "DeskAlertService";
    private static final int PORT = 8080;
    private static final String CHANNEL_ID = "desk_alert_service";

    private ServerSocket serverSocket;
    private boolean isRunning = false;
    private SettingsManager settingsManager;

    @Override
    public void onCreate() {
        super.onCreate();
        settingsManager = new SettingsManager(this);
        createNotificationChannel();
        startForeground(1001, createForegroundNotification());
        startHttpServer();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Desk Kiosk Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background HTTP listener for Desk Timer and Alerts");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification createForegroundNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("Desk Display Active")
                .setContentText("Settings Web UI on port " + PORT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();
    }

    private void startHttpServer() {
        isRunning = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                Log.i(TAG, "Server listening on port " + PORT);
                while (isRunning) {
                    try {
                        Socket client = serverSocket.accept();
                        handleClient(client);
                    } catch (Exception e) {
                        if (!isRunning) break;
                        Log.e(TAG, "Client socket error: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Server error: " + e.getMessage());
            }
        }).start();
    }

    private void handleClient(Socket socket) {
        new Thread(() -> {
            try (Socket clientSocket = socket;
                 BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
                 OutputStream out = clientSocket.getOutputStream()) {

                String requestLine = reader.readLine();
                if (requestLine == null || requestLine.isEmpty()) return;

                String[] parts = requestLine.split(" ");
                if (parts.length < 2) return;

                String method = parts[0];
                String rawPath = parts[1];

                int contentLength = 0;
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("content-length:")) {
                        try {
                            contentLength = Integer.parseInt(line.substring(15).trim());
                        } catch (Exception ignored) {}
                    }
                }

                StringBuilder body = new StringBuilder();
                if (contentLength > 0) {
                    char[] buffer = new char[contentLength];
                    int totalRead = 0;
                    while (totalRead < contentLength) {
                        int read = reader.read(buffer, totalRead, contentLength - totalRead);
                        if (read == -1) break;
                        totalRead += read;
                    }
                    body.append(buffer, 0, totalRead);
                }

                String path = rawPath;
                String queryString = "";
                int qIndex = rawPath.indexOf('?');
                if (qIndex != -1) {
                    path = rawPath.substring(0, qIndex);
                    queryString = rawPath.substring(qIndex + 1);
                }

                if (path.equals("/ping") || path.equals("/health")) {
                    sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}");
                    return;
                }

                // Web UI Dashboard / Settings
                if (path.equals("/") || path.equals("/settings") || path.equals("/index.html")) {
                    String html = getSettingsHtml();
                    sendResponse(out, 200, "text/html; charset=utf-8", html);
                    return;
                }

                // API: Get Settings
                if (path.equals("/api/settings") && method.equalsIgnoreCase("GET")) {
                    sendResponse(out, 200, "application/json", settingsManager.toJson().toString());
                    return;
                }

                // API: Save Settings
                if (path.equals("/api/settings") && method.equalsIgnoreCase("POST")) {
                    String bodyStr = body.toString().trim();
                    try {
                        JSONObject json = new JSONObject(bodyStr);
                        settingsManager.updateFromJson(json);
                        sendBroadcast(new Intent("com.fossil.deskalert.SETTINGS_UPDATED"));
                        sendResponse(out, 200, "application/json", "{\"status\":\"ok\",\"saved\":true}");
                    } catch (Exception e) {
                        sendResponse(out, 400, "application/json", "{\"error\":\"" + e.getMessage() + "\"}");
                    }
                    return;
                }

                if (path.equals("/timer")) {
                    int seconds = 1500;
                    String label = "TIMER";
                    Map<String, String> params = parseQuery(queryString);
                    if (params.containsKey("minutes")) {
                        try {
                            int m = Integer.parseInt(params.get("minutes"));
                            seconds = m * 60;
                            label = m + " MIN TIMER";
                        } catch (Exception ignored) {}
                    } else if (params.containsKey("seconds")) {
                        try {
                            seconds = Integer.parseInt(params.get("seconds"));
                            label = (seconds / 60) + " MIN TIMER";
                        } catch (Exception ignored) {}
                    }
                    if (params.containsKey("label")) label = params.get("label");

                    Intent timerIntent = new Intent("com.fossil.deskalert.START_TIMER");
                    timerIntent.putExtra("seconds", seconds);
                    timerIntent.putExtra("label", label);
                    sendBroadcast(timerIntent);
                    sendResponse(out, 200, "application/json", "{\"status\":\"ok\",\"timer_started\":" + seconds + "}");
                    return;
                }

                if (path.equals("/notify")) {
                    String title = "Home Assistant";
                    String message = "Alert received";
                    String image = "";
                    int duration = 12;
                    boolean vibrate = true;

                    Map<String, String> params = parseQuery(queryString);
                    if (params.containsKey("title")) title = params.get("title");
                    if (params.containsKey("message")) message = params.get("message");
                    if (params.containsKey("image")) image = params.get("image");
                    if (params.containsKey("duration")) {
                        try { duration = Integer.parseInt(params.get("duration")); } catch (Exception ignored) {}
                    }

                    String bodyStr = body.toString().trim();
                    if (bodyStr.startsWith("{")) {
                        try {
                            JSONObject json = new JSONObject(bodyStr);
                            if (json.has("title")) title = json.getString("title");
                            if (json.has("message")) message = json.getString("message");
                            if (json.has("image")) image = json.getString("image");
                            if (json.has("duration")) duration = json.getInt("duration");
                            if (json.has("vibrate")) vibrate = json.getBoolean("vibrate");
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to parse JSON body: " + e.getMessage());
                        }
                    }

                    triggerAlert(title, message, image, duration, vibrate);
                    sendResponse(out, 200, "application/json", "{\"status\":\"ok\",\"received\":true}");
                    return;
                }

                sendResponse(out, 404, "application/json", "{\"error\":\"not_found\"}");

            } catch (Exception e) {
                Log.e(TAG, "Client handling exception: " + e.getMessage());
            }
        }).start();
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            try {
                if (idx > 0) {
                    map.put(URLDecoder.decode(pair.substring(0, idx), "UTF-8"),
                            URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
                } else {
                    map.put(URLDecoder.decode(pair, "UTF-8"), "");
                }
            } catch (Exception ignored) {}
        }
        return map;
    }

    private void sendResponse(OutputStream out, int code, String contentType, String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + code + " OK\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private void triggerAlert(String title, String message, String image, int duration, boolean vibrate) {
        if (vibrate) {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(new long[]{0, 250, 100, 250}, -1));
                } else {
                    v.vibrate(new long[]{0, 250, 100, 250}, -1);
                }
            }
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "DeskAlert:WakeLock"
            );
            wl.acquire((duration + 2) * 1000L);
        }

        Intent intent = new Intent(this, AlertActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("title", title);
        intent.putExtra("message", message);
        intent.putExtra("image", image);
        intent.putExtra("duration", duration);
        startActivity(intent);
    }

    private String getSettingsHtml() {
        JSONObject j = settingsManager.toJson();
        String dayStart = j.optString("day_start", "07:00");
        String nightStart = j.optString("night_start", "21:30");
        int timeout = j.optInt("ambient_timeout", 15);
        int p1m = j.optInt("preset1_min", 5);
        String p1l = j.optString("preset1_lbl", "Quick Break");
        int p2m = j.optInt("preset2_min", 10);
        String p2l = j.optString("preset2_lbl", "Short Task");
        int p3m = j.optInt("preset3_min", 15);
        String p3l = j.optString("preset3_lbl", "Break / Call");
        int p4m = j.optInt("preset4_min", 25);
        String p4l = j.optString("preset4_lbl", "Pomodoro");
        String nightColor = j.optString("night_color", "#CC2222");
        boolean isNight = j.optBoolean("is_night_now", false);

        return "<!DOCTYPE html>\n" +
                "<html lang=\"en\">\n" +
                "<head>\n" +
                "<meta charset=\"utf-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
                "<title>Desk Smartwatch Display</title>\n" +
                "<style>\n" +
                "* { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }\n" +
                "body { background: #121214; color: #E5E5E7; padding: 24px; display: flex; justify-content: center; }\n" +
                ".container { max-width: 520px; width: 100%; }\n" +
                ".header { margin-bottom: 24px; text-align: center; }\n" +
                ".header h1 { font-size: 22px; font-weight: 700; color: #FFF; }\n" +
                ".header p { font-size: 13px; color: #8E8E93; margin-top: 4px; }\n" +
                ".badge { display: inline-block; padding: 4px 12px; border-radius: 12px; font-size: 12px; font-weight: 600; margin-top: 8px; }\n" +
                ".badge-day { background: #1C3322; color: #30D158; border: 1px solid #30D158; }\n" +
                ".badge-night { background: #331A1A; color: #FF453A; border: 1px solid #FF453A; }\n" +
                ".card { background: #1C1C1E; border: 1px solid #2C2C2E; border-radius: 14px; padding: 18px; margin-bottom: 18px; }\n" +
                ".card h2 { font-size: 15px; font-weight: 600; color: #FFF; margin-bottom: 14px; border-bottom: 1px solid #2C2C2E; padding-bottom: 8px; }\n" +
                ".row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }\n" +
                ".row:last-child { margin-bottom: 0; }\n" +
                "label { font-size: 13px; color: #AEAEC2; }\n" +
                "input[type=\"time\"], input[type=\"number\"], input[type=\"text\"], select {\n" +
                "  background: #2C2C2E; border: 1px solid #3A3A3C; color: #FFF; padding: 8px 12px; border-radius: 8px; font-size: 14px; outline: none; width: 140px; text-align: center;\n" +
                "}\n" +
                "input[type=\"color\"] { background: none; border: none; width: 44px; height: 32px; cursor: pointer; }\n" +
                ".grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }\n" +
                ".preset-box { background: #242426; border: 1px solid #343438; border-radius: 10px; padding: 10px; }\n" +
                ".preset-box input { width: 100%; margin-top: 4px; text-align: left; }\n" +
                ".btn { background: #0A84FF; color: #FFF; border: none; padding: 12px; border-radius: 10px; font-size: 15px; font-weight: 600; cursor: pointer; width: 100%; transition: 0.2s; }\n" +
                ".btn:hover { background: #0071E3; }\n" +
                ".btn-secondary { background: #2C2C2E; color: #E5E5E7; border: 1px solid #3A3A3C; margin-top: 8px; }\n" +
                ".toast { display: none; background: #30D158; color: #000; font-weight: 600; padding: 10px; border-radius: 8px; text-align: center; margin-bottom: 14px; font-size: 14px; }\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"container\">\n" +
                "  <div class=\"header\">\n" +
                "    <h1>Smartwatch Desk Display</h1>\n" +
                "    <p>Wear OS Smart Display</p>\n" +
                (isNight ? "    <span class=\"badge badge-night\">Currently in Night Mode</span>\n" : "    <span class=\"badge badge-day\">Currently in Day Mode</span>\n") +
                "  </div>\n" +
                "  <div id=\"toast\" class=\"toast\">Settings saved & updated on watch!</div>\n" +
                "  <form id=\"settingsForm\">\n" +
                "    <div class=\"card\">\n" +
                "      <h2>Day / Night Schedule</h2>\n" +
                "      <div class=\"row\">\n" +
                "        <label>Day Mode Starts (Morning):</label>\n" +
                "        <input type=\"time\" id=\"day_start\" name=\"day_start\" value=\"" + dayStart + "\">\n" +
                "      </div>\n" +
                "      <div class=\"row\">\n" +
                "        <label>Night Mode Starts (Evening):</label>\n" +
                "        <input type=\"time\" id=\"night_start\" name=\"night_start\" value=\"" + nightStart + "\">\n" +
                "      </div>\n" +
                "      <div class=\"row\">\n" +
                "        <label>Night Clock Digits Color:</label>\n" +
                "        <input type=\"color\" id=\"night_color\" name=\"night_color\" value=\"" + nightColor + "\">\n" +
                "      </div>\n" +
                "    </div>\n" +
                "    <div class=\"card\">\n" +
                "      <h2>Display & Standby Behavior</h2>\n" +
                "      <div class=\"row\">\n" +
                "        <label>Ambient Dim Timeout:</label>\n" +
                "        <select id=\"ambient_timeout\" name=\"ambient_timeout\">\n" +
                "          <option value=\"10\"" + (timeout == 10 ? " selected" : "") + ">10 seconds</option>\n" +
                "          <option value=\"15\"" + (timeout == 15 ? " selected" : "") + ">15 seconds (Default)</option>\n" +
                "          <option value=\"25\"" + (timeout == 25 ? " selected" : "") + ">25 seconds</option>\n" +
                "          <option value=\"45\"" + (timeout == 45 ? " selected" : "") + ">45 seconds</option>\n" +
                "        </select>\n" +
                "      </div>\n" +
                "    </div>\n" +
                "    <div class=\"card\">\n" +
                "      <h2>Quick Timer Presets</h2>\n" +
                "      <div class=\"grid\">\n" +
                "        <div class=\"preset-box\">\n" +
                "          <label>Preset 1 (Minutes / Label):</label>\n" +
                "          <input type=\"number\" id=\"preset1_min\" name=\"preset1_min\" value=\"" + p1m + "\">\n" +
                "          <input type=\"text\" id=\"preset1_lbl\" name=\"preset1_lbl\" value=\"" + p1l + "\">\n" +
                "        </div>\n" +
                "        <div class=\"preset-box\">\n" +
                "          <label>Preset 2 (Minutes / Label):</label>\n" +
                "          <input type=\"number\" id=\"preset2_min\" name=\"preset2_min\" value=\"" + p2m + "\">\n" +
                "          <input type=\"text\" id=\"preset2_lbl\" name=\"preset2_lbl\" value=\"" + p2l + "\">\n" +
                "        </div>\n" +
                "        <div class=\"preset-box\">\n" +
                "          <label>Preset 3 (Minutes / Label):</label>\n" +
                "          <input type=\"number\" id=\"preset3_min\" name=\"preset3_min\" value=\"" + p3m + "\">\n" +
                "          <input type=\"text\" id=\"preset3_lbl\" name=\"preset3_lbl\" value=\"" + p3l + "\">\n" +
                "        </div>\n" +
                "        <div class=\"preset-box\">\n" +
                "          <label>Preset 4 (Minutes / Label):</label>\n" +
                "          <input type=\"number\" id=\"preset4_min\" name=\"preset4_min\" value=\"" + p4m + "\">\n" +
                "          <input type=\"text\" id=\"preset4_lbl\" name=\"preset4_lbl\" value=\"" + p4l + "\">\n" +
                "        </div>\n" +
                "      </div>\n" +
                "    </div>\n" +
                "    <button type=\"submit\" class=\"btn\">Save Changes to Watch</button>\n" +
                "  </form>\n" +
                "  <div class=\"card\" style=\"margin-top: 18px;\">\n" +
                "    <h2>Test & Quick Actions</h2>\n" +
                "    <button class=\"btn btn-secondary\" onclick=\"testDoorbell()\">Ring / Trigger Test Doorbell Alert</button>\n" +
                "    <button class=\"btn btn-secondary\" onclick=\"testTimer()\">Start 25m Pomodoro on Watch</button>\n" +
                "  </div>\n" +
                "</div>\n" +
                "<script>\n" +
                "document.getElementById('settingsForm').addEventListener('submit', async (e) => {\n" +
                "  e.preventDefault();\n" +
                "  const data = {\n" +
                "    day_start: document.getElementById('day_start').value,\n" +
                "    night_start: document.getElementById('night_start').value,\n" +
                "    night_color: document.getElementById('night_color').value,\n" +
                "    ambient_timeout: parseInt(document.getElementById('ambient_timeout').value),\n" +
                "    preset1_min: parseInt(document.getElementById('preset1_min').value),\n" +
                "    preset1_lbl: document.getElementById('preset1_lbl').value,\n" +
                "    preset2_min: parseInt(document.getElementById('preset2_min').value),\n" +
                "    preset2_lbl: document.getElementById('preset2_lbl').value,\n" +
                "    preset3_min: parseInt(document.getElementById('preset3_min').value),\n" +
                "    preset3_lbl: document.getElementById('preset3_lbl').value,\n" +
                "    preset4_min: parseInt(document.getElementById('preset4_min').value),\n" +
                "    preset4_lbl: document.getElementById('preset4_lbl').value\n" +
                "  };\n" +
                "  try {\n" +
                "    const res = await fetch('/api/settings', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) });\n" +
                "    if (res.ok) {\n" +
                "      const toast = document.getElementById('toast');\n" +
                "      toast.style.display = 'block';\n" +
                "      setTimeout(() => { toast.style.display = 'none'; location.reload(); }, 2000);\n" +
                "    }\n" +
                "  } catch (err) { alert('Error: ' + err); }\n" +
                "});\n" +
                "async function testDoorbell() {\n" +
                "  await fetch('/notify', { method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({ title: 'Front Doorbell', message: 'Test Alert from Web UI', duration: 10 }) });\n" +
                "}\n" +
                "async function testTimer() {\n" +
                "  await fetch('/timer?minutes=25&label=POMODORO', { method: 'POST' });\n" +
                "}\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
