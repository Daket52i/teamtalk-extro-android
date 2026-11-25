package org.teamtalkextro.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ExtroShieldTest {

    @Test
    public void testSafeLinkGuardDetectsThreatsAndAllowsNormalUrls() {
        // IP-loggers
        ExtroShield.LinkScanResult r1 = ExtroShield.scanTextLinks("Check this photo https://grabify.link/XYZ987");
        assertTrue(r1.suspicious);
        assertTrue(r1.threats.contains(ExtroShield.ThreatType.IP_LOGGER));

        ExtroShield.LinkScanResult r2 = ExtroShield.scanTextLinks("go to iplogger.org/abc or https://2no.co/test");
        assertTrue(r2.suspicious);
        assertTrue(r2.threats.contains(ExtroShield.ThreatType.IP_LOGGER));

        // Punycode homographs
        ExtroShield.LinkScanResult r3 = ExtroShield.scanTextLinks("Login at https://xn--80ak6aa92e.com/account");
        assertTrue(r3.suspicious);
        assertTrue(r3.threats.contains(ExtroShield.ThreatType.PUNYCODE));

        // Direct APK / EXE links
        ExtroShield.LinkScanResult r4 = ExtroShield.scanTextLinks("Install https://example.org/files/update.apk?dl=1");
        assertTrue(r4.suspicious);
        assertTrue(r4.threats.contains(ExtroShield.ThreatType.EXECUTABLE));

        ExtroShield.LinkScanResult r5 = ExtroShield.scanTextLinks("Run https://cdn.example.com/setup.EXE");
        assertTrue(r5.suspicious);
        assertTrue(r5.threats.contains(ExtroShield.ThreatType.EXECUTABLE));

        // URL shorteners
        ExtroShield.LinkScanResult r6 = ExtroShield.scanTextLinks("Short link: https://bit.ly/3aBcDeF and https://clck.ru/999");
        assertTrue(r6.suspicious);
        assertTrue(r6.threats.contains(ExtroShield.ThreatType.URL_SHORTENER));

        // Raw IP HTTP URLs
        ExtroShield.LinkScanResult r7 = ExtroShield.scanTextLinks("Connect http://192.168.1.10:8080/stream");
        assertTrue(r7.suspicious);
        assertTrue(r7.threats.contains(ExtroShield.ThreatType.RAW_IP_HTTP));

        // Safe normal URLs must NOT trigger false positives
        ExtroShield.LinkScanResult safe1 = ExtroShield.scanTextLinks("Listen here: https://github.com/Daket52i/TeamTalk5");
        assertFalse(safe1.suspicious);

        ExtroShield.LinkScanResult safe2 = ExtroShield.scanTextLinks("Привет! Скачай трек с https://solneshko.radio-dasha.msk.ru/track.mp3");
        assertFalse(safe2.suspicious);

        // Annotation helper
        String annotated = ExtroShield.annotateMessageIfSuspicious(null, "click https://grabify.link/123", r1);
        assertTrue(annotated.startsWith("[⚠️ Осторожно:"));
        assertTrue(annotated.contains("grabify.link/123"));
    }

    @Test
    public void testZalgoAndUnicodeRloSanitizer() {
        // RLO U+202E spoof
        String spoof = "photo_\u202Egnp.exe";
        ExtroShield.SanitizeResult rloRes = ExtroShield.sanitizeUnicode(spoof);
        assertTrue(rloRes.modified);
        assertTrue(rloRes.rloDetected);
        assertFalse(rloRes.zalgoDetected);
        assertEquals("photo_gnp.exe", rloRes.text);

        // Zalgo combining-mark attack (>6 consecutive combining diacritics)
        StringBuilder zalgo = new StringBuilder("H");
        for (int i = 0; i < 18; i++) {
            zalgo.append('\u0301'); // combining acute accent
        }
        zalgo.append("i");
        ExtroShield.SanitizeResult zalgoRes = ExtroShield.sanitizeUnicode(zalgo.toString());
        assertTrue(zalgoRes.modified);
        assertTrue(zalgoRes.zalgoDetected);
        assertFalse(zalgoRes.rloDetected);
        assertEquals(4, zalgoRes.text.length()); // 'H' + 2 kept marks + 'i'

        // Normal text (Russian + mild 2 combining marks) must stay untouched
        String normal = "Привет, TeamTalk Extro! e\u0301\u0302";
        ExtroShield.SanitizeResult normalRes = ExtroShield.sanitizeUnicode(normal);
        assertFalse(normalRes.modified);
        assertEquals(normal, normalRes.text);
    }

    @Test
    public void testAntiFloodBurstLimiter() {
        ExtroShield.FloodDetector detector = new ExtroShield.FloodDetector(6, 3000L);
        long t0 = 10_000L;

        // First 6 messages in 3 seconds are allowed
        for (int i = 0; i < 6; i++) {
            assertEquals(ExtroShield.FloodVerdict.ALLOWED, detector.check(42, t0 + i * 200L));
        }
        // 7th message within 3 seconds triggers BLOCKED_FIRST
        assertEquals(ExtroShield.FloodVerdict.BLOCKED_FIRST, detector.check(42, t0 + 1500L));
        // 8th message within same burst triggers BLOCKED_REPEAT
        assertEquals(ExtroShield.FloodVerdict.BLOCKED_REPEAT, detector.check(42, t0 + 1600L));

        // Another user is unaffected
        assertEquals(ExtroShield.FloodVerdict.ALLOWED, detector.check(99, t0 + 1650L));

        // After 3 seconds window passes, user 42 is allowed again
        assertEquals(ExtroShield.FloodVerdict.ALLOWED, detector.check(42, t0 + 5000L));
    }

    @Test
    public void testAuditLogRingBufferLimit() {
        ExtroShield.clearAuditLog();
        for (int i = 0; i < 230; i++) {
            ExtroShield.logEvent(ExtroShield.CAT_LINK, "Event #" + i);
        }
        assertEquals(ExtroShield.MAX_AUDIT_LOG_ENTRIES, ExtroShield.getAuditLogCount());
        assertTrue(ExtroShield.getAuditLog().get(0).message.contains("#229"));
        ExtroShield.clearAuditLog();
        assertEquals(0, ExtroShield.getAuditLogCount());
    }
}
