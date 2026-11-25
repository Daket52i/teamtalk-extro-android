package org.teamtalkextro.security;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import com.google.gson.JsonObject;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.teamtalkextro.R;
import org.teamtalkextro.data.Preferences;

/**
 * Extro Shield — built-in security guard for TeamTalk Extro Android v5.33.0.
 *
 * Provides:
 * - Safe Link Guard (IP-loggers, punycode homographs, direct executable/APK links,
 *   URL shorteners, raw IP HTTP URLs)
 * - Anti-Zalgo & Unicode BiDi/RLO spoof sanitizer
 * - Anti-Flood burst limiter (>6 messages in 3 seconds per user)
 * - Security Audit Log (last 200 security events with timestamps)
 */
public final class ExtroShield {

    public static final int MAX_AUDIT_LOG_ENTRIES = 200;
    public static final int MAX_COMBINING_MARKS = 6;
    public static final int ZALGO_KEEP_MARKS = 2;
    public static final int FLOOD_MAX_MESSAGES = 6;
    public static final long FLOOD_WINDOW_MS = 3000L;

    public static final String CAT_LINK = "LINK";
    public static final String CAT_FLOOD = "FLOOD";
    public static final String CAT_UNICODE = "UNICODE";
    public static final String CAT_TLS = "TLS";
    public static final String CAT_MODERATION = "MOD";
    public static final String CAT_PANIC = "PANIC";
    public static final String CAT_TIMER = "TIMER";

    private static final String[] IP_LOGGER_PREFIXES = {
            "grabify.",
            "iplogger.",
            "blasze.",
            "ps3cfw."
    };

    private static final String[] IP_LOGGER_DOMAINS = {
            "2no.co",
            "yip.su"
    };

    private static final String[] URL_SHORTENER_DOMAINS = {
            "bit.ly",
            "tinyurl.com",
            "clck.ru",
            "t.co"
    };

    private static final String[] DANGEROUS_EXTENSIONS = {
            ".apk",
            ".exe",
            ".scr",
            ".bat",
            ".msi"
    };

