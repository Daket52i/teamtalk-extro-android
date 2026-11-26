package org.teamtalkextro.bot;

import android.util.Log;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Lightweight loopback-only HTTP relay server (127.0.0.1:<ephemeralPort>) that
 * bridges HTTPS/TLS 1.3 internet radio, Volnorez, and HLS (.m3u8) TV streams
 * into plain local HTTP for TeamTalk5's native FFmpeg (which includes
 * ff_http_protocol and ff_hls_demuxer, but not ff_https_protocol / TLS).
 *
 * Provides:
 * - Continuous live MPEG-TS streamer ({@code GET /tv/<id>.ts}) that stitches
 *   upstream HLS .ts segments (including AES-128 decryption and 0x47 sync alignment)
 *   into a single continuous {@code video/mp2t} HTTP stream for maximum FFmpeg reliability.
 * - Fixed HLS playlist rewriter ({@code GET /hls/<id>.m3u8}) preserving proper
 *   file extensions ({@code /seg/s_0.ts}, {@code /seg/key.key}, {@code /seg/init.mp4})
 *   so FFmpeg's {@code hls.c} {@code allowed_extensions} check always passes.
 * - Automatic Wink/Rostelecom User-Agent selection for {@code cdn.ngenix.net} / Wink URLs.
 */
public final class LocalMediaRelayServer {

    private static final String TAG = "LocalMediaRelay";
    public static final String WINK_USER_AGENT = "WINK/1.40.1 (AndroidTV/9) HlsWinkPlayer";

    private static final int MAX_REDIRECTS = 8;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 18_000;
    private static final int MAX_PLAYLIST_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SEGMENT_BYTES = 16 * 1024 * 1024;
    private static final int MAX_REGISTERED_ENTRIES = 64;
    private static final int MAX_SEEN_SEGMENTS = 64;
    private static final int MAX_CONSECUTIVE_POLL_FAILURES = 5;

