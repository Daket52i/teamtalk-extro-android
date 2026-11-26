package org.teamtalkextro.bot;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DjBotGuardTest {

    @Test
    public void testGuardDisabledAllowsEveryone() {
        BotConfig cfg = BotConfig.createDefault();
        cfg.guardEnabled = false;
        assertNull(EmbeddedMediaBot.evaluateGuard(cfg, "", "guest"));
        assertNull(EmbeddedMediaBot.evaluateGuard(cfg, "🤖 Shadow_Pilot_46", "guest"));
        assertNull(EmbeddedMediaBot.evaluateGuard(cfg, "плохой_мудак", "guest"));
    }

    @Test
    public void testEmptyNickAndLengthChecks() {
        BotConfig cfg = BotConfig.createDefault();
        cfg.guardEnabled = true;
        cfg.guardKickEmptyNick = true;
        cfg.guardMaxNickLen = 15;

        assertNotNull(EmbeddedMediaBot.evaluateGuard(cfg, "", "guest"));
        assertNotNull(EmbeddedMediaBot.evaluateGuard(cfg, "   ", "guest"));
        assertNotNull(EmbeddedMediaBot.evaluateGuard(cfg, "ОченьДлинныйНикнеймБольше15", "guest"));
        assertNull(EmbeddedMediaBot.evaluateGuard(cfg, "Нормальный", "guest"));
    }

    @Test
    public void testProfanityFilterInNicknames() {
        BotConfig cfg = BotConfig.createDefault();
        cfg.guardEnabled = true;
        cfg.guardMatCheck = true;

        assertTrue(EmbeddedMediaBot.containsProfanity("СуперБляха"));
        assertTrue(EmbeddedMediaBot.containsProfanity("Какой-то Пиздец"));
        assertTrue(EmbeddedMediaBot.containsProfanity("Заёбчик"));
        assertFalse(EmbeddedMediaBot.containsProfanity("Солнышко"));
        assertFalse(EmbeddedMediaBot.containsProfanity("Кирилл Москва"));

        assertNotNull(EmbeddedMediaBot.evaluateGuard(cfg, "Гость_мудак", "guest"));
        assertNull(EmbeddedMediaBot.evaluateGuard(cfg, "Лена", "lena"));
    }

    @Test
    public void testBotnetNickHeuristicAndTrustedExemption() {
        BotConfig cfg = BotConfig.createDefault();
        cfg.guardEnabled = true;
        cfg.guardBotnetNickCheck = true;
        cfg.trustedUsers = "trusted_bot, Friendly_User_Name_1";
        cfg.adminUsers = "owner";

        // Emoji + Latin word_word_num -> botnet
        assertTrue(EmbeddedMediaBot.isBotnetNick("🤖 Shadow_Pilot_46"));
        assertNotNull(EmbeddedMediaBot.evaluateGuard(cfg, "🤖 Shadow_Pilot_46", "1"));

        // 3 underscores + Latin word_word -> botnet
        assertTrue(EmbeddedMediaBot.isBotnetNick("Alpha_Beta_Gamma_99"));
        assertNotNull(EmbeddedMediaBot.evaluateGuard(cfg, "Alpha_Beta_Gamma_99", "1"));

        // Normal nicks with 1 underscore or Cyrillic -> allowed
        assertFalse(EmbeddedMediaBot.isBotnetNick("kirill_mobile"));
        assertFalse(EmbeddedMediaBot.isBotnetNick("Иван_Петров_Москва"));
        assertNull(EmbeddedMediaBot.evaluateGuard(cfg, "kirill_mobile", "kirill"));

        // Trusted & admin users are always exempt
        assertNull(EmbeddedMediaBot.evaluateGuard(cfg, "", "owner"));
        assertNull(EmbeddedMediaBot.evaluateGuard(cfg, "🤖 Shadow_Pilot_46", "trusted_bot"));
        assertNull(EmbeddedMediaBot.evaluateGuard(cfg, "Friendly_User_Name_1", "guest"));
    }

    @Test
    public void testKnownDjBotCommands() {
        String[] expected = {
                "play", "p", "stop", "s", "skip", "n", "pause", "sp",
                "v", "sf", "sb", "playlist", "q", "radio", "r",
                "voln", "now", "tv", "pop", "sv", "fav", "f",
                "vo", "welcome", "settings", "set", "j", "h", "help"
        };
        for (String cmd : expected) {
            assertTrue("Expected DJ Bot command to be recognized: " + cmd,
                    EmbeddedMediaBot.isKnownCommand(cmd));
        }
        assertFalse(EmbeddedMediaBot.isKnownCommand("привет"));
        assertFalse(EmbeddedMediaBot.isKnownCommand("randomword"));
    }
}
