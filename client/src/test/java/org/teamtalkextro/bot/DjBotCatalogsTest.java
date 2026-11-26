package org.teamtalkextro.bot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class DjBotCatalogsTest {

    @Test
    public void testBuiltInTvCatalogHasRostelecomAndMultiMirrorFallbacks() {
        assertTrue("Expected at least 35 built-in Russian TV channels, found " + DjBotCatalogs.BUILT_IN_TV.size(),
                DjBotCatalogs.BUILT_IN_TV.size() >= 35);

        int ngenixCount = 0;
        for (BotMusicSearch.BotTrack ch : DjBotCatalogs.BUILT_IN_TV) {
            assertFalse("Channel title must not be empty", ch.title.isEmpty());
            assertFalse("Channel primary URL must not be empty", ch.url.isEmpty());
            assertEquals(DjBotCatalogs.SERVICE_TV, ch.service);
            assertTrue(ch.isLiveStream);
            for (String u : ch.getAllCandidateUrls()) {
                if (u.contains("cdn.ngenix.net")) {
                    ngenixCount++;
                    break;
                }
            }
        }
        assertTrue("Expected multiple Rostelecom cdn.ngenix.net channels", ngenixCount >= 15);

        String[] requiredMultiMirror = { "Первый канал", "Россия 1", "НТВ", "СТС", "ТНТ" };
        for (String name : requiredMultiMirror) {
            List<BotMusicSearch.BotTrack> found = DjBotCatalogs.searchTv(name);
            assertFalse("Missing TV channel: " + name, found.isEmpty());
            BotMusicSearch.BotTrack track = found.get(0);
            List<String> urls = track.getAllCandidateUrls();
            assertTrue("Expected Rostelecom primary URL for " + name + ", got " + track.url,
                    track.url.contains("cdn.ngenix.net"));
            assertTrue("Expected multi-mirror fallback URLs for " + name + ", got " + urls.size(),
                    urls.size() >= 2);
            assertFalse("Fallback list should not be empty for " + name, track.fallbackUrls.isEmpty());
        }
    }

    @Test
    public void testSearchTvByIndexAndName() {
        List<BotMusicSearch.BotTrack> first = DjBotCatalogs.searchTv("1");
        assertEquals(1, first.size());
        assertEquals("Первый канал", first.get(0).title);

        List<BotMusicSearch.BotTrack> byName = DjBotCatalogs.searchTv("россия 24");
        assertFalse(byName.isEmpty());
        assertEquals("Россия 24", byName.get(0).title);
    }
}