    // Matches http(s)://... or ftp://... OR bare suspicious domains (grabify, shorteners, punycode)
    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?i)(?:\\b(?:https?|ftp)://[^\\s<>\"'()]+|\\b(?:grabify\\.[a-z0-9.-]+|iplogger\\.[a-z0-9.-]+|blasze\\.[a-z0-9.-]+|ps3cfw\\.[a-z0-9.-]+|2no\\.co|yip\\.su|bit\\.ly|tinyurl\\.com|clck\\.ru|t\\.co|xn--[a-z0-9-]+(?:\\.[a-z0-9-]+)+)(?:/[^\\s<>\"'()]*)?)"
    );

    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$"
    );

    public enum ThreatType {
        IP_LOGGER,
        PUNYCODE,
        EXECUTABLE,
        URL_SHORTENER,
        RAW_IP_HTTP
    }

    public enum FloodVerdict {
        ALLOWED,
        BLOCKED_FIRST,
        BLOCKED_REPEAT
    }

    public static final class LinkScanResult {
        public final boolean suspicious;
        public final Set<ThreatType> threats;
        public final List<String> suspiciousUrls;

        public LinkScanResult(boolean suspicious, Set<ThreatType> threats, List<String> suspiciousUrls) {
            this.suspicious = suspicious;
            this.threats = Collections.unmodifiableSet(threats);
            this.suspiciousUrls = Collections.unmodifiableList(suspiciousUrls);
        }
    }

    public static final class SanitizeResult {
        public final String text;
        public final boolean modified;
        public final boolean rloDetected;
        public final boolean zalgoDetected;

        public SanitizeResult(String text, boolean modified, boolean rloDetected, boolean zalgoDetected) {
            this.text = text;
            this.modified = modified;
            this.rloDetected = rloDetected;
            this.zalgoDetected = zalgoDetected;
        }
    }

    public static final class SecurityEvent {
        public final long timestampMs;
        public final String formattedTime;
        public final String category;
        public final String message;

        public SecurityEvent(long timestampMs, String formattedTime, String category, String message) {
            this.timestampMs = timestampMs;
            this.formattedTime = formattedTime;
            this.category = category;
            this.message = message;
        }

        public String formatLine() {
            return "[" + formattedTime + "] [" + category + "] " + message;
        }
    }

    /**
     * Sliding-window per-user burst flood detector (>6 messages in 3 seconds).
     */
    public static final class FloodDetector {
        private final int maxMessages;
        private final long windowMs;
        private final Map<Integer, Deque<Long>> userTimestamps = new HashMap<>();
        private final Set<Integer> notifiedInBurst = new LinkedHashSet<>();

        public FloodDetector() {
            this(FLOOD_MAX_MESSAGES, FLOOD_WINDOW_MS);
        }

        public FloodDetector(int maxMessages, long windowMs) {
            this.maxMessages = Math.max(1, maxMessages);
            this.windowMs = Math.max(250L, windowMs);
        }

        public synchronized FloodVerdict check(int userId, long nowMs) {
            Deque<Long> deque = userTimestamps.get(userId);
            if (deque == null) {
                deque = new ArrayDeque<>();
                userTimestamps.put(userId, deque);
            }
            long cutoff = nowMs - windowMs;
            while (!deque.isEmpty() && deque.peekFirst() < cutoff) {
                deque.pollFirst();
            }
            if (deque.size() < maxMessages) {
                deque.addLast(nowMs);
                notifiedInBurst.remove(userId);
                return FloodVerdict.ALLOWED;
            }
            // Count would be > maxMessages within windowMs: drop message
            deque.addLast(nowMs);
            if (notifiedInBurst.add(userId)) {
                return FloodVerdict.BLOCKED_FIRST;
            }
            return FloodVerdict.BLOCKED_REPEAT;
        }

        public synchronized void reset() {
            userTimestamps.clear();
            notifiedInBurst.clear();
        }
    }

    private static final Deque<SecurityEvent> AUDIT_LOG = new ArrayDeque<>();
    private static final FloodDetector SHARED_FLOOD_DETECTOR = new FloodDetector();

    private ExtroShield() {
    }

    // --- Settings ---

    public static boolean isSafeLinkGuardEnabled(Context context) {
        if (context == null) return true;
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
            return prefs.getBoolean(Preferences.PREF_SHIELD_SAFE_LINKS, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static void setSafeLinkGuardEnabled(Context context, boolean enabled) {
        if (context == null) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        prefs.edit().putBoolean(Preferences.PREF_SHIELD_SAFE_LINKS, enabled).apply();
    }

    public static boolean isAntiFloodZalgoEnabled(Context context) {
        if (context == null) return true;
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
            return prefs.getBoolean(Preferences.PREF_SHIELD_ANTI_FLOOD_ZALGO, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static void setAntiFloodZalgoEnabled(Context context, boolean enabled) {
        if (context == null) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        prefs.edit().putBoolean(Preferences.PREF_SHIELD_ANTI_FLOOD_ZALGO, enabled).apply();
    }

    public static JsonObject exportSettingsJson(Context context) {
        JsonObject obj = new JsonObject();
        obj.addProperty("safe_links", isSafeLinkGuardEnabled(context));
        obj.addProperty("anti_flood_zalgo", isAntiFloodZalgoEnabled(context));
        return obj;
    }

    public static int importSettingsJson(Context context, JsonObject obj) {
        if (context == null || obj == null) return 0;
        int restored = 0;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        SharedPreferences.Editor editor = prefs.edit();
        if (obj.has("safe_links") && !obj.get("safe_links").isJsonNull()) {
            try {
                editor.putBoolean(Preferences.PREF_SHIELD_SAFE_LINKS, obj.get("safe_links").getAsBoolean());
                restored++;
            } catch (Exception ignored) {
            }
        }
        if (obj.has("anti_flood_zalgo") && !obj.get("anti_flood_zalgo").isJsonNull()) {
            try {
                editor.putBoolean(Preferences.PREF_SHIELD_ANTI_FLOOD_ZALGO, obj.get("anti_flood_zalgo").getAsBoolean());
                restored++;
            } catch (Exception ignored) {
            }
        }
        editor.apply();
        return restored;
    }

    // --- Safe Link Guard ---

    public static LinkScanResult scanTextLinks(String text) {
        if (text == null || text.isEmpty()) {
            return new LinkScanResult(false, Collections.emptySet(), Collections.emptyList());
        }
        Set<ThreatType> threats = new LinkedHashSet<>();
        List<String> suspiciousUrls = new ArrayList<>();

        Matcher m = URL_PATTERN.matcher(text);
        while (m.find()) {
            String rawUrl = trimTrailingPunctuation(m.group());
            if (rawUrl.isEmpty()) continue;
            Set<ThreatType> urlThreats = inspectSingleUrl(rawUrl);
            if (!urlThreats.isEmpty()) {
                threats.addAll(urlThreats);
                suspiciousUrls.add(rawUrl);
            }
        }
        return new LinkScanResult(!threats.isEmpty(), threats, suspiciousUrls);
    }

    public static Set<ThreatType> inspectSingleUrl(String rawUrl) {
        Set<ThreatType> found = new LinkedHashSet<>();
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return found;
        }
        String candidate = trimTrailingPunctuation(rawUrl.trim());
        String lower = candidate.toLowerCase(Locale.ROOT);
        boolean hadHttpScheme = lower.startsWith("http://");
        String parseTarget = (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("ftp://"))
                ? candidate : ("http://" + candidate);

        String host = null;
        String path = "";
        try {
            URI uri = new URI(parseTarget);
            host = uri.getHost();
            if (host == null) {
                String auth = uri.getRawAuthority();
                if (auth != null) {
                    int at = auth.lastIndexOf('@');
                    if (at >= 0) auth = auth.substring(at + 1);
                    int colon = auth.indexOf(':');
                    host = colon >= 0 ? auth.substring(0, colon) : auth;
                }
            }
            path = uri.getRawPath();
            if (path == null) path = "";
        } catch (Exception e) {
            // Fallback host/path extraction for malformed URLs
            String stripped = parseTarget.replaceFirst("(?i)^[a-z]+://", "");
            int slash = stripped.indexOf('/');
            String hostPort = slash >= 0 ? stripped.substring(0, slash) : stripped;
            path = slash >= 0 ? stripped.substring(slash) : "";
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            int hash = path.indexOf('#');
            if (hash >= 0) path = path.substring(0, hash);
            int at = hostPort.lastIndexOf('@');
            if (at >= 0) hostPort = hostPort.substring(at + 1);
            int colon = hostPort.indexOf(':');
            host = colon >= 0 ? hostPort.substring(0, colon) : hostPort;
        }

        if (host != null) {
            host = host.toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) {
                host = host.substring(0, host.length() - 1);
            }

            // 1. IP-loggers
            if (isIpLoggerHost(host)) {
                found.add(ThreatType.IP_LOGGER);
            }

            // 2. Punycode homographs (xn--)
            if (hasPunycodeLabel(host)) {
                found.add(ThreatType.PUNYCODE);
            }

            // 3. URL shorteners
            if (isUrlShortenerHost(host)) {
                found.add(ThreatType.URL_SHORTENER);
            }

            // 4. Raw IP HTTP URLs
            if (hadHttpScheme && isValidIpv4(host)) {
                found.add(ThreatType.RAW_IP_HTTP);
            }
        }

        // 5. Direct executable / APK links
        String cleanPath = path.toLowerCase(Locale.ROOT);
        int qIdx = cleanPath.indexOf('?');
        if (qIdx >= 0) cleanPath = cleanPath.substring(0, qIdx);
        int hIdx = cleanPath.indexOf('#');
        if (hIdx >= 0) cleanPath = cleanPath.substring(0, hIdx);
        for (String ext : DANGEROUS_EXTENSIONS) {
            if (cleanPath.endsWith(ext)) {
                found.add(ThreatType.EXECUTABLE);
                break;
            }
        }

        return found;
    }

    private static boolean isIpLoggerHost(String host) {
        String[] labels = host.split("\\.");
        for (String prefix : IP_LOGGER_PREFIXES) {
            if (host.startsWith(prefix) || host.contains("." + prefix)) {
                return true;
            }
        }
        for (String domain : IP_LOGGER_DOMAINS) {
            if (host.equals(domain) || host.endsWith("." + domain)) {
                return true;
            }
        }
        // Also catch exact label "grabify" / "iplogger" / "blasze" / "ps3cfw"
        for (String label : labels) {
            if ("grabify".equals(label) || "iplogger".equals(label)
                    || "blasze".equals(label) || "ps3cfw".equals(label)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPunycodeLabel(String host) {
        if (host.startsWith("xn--") || host.contains(".xn--")) {
            return true;
        }
        return false;
    }

    private static boolean isUrlShortenerHost(String host) {
        for (String domain : URL_SHORTENER_DOMAINS) {
            if (host.equals(domain) || host.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isValidIpv4(String host) {
        Matcher m = IPV4_PATTERN.matcher(host);
        if (!m.matches()) return false;
        try {
            for (int i = 1; i <= 4; i++) {
                int octet = Integer.parseInt(m.group(i));
                if (octet < 0 || octet > 255) return false;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String trimTrailingPunctuation(String s) {
        if (s == null) return "";
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == '.' || c == ',' || c == '!' || c == '?' || c == ';' || c == ':' || c == ')' || c == ']' || c == '}') {
                end--;
            } else {
                break;
            }
        }
        return s.substring(0, end);
    }

    public static String describeThreats(Context context, Set<ThreatType> threats) {
        if (threats == null || threats.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ThreatType t : threats) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(describeThreat(context, t));
        }
        return sb.toString();
    }

    public static String describeThreat(Context context, ThreatType threat) {
        if (context != null) {
            try {
                switch (threat) {
                    case IP_LOGGER:
                        return context.getString(R.string.shield_threat_ip_logger);
                    case PUNYCODE:
                        return context.getString(R.string.shield_threat_punycode);
                    case EXECUTABLE:
                        return context.getString(R.string.shield_threat_executable);
                    case URL_SHORTENER:
                        return context.getString(R.string.shield_threat_shortener);
                    case RAW_IP_HTTP:
                        return context.getString(R.string.shield_threat_raw_ip);
                }
            } catch (Throwable ignored) {
            }
        }
        switch (threat) {
            case IP_LOGGER:
                return "IP-логгер";
            case PUNYCODE:
                return "Punycode-спуфинг";
            case EXECUTABLE:
                return "исполняемый файл / APK";
            case URL_SHORTENER:
                return "сокращатель ссылок";
            case RAW_IP_HTTP:
                return "прямой IP по HTTP";
            default:
                return "подозрительная ссылка";
        }
    }

    public static String annotateMessageIfSuspicious(Context context, String message, LinkScanResult scan) {
        if (message == null || scan == null || !scan.suspicious) {
            return message;
        }
        String reasons = describeThreats(context, scan.threats);
        if (context != null) {
            try {
                return context.getString(R.string.shield_link_warning_prefix, reasons) + " " + message;
            } catch (Throwable ignored) {
            }
        }
        return "[⚠️ Осторожно: " + reasons + "] " + message;
    }

    // --- Anti-Zalgo / Unicode Spoof Guard ---

    private static boolean isBidiSpoofChar(int cp) {
        return cp == 0x202A // LRE
                || cp == 0x202B // RLE
                || cp == 0x202C // PDF
                || cp == 0x202D // LRO
                || cp == 0x202E // RLO (Right-to-Left Override)
                || cp == 0x2066 // LRI
                || cp == 0x2067 // RLI
                || cp == 0x2068 // FSI
                || cp == 0x2069; // PDI
    }

    private static boolean isCombiningMark(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    /**
     * Strips Unicode BiDi/RLO spoof controls (including U+202E) and neutralizes
     * Zalgo combining-mark attacks (>6 consecutive combining diacritics).
     */
    public static SanitizeResult sanitizeUnicode(String input) {
        if (input == null || input.isEmpty()) {
            return new SanitizeResult(input != null ? input : "", false, false, false);
        }

        StringBuilder out = new StringBuilder(input.length());
        boolean rloDetected = false;
        boolean zalgoDetected = false;

        int i = 0;
        int len = input.length();
        while (i < len) {
            int cp = input.codePointAt(i);
            int charCount = Character.charCount(cp);

            if (isBidiSpoofChar(cp)) {
                rloDetected = true;
                i += charCount;
                continue;
            }

            if (isCombiningMark(cp)) {
                // Collect entire consecutive run of combining marks (ignoring embedded bidi spoof chars)
                List<Integer> run = new ArrayList<>();
                int j = i;
                while (j < len) {
                    int nextCp = input.codePointAt(j);
                    int nextCount = Character.charCount(nextCp);
                    if (isBidiSpoofChar(nextCp)) {
                        rloDetected = true;
                        j += nextCount;
                        continue;
                    }
                    if (isCombiningMark(nextCp)) {
                        run.add(nextCp);
                        j += nextCount;
                    } else {
                        break;
                    }
                }
                if (run.size() > MAX_COMBINING_MARKS) {
                    zalgoDetected = true;
                    int keep = Math.min(ZALGO_KEEP_MARKS, run.size());
                    for (int k = 0; k < keep; k++) {
                        out.appendCodePoint(run.get(k));
                    }
                } else {
                    for (int mark : run) {
                        out.appendCodePoint(mark);
                    }
                }
                i = j;
                continue;
            }

            out.appendCodePoint(cp);
            i += charCount;
        }

        boolean modified = rloDetected || zalgoDetected;
        return new SanitizeResult(modified ? out.toString() : input, modified, rloDetected, zalgoDetected);
    }

    // --- Shared Anti-Flood ---

    public static FloodVerdict checkFlood(int userId) {
        return SHARED_FLOOD_DETECTOR.check(userId, System.currentTimeMillis());
    }

    public static FloodVerdict checkFlood(int userId, long nowMs) {
        return SHARED_FLOOD_DETECTOR.check(userId, nowMs);
    }

    public static void resetFloodState() {
        SHARED_FLOOD_DETECTOR.reset();
    }

    // --- Security Audit Log ---

    public static void logEvent(String category, String message) {
        if (message == null || message.trim().isEmpty()) return;
        long now = System.currentTimeMillis();
        String ts;
        synchronized (AUDIT_LOG) {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.US);
            ts = sdf.format(new Date(now));
            AUDIT_LOG.addFirst(new SecurityEvent(now, ts, category != null ? category : "INFO", message.trim()));
            while (AUDIT_LOG.size() > MAX_AUDIT_LOG_ENTRIES) {
                AUDIT_LOG.removeLast();
            }
        }
    }

    public static List<SecurityEvent> getAuditLog() {
        synchronized (AUDIT_LOG) {
            return new ArrayList<>(AUDIT_LOG);
        }
    }

    public static int getAuditLogCount() {
        synchronized (AUDIT_LOG) {
            return AUDIT_LOG.size();
        }
    }

    public static String formatAuditLog() {
        List<SecurityEvent> events = getAuditLog();
        if (events.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(events.get(i).formatLine());
        }
        return sb.toString();
    }

    public static void clearAuditLog() {
        synchronized (AUDIT_LOG) {
            AUDIT_LOG.clear();
        }
    }
}
