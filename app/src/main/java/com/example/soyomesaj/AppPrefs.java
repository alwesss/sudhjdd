package com.example.soyomesaj;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppPrefs {
    public static final String PREFS = "soyo_helper_prefs";
    public static final String KEY_TEMPLATE = "template";
    public static final String KEY_TITLE = "title";
    public static final String KEY_AUTO_OPEN = "auto_open_recommendation";
    public static final String KEY_FULL_AUTO = "full_auto_mode";
    public static final String KEY_VIP_ONLY = "vip_only";
    public static final String KEY_RUNTIME_STATUS = "runtime_status";
    public static final String KEY_RUNTIME_STATUS_AT = "runtime_status_at";
    public static final String KEY_HISTORY_RESET = "history_reset_nonce";

    public static final String DEFAULT_TEMPLATE = "{isim} {hitap} selamlarrrr";

    private final SharedPreferences prefs;

    public AppPrefs(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public SharedPreferences raw() {
        return prefs;
    }

    public boolean fullAuto() {
        return prefs.getBoolean(KEY_FULL_AUTO, false);
    }

    public boolean autoOpen() {
        return prefs.getBoolean(KEY_AUTO_OPEN, true);
    }

    public boolean vipOnly() {
        return prefs.getBoolean(KEY_VIP_ONLY, false);
    }

    public int titleIndex() {
        int value = prefs.getInt(KEY_TITLE, 0);
        return value < 0 || value > 2 ? 0 : value;
    }

    public String template() {
        String value = prefs.getString(KEY_TEMPLATE, DEFAULT_TEMPLATE);
        if (value == null || value.trim().isEmpty()) return DEFAULT_TEMPLATE;
        return value.trim();
    }

    public String buildMessage(String name) {
        String title = titleIndex() == 1 ? "Hanım" : titleIndex() == 2 ? "" : "Bey";
        return template()
                .replace("{isim}", name == null ? "" : name)
                .replace("{hitap}", title)
                .replaceAll("\\s+", " ")
                .trim();
    }

    public void setRuntimeStatus(String status) {
        String safe = status == null ? "" : status;
        String current = prefs.getString(KEY_RUNTIME_STATUS, "");
        if (safe.equals(current)) return;
        prefs.edit()
                .putString(KEY_RUNTIME_STATUS, safe)
                .putLong(KEY_RUNTIME_STATUS_AT, System.currentTimeMillis())
                .apply();
    }
}
