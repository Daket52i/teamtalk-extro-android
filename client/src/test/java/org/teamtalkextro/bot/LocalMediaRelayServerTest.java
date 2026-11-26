package org.teamtalkextro.bot;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class LocalMediaRelayServerTest {

    @Test
    public void testSelectUserAgentForUrl() {
        assertEquals(LocalMediaRelayServer.WINK_USER_AGENT,
                LocalMediaRelayServer.selectUserAgentForUrl(
                        "http://rt-vlg-kzn-htlive.cdn.ngenix.net/hls/CH_R03_OTT_VLG_KZN_1TV/variant.m3u8", null));
        assertEquals(LocalMediaRelayServer.WINK_USER_AGENT,
                LocalMediaRelayServer.selectUserAgentForUrl("https://zabava-htlive.cdn.ngenix.net/live/index.m3u8", ""));
        assertEquals(LocalMediaRelayServer.WINK_USER_AGENT,
                LocalMediaRelayServer.selectUserAgentForUrl("https://wink.rt.ru/hls/stream.m3u8", null));
        assertEquals(BotMusicSearch.USER_AGENT,
                LocalMediaRelayServer.selectUserAgentForUrl("https://cdn.ntv.ru/vitrina/index.m3u8", null));
        assertEquals("CustomPlayer/2.0",
                LocalMediaRelayServer.selectUserAgentForUrl(
                        "http://rt-vlg-kzn-htlive.cdn.ngenix.net/hls/test/variant.m3u8", "CustomPlayer/2.0"));
    }

    @Test
    public void testHlsMasterVariantSelectionPrefersSmoothMobileVideo() {
        String master = "#EXTM3U\n"
                + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"ru\",URI=\"audio_ru.m3u8\"\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS=\"mp4a.40.2\"\n"
                + "audio_only.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080,CODECS=\"avc1.640028,mp4a.40.2\"\n"
                + "1080p/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=1350000,RESOLUTION=1280x720,CODECS=\"avc1.4d401f,mp4a.40.2\"\n"
                + "720p/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=450000,RESOLUTION=640x360,CODECS=\"avc1.42c01e,mp4a.40.2\"\n"
                + "360p/index.m3u8\n";

        String videoUrl = LocalMediaRelayServer.selectMasterVariantUrl(
                master, "https://cdn.example.tv/live/master.m3u8", true);
        assertEquals("https://cdn.example.tv/live/720p/index.m3u8", videoUrl);

        String lowBitrateUrl = LocalMediaRelayServer.selectMasterVariantUrl(
                master, "https://cdn.example.tv/live/master.m3u8", true, 500);
        assertEquals("https://cdn.example.tv/live/360p/index.m3u8", lowBitrateUrl);

        String audioUrl = LocalMediaRelayServer.selectMasterVariantUrl(
                master, "https://cdn.example.tv/live/master.m3u8", false);
        assertEquals("https://cdn.example.tv/live/audio_ru.m3u8", audioUrl);
    }

    @Test
    public void testHlsMediaPlaylistRewritingPreservesAllowedExtensions() {
        String media = "#EXTM3U\n"
                + "#EXT-X-VERSION:4\n"
                + "#EXT-X-TARGETDURATION:6\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"keys/enc.key\",IV=0x1234\n"
                + "#EXT-X-MAP:URI=\"https://cdn.example.tv/hls/init.mp4\"\n"
                + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"ru\",URI=\"sub/audio.m3u8\"\n"
                + "#EXTINF:6.0,\n"
                + "seg_001.ts\n"
                + "#EXTINF:6.0,\n"
                + "https://edge.example.tv/hls/seg_002.m4s?token=abc\n"
                + "#EXTINF:6.0,\n"
                + "chunk_without_ext?seq=3\n";

        String rewritten = LocalMediaRelayServer.rewriteHlsMediaPlaylist(
                media,
                "https://cdn.example.tv/hls/720p/index.m3u8",
                "http://127.0.0.1:43210",
                "https://player.example.tv/",
                LocalMediaRelayServer.WINK_USER_AGENT,
                true);

        assertFalse("No raw upstream https URLs should remain in rewritten playlist",
                rewritten.contains("https://cdn.example.tv") || rewritten.contains("https://edge.example.tv"));
        assertTrue("Key URI should carry .key extension",
                rewritten.contains("#EXT-X-KEY:METHOD=AES-128,URI=\"http://127.0.0.1:43210/seg/key.key?u="));
        assertTrue("Map URI should carry .mp4 extension",
                rewritten.contains("#EXT-X-MAP:URI=\"http://127.0.0.1:43210/seg/init.mp4?u="));
        assertTrue("Nested media playlist should carry .m3u8 extension",
                rewritten.contains("http://127.0.0.1:43210/hls-proxy/sub.m3u8?u="));
        assertTrue("TS segment should carry .ts extension",
                rewritten.contains("http://127.0.0.1:43210/seg/s_0.ts?u="));
        assertTrue("fMP4 segment should preserve .m4s extension",
                rewritten.contains("http://127.0.0.1:43210/seg/s_1.m4s?u="));
        assertTrue("Extensionless segment should default to .ts",
                rewritten.contains("http://127.0.0.1:43210/seg/s_2.ts?u="));
        assertTrue("Custom UA should be forwarded via &ua=",
                rewritten.contains("&ua=" + LocalMediaRelayServer.encodeBase64Url(LocalMediaRelayServer.WINK_USER_AGENT)));

        // Verify base64url round-trip on encoded segment URL
        String expectedSeg1 = LocalMediaRelayServer.encodeBase64Url("https://cdn.example.tv/hls/720p/seg_001.ts");
        assertTrue(rewritten.contains(expectedSeg1));
        assertEquals("https://cdn.example.tv/hls/720p/seg_001.ts",
                LocalMediaRelayServer.decodeBase64Url(expectedSeg1));
    }

    @Test
    public void testPlsAndM3uExtraction() {
        String pls = "[playlist]\nNumberOfEntries=2\nFile1=https://icecast.example.ru:8443/stream128.mp3\nTitle1=Test Radio\n";
        assertEquals("https://icecast.example.ru:8443/stream128.mp3",
                LocalMediaRelayServer.extractFirstUrlFromPlaylist(pls, "https://icecast.example.ru/listen.pls"));

        String m3u = "#EXTM3U\n#EXTINF:-1,Radio Test\nstreams/live.aacp\n";
        assertEquals("https://radio.example.ru/streams/live.aacp",
                LocalMediaRelayServer.extractFirstUrlFromPlaylist(m3u, "https://radio.example.ru/play.m3u"));
    }

    @Test
    public void testLiveLoopbackHttpRelayEndToEnd() throws Exception {
        // Spin up a tiny upstream mock HTTP server on loopback
        final byte[] fakeAudio = "ID3MOCKAUDIOSTREAMDATA1234567890".getBytes(StandardCharsets.US_ASCII);
        ServerSocket upstream = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        int upPort = upstream.getLocalPort();
        Thread upThread = new Thread(() -> {
            try (Socket s = upstream.accept()) {
                BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                String line;
                while ((line = br.readLine()) != null && !line.isEmpty()) {
                    // drain request headers
                }
                OutputStream os = s.getOutputStream();
                String hdr = "HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nContent-Length: "
                        + fakeAudio.length + "\r\nConnection: close\r\n\r\n";
                os.write(hdr.getBytes(StandardCharsets.ISO_8859_1));
                os.write(fakeAudio);
                os.flush();
            } catch (Throwable ignored) {
            }
        });
        upThread.setDaemon(true);
        upThread.start();

        LocalMediaRelayServer relay = new LocalMediaRelayServer();
        try {
            String relayUrl = relay.registerLive("http://127.0.0.1:" + upPort + "/stream.mp3", null);
            assertNotNull(relayUrl);
            assertTrue(relayUrl.startsWith("http://127.0.0.1:" + relay.getPort() + "/live/"));

            HttpURLConnection conn = (HttpURLConnection) new URL(relayUrl).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            assertEquals(200, conn.getResponseCode());
            assertTrue(conn.getContentType().contains("audio/mpeg"));

            byte[] buf = new byte[64];
            int n = conn.getInputStream().read(buf);
            assertEquals(fakeAudio.length, n);
            assertEquals(new String(fakeAudio, StandardCharsets.US_ASCII),
                    new String(buf, 0, n, StandardCharsets.US_ASCII));
            conn.disconnect();
        } finally {
            relay.stop();
            upstream.close();
        }
    }

    @Test
    public void testContinuousMpegTsRelayEndToEndAndFmp4Detection() throws Exception {
        // Build two 188-byte-aligned synthetic MPEG-TS packets per segment (376 bytes each)
        final byte[] seg1 = new byte[188 * 2];
        final byte[] seg2 = new byte[188 * 2];
        seg1[0] = 0x47;
        seg1[1] = 0x40;
        seg1[2] = 0x00;
        seg1[3] = 0x10;
        seg1[188] = 0x47;
        seg1[189] = 0x40;
        seg1[190] = 0x01;
        seg1[191] = 0x11;

        seg2[0] = 0x47;
        seg2[1] = 0x00;
        seg2[2] = 0x10;
        seg2[3] = 0x12;
        seg2[188] = 0x47;
        seg2[189] = 0x00;
        seg2[190] = 0x11;
        seg2[191] = 0x13;

        final byte[] expectedStitched = new byte[seg1.length + seg2.length];
        System.arraycopy(seg1, 0, expectedStitched, 0, seg1.length);
        System.arraycopy(seg2, 0, expectedStitched, seg1.length, seg2.length);

        final String tsPlaylist = "#EXTM3U\n"
                + "#EXT-X-VERSION:3\n"
                + "#EXT-X-TARGETDURATION:2\n"
                + "#EXT-X-MEDIA-SEQUENCE:10\n"
                + "#EXTINF:2.0,\n"
                + "seg1.ts\n"
                + "#EXTINF:2.0,\n"
                + "seg2.ts\n"
                + "#EXT-X-ENDLIST\n";

        final String fmp4Playlist = "#EXTM3U\n"
                + "#EXT-X-VERSION:7\n"
                + "#EXT-X-TARGETDURATION:2\n"
                + "#EXT-X-MAP:URI=\"init.mp4\"\n"
                + "#EXTINF:2.0,\n"
                + "seg1.m4s\n"
                + "#EXT-X-ENDLIST\n";

        final AtomicBoolean keepServing = new AtomicBoolean(true);
        ServerSocket upstream = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        int upPort = upstream.getLocalPort();
        Thread upThread = new Thread(() -> {
            while (keepServing.get() && !upstream.isClosed()) {
                try (Socket s = upstream.accept()) {
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                    String req = br.readLine();
                    if (req == null) continue;
                    String line;
                    while ((line = br.readLine()) != null && !line.isEmpty()) {
                        // drain headers
                    }
                    String[] parts = req.split("\\s+");
                    String path = parts.length >= 2 ? parts[1] : "/";
                    OutputStream os = s.getOutputStream();
                    byte[] body;
                    String ct;
                    if ("/live/index.m3u8".equals(path)) {
                        body = tsPlaylist.getBytes(StandardCharsets.UTF_8);
                        ct = "application/vnd.apple.mpegurl";
                    } else if ("/live/fmp4.m3u8".equals(path)) {
                        body = fmp4Playlist.getBytes(StandardCharsets.UTF_8);
                        ct = "application/vnd.apple.mpegurl";
                    } else if ("/live/seg1.ts".equals(path)) {
                        body = seg1;
                        ct = "video/mp2t";
                    } else if ("/live/seg2.ts".equals(path)) {
                        body = seg2;
                        ct = "video/mp2t";
                    } else {
                        String nf = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                        os.write(nf.getBytes(StandardCharsets.ISO_8859_1));
                        os.flush();
                        continue;
                    }
                    String hdr = "HTTP/1.1 200 OK\r\nContent-Type: " + ct
                            + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                    os.write(hdr.getBytes(StandardCharsets.ISO_8859_1));
                    os.write(body);
                    os.flush();
                } catch (Throwable ignored) {
                }
            }
        });
        upThread.setDaemon(true);
        upThread.start();

        LocalMediaRelayServer relay = new LocalMediaRelayServer();
        try {
            // 1) Continuous MPEG-TS stream GET /tv/<id>.ts
            String tsUrl = relay.registerTsStream(
                    "http://127.0.0.1:" + upPort + "/live/index.m3u8",
                    null,
                    LocalMediaRelayServer.WINK_USER_AGENT,
                    true,
                    850);
            assertNotNull(tsUrl);
            assertTrue(tsUrl.startsWith("http://127.0.0.1:" + relay.getPort() + "/tv/"));
            assertTrue(tsUrl.endsWith(".ts"));

            // Verify HEAD /tv/<id>.ts responds cleanly with 200 + video/mp2t
            HttpURLConnection headConn = (HttpURLConnection) new URL(tsUrl).openConnection();
            headConn.setRequestMethod("HEAD");
            headConn.setConnectTimeout(4000);
            headConn.setReadTimeout(4000);
            assertEquals(200, headConn.getResponseCode());
            assertTrue(headConn.getContentType().contains("video/mp2t"));
            headConn.disconnect();

            // Verify GET /tv/<id>.ts streams exact stitched MPEG-TS packets
            HttpURLConnection getConn = (HttpURLConnection) new URL(tsUrl).openConnection();
            getConn.setConnectTimeout(4000);
            getConn.setReadTimeout(4000);
            assertEquals(200, getConn.getResponseCode());
            assertTrue(getConn.getContentType().contains("video/mp2t"));
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            try (InputStream is = getConn.getInputStream()) {
                byte[] buf = new byte[512];
                int n;
                while ((n = is.read(buf)) > 0) {
                    received.write(buf, 0, n);
                }
            }
            getConn.disconnect();
            assertArrayEquals("Stitched MPEG-TS payload must match both 188-byte aligned segments",
                    expectedStitched, received.toByteArray());

            // 2) Verify fMP4 (#EXT-X-MAP) playlist is cleanly rejected with HTTP 415 before 200 OK
            String fmp4TsUrl = relay.registerTsStream(
                    "http://127.0.0.1:" + upPort + "/live/fmp4.m3u8", null, null, true, 850);
            HttpURLConnection fmp4Conn = (HttpURLConnection) new URL(fmp4TsUrl).openConnection();
            fmp4Conn.setConnectTimeout(4000);
            fmp4Conn.setReadTimeout(4000);
            assertEquals(415, fmp4Conn.getResponseCode());
            fmp4Conn.disconnect();
        } finally {
            keepServing.set(false);
            relay.stop();
            upstream.close();
        }
    }
}
