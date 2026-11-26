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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Curated Russian media catalogs for DJ Bot (TT-DJbot analog):
 * - 32 built-in Russian FM radio stations
 * - Live Volnorez online station fetcher + built-in fallback catalog (from Volnorez.m3u)
 * - Volnorez WebSocket live metadata & listener counter reader (wss://s05/s04.volnorez.com)
 * - Exact 33 priority Russian Live TV channels (from 00_Top_RU_Live.m3u8) + geo-block probe
 * - Curated Russian /pop random hit queries
 */
public final class DjBotCatalogs {

    public static final String SERVICE_VOLN = "voln";
    public static final String SERVICE_TV = "tv";

    public static final List<BotMusicSearch.BotTrack> BUILT_IN_RADIO;
    public static final List<BotMusicSearch.BotTrack> BUILT_IN_VOLNOREZ;
    public static final List<BotMusicSearch.BotTrack> BUILT_IN_TV;
    public static final List<String> POP_QUERIES;

    private static final long VOLNOREZ_CACHE_TTL_MS = 5L * 60L * 1000L;
    private static volatile List<BotMusicSearch.BotTrack> cachedVolnorez = null;
    private static volatile long cachedVolnorezAt = 0L;

    private static final Pattern VOLN_STATION_LINK = Pattern.compile(
            "href\\s*=\\s*['\"]/(?:ru/)?([^/'\"?#]+)['\"][^>]*>([^<]{2,80})<",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VOLN_LIVE_APP_ID = Pattern.compile(
            "(?:live-app/|station_id[\"':\\s=]+|data-station-id=[\"']|broadcast_id[\"':\\s=]+)(\\d{5,8})");
    private static final Pattern VOLN_CARD_BLOCK = Pattern.compile(
            "(?:live-app/|data-id=[\"']|broadcast/|station[_-]?)(\\d{5,8})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VOLN_TITLE_ATTR = Pattern.compile(
            "(?:title|data-name|data-title|alt)\\s*=\\s*[\"']([^\"']{2,90})[\"']",
            Pattern.CASE_INSENSITIVE);

    static {
        // --- 32 Popular Russian FM Radio Stations ---
        List<BotMusicSearch.BotTrack> radio = new ArrayList<>();
        radio.add(mkRadio("Европа Плюс", "https://ep128.hostingradio.ru:8030/ep128"));
        radio.add(mkRadio("Русское Радио", "https://rusradio.hostingradio.ru/rusradio96.aacp"));
        radio.add(mkRadio("Дорожное Радио", "https://dorognoe.hostingradio.ru/radio"));
        radio.add(mkRadio("Авторадио", "https://pub0302.101.ru:8443/stream/air/aac/64/100"));
        radio.add(mkRadio("Ретро FM", "https://retro.hostingradio.ru:8043/retro128"));
        radio.add(mkRadio("Радио Рекорд", "https://radiorecord.hostingradio.ru/rr_main96.aacp"));
        radio.add(mkRadio("Радио Шансон", "https://chanson.hostingradio.ru:8041/chanson128.mp3"));
        radio.add(mkRadio("DFM", "https://dfm.hostingradio.ru/dfm96.aacp"));
        radio.add(mkRadio("Наше Радио", "https://nashe1.hostingradio.ru/nashe-128.mp3"));
        radio.add(mkRadio("Хит FM", "https://hitfm.hostingradio.ru/hitfm96.aacp"));
        radio.add(mkRadio("Юмор FM", "https://pub0302.101.ru:8443/stream/air/aac/64/102"));
        radio.add(mkRadio("Love Radio", "https://stream.loveradio.ru/12_love_28"));
        radio.add(mkRadio("Радио Дача", "https://stream.radiodacha.ru/14_dacha_28"));
        radio.add(mkRadio("Maximum", "https://maximum.hostingradio.ru/maximum96.aacp"));
        radio.add(mkRadio("Energy (NRJ)", "https://pub0302.101.ru:8443/stream/air/aac/64/99"));
        radio.add(mkRadio("Like FM", "https://pub0302.101.ru:8443/stream/air/aac/64/219"));
        radio.add(mkRadio("Новое Радио", "https://icecast-newradio.cdnvideo.ru/newradio3"));
        radio.add(mkRadio("Радио Ваня", "https://icecast-radiovanya.cdnvideo.ru/radiovanya"));
        radio.add(mkRadio("Радио 7 на семи холмах", "https://radio7.hostingradio.ru:8040/radio7128.mp3"));
        radio.add(mkRadio("Monte Carlo", "https://montecarlo.hostingradio.ru/montecarlo96.aacp"));
        radio.add(mkRadio("Comedy Radio", "https://pub0302.101.ru:8443/stream/air/aac/64/202"));
        radio.add(mkRadio("Relax FM", "https://pub0302.101.ru:8443/stream/air/aac/64/200"));
        radio.add(mkRadio("Радио Jazz", "https://nashe1.hostingradio.ru/jazz-128.mp3"));
        radio.add(mkRadio("Радио Маяк", "https://icecast-vgtrk.cdnvideo.ru/mayakfm_mp3_128kbps"));
        radio.add(mkRadio("Вести FM", "https://icecast-vgtrk.cdnvideo.ru/vestifm_mp3_128kbps"));
        radio.add(mkRadio("Радио России", "https://icecast-vgtrk.cdnvideo.ru/rrzonam_mp3_128kbps"));
        radio.add(mkRadio("Studio 21", "https://icecast-studio21.cdnvideo.ru/S21_1"));
        radio.add(mkRadio("Радио Искатель", "https://iskatel.hostingradio.ru:8015/iskatel-128.mp3"));
        radio.add(mkRadio("Милицейская Волна", "https://radiomv.hostingradio.ru:80/radiomv128.mp3"));
        radio.add(mkRadio("Маруся ФМ", "https://radio-holding.ru:9433/marusya_default"));
        radio.add(mkRadio("Радио Вера", "https://radiovera.hostingradio.ru:8007/radiovera_128"));
        radio.add(mkRadio("Радио Шоколад", "https://choco.hostingradio.ru:10010/fm"));
        BUILT_IN_RADIO = Collections.unmodifiableList(radio);

        // --- Built-in Volnorez stations (from TT-DJbot radio/Volnorez.m3u) ---
        List<BotMusicSearch.BotTrack> voln = new ArrayList<>();
        voln.add(mkVoln("Megamix MUSIC-RADIO", "698007"));
        voln.add(mkVoln("Чистый Кайф", "752890"));
        voln.add(mkVoln("Music-arya", "917320"));
        voln.add(mkVoln("ПыЛь_ДоРоГ FM", "869683"));
        voln.add(mkVoln("ШАНСОН над ВОЛГОЙ", "109661"));
        voln.add(mkVoln("Лидер-радио", "900927"));
        voln.add(mkVoln("Melody of Rain fm", "886682"));
        voln.add(mkVoln("Любви дыхание", "815228"));
        voln.add(mkVoln("Неповторимое", "383025"));
        voln.add(mkVoln("Радио Ночь любви", "879375"));
        voln.add(mkVoln("Venezia", "897415"));
        voln.add(mkVoln("Квезаль", "858936"));
        voln.add(mkVoln("В гостях у Брюнеточки", "902458"));
        voln.add(mkVoln("Океан Плюс", "773023"));
        voln.add(mkVoln("ПОЛЁТ ДУШИ", "904019"));
        voln.add(mkVoln("Страна талантов", "879346"));
        voln.add(mkVoln("Забытые Сердца", "880881"));
        voln.add(mkVoln("Минимум на максимум", "914556"));
        voln.add(mkVoln("Макси хард", "914654"));
        voln.add(mkVoln("КаПеЛьКа ТеПлА FM", "732475"));
        voln.add(mkVoln("Гравитация ФЁКЛЫ", "763788"));
        voln.add(mkVoln("ЕСЕНИЯ", "860704"));
        voln.add(mkVoln("Радио ВИШНЯ", "928573"));
        voln.add(mkVoln("СИНИЙ МОСТ радио", "928657"));
        voln.add(mkVoln("РАДИО ПЯТЫЙ ЭТАЖ", "928420"));
        voln.add(mkVoln("ПАЛАТА 7", "898145"));
        voln.add(mkVoln("Emotion Tunes", "545197"));
        voln.add(mkVoln("океан счастья", "900330"));
        voln.add(mkVoln("Музыкальный КЛУБ", "775495"));
        voln.add(mkVoln("Твоё Дыхание", "746596"));
        voln.add(mkVoln("РАДИО ДВУХ СЕРДЕЦ", "881102"));
        voln.add(mkVoln("Black black cat Radio", "686570"));
        voln.add(mkVoln("МАК-Онлайн Живое Радио Андрея Крупина", "843862"));
        voln.add(mkVoln("ЛИС-радио", "927499"));
        voln.add(mkVoln("Типичный и новый рок", "900894"));
        voln.add(mkVoln("ВАЛЕРИЙ СЕМИН", "925735"));
        voln.add(mkVoln("Авторадио Новомосковск", "443464"));
        voln.add(mkVoln("Наш шепот ветра", "881825"));
        voln.add(mkVoln("Звезда", "928435"));
        voln.add(mkVoln("М радио - супер радио", "764707"));
        voln.add(mkVoln("Black Sea Radio Stations", "906382"));
        voln.add(mkVoln("ШИПЫ и РОЗЫ", "920923"));
        voln.add(mkVoln("Всё для всех", "907814"));
        voln.add(mkVoln("РАДИО ЗОЛОТОЙ КВАДРАТ ШАНСОНА", "929308"));
        voln.add(mkVoln("Новое Радио Алексин", "853054"));
        voln.add(mkVoln("Рохан FM", "908357"));
        voln.add(mkVoln("Деревенская дискотека", "931402"));
        voln.add(mkVoln("LOVE_BREEZE", "780653"));
        voln.add(mkVoln("Зелёная долина", "837242"));
        voln.add(mkVoln("Авторадио Алексин", "916369"));
        voln.add(mkVoln("Миллениал FM", "931499"));
        voln.add(mkVoln("Радио Вертикаль", "159663"));
        voln.add(mkVoln("Radio NIGHTLIFE", "922822"));
        voln.add(mkVoln("Яркая Луна Онлайн", "891857"));
        voln.add(mkVoln("Осенний блюз-2", "907480"));
        voln.add(mkVoln("В гостях у котэ", "901850"));
        voln.add(mkVoln("КСП", "780765"));
        voln.add(mkVoln("Европа Плюс Мордовия 13 регион", "912351"));
        voln.add(mkVoln("Stereo-Radio", "839911"));
        voln.add(mkVoln("Avocado-FM", "930301"));
        BUILT_IN_VOLNOREZ = Collections.unmodifiableList(voln);

        // --- Exact 33 Priority Russian Live TV Channels (from 00_Top_RU_Live.m3u8) ---
        List<BotMusicSearch.BotTrack> tv = new ArrayList<>();
        tv.add(mkTv("Первый канал", "http://31.148.5.241/kanal_1/index.m3u8"));
        tv.add(mkTv("Россия 1", "http://31.148.5.241/kanal_2/index.m3u8"));
        tv.add(mkTv("Матч! Ультра", "http://31.148.5.241/kanal_3/index.m3u8"));
        tv.add(mkTv("НТВ", "http://31.148.5.241/kanal_4/index.m3u8"));
        tv.add(mkTv("Пятый канал", "http://31.148.5.241/kanal_5/index.m3u8"));
        tv.add(mkTv("Россия К", "http://31.148.5.241/kanal_6/index.m3u8"));
        tv.add(mkTv("Россия 24", "http://31.148.5.241/kanal_7/index.m3u8"));
        tv.add(mkTv("Карусель", "http://31.148.5.241/kanal_8/index.m3u8"));
        tv.add(mkTv("ОТР", "http://31.148.5.241/kanal_9/index.m3u8"));
        tv.add(mkTv("ТВ Центр", "http://31.148.5.241/kanal_10/index.m3u8"));
        tv.add(mkTv("РЕН ТВ", "http://31.148.5.241/kanal_11/index.m3u8"));
        tv.add(mkTv("Спас", "http://31.148.5.241/kanal_12/index.m3u8"));
        tv.add(mkTv("СТС", "http://31.148.5.241/kanal_13/index.m3u8"));
        tv.add(mkTv("Домашний", "http://31.148.5.241/kanal_14/index.m3u8"));
        tv.add(mkTv("ТВ-3", "http://31.148.5.241/kanal_15/index.m3u8"));
        tv.add(mkTv("Пятница!", "http://31.148.5.241/kanal_16/index.m3u8"));
        tv.add(mkTv("Звезда", "http://31.148.5.241/kanal_17/index.m3u8"));
        tv.add(mkTv("Мир", "http://31.148.5.241/kanal_18/index.m3u8"));
        tv.add(mkTv("ТНТ", "http://31.148.5.241/kanal_19/index.m3u8"));
        tv.add(mkTv("Муз-ТВ", "http://31.148.5.241/kanal_20/index.m3u8"));
        tv.add(mkTv("2x2", "http://176.118.197.101/2x2/index.m3u8"));
        tv.add(mkTv("Че!", "http://176.118.197.101/Che/index.m3u8"));
        tv.add(mkTv("Карусель +4", "http://185.37.150.46/Karusel/index.m3u8"));
        tv.add(mkTv("Мир 24", "http://hls.mirtv.cdnvideo.ru/mirtv-parampublish/mir24_2500/playlist.m3u8"));
        tv.add(mkTv("360°", "https://cdn-evacoder-tv.facecast.io/evacoder_hls_hi/CkxfR1xNUAJwTgtXTBZTAJli/index.m3u8"));
        tv.add(mkTv("Эфир ТВ", "http://tele2dvrnat01-02.cdnvideo.ru/stream/TatTVR_efir_d7b0c774f668d0652d2f435f03f2ef8a/hls/1024x576@1272/playlist.m3u8"));
        tv.add(mkTv("1HD Music", "http://176.118.197.101/1HD/index.m3u8"));
        tv.add(mkTv("BRIDGE TV", "http://178.134.1.158:8081/BRIDGE/index.m3u8"));
        tv.add(mkTv("BRIDGE Rock", "http://178.134.1.158:8081/BRIDGEROCK/index.m3u8"));
        tv.add(mkTv("Ностальгия", "http://176.118.197.101/Nostalgiya/index.m3u8"));
        tv.add(mkTv("Мир сериала", "http://176.118.197.101/MirSeriala/index.m3u8"));
        tv.add(mkTv("Музыка Первого", "http://88.212.15.50/live/test_muzyka_pervogo/playlist.m3u8"));
        tv.add(mkTv("Бобёр", "http://88.212.15.50/live/test_bober/playlist.m3u8"));
        BUILT_IN_TV = Collections.unmodifiableList(tv);

        // --- Curated Russian chanson / pop / rock queries for /pop ---
        POP_QUERIES = Collections.unmodifiableList(Arrays.asList(
                "Михаил Круг",
                "Руки Вверх",
                "Виктор Цой Кино",
                "Григорий Лепс",
                "Ирина Круг",
                "Стас Михайлов",
                "Любэ",
                "Король и Шут",
                "Юрий Шатунов",
                "Zivert",
                "Баста",
                "Полина Гагарина",
                "Дискотека Авария",
                "Звери",
                "Би-2",
                "ДДТ",
                "Сплин",
                "Сергей Лазарев",
                "Ирина Аллегрова",
                "Михаил Шуфутинский",
                "Иванушки International",
                "Ласковый май",
                "Мираж",
                "Валерий Меладзе",
                "Анна Асти"
        ));
    }

    private DjBotCatalogs() {
    }

    private static BotMusicSearch.BotTrack mkRadio(String name, String url) {
        return new BotMusicSearch.BotTrack(name, "Радио", url, BotMusicSearch.SERVICE_RADIO, true, null, null);
    }

    private static BotMusicSearch.BotTrack mkVoln(String name, String id) {
        return new BotMusicSearch.BotTrack(
                name, "Волнорез", "https://stream.volnorez.com/live-app/" + id, SERVICE_VOLN, true, null, "https://volnorez.com/");
    }

    private static BotMusicSearch.BotTrack mkTv(String name, String url) {
        return new BotMusicSearch.BotTrack(name, "ТВ", url, SERVICE_TV, true, null, null);
    }

    /**
     * Fetches online Volnorez stations from volnorez.com (cached 5 min) with built-in M3U fallback.
     */
    public static List<BotMusicSearch.BotTrack> getVolnorezStations(boolean forceRefresh) {
        long now = System.currentTimeMillis();
        if (!forceRefresh && cachedVolnorez != null && (now - cachedVolnorezAt) < VOLNOREZ_CACHE_TTL_MS) {
            return cachedVolnorez;
        }
        List<BotMusicSearch.BotTrack> live = fetchLiveVolnorezStations();
        if (live != null && live.size() >= 5) {
            cachedVolnorez = Collections.unmodifiableList(live);
            cachedVolnorezAt = now;
            return cachedVolnorez;
        }
        if (cachedVolnorez != null && !cachedVolnorez.isEmpty()) {
            return cachedVolnorez;
        }
        return BUILT_IN_VOLNOREZ;
    }

    private static List<BotMusicSearch.BotTrack> fetchLiveVolnorezStations() {
        List<BotMusicSearch.BotTrack> out = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        try {
            String html = BotMusicSearch.httpGet("https://volnorez.com/radio-stations?lang=ru", "https://volnorez.com/", "text/html");
            if (html == null || html.isEmpty()) return out;

            // Extract station IDs near titles in the HTML catalog
            Matcher idM = VOLN_LIVE_APP_ID.matcher(html);
            while (idM.find() && out.size() < 80) {
                String id = idM.group(1);
                if (!seenIds.add(id)) continue;
                int start = Math.max(0, idM.start() - 350);
                int end = Math.min(html.length(), idM.end() + 350);
                String slice = html.substring(start, end);
                String name = "";
                Matcher tm = VOLN_TITLE_ATTR.matcher(slice);
                while (tm.find()) {
                    String cand = tm.group(1).trim();
                    if (!cand.isEmpty() && !cand.toLowerCase(Locale.ROOT).contains("volnorez")) {
                        name = cand;
                        break;
                    }
                }
                if (name.isEmpty()) {
                    Matcher lm = VOLN_STATION_LINK.matcher(slice);
                    if (lm.find()) {
                        name = lm.group(2).trim();
                    }
                }
                if (name.isEmpty()) {
                    name = "Volnorez #" + id;
                }
                out.add(mkVoln(unescapeSimple(name), id));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static List<BotMusicSearch.BotTrack> searchVolnorez(String query) {
        List<BotMusicSearch.BotTrack> all = getVolnorezStations(false);
        String q = query != null ? query.trim() : "";
        if (q.isEmpty()) {
            return new ArrayList<>(all);
        }
        try {
            int idx = Integer.parseInt(q);
            if (idx >= 1 && idx <= all.size()) {
                return Collections.singletonList(all.get(idx - 1));
            }
        } catch (NumberFormatException ignored) {
        }
        String low = q.toLowerCase(Locale.ROOT);
        List<BotMusicSearch.BotTrack> matched = new ArrayList<>();
        for (BotMusicSearch.BotTrack st : all) {
            if (st.title.toLowerCase(Locale.ROOT).contains(low)) {
                matched.add(st);
            }
        }
        return matched;
    }

    public static List<BotMusicSearch.BotTrack> searchTv(String query) {
        String q = query != null ? query.trim() : "";
        if (q.isEmpty()) {
            return new ArrayList<>(BUILT_IN_TV);
        }
        try {
            int idx = Integer.parseInt(q);
            if (idx >= 1 && idx <= BUILT_IN_TV.size()) {
                return Collections.singletonList(BUILT_IN_TV.get(idx - 1));
            }
        } catch (NumberFormatException ignored) {
        }
        String low = q.toLowerCase(Locale.ROOT).replace('ё', 'е');
        List<BotMusicSearch.BotTrack> matched = new ArrayList<>();
        for (BotMusicSearch.BotTrack ch : BUILT_IN_TV) {
            if (ch.title.toLowerCase(Locale.ROOT).replace('ё', 'е').contains(low)) {
                matched.add(ch);
            }
        }
        return matched;
    }

    /**
     * Quick pre-flight check to reject geo-block stubs (rtk_block / zabava-block) or HTTP 403/404 TV streams.
     * Returns null if stream looks playable, or a Russian error description if blocked/unavailable.
     */
    public static String probeTvUrlBlocked(String urlStr) {
        if (urlStr == null || (!urlStr.startsWith("http://") && !urlStr.startsWith("https://"))) {
            return "некорректная ссылка";
        }
        String current = urlStr;
        for (int hops = 0; hops < 5; hops++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(current).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", BotMusicSearch.USER_AGENT);
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) {
                        return "пустой редирект";
                    }
                    String next = new URL(new URL(current), loc).toString();
                    String lowNext = next.toLowerCase(Locale.ROOT);
                    if (lowNext.contains("rtk_block") || lowNext.contains("zabava-block")) {
                        return "канал заблокирован по региону (геоблок)";
                    }
                    current = next;
                    continue;
                }
                if (code == 403 || code == 404 || code >= 400) {
                    return "HTTP " + code;
                }
                String lowUrl = current.toLowerCase(Locale.ROOT);
                if (lowUrl.contains("rtk_block") || lowUrl.contains("zabava-block")) {
                    return "канал заблокирован по региону (геоблок)";
                }
                try (InputStream is = conn.getInputStream()) {
                    byte[] buf = new byte[512];
                    int r = is.read(buf);
                    if (r > 0) {
                        String head = new String(buf, 0, r, StandardCharsets.UTF_8);
                        String lowHead = head.toLowerCase(Locale.ROOT);
                        if (head.contains("#EXT-X-ENDLIST") && lowHead.contains("block")) {
                            return "заглушка провайдера";
                        }
                    }
                }
                return null;
            } catch (IOException e) {
                String msg = e.getMessage();
                return msg != null && !msg.isEmpty() ? msg : "поток недоступен";
            } finally {
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return "слишком много перенаправлений";
    }

    private static String unescapeSimple(String s) {
        if (s == null) return "";
        return s.replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&#39;", "'")
                .replace("&#039;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Pure-Java Volnorez WebSocket live metadata & listener counter reader.
     * Mirrors TT-DJbot's {@code volnorez_metadata.py}.
     */
    public static final class VolnorezMetadataReader {

        private static final Pattern LIVE_APP_RE = Pattern.compile("/live-app/(\\d+)");
        private static final String[] SHARDS = {"s05.volnorez.com", "s04.volnorez.com"};

        public interface Callback {
            void onMetadata(String djAndTrack);
            void onListeners(int count);
        }

        private final String stationId;
        private final String wsPath;
        private final Callback callback;
        private volatile boolean stopped = false;
        private volatile Socket activeSocket = null;
        private Thread thread;
        private String lastText = "";

        public VolnorezMetadataReader(String streamUrl, Callback callback) {
            Matcher m = streamUrl != null ? LIVE_APP_RE.matcher(streamUrl) : null;
            this.stationId = (m != null && m.find()) ? m.group(1) : null;
            this.wsPath = this.stationId != null ? ("/live-app/" + this.stationId) : "";
            this.callback = callback;
        }

        public static boolean supports(String url) {
            if (url == null) return false;
            String low = url.toLowerCase(Locale.ROOT);
            return low.contains("volnorez.com") && LIVE_APP_RE.matcher(url).find();
        }

        public void start() {
            if (stationId == null) return;
            stopped = false;
            thread = new Thread(this::runLoop, "Extro-VolnMeta");
            thread.setDaemon(true);
            thread.start();
        }

        public void stop() {
            stopped = true;
            closeActiveSocket();
            Thread t = thread;
            thread = null;
            if (t != null) {
                t.interrupt();
            }
        }

        private void closeActiveSocket() {
            Socket s = activeSocket;
            activeSocket = null;
            if (s != null) {
                try {
                    s.close();
                } catch (Throwable ignored) {
                }
            }
        }

        private void runLoop() {
            List<String> candidates = new ArrayList<>(Arrays.asList(SHARDS));
            while (!stopped) {
                String host = candidates.remove(0);
                candidates.add(host);
                String redirect = null;
                try {
                    redirect = runSession(host);
                } catch (Throwable ignored) {
                }
                if (stopped) break;
                if (redirect != null && !redirect.isEmpty()) {
                    candidates.remove(redirect);
                    candidates.add(0, redirect);
                    continue;
                }
                try {
                    Thread.sleep(5000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        private String runSession(String host) throws IOException {
            SSLSocket sock = openTlsSocket(host, 443);
            if (sock == null) return null;
            activeSocket = sock;
            try {
                sock.setSoTimeout(30000);
                InputStream in = sock.getInputStream();
                OutputStream out = sock.getOutputStream();

                byte[] keyBytes = new byte[16];
                new SecureRandom().nextBytes(keyBytes);
                String key = encodeBase64(keyBytes);
                String req = "GET " + wsPath + " HTTP/1.1\r\n"
                        + "Host: " + host + "\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Key: " + key + "\r\n"
                        + "Sec-WebSocket-Version: 13\r\n"
                        + "Origin: https://volnorez.com\r\n\r\n";
                out.write(req.getBytes(StandardCharsets.ISO_8859_1));
                out.flush();

                String headers = readHttpHeaders(in);
                if (headers == null || !headers.contains(" 101 ")) {
                    return null;
                }

                sendWsFrame(out, 0x01, "{\"cmd\":\"add_player\",\"paused\":false,\"type\":\"stream\"}"
                        .getBytes(StandardCharsets.UTF_8));

                while (!stopped) {
                    WsFrame frame = readWsFrame(in);
                    if (frame == null) return null;
                    if (frame.opcode == 0x01) {
                        String redirect = handleTextPayload(frame.payload);
                        if (redirect != null) return redirect;
                    } else if (frame.opcode == 0x09) {
                        sendWsFrame(out, 0x0A, frame.payload);
                    } else if (frame.opcode == 0x08) {
                        return null;
                    }
                }
            } finally {
                closeActiveSocket();
            }
            return null;
        }

        private String handleTextPayload(byte[] payload) {
            try {
                String json = new String(payload, StandardCharsets.UTF_8);
                JsonElement el = JsonParser.parseString(json);
                if (el == null || !el.isJsonObject()) return null;
                JsonObject msg = el.getAsJsonObject();
                String cmd = msg.has("cmd") && !msg.get("cmd").isJsonNull() ? msg.get("cmd").getAsString() : "";
                if ("redirect".equals(cmd)) {
                    String h = msg.has("host") && !msg.get("host").isJsonNull() ? msg.get("host").getAsString().trim() : "";
                    return h.isEmpty() ? null : h;
                }
                if ("set_desc".equals(cmd) && msg.has("desc") && msg.get("desc").isJsonObject()) {
                    String formatted = formatVolnorezDesc(msg.getAsJsonObject("desc"));
                    if (!formatted.equals(lastText)) {
                        lastText = formatted;
                        if (callback != null && !formatted.isEmpty()) {
                            callback.onMetadata(formatted);
                        }
                    }
                } else if ("counter".equals(cmd) && msg.has("val") && !msg.get("val").isJsonNull()) {
                    int val = msg.get("val").getAsInt();
                    if (callback != null) {
                        callback.onListeners(Math.max(0, val));
                    }
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        public static String formatVolnorezDesc(JsonObject desc) {
            if (desc == null) return "";
            if (desc.has("status") && !desc.get("status").isJsonNull()) {
                String status = desc.get("status").getAsString();
                if (!status.isEmpty() && !"online".equalsIgnoreCase(status)) {
                    return "";
                }
            }
            String track = "";
            String dj = "";
            if (desc.has("music") && desc.get("music").isJsonArray()) {
                JsonArray music = desc.getAsJsonArray("music");
                if (music.size() > 0 && music.get(0).isJsonObject()) {
                    JsonObject first = music.get(0).getAsJsonObject();
                    if (first.has("title") && !first.get("title").isJsonNull()) {
                        track = first.get("title").getAsString().trim();
                    }
                    if (first.has("rj") && !first.get("rj").isJsonNull()) {
                        dj = first.get("rj").getAsString().trim();
                    }
                }
            }
            if (dj.isEmpty() && desc.has("rjs") && desc.get("rjs").isJsonArray()) {
                JsonArray rjs = desc.getAsJsonArray("rjs");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < rjs.size(); i++) {
                    if (!rjs.get(i).isJsonNull()) {
                        String r = rjs.get(i).getAsString().trim();
                        if (!r.isEmpty()) {
                            if (sb.length() > 0) sb.append(", ");
                            sb.append(r);
                        }
                    }
                }
                dj = sb.toString();
            }
            if (!dj.isEmpty() && !track.isEmpty()) {
                return dj + " — " + track;
            }
            return !track.isEmpty() ? track : dj;
        }

        private static SSLSocket openTlsSocket(String host, int port) {
            try {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                Socket raw = new Socket();
                raw.connect(new InetSocketAddress(host, port), 15000);
                SSLSocket ssl = (SSLSocket) factory.createSocket(raw, host, port, true);
                ssl.startHandshake();
                return ssl;
            } catch (Throwable firstErr) {
                try {
                    SSLContext ctx = SSLContext.getInstance("TLS");
                    ctx.init(null, new TrustManager[]{new X509TrustManager() {
                        @Override public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                        @Override public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                        @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[0];
                        }
                    }}, new SecureRandom());
                    Socket raw = new Socket();
                    raw.connect(new InetSocketAddress(host, port), 15000);
                    SSLSocket ssl = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
                    ssl.startHandshake();
                    return ssl;
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }

        private static String readHttpHeaders(InputStream in) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int prev3 = 0, prev2 = 0, prev1 = 0;
            while (baos.size() < 32768) {
                int b = in.read();
                if (b < 0) return null;
                baos.write(b);
                if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && b == '\n') {
                    return new String(baos.toByteArray(), StandardCharsets.ISO_8859_1);
                }
                prev3 = prev2;
                prev2 = prev1;
                prev1 = b;
            }
            return null;
        }

        private static final class WsFrame {
            final int opcode;
            final byte[] payload;

            WsFrame(int opcode, byte[] payload) {
                this.opcode = opcode;
                this.payload = payload;
            }
        }

        private WsFrame readWsFrame(InputStream in) throws IOException {
            byte[] hdr = readExact(in, 2);
            if (hdr == null) return null;
            int opcode = hdr[0] & 0x0F;
            boolean masked = (hdr[1] & 0x80) != 0;
            long len = hdr[1] & 0x7F;
            if (len == 126) {
                byte[] ext = readExact(in, 2);
                if (ext == null) return null;
                len = ((ext[0] & 0xFF) << 8) | (ext[1] & 0xFF);
            } else if (len == 127) {
                byte[] ext = readExact(in, 8);
                if (ext == null) return null;
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | (ext[i] & 0xFF);
                }
            }
            if (len < 0 || len > 4 * 1024 * 1024) return null;
            byte[] mask = masked ? readExact(in, 4) : null;
            if (masked && mask == null) return null;
            byte[] payload = len > 0 ? readExact(in, (int) len) : new byte[0];
            if (payload == null) return null;
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] = (byte) (payload[i] ^ mask[i % 4]);
                }
            }
            return new WsFrame(opcode, payload);
        }

        private byte[] readExact(InputStream in, int n) throws IOException {
            byte[] buf = new byte[n];
            int pos = 0;
            while (pos < n && !stopped) {
                try {
                    int r = in.read(buf, pos, n - pos);
                    if (r < 0) return null;
                    pos += r;
                } catch (java.net.SocketTimeoutException ignored) {
                }
            }
            return pos == n ? buf : null;
        }

        private static void sendWsFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(0x80 | (opcode & 0x0F));
            int n = payload.length;
            if (n < 126) {
                baos.write(0x80 | n);
            } else if (n < 65536) {
                baos.write(0x80 | 126);
                baos.write((n >> 8) & 0xFF);
                baos.write(n & 0xFF);
            } else {
                baos.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) {
                    baos.write((int) ((n >> (i * 8)) & 0xFF));
                }
            }
            byte[] mask = new byte[4];
            new SecureRandom().nextBytes(mask);
            baos.write(mask);
            for (int i = 0; i < n; i++) {
                baos.write(payload[i] ^ mask[i % 4]);
            }
            out.write(baos.toByteArray());
            out.flush();
        }

        private static String encodeBase64(byte[] bytes) {
            try {
                return Base64.getEncoder().encodeToString(bytes);
            } catch (Throwable ignored) {
                return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
            }
        }
    }
}
