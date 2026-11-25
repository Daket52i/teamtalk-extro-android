package org.teamtalkextro.data;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Smart AFK Auto-Responder & Quick Chat Phrases manager for TeamTalk Extro Android v5.34.0.
 *
 * - AfkEngine handles auto-reply throttling (once per sender every 5 minutes),
 *   loop prevention (ignores self and messages starting with "[AFK]"),
 *   and missed PM logging for summary when returning from AFK.
 * - Quick Chat Phrases provides editable 1-tap channel chat phrases persisted
 *   in SharedPreferences and included in SettingsBackupManager JSON backups.
 */
public final class AfkAndQuickPhrases {

    public static final String AFK_PREFIX = "[AFK] ";
    public static final long DEFAULT_THROTTLE_MS = 5L * 60L * 1000L; // 5 minutes
    public static final int MAX_MISSED_PMS = 100;
    public static final int MAX_QUICK_PHRASES = 40;

    public static final String DEFAULT_AFK_MESSAGE = "Отошёл, скоро вернусь! / AFK";

    private static final List<String> DEFAULT_PHRASES_RU = Arrays.asList(
            "Всем привет! 👋",
            "Минуточку, скоро вернусь ⏳",
            "Браво! Отличное исполнение! 👏",
            "Поставьте, пожалуйста, трек 🎵",
            "Спасибо большое! 🙏",
            "Всем спокойной ночи! 🌙"
    );

    private static final List<String> DEFAULT_PHRASES_EN = Arrays.asList(
            "Hello everyone! 👋",
            "One moment, be right back ⏳",
            "Bravo! Awesome performance! 👏",
            "Please queue a track 🎵",
            "Thank you so much! 🙏",
            "Good night everyone! 🌙"
    );

    private static final Gson GSON = new Gson();

    private AfkAndQuickPhrases() {
    }

    public static final class MissedPm {
        public final int fromUserId;
        public final String senderName;
        public final String message;
        public final long timestampMs;

        public MissedPm(int fromUserId, String senderName, String message, long timestampMs) {
            this.fromUserId = fromUserId;
            this.senderName = senderName != null ? senderName : "";
            this.message = message != null ? message : "";
            this.timestampMs = timestampMs;
        }

        public String formatLine() {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
            String time = sdf.format(new Date(this.timestampMs));
            String who = !this.senderName.isEmpty() ? this.senderName : ("#" + this.fromUserId);
            String preview = this.message.trim();
            if (preview.length() > 120) {
                preview = preview.substring(0, 120) + "…";
            }
            return "[" + time + "] " + who + ": " + preview;
        }
    }

    /**
     * Pure JVM-testable AFK auto-responder state machine.
     */
    public static final class AfkEngine {
        private final long throttleMs;
        private boolean enabled;
        private String message = DEFAULT_AFK_MESSAGE;
        private final Map<Integer, Long> lastReplyBySender = new HashMap<>();
        private final List<MissedPm> missedPms = new ArrayList<>();

        public AfkEngine() {
            this(DEFAULT_THROTTLE_MS);
        }

        public AfkEngine(long throttleMs) {
            this.throttleMs = Math.max(1000L, throttleMs);
        }

        public synchronized boolean isEnabled() {
            return this.enabled;
        }

        public synchronized void setEnabled(boolean enabled) {
            this.enabled = enabled;
            if (enabled) {
                this.lastReplyBySender.clear();
            }
        }

        public synchronized String getMessage() {
            return this.message;
        }

        public synchronized void setMessage(String message) {
            String trimmed = message != null ? message.trim() : "";
            this.message = trimmed.isEmpty() ? DEFAULT_AFK_MESSAGE : trimmed;
        }

        public synchronized int getMissedCount() {
            return this.missedPms.size();
        }

        public synchronized List<MissedPm> getMissedPmsSnapshot() {
            return new ArrayList<>(this.missedPms);
        }

        public synchronized List<MissedPm> drainMissedPms() {
            List<MissedPm> copy = new ArrayList<>(this.missedPms);
            this.missedPms.clear();
            this.lastReplyBySender.clear();
            return copy;
        }

        /**
         * Processes an incoming private message (MSGTYPE_USER).
         *
         * @return Auto-reply text starting with "[AFK] " if an auto-reply should be sent,
         *         or {@code null} if AFK is off, message is from self, message is itself an
         *         "[AFK]" auto-reply (loop prevention), or sender was already replied to
         *         within {@code throttleMs}.
         */
        public synchronized String onIncomingPrivateMessage(
                int fromUserId,
                int myUserId,
                String senderName,
                String incomingText,
                long nowMs) {
            if (!this.enabled) {
                return null;
            }
            if (fromUserId <= 0 || fromUserId == myUserId) {
                return null;
            }
            String trimmedIncoming = incomingText != null ? incomingText.trim() : "";
            // Prevent infinite auto-reply loops with other AFK users or bots
            if (trimmedIncoming.startsWith("[AFK]") || trimmedIncoming.startsWith("[afk]")) {
                return null;
            }

            if (this.missedPms.size() >= MAX_MISSED_PMS) {
                this.missedPms.remove(0);
            }
            this.missedPms.add(new MissedPm(fromUserId, senderName, trimmedIncoming, nowMs));

            Long lastSent = this.lastReplyBySender.get(fromUserId);
            if (lastSent != null && (nowMs - lastSent) < this.throttleMs) {
                return null;
            }
            this.lastReplyBySender.put(fromUserId, nowMs);

            String base = this.message != null && !this.message.trim().isEmpty()
                    ? this.message.trim()
                    : DEFAULT_AFK_MESSAGE;
            if (base.startsWith("[AFK]")) {
                return base;
            }
            return AFK_PREFIX + base;
        }
    }

