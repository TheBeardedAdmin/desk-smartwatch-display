package com.fossil.deskalert;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Calendar;

public class SettingsManager {
    private static final String PREF_NAME = "DeskAlertSettings";
    private final SharedPreferences prefs;

    public SettingsManager(Context context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public int getDayStartHour() { return prefs.getInt("day_start_hour", 7); }
    public int getDayStartMinute() { return prefs.getInt("day_start_minute", 0); }

    public int getNightStartHour() { return prefs.getInt("night_start_hour", 21); }
    public int getNightStartMinute() { return prefs.getInt("night_start_minute", 30); }

    public int getAmbientTimeout() { return prefs.getInt("ambient_timeout", 15); }

    public int getPreset1Min() { return prefs.getInt("preset1_min", 5); }
    public String getPreset1Label() { return prefs.getString("preset1_lbl", "Quick Break"); }

    public int getPreset2Min() { return prefs.getInt("preset2_min", 10); }
    public String getPreset2Label() { return prefs.getString("preset2_lbl", "Short Task"); }

    public int getPreset3Min() { return prefs.getInt("preset3_min", 15); }
    public String getPreset3Label() { return prefs.getString("preset3_lbl", "Break / Call"); }

    public int getPreset4Min() { return prefs.getInt("preset4_min", 25); }
    public String getPreset4Label() { return prefs.getString("preset4_lbl", "Pomodoro"); }

    public String getNightColor() { return prefs.getString("night_color", "#CC2222"); }
    public String getDayColor() { return prefs.getString("day_color", "#FFFFFF"); }

    public float getDayDimBrightness() { return prefs.getFloat("day_dim_bright", 0.12f); }
    public float getNightDimBrightness() { return prefs.getFloat("night_dim_bright", 0.01f); }

    public boolean isNightTime() {
        Calendar cal = Calendar.getInstance();
        int nowMins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        int dayMins = getDayStartHour() * 60 + getDayStartMinute();
        int nightMins = getNightStartHour() * 60 + getNightStartMinute();

        if (dayMins < nightMins) {
            // Normal: day e.g. 7:00 to 21:30
            return nowMins < dayMins || nowMins >= nightMins;
        } else {
            // Inverted
            return nowMins >= nightMins && nowMins < dayMins;
        }
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("day_start", String.format("%02d:%02d", getDayStartHour(), getDayStartMinute()));
            json.put("night_start", String.format("%02d:%02d", getNightStartHour(), getNightStartMinute()));
            json.put("ambient_timeout", getAmbientTimeout());
            json.put("preset1_min", getPreset1Min());
            json.put("preset1_lbl", getPreset1Label());
            json.put("preset2_min", getPreset2Min());
            json.put("preset2_lbl", getPreset2Label());
            json.put("preset3_min", getPreset3Min());
            json.put("preset3_lbl", getPreset3Label());
            json.put("preset4_min", getPreset4Min());
            json.put("preset4_lbl", getPreset4Label());
            json.put("night_color", getNightColor());
            json.put("day_color", getDayColor());
            json.put("is_night_now", isNightTime());
        } catch (Exception ignored) {}
        return json;
    }

    public void updateFromJson(JSONObject json) {
        SharedPreferences.Editor editor = prefs.edit();
        try {
            if (json.has("day_start")) {
                String[] parts = json.getString("day_start").split(":");
                if (parts.length == 2) {
                    editor.putInt("day_start_hour", Integer.parseInt(parts[0].trim()));
                    editor.putInt("day_start_minute", Integer.parseInt(parts[1].trim()));
                }
            }
            if (json.has("night_start")) {
                String[] parts = json.getString("night_start").split(":");
                if (parts.length == 2) {
                    editor.putInt("night_start_hour", Integer.parseInt(parts[0].trim()));
                    editor.putInt("night_start_minute", Integer.parseInt(parts[1].trim()));
                }
            }
            if (json.has("ambient_timeout")) editor.putInt("ambient_timeout", json.getInt("ambient_timeout"));
            if (json.has("preset1_min")) editor.putInt("preset1_min", json.getInt("preset1_min"));
            if (json.has("preset1_lbl")) editor.putString("preset1_lbl", json.getString("preset1_lbl"));
            if (json.has("preset2_min")) editor.putInt("preset2_min", json.getInt("preset2_min"));
            if (json.has("preset2_lbl")) editor.putString("preset2_lbl", json.getString("preset2_lbl"));
            if (json.has("preset3_min")) editor.putInt("preset3_min", json.getInt("preset3_min"));
            if (json.has("preset3_lbl")) editor.putString("preset3_lbl", json.getString("preset3_lbl"));
            if (json.has("preset4_min")) editor.putInt("preset4_min", json.getInt("preset4_min"));
            if (json.has("preset4_lbl")) editor.putString("preset4_lbl", json.getString("preset4_lbl"));
            if (json.has("night_color")) editor.putString("night_color", json.getString("night_color"));
            if (json.has("day_color")) editor.putString("day_color", json.getString("day_color"));
        } catch (Exception ignored) {}
        editor.apply();
    }
}
