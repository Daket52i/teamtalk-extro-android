package org.teamtalkextro.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;
import androidx.preference.PreferenceManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import org.teamtalkextro.backend.VoiceChanger;

/**
 * Unified cross-platform JSON Backup & Restore for TeamTalk Extro (Android & Windows).
 *
 * Exports saved servers, Extro audio/karaoke presets, and Android SharedPreferences.
 * Seamlessly imports backups created on either Android or Windows.
 */
public final class SettingsBackupManager {

    public static final String BACKUP_FORMAT = "teamtalk-extro-backup";
    public static final int BACKUP_VERSION = 1;
    private static final String SERVERLIST_PREFS = "serverlist";

    public static final class ExportResult {
        public final boolean success;
        public final int serverCount;
        public final int prefCount;
        public final String errorMessage;

        public ExportResult(boolean success, int serverCount, int prefCount, String errorMessage) {
            this.success = success;
            this.serverCount = serverCount;
            this.prefCount = prefCount;
            this.errorMessage = errorMessage;
        }
    }

    public static final class ImportResult {
        public final boolean success;
        public final String sourcePlatform;
        public final int serversAdded;
        public final int serversSkipped;
        public final int prefsRestored;
        public final String errorMessage;

        public ImportResult(boolean success, String sourcePlatform, int serversAdded,
                            int serversSkipped, int prefsRestored, String errorMessage) {
            this.success = success;
            this.sourcePlatform = sourcePlatform;
            this.serversAdded = serversAdded;
            this.serversSkipped = serversSkipped;
            this.prefsRestored = prefsRestored;
            this.errorMessage = errorMessage;
        }
    }

    private SettingsBackupManager() {
    }

