package org.teamtalkextro.bot;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import androidx.preference.PreferenceManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persistent configuration profile for the built-in Android TTMediaBot.
 */
public class BotConfig {

    public static final String PREF_BOT_PROFILES_JSON = "extro_bot_profiles_json";
    public static final String PREF_BOT_ACTIVE_ID = "extro_bot_active_id";

    public String id = UUID.randomUUID().toString();
    public String profileName = "TTMediaBot";
    public String host = "";
    public int tcpPort = 10333;
    public int udpPort = 10333;
    public boolean encrypted = false;
    public boolean verifyPeer = false;
    public String username = "";
    public String password = "";
    public String nickname = "TTMediaBot";
    public String channel = "/";
    public String channelPassword = "";
    public String defaultService = "hitmo";
    public int volume = 50;
    public boolean autoStatusTrack = true;
    public boolean allowChannelCommands = true;
    public boolean allowPrivateCommands = true;
    public boolean sendChannelAnnouncements = true;
    public String adminUsers = "";
    public String welcomeMessage = "";

    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<BotConfig>>() {}.getType();

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
        c.allowChannelCommands = this.allowChannelCommands;
        c.allowPrivateCommands = this.allowPrivateCommands;
        c.sendChannelAnnouncements = this.sendChannelAnnouncements;
        c.adminUsers = this.adminUsers;
        c.welcomeMessage = this.welcomeMessage;
        return c;
    }

    public void normalize() {
        if (TextUtils.isEmpty(id)) {
            id = UUID.randomUUID().toString();
        }
        if (TextUtils.isEmpty(profileName)) {
            profileName = !TextUtils.isEmpty(host) ? host : "TTMediaBot";
        }
        host = host != null ? host.trim() : "";
        if (tcpPort <= 0 || tcpPort > 65535) {
            tcpPort = 10333;
        }
        if (udpPort <= 0 || udpPort > 65535) {
            udpPort = tcpPort;
        }
        username = username != null ? username : "";
        password = password != null ? password : "";
        if (TextUtils.isEmpty(nickname)) {
            nickname = "TTMediaBot";
        }
        if (TextUtils.isEmpty(channel)) {
            channel = "/";
        }
        channelPassword = channelPassword != null ? channelPassword : "";
        if (!"hitmo".equals(defaultService) && !"mf".equals(defaultService)
                && !"rt".equals(defaultService) && !"radio".equals(defaultService)) {
            defaultService = "hitmo";
        }
        volume = Math.max(0, Math.min(100, volume));
        adminUsers = adminUsers != null ? adminUsers.trim() : "";
        welcomeMessage = welcomeMessage != null ? welcomeMessage.trim() : "";
    }

    public boolean isAdmin(String senderUsername) {
        if (TextUtils.isEmpty(adminUsers)) {
            return true;
        }
        if (TextUtils.isEmpty(senderUsername)) {
            return false;
        }
        String needle = senderUsername.trim().toLowerCase();
        String[] parts = adminUsers.split("[,;\\s]+");
        for (String p : parts) {
            if (!TextUtils.isEmpty(p) && p.trim().toLowerCase().equals(needle)) {
                return true;
            }
        }
        return false;
    }

    public static BotConfig createDefault() {
        BotConfig cfg = new BotConfig();
        cfg.normalize();
        return cfg;
    }

    public static List<BotConfig> loadAll(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        String json = prefs.getString(PREF_BOT_PROFILES_JSON, "");
        List<BotConfig> list = new ArrayList<>();
        if (!TextUtils.isEmpty(json)) {
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
        if (list.isEmpty()) {
            list.add(createDefault());
        }
        return list;
    }

    public static void saveAll(Context context, List<BotConfig> profiles) {
        List<BotConfig> clean = new ArrayList<>();
        if (profiles != null) {
            for (BotConfig c : profiles) {
                if (c != null) {
                    c.normalize();
                    clean.add(c);
                }
            }
        }
        if (clean.isEmpty()) {
            clean.add(createDefault());
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        prefs.edit().putString(PREF_BOT_PROFILES_JSON, GSON.toJson(clean)).apply();
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
        if (!TextUtils.isEmpty(activeId)) {
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
        if (TextUtils.isEmpty(id)) return;
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
        // Remove empty placeholder default profile if real profiles are imported
        if (existing.size() == 1 && TextUtils.isEmpty(existing.get(0).host)) {
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
                if (TextUtils.isEmpty(cfg.host)) continue;

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
