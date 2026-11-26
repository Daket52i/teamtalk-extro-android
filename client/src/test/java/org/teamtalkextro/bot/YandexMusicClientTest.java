package org.teamtalkextro.bot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class YandexMusicClientTest {

    @Test
    public void testMd5SignFormulaAndSignedUrlBuilder() {
        // Known deterministic MD5 of "XGRlBW9FXlekgbPrRHuSiAget-mp3/test.mp3secret123"
        String sign = YandexMusicClient.computeDownloadSign("/get-mp3/test.mp3", "secret123");
        assertNotNull(sign);
        assertEquals(32, sign.length());
        // Verify leading slash stripping makes path with/without '/' produce identical MD5
        assertEquals(sign, YandexMusicClient.computeDownloadSign("get-mp3/test.mp3", "secret123"));

        String url = YandexMusicClient.buildSignedMp3Url(
                "s123vla.storage.yandex.net", "/rmusic/U2FsdGVk/track.mp3", "67890abc", "saltval");
        String expectedSign = YandexMusicClient.computeDownloadSign("/rmusic/U2FsdGVk/track.mp3", "saltval");
        assertEquals(
                "https://s123vla.storage.yandex.net/get-mp3/" + expectedSign + "/67890abc/rmusic/U2FsdGVk/track.mp3",
                url);
    }

    @Test
    public void testParseDownloadTargetJsonAndXml() {
        String json = "{\"host\":\"s55.storage.yandex.net\",\"path\":\"/rmusic/abc/123.mp3\",\"ts\":\"00112233\",\"s\":\"xyz789\"}";
        YandexMusicClient.DownloadTarget jt = YandexMusicClient.parseDownloadTargetJson(json);
        assertNotNull(jt);
        assertTrue(jt.isValid());
        assertEquals("s55.storage.yandex.net", jt.host);
        assertEquals("/rmusic/abc/123.mp3", jt.path);
        assertEquals("00112233", jt.ts);
        assertEquals("xyz789", jt.s);

        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?><download-info>"
                + "<host>s77.storage.yandex.net</host>"
                + "<path>/rmusic/xml/456.mp3</path>"
                + "<ts>44556677</ts>"
                + "<s>xmlsig</s>"
                + "</download-info>";
        YandexMusicClient.DownloadTarget xt = YandexMusicClient.parseDownloadTargetXml(xml);
        assertNotNull(xt);
        assertTrue(xt.isValid());
        assertEquals("s77.storage.yandex.net", xt.host);
        assertEquals("/rmusic/xml/456.mp3", xt.path);
        assertEquals("44556677", xt.ts);
        assertEquals("xmlsig", xt.s);

        assertNull(YandexMusicClient.parseDownloadTargetJson("{broken"));
        assertNull(YandexMusicClient.parseDownloadTargetXml("<download-info/>"));
    }

    @Test
    public void testYandexUrlPatternExtraction() {
        YandexMusicClient.ParsedYandexUrl t1 = YandexMusicClient.parseYandexUrl(
                "https://music.yandex.ru/album/12345/track/67890?utm_source=web");
        assertNotNull(t1);
        assertEquals(YandexMusicClient.ParsedYandexUrl.Kind.TRACK, t1.kind);
        assertEquals("67890", t1.primaryId);

        YandexMusicClient.ParsedYandexUrl t2 = YandexMusicClient.parseYandexUrl(
                "https://music.yandex.com/track/998877");
        assertNotNull(t2);
        assertEquals(YandexMusicClient.ParsedYandexUrl.Kind.TRACK, t2.kind);
        assertEquals("998877", t2.primaryId);

        YandexMusicClient.ParsedYandexUrl t3 = YandexMusicClient.parseYandexUrl("ymtrack:554433");
        assertNotNull(t3);
        assertEquals(YandexMusicClient.ParsedYandexUrl.Kind.TRACK, t3.kind);
        assertEquals("554433", t3.primaryId);

        YandexMusicClient.ParsedYandexUrl alb = YandexMusicClient.parseYandexUrl(
                "https://music.yandex.ru/album/777666");
        assertNotNull(alb);
        assertEquals(YandexMusicClient.ParsedYandexUrl.Kind.ALBUM, alb.kind);
        assertEquals("777666", alb.primaryId);

        YandexMusicClient.ParsedYandexUrl pl = YandexMusicClient.parseYandexUrl(
                "https://music.yandex.ru/users/daket52i/playlists/1003");
        assertNotNull(pl);
        assertEquals(YandexMusicClient.ParsedYandexUrl.Kind.USER_PLAYLIST, pl.kind);
        assertEquals("daket52i", pl.primaryId);
        assertEquals("1003", pl.secondaryId);

        YandexMusicClient.ParsedYandexUrl shortPl = YandexMusicClient.parseYandexUrl(
                "https://music.yandex.ru/playlists/lk.abcdef-1234");
        assertNotNull(shortPl);
        assertEquals(YandexMusicClient.ParsedYandexUrl.Kind.SHORT_PLAYLIST, shortPl.kind);
        assertEquals("lk.abcdef-1234", shortPl.primaryId);

        assertFalse(YandexMusicClient.isYandexMusicUrl("https://rutube.ru/video/1234"));
        assertEquals("ym", BotMusicSearch.normalizeService("Яндекс"));
        assertEquals("ym", BotMusicSearch.normalizeService("ym"));
    }
}
