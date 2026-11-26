package org.teamtalkextro.bot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class LocalMediaRelayServerTest {

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

        String audioUrl = LocalMediaRelayServer.selectMasterVariantUrl(
                master, "https://cdn.example.tv/live/master.m3u8", false);
        assertEquals("https://cdn.example.tv/live/audio_ru.m3u8", audioUrl);
    }

    @Test
    public void testHlsMediaPlaylistRewritingHttpsAndKeys() {
        String media = "#EXTM3U\n"
                + "#EXT-X-VERSION:4\n"
                + "#EXT-X-TARGETDURATION:6\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"keys/enc.key\",IV=0x1234\n"
                + "#EXT-X-MAP:URI=\"https://cdn.example.tv/hls/init.mp4\"\n"
                + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"ru\",URI=\"sub/audio.m3u8\"\n"
                + "#EXTINF:6.0,\n"
                + "seg_001.ts\n"
                + "#EXTINF:6.0,\n"
                + "https://edge.example.tv/hls/seg_002.ts?token=abc\n";

        String rewritten = LocalMediaRelayServer.rewriteHlsMediaPlaylist(
                media,
                "https://cdn.example.tv/hls/720p/index.m3u8",
                "http://127.0.0.1:43210",
                "https://player.example.tv/",
                true);

        assertFalse("No raw upstream https URLs should remain in rewritten playlist",
                rewritten.contains("https://cdn.example.tv") || rewritten.contains("https://edge.example.tv"));
        assertTrue(rewritten.contains("http://127.0.0.1:43210/seg?u="));
        assertTrue(rewritten.contains("http://127.0.0.1:43210/hls-proxy?u="));
        assertTrue(rewritten.contains("#EXT-X-KEY:METHOD=AES-128,URI=\"http://127.0.0.1:43210/seg?u="));
        assertTrue(rewritten.contains("#EXT-X-MAP:URI=\"http://127.0.0.1:43210/seg?u="));

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
}
