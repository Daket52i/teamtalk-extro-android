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
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.teamtalkextro.R;
import org.teamtalkextro.backend.VoiceChanger;
import org.teamtalkextro.security.ExtroShield;

/**
 * Unified cross-platform JSON Backup & Restore for TeamTalk Extro (Android & Windows).
 *
 * Supports both plain v1 JSON backups and password-encrypted v2 backups
 * (PBKDF2-HMAC-SHA256 100,000 iterations + AES-256-GCM) compatible with
 * TeamTalk Extro for Windows.
 *
 * Exports saved servers, Extro audio/karaoke presets, shield settings,
 * local user notes, bot profiles, and Android SharedPreferences.
 */
public final class SettingsBackupManager {

    public static final String BACKUP_FORMAT = "teamtalk-extro-backup";
    public static final int BACKUP_VERSION = 1;

    public static final String ENCRYPTED_BACKUP_FORMAT = "teamtalk-extro-backup-encrypted";
    public static final int ENCRYPTED_BACKUP_VERSION = 2;
    public static final String ENCRYPTED_KDF = "PBKDF2-HMAC-SHA256";
    public static final int ENCRYPTED_ITERATIONS = 100000;
    public static final String ENCRYPTED_CIPHER = "AES-256-GCM";
    private static final int SALT_LEN_BYTES = 16;
    private static final int IV_LEN_BYTES = 12;
    private static final int GCM_TAG_LEN_BYTES = 16;
    private static final int AES_KEY_BITS = 256;

    private static final String SERVERLIST_PREFS = "serverlist";

    private static final char[] B64_ENC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final int[] B64_DEC = new int[128];

    static {
        Arrays.fill(B64_DEC, -1);
        for (int i = 0; i < B64_ENC.length; i++) {
            B64_DEC[B64_ENC[i]] = i;
        }
    }

    public static final class WrongPasswordException extends Exception {
        public WrongPasswordException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class ExportResult {
        public final boolean success;
        public final boolean encrypted;
        public final int serverCount;
        public final int prefCount;
        public final String errorMessage;

        public ExportResult(boolean success, int serverCount, int prefCount, String errorMessage) {
            this(success, false, serverCount, prefCount, errorMessage);
        }

        public ExportResult(boolean success, boolean encrypted, int serverCount, int prefCount, String errorMessage) {
            this.success = success;
            this.encrypted = encrypted;
            this.serverCount = serverCount;
            this.prefCount = prefCount;
            this.errorMessage = errorMessage;
        }
    }

    public static final class ImportResult {
        public final boolean success;
        public final boolean passwordRequired;
        public final boolean wrongPassword;
        public final String sourcePlatform;
        public final int serversAdded;
        public final int serversSkipped;
        public final int prefsRestored;
        public final String errorMessage;

        public ImportResult(boolean success, String sourcePlatform, int serversAdded,
                            int serversSkipped, int prefsRestored, String errorMessage) {
            this(success, false, false, sourcePlatform, serversAdded, serversSkipped, prefsRestored, errorMessage);
        }

        public ImportResult(boolean success, boolean passwordRequired, boolean wrongPassword,
                            String sourcePlatform, int serversAdded, int serversSkipped,
                            int prefsRestored, String errorMessage) {
            this.success = success;
            this.passwordRequired = passwordRequired;
            this.wrongPassword = wrongPassword;
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

    // --- Pure JVM-compatible RFC 4648 Base64 (matches Android Base64.NO_WRAP & Qt QByteArray::toBase64) ---

    public static String base64Encode(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder sb = new StringBuilder(((data.length + 2) / 3) * 4);
        int i = 0;
        while (i < data.length) {
            int b0 = data[i++] & 0xFF;
            int hasB1 = i < data.length ? 1 : 0;
            int b1 = hasB1 == 1 ? (data[i++] & 0xFF) : 0;
            int hasB2 = i < data.length ? 1 : 0;
            int b2 = hasB2 == 1 ? (data[i++] & 0xFF) : 0;

            int triple = (b0 << 16) | (b1 << 8) | b2;
            sb.append(B64_ENC[(triple >> 18) & 0x3F]);
            sb.append(B64_ENC[(triple >> 12) & 0x3F]);
            sb.append(hasB1 == 1 ? B64_ENC[(triple >> 6) & 0x3F] : '=');
            sb.append(hasB2 == 1 ? B64_ENC[triple & 0x3F] : '=');
        }
        return sb.toString();
    }

    public static byte[] base64Decode(String b64) {
        if (b64 == null) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream((b64.length() * 3) / 4);
        int[] quad = new int[4];
        int qLen = 0;
        int pad = 0;
        for (int i = 0; i < b64.length(); i++) {
            char c = b64.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                continue;
            }
            if (c == '=') {
                quad[qLen++] = 0;
                pad++;
            } else if (c < 128 && B64_DEC[c] >= 0) {
                quad[qLen++] = B64_DEC[c];
            } else {
                throw new IllegalArgumentException("Invalid Base64 character");
            }
            if (qLen == 4) {
                int triple = (quad[0] << 18) | (quad[1] << 12) | (quad[2] << 6) | quad[3];
                out.write((triple >> 16) & 0xFF);
                if (pad < 2) out.write((triple >> 8) & 0xFF);
                if (pad < 1) out.write(triple & 0xFF);
                qLen = 0;
                pad = 0;
            }
        }
        return out.toByteArray();
    }