    private static final Pattern URI_ATTR_PATTERN = Pattern.compile("URI=\"([^\"]+)\"");
    private static final Pattern BANDWIDTH_PATTERN = Pattern.compile("BANDWIDTH=(\\d+)");
    private static final Pattern RESOLUTION_PATTERN = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)");
    private static final Pattern CODECS_PATTERN = Pattern.compile("CODECS=\"([^\"]+)\"");
    private static final Pattern PLS_FILE_PATTERN = Pattern.compile("^File\\d+\\s*=\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern KEY_METHOD_PATTERN = Pattern.compile("METHOD=([^,\\s]+)");
    private static final Pattern KEY_IV_PATTERN = Pattern.compile("IV=0[xX]([0-9a-fA-F]+)");

    public static final class StreamEntry {
        public final String id;
        public final String url;
        public final String referer;
        public final String customUserAgent;
        public final boolean preferVideo;
        public final int maxBitrateKbps;

        public StreamEntry(String id, String url, String referer, boolean preferVideo) {
            this(id, url, referer, null, preferVideo, 0);
        }

        public StreamEntry(String id, String url, String referer, String customUserAgent,
                           boolean preferVideo, int maxBitrateKbps) {
            this.id = id;
            this.url = url;
            this.referer = referer;
            this.customUserAgent = customUserAgent;
            this.preferVideo = preferVideo;
            this.maxBitrateKbps = maxBitrateKbps;
        }
    }

    private final Map<String, StreamEntry> entries = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    private volatile boolean running = false;
    private volatile int port = -1;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService workerPool;

    public int ensureStarted() throws IOException {
        synchronized (lifecycleLock) {
            if (running && serverSocket != null && !serverSocket.isClosed() && port > 0) {
                return port;
            }
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            ServerSocket ss = new ServerSocket(0, 32, loopback);
            ss.setReuseAddress(true);
            this.serverSocket = ss;
            this.port = ss.getLocalPort();
            this.running = true;
            ThreadPoolExecutor exec = new ThreadPoolExecutor(
                    2,
                    16,
                    30L,
                    TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "Extro-MediaRelayWorker");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
            exec.allowCoreThreadTimeOut(true);
            this.workerPool = exec;
            this.acceptThread = new Thread(this::acceptLoop, "Extro-MediaRelayAccept");
            this.acceptThread.setDaemon(true);
            this.acceptThread.start();
            logDebug("Started loopback media relay on 127.0.0.1:" + this.port);
            return this.port;
        }
    }

    public int getPort() {
        return running ? port : -1;
    }

    public boolean isRunning() {
        return running && port > 0;
    }

    public String registerLive(String upstreamUrl, String referer) throws IOException {
        int p = ensureStarted();
        trimRegistryIfNeeded();
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        boolean looksHls = looksLikeHlsUrl(upstreamUrl);
        entries.put(id, new StreamEntry(id, upstreamUrl, referer, null, false, 0));
        if (looksHls) {
            return "http://127.0.0.1:" + p + "/hls/" + id + ".m3u8";
        }
        return "http://127.0.0.1:" + p + "/live/" + id;
    }

    public String registerHls(String upstreamUrl, String referer, boolean preferVideo) throws IOException {
        return registerHls(upstreamUrl, referer, null, preferVideo);
    }

    public String registerHls(String upstreamUrl, String referer, String customUserAgent, boolean preferVideo) throws IOException {
        int p = ensureStarted();
        trimRegistryIfNeeded();
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        entries.put(id, new StreamEntry(id, upstreamUrl, referer, customUserAgent, preferVideo, 0));
        return "http://127.0.0.1:" + p + "/hls/" + id + ".m3u8";
    }

    public String registerTsStream(String upstreamUrl, String referer, String customUserAgent,
                                   boolean preferVideo, int maxBitrateKbps) throws IOException {
        int p = ensureStarted();
        trimRegistryIfNeeded();
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        entries.put(id, new StreamEntry(id, upstreamUrl, referer, customUserAgent, preferVideo, maxBitrateKbps));
        return "http://127.0.0.1:" + p + "/tv/" + id + ".ts";
    }

    public void stop() {
        synchronized (lifecycleLock) {
            running = false;
            port = -1;
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (Throwable ignored) {
                }
                serverSocket = null;
            }
            if (acceptThread != null) {
                acceptThread.interrupt();
                acceptThread = null;
            }
            if (workerPool != null) {
                try {
                    workerPool.shutdownNow();
                } catch (Throwable ignored) {
                }
                workerPool = null;
            }
            entries.clear();
        }
    }

    private void trimRegistryIfNeeded() {
        if (entries.size() > MAX_REGISTERED_ENTRIES) {
            entries.clear();
        }
    }

    private void acceptLoop() {
        while (running) {
            ServerSocket ss = this.serverSocket;
            if (ss == null || ss.isClosed()) break;
            try {
                Socket client = ss.accept();
                ExecutorService pool = this.workerPool;
                if (pool != null && !pool.isShutdown()) {
                    try {
                        pool.execute(() -> handleClient(client));
                    } catch (RejectedExecutionException rex) {
                        logWarn("Relay worker pool saturated, rejecting connection");
                        try {
                            sendSimpleError(client.getOutputStream(), 503, "Service Unavailable");
                        } catch (Throwable ignored) {
                        }
                        closeQuiet(client);
                    }
                } else {
                    closeQuiet(client);
                }
            } catch (IOException e) {
                if (running) {
                    logWarn("Relay accept error: " + e.getMessage());
                }
                break;
            }
        }
    }

    private void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(15_000);
            socket.setTcpNoDelay(true);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.trim().isEmpty()) {
                return;
            }
            // Drain incoming request headers
            String headerLine;
            String rangeHeader = null;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                int colon = headerLine.indexOf(':');
                if (colon > 0) {
                    String k = headerLine.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    if ("range".equals(k)) {
                        rangeHeader = headerLine.substring(colon + 1).trim();
                    }
                }
            }

            String[] parts = requestLine.split("\\s+");
            if (parts.length < 2) {
                sendSimpleError(socket.getOutputStream(), 400, "Bad Request");
                return;
            }
            String method = parts[0].toUpperCase(Locale.ROOT);
            boolean headOnly = "HEAD".equals(method);
            if (!"GET".equals(method) && !headOnly) {
                sendSimpleError(socket.getOutputStream(), 405, "Method Not Allowed");
                return;
            }

            String target = parts[1];
            String path = target;
            String query = "";
            int qIdx = target.indexOf('?');
            if (qIdx >= 0) {
                path = target.substring(0, qIdx);
                query = target.substring(qIdx + 1);
            }

            OutputStream out = socket.getOutputStream();
            if (path.startsWith("/tv/")) {
                String id = path.substring("/tv/".length());
                if (id.endsWith(".ts")) {
                    id = id.substring(0, id.length() - 3);
                }
                StreamEntry entry = entries.get(id);
                if (entry == null) {
                    sendSimpleError(out, 404, "Not Found");
                    return;
                }
                serveContinuousTsStream(entry, headOnly, out);
            } else if (path.startsWith("/live/")) {
                String id = path.substring("/live/".length());
                int dot = id.indexOf('.');
                if (dot > 0) id = id.substring(0, dot);
                StreamEntry entry = entries.get(id);
                if (entry == null) {
                    sendSimpleError(out, 404, "Not Found");
                    return;
                }
                serveLiveStream(entry, headOnly, rangeHeader, out);
            } else if (path.startsWith("/hls/")) {
                String id = path.substring("/hls/".length());
                if (id.endsWith(".m3u8")) {
                    id = id.substring(0, id.length() - 5);
                }
                StreamEntry entry = entries.get(id);
                if (entry == null) {
                    sendSimpleError(out, 404, "Not Found");
                    return;
                }
                serveHlsPlaylist(entry.url, entry.referer, entry.customUserAgent, entry.preferVideo, entry.maxBitrateKbps, headOnly, out);
            } else if ("/hls-proxy".equals(path) || path.startsWith("/hls-proxy/")) {
                Map<String, String> params = parseQueryParams(query);
                String u = decodeBase64Url(params.get("u"));
                String r = decodeBase64Url(params.get("r"));
                String ua = decodeBase64Url(params.get("ua"));
                boolean preferVideo = !"0".equals(params.get("v"));
                if (u.isEmpty()) {
                    sendSimpleError(out, 400, "Missing u");
                    return;
                }
                serveHlsPlaylist(u, r, ua.isEmpty() ? null : ua, preferVideo, 0, headOnly, out);
            } else if ("/seg".equals(path) || path.startsWith("/seg/")) {
                Map<String, String> params = parseQueryParams(query);
                String u = decodeBase64Url(params.get("u"));
                String r = decodeBase64Url(params.get("r"));
                String ua = decodeBase64Url(params.get("ua"));
                if (u.isEmpty()) {
                    sendSimpleError(out, 400, "Missing u");
                    return;
                }
                serveSegmentWithRetry(u, r, ua.isEmpty() ? null : ua, rangeHeader, headOnly, out);
            } else if ("/health".equals(path)) {
                byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
                writeHttpHeaders(out, 200, "OK", "text/plain; charset=utf-8", body.length);
                if (!headOnly) {
                    out.write(body);
                    out.flush();
                }
            } else {
                sendSimpleError(out, 404, "Not Found");
            }
        } catch (IOException ignored) {
            // Client closed connection (normal when FFmpeg stops/reconnects)
        } catch (Throwable t) {
            logWarn("Relay client error: " + t.getMessage());
        } finally {
            closeQuiet(socket);
        }
    }

    /**
     * Continuous Live MPEG-TS Streamer ({@code GET /tv/<id>.ts}):
     * Fetches the upstream HLS playlist, resolves master variants, validates
     * standard MPEG-TS segments (rejecting fMP4 {@code #EXT-X-MAP} early before 200 OK
     * so caller can switch to {@code /hls/<id>.m3u8}), decrypts AES-128 segments if needed,
     * aligns on 0x47 MPEG-TS packet sync, and stitches segments into one continuous HTTP stream.
     */
    private void serveContinuousTsStream(StreamEntry entry, boolean headOnly, OutputStream out) throws IOException {
        ResolvedPlaylist resolved = resolveMediaPlaylist(entry.url, entry.referer, entry.customUserAgent,
                entry.preferVideo, entry.maxBitrateKbps);
        if (resolved == null || resolved.parsed == null) {
            sendSimpleError(out, 502, "Upstream HLS playlist unavailable");
            return;
        }
        if (resolved.parsed.hasFmp4Map || resolved.parsed.hasFmp4Segments) {
            sendSimpleError(out, 415, "fMP4 HLS requires /hls playlist relay");
            return;
        }
        if (resolved.parsed.segments.isEmpty()) {
            sendSimpleError(out, 502, "Empty HLS media playlist");
            return;
        }

        writeHttpHeaders(out, 200, "OK", "video/mp2t", -1, "no-store");
        if (headOnly) {
            return;
        }

        String mediaPlaylistUrl = resolved.mediaPlaylistUrl;
        ParsedMediaPlaylist parsed = resolved.parsed;

        LinkedHashSet<String> seenSegments = new LinkedHashSet<>();
        Map<String, byte[]> keyCache = new HashMap<>();
        boolean firstPoll = true;
        boolean syncedFirstPacket = false;
        int consecutiveFailures = 0;

        while (running) {
            if (!firstPoll) {
                ParsedMediaPlaylist refreshed = fetchParsedMediaPlaylist(
                        mediaPlaylistUrl, entry.referer, entry.customUserAgent);
                if (refreshed == null || refreshed.segments.isEmpty()) {
                    consecutiveFailures++;
                    if (consecutiveFailures == 2) {
                        // Try refreshing master playlist in case variant token rotated
                        ResolvedPlaylist reResolved = resolveMediaPlaylist(entry.url, entry.referer,
                                entry.customUserAgent, entry.preferVideo, entry.maxBitrateKbps);
                        if (reResolved != null && reResolved.parsed != null
                                && !reResolved.parsed.hasFmp4Map && !reResolved.parsed.segments.isEmpty()) {
                            mediaPlaylistUrl = reResolved.mediaPlaylistUrl;
                            refreshed = reResolved.parsed;
                            consecutiveFailures = 0;
                        }
                    }
                    if (refreshed == null || refreshed.segments.isEmpty()) {
                        if (consecutiveFailures >= MAX_CONSECUTIVE_POLL_FAILURES) {
                            break;
                        }
                        if (!sleepQuiet(1000L)) {
                            break;
                        }
                        continue;
                    }
                } else {
                    consecutiveFailures = 0;
                }
                if (refreshed.hasFmp4Map || refreshed.hasFmp4Segments) {
                    break;
                }
                parsed = refreshed;
            }

            int startIdx = 0;
            if (firstPoll) {
                firstPoll = false;
                // For live playlists (no #EXT-X-ENDLIST), jump near live edge (last 3 segments)
                if (!parsed.isVodEnded && parsed.segments.size() > 3) {
                    startIdx = parsed.segments.size() - 3;
                    for (int k = 0; k < startIdx; k++) {
                        rememberSeenSegment(seenSegments, parsed.segments.get(k).url);
                    }
                }
            }

            int streamedThisPoll = 0;
            for (int i = startIdx; i < parsed.segments.size() && running; i++) {
                MediaSegment seg = parsed.segments.get(i);
                if (seenSegments.contains(seg.url)) {
                    continue;
                }
                rememberSeenSegment(seenSegments, seg.url);

                byte[] segBytes = downloadSegmentBytesWithRetry(seg.url, entry.referer, entry.customUserAgent);
                if (segBytes == null || segBytes.length == 0) {
                    continue;
                }

                if ("AES-128".equalsIgnoreCase(seg.keyMethod) && seg.keyUri != null && !seg.keyUri.isEmpty()) {
                    byte[] decrypted = decryptAes128Segment(segBytes, seg, entry.referer, entry.customUserAgent, keyCache);
                    if (decrypted == null || decrypted.length == 0) {
                        continue;
                    }
                    segBytes = decrypted;
                }

                int writeOffset = 0;
                if (!syncedFirstPacket) {
                    int sync = findMpegTsSyncOffset(segBytes);
                    if (sync >= 0) {
                        writeOffset = sync;
                        syncedFirstPacket = true;
                    } else {
                        // Not yet aligned; still emit once sync not found only if it looks non-empty
                        syncedFirstPacket = true;
                    }
                }

                int len = segBytes.length - writeOffset;
                if (len > 0) {
                    out.write(segBytes, writeOffset, len);
                    out.flush();
                    streamedThisPoll++;
                }
            }

            if (parsed.isVodEnded) {
                break;
            }

            long sleepMs = Math.max(1200L, Math.round(parsed.targetDurationSec * 500.0));
            if (streamedThisPoll == 0) {
                sleepMs = Math.min(sleepMs, 1500L);
            }
            if (!sleepQuiet(sleepMs)) {
                break;
            }
        }
    }

    private ResolvedPlaylist resolveMediaPlaylist(String upstreamUrl, String referer, String customUserAgent,
                                                  boolean preferVideo, int maxBitrateKbps) {
        FetchedText first = fetchTextFollowingRedirects(upstreamUrl, referer, customUserAgent, true);
        if (first == null || first.body == null || first.body.trim().isEmpty()) {
            return null;
        }
        String currentUrl = first.finalUrl;
        String currentBody = first.body;
        if (currentBody.contains("#EXT-X-STREAM-INF")) {
            String variantUrl = selectMasterVariantUrl(currentBody, currentUrl, preferVideo, maxBitrateKbps);
            if (variantUrl != null && !variantUrl.isEmpty() && !variantUrl.equals(currentUrl)) {
                FetchedText variant = fetchTextFollowingRedirects(variantUrl, referer, customUserAgent, true);
                if (variant == null || variant.body == null || variant.body.trim().isEmpty()) {
                    return null;
                }
                currentUrl = variant.finalUrl;
                currentBody = variant.body;
            }
        }
        ParsedMediaPlaylist parsed = parseMediaPlaylist(currentBody, currentUrl);
        return new ResolvedPlaylist(currentUrl, parsed);
    }

    private ParsedMediaPlaylist fetchParsedMediaPlaylist(String mediaUrl, String referer, String customUserAgent) {
        FetchedText fetched = fetchTextFollowingRedirects(mediaUrl, referer, customUserAgent, true);
        if (fetched == null || fetched.body == null || fetched.body.trim().isEmpty()) {
            return null;
        }
        return parseMediaPlaylist(fetched.body, fetched.finalUrl);
    }

    private FetchedText fetchTextFollowingRedirects(String urlStr, String referer, String customUserAgent, boolean keepAlive) {
        HttpURLConnection conn = null;
        try {
            conn = openFollowingRedirects(urlStr, referer, customUserAgent, null, false, keepAlive);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            String finalUrl = conn.getURL() != null ? conn.getURL().toString() : urlStr;
            String body = readLimitedString(conn.getInputStream(), MAX_PLAYLIST_BYTES);
            return new FetchedText(finalUrl, body);
        } catch (IOException e) {
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private byte[] downloadSegmentBytesWithRetry(String segUrl, String referer, String customUserAgent) {
        for (int attempt = 0; attempt < 2 && running; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = openFollowingRedirects(segUrl, referer, customUserAgent, null, false, true);
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    return readLimitedBytes(conn.getInputStream(), MAX_SEGMENT_BYTES);
                }
            } catch (IOException ignored) {
            } finally {
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return null;
    }

    private byte[] decryptAes128Segment(byte[] encrypted, MediaSegment seg, String referer,
                                        String customUserAgent, Map<String, byte[]> keyCache) {
        try {
            byte[] keyBytes = keyCache.get(seg.keyUri);
            if (keyBytes == null) {
                keyBytes = downloadSegmentBytesWithRetry(seg.keyUri, referer, customUserAgent);
                if (keyBytes == null || keyBytes.length != 16) {
                    return null;
                }
                if (keyCache.size() >= 16) {
                    keyCache.clear();
                }
                keyCache.put(seg.keyUri, keyBytes);
            }
            byte[] iv = deriveAes128Iv(seg.keyIvHex, seg.sequenceNumber);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(encrypted);
        } catch (Throwable t) {
            logWarn("AES-128 segment decrypt failed: " + t.getMessage());
            return null;
        }
    }

    private static void rememberSeenSegment(LinkedHashSet<String> seen, String url) {
        if (seen.contains(url)) return;
        while (seen.size() >= MAX_SEEN_SEGMENTS) {
            Iterator<String> it = seen.iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            } else {
                break;
            }
        }
        seen.add(url);
    }

    private boolean sleepQuiet(long millis) {
        long remaining = millis;
        while (running && remaining > 0) {
            long step = Math.min(remaining, 250L);
            try {
                Thread.sleep(step);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            remaining -= step;
        }
        return running;
    }

    private void serveLiveStream(StreamEntry entry, boolean headOnly, String rangeHeader, OutputStream out) throws IOException {
        String resolvedUrl = entry.url;
        // If URL is a playlist (.pls / .m3u), resolve the first actual stream URL
        if (looksLikeSimplePlaylistUrl(resolvedUrl)) {
            String extracted = fetchAndExtractPlaylistUrl(resolvedUrl, entry.referer, entry.customUserAgent);
            if (extracted != null && !extracted.isEmpty()) {
                resolvedUrl = extracted;
            }
        }
        if (looksLikeHlsUrl(resolvedUrl)) {
            serveHlsPlaylist(resolvedUrl, entry.referer, entry.customUserAgent, entry.preferVideo, entry.maxBitrateKbps, headOnly, out);
            return;
        }

        HttpURLConnection conn = openFollowingRedirects(resolvedUrl, entry.referer, entry.customUserAgent, rangeHeader, false, false);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                sendSimpleError(out, 502, "Upstream HTTP " + code);
                return;
            }
            String finalUrl = conn.getURL() != null ? conn.getURL().toString() : resolvedUrl;
            String rawCt = conn.getContentType();
            String ctLower = rawCt != null ? rawCt.toLowerCase(Locale.ROOT) : "";

            // Check if upstream actually returned a .pls/.m3u/.m3u8 text playlist
            boolean isPlaylistMime = ctLower.contains("mpegurl")
                    || ctLower.contains("x-scpls")
                    || ctLower.contains("pls")
                    || looksLikeHlsUrl(finalUrl);
            if (isPlaylistMime && !headOnly) {
                String body = readLimitedString(conn.getInputStream(), MAX_PLAYLIST_BYTES);
                conn.disconnect();
                conn = null;
                if (body.contains("#EXT-X-") || looksLikeHlsUrl(finalUrl)) {
                    serveHlsBody(finalUrl, entry.referer, entry.customUserAgent, entry.preferVideo, entry.maxBitrateKbps, body, false, out);
                    return;
                }
                String first = extractFirstUrlFromPlaylist(body, finalUrl);
                if (first != null && !first.isEmpty() && !first.equals(finalUrl)) {
                    if (looksLikeHlsUrl(first)) {
                        serveHlsPlaylist(first, entry.referer, entry.customUserAgent, entry.preferVideo, entry.maxBitrateKbps, false, out);
                        return;
                    }
                    conn = openFollowingRedirects(first, entry.referer, entry.customUserAgent, rangeHeader, false, false);
                    int code2 = conn.getResponseCode();
                    if (code2 < 200 || code2 >= 300) {
                        sendSimpleError(out, 502, "Upstream HTTP " + code2);
                        return;
                    }
                    rawCt = conn.getContentType();
                    finalUrl = conn.getURL() != null ? conn.getURL().toString() : first;
                } else {
                    sendSimpleError(out, 502, "Empty playlist");
                    return;
                }
            }

            String contentType = normalizeAudioContentType(rawCt, finalUrl);
            long contentLength = -1;
            try {
                contentLength = conn.getContentLengthLong();
            } catch (Throwable ignored) {
            }
            // Live radio streams should not send Content-Length
            if (contentLength <= 0) contentLength = -1;

            writeHttpHeaders(out, 200, "OK", contentType, contentLength);
            if (headOnly) {
                return;
            }

            try (InputStream is = new BufferedInputStream(conn.getInputStream(), 16384)) {
                byte[] buf = new byte[16384];
                int n;
                while (running && (n = is.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
            }
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void serveHlsPlaylist(String playlistUrl, String referer, String customUserAgent,
                                  boolean preferVideo, int maxBitrateKbps, boolean headOnly,
                                  OutputStream out) throws IOException {
        HttpURLConnection conn = openFollowingRedirects(playlistUrl, referer, customUserAgent, null, false, false);
        String finalUrl = playlistUrl;
        String body;
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                sendSimpleError(out, 502, "Upstream HLS HTTP " + code);
                return;
            }
            if (conn.getURL() != null) {
                finalUrl = conn.getURL().toString();
            }
            body = readLimitedString(conn.getInputStream(), MAX_PLAYLIST_BYTES);
        } finally {
            try {
                conn.disconnect();
            } catch (Throwable ignored) {
            }
        }
        serveHlsBody(finalUrl, referer, customUserAgent, preferVideo, maxBitrateKbps, body, headOnly, out);
    }

    private void serveHlsBody(String finalUrl, String referer, String customUserAgent, boolean preferVideo,
                              int maxBitrateKbps, String body, boolean headOnly, OutputStream out) throws IOException {
        if (body == null || body.trim().isEmpty()) {
            sendSimpleError(out, 502, "Empty HLS playlist");
            return;
        }
        String currentUrl = finalUrl;
        String currentBody = body;

        // If this is a master playlist (#EXT-X-STREAM-INF), select optimal variant & fetch media playlist
        if (currentBody.contains("#EXT-X-STREAM-INF")) {
            String variantUrl = selectMasterVariantUrl(currentBody, currentUrl, preferVideo, maxBitrateKbps);
            if (variantUrl != null && !variantUrl.isEmpty() && !variantUrl.equals(currentUrl)) {
                HttpURLConnection vConn = openFollowingRedirects(variantUrl, referer, customUserAgent, null, false, false);
                try {
                    int vCode = vConn.getResponseCode();
                    if (vCode >= 200 && vCode < 300) {
                        if (vConn.getURL() != null) {
                            currentUrl = vConn.getURL().toString();
                        } else {
                            currentUrl = variantUrl;
                        }
                        currentBody = readLimitedString(vConn.getInputStream(), MAX_PLAYLIST_BYTES);
                    }
                } finally {
                    try {
                        vConn.disconnect();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        String baseLocal = "http://127.0.0.1:" + this.port;
        String rewritten = rewriteHlsMediaPlaylist(currentBody, currentUrl, baseLocal, referer, customUserAgent, preferVideo);
        byte[] bytes = rewritten.getBytes(StandardCharsets.UTF_8);
        writeHttpHeaders(out, 200, "OK", "application/vnd.apple.mpegurl; charset=utf-8", bytes.length);
        if (!headOnly) {
            out.write(bytes);
            out.flush();
        }
    }

    private void serveSegmentWithRetry(String segUrl, String referer, String customUserAgent, String rangeHeader,
                                       boolean headOnly, OutputStream out) throws IOException {
        IOException lastErr = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = openFollowingRedirects(segUrl, referer, customUserAgent, rangeHeader, false, true);
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    String ct = conn.getContentType();
                    if (ct == null || ct.trim().isEmpty()) {
                        ct = "video/mp2t";
                    }
                    long len = -1;
                    try {
                        len = conn.getContentLengthLong();
                    } catch (Throwable ignored) {
                    }
                    writeHttpHeaders(out, code, code == 206 ? "Partial Content" : "OK", ct, len);
                    if (headOnly) {
                        return;
                    }
                    try (InputStream is = new BufferedInputStream(conn.getInputStream(), 16384)) {
                        byte[] buf = new byte[16384];
                        int n;
                        while (running && (n = is.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                        out.flush();
                    }
                    return;
                }
                lastErr = new IOException("Segment HTTP " + code);
            } catch (IOException e) {
                lastErr = e;
            } finally {
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        sendSimpleError(out, 502, lastErr != null ? lastErr.getMessage() : "Segment fetch failed");
    }

    private String fetchAndExtractPlaylistUrl(String playlistUrl, String referer, String customUserAgent) {
        HttpURLConnection conn = null;
        try {
            conn = openFollowingRedirects(playlistUrl, referer, customUserAgent, null, false, false);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            String baseUrl = conn.getURL() != null ? conn.getURL().toString() : playlistUrl;
            String body = readLimitedString(conn.getInputStream(), MAX_PLAYLIST_BYTES);
            return extractFirstUrlFromPlaylist(body, baseUrl);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private HttpURLConnection openFollowingRedirects(String urlStr, String referer, String customUserAgent,
                                                     String rangeHeader, boolean wantIcy, boolean keepAlive) throws IOException {
        String current = urlStr;
        for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", selectUserAgentForUrl(current, customUserAgent));
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Connection", keepAlive ? "keep-alive" : "close");
            if (referer != null && !referer.trim().isEmpty()) {
                conn.setRequestProperty("Referer", referer.trim());
            }
            if (rangeHeader != null && !rangeHeader.trim().isEmpty()) {
                conn.setRequestProperty("Range", rangeHeader.trim());
            }
            if (wantIcy) {
                conn.setRequestProperty("Icy-MetaData", "1");
            }
            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.trim().isEmpty()) {
                    throw new IOException("Redirect without Location");
                }
                current = new URL(new URL(current), loc.trim()).toString();
                continue;
            }
            return conn;
        }
        throw new IOException("Too many redirects for " + urlStr);
    }

    // --- Pure, unit-testable playlist & streaming helpers ---

    /**
     * Selects the appropriate HTTP User-Agent for an upstream stream URL.
     * Automatically uses the Rostelecom / Wink AndroidTV player User-Agent for
     * {@code ngenix.net}, {@code wink}, and {@code zabava} streams unless overridden.
     */
    public static String selectUserAgentForUrl(String url, String overrideUa) {
        if (overrideUa != null && !overrideUa.trim().isEmpty()) {
            return overrideUa.trim();
        }
        if (url != null) {
            String low = url.toLowerCase(Locale.ROOT);
            if (low.contains("ngenix.net") || low.contains("wink") || low.contains("zabava")) {
                return WINK_USER_AGENT;
            }
        }
        return BotMusicSearch.USER_AGENT;
    }

    /**
     * Finds the byte offset of the first valid MPEG-TS 188-byte packet sync boundary ({@code 0x47}).
     */
    public static int findMpegTsSyncOffset(byte[] data) {
        if (data == null || data.length == 0) return -1;
        // Prefer confirmed 188-byte packet alignment: data[i] == 0x47 && data[i + 188] == 0x47
        int limit = Math.min(data.length - 188, 8192);
        for (int i = 0; i < limit; i++) {
            if (data[i] == 0x47 && data[i + 188] == 0x47) {
                return i;
            }
        }
        // Single-packet buffer (< 189 bytes): accept first 0x47 sync byte
        int scan = Math.min(data.length, 1024);
        for (int i = 0; i < scan; i++) {
            if (data[i] == 0x47) {
                return i;
            }
        }
        return -1;
    }

    public static byte[] deriveAes128Iv(String ivHex, long mediaSequence) {
        byte[] iv = new byte[16];
        if (ivHex != null && !ivHex.isEmpty()) {
            String clean = ivHex.trim();
            if (clean.length() > 32) {
                clean = clean.substring(clean.length() - 32);
            }
            while (clean.length() < 32) {
                clean = "0" + clean;
            }
            for (int i = 0; i < 16; i++) {
                int hi = Character.digit(clean.charAt(i * 2), 16);
                int lo = Character.digit(clean.charAt(i * 2 + 1), 16);
                iv[i] = (byte) (((hi >= 0 ? hi : 0) << 4) | (lo >= 0 ? lo : 0));
            }
            return iv;
        }
        long seq = Math.max(0L, mediaSequence);
        for (int i = 15; i >= 8; i--) {
            iv[i] = (byte) (seq & 0xFFL);
            seq >>>= 8;
        }
        return iv;
    }

    public static String inferSegmentExtension(String url, String defaultExt) {
        if (url == null || url.isEmpty()) return defaultExt;
        String clean = url;
        int q = clean.indexOf('?');
        if (q >= 0) clean = clean.substring(0, q);
        int hash = clean.indexOf('#');
        if (hash >= 0) clean = clean.substring(0, hash);
        String low = clean.toLowerCase(Locale.ROOT);
        String[] allowed = { ".m4s", ".mp4", ".m4a", ".aac", ".key", ".ts", ".m3u8", ".mp3", ".webm" };
        for (String ext : allowed) {
            if (low.endsWith(ext)) {
                return ext;
            }
        }
        return defaultExt;
    }

    public static boolean looksLikeHlsUrl(String url) {
        if (url == null) return false;
        String low = url.toLowerCase(Locale.ROOT);
        int q = low.indexOf('?');
        String path = q >= 0 ? low.substring(0, q) : low;
        return path.endsWith(".m3u8") || low.contains(".m3u8?");
    }

    public static boolean looksLikeSimplePlaylistUrl(String url) {
        if (url == null) return false;
        String low = url.toLowerCase(Locale.ROOT);
        int q = low.indexOf('?');
        String path = q >= 0 ? low.substring(0, q) : low;
        return path.endsWith(".pls") || path.endsWith(".m3u");
    }

    public static String normalizeAudioContentType(String upstreamCt, String url) {
        if (upstreamCt != null && !upstreamCt.trim().isEmpty()) {
            String ct = upstreamCt.trim();
            String low = ct.toLowerCase(Locale.ROOT);
            if (low.startsWith("audio/") || low.startsWith("video/") || low.contains("ogg")) {
                return ct;
            }
        }
        String lowUrl = url != null ? url.toLowerCase(Locale.ROOT) : "";
        if (lowUrl.contains(".aacp") || lowUrl.contains("/aac")) return "audio/aacp";
        if (lowUrl.contains(".aac")) return "audio/aac";
        if (lowUrl.contains(".ogg") || lowUrl.contains(".opus")) return "audio/ogg";
        return "audio/mpeg";
    }

    /**
     * Extracts the first stream URL from a .pls or .m3u playlist body.
     */
    public static String extractFirstUrlFromPlaylist(String body, String baseUrl) {
        if (body == null || body.trim().isEmpty()) return null;
        String[] lines = body.split("\\r?\\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher pls = PLS_FILE_PATTERN.matcher(line);
            if (pls.matches()) {
                String candidate = pls.group(1).trim();
                if (!candidate.isEmpty()) {
                    return resolveAbsoluteUrl(baseUrl, candidate);
                }
            }
        }
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) continue;
            if (line.contains("=") && !line.startsWith("http://") && !line.startsWith("https://")) continue;
            return resolveAbsoluteUrl(baseUrl, line);
        }
        return null;
    }

    /**
     * Selects the best variant URL from an HLS master playlist.
     */
    public static String selectMasterVariantUrl(String masterPlaylist, String baseUrl, boolean preferVideo) {
        return selectMasterVariantUrl(masterPlaylist, baseUrl, preferVideo, 0);
    }

    /**
     * Selects the best variant URL from an HLS master playlist respecting {@code maxBitrateKbps}.
     * When {@code preferVideo} is true and {@code maxBitrateKbps > 0 && maxBitrateKbps <= 600},
     * prefers <= 480p / low-bandwidth streams; otherwise prefers <= 720p in 600–2400 kbps.
     */
    public static String selectMasterVariantUrl(String masterPlaylist, String baseUrl,
                                                boolean preferVideo, int maxBitrateKbps) {
        if (masterPlaylist == null || !masterPlaylist.contains("#EXT-X-STREAM-INF")) {
            return baseUrl;
        }
        String[] lines = masterPlaylist.split("\\r?\\n");
        if (!preferVideo) {
            for (String raw : lines) {
                String line = raw.trim();
                if (line.startsWith("#EXT-X-MEDIA:") && line.contains("TYPE=AUDIO")) {
                    Matcher um = URI_ATTR_PATTERN.matcher(line);
                    if (um.find() && !um.group(1).trim().isEmpty()) {
                        return resolveAbsoluteUrl(baseUrl, um.group(1).trim());
                    }
                }
            }
        }

        List<Variant> variants = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue;
            long bw = -1;
            Matcher bm = BANDWIDTH_PATTERN.matcher(line);
            if (bm.find()) {
                try {
                    bw = Long.parseLong(bm.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
            int height = 0;
            Matcher rm = RESOLUTION_PATTERN.matcher(line);
            if (rm.find()) {
                try {
                    height = Integer.parseInt(rm.group(2));
                } catch (NumberFormatException ignored) {
                }
            }
            String codecs = "";
            Matcher cm = CODECS_PATTERN.matcher(line);
            if (cm.find()) {
                codecs = cm.group(1).toLowerCase(Locale.ROOT);
            }
            boolean audioOnly = height == 0
                    && !codecs.isEmpty()
                    && !codecs.contains("avc")
                    && !codecs.contains("hev")
                    && !codecs.contains("hvc")
                    && !codecs.contains("vp")
                    && !codecs.contains("av01");

            String uri = null;
            for (int j = i + 1; j < lines.length; j++) {
                String next = lines[j].trim();
                if (next.isEmpty() || next.startsWith("#")) continue;
                uri = next;
                break;
            }
            if (uri != null && !uri.isEmpty()) {
                variants.add(new Variant(resolveAbsoluteUrl(baseUrl, uri), bw, height, audioOnly));
            }
        }

        if (variants.isEmpty()) return baseUrl;

        if (!preferVideo) {
            Variant lowest = variants.get(0);
            for (Variant v : variants) {
                if (v.bandwidth > 0 && (lowest.bandwidth <= 0 || v.bandwidth < lowest.bandwidth)) {
                    lowest = v;
                }
            }
            return lowest.url;
        }

        // Video requested: filter out audio-only variants if any video variant exists
        List<Variant> videoVariants = new ArrayList<>();
        for (Variant v : variants) {
            if (!v.audioOnly) videoVariants.add(v);
        }
        List<Variant> pool = videoVariants.isEmpty() ? variants : videoVariants;

        Variant best = pool.get(0);
        long bestScore = scoreVideoVariant(best, maxBitrateKbps);
        for (int i = 1; i < pool.size(); i++) {
            Variant cand = pool.get(i);
            long sc = scoreVideoVariant(cand, maxBitrateKbps);
            if (sc < bestScore) {
                bestScore = sc;
                best = cand;
            }
        }
        return best.url;
    }

    private static long scoreVideoVariant(Variant v, int maxBitrateKbps) {
        long penalty = 0L;
        boolean lowProfile = maxBitrateKbps > 0 && maxBitrateKbps <= 600;
        int maxHeight = lowProfile ? 480 : 720;
        long minBw = lowProfile ? 250_000L : 600_000L;
        long maxBw = lowProfile ? 900_000L : 2_400_000L;
        long targetBw = lowProfile ? Math.max(350_000L, maxBitrateKbps * 1000L) : 1_200_000L;

        if (v.height > maxHeight) {
            penalty += 5_000_000L + (v.height - maxHeight) * 5_000L;
        }
        if (v.bandwidth > 0) {
            if (v.bandwidth >= minBw && v.bandwidth <= maxBw) {
                penalty += Math.abs(v.bandwidth - targetBw) / 10L;
            } else if (v.bandwidth < minBw) {
                penalty += 1_500_000L + (minBw - v.bandwidth);
            } else {
                penalty += 2_000_000L + (v.bandwidth - maxBw);
            }
        } else {
            penalty += 1_000_000L;
        }
        return penalty;
    }

    private static final class Variant {
        final String url;
        final long bandwidth;
        final int height;
        final boolean audioOnly;

        Variant(String url, long bandwidth, int height, boolean audioOnly) {
            this.url = url;
            this.bandwidth = bandwidth;
            this.height = height;
            this.audioOnly = audioOnly;
        }
    }

    private static final class FetchedText {
        final String finalUrl;
        final String body;

        FetchedText(String finalUrl, String body) {
            this.finalUrl = finalUrl;
            this.body = body;
        }
    }

    private static final class ResolvedPlaylist {
        final String mediaPlaylistUrl;
        final ParsedMediaPlaylist parsed;

        ResolvedPlaylist(String mediaPlaylistUrl, ParsedMediaPlaylist parsed) {
            this.mediaPlaylistUrl = mediaPlaylistUrl;
            this.parsed = parsed;
        }
    }

    private static final class MediaSegment {
        final String url;
        final long sequenceNumber;
        final String keyMethod;
        final String keyUri;
        final String keyIvHex;

        MediaSegment(String url, long sequenceNumber, String keyMethod, String keyUri, String keyIvHex) {
            this.url = url;
            this.sequenceNumber = sequenceNumber;
            this.keyMethod = keyMethod;
            this.keyUri = keyUri;
            this.keyIvHex = keyIvHex;
        }
    }

    private static final class ParsedMediaPlaylist {
        final List<MediaSegment> segments;
        final boolean hasFmp4Map;
        final boolean hasFmp4Segments;
        final boolean isVodEnded;
        final double targetDurationSec;

        ParsedMediaPlaylist(List<MediaSegment> segments, boolean hasFmp4Map, boolean hasFmp4Segments,
                            boolean isVodEnded, double targetDurationSec) {
            this.segments = segments;
            this.hasFmp4Map = hasFmp4Map;
            this.hasFmp4Segments = hasFmp4Segments;
            this.isVodEnded = isVodEnded;
            this.targetDurationSec = targetDurationSec;
        }
    }

    private static ParsedMediaPlaylist parseMediaPlaylist(String body, String baseUrl) {
        List<MediaSegment> segments = new ArrayList<>();
        boolean hasFmp4Map = false;
        boolean hasFmp4Segments = false;
        boolean isVodEnded = false;
        double targetDuration = 4.0;
        long mediaSequence = 0L;

        String currentKeyMethod = null;
        String currentKeyUri = null;
        String currentKeyIvHex = null;

        String[] lines = body.split("\\r?\\n");
        long segIdx = 0L;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                if (line.startsWith("#EXT-X-MAP:")) {
                    hasFmp4Map = true;
                } else if (line.startsWith("#EXT-X-ENDLIST")) {
                    isVodEnded = true;
                } else if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                    try {
                        double td = Double.parseDouble(line.substring("#EXT-X-TARGETDURATION:".length()).trim());
                        if (td > 0.5 && td <= 30.0) {
                            targetDuration = td;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    try {
                        mediaSequence = Long.parseLong(line.substring("#EXT-X-MEDIA-SEQUENCE:".length()).trim());
                    } catch (NumberFormatException ignored) {
                    }
                } else if (line.startsWith("#EXT-X-KEY:")) {
                    Matcher mm = KEY_METHOD_PATTERN.matcher(line);
                    String method = mm.find() ? mm.group(1).trim() : "NONE";
                    if ("NONE".equalsIgnoreCase(method)) {
                        currentKeyMethod = null;
                        currentKeyUri = null;
                        currentKeyIvHex = null;
                    } else {
                        currentKeyMethod = method;
                        Matcher um = URI_ATTR_PATTERN.matcher(line);
                        currentKeyUri = um.find() ? resolveAbsoluteUrl(baseUrl, um.group(1).trim()) : null;
                        Matcher ivm = KEY_IV_PATTERN.matcher(line);
                        currentKeyIvHex = ivm.find() ? ivm.group(1).trim() : null;
                    }
                }
            } else {
                String abs = resolveAbsoluteUrl(baseUrl, line);
                String ext = inferSegmentExtension(abs, ".ts");
                if (".m4s".equals(ext) || ".mp4".equals(ext)) {
                    hasFmp4Segments = true;
                }
                segments.add(new MediaSegment(abs, mediaSequence + segIdx, currentKeyMethod, currentKeyUri, currentKeyIvHex));
                segIdx++;
            }
        }
        return new ParsedMediaPlaylist(segments, hasFmp4Map, hasFmp4Segments, isVodEnded, targetDuration);
    }

    /**
     * Rewrites all media segment URIs, #EXT-X-KEY URI, #EXT-X-MAP URI, #EXT-X-MEDIA URI,
     * and nested .m3u8 variant URIs to local loopback relay URLs with proper file extensions
     * ({@code /seg/s_<idx>.ts}, {@code /seg/key.key}, {@code /seg/init.mp4}) so FFmpeg's
     * {@code hls.c} {@code allowed_extensions} validation always accepts them.
     */
    public static String rewriteHlsMediaPlaylist(String playlistBody, String upstreamBaseUrl,
                                                 String localBaseUrl, String referer, boolean preferVideo) {
        return rewriteHlsMediaPlaylist(playlistBody, upstreamBaseUrl, localBaseUrl, referer, null, preferVideo);
    }

    public static String rewriteHlsMediaPlaylist(String playlistBody, String upstreamBaseUrl,
                                                 String localBaseUrl, String referer,
                                                 String customUserAgent, boolean preferVideo) {
        if (playlistBody == null) return "";
        String encodedRef = (referer != null && !referer.trim().isEmpty())
                ? "&r=" + encodeBase64Url(referer.trim()) : "";
        String encodedUa = (customUserAgent != null && !customUserAgent.trim().isEmpty())
                ? "&ua=" + encodeBase64Url(customUserAgent.trim()) : "";
        String[] lines = playlistBody.split("\\r?\\n");
        StringBuilder out = new StringBuilder(playlistBody.length() + 512);
        int segIndex = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                out.append('\n');
                continue;
            }
            if (trimmed.startsWith("#")) {
                if ((trimmed.startsWith("#EXT-X-KEY:")
                        || trimmed.startsWith("#EXT-X-MAP:")
                        || trimmed.startsWith("#EXT-X-MEDIA:"))
                        && trimmed.contains("URI=\"")) {
                    boolean isMediaPlaylistTag = trimmed.startsWith("#EXT-X-MEDIA:");
                    boolean isKeyTag = trimmed.startsWith("#EXT-X-KEY:");
                    boolean isMapTag = trimmed.startsWith("#EXT-X-MAP:");
                    Matcher m = URI_ATTR_PATTERN.matcher(trimmed);
                    StringBuffer sb = new StringBuffer();
                    while (m.find()) {
                        String rawUri = m.group(1);
                        String abs = resolveAbsoluteUrl(upstreamBaseUrl, rawUri);
                        String proxied;
                        if (isMediaPlaylistTag || looksLikeHlsUrl(abs)) {
                            proxied = localBaseUrl + "/hls-proxy/sub.m3u8?u=" + encodeBase64Url(abs)
                                    + encodedRef + encodedUa + "&v=" + (preferVideo ? "1" : "0");
                        } else if (isKeyTag) {
                            String ext = inferSegmentExtension(abs, ".key");
                            proxied = localBaseUrl + "/seg/key" + ext + "?u=" + encodeBase64Url(abs)
                                    + encodedRef + encodedUa;
                        } else if (isMapTag) {
                            String ext = inferSegmentExtension(abs, ".mp4");
                            proxied = localBaseUrl + "/seg/init" + ext + "?u=" + encodeBase64Url(abs)
                                    + encodedRef + encodedUa;
                        } else {
                            String ext = inferSegmentExtension(abs, ".ts");
                            proxied = localBaseUrl + "/seg/s_" + (segIndex++) + ext + "?u=" + encodeBase64Url(abs)
                                    + encodedRef + encodedUa;
                        }
                        m.appendReplacement(sb, Matcher.quoteReplacement("URI=\"" + proxied + "\""));
                    }
                    m.appendTail(sb);
                    out.append(sb).append('\n');
                } else {
                    out.append(trimmed).append('\n');
                }
            } else {
                String abs = resolveAbsoluteUrl(upstreamBaseUrl, trimmed);
                if (looksLikeHlsUrl(abs)) {
                    out.append(localBaseUrl).append("/hls-proxy/sub.m3u8?u=").append(encodeBase64Url(abs))
                            .append(encodedRef).append(encodedUa).append("&v=").append(preferVideo ? "1" : "0").append('\n');
                } else {
                    String ext = inferSegmentExtension(abs, ".ts");
                    out.append(localBaseUrl).append("/seg/s_").append(segIndex++).append(ext)
                            .append("?u=").append(encodeBase64Url(abs))
                            .append(encodedRef).append(encodedUa).append('\n');
                }
            }
        }
        return out.toString();
    }

    public static String resolveAbsoluteUrl(String baseUrl, String relativeOrAbsolute) {
        if (relativeOrAbsolute == null) return "";
        String trimmed = relativeOrAbsolute.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        try {
            return new URL(new URL(baseUrl), trimmed).toString();
        } catch (Throwable ignored) {
            return trimmed;
        }
    }

    public static String encodeBase64Url(String text) {
        if (text == null || text.isEmpty()) return "";
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String decodeBase64Url(String encoded) {
        if (encoded == null || encoded.isEmpty()) return "";
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static Map<String, String> parseQueryParams(String query) {
        if (query == null || query.isEmpty()) return Collections.emptyMap();
        Map<String, String> map = new HashMap<>();
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            try {
                if (eq > 0) {
                    String k = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                    String v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    map.put(k, v);
                } else if (!pair.isEmpty()) {
                    map.put(URLDecoder.decode(pair, "UTF-8"), "");
                }
            } catch (Throwable ignored) {
            }
        }
        return map;
    }

    private static String readLimitedString(InputStream is, int maxBytes) throws IOException {
        return new String(readLimitedBytes(is, maxBytes), StandardCharsets.UTF_8);
    }

    private static byte[] readLimitedBytes(InputStream is, int maxBytes) throws IOException {
        try (InputStream in = is) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                baos.write(buf, 0, n);
                total += n;
                if (total >= maxBytes) break;
            }
            return baos.toByteArray();
        }
    }

    private static void writeHttpHeaders(OutputStream out, int code, String reason,
                                         String contentType, long contentLength) throws IOException {
        writeHttpHeaders(out, code, reason, contentType, contentLength, "no-cache");
    }

    private static void writeHttpHeaders(OutputStream out, int code, String reason,
                                         String contentType, long contentLength,
                                         String cacheControl) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        sb.append("Content-Type: ").append(contentType).append("\r\n");
        if (contentLength >= 0) {
            sb.append("Content-Length: ").append(contentLength).append("\r\n");
        }
        sb.append("Cache-Control: ").append(cacheControl != null ? cacheControl : "no-cache").append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static void sendSimpleError(OutputStream out, int code, String msg) {
        try {
            byte[] body = (msg != null ? msg : "Error").getBytes(StandardCharsets.UTF_8);
            writeHttpHeaders(out, code, msg != null ? msg : "Error", "text/plain; charset=utf-8", body.length);
            out.write(body);
            out.flush();
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuiet(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void logDebug(String msg) {
        try {
            Log.d(TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    private static void logWarn(String msg) {
        try {
            Log.w(TAG, msg);
        } catch (Throwable ignored) {
        }
    }
}
