package org.teamtalkextro.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java REST client for Yandex Music API (https://api.music.yandex.net).
 * Supports OAuth token verification, track search, direct MP3 download URL
 * signing (MD5 salt), URL resolution (track / album / playlist), charts, and
 * user liked tracks.
 */
public final class YandexMusicClient {

    public static final String API_BASE = "https://api.music.yandex.net";
    public static final String CLIENT_HEADER = "YandexMusicAndroid/24023621";
    public static final String DOWNLOAD_SIGN_SALT = "XGRlBW9FXlekgbPrRHuSiA";
    public static final String YM_TRACK_PREFIX = "ymtrack:";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private static final Pattern TRACK_URL_PATTERN =
            Pattern.compile("music\\.yandex\\.[^/\\s]+/(?:album/\\d+/)?track/(\\d+)");
    private static final Pattern ALBUM_URL_PATTERN =
            Pattern.compile("music\\.yandex\\.[^/\\s]+/album/(\\d+)(?:[/?#]|$)");
    private static final Pattern USER_PLAYLIST_URL_PATTERN =
            Pattern.compile("music\\.yandex\\.[^/\\s]+/users/([^/?#]+)/playlists/(\\d+)");
    private static final Pattern SHORT_PLAYLIST_URL_PATTERN =
            Pattern.compile("music\\.yandex\\.[^/\\s]+/playlists/([^/?#]+)");

    private static final Pattern XML_HOST = Pattern.compile("<host>([^<]+)</host>");
    private static final Pattern XML_PATH = Pattern.compile("<path>([^<]+)</path>");
    private static final Pattern XML_TS = Pattern.compile("<ts>([^<]+)</ts>");
    private static final Pattern XML_S = Pattern.compile("<s>([^<]+)</s>");

    public static final class AccountStatus {
        public final long uid;
        public final String login;
        public final String displayName;
        public final boolean hasPlus;

        public AccountStatus(long uid, String login, String displayName, boolean hasPlus) {
            this.uid = uid;
            this.login = login != null ? login : "";
            this.displayName = displayName != null ? displayName : "";
            this.hasPlus = hasPlus;
        }

        public String formatSummary() {
            String name = !displayName.isEmpty() ? displayName : (!login.isEmpty() ? login : ("uid=" + uid));
            return name + (hasPlus ? " (Plus)" : " (без Plus)");
        }
    }

    public static final class DownloadTarget {
        public final String host;
        public final String path;
        public final String ts;
        public final String s;

        public DownloadTarget(String host, String path, String ts, String s) {
            this.host = host != null ? host.trim() : "";
            this.path = path != null ? path.trim() : "";
            this.ts = ts != null ? ts.trim() : "";
            this.s = s != null ? s.trim() : "";
        }

        public boolean isValid() {
            return !host.isEmpty() && !path.isEmpty() && !ts.isEmpty() && !s.isEmpty();
        }
    }

    public static final class ParsedYandexUrl {
        public enum Kind { TRACK, ALBUM, USER_PLAYLIST, SHORT_PLAYLIST }
        public final Kind kind;
        public final String primaryId;
        public final String secondaryId;

        public ParsedYandexUrl(Kind kind, String primaryId, String secondaryId) {
            this.kind = kind;
            this.primaryId = primaryId;
            this.secondaryId = secondaryId;
        }
    }

    private YandexMusicClient() {
    }

    public static boolean isYandexMusicUrl(String url) {
        return parseYandexUrl(url) != null;
    }

    public static ParsedYandexUrl parseYandexUrl(String url) {
        if (url == null || url.trim().isEmpty()) return null;
        String u = url.trim();
        if (u.startsWith(YM_TRACK_PREFIX)) {
            String id = u.substring(YM_TRACK_PREFIX.length()).trim();
            if (!id.isEmpty()) {
                return new ParsedYandexUrl(ParsedYandexUrl.Kind.TRACK, id, null);
            }
        }
        Matcher tm = TRACK_URL_PATTERN.matcher(u);
        if (tm.find()) {
            return new ParsedYandexUrl(ParsedYandexUrl.Kind.TRACK, tm.group(1), null);
        }
        Matcher upm = USER_PLAYLIST_URL_PATTERN.matcher(u);
        if (upm.find()) {
            return new ParsedYandexUrl(ParsedYandexUrl.Kind.USER_PLAYLIST, upm.group(1), upm.group(2));
        }
        Matcher spm = SHORT_PLAYLIST_URL_PATTERN.matcher(u);
        if (spm.find()) {
            return new ParsedYandexUrl(ParsedYandexUrl.Kind.SHORT_PLAYLIST, spm.group(1), null);
        }
        Matcher am = ALBUM_URL_PATTERN.matcher(u);
        if (am.find()) {
            return new ParsedYandexUrl(ParsedYandexUrl.Kind.ALBUM, am.group(1), null);
        }
        return null;
    }

    public static AccountStatus verifyToken(String token) throws IOException {
        String cleanToken = requireToken(token);
        String body = apiRequest("GET", API_BASE + "/account/status", cleanToken, null);
        JsonObject root = parseObject(body);
        JsonObject result = optObj(root, "result");
        if (result == null) {
            throw new IOException("Некорректный ответ Яндекс.Музыки");
        }
        JsonObject account = optObj(result, "account");
        if (account == null) {
            throw new IOException("Токен недействителен или устарел");
        }
        long uid = optLong(account, "uid", 0L);
        String login = optStr(account, "login", "");
        String displayName = optStr(account, "displayName", "");
        if (displayName.isEmpty()) {
            displayName = optStr(account, "fullName", login);
        }
        boolean hasPlus = false;
        JsonObject plus = optObj(result, "plus");
        if (plus != null && plus.has("hasPlus")) {
            try {
                hasPlus = plus.get("hasPlus").getAsBoolean();
            } catch (Throwable ignored) {
            }
        }
        if (uid <= 0 && login.isEmpty()) {
            throw new IOException("Авторизация Яндекс.Музыки не пройдена");
        }
        return new AccountStatus(uid, login, displayName, hasPlus);
    }

    public static List<BotMusicSearch.BotTrack> searchTracks(String token, String query, int limit) throws IOException {
        String cleanToken = requireToken(token);
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        int safeLimit = Math.max(1, Math.min(50, limit));
        String enc = URLEncoder.encode(query.trim(), "UTF-8");
        String url = API_BASE + "/search?text=" + enc + "&type=track&page=0&nococrrect=false";
        String body = apiRequest("GET", url, cleanToken, null);
        JsonObject root = parseObject(body);
        JsonObject result = optObj(root, "result");
        JsonObject tracksObj = optObj(result, "tracks");
        JsonArray arr = optArr(tracksObj, "results");
        return parseTrackArray(arr, safeLimit);
    }

    public static String resolveDirectMp3Url(String token, String trackId) throws IOException {
        String cleanToken = requireToken(token);
        String id = cleanTrackId(trackId);
        if (id.isEmpty()) {
            throw new IOException("Пустой ID трека Яндекс.Музыки");
        }
        String infoBody = apiRequest("GET", API_BASE + "/tracks/" + id + "/download-info", cleanToken, null);
        JsonObject root = parseObject(infoBody);
        JsonArray arr = optArr(root, "result");
        if (arr == null || arr.size() == 0) {
            throw new IOException("Нет ссылок для скачивания трека " + id);
        }

        String bestUrl = null;
        int bestBitrate = -1;
        boolean bestIsMp3 = false;
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            if (el == null || !el.isJsonObject()) continue;
            JsonObject item = el.getAsJsonObject();
            String codec = optStr(item, "codec", "").toLowerCase(Locale.ROOT);
            String dlUrl = optStr(item, "downloadInfoUrl", "");
            int bitrate = (int) optLong(item, "bitrateInKbps", 0L);
            if (dlUrl.isEmpty()) continue;

            boolean isMp3 = "mp3".equals(codec);
            if (bestUrl == null
                    || (isMp3 && !bestIsMp3)
                    || (isMp3 == bestIsMp3 && bitrate > bestBitrate)) {
                bestUrl = dlUrl;
                bestBitrate = bitrate;
                bestIsMp3 = isMp3;
            }
        }
        if (bestUrl == null) {
            throw new IOException("Не найдена ссылка downloadInfoUrl для трека " + id);
        }

        String jsonUrl = bestUrl + (bestUrl.contains("?") ? "&" : "?") + "format=json";
        DownloadTarget target = null;
        try {
            String targetJson = apiRequest("GET", jsonUrl, cleanToken, null);
            target = parseDownloadTargetJson(targetJson);
        } catch (IOException ignored) {
        }
        if (target == null || !target.isValid()) {
            String targetXml = apiRequest("GET", bestUrl, cleanToken, null);
            target = parseDownloadTargetXml(targetXml);
        }
        if (target == null || !target.isValid()) {
            throw new IOException("Не удалось разобрать download-info ЯМ");
        }
        return buildSignedMp3Url(target.host, target.path, target.ts, target.s);
    }

    public static List<BotMusicSearch.BotTrack> resolveYandexUrl(String token, String url) throws IOException {
        String cleanToken = requireToken(token);
        ParsedYandexUrl parsed = parseYandexUrl(url);
        if (parsed == null) {
            return Collections.emptyList();
        }
        switch (parsed.kind) {
            case TRACK: {
                String body = apiRequest("GET", API_BASE + "/tracks/" + parsed.primaryId, cleanToken, null);
                JsonObject root = parseObject(body);
                JsonArray arr = optArr(root, "result");
                List<BotMusicSearch.BotTrack> list = parseTrackArray(arr, 1);
                if (!list.isEmpty()) return list;
                return Collections.singletonList(new BotMusicSearch.BotTrack(
                        "Яндекс.Музыка #" + parsed.primaryId,
                        "Яндекс.Музыка",
                        YM_TRACK_PREFIX + parsed.primaryId,
                        BotMusicSearch.SERVICE_YANDEX,
                        false,
                        null,
                        "https://music.yandex.ru/"));
            }
            case ALBUM: {
                String body = apiRequest("GET",
                        API_BASE + "/albums/" + parsed.primaryId + "/with-tracks", cleanToken, null);
                JsonObject root = parseObject(body);
                JsonObject result = optObj(root, "result");
                JsonArray volumes = optArr(result, "volumes");
                List<BotMusicSearch.BotTrack> out = new ArrayList<>();
                if (volumes != null) {
                    for (int v = 0; v < volumes.size() && out.size() < 50; v++) {
                        JsonElement volEl = volumes.get(v);
                        if (volEl != null && volEl.isJsonArray()) {
                            out.addAll(parseTrackArray(volEl.getAsJsonArray(), 50 - out.size()));
                        }
                    }
                }
                return out;
            }
            case USER_PLAYLIST: {
                String ownerEnc = URLEncoder.encode(parsed.primaryId, "UTF-8");
                String body = apiRequest("GET",
                        API_BASE + "/users/" + ownerEnc + "/playlists/" + parsed.secondaryId,
                        cleanToken, null);
                return parsePlaylistTracksResponse(body, 50);
            }
            case SHORT_PLAYLIST: {
                String uuidEnc = URLEncoder.encode(parsed.primaryId, "UTF-8");
                String body = apiRequest("GET",
                        API_BASE + "/playlist/" + uuidEnc,
                        cleanToken, null);
                return parsePlaylistTracksResponse(body, 50);
            }
            default:
                return Collections.emptyList();
        }
    }

    public static List<BotMusicSearch.BotTrack> getChartTracks(String token, int limit) throws IOException {
        String cleanToken = requireToken(token);
        int safeLimit = Math.max(1, Math.min(50, limit));
        String body = apiRequest("GET", API_BASE + "/landing3/chart", cleanToken, null);
        JsonObject root = parseObject(body);
        JsonObject result = optObj(root, "result");
        JsonObject chart = optObj(result, "chart");
        JsonArray tracks = optArr(chart, "tracks");
        return parseWrappedTrackArray(tracks, safeLimit);
    }

    public static List<BotMusicSearch.BotTrack> getLikedTracks(String token, int limit) throws IOException {
        String cleanToken = requireToken(token);
        int safeLimit = Math.max(1, Math.min(50, limit));
        AccountStatus status = verifyToken(cleanToken);
        if (status.uid <= 0) {
            throw new IOException("Не удалось определить UID аккаунта Яндекс.Музыки");
        }
        String likesBody = apiRequest("GET",
                API_BASE + "/users/" + status.uid + "/likes/tracks", cleanToken, null);
        JsonObject root = parseObject(likesBody);
        JsonObject result = optObj(root, "result");
        JsonObject library = optObj(result, "library");
        JsonArray liked = optArr(library, "tracks");
        if (liked == null || liked.size() == 0) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < liked.size() && ids.size() < safeLimit; i++) {
            JsonElement el = liked.get(i);
            if (el == null || !el.isJsonObject()) continue;
            String id = optStr(el.getAsJsonObject(), "id", "");
            if (!id.isEmpty()) ids.add(id);
        }
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        StringBuilder form = new StringBuilder("track-ids=");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) form.append(',');
            form.append(URLEncoder.encode(ids.get(i), "UTF-8"));
        }
        String tracksBody = apiRequest("POST", API_BASE + "/tracks", cleanToken, form.toString());
        JsonObject tracksRoot = parseObject(tracksBody);
        return parseTrackArray(optArr(tracksRoot, "result"), safeLimit);
    }

    // --- Pure unit-testable signing & parsing helpers ---

    public static String computeDownloadSign(String path, String s) {
        String cleanPath = path != null ? path.trim() : "";
        if (cleanPath.startsWith("/")) {
            cleanPath = cleanPath.substring(1);
        }
        String payload = DOWNLOAD_SIGN_SALT + cleanPath + (s != null ? s.trim() : "");
        return md5Hex(payload);
    }

    public static String buildSignedMp3Url(String host, String path, String ts, String s) {
        String cleanPath = path != null ? path.trim() : "";
        if (!cleanPath.startsWith("/")) {
            cleanPath = "/" + cleanPath;
        }
        String sign = computeDownloadSign(cleanPath, s);
        return "https://" + host.trim() + "/get-mp3/" + sign + "/" + ts.trim() + cleanPath;
    }

    public static DownloadTarget parseDownloadTargetJson(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JsonElement el = JsonParser.parseString(json);
            if (el == null || !el.isJsonObject()) return null;
            JsonObject obj = el.getAsJsonObject();
            DownloadTarget target = new DownloadTarget(
                    optStr(obj, "host", ""),
                    optStr(obj, "path", ""),
                    optStr(obj, "ts", ""),
                    optStr(obj, "s", ""));
            return target.isValid() ? target : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static DownloadTarget parseDownloadTargetXml(String xml) {
        if (xml == null || xml.trim().isEmpty()) return null;
        String host = findFirst(XML_HOST, xml);
        String path = findFirst(XML_PATH, xml);
        String ts = findFirst(XML_TS, xml);
        String s = findFirst(XML_S, xml);
        DownloadTarget target = new DownloadTarget(host, path, ts, s);
        return target.isValid() ? target : null;
    }

    public static String cleanTrackId(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.startsWith(YM_TRACK_PREFIX)) {
            t = t.substring(YM_TRACK_PREFIX.length()).trim();
        }
        int colon = t.indexOf(':');
        if (colon > 0) {
            t = t.substring(0, colon);
        }
        return t;
    }

    private static List<BotMusicSearch.BotTrack> parsePlaylistTracksResponse(String body, int limit) throws IOException {
        JsonObject root = parseObject(body);
        JsonObject result = optObj(root, "result");
        JsonArray tracks = optArr(result, "tracks");
        return parseWrappedTrackArray(tracks, limit);
    }

    private static List<BotMusicSearch.BotTrack> parseWrappedTrackArray(JsonArray arr, int limit) {
        if (arr == null || arr.size() == 0) return Collections.emptyList();
        List<BotMusicSearch.BotTrack> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < arr.size() && out.size() < limit; i++) {
            JsonElement el = arr.get(i);
            if (el == null || !el.isJsonObject()) continue;
            JsonObject wrapper = el.getAsJsonObject();
            JsonObject tr = optObj(wrapper, "track");
            if (tr == null) tr = wrapper;
            BotMusicSearch.BotTrack bt = toBotTrack(tr);
            if (bt != null && seen.add(bt.url)) {
                out.add(bt);
            }
        }
        return out;
    }

    private static List<BotMusicSearch.BotTrack> parseTrackArray(JsonArray arr, int limit) {
        if (arr == null || arr.size() == 0) return Collections.emptyList();
        List<BotMusicSearch.BotTrack> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < arr.size() && out.size() < limit; i++) {
            JsonElement el = arr.get(i);
            if (el == null || !el.isJsonObject()) continue;
            BotMusicSearch.BotTrack bt = toBotTrack(el.getAsJsonObject());
            if (bt != null && seen.add(bt.url)) {
                out.add(bt);
            }
        }
        return out;
    }

    private static BotMusicSearch.BotTrack toBotTrack(JsonObject tr) {
        if (tr == null) return null;
        String id = optStr(tr, "id", "");
        if (id.isEmpty()) {
            long numId = optLong(tr, "id", 0L);
            if (numId > 0) id = String.valueOf(numId);
        }
        if (id.isEmpty()) return null;
        String title = optStr(tr, "title", "").trim();
        String version = optStr(tr, "version", "").trim();
        if (!version.isEmpty() && !title.toLowerCase(Locale.ROOT).contains(version.toLowerCase(Locale.ROOT))) {
            title = title + " (" + version + ")";
        }
        StringBuilder artistsSb = new StringBuilder();
        JsonArray artists = optArr(tr, "artists");
        if (artists != null) {
            for (int i = 0; i < artists.size(); i++) {
                JsonElement ae = artists.get(i);
                if (ae != null && ae.isJsonObject()) {
                    String name = optStr(ae.getAsJsonObject(), "name", "").trim();
                    if (!name.isEmpty()) {
                        if (artistsSb.length() > 0) artistsSb.append(", ");
                        artistsSb.append(name);
                    }
                }
            }
        }
        if (title.isEmpty() && artistsSb.length() == 0) {
            title = "Track #" + id;
        }
        return new BotMusicSearch.BotTrack(
                title,
                artistsSb.toString(),
                YM_TRACK_PREFIX + id,
                BotMusicSearch.SERVICE_YANDEX,
                false,
                null,
                "https://music.yandex.ru/");
    }

    private static String apiRequest(String method, String urlStr, String token, String formBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Authorization", "OAuth " + token);
            conn.setRequestProperty("X-Yandex-Music-Client", CLIENT_HEADER);
            conn.setRequestProperty("Accept-Language", "ru");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", BotMusicSearch.USER_AGENT);
            if ("POST".equals(method) && formBody != null) {
                byte[] payload = formBody.getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                conn.setFixedLengthStreamingMode(payload.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                    os.flush();
                }
            }
            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String resp = readAll(stream);
            if (code == 401 || code == 403) {
                throw new IOException("Яндекс.Музыка: неверный или устаревший OAuth-токен (HTTP " + code + ")");
            }
            if (code < 200 || code >= 300) {
                throw new IOException("Яндекс.Музыка HTTP " + code);
            }
            return resp;
        } finally {
            conn.disconnect();
        }
    }

    private static String requireToken(String token) throws IOException {
        if (token == null || token.trim().isEmpty()) {
            throw new IOException("Не задан OAuth-токен Яндекс.Музыки");
        }
        return token.trim();
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        try (InputStream in = is) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static JsonObject parseObject(String json) throws IOException {
        try {
            JsonElement el = JsonParser.parseString(json != null ? json : "");
            if (el != null && el.isJsonObject()) {
                return el.getAsJsonObject();
            }
        } catch (Throwable t) {
            throw new IOException("Ошибка разбора JSON Яндекс.Музыки: " + t.getMessage(), t);
        }
        throw new IOException("Пустой ответ от API Яндекс.Музыки");
    }

    private static JsonObject optObj(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) return null;
        return parent.getAsJsonObject(key);
    }

    private static JsonArray optArr(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonArray()) return null;
        return parent.getAsJsonArray(key);
    }

    private static String optStr(JsonObject parent, String key, String def) {
        if (parent == null || !parent.has(key) || parent.get(key).isJsonNull()) return def;
        try {
            return parent.get(key).getAsString();
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static long optLong(JsonObject parent, String key, long def) {
        if (parent == null || !parent.has(key) || parent.get(key).isJsonNull()) return def;
        try {
            return parent.get(key).getAsLong();
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static String findFirst(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() && m.group(1) != null ? m.group(1).trim() : "";
    }

    private static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.US, "%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Throwable t) {
            throw new RuntimeException("MD5 unavailable", t);
        }
    }
}
