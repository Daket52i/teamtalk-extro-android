package org.teamtalkextro.data;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dk.bearware.User;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Persistent local user notes stored in SharedPreferences ("extro_user_notes_json"),
 * keyed by lowercase trimmed username (or nickname fallback), included in JSON backup.
 */
public final class UserNotesStore {

    private static final Gson GSON = new Gson();

    private UserNotesStore() {
    }

    public static String normalizeKey(String key) {
        if (key == null) return "";
        return key.trim().toLowerCase(Locale.ROOT);
    }

    public static String keyForUser(User user) {
        if (user == null) return "";
        if (user.szUsername != null && !user.szUsername.trim().isEmpty()) {
            return normalizeKey(user.szUsername);
        }
        if (user.szNickname != null && !user.szNickname.trim().isEmpty()) {
            return normalizeKey(user.szNickname);
        }
        return "";
    }

    public static Map<String, String> getAllNotes(Context context) {
        Map<String, String> result = new TreeMap<>();
        if (context == null) return result;
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
            String raw = prefs.getString(Preferences.PREF_USER_NOTES_JSON, "{}");
            if (raw == null || raw.trim().isEmpty()) return result;
            JsonElement parsed = JsonParser.parseString(raw);
            if (parsed != null && parsed.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                    String k = normalizeKey(entry.getKey());
                    if (!k.isEmpty() && entry.getValue() != null && !entry.getValue().isJsonNull()) {
                        String val = entry.getValue().getAsString().trim();
                        if (!val.isEmpty()) {
                            result.put(k, val);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    public static String getNote(Context context, String key) {
        String norm = normalizeKey(key);
        if (norm.isEmpty()) return "";
        Map<String, String> all = getAllNotes(context);
        String val = all.get(norm);
        return val != null ? val : "";
    }

    public static String getNoteForUser(Context context, User user) {
        if (context == null || user == null) return "";
        Map<String, String> all = getAllNotes(context);
        if (all.isEmpty()) return "";
        if (user.szUsername != null && !user.szUsername.trim().isEmpty()) {
            String byUser = all.get(normalizeKey(user.szUsername));
            if (byUser != null && !byUser.isEmpty()) return byUser;
        }
        if (user.szNickname != null && !user.szNickname.trim().isEmpty()) {
            String byNick = all.get(normalizeKey(user.szNickname));
            if (byNick != null && !byNick.isEmpty()) return byNick;
        }
        return "";
    }

    public static void setNote(Context context, String key, String note) {
        if (context == null) return;
        String norm = normalizeKey(key);
        if (norm.isEmpty()) return;
        Map<String, String> all = getAllNotes(context);
        String trimmed = note != null ? note.trim() : "";
        if (trimmed.isEmpty()) {
            all.remove(norm);
        } else {
            all.put(norm, trimmed);
        }
        saveAll(context, all);
    }

    public static void removeNote(Context context, String key) {
        setNote(context, key, "");
    }

    public static JsonObject toJsonObject(Context context) {
        JsonObject obj = new JsonObject();
        for (Map.Entry<String, String> e : getAllNotes(context).entrySet()) {
            obj.addProperty(e.getKey(), e.getValue());
        }
        return obj;
    }

    public static int importFromJsonObject(Context context, JsonObject obj) {
        if (context == null || obj == null) return 0;
        Map<String, String> all = getAllNotes(context);
        int count = 0;
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            String k = normalizeKey(entry.getKey());
            if (!k.isEmpty() && entry.getValue() != null && !entry.getValue().isJsonNull()) {
                try {
                    String val = entry.getValue().getAsString().trim();
                    if (!val.isEmpty()) {
                        all.put(k, val);
                        count++;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        saveAll(context, all);
        return count;
    }

    private static void saveAll(Context context, Map<String, String> map) {
        JsonObject obj = new JsonObject();
        for (Map.Entry<String, String> e : map.entrySet()) {
            obj.addProperty(e.getKey(), e.getValue());
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        prefs.edit().putString(Preferences.PREF_USER_NOTES_JSON, GSON.toJson(obj)).apply();
    }
}
