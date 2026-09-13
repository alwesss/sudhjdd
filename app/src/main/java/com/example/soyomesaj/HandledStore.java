package com.example.soyomesaj;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class HandledStore {
    private static final String PREFIX = "handled_recent::";
    private static final long TTL_MS = 8L * 60L * 60L * 1000L;

    private final SharedPreferences prefs;

    public HandledStore(Context context) {
        prefs = context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE);
    }

    public void mark(String name) {
        String key = NameCleaner.key(name);
        if (key.isEmpty()) return;
        prefs.edit().putLong(PREFIX + key, System.currentTimeMillis()).apply();
    }

    public boolean isHandled(String name) {
        String key = NameCleaner.key(name);
        if (key.isEmpty()) return true;
        String prefKey = PREFIX + key;
        long when = prefs.getLong(prefKey, 0L);
        if (when <= 0L) return false;
        if (System.currentTimeMillis() - when <= TTL_MS) return true;
        prefs.edit().remove(prefKey).apply();
        return false;
    }

    public int count() {
        cleanupExpired();
        int count = 0;
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(PREFIX)) count++;
        }
        return count;
    }

    public void clear() {
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : new ArrayList<>(prefs.getAll().keySet())) {
            if (key.startsWith(PREFIX)) editor.remove(key);
        }
        editor.apply();
    }

    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        Map<String, ?> all = prefs.getAll();
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            if (!entry.getKey().startsWith(PREFIX)) continue;
            Object value = entry.getValue();
            long when = value instanceof Long ? (Long) value : 0L;
            if (when <= 0L || now - when > TTL_MS) expired.add(entry.getKey());
        }
        if (expired.isEmpty()) return;
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : expired) editor.remove(key);
        editor.apply();
    }
}
