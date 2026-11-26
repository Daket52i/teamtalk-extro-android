package org.teamtalkextro.bot;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Persistent configuration profile for the embedded Android DJ Bot
 * (analog of TT-DJbot / welcome, guard, music, radio, Volnorez, TV + EPG).
 *
 * Uses fresh SharedPreferences keys ({@code extro_djbot_*}) so legacy TTMediaBot
 * profiles are not silently carried over after the bot replacement.
 */
public class BotConfig {

    public static final String PREF_BOT_PROFILES_JSON = "extro_djbot_profiles_json";
    public static final String PREF_BOT_ACTIVE_ID = "extro_djbot_active_id";
    public static final String PREF_BOT_FAVORITES_JSON = "extro_djbot_favorites_json";

    public static final String DEFAULT_PROFILE_NAME = "DJ Bot";
    public static final String DEFAULT_NICKNAME = "!DJ Bot";
    public static final String DEFAULT_WELCOME_TEXT = "Добро пожаловать на сервер! Команды бота: /h";

    public String id = UUID.randomUUID().toString();
    public String profileName = DEFAULT_PROFILE_NAME;

    // Connection
    public String host = "";
    public int tcpPort = 10333;
    public int udpPort = 10333;
    public boolean encrypted = false;
    public boolean verifyPeer = false;
    public String username = "";
    public String password = "";
    public String nickname = DEFAULT_NICKNAME;
    public String channel = "/";
    public String channelPassword = "";

    // Playback
    public String defaultService = BotMusicSearch.SERVICE_HITMO;
    public int volume = 50;
    public boolean autoStatusTrack = true;
    public boolean voiceAnnounceTrack = false;

    // Welcome & Greeting
    public boolean welcomeEnabled = true;
    public String welcomeText = DEFAULT_WELCOME_TEXT;

    // Guard (anti-botnet / anti-abuse protection)
    public boolean guardEnabled = false;
    public boolean guardKickEmptyNick = true;
    public boolean guardMatCheck = true;
    public boolean guardBotnetNickCheck = true;
    public boolean guardBanOnViolation = false;
    public int guardMaxNickLen = 40;
    public String trustedUsers = "";

    // Permissions & announcements
    public String adminUsers = "";
    public boolean allowChannelCommands = true;
    public boolean allowPrivateCommands = true;
    public boolean sendChannelAnnouncements = true;

    public static final class FavoriteItem {
        public String label;
        public String url;
        public String service;
        public boolean isLive;

        public FavoriteItem() {
        }

        public FavoriteItem(String label, String url, String service, boolean isLive) {
            this.label = label != null ? label.trim() : "";
            this.url = url != null ? url.trim() : "";
            this.service = service != null ? service.trim() : "";
            this.isLive = isLive;
        }
    }

    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<BotConfig>>() {}.getType();
    private static final Type FAV_LIST_TYPE = new TypeToken<List<FavoriteItem>>() {}.getType();

    public BotConfig() {
    }

    public BotConfig copy() {
        BotConfig c = new BotConfig();
        c.id = this.id;
        c.profileName = this.profileName;
        c.host = this.host;
        c.tcpPort = this.tcpPort;
        c.udpPort = this.udpPort;
        c.encrypted = this.encrypted;
        c.verifyPeer = this.verifyPeer;
        c.username = this.username;
        c.password = this.password;
        c.nickname = this.nickname;
        c.channel = this.channel;
        c.channelPassword = this.channelPassword;
        c.defaultService = this.defaultService;
        c.volume = this.volume;
        c.autoStatusTrack = this.autoStatusTrack;
        c.voiceAnnounceTrack = this.voiceAnnounceTrack;
        c.welcomeEnabled = this.welcomeEnabled;
        c.welcomeText = this.welcomeText;
        c.guardEnabled = this.guardEnabled;
        c.guardKickEmptyNick = this.guardKickEmptyNick;
        c.guardMatCheck = this.guardMatCheck;
        c.guardBotnetNickCheck = this.guardBotnetNickCheck;
        c.guardBanOnViolation = this.guardBanOnViolation;
        c.guardMaxNickLen = this.guardMaxNickLen;
        c.trustedUsers = this.trustedUsers;
        c.adminUsers = this.adminUsers;
        c.allowChannelCommands = this.allowChannelCommands;
        c.allowPrivateCommands = this.allowPrivateCommands;
        c.sendChannelAnnouncements = this.sendChannelAnnouncements;
        return c;
    }

