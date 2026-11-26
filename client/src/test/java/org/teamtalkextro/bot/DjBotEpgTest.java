package org.teamtalkextro.bot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

public class DjBotEpgTest {

    @Test
    public void testNormChannelNameAndAliases() {
        assertEquals("первый канал", DjBotEpg.normChannelName("Первый канал HD (1080p)"));
        assertEquals("карусель", DjBotEpg.normChannelName("Карусель +4 [Not 24/7]"));
        assertEquals("бобер", DjBotEpg.normChannelName("Бобёр HD"));
        assertEquals("360", DjBotEpg.normChannelName("360°"));

        List<String> keys = DjBotEpg.candidateKeys("Матч! Ультра");
        assertTrue(keys.contains("матч тв"));
        assertTrue(keys.contains("match tv"));

        List<String> tvcKeys = DjBotEpg.candidateKeys("ТВ Центр");
        assertTrue(tvcKeys.contains("твц"));
        assertTrue(tvcKeys.contains("tvc"));
    }

    @Test
    public void testXmltvTimeParsingAndStreamingEpgLookup() throws Exception {
        // 2026-11-26 12:30:00 MSK (+0300) == 2026-11-26 09:30:00 UTC
        long nowMs = DjBotEpg.parseXmltvTime("20261126123000 +0300");
        assertTrue(nowMs > 0);
        assertEquals(DjBotEpg.parseXmltvTime("20261126093000 +0000"), nowMs);

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<tv>\n"
                + "  <channel id=\"1tv\">\n"
                + "    <display-name>Первый канал HD</display-name>\n"
                + "  </channel>\n"
                + "  <channel id=\"kultura\">\n"
                + "    <display-name>Россия Культура</display-name>\n"
                + "  </channel>\n"
                + "  <channel id=\"bober-ru\">\n"
                + "    <display-name>Бобёр</display-name>\n"
                + "  </channel>\n"
                + "  <programme start=\"20261126120000 +0300\" stop=\"20261126130000 +0300\" channel=\"1tv\">\n"
                + "    <title>Новости</title>\n"
                + "  </programme>\n"
                + "  <programme start=\"20261126130000 +0300\" stop=\"20261126143000 +0300\" channel=\"1tv\">\n"
                + "    <title>Время покажет</title>\n"
                + "  </programme>\n"
                + "  <programme start=\"20261126113000 +0300\" stop=\"20261126131500 +0300\" channel=\"kultura\">\n"
                + "    <title>Правила жизни</title>\n"
                + "  </programme>\n"
                + "  <programme start=\"20261126121500 +0300\" stop=\"20261126124500 +0300\" channel=\"bober-ru\">\n"
                + "    <title>Дачные идеи</title>\n"
                + "  </programme>\n"
                + "  <!-- Outside +/-12h window, should be skipped -->\n"
                + "  <programme start=\"20261128120000 +0300\" stop=\"20261128130000 +0300\" channel=\"1tv\">\n"
                + "    <title>Далеко в будущем</title>\n"
                + "  </programme>\n"
                + "</tv>\n";

        DjBotEpg epg = new DjBotEpg(null, null);
        epg.parseStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), nowMs);
        assertTrue(epg.isLoaded());

        DjBotEpg.EpgInfo ch1 = epg.getNowAt("Первый канал", nowMs);
        assertNotNull(ch1);
        assertEquals("Новости", ch1.title);
        assertEquals("12:00", ch1.start);
        assertEquals("13:00", ch1.stop);
        assertEquals("Время покажет", ch1.nextTitle);
        assertEquals("13:00", ch1.nextStart);
        assertEquals("📺 Первый канал: Новости (12:00–13:00)", ch1.formatStatus("Первый канал"));

        // Alias match: "Россия К" -> "Россия Культура"
        DjBotEpg.EpgInfo kult = epg.getNowAt("Россия К", nowMs);
        assertNotNull(kult);
        assertEquals("Правила жизни", kult.title);
        assertEquals("11:30", kult.start);
        assertEquals("13:15", kult.stop);

        // Yo normalization match: "Бобёр"
        DjBotEpg.EpgInfo bober = epg.getNowAt("Бобёр", nowMs);
        assertNotNull(bober);
        assertEquals("Дачные идеи", bober.title);

        // Out-of-window future programme must not be kept
        long twoDaysLater = DjBotEpg.parseXmltvTime("20261128123000 +0300");
        assertNull(epg.getNowAt("Первый канал", twoDaysLater));
    }

    @Test
    public void testCatalogCountsAndIntegrity() {
        assertEquals(33, DjBotCatalogs.BUILT_IN_TV.size());
        assertEquals("Первый канал", DjBotCatalogs.BUILT_IN_TV.get(0).title);
        assertEquals("Бобёр", DjBotCatalogs.BUILT_IN_TV.get(32).title);
        assertTrue(DjBotCatalogs.BUILT_IN_RADIO.size() >= 30);
        assertTrue(DjBotCatalogs.BUILT_IN_VOLNOREZ.size() >= 50);
        assertTrue(DjBotCatalogs.POP_QUERIES.size() >= 15);
    }
}