    // --- Pure list/JSON helpers for Quick Chat Phrases ---

    public static List<String> getDefaultPhrases(Locale locale) {
        boolean ru = locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
        return new ArrayList<>(ru ? DEFAULT_PHRASES_RU : DEFAULT_PHRASES_EN);
    }

    public static List<String> parsePhrasesJson(String rawJson) {
        List<String> result = new ArrayList<>();
        if (rawJson == null || rawJson.trim().isEmpty()) {
            return result;
        }
        try {
            JsonElement el = JsonParser.parseString(rawJson);
            if (el != null && el.isJsonArray()) {
                for (JsonElement item : el.getAsJsonArray()) {
                    if (item != null && !item.isJsonNull()) {
                        String s = item.getAsString().trim();
                        if (!s.isEmpty() && !result.contains(s)) {
                            result.add(s);
                            if (result.size() >= MAX_QUICK_PHRASES) break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    public static String serializePhrasesJson(List<String> phrases) {
        JsonArray arr = new JsonArray();
        if (phrases != null) {
            for (String p : phrases) {
                if (p != null) {
                    String s = p.trim();
                    if (!s.isEmpty()) {
                        arr.add(s);
                    }
                }
            }
        }
        return GSON.toJson(arr);
    }

    public static List<String> addPhraseToList(List<String> existing, String phrase) {
        List<String> next = existing != null ? new ArrayList<>(existing) : new ArrayList<>();
        if (phrase == null) return next;
        String trimmed = phrase.trim();
        if (trimmed.isEmpty()) return next;
        if (!next.contains(trimmed) && next.size() < MAX_QUICK_PHRASES) {
            next.add(trimmed);
        }
        return next;
    }

    public static List<String> removePhraseFromList(List<String> existing, int index) {
        List<String> next = existing != null ? new ArrayList<>(existing) : new ArrayList<>();
        if (index >= 0 && index < next.size()) {
            next.remove(index);
        }
        return next;
    }

    // --- SharedPreferences persistence ---

    public static boolean isAfkEnabledPref(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        return prefs.getBoolean(Preferences.PREF_AFK_ENABLED, false);
    }

    public static void setAfkEnabledPref(Context context, boolean enabled) {
        if (context == null) return;
        PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext())
                .edit()
                .putBoolean(Preferences.PREF_AFK_ENABLED, enabled)
                .apply();
    }

    public static String getAfkMessage(Context context) {
        if (context == null) return DEFAULT_AFK_MESSAGE;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        String saved = prefs.getString(Preferences.PREF_AFK_MESSAGE, "");
        if (saved == null || saved.trim().isEmpty()) {
            return DEFAULT_AFK_MESSAGE;
        }
        return saved.trim();
    }

    public static void setAfkMessage(Context context, String message) {
        if (context == null) return;
        String trimmed = message != null ? message.trim() : "";
        if (trimmed.isEmpty()) {
            trimmed = DEFAULT_AFK_MESSAGE;
        }
        PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext())
                .edit()
                .putString(Preferences.PREF_AFK_MESSAGE, trimmed)
                .apply();
    }

    public static List<String> getQuickPhrases(Context context) {
        if (context == null) {
            return getDefaultPhrases(Locale.getDefault());
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        if (!prefs.contains(Preferences.PREF_QUICK_PHRASES_JSON)) {
            return getDefaultPhrases(Locale.getDefault());
        }
        String raw = prefs.getString(Preferences.PREF_QUICK_PHRASES_JSON, "[]");
        return parsePhrasesJson(raw);
    }

    public static void saveQuickPhrases(Context context, List<String> phrases) {
        if (context == null) return;
        PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext())
                .edit()
                .putString(Preferences.PREF_QUICK_PHRASES_JSON, serializePhrasesJson(phrases))
                .apply();
    }

    public static List<String> addQuickPhrase(Context context, String phrase) {
        List<String> updated = addPhraseToList(getQuickPhrases(context), phrase);
        saveQuickPhrases(context, updated);
        return updated;
    }

    public static List<String> removeQuickPhrase(Context context, int index) {
        List<String> updated = removePhraseFromList(getQuickPhrases(context), index);
        saveQuickPhrases(context, updated);
        return updated;
    }

    // --- SettingsBackupManager JSON integration ---

    public static JsonArray exportQuickPhrasesJson(Context context) {
        JsonArray arr = new JsonArray();
        for (String p : getQuickPhrases(context)) {
            if (p != null && !p.trim().isEmpty()) {
                arr.add(p.trim());
            }
        }
        return arr;
    }

    public static int importQuickPhrasesJson(Context context, JsonArray arr) {
        if (context == null || arr == null) return 0;
        List<String> existing = getQuickPhrases(context);
        int added = 0;
        for (JsonElement el : arr) {
            if (el != null && !el.isJsonNull()) {
                try {
                    String s = el.getAsString().trim();
                    if (!s.isEmpty() && !existing.contains(s) && existing.size() < MAX_QUICK_PHRASES) {
                        existing.add(s);
                        added++;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (added > 0) {
            saveQuickPhrases(context, existing);
        }
        return added;
    }
}
