package org.teamtalkextro.bot;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

/**
 * Lightweight Android XMLTV EPG guide matching TT-DJbot's {@code tv_epg.py}.
 *
 * Downloads and caches gzipped Russian XMLTV ({@code epg_lite.xml.gz} / {@code epg2.xml.gz})
 * for 6 hours in {@code cacheDir/djbot_xmltv.xml.gz}, streams with {@link XmlPullParser}
 * (with automatic {@link SAXParser} fallback for host JVM unit tests), keeps only a
 * +/-12h window in memory, maps Russian channel aliases, and formats programme times in
 * {@code Europe/Moscow}.
 */
public final class DjBotEpg {

    public static final String[] EPG_URLS = {
            "https://iptvx.one/epg/epg_lite.xml.gz",
            "https://epg.one/epg2.xml.gz",
            "http://epg.it999.ru/epg.xml.gz"
    };

    private static final long DEFAULT_TTL_MS = 6L * 3600L * 1000L; // 6 hours
    private static final long WINDOW_HALF_MS = 12L * 3600L * 1000L; // +/- 12 hours
    private static final long MIN_EPG_BYTES = 50_000L;
    private static final TimeZone MSK = TimeZone.getTimeZone("Europe/Moscow");

    private static final Pattern STRIP_BRACKETS = Pattern.compile("\\[[^\\]]*\\]");
    private static final Pattern STRIP_PARENS = Pattern.compile("\\([^)]*\\)");
    private static final Pattern STRIP_SHIFT = Pattern.compile("\\+\\d+\\b");
    private static final Pattern STRIP_QUALITY = Pattern.compile("\\b(hd|sd|fhd|uhd|4k|russia)\\b");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-zа-я0-9]+");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final Pattern XMLTV_TIME = Pattern.compile(
            "^(\\d{4})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(?:\\s*([+-]\\d{2})(\\d{2}))?");

    public static final Map<String, List<String>> CHANNEL_ALIASES;

    static {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("первый канал", Arrays.asList("первый канал", "первый", "1tv", "channel one", "1 канал"));
        map.put("россия 1", Arrays.asList("россия 1", "russia 1", "россия-1"));
        map.put("матч тв", Arrays.asList("матч тв", "матч!", "матч", "match tv", "match! ultra", "матч! ультра"));
        map.put("нтв", Arrays.asList("нтв", "ntv", "нтв hd", "ntv hd", "ntv russia"));
        map.put("пятый канал", Arrays.asList("пятый канал", "5 канал", "channel 5", "channel 5 russia"));
        map.put("россия к", Arrays.asList("россия к", "россия культура", "культура", "russia k"));
        map.put("россия 24", Arrays.asList("россия 24", "russia 24"));
        map.put("карусель", Arrays.asList("карусель", "carousel", "karusel"));
        map.put("отр", Arrays.asList("отр", "otr"));
        map.put("тв центр", Arrays.asList("тв центр", "твц", "tv centr", "tvc"));
        map.put("рен тв", Arrays.asList("рен тв", "ren tv", "рен-тв"));
        map.put("спас", Arrays.asList("спас", "spas"));
        map.put("стс", Arrays.asList("стс", "sts", "ctc"));
        map.put("домашний", Arrays.asList("домашний", "domashniy", "domashny"));
        map.put("тв 3", Arrays.asList("тв 3", "тв-3", "тв3", "tv 3", "tv-3", "tv3"));
        map.put("пятница", Arrays.asList("пятница", "пятница!", "friday", "friday!"));
        map.put("звезда", Arrays.asList("звезда", "zvezda"));
        map.put("мир", Arrays.asList("мир", "mir", "mir hd", "мир hd"));
        map.put("мир 24", Arrays.asList("мир 24", "mir 24"));
        map.put("тнт", Arrays.asList("тнт", "tnt"));
        map.put("муз тв", Arrays.asList("муз тв", "муз-тв", "muz tv", "muz-tv"));
        map.put("че", Arrays.asList("че", "че!", "che", "che!"));
        map.put("2x2", Arrays.asList("2x2", "2х2"));
        map.put("ю", Arrays.asList("ю", "u", "ю тв"));
        map.put("эфир", Arrays.asList("эфир", "эфир тв", "татарстан 24"));
        map.put("360", Arrays.asList("360", "360°", "360 новости", "360° news", "360 подмосковье"));
        map.put("тнв татарстан", Arrays.asList("тнв татарстан", "тнв", "тнв планета"));
        map.put("bridge tv", Arrays.asList("bridge", "bridge tv", "bridge classic"));
        map.put("bridge rock", Collections.singletonList("bridge rock"));
        map.put("ностальгия", Arrays.asList("ностальгия", "nostalgia", "nostalgia russia"));
        map.put("мир сериала", Arrays.asList("мир сериала", "mir seriala"));
        map.put("1hd", Arrays.asList("1hd", "1hd music", "1hd music television"));
        map.put("музыка первого", Arrays.asList("музыка первого", "muzyka pervogo"));
        map.put("бобер", Arrays.asList("бобер", "бобёр", "bober"));
        map.put("бст", Arrays.asList("бст", "bst"));
        CHANNEL_ALIASES = Collections.unmodifiableMap(map);
    }

    public static final class EpgInfo {
        public final String title;
        public final String start;
        public final String stop;
        public final String nextTitle;
        public final String nextStart;

        public EpgInfo(String title, String start, String stop, String nextTitle, String nextStart) {
            this.title = title != null ? title : "";
            this.start = start != null ? start : "";
            this.stop = stop != null ? stop : "";
            this.nextTitle = nextTitle;
            this.nextStart = nextStart;
        }

        public String formatStatus(String channelName) {
            String base = "📺 " + channelName + ": " + title;
            if (!start.isEmpty() && !stop.isEmpty()) {
                base += " (" + start + "–" + stop + ")";
            }
            return base;
        }

        public String formatDetailed(String channelName) {
            StringBuilder sb = new StringBuilder();
            sb.append("📺 ").append(channelName).append("\n");
            sb.append("Сейчас: ").append(title);
            if (!start.isEmpty() && !stop.isEmpty()) {
                sb.append(" (").append(start).append("–").append(stop).append(" МСК)");
            }
            if (nextTitle != null && !nextTitle.isEmpty()) {
                sb.append("\nДалее");
                if (nextStart != null && !nextStart.isEmpty()) {
                    sb.append(" в ").append(nextStart);
                }
                sb.append(": ").append(nextTitle);
            }
            return sb.toString();
        }
    }

    private static final class Programme {
        final long startMs;
        final long stopMs;
        final String title;

        Programme(long startMs, long stopMs, String title) {
            this.startMs = startMs;
            this.stopMs = stopMs;
            this.title = title;
        }
    }

    public interface Logger {
        void log(String msg);
    }

    private final File cacheFile;
    private final long ttlMs;
    private final Logger logger;
    private final Object lock = new Object();

    private boolean loading = false;
    private long loadedAtMs = 0L;
    private Map<String, List<String>> nameToIds = new HashMap<>();
    private Map<String, List<Programme>> programmes = new HashMap<>();

    public DjBotEpg(File cacheDir, Logger logger) {
        this(cacheDir, DEFAULT_TTL_MS, logger);
    }

    public DjBotEpg(File cacheDir, long ttlMs, Logger logger) {
        this.cacheFile = cacheDir != null ? new File(cacheDir, "djbot_xmltv.xml.gz") : null;
        this.ttlMs = ttlMs;
        this.logger = logger;
    }

    public static String normChannelName(String name) {
        if (name == null) return "";
        String s = name.toLowerCase(Locale.ROOT).replace('ё', 'е');
        s = STRIP_BRACKETS.matcher(s).replaceAll(" ");
        s = STRIP_PARENS.matcher(s).replaceAll(" ");
        s = STRIP_SHIFT.matcher(s).replaceAll(" ");
        s = STRIP_QUALITY.matcher(s).replaceAll(" ");
        s = NON_ALNUM.matcher(s).replaceAll(" ");
        return MULTI_SPACE.matcher(s).replaceAll(" ").trim();
    }

    public static long parseXmltvTime(String raw) {
        if (raw == null || raw.trim().isEmpty()) return -1L;
        Matcher m = XMLTV_TIME.matcher(raw.trim());
        if (!m.find()) return -1L;
        try {
            int y = Integer.parseInt(m.group(1));
            int mo = Integer.parseInt(m.group(2));
            int d = Integer.parseInt(m.group(3));
            int hh = Integer.parseInt(m.group(4));
            int mm = Integer.parseInt(m.group(5));
            int ss = Integer.parseInt(m.group(6));
            String tzH = m.group(7);
            String tzM = m.group(8);

            TimeZone tz;
            if (tzH != null && tzM != null) {
                tz = TimeZone.getTimeZone("GMT" + tzH + ":" + tzM);
            } else {
                tz = TimeZone.getTimeZone("UTC");
            }
            Calendar cal = Calendar.getInstance(tz);
            cal.setLenient(false);
            cal.set(Calendar.YEAR, y);
            cal.set(Calendar.MONTH, mo - 1);
            cal.set(Calendar.DAY_OF_MONTH, d);
            cal.set(Calendar.HOUR_OF_DAY, hh);
            cal.set(Calendar.MINUTE, mm);
            cal.set(Calendar.SECOND, ss);
            cal.set(Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    public boolean isLoaded() {
        synchronized (lock) {
            return !programmes.isEmpty();
        }
    }

    public void ensureLoadedAsync() {
        synchronized (lock) {
            if (loading) return;
            if (!programmes.isEmpty() && (System.currentTimeMillis() - loadedAtMs) < ttlMs) {
                return;
            }
            loading = true;
        }
        Thread t = new Thread(this::refreshWorker, "Extro-DjEpg");
        t.setDaemon(true);
        t.start();
    }

    private boolean isCacheFresh() {
        if (cacheFile == null || !cacheFile.exists()) return false;
        return cacheFile.length() > MIN_EPG_BYTES
                && (System.currentTimeMillis() - cacheFile.lastModified()) < ttlMs;
    }

    private boolean downloadCache() {
        if (cacheFile == null) return false;
        File parent = cacheFile.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        File tmp = new File(cacheFile.getAbsolutePath() + ".tmp");
        for (String urlStr : EPG_URLS) {
            HttpURLConnection conn = null;
            try {
                logMsg("EPG: загрузка " + urlStr);
                conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(45000);
                conn.setRequestProperty("User-Agent", BotMusicSearch.USER_AGENT);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    continue;
                }
                try (InputStream is = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[32768];
                    int r;
                    while ((r = is.read(buf)) > 0) {
                        fos.write(buf, 0, r);
                    }
                    fos.flush();
                }
                if (tmp.length() > MIN_EPG_BYTES) {
                    if (cacheFile.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        cacheFile.delete();
                    }
                    if (!tmp.renameTo(cacheFile)) {
                        // Fallback copy if rename failed
                        try (InputStream in = new FileInputStream(tmp);
                             FileOutputStream out = new FileOutputStream(cacheFile)) {
                            byte[] buf = new byte[32768];
                            int r;
                            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                        }
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                    }
                    logMsg("EPG: загружено " + (cacheFile.length() / 1024) + " КБ");
                    return true;
                }
            } catch (Throwable e) {
                logMsg("EPG ошибка (" + urlStr + "): " + e.getMessage());
                if (tmp.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
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
        return cacheFile.exists() && cacheFile.length() > MIN_EPG_BYTES;
    }

    private void refreshWorker() {
        try {
            if (!isCacheFresh()) {
                if (!downloadCache()) {
                    return;
                }
            }
            if (cacheFile != null && cacheFile.exists()) {
                try (InputStream fis = new BufferedInputStream(new FileInputStream(cacheFile), 32768);
                     InputStream gz = new GZIPInputStream(fis, 32768)) {
                    parseStream(gz, System.currentTimeMillis());
                }
            }
        } catch (Throwable t) {
            logMsg("EPG разбор: " + t.getMessage());
        } finally {
            synchronized (lock) {
                loading = false;
            }
        }
    }

    /**
     * Parses an XMLTV stream keeping only programmes inside {@code [nowMs - 12h, nowMs + 12h]}.
     * Uses {@link XmlPullParser} on Android and falls back to {@link SAXParser} on plain JVM.
     */
    public void parseStream(InputStream xmlStream, long nowMs) throws Exception {
        long winStart = nowMs - WINDOW_HALF_MS;
        long winEnd = nowMs + WINDOW_HALF_MS;
        Map<String, List<String>> nextNameToIds = new HashMap<>();
        Map<String, List<Programme>> nextProgrammes = new HashMap<>();

        boolean parsedWithPull = false;
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser xpp = factory.newPullParser();
            xpp.setInput(xmlStream, "UTF-8");
            parseWithXmlPull(xpp, winStart, winEnd, nextNameToIds, nextProgrammes);
            parsedWithPull = true;
        } catch (Throwable pullErr) {
            // Host JVM unit tests don't bundle kxml2 for XmlPullParserFactory; fall back to JDK SAXParser
            if (pullErr instanceof IOException) {
                throw (IOException) pullErr;
            }
        }

        if (!parsedWithPull) {
            parseWithSax(xmlStream, winStart, winEnd, nextNameToIds, nextProgrammes);
        }

        for (List<Programme> plist : nextProgrammes.values()) {
            Collections.sort(plist, Comparator.comparingLong(p -> p.startMs));
        }

        int progTotal = 0;
        for (List<Programme> plist : nextProgrammes.values()) {
            progTotal += plist.size();
        }
        synchronized (lock) {
            this.nameToIds = nextNameToIds;
            this.programmes = nextProgrammes;
            this.loadedAtMs = System.currentTimeMillis();
        }
        logMsg("EPG: " + nextNameToIds.size() + " имён, " + progTotal + " передач в окне ±12ч");
    }

    private static void parseWithXmlPull(
            XmlPullParser xpp,
            long winStart,
            long winEnd,
            Map<String, List<String>> nameToIds,
            Map<String, List<Programme>> programmes) throws Exception {
        int eventType = xpp.getEventType();
        String curChannelId = null;
        String curProgChannel = null;
        long curProgStart = -1L;
        long curProgStop = -1L;
        boolean inChannel = false;
        boolean inProgramme = false;

        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                String tag = xpp.getName();
                if ("channel".equals(tag)) {
                    curChannelId = trimOrEmpty(xpp.getAttributeValue(null, "id"));
                    inChannel = !curChannelId.isEmpty();
                    if (inChannel) {
                        addChannelMapping(nameToIds, normChannelName(curChannelId), curChannelId);
                    }
                } else if (inChannel && "display-name".equals(tag)) {
                    String dn = trimOrEmpty(xpp.nextText());
                    if (!dn.isEmpty()) {
                        addChannelMapping(nameToIds, normChannelName(dn), curChannelId);
                    }
                } else if ("programme".equals(tag)) {
                    curProgChannel = trimOrEmpty(xpp.getAttributeValue(null, "channel"));
                    curProgStart = parseXmltvTime(xpp.getAttributeValue(null, "start"));
                    curProgStop = parseXmltvTime(xpp.getAttributeValue(null, "stop"));
                    inProgramme = !curProgChannel.isEmpty()
                            && curProgStart > 0 && curProgStop > 0
                            && curProgStop >= winStart && curProgStart <= winEnd;
                } else if (inProgramme && "title".equals(tag)) {
                    String title = trimOrEmpty(xpp.nextText());
                    if (!title.isEmpty()) {
                        addProgramme(programmes, curProgChannel, curProgStart, curProgStop, title);
                        inProgramme = false; // take first title element
                    }
                }
            } else if (eventType == XmlPullParser.END_TAG) {
                String tag = xpp.getName();
                if ("channel".equals(tag)) {
                    inChannel = false;
                    curChannelId = null;
                } else if ("programme".equals(tag)) {
                    inProgramme = false;
                    curProgChannel = null;
                }
            }
            eventType = xpp.next();
        }
    }

    private static void parseWithSax(
            InputStream xmlStream,
            long winStart,
            long winEnd,
            Map<String, List<String>> nameToIds,
            Map<String, List<Programme>> programmes) throws Exception {
        SAXParserFactory spf = SAXParserFactory.newInstance();
        SAXParser parser = spf.newSAXParser();
        parser.parse(xmlStream, new DefaultHandler() {
            private String curChannelId;
            private boolean inChannel;
            private boolean captureDisplayName;

            private String curProgChannel;
            private long curProgStart;
            private long curProgStop;
            private boolean inProgramme;
            private boolean captureTitle;
            private final StringBuilder textBuf = new StringBuilder();

            @Override
            public void startElement(String uri, String localName, String qName, Attributes attributes) {
                if ("channel".equals(qName)) {
                    curChannelId = trimOrEmpty(attributes.getValue("id"));
                    inChannel = !curChannelId.isEmpty();
                    if (inChannel) {
                        addChannelMapping(nameToIds, normChannelName(curChannelId), curChannelId);
                    }
                } else if (inChannel && "display-name".equals(qName)) {
                    captureDisplayName = true;
                    textBuf.setLength(0);
                } else if ("programme".equals(qName)) {
                    curProgChannel = trimOrEmpty(attributes.getValue("channel"));
                    curProgStart = parseXmltvTime(attributes.getValue("start"));
                    curProgStop = parseXmltvTime(attributes.getValue("stop"));
                    inProgramme = !curProgChannel.isEmpty()
                            && curProgStart > 0 && curProgStop > 0
                            && curProgStop >= winStart && curProgStart <= winEnd;
                } else if (inProgramme && "title".equals(qName)) {
                    captureTitle = true;
                    textBuf.setLength(0);
                }
            }

            @Override
            public void characters(char[] ch, int start, int length) {
                if (captureDisplayName || captureTitle) {
                    textBuf.append(ch, start, length);
                }
            }

            @Override
            public void endElement(String uri, String localName, String qName) {
                if ("display-name".equals(qName) && captureDisplayName) {
                    captureDisplayName = false;
                    String dn = textBuf.toString().trim();
                    if (!dn.isEmpty() && curChannelId != null) {
                        addChannelMapping(nameToIds, normChannelName(dn), curChannelId);
                    }
                } else if ("channel".equals(qName)) {
                    inChannel = false;
                    curChannelId = null;
                } else if ("title".equals(qName) && captureTitle) {
                    captureTitle = false;
                    String title = textBuf.toString().trim();
                    if (!title.isEmpty() && inProgramme && curProgChannel != null) {
                        addProgramme(programmes, curProgChannel, curProgStart, curProgStop, title);
                        inProgramme = false;
                    }
                } else if ("programme".equals(qName)) {
                    inProgramme = false;
                    curProgChannel = null;
                }
            }
        });
    }

    private static void addChannelMapping(Map<String, List<String>> map, String normKey, String cid) {
        if (normKey == null || normKey.isEmpty() || cid == null || cid.isEmpty()) return;
        List<String> list = map.get(normKey);
        if (list == null) {
            list = new ArrayList<>(2);
            map.put(normKey, list);
        }
        if (!list.contains(cid)) {
            list.add(cid);
        }
    }

    private static void addProgramme(Map<String, List<Programme>> map, String cid, long start, long stop, String title) {
        List<Programme> list = map.get(cid);
        if (list == null) {
            list = new ArrayList<>();
            map.put(cid, list);
        }
        list.add(new Programme(start, stop, title));
    }

    public static List<String> candidateKeys(String channelTitle) {
        String base = normChannelName(channelTitle);
        if (base.isEmpty()) return Collections.emptyList();
        Set<String> keys = new LinkedHashSet<>();
        keys.add(base);
        for (Map.Entry<String, List<String>> entry : CHANNEL_ALIASES.entrySet()) {
            String canon = normChannelName(entry.getKey());
            List<String> normAliases = new ArrayList<>();
            for (String a : entry.getValue()) {
                normAliases.add(normChannelName(a));
            }
            if (base.equals(canon) || normAliases.contains(base)) {
                if (!canon.isEmpty()) keys.add(canon);
                for (String na : normAliases) {
                    if (!na.isEmpty()) keys.add(na);
                }
                break;
            }
        }
        return new ArrayList<>(keys);
    }

    public EpgInfo getNow(String channelTitle) {
        ensureLoadedAsync();
        return getNowAt(channelTitle, System.currentTimeMillis());
    }

    public EpgInfo getNowAt(String channelTitle, long nowMs) {
        Map<String, List<String>> n2i;
        Map<String, List<Programme>> progs;
        synchronized (lock) {
            if (programmes.isEmpty()) return null;
            n2i = this.nameToIds;
            progs = this.programmes;
        }

        List<String> matchedIds = new ArrayList<>();
        for (String key : candidateKeys(channelTitle)) {
            List<String> cids = n2i.get(key);
            if (cids != null) {
                for (String cid : cids) {
                    if (!matchedIds.contains(cid)) matchedIds.add(cid);
                }
            }
        }

        if (matchedIds.isEmpty()) {
            String base = normChannelName(channelTitle);
            if (base.length() >= 3) {
                for (Map.Entry<String, List<String>> entry : n2i.entrySet()) {
                    String k = entry.getKey();
                    if (base.equals(k) || (k.length() >= 3 && (base.contains(k) || k.contains(base)))) {
                        for (String cid : entry.getValue()) {
                            if (!matchedIds.contains(cid)) matchedIds.add(cid);
                        }
                        if (matchedIds.size() >= 5) break;
                    }
                }
            }
        }

        SimpleDateFormat hhmm = new SimpleDateFormat("HH:mm", Locale.US);
        hhmm.setTimeZone(MSK);

        for (String cid : matchedIds) {
            List<Programme> plist = progs.get(cid);
            if (plist == null) continue;
            for (int i = 0; i < plist.size(); i++) {
                Programme p = plist.get(i);
                if (p.startMs <= nowMs && nowMs < p.stopMs) {
                    String startStr = hhmm.format(new Date(p.startMs));
                    String stopStr = hhmm.format(new Date(p.stopMs));
                    String nextTitle = null;
                    String nextStart = null;
                    if (i + 1 < plist.size()) {
                        Programme next = plist.get(i + 1);
                        nextTitle = next.title;
                        nextStart = hhmm.format(new Date(next.startMs));
                    }
                    return new EpgInfo(p.title, startStr, stopStr, nextTitle, nextStart);
                }
            }
        }
        return null;
    }

    private static String trimOrEmpty(String s) {
        return s != null ? s.trim() : "";
    }

    private void logMsg(String msg) {
        if (logger != null) {
            try {
                logger.log(msg);
            } catch (Throwable ignored) {
            }
        }
    }
}