    public void normalize() {
        if (isBlank(id)) {
            id = UUID.randomUUID().toString();
        }
        host = host != null ? host.trim() : "";
        if (isBlank(profileName)) {
            profileName = !host.isEmpty() ? host : DEFAULT_PROFILE_NAME;
        } else {
            profileName = profileName.trim();
        }
        if (tcpPort <= 0 || tcpPort > 65535) {
            tcpPort = 10333;
        }
        if (udpPort <= 0 || udpPort > 65535) {
            udpPort = tcpPort;
        }
        username = username != null ? username.trim() : "";
        password = password != null ? password : "";
        if (isBlank(nickname)) {
            nickname = DEFAULT_NICKNAME;
        } else {
            nickname = nickname.trim();
        }
        if (isBlank(channel)) {
            channel = "/";
        } else {
            channel = channel.trim();
        }
        channelPassword = channelPassword != null ? channelPassword : "";

        if (!BotMusicSearch.SERVICE_HITMO.equals(defaultService)
                && !BotMusicSearch.SERVICE_MF.equals(defaultService)
                && !BotMusicSearch.SERVICE_RUTUBE.equals(defaultService)) {
            defaultService = BotMusicSearch.SERVICE_HITMO;
        }
        volume = Math.max(0, Math.min(100, volume));
        welcomeText = welcomeText != null ? welcomeText.trim() : "";
        if (guardMaxNickLen < 0) {
            guardMaxNickLen = 0;
        } else if (guardMaxNickLen > 200) {
            guardMaxNickLen = 200;
        }
        trustedUsers = trustedUsers != null ? trustedUsers.trim() : "";
        adminUsers = adminUsers != null ? adminUsers.trim() : "";
    }

    public boolean isAdmin(String senderUsername) {
        if (isBlank(adminUsers)) {
            return true;
        }
        return matchesCsvList(adminUsers, senderUsername);
    }

