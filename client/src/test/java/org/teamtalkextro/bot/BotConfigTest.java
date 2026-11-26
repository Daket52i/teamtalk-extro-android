package org.teamtalkextro.bot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class BotConfigTest {

    @Test
    public void testFreshDjBotPreferenceKeysAndDefaults() {
        assertEquals("extro_djbot_profiles_json", BotConfig.PREF_BOT_PROFILES_JSON);
        assertEquals("extro_djbot_active_id", BotConfig.PREF_BOT_ACTIVE_ID);
        assertEquals("extro_djbot_favorites_json", BotConfig.PREF_BOT_FAVORITES_JSON);

        BotConfig cfg = BotConfig.createDefault();
        assertEquals("!DJ Bot", cfg.nickname);
        assertEquals("/", cfg.channel);
        assertEquals(10333, cfg.tcpPort);
        assertEquals(10333, cfg.udpPort);
        assertEquals(50, cfg.volume);
        assertEquals("hitmo", cfg.defaultService);
        assertTrue(cfg.welcomeEnabled);
        assertFalse(cfg.welcomeText.isEmpty());
        assertFalse(cfg.guardEnabled);
        assertTrue(cfg.guardKickEmptyNick);
        assertTrue(cfg.guardMatCheck);
        assertTrue(cfg.guardBotnetNickCheck);
        assertFalse(cfg.guardBanOnViolation);
        assertEquals(40, cfg.guardMaxNickLen);
        assertFalse(cfg.voiceAnnounceTrack);
    }

    @Test
    public void testProfileJsonRoundTripAndPermissions() {
        BotConfig cfg = BotConfig.createDefault();
        cfg.profileName = "Мой сервер";
        cfg.host = "tt.example.ru";
        cfg.tcpPort = 10444;
        cfg.udpPort = 10444;
        cfg.encrypted = true;
        cfg.username = "dj_bot";
        cfg.password = "secret123";
        cfg.nickname = "!DJ Радио";
        cfg.channel = "/Музыка";
        cfg.defaultService = "mf";
        cfg.volume = 75;
        cfg.voiceAnnounceTrack = true;
        cfg.welcomeEnabled = true;
        cfg.welcomeText = "Привет, {user}!";
        cfg.guardEnabled = true;
        cfg.guardKickEmptyNick = true;
        cfg.guardMatCheck = true;
        cfg.guardBotnetNickCheck = true;
        cfg.guardBanOnViolation = true;
        cfg.guardMaxNickLen = 35;
        cfg.trustedUsers = "bot2, Moderator_Nick";
        cfg.adminUsers = "owner, lena";

        String json = BotConfig.serializeProfilesJson(Collections.singletonList(cfg));
        List<BotConfig> restored = BotConfig.parseProfilesJson(json);
        assertEquals(1, restored.size());

        BotConfig r = restored.get(0);
        assertEquals("tt.example.ru", r.host);
        assertEquals(10444, r.tcpPort);
        assertTrue(r.encrypted);
        assertEquals("mf", r.defaultService);
        assertEquals(75, r.volume);
        assertTrue(r.voiceAnnounceTrack);
        assertEquals("Привет, {user}!", r.welcomeText);
        assertTrue(r.guardEnabled);
        assertTrue(r.guardBanOnViolation);
        assertEquals(35, r.guardMaxNickLen);

        assertTrue(r.isAdmin("Owner"));
        assertTrue(r.isAdmin("lena"));
        assertFalse(r.isAdmin("guest"));

        assertTrue(r.isTrusted("owner", "any"));
        assertTrue(r.isTrusted("bot2", "any"));
        assertTrue(r.isTrusted("guest", "moderator_nick"));
        assertFalse(r.isTrusted("guest", "stranger"));
    }

    @Test
    public void testFavoritesJsonRoundTrip() {
        List<BotConfig.FavoriteItem> favs = Arrays.asList(
                new BotConfig.FavoriteItem("Европа Плюс", "https://ep128.hostingradio.ru:8030/ep128", "radio", true),
                new BotConfig.FavoriteItem("Первый канал", "http://31.148.5.241/kanal_1/index.m3u8", "tv", true)
        );
        String json = BotConfig.serializeFavoritesJson(favs);
        List<BotConfig.FavoriteItem> parsed = BotConfig.parseFavoritesJson(json);
        assertEquals(2, parsed.size());
        assertEquals("Европа Плюс", parsed.get(0).label);
        assertEquals("radio", parsed.get(0).service);
        assertTrue(parsed.get(0).isLive);
        assertEquals("Первый канал", parsed.get(1).label);
        assertEquals("tv", parsed.get(1).service);

        // Corrupt JSON safely yields empty list
        assertTrue(BotConfig.parseFavoritesJson("{broken").isEmpty());
    }
}
