package org.teamtalkextro.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java music & radio search engine matching Daket52i/TTMediaBot-by-dancho.
 *
 * Supported services:
 * - "hitmo": rus.hitmotop.com (data-musmeta JSON) + rus.hitmos.fm fallback
 * - "mf":    muzofond.su (data-file / data-artist / data-track-title) + hitmo fallback
 * - "rt":    rutube.ru API search + play options HLS resolver
 * - "radio": 16 curated Russian FM stations + radio-browser.info live search
 * - Direct HTTP(S) URLs and local Android URIs
 */
public final class BotMusicSearch {

    public static final String SERVICE_HITMO = "hitmo";
    public static final String SERVICE_MF = "mf";
    public static final String SERVICE_RUTUBE = "rt";
    public static final String SERVICE_RADIO = "radio";

    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 12000;
    private static final int MAX_RESULTS = 15;

    private static final Pattern HITMO_MUSMETA_PATTERN =
            Pattern.compile("data-musmeta\\s*=\\s*(['\"])(.*?)\\1", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final Pattern HITMOS_NEXT_PATTERN =
            Pattern.compile("\\\"url\\\":\\\"(https?://[^\\\"]+\\.mp3[^\\\"]*)\\\".*?\\\"title\\\":\\\"([^\\\"]+)\\\".*?\\\"artist\\\":\\\"([^\\\"]+)\\\"",
                    Pattern.DOTALL);

    private static final Pattern MF_ITEM_PATTERN =
            Pattern.compile("<li[^>]*class\\s*=\\s*['\"][^'\"]*item[^'\"]*['\"][^>]*>(.*?)</li>",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern MF_DATA_FILE =
            Pattern.compile("data-file\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern MF_DATA_ARTIST =
            Pattern.compile("data-artist\\s*=\\s*['\"]([^'\"]*)['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern MF_DATA_TITLE =
            Pattern.compile("data-track-title\\s*=\\s*['\"]([^'\"]*)['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern MF_PLAY_LI =
            Pattern.compile("data-url\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);

    private static final Pattern RUTUBE_URL_ID =
            Pattern.compile("rutube\\.ru/(?:video|play/embed|shorts)/([a-fA-F0-9]{16,40})");

    public static final class BotTrack {
        public final String title;
        public final String artist;
        public final String url;
        public final String service;
        public final boolean isLiveStream;
        public final String localUri;
        public final String referer;

        public BotTrack(String title, String artist, String url, String service,
                        boolean isLiveStream, String localUri, String referer) {
            this.title = title != null ? title.trim() : "";
            this.artist = artist != null ? artist.trim() : "";
            this.url = url != null ? url.trim() : "";
            this.service = service != null ? service : "";
            this.isLiveStream = isLiveStream;
            this.localUri = localUri;
            this.referer = referer;
        }

        public String getDisplayName() {
            if (!artist.isEmpty() && !title.isEmpty()) {
                return artist + " — " + title;
            }
            if (!title.isEmpty()) {
                return title;
            }
            if (!artist.isEmpty()) {
                return artist;
            }
            return url;
        }
    }

    public static final List<BotTrack> BUILT_IN_RADIO;

    static {
        List<BotTrack> stations = new ArrayList<>();
        stations.add(radio("Европа Плюс", "https://ep128.hostingradio.ru:8030/ep128"));
        stations.add(radio("Русское Радио", "https://rusradio.hostingradio.ru/rusradio96.aacp"));
        stations.add(radio("Дорожное Радио", "https://dorognoe.hostingradio.ru/radio"));
        stations.add(radio("Авторадио", "https://pub0302.101.ru:8443/stream/air/aac/64/100"));
        stations.add(radio("Ретро FM", "https://retro.hostingradio.ru:8043/retro128"));
        stations.add(radio("Радио Рекорд", "https://radiorecord.hostingradio.ru/rr_main96.aacp"));
        stations.add(radio("Радио Шансон", "https://chanson.hostingradio.ru:8041/chanson128.mp3"));
        stations.add(radio("DFM", "https://dfm.hostingradio.ru/dfm96.aacp"));
        stations.add(radio("Наше Радио", "https://nashe1.hostingradio.ru/nashe-128.mp3"));
        stations.add(radio("Хит FM", "https://hitfm.hostingradio.ru/hitfm96.aacp"));
        stations.add(radio("Юмор FM", "https://pub0302.101.ru:8443/stream/air/aac/64/102"));
        stations.add(radio("Love Radio", "https://stream.loveradio.ru/12_love_28"));
        stations.add(radio("Радио Дача", "https://stream.radiodacha.ru/14_dacha_28"));
        stations.add(radio("Maximum", "https://maximum.hostingradio.ru/maximum96.aacp"));
        stations.add(radio("Energy (NRJ)", "https://pub0302.101.ru:8443/stream/air/aac/64/99"));
        stations.add(radio("Like FM", "https://pub0302.101.ru:8443/stream/air/aac/64/219"));
        BUILT_IN_RADIO = Collections.unmodifiableList(stations);
    }

    private BotMusicSearch() {
    }

    private static BotTrack radio(String name, String streamUrl) {
        return new BotTrack(name, "Радио", streamUrl, SERVICE_RADIO, true, null, null);
    }

    public static String normalizeService(String svc) {
        if (svc == null) return SERVICE_HITMO;
        String s = svc.trim().toLowerCase(Locale.ROOT);
        if (s.equals("mf") || s.equals("muzofond") || s.equals("музфонд")) return SERVICE_MF;
        if (s.equals("rt") || s.equals("rutube") || s.equals("рутуб")) return SERVICE_RUTUBE;
        if (s.equals("radio") || s.equals("r") || s.equals("радио")) return SERVICE_RADIO;
        return SERVICE_HITMO;
    }

    public static String getServiceLabel(String svc) {
        String s = normalizeService(svc);
        switch (s) {
            case SERVICE_MF: return "Музфонд (mf)";
            case SERVICE_RUTUBE: return "Rutube (rt)";
            case SERVICE_RADIO: return "Радио (radio)";
            default: return "Хитмо (hitmo)";
        }
    }

    public static boolean isDirectUrl(String query) {
        if (query == null) return false;
        String q = query.trim().toLowerCase(Locale.ROOT);
        return q.startsWith("http://") || q.startsWith("https://");
    }

    public static List<BotTrack> search(String service, String query) throws IOException {
        String q = query != null ? query.trim() : "";
        if (isDirectUrl(q)) {
            Matcher rtMatcher = RUTUBE_URL_ID.matcher(q);
            if (rtMatcher.find()) {
                String vid = rtMatcher.group(1);
                BotTrack resolved = resolveRutubeTrack(vid, "Rutube " + vid, "Rutube");
                if (resolved != null) {
                    return Collections.singletonList(resolved);
                }
                throw new IOException("Rutube stream not found for video " + vid);
            }
            boolean live = q.toLowerCase(Locale.ROOT).contains(".m3u8")
                    || q.toLowerCase(Locale.ROOT).contains("stream")
                    || q.toLowerCase(Locale.ROOT).contains("radio")
                    || q.toLowerCase(Locale.ROOT).contains(".aacp")
                    || q.toLowerCase(Locale.ROOT).contains(".pls");
            String title = extractNameFromUrl(q);
            return Collections.singletonList(new BotTrack(title, "URL", q, "url", live, null, null));
        }

        String svc = normalizeService(service);
        switch (svc) {
            case SERVICE_MF:
                return searchMuzofond(q);
            case SERVICE_RUTUBE:
                return searchRutube(q);
            case SERVICE_RADIO:
                return searchRadio(q);
            case SERVICE_HITMO:
            default:
                return searchHitmo(q);
        }
    }

    public static List<BotTrack> searchHitmo(String query) throws IOException {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String enc = URLEncoder.encode(query.trim(), "UTF-8");
        List<BotTrack> results = new ArrayList<>();
        try {
            String url = "https://rus.hitmotop.com/search?q=" + enc;
            String html = httpGet(url, "https://rus.hitmotop.com/", null);
            parseHitmotopHtml(html, results);
        } catch (IOException e) {
            // Try fallback below
        }

        if (results.isEmpty()) {
            try {
                String fallbackUrl = "https://rus.hitmos.fm/search?q=" + enc;
                String html = httpGet(fallbackUrl, "https://rus.hitmos.fm/", null);
                parseHitmotopHtml(html, results);
                if (results.isEmpty()) {
                    parseHitmosNextHtml(html, results);
                }
            } catch (IOException ignored) {
            }
        }
        return results;
    }

    private static void parseHitmotopHtml(String html, List<BotTrack> out) {
        if (html == null || html.isEmpty()) return;
        Set<String> seenUrls = new LinkedHashSet<>();
        Matcher m = HITMO_MUSMETA_PATTERN.matcher(html);
        while (m.find() && out.size() < MAX_RESULTS) {
            String raw = unescapeHtml(m.group(2));
            try {
                JsonElement el = JsonParser.parseString(raw);
                if (el == null || !el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                String url = optString(obj, "url", "").trim();
                if (url.isEmpty()) continue;
                if (url.startsWith("//")) url = "https:" + url;
                if (!url.startsWith("http://") && !url.startsWith("https://")) continue;
                if (!seenUrls.add(url)) continue;

                String title = cleanText(optString(obj, "title", ""));
                String artist = cleanText(optString(obj, "artist", ""));
                if (title.isEmpty() && artist.isEmpty()) continue;
                out.add(new BotTrack(title, artist, url, SERVICE_HITMO, false, null, "https://rus.hitmotop.com/"));
            } catch (Throwable ignored) {
            }
        }
    }

    private static void parseHitmosNextHtml(String html, List<BotTrack> out) {
        if (html == null || html.isEmpty()) return;
        String unescaped = html.replace("\\\"", "\"").replace("\\/", "/");
        Set<String> seenUrls = new LinkedHashSet<>();
        Matcher m = HITMOS_NEXT_PATTERN.matcher(unescaped);
        while (m.find() && out.size() < MAX_RESULTS) {
            String url = m.group(1).trim();
            if (!seenUrls.add(url)) continue;
            String title = cleanText(m.group(2));
            String artist = cleanText(m.group(3));
            out.add(new BotTrack(title, artist, url, SERVICE_HITMO, false, null, "https://rus.hitmos.fm/"));
        }
    }

    public static List<BotTrack> searchMuzofond(String query) throws IOException {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String enc = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20");
        List<BotTrack> results = new ArrayList<>();
        try {
            String html = httpGet("https://muzofond.su/search/" + enc, "https://muzofond.su/", null);
            Set<String> seen = new LinkedHashSet<>();
            Matcher itemMatcher = MF_ITEM_PATTERN.matcher(html);
            while (itemMatcher.find() && results.size() < MAX_RESULTS) {
                String block = itemMatcher.group(1);
                String fileUrl = findFirstGroup(MF_DATA_FILE, block);
                if (fileUrl.isEmpty()) {
                    fileUrl = findFirstGroup(MF_PLAY_LI, block);
                }
                if (fileUrl.isEmpty()) continue;
                if (fileUrl.startsWith("//")) fileUrl = "https:" + fileUrl;
                if (!fileUrl.startsWith("http://") && !fileUrl.startsWith("https://")) continue;
                if (!seen.add(fileUrl)) continue;

                String artist = cleanText(unescapeHtml(findFirstGroup(MF_DATA_ARTIST, block)));
                String title = cleanText(unescapeHtml(findFirstGroup(MF_DATA_TITLE, block)));
                if (title.isEmpty() && artist.isEmpty()) {
                    title = extractNameFromUrl(fileUrl);
                }
                results.add(new BotTrack(title, artist, fileUrl, SERVICE_MF, false, null, "https://muzofond.su/"));
            }
            if (results.isEmpty()) {
                Matcher fm = MF_DATA_FILE.matcher(html);
                while (fm.find() && results.size() < MAX_RESULTS) {
                    String fileUrl = fm.group(1).trim();
                    if (fileUrl.startsWith("//")) fileUrl = "https:" + fileUrl;
                    if (!fileUrl.startsWith("http://") && !fileUrl.startsWith("https://")) continue;
                    if (!seen.add(fileUrl)) continue;
                    int ctxStart = Math.max(0, fm.start() - 300);
                    int ctxEnd = Math.min(html.length(), fm.end() + 500);
                    String slice = html.substring(ctxStart, ctxEnd);
                    String artist = cleanText(unescapeHtml(findFirstGroup(MF_DATA_ARTIST, slice)));
                    String title = cleanText(unescapeHtml(findFirstGroup(MF_DATA_TITLE, slice)));
                    if (title.isEmpty()) title = extractNameFromUrl(fileUrl);
                    results.add(new BotTrack(title, artist, fileUrl, SERVICE_MF, false, null, "https://muzofond.su/"));
                }
            }
        } catch (IOException ignored) {
            // muzofond.su may return 403/Cloudflare — fallback to hitmo below
        }

        if (results.isEmpty()) {
            return searchHitmo(query);
        }
        return results;
    }

    public static List<BotTrack> searchRutube(String query) throws IOException {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String enc = URLEncoder.encode(query.trim(), "UTF-8");
        String apiUrl = "https://rutube.ru/api/search/video/?query=" + enc + "&limit=10";
        String body = httpGet(apiUrl, "https://rutube.ru/", "application/json");
        List<BotTrack> results = new ArrayList<>();
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed == null || !parsed.isJsonObject()) return results;
            JsonObject root = parsed.getAsJsonObject();
            JsonArray arr = root.has("results") && root.get("results").isJsonArray()
                    ? root.getAsJsonArray("results") : null;
            if (arr != null) {
                for (int i = 0; i < arr.size() && results.size() < MAX_RESULTS; i++) {
                    JsonElement itemEl = arr.get(i);
                    if (itemEl == null || !itemEl.isJsonObject()) continue;
                    JsonObject item = itemEl.getAsJsonObject();
                    String vid = optString(item, "id", "").trim();
                    if (vid.isEmpty()) continue;
                    String title = cleanText(optString(item, "title", "Rutube video"));
                    String author = "";
                    if (item.has("author") && item.get("author").isJsonObject()) {
                        author = cleanText(optString(item.getAsJsonObject("author"), "name", ""));
                    }
                    results.add(new BotTrack(title, author, "rutube://" + vid, SERVICE_RUTUBE, true, null, "https://rutube.ru/"));
                }
            }
        } catch (Throwable t) {
            throw new IOException("Rutube JSON parse error: " + t.getMessage(), t);
        }
        return results;
    }

    public static BotTrack resolveTrackForPlayback(BotTrack track) throws IOException {
        if (track == null) return null;
        if (track.url != null && track.url.startsWith("rutube://")) {
            String vid = track.url.substring("rutube://".length()).trim();
            BotTrack resolved = resolveRutubeTrack(vid, track.title, track.artist);
            if (resolved == null) {
                throw new IOException("Rutube video_balancer.m3u8 unavailable for " + vid);
            }
            return resolved;
        }
        return track;
    }

    public static BotTrack resolveRutubeTrack(String videoId, String fallbackTitle, String fallbackArtist) throws IOException {
        String optsUrl = "https://rutube.ru/api/play/options/" + videoId + "/?format=json";
        String body = httpGet(optsUrl, "https://rutube.ru/", "application/json");
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed == null || !parsed.isJsonObject()) return null;
            JsonObject obj = parsed.getAsJsonObject();
            String title = cleanText(optString(obj, "title", fallbackTitle));
            if (title.isEmpty()) title = fallbackTitle;
            String author = fallbackArtist;
            if (obj.has("author") && obj.get("author").isJsonObject()) {
                String aName = cleanText(optString(obj.getAsJsonObject("author"), "name", ""));
                if (!aName.isEmpty()) author = aName;
            }
            String m3u8 = "";
            if (obj.has("video_balancer") && obj.get("video_balancer").isJsonObject()) {
                m3u8 = optString(obj.getAsJsonObject("video_balancer"), "m3u8", "").trim();
            }
            if (m3u8.isEmpty() && obj.has("live_streams") && obj.get("live_streams").isJsonObject()) {
                JsonObject liveStreams = obj.getAsJsonObject("live_streams");
                if (liveStreams.has("hls") && liveStreams.get("hls").isJsonArray()) {
                    JsonArray hls = liveStreams.getAsJsonArray("hls");
                    if (hls.size() > 0 && hls.get(0).isJsonObject()) {
                        m3u8 = optString(hls.get(0).getAsJsonObject(), "url", "").trim();
                    }
                }
            }
            if (m3u8.isEmpty()) {
                return null;
            }
            return new BotTrack(title, author, m3u8, SERVICE_RUTUBE, true, null, "https://rutube.ru/");
        } catch (Throwable t) {
            throw new IOException("Rutube play options parse error: " + t.getMessage(), t);
        }
    }

    public static List<BotTrack> searchRadio(String query) throws IOException {
        String q = query != null ? query.trim() : "";
        if (q.isEmpty()) {
            return new ArrayList<>(BUILT_IN_RADIO);
        }

        try {
            int idx = Integer.parseInt(q);
            if (idx >= 1 && idx <= BUILT_IN_RADIO.size()) {
                return Collections.singletonList(BUILT_IN_RADIO.get(idx - 1));
            }
        } catch (NumberFormatException ignored) {
        }

        List<BotTrack> results = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        String low = q.toLowerCase(Locale.ROOT);
        for (BotTrack st : BUILT_IN_RADIO) {
            if (st.title.toLowerCase(Locale.ROOT).contains(low)) {
                results.add(st);
                seenUrls.add(st.url);
            }
        }

        String enc = URLEncoder.encode(q, "UTF-8");
        String apiUrl = "https://de1.api.radio-browser.info/json/stations/search?name="
                + enc + "&limit=15&order=clickcount&reverse=true";
        try {
            String body = httpGet(apiUrl, null, "application/json");
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed != null && parsed.isJsonArray()) {
                JsonArray arr = parsed.getAsJsonArray();
                for (int i = 0; i < arr.size() && results.size() < MAX_RESULTS; i++) {
                    JsonElement itemEl = arr.get(i);
                    if (itemEl == null || !itemEl.isJsonObject()) continue;
                    JsonObject item = itemEl.getAsJsonObject();
                    String streamUrl = optString(item, "url_resolved", "").trim();
                    if (streamUrl.isEmpty()) {
                        streamUrl = optString(item, "url", "").trim();
                    }
                    if (streamUrl.isEmpty() || !seenUrls.add(streamUrl)) continue;
                    String name = cleanText(optString(item, "name", "Radio"));
                    String country = cleanText(optString(item, "country", ""));
                    String artist = country.isEmpty() ? "Радио" : ("Радио • " + country);
                    results.add(new BotTrack(name, artist, streamUrl, SERVICE_RADIO, true, null, null));
                }
            }
        } catch (Throwable t) {
            if (results.isEmpty()) {
                throw new IOException("Radio search failed: " + t.getMessage(), t);
            }
        }
        return results;
    }

    public static String httpGet(String urlStr, String referer, String accept) throws IOException {
        String current = urlStr;
        for (int hops = 0; hops < 5; hops++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
            if (referer != null && !referer.isEmpty()) {
                conn.setRequestProperty("Referer", referer);
            }
            if (accept != null && !accept.isEmpty()) {
                conn.setRequestProperty("Accept", accept);
            }
            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) {
                    throw new IOException("Redirect without Location header");
                }
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            if (code < 200 || code >= 300) {
                conn.disconnect();
                throw new IOException("HTTP " + code + " for " + current);
            }
            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                int total = 0;
                while ((r = is.read(buf)) > 0) {
                    baos.write(buf, 0, r);
                    total += r;
                    if (total > 4 * 1024 * 1024) break;
                }
                return new String(baos.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                conn.disconnect();
            }
        }
        throw new IOException("Too many redirects for " + urlStr);
    }

    private static String optString(JsonObject obj, String key, String def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return def;
        try {
            return obj.get(key).getAsString();
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static String findFirstGroup(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() && m.group(1) != null ? m.group(1).trim() : "";
    }

    private static String extractNameFromUrl(String url) {
        try {
            String path = new URL(url).getPath();
            if (path != null && !path.isEmpty()) {
                int slash = path.lastIndexOf('/');
                String seg = slash >= 0 ? path.substring(slash + 1) : path;
                if (!seg.isEmpty()) {
                    return java.net.URLDecoder.decode(seg, "UTF-8");
                }
            }
        } catch (Throwable ignored) {
        }
        return url;
    }

    private static String unescapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&#039;", "'")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("\\/", "/");
    }

    private static String cleanText(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }
}