    public boolean isTrusted(String userUsername, String userNickname) {
        if (!isBlank(adminUsers) && matchesCsvList(adminUsers, userUsername)) {
            return true;
        }
        if (!isBlank(trustedUsers)) {
            if (matchesCsvList(trustedUsers, userUsername)) {
                return true;
            }
            if (matchesCsvList(trustedUsers, userNickname)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesCsvList(String csv, String candidate) {
        if (isBlank(csv) || isBlank(candidate)) {
            return false;
        }
        String needle = candidate.trim().toLowerCase(Locale.ROOT);
        String[] parts = csv.split("[,;\\n]+");
        for (String p : parts) {
            if (!isBlank(p) && p.trim().toLowerCase(Locale.ROOT).equals(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static BotConfig createDefault() {
        BotConfig cfg = new BotConfig();
        cfg.normalize();
        return cfg;
    }

    // --- Pure JSON helpers (unit-testable without Android Context) ---

    public static String serializeProfilesJson(List<BotConfig> profiles) {
        List<BotConfig> clean = new ArrayList<>();
        if (profiles != null) {
            for (BotConfig c : profiles) {
                if (c != null) {
                    BotConfig copy = c.copy();
                    copy.normalize();
                    clean.add(copy);
                }
            }
        }
        if (clean.isEmpty()) {
            clean.add(createDefault());
        }
        return GSON.toJson(clean, LIST_TYPE);
    }

    public static List<BotConfig> parseProfilesJson(String json) {
        List<BotConfig> list = new ArrayList<>();
        if (!isBlank(json)) {
            try {
                List<BotConfig> parsed = GSON.fromJson(json, LIST_TYPE);
                if (parsed != null) {
                    for (BotConfig c : parsed) {
                        if (c != null) {
                            c.normalize();
                            list.add(c);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return list;
    }

    public static String serializeFavoritesJson(List<FavoriteItem> items) {
        List<FavoriteItem> clean = new ArrayList<>();
        if (items != null) {
            for (FavoriteItem it : items) {
                if (it != null && !isBlank(it.url)) {
                    String lbl = !isBlank(it.label) ? it.label.trim() : it.url.trim();
                    clean.add(new FavoriteItem(lbl, it.url, it.service, it.isLive));
                }
            }
        }
        return GSON.toJson(clean, FAV_LIST_TYPE);
    }

    public static List<FavoriteItem> parseFavoritesJson(String json) {
        List<FavoriteItem> list = new ArrayList<>();
        if (!isBlank(json)) {
            try {
                List<FavoriteItem> parsed = GSON.fromJson(json, FAV_LIST_TYPE);
                if (parsed != null) {
                    for (FavoriteItem it : parsed) {
                        if (it != null && !isBlank(it.url)) {
                            list.add(new FavoriteItem(
                                    !isBlank(it.label) ? it.label : it.url,
                                    it.url,
                                    it.service,
                                    it.isLive));
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return list;
    }

    // --- SharedPreferences Persistence ---

    /**
     * Returns true if the user has already saved at least one DJ Bot profile
     * (used to show the first-time upgrade notice after replacing TTMediaBot).
     */
    public static boolean hasSavedProfiles(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        String json = prefs.getString(PREF_BOT_PROFILES_JSON, "");
        return !isBlank(json);
    }

    public static List<BotConfig> loadAll(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        String json = prefs.getString(PREF_BOT_PROFILES_JSON, "");
        List<BotConfig> list = parseProfilesJson(json);
        if (list.isEmpty()) {
            list.add(createDefault());
        }
        return list;
    }

    public static void saveAll(Context context, List<BotConfig> profiles) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        prefs.edit().putString(PREF_BOT_PROFILES_JSON, serializeProfilesJson(profiles)).apply();
    }

    public static String getActiveId(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        return prefs.getString(PREF_BOT_ACTIVE_ID, "");
    }

    public static void setActiveId(Context context, String id) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        prefs.edit().putString(PREF_BOT_ACTIVE_ID, id != null ? id : "").apply();
    }

    public static BotConfig getActiveProfile(Context context) {
        List<BotConfig> all = loadAll(context);
        String activeId = getActiveId(context);
        if (!isBlank(activeId)) {
            for (BotConfig c : all) {
                if (activeId.equals(c.id)) {
                    return c;
                }
            }
        }
        BotConfig first = all.get(0);
        setActiveId(context, first.id);
        return first;
    }

    public static void upsert(Context context, BotConfig config) {
        if (config == null) return;
        config.normalize();
        List<BotConfig> all = loadAll(context);
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (config.id.equals(all.get(i).id)) {
                all.set(i, config);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            all.add(config);
        }
        saveAll(context, all);
        setActiveId(context, config.id);
    }

    public static void deleteById(Context context, String id) {
        if (isBlank(id)) return;
        List<BotConfig> all = loadAll(context);
        List<BotConfig> remaining = new ArrayList<>();
        for (BotConfig c : all) {
            if (!id.equals(c.id)) {
                remaining.add(c);
            }
        }
        if (remaining.isEmpty()) {
            remaining.add(createDefault());
        }
        saveAll(context, remaining);
        setActiveId(context, remaining.get(0).id);
    }

    public static List<FavoriteItem> loadFavorites(Context context) {
        if (context == null) return new ArrayList<>();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        return parseFavoritesJson(prefs.getString(PREF_BOT_FAVORITES_JSON, ""));
    }

    public static void saveFavorites(Context context, List<FavoriteItem> items) {
        if (context == null) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        prefs.edit().putString(PREF_BOT_FAVORITES_JSON, serializeFavoritesJson(items)).apply();
    }

    public static JsonArray toJsonArray(Context context) {
        List<BotConfig> all = loadAll(context);
        JsonElement el = GSON.toJsonTree(all, LIST_TYPE);
        return el != null && el.isJsonArray() ? el.getAsJsonArray() : new JsonArray();
    }

    public static int importJsonArray(Context context, JsonArray array) {
        if (array == null || array.size() == 0) {
            return 0;
        }
        List<BotConfig> existing = loadAll(context);
        if (existing.size() == 1 && isBlank(existing.get(0).host)) {
            existing.clear();
        }
        int added = 0;
        for (JsonElement el : array) {
            if (el == null || !el.isJsonObject()) continue;
            try {
                JsonObject obj = el.getAsJsonObject();
                BotConfig cfg = GSON.fromJson(obj, BotConfig.class);
                if (cfg == null) continue;
                cfg.normalize();
                if (isBlank(cfg.host)) continue;

                boolean dup = false;
                for (BotConfig ex : existing) {
                    if (ex.id.equals(cfg.id)
                            || (ex.host.equalsIgnoreCase(cfg.host)
                            && ex.tcpPort == cfg.tcpPort
                            && ex.nickname.equalsIgnoreCase(cfg.nickname)
                            && ex.username.equalsIgnoreCase(cfg.username))) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    existing.add(cfg);
                    added++;
                }
            } catch (Throwable ignored) {
            }
        }
        if (added > 0) {
            saveAll(context, existing);
        }
        return added;
    }
}
