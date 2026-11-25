package org.teamtalkextro.data;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Test;

public class SettingsBackupManagerCryptoTest {

    @Test
    public void testBase64MatchesJavaStandardRfc4648() {
        byte[] sample = "TeamTalk Extro v5.33.0 — Привет мир! \u0000\u0001\u00FF".getBytes(StandardCharsets.UTF_8);
        String ours = SettingsBackupManager.base64Encode(sample);
        String jdk = Base64.getEncoder().encodeToString(sample);
        assertEquals(jdk, ours);
        assertArrayEquals(sample, SettingsBackupManager.base64Decode(ours));
    }

    @Test
    public void testEncryptDecryptRoundTripAndEnvelopeSpec() throws Exception {
        String plainJson = "{\n"
                + "  \"format\": \"teamtalk-extro-backup\",\n"
                + "  \"version\": 1,\n"
                + "  \"platform\": \"android\",\n"
                + "  \"servers\": [{\"name\": \"Тест\", \"host\": \"example.org\", \"tcp_port\": 10333}],\n"
                + "  \"user_notes\": {\"ivan\": \"Радио-ведущий\"},\n"
                + "  \"shield_settings\": {\"safe_links\": true, \"anti_flood_zalgo\": true}\n"
                + "}";
        String password = "SuperSecret#2026!Пароль";

        assertFalse(SettingsBackupManager.isEncryptedBackupJson(plainJson));

        String encryptedJson = SettingsBackupManager.encryptBackupJson(plainJson, password);
        assertTrue(SettingsBackupManager.isEncryptedBackupJson(encryptedJson));
        assertFalse(encryptedJson.contains("Радио-ведущий"));

        JsonObject env = JsonParser.parseString(encryptedJson).getAsJsonObject();
        assertEquals("teamtalk-extro-backup-encrypted", env.get("format").getAsString());
        assertEquals(2, env.get("version").getAsInt());
        assertEquals("PBKDF2-HMAC-SHA256", env.get("kdf").getAsString());
        assertEquals(100000, env.get("iterations").getAsInt());
        assertEquals("AES-256-GCM", env.get("cipher").getAsString());

        byte[] salt = Base64.getDecoder().decode(env.get("salt_b64").getAsString());
        byte[] iv = Base64.getDecoder().decode(env.get("iv_b64").getAsString());
        byte[] tag = Base64.getDecoder().decode(env.get("tag_b64").getAsString());
        byte[] ct = Base64.getDecoder().decode(env.get("ciphertext_b64").getAsString());

        assertEquals(16, salt.length);
        assertEquals(12, iv.length);
        assertEquals(16, tag.length);
        assertTrue(ct.length > 0);

        String decrypted = SettingsBackupManager.decryptBackupJson(encryptedJson, password);
        assertEquals(plainJson, decrypted);

        // Encrypting twice must produce distinct random salt/iv/ciphertext
        String encryptedJson2 = SettingsBackupManager.encryptBackupJson(plainJson, password);
        assertNotEquals(encryptedJson, encryptedJson2);
    }

    @Test
    public void testWrongPasswordThrowsCleanly() throws Exception {
        String plain = "{\"format\":\"teamtalk-extro-backup\",\"version\":1}";
        String encrypted = SettingsBackupManager.encryptBackupJson(plain, "correct-password");

        try {
            SettingsBackupManager.decryptBackupJson(encrypted, "wrong-password");
            fail("Expected WrongPasswordException for wrong password");
        } catch (SettingsBackupManager.WrongPasswordException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        }
    }

    @Test
    public void testTamperedTagThrowsCleanly() throws Exception {
        String plain = "{\"format\":\"teamtalk-extro-backup\",\"version\":1}";
        String encrypted = SettingsBackupManager.encryptBackupJson(plain, "pass123");

        JsonObject env = JsonParser.parseString(encrypted).getAsJsonObject();
        byte[] tag = Base64.getDecoder().decode(env.get("tag_b64").getAsString());
        tag[0] ^= (byte) 0xFF;
        env.addProperty("tag_b64", Base64.getEncoder().encodeToString(tag));

        try {
            SettingsBackupManager.decryptBackupJson(env.toString(), "pass123");
            fail("Expected WrongPasswordException for tampered GCM tag");
        } catch (SettingsBackupManager.WrongPasswordException expected) {
            // Expected GCM auth tag failure
        }
    }
}