    // --- Cross-Platform AES-256-GCM + PBKDF2-HMAC-SHA256 Encryption/Decryption ---

    public static boolean isEncryptedBackupJson(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) {
            return false;
        }
        try {
            JsonElement el = JsonParser.parseString(rawJson);
            if (el != null && el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                return ENCRYPTED_BACKUP_FORMAT.equals(getString(obj, "format", ""));
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean isEncryptedBackupUri(Context context, Uri uri) {
        if (context == null || uri == null) return false;
        try {
            String raw = readUriText(context, uri);
            return isEncryptedBackupJson(raw);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Encrypts a plaintext JSON backup string into the cross-platform v2 encrypted
     * envelope (PBKDF2-HMAC-SHA256 100k + AES-256-GCM with split tag_b64 & ciphertext_b64).
     */
    public static String encryptBackupJson(String plainJson, String password) throws Exception {
        if (plainJson == null) {
            throw new IllegalArgumentException("Plaintext backup is null");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Password must not be empty");
        }

        SecureRandom rng = new SecureRandom();
        byte[] salt = new byte[SALT_LEN_BYTES];
        byte[] iv = new byte[IV_LEN_BYTES];
        rng.nextBytes(salt);
        rng.nextBytes(iv);

        byte[] keyBytes = deriveAesKey(password, salt, ENCRYPTED_ITERATIONS);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LEN_BYTES * 8, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

            byte[] encryptedWithTag = cipher.doFinal(plainJson.getBytes(StandardCharsets.UTF_8));
            if (encryptedWithTag.length < GCM_TAG_LEN_BYTES) {
                throw new IllegalStateException("Unexpected AES-GCM output length");
            }
            int ctLen = encryptedWithTag.length - GCM_TAG_LEN_BYTES;
            byte[] ciphertext = Arrays.copyOfRange(encryptedWithTag, 0, ctLen);
            byte[] tag = Arrays.copyOfRange(encryptedWithTag, ctLen, encryptedWithTag.length);

            JsonObject envelope = new JsonObject();
            envelope.addProperty("format", ENCRYPTED_BACKUP_FORMAT);
            envelope.addProperty("version", ENCRYPTED_BACKUP_VERSION);
            envelope.addProperty("kdf", ENCRYPTED_KDF);
            envelope.addProperty("iterations", ENCRYPTED_ITERATIONS);
            envelope.addProperty("cipher", ENCRYPTED_CIPHER);
            envelope.addProperty("salt_b64", base64Encode(salt));
            envelope.addProperty("iv_b64", base64Encode(iv));
            envelope.addProperty("tag_b64", base64Encode(tag));
            envelope.addProperty("ciphertext_b64", base64Encode(ciphertext));

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            return gson.toJson(envelope);
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    /**
     * Decrypts a cross-platform v2 encrypted backup JSON envelope back to plaintext v1 JSON.
     * Throws WrongPasswordException when the GCM authentication tag fails.
     */
    public static String decryptBackupJson(String encryptedJson, String password) throws Exception {
        if (encryptedJson == null || encryptedJson.trim().isEmpty()) {
            throw new IllegalArgumentException("Empty encrypted backup");
        }
        if (password == null || password.isEmpty()) {
            throw new WrongPasswordException("Password is required", null);
        }

        JsonElement parsed = JsonParser.parseString(encryptedJson);
        if (parsed == null || !parsed.isJsonObject()) {
            throw new IllegalArgumentException("Invalid encrypted JSON format");
        }
        JsonObject env = parsed.getAsJsonObject();
        if (!ENCRYPTED_BACKUP_FORMAT.equals(getString(env, "format", ""))) {
            throw new IllegalArgumentException("Not an encrypted TeamTalk Extro backup");
        }

        int iterations = getInt(env, "iterations", ENCRYPTED_ITERATIONS);
        if (iterations < 1000 || iterations > 5000000) {
            throw new IllegalArgumentException("Invalid KDF iterations: " + iterations);
        }

        byte[] salt = base64Decode(getString(env, "salt_b64", ""));
        byte[] iv = base64Decode(getString(env, "iv_b64", ""));
        byte[] tag = base64Decode(getString(env, "tag_b64", ""));
        byte[] ciphertext = base64Decode(getString(env, "ciphertext_b64", ""));

        if (salt.length < 8 || iv.length != IV_LEN_BYTES || tag.length != GCM_TAG_LEN_BYTES) {
            throw new IllegalArgumentException("Malformed encrypted backup parameters");
        }

        byte[] combined = new byte[ciphertext.length + tag.length];
        System.arraycopy(ciphertext, 0, combined, 0, ciphertext.length);
        System.arraycopy(tag, 0, combined, ciphertext.length, tag.length);

        byte[] keyBytes = deriveAesKey(password, salt, iterations);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LEN_BYTES * 8, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

            byte[] plainBytes = cipher.doFinal(combined);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new WrongPasswordException("Wrong password or corrupted backup", e);
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    private static byte[] deriveAesKey(String password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, AES_KEY_BITS);
        try {
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return skf.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    // --- Export / Import ---

    public static ExportResult exportToUri(Context context, Uri uri) {
        return exportToUri(context, uri, null);
    }

    public static ExportResult exportToUri(Context context, Uri uri, String password) {
        if (context == null || uri == null) {
            return new ExportResult(false, false, 0, 0, "Invalid destination");
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

            // 3. Extro Shield settings, Local User Notes, AFK & Quick Phrases
            root.add("shield_settings", ExtroShield.exportSettingsJson(context));
            root.add("user_notes", UserNotesStore.toJsonObject(context));
            root.addProperty("afk_message", AfkAndQuickPhrases.getAfkMessage(context));
            root.add("quick_phrases", AfkAndQuickPhrases.exportQuickPhrasesJson(context));

            // 4. Full Android SharedPreferences + type metadata
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

            // 5. Embedded TTMediaBot profiles
            try {
                root.add("bot_profiles", org.teamtalkextro.bot.BotConfig.toJsonArray(context));
            } catch (Throwable ignored) {
            }

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String plainJson = gson.toJson(root);

            boolean encrypted = password != null && !password.isEmpty();
            String outputJson = encrypted ? encryptBackupJson(plainJson, password) : plainJson;

            try (OutputStream os = context.getContentResolver().openOutputStream(uri, "wt")) {
                if (os == null) {
                    return new ExportResult(false, encrypted, 0, 0, "Cannot open output stream");
                }
                os.write(outputJson.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            return new ExportResult(true, encrypted, savedServers.size(), prefCount, null);
        } catch (Throwable t) {
            return new ExportResult(false, false, 0, 0, t.getMessage() != null ? t.getMessage() : "Export failed");
        }
    }

    public static ImportResult importFromUri(Context context, Uri uri) {
        return importFromUri(context, uri, null);
    }

    public static ImportResult importFromUri(Context context, Uri uri, String password) {
        if (context == null || uri == null) {
            return new ImportResult(false, "", 0, 0, 0, "Invalid source");
        }
        try {
            String rawContent = readUriText(context, uri);
            if (rawContent == null) {
                return new ImportResult(false, "", 0, 0, 0, "Cannot open file");
            }

            String jsonToImport = rawContent;
            if (isEncryptedBackupJson(rawContent)) {
                if (password == null || password.isEmpty()) {
                    return new ImportResult(false, true, false, "", 0, 0, 0,
                            safeString(context, R.string.backup_password_required, "Password required"));
                }
                try {
                    jsonToImport = decryptBackupJson(rawContent, password);
                } catch (WrongPasswordException wpe) {
                    return new ImportResult(false, false, true, "", 0, 0, 0,
                            safeString(context, R.string.backup_wrong_password, "Wrong password or corrupted encrypted backup"));
                }
            }

            JsonElement parsed = JsonParser.parseString(jsonToImport);
            if (parsed == null || !parsed.isJsonObject()) {
                return new ImportResult(false, "", 0, 0, 0, "Invalid JSON format");
            }
            JsonObject root = parsed.getAsJsonObject();

            String format = getString(root, "format", "");
            boolean hasKnownSections = root.has("servers") || root.has("extro")
                    || root.has("android_preferences") || root.has("user_notes") || root.has("shield_settings");
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

            // 4. Restore Extro Shield settings, User Notes, AFK & Quick Phrases (backwards-compatible!)
            if (root.has("shield_settings") && root.get("shield_settings").isJsonObject()) {
                prefsRestored += ExtroShield.importSettingsJson(context, root.getAsJsonObject("shield_settings"));
            }
            if (root.has("user_notes") && root.get("user_notes").isJsonObject()) {
                prefsRestored += UserNotesStore.importFromJsonObject(context, root.getAsJsonObject("user_notes"));
            }
            if (root.has("afk_message") && !root.get("afk_message").isJsonNull()) {
                String afkMsg = getString(root, "afk_message", "");
                if (!afkMsg.isEmpty()) {
                    AfkAndQuickPhrases.setAfkMessage(context, afkMsg);
                    prefsRestored++;
                }
            }
            if (root.has("quick_phrases") && root.get("quick_phrases").isJsonArray()) {
                prefsRestored += AfkAndQuickPhrases.importQuickPhrasesJson(context, root.getAsJsonArray("quick_phrases"));
            }

            // 5. Restore embedded TTMediaBot profiles (backwards-compatible)
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

    private static String readUriText(Context context, Uri uri) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static String safeString(Context context, int resId, String fallback) {
        try {
            return context != null ? context.getString(resId) : fallback;
        } catch (Throwable ignored) {
            return fallback;
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
