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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight loopback-only HTTP relay server (127.0.0.1:<ephemeralPort>) that
 * bridges HTTPS/TLS 1.3 internet radio, Volnorez, and HLS (.m3u8) TV streams
 * into plain local HTTP for TeamTalk5's native FFmpeg (which includes
 * ff_http_protocol and ff_hls_demuxer, but not ff_https_protocol / TLS).
 *
 * Also rewrites HLS master/media playlists so every segment, key, init map, and
 * media rendition flows through loopback with clean retries and redirects.
 */
public final class LocalMediaRelayServer {

    private static final String TAG = "LocalMediaRelay";
    private static final int MAX_REDIRECTS = 8;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 18_000;
    private static final int MAX_PLAYLIST_BYTES = 2 * 1024 * 1024;
    private static final int MAX_REGISTERED_ENTRIES = 64;

    private static final Pattern URI_ATTR_PATTERN = Pattern.compile("URI=\"([^\"]+)\"");
    private static final Pattern BANDWIDTH_PATTERN = Pattern.compile("BANDWIDTH=(\\d+)");
    private static final Pattern RESOLUTION_PATTERN = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)");
    private static final Pattern CODECS_PATTERN = Pattern.compile("CODECS=\"([^\"]+)\"");
    private static final Pattern PLS_FILE_PATTERN = Pattern.compile("^File\\d+\\s*=\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    public static final class StreamEntry {
        public final String id;
        public final String url;
        public final String referer;
        public final boolean preferVideo;

        public StreamEntry(String id, String url, String referer, boolean preferVideo) {
            this.id = id;
            this.url = url;
            this.referer = referer;
            this.preferVideo = preferVideo;
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
            this.workerPool = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "Extro-MediaRelayWorker");
                t.setDaemon(true);
                return t;
            });
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
        entries.put(id, new StreamEntry(id, upstreamUrl, referer, false));
        if (looksHls) {
            return "http://127.0.0.1:" + p + "/hls/" + id + ".m3u8";
        }
        return "http://127.0.0.1:" + p + "/live/" + id;
    }

    public String registerHls(String upstreamUrl, String referer, boolean preferVideo) throws IOException {
        int p = ensureStarted();
        trimRegistryIfNeeded();
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        entries.put(id, new StreamEntry(id, upstreamUrl, referer, preferVideo));
        return "http://127.0.0.1:" + p + "/hls/" + id + ".m3u8";
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
                    pool.execute(() -> handleClient(client));
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
            if (path.startsWith("/live/")) {
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
                serveHlsPlaylist(entry.url, entry.referer, entry.preferVideo, headOnly, out);
            } else if ("/hls-proxy".equals(path)) {
                Map<String, String> params = parseQueryParams(query);
                String u = decodeBase64Url(params.get("u"));
                String r = decodeBase64Url(params.get("r"));
                boolean preferVideo = !"0".equals(params.get("v"));
                if (u.isEmpty()) {
                    sendSimpleError(out, 400, "Missing u");
                    return;
                }
                serveHlsPlaylist(u, r, preferVideo, headOnly, out);
            } else if ("/seg".equals(path)) {
                Map<String, String> params = parseQueryParams(query);
                String u = decodeBase64Url(params.get("u"));
                String r = decodeBase64Url(params.get("r"));
                if (u.isEmpty()) {
                    sendSimpleError(out, 400, "Missing u");
                    return;
                }
                serveSegmentWithRetry(u, r, rangeHeader, headOnly, out);
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

    private void serveLiveStream(StreamEntry entry, boolean headOnly, String rangeHeader, OutputStream out) throws IOException {
        String resolvedUrl = entry.url;
        // If URL is a playlist (.pls / .m3u), resolve the first actual stream URL
        if (looksLikeSimplePlaylistUrl(resolvedUrl)) {
            String extracted = fetchAndExtractPlaylistUrl(resolvedUrl, entry.referer);
            if (extracted != null && !extracted.isEmpty()) {
                resolvedUrl = extracted;
            }
        }
        if (looksLikeHlsUrl(resolvedUrl)) {
            serveHlsPlaylist(resolvedUrl, entry.referer, entry.preferVideo, headOnly, out);
            return;
        }

        HttpURLConnection conn = openFollowingRedirects(resolvedUrl, entry.referer, rangeHeader, false);
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
                    serveHlsBody(finalUrl, entry.referer, entry.preferVideo, body, false, out);
                    return;
                }
                String first = extractFirstUrlFromPlaylist(body, finalUrl);
                if (first != null && !first.isEmpty() && !first.equals(finalUrl)) {
                    if (looksLikeHlsUrl(first)) {
                        serveHlsPlaylist(first, entry.referer, entry.preferVideo, false, out);
                        return;
                    }
                    conn = openFollowingRedirects(first, entry.referer, rangeHeader, false);
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

    private void serveHlsPlaylist(String playlistUrl, String referer, boolean preferVideo,
                                  boolean headOnly, OutputStream out) throws IOException {
        HttpURLConnection conn = openFollowingRedirects(playlistUrl, referer, null, false);
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
        serveHlsBody(finalUrl, referer, preferVideo, body, headOnly, out);
    }

    private void serveHlsBody(String finalUrl, String referer, boolean preferVideo,
                              String body, boolean headOnly, OutputStream out) throws IOException {
        if (body == null || body.trim().isEmpty()) {
            sendSimpleError(out, 502, "Empty HLS playlist");
            return;
        }
        String currentUrl = finalUrl;
        String currentBody = body;

        // If this is a master playlist (#EXT-X-STREAM-INF), select optimal variant & fetch media playlist
        if (currentBody.contains("#EXT-X-STREAM-INF")) {
            String variantUrl = selectMasterVariantUrl(currentBody, currentUrl, preferVideo);
            if (variantUrl != null && !variantUrl.isEmpty() && !variantUrl.equals(currentUrl)) {
                HttpURLConnection vConn = openFollowingRedirects(variantUrl, referer, null, false);
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
        String rewritten = rewriteHlsMediaPlaylist(currentBody, currentUrl, baseLocal, referer, preferVideo);
        byte[] bytes = rewritten.getBytes(StandardCharsets.UTF_8);
        writeHttpHeaders(out, 200, "OK", "application/vnd.apple.mpegurl; charset=utf-8", bytes.length);
        if (!headOnly) {
            out.write(bytes);
            out.flush();
        }
    }

    private void serveSegmentWithRetry(String segUrl, String referer, String rangeHeader,
                                       boolean headOnly, OutputStream out) throws IOException {
        IOException lastErr = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = openFollowingRedirects(segUrl, referer, rangeHeader, false);
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

    private String fetchAndExtractPlaylistUrl(String playlistUrl, String referer) {
        HttpURLConnection conn = null;
        try {
            conn = openFollowingRedirects(playlistUrl, referer, null, false);
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

    private HttpURLConnection openFollowingRedirects(String urlStr, String referer,
                                                     String rangeHeader, boolean wantIcy) throws IOException {
        String current = urlStr;
        for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", BotMusicSearch.USER_AGENT);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Connection", "close");
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

    // --- Pure, unit-testable playlist helpers ---

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
     * When {@code preferVideo} is true: avoids audio-only variants and prefers
     * smooth mobile <= 720p streams in the 600–2200 kbps range (falling back to
     * closest available video variant).
     * When {@code preferVideo} is false: prefers dedicated AUDIO rendition or lowest bandwidth.
     */
    public static String selectMasterVariantUrl(String masterPlaylist, String baseUrl, boolean preferVideo) {
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

        // Score variants: prefer height <= 720p (or unspecified) and bandwidth in 600k..2200k (ideal ~1200k)
        Variant best = pool.get(0);
        long bestScore = scoreVideoVariant(best);
        for (int i = 1; i < pool.size(); i++) {
            Variant cand = pool.get(i);
            long sc = scoreVideoVariant(cand);
            if (sc < bestScore) {
                bestScore = sc;
                best = cand;
            }
        }
        return best.url;
    }

    private static long scoreVideoVariant(Variant v) {
        long penalty = 0L;
        if (v.height > 720) {
            penalty += 5_000_000L + (v.height - 720L) * 5_000L;
        }
        if (v.bandwidth > 0) {
            if (v.bandwidth >= 600_000L && v.bandwidth <= 2_200_000L) {
                // Sweet spot around 1.2 Mbps
                penalty += Math.abs(v.bandwidth - 1_200_000L) / 10L;
            } else if (v.bandwidth < 600_000L) {
                penalty += 1_500_000L + (600_000L - v.bandwidth);
            } else {
                penalty += 2_000_000L + (v.bandwidth - 2_200_000L);
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

    /**
     * Rewrites all media segment URIs, #EXT-X-KEY URI, #EXT-X-MAP URI, #EXT-X-MEDIA URI,
     * and nested .m3u8 variant URIs to local loopback relay URLs.
     */
    public static String rewriteHlsMediaPlaylist(String playlistBody, String upstreamBaseUrl,
                                                 String localBaseUrl, String referer, boolean preferVideo) {
        if (playlistBody == null) return "";
        String encodedRef = (referer != null && !referer.trim().isEmpty())
                ? "&r=" + encodeBase64Url(referer.trim()) : "";
        String[] lines = playlistBody.split("\\r?\\n");
        StringBuilder out = new StringBuilder(playlistBody.length() + 512);
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
                    Matcher m = URI_ATTR_PATTERN.matcher(trimmed);
                    StringBuffer sb = new StringBuffer();
                    while (m.find()) {
                        String rawUri = m.group(1);
                        String abs = resolveAbsoluteUrl(upstreamBaseUrl, rawUri);
                        String proxied;
                        if (isMediaPlaylistTag || looksLikeHlsUrl(abs)) {
                            proxied = localBaseUrl + "/hls-proxy?u=" + encodeBase64Url(abs)
                                    + encodedRef + "&v=" + (preferVideo ? "1" : "0");
                        } else {
                            proxied = localBaseUrl + "/seg?u=" + encodeBase64Url(abs) + encodedRef;
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
                    out.append(localBaseUrl).append("/hls-proxy?u=").append(encodeBase64Url(abs))
                            .append(encodedRef).append("&v=").append(preferVideo ? "1" : "0").append('\n');
                } else {
                    out.append(localBaseUrl).append("/seg?u=").append(encodeBase64Url(abs))
                            .append(encodedRef).append('\n');
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
        try (InputStream in = is) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                baos.write(buf, 0, n);
                total += n;
                if (total >= maxBytes) break;
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeHttpHeaders(OutputStream out, int code, String reason,
                                         String contentType, long contentLength) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        sb.append("Content-Type: ").append(contentType).append("\r\n");
        if (contentLength >= 0) {
            sb.append("Content-Length: ").append(contentLength).append("\r\n");
        }
        sb.append("Cache-Control: no-cache\r\n");
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