    public static String getDefaultBackupFileName() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US);
        return "teamtalk-extro-backup-" + sdf.format(new Date()) + ".json";
    }

    public static ExportResult exportToUri(Context context, Uri uri) {
        if (context == null || uri == null) {
            return new ExportResult(false, 0, 0, "Invalid destination");
        }
        try {
            JsonObject root = new JsonObject();
            root.addProperty("format", BACKUP_FORMAT);
            root.addProperty("version", BACKUP_VERSION);
            root.addProperty("platform", "android");

            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            root.addProperty("exported_at", iso.format(new Date()));

            // 1. Saved servers
            List<ServerEntry> savedServers = loadSavedServers(context);
            JsonArray serversArr = new JsonArray();
            for (ServerEntry se : savedServers) {
                JsonObject obj = new JsonObject();
                obj.addProperty("name", safe(se.servername));
                obj.addProperty("host", safe(se.ipaddr));
                obj.addProperty("tcp_port", se.tcpport > 0 ? se.tcpport : 10333);
                obj.addProperty("udp_port", se.udpport > 0 ? se.udpport : 10333);
                obj.addProperty("encrypted", se.encrypted);
                obj.addProperty("username", safe(se.username));
                obj.addProperty("password", safe(se.password));
                obj.addProperty("nickname", safe(se.nickname));
                obj.addProperty("channel", safe(se.channel));
                obj.addProperty("channel_password", safe(se.chanpasswd));
                serversArr.add(obj);
            }
            root.add("servers", serversArr);

            // 2. Unified cross-platform Extro settings
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
            JsonObject extro = new JsonObject();
            extro.addProperty("voice_changer_mode", readIntSafe(prefs, Preferences.PREF_VOICE_CHANGER_MODE, 0));
            extro.addProperty("echo_enabled", prefs.getBoolean(Preferences.PREF_VOICE_ECHO_ENABLED, true));
            extro.addProperty("echo_level", readIntSafe(prefs, Preferences.PREF_VOICE_ECHO_LEVEL, 35));
            extro.addProperty("reverb_enabled", prefs.getBoolean(Preferences.PREF_VOICE_REVERB_ENABLED, true));
            extro.addProperty("reverb_level", readIntSafe(prefs, Preferences.PREF_VOICE_REVERB_LEVEL, 50));
            extro.addProperty("reverb_room_size", readIntSafe(prefs, Preferences.PREF_VOICE_REVERB_ROOM_SIZE, 60));
            extro.addProperty("autotune_scale", readIntSafe(prefs, Preferences.PREF_VOICE_AUTOTUNE_SCALE, 0));
            extro.addProperty("karaoke_volume", readIntSafe(prefs, Preferences.PREF_KARAOKE_GAIN, 75));
            extro.addProperty("karaoke_monitor", prefs.getBoolean(Preferences.PREF_KARAOKE_MONITOR, true));
            extro.addProperty("karaoke_ducking", prefs.getBoolean(Preferences.PREF_KARAOKE_DUCKING, false));
            extro.addProperty("karaoke_pitch", readIntSafe(prefs, Preferences.PREF_KARAOKE_PITCH, 0));
            extro.addProperty("karaoke_auto_status", prefs.getBoolean(Preferences.PREF_KARAOKE_AUTO_STATUS, true));
            root.add("extro", extro);

            // 3. Full Android SharedPreferences + type metadata
            JsonObject androidPrefs = new JsonObject();
            JsonObject androidPrefTypes = new JsonObject();
            Map<String, ?> all = prefs.getAll();
            int prefCount = 0;
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                String k = entry.getKey();
                Object v = entry.getValue();
                if (k == null || v == null) {
                    continue;
                }
                if (v instanceof Boolean) {
                    androidPrefs.addProperty(k, (Boolean) v);
                    androidPrefTypes.addProperty(k, "boolean");
                    prefCount++;
                } else if (v instanceof Integer) {
                    androidPrefs.addProperty(k, (Integer) v);
                    androidPrefTypes.addProperty(k, "int");
                    prefCount++;
                } else if (v instanceof Long) {
                    androidPrefs.addProperty(k, (Long) v);
                    androidPrefTypes.addProperty(k, "long");
                    prefCount++;
                } else if (v instanceof Float) {
                    androidPrefs.addProperty(k, (Float) v);
                    androidPrefTypes.addProperty(k, "float");
                    prefCount++;
                } else if (v instanceof String) {
                    androidPrefs.addProperty(k, (String) v);
                    androidPrefTypes.addProperty(k, "string");
                    prefCount++;
                } else if (v instanceof Set<?>) {
                    JsonArray arr = new JsonArray();
                    for (Object item : (Set<?>) v) {
                        if (item != null) {
                            arr.add(String.valueOf(item));
                        }
                    }
                    androidPrefs.add(k, arr);
                    androidPrefTypes.addProperty(k, "string_set");
                    prefCount++;
                }
            }
            root.add("android_preferences", androidPrefs);
            root.add("android_preference_types", androidPrefTypes);

            // 4. Embedded TTMediaBot profiles
            try {
                root.add("bot_profiles", org.teamtalkextro.bot.BotConfig.toJsonArray(context));
            } catch (Throwable ignored) {
            }

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String json = gson.toJson(root);

            try (OutputStream os = context.getContentResolver().openOutputStream(uri, "wt")) {
                if (os == null) {
                    return new ExportResult(false, 0, 0, "Cannot open output stream");
                }
                os.write(json.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            return new ExportResult(true, savedServers.size(), prefCount, null);
        } catch (Throwable t) {
            return new ExportResult(false, 0, 0, t.getMessage() != null ? t.getMessage() : "Export failed");
        }
    }

    public static ImportResult importFromUri(Context context, Uri uri) {
        if (context == null || uri == null) {
            return new ImportResult(false, "", 0, 0, 0, "Invalid source");
        }
        try {
            StringBuilder sb = new StringBuilder();
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                if (is == null) {
                    return new ImportResult(false, "", 0, 0, 0, "Cannot open file");
                }
                BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }

            JsonElement parsed = JsonParser.parseString(sb.toString());
            if (parsed == null || !parsed.isJsonObject()) {
                return new ImportResult(false, "", 0, 0, 0, "Invalid JSON format");
            }
            JsonObject root = parsed.getAsJsonObject();

            String format = getString(root, "format", "");
            boolean hasKnownSections = root.has("servers") || root.has("extro") || root.has("android_preferences");
            if (!BACKUP_FORMAT.equals(format) && !hasKnownSections) {
                return new ImportResult(false, "", 0, 0, 0, "Unrecognized backup format");
            }
            String platform = getString(root, "platform", "unknown");

            // 1. Merge servers without duplicates
            int added = 0;
            int skipped = 0;
            if (root.has("servers") && root.get("servers").isJsonArray()) {
                List<ServerEntry> existing = loadSavedServers(context);
                JsonArray arr = root.getAsJsonArray("servers");
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    JsonObject obj = el.getAsJsonObject();
                    String host = getString(obj, "host", "").trim();
                    if (TextUtils.isEmpty(host)) continue;
                    int tcp = getInt(obj, "tcp_port", 10333);
                    int udp = getInt(obj, "udp_port", tcp);
                    String name = getString(obj, "name", "");
                    if (TextUtils.isEmpty(name)) {
                        name = host + ":" + tcp;
                    }
                    String username = getString(obj, "username", "");

                    boolean dup = false;
                    for (ServerEntry s : existing) {
                        if (s.ipaddr != null && s.ipaddr.equalsIgnoreCase(host)
                                && s.tcpport == tcp
                                && safe(s.username).equalsIgnoreCase(username)) {
                            dup = true;
                            break;
                        }
                    }
                    if (dup) {
                        skipped++;
                        continue;
                    }
                    ServerEntry se = new ServerEntry();
                    se.servername = name;
                    se.ipaddr = host;
                    se.tcpport = tcp;
                    se.udpport = udp;
                    se.encrypted = getBoolean(obj, "encrypted", false);
                    se.username = username;
                    se.password = getString(obj, "password", "");
                    se.nickname = getString(obj, "nickname", "");
                    se.channel = getString(obj, "channel", "");
                    se.chanpasswd = getString(obj, "channel_password", "");
                    se.rememberLastChannel = true;
                    se.servertype = ServerEntry.ServerType.LOCAL;
                    existing.add(se);
                    added++;
                }
                saveSavedServers(context, existing);
            }

            // 2. Restore android_preferences (if present)
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
            SharedPreferences.Editor editor = prefs.edit();
            int prefsRestored = 0;

            JsonObject types = (root.has("android_preference_types") && root.get("android_preference_types").isJsonObject())
                    ? root.getAsJsonObject("android_preference_types") : null;

            if (root.has("android_preferences") && root.get("android_preferences").isJsonObject()) {
                JsonObject ap = root.getAsJsonObject("android_preferences");
                for (Map.Entry<String, JsonElement> entry : ap.entrySet()) {
                    String k = entry.getKey();
                    JsonElement el = entry.getValue();
                    if (k == null || el == null || el.isJsonNull()) continue;
                    String explicitType = types != null ? getString(types, k, "") : "";

                    if ("string_set".equals(explicitType) || el.isJsonArray()) {
                        Set<String> set = new HashSet<>();
                        if (el.isJsonArray()) {
                            for (JsonElement item : el.getAsJsonArray()) {
                                if (item != null && !item.isJsonNull()) {
                                    set.add(item.getAsString());
                                }
                            }
                        }
                        editor.putStringSet(k, set);
                        prefsRestored++;
                    } else if (el.isJsonPrimitive()) {
                        JsonPrimitive p = el.getAsJsonPrimitive();
                        if ("boolean".equals(explicitType) || p.isBoolean()) {
                            editor.putBoolean(k, p.getAsBoolean());
                            prefsRestored++;
                        } else if ("int".equals(explicitType)) {
                            editor.putInt(k, p.getAsInt());
                            prefsRestored++;
                        } else if ("long".equals(explicitType)) {
                            editor.putLong(k, p.getAsLong());
                            prefsRestored++;
                        } else if ("float".equals(explicitType)) {
                            editor.putFloat(k, p.getAsFloat());
                            prefsRestored++;
                        } else if ("string".equals(explicitType) || p.isString()) {
                            editor.putString(k, p.getAsString());
                            prefsRestored++;
                        } else if (p.isNumber()) {
                            double d = p.getAsDouble();
                            if (d == Math.rint(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
                                editor.putInt(k, (int) d);
                            } else {
                                editor.putFloat(k, (float) d);
                            }
                            prefsRestored++;
                        }
                    }
                }
            }

            // 3. Restore unified cross-platform Extro settings (both Windows & Android backups!)
            if (root.has("extro") && root.get("extro").isJsonObject()) {
                JsonObject extro = root.getAsJsonObject("extro");
                if (extro.has("voice_changer_mode")) {
                    editor.putInt(Preferences.PREF_VOICE_CHANGER_MODE, getInt(extro, "voice_changer_mode", 0));
                    prefsRestored++;
                }
                if (extro.has("echo_enabled")) {
                    editor.putBoolean(Preferences.PREF_VOICE_ECHO_ENABLED, getBoolean(extro, "echo_enabled", true));
                    prefsRestored++;
                }
                if (extro.has("echo_level")) {
                    editor.putInt(Preferences.PREF_VOICE_ECHO_LEVEL, clamp(getInt(extro, "echo_level", 35), 0, 100));
                    prefsRestored++;
                }
                if (extro.has("reverb_enabled")) {
                    editor.putBoolean(Preferences.PREF_VOICE_REVERB_ENABLED, getBoolean(extro, "reverb_enabled", true));
                    prefsRestored++;
                }
                if (extro.has("reverb_level")) {
                    editor.putInt(Preferences.PREF_VOICE_REVERB_LEVEL, clamp(getInt(extro, "reverb_level", 50), 0, 100));
                    prefsRestored++;
                }
                if (extro.has("reverb_room_size")) {
                    editor.putInt(Preferences.PREF_VOICE_REVERB_ROOM_SIZE, clamp(getInt(extro, "reverb_room_size", 60), 0, 100));
                    prefsRestored++;
                }
                if (extro.has("autotune_scale")) {
                    editor.putInt(Preferences.PREF_VOICE_AUTOTUNE_SCALE, clamp(getInt(extro, "autotune_scale", 0), 0, 2));
                    prefsRestored++;
                }
                if (extro.has("karaoke_volume")) {
                    editor.putInt(Preferences.PREF_KARAOKE_GAIN, clamp(getInt(extro, "karaoke_volume", 75), 0, 100));
                    prefsRestored++;
                }
                if (extro.has("karaoke_monitor")) {
                    editor.putBoolean(Preferences.PREF_KARAOKE_MONITOR, getBoolean(extro, "karaoke_monitor", true));
                    prefsRestored++;
                }
                if (extro.has("karaoke_ducking")) {
                    editor.putBoolean(Preferences.PREF_KARAOKE_DUCKING, getBoolean(extro, "karaoke_ducking", false));
                    prefsRestored++;
                }
                if (extro.has("karaoke_pitch")) {
                    editor.putInt(Preferences.PREF_KARAOKE_PITCH, clamp(getInt(extro, "karaoke_pitch", 0), -6, 6));
                    prefsRestored++;
                }
                if (extro.has("karaoke_auto_status")) {
                    editor.putBoolean(Preferences.PREF_KARAOKE_AUTO_STATUS, getBoolean(extro, "karaoke_auto_status", true));
                    prefsRestored++;
                }
            }

            editor.apply();
            VoiceChanger.loadFromPreferences(context);

            // 4. Restore embedded TTMediaBot profiles (backwards-compatible)
            if (root.has("bot_profiles") && root.get("bot_profiles").isJsonArray()) {
                try {
                    int botAdded = org.teamtalkextro.bot.BotConfig.importJsonArray(context, root.getAsJsonArray("bot_profiles"));
                    prefsRestored += botAdded;
                } catch (Throwable ignored) {
                }
            }

            return new ImportResult(true, platform, added, skipped, prefsRestored, null);
        } catch (Throwable t) {
            return new ImportResult(false, "", 0, 0, 0, t.getMessage() != null ? t.getMessage() : "Import failed");
        }
    }

    private static List<ServerEntry> loadSavedServers(Context context) {
        SharedPreferences pref = context.getSharedPreferences(SERVERLIST_PREFS, Context.MODE_PRIVATE);
        List<ServerEntry> list = new ArrayList<>();
        int i = 0;
        while (!pref.getString(i + ServerEntry.KEY_SERVERNAME, "").isEmpty()) {
            ServerEntry entry = new ServerEntry();
            entry.servername = pref.getString(i + ServerEntry.KEY_SERVERNAME, "");
            entry.ipaddr = pref.getString(i + ServerEntry.KEY_IPADDR, "");
            entry.tcpport = pref.getInt(i + ServerEntry.KEY_TCPPORT, 10333);
            entry.udpport = pref.getInt(i + ServerEntry.KEY_UDPPORT, 10333);
            entry.encrypted = pref.getBoolean(i + ServerEntry.KEY_ENCRYPTED, false);
            entry.username = pref.getString(i + ServerEntry.KEY_USERNAME, "");
            entry.password = pref.getString(i + ServerEntry.KEY_PASSWORD, "");
            entry.nickname = pref.getString(i + ServerEntry.KEY_NICKNAME, "");
            entry.statusmsg = pref.getString(i + ServerEntry.KEY_STATUSMSG, "");
            entry.rememberLastChannel = pref.getBoolean(i + ServerEntry.KEY_REMEMBER_LAST_CHANNEL, true);
            entry.channel = pref.getString(i + ServerEntry.KEY_CHANNEL, "");
            entry.chanpasswd = pref.getString(i + ServerEntry.KEY_CHANPASSWD, "");
            entry.servertype = ServerEntry.ServerType.LOCAL;
            list.add(entry);
            i++;
        }
        return list;
    }

    private static void saveSavedServers(Context context, List<ServerEntry> list) {
        SharedPreferences pref = context.getSharedPreferences(SERVERLIST_PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = pref.edit();
        int oldIdx = 0;
        String[] keys = {
                ServerEntry.KEY_SERVERNAME, ServerEntry.KEY_IPADDR, ServerEntry.KEY_TCPPORT,
                ServerEntry.KEY_UDPPORT, ServerEntry.KEY_ENCRYPTED, ServerEntry.KEY_USERNAME,
                ServerEntry.KEY_PASSWORD, ServerEntry.KEY_NICKNAME, ServerEntry.KEY_STATUSMSG,
                ServerEntry.KEY_REMEMBER_LAST_CHANNEL, ServerEntry.KEY_CHANNEL, ServerEntry.KEY_CHANPASSWD
        };
        while (!pref.getString(oldIdx + ServerEntry.KEY_SERVERNAME, "").isEmpty()) {
            for (String k : keys) {
                edit.remove(oldIdx + k);
            }
            oldIdx++;
        }
        for (int i = 0; i < list.size(); i++) {
            ServerEntry s = list.get(i);
            edit.putString(i + ServerEntry.KEY_SERVERNAME, safe(s.servername));
            edit.putString(i + ServerEntry.KEY_IPADDR, safe(s.ipaddr));
            edit.putInt(i + ServerEntry.KEY_TCPPORT, s.tcpport);
            edit.putInt(i + ServerEntry.KEY_UDPPORT, s.udpport);
            edit.putBoolean(i + ServerEntry.KEY_ENCRYPTED, s.encrypted);
            edit.putString(i + ServerEntry.KEY_USERNAME, safe(s.username));
            edit.putString(i + ServerEntry.KEY_PASSWORD, safe(s.password));
            edit.putString(i + ServerEntry.KEY_NICKNAME, safe(s.nickname));
            edit.putString(i + ServerEntry.KEY_STATUSMSG, safe(s.statusmsg));
            edit.putBoolean(i + ServerEntry.KEY_REMEMBER_LAST_CHANNEL, s.rememberLastChannel);
            edit.putString(i + ServerEntry.KEY_CHANNEL, safe(s.channel));
            edit.putString(i + ServerEntry.KEY_CHANPASSWD, safe(s.chanpasswd));
        }
        edit.apply();
    }

    private static int readIntSafe(SharedPreferences prefs, String key, int def) {
        try {
            return prefs.getInt(key, def);
        } catch (ClassCastException e) {
            try {
                return Integer.parseInt(prefs.getString(key, String.valueOf(def)));
            } catch (Exception ignored) {
                return def;
            }
        }
    }

    private static String safe(String s) {
        return s != null ? s : "";
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String getString(JsonObject obj, String key, String def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return def;
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return def;
        }
    }

    private static int getInt(JsonObject obj, String key, int def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return def;
        try {
            return obj.get(key).getAsInt();
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean getBoolean(JsonObject obj, String key, boolean def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return def;
        try {
            return obj.get(key).getAsBoolean();
        } catch (Exception e) {
            return def;
        }
    }
}
