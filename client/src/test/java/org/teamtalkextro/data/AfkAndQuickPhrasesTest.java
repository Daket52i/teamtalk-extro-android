package org.teamtalkextro.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.Test;

public class AfkAndQuickPhrasesTest {

    @Test
    public void testAfkAutoReplyThrottlingAndMissedPmTracking() {
        long fiveMinutes = 5L * 60L * 1000L;
        AfkAndQuickPhrases.AfkEngine engine = new AfkAndQuickPhrases.AfkEngine(fiveMinutes);
        engine.setMessage("Отошёл за чаем");
        engine.setEnabled(true);

        long t0 = 1_000_000L;

        // First PM from user 10 -> triggers auto-reply with [AFK] prefix
        String reply1 = engine.onIncomingPrivateMessage(10, 1, "Лена", "Привет, ты тут?", t0);
        assertEquals("[AFK] Отошёл за чаем", reply1);
        assertEquals(1, engine.getMissedCount());

        // Second PM from user 10 within 5 minutes -> throttled (null), but still recorded in missed PMs
        String reply2 = engine.onIncomingPrivateMessage(10, 1, "Лена", "Ответь когда вернёшься", t0 + 60_000L);
        assertNull(reply2);
        assertEquals(2, engine.getMissedCount());

        // PM from a different user 20 -> triggers auto-reply independently
        String replyUser20 = engine.onIncomingPrivateMessage(20, 1, "Иван", "Пойдём в караоке?", t0 + 70_000L);
        assertEquals("[AFK] Отошёл за чаем", replyUser20);
        assertEquals(3, engine.getMissedCount());

        // PM from user 10 AFTER 5 minutes -> triggers auto-reply again
        String reply3 = engine.onIncomingPrivateMessage(10, 1, "Лена", "Всё ещё нет?", t0 + fiveMinutes + 1000L);
        assertEquals("[AFK] Отошёл за чаем", reply3);
        assertEquals(4, engine.getMissedCount());

        // Draining missed PMs returns all 4 and clears state
        List<AfkAndQuickPhrases.MissedPm> drained = engine.drainMissedPms();
        assertEquals(4, drained.size());
        assertEquals("Лена", drained.get(0).senderName);
        assertEquals("Иван", drained.get(2).senderName);
        assertTrue(drained.get(0).formatLine().contains("Лена"));
        assertEquals(0, engine.getMissedCount());
    }

    @Test
    public void testAfkLoopPreventionAndSelfIgnore() {
        AfkAndQuickPhrases.AfkEngine engine = new AfkAndQuickPhrases.AfkEngine();
        engine.setMessage("Busy");
        engine.setEnabled(true);

        long t = 5_000_000L;

        // Self message must be ignored
        assertNull(engine.onIncomingPrivateMessage(7, 7, "Me", "test", t));
        assertEquals(0, engine.getMissedCount());

        // Message starting with [AFK] or [afk] must NOT trigger reply or loop
        assertNull(engine.onIncomingPrivateMessage(42, 7, "OtherBot", "[AFK] I am also away", t + 1000L));
        assertNull(engine.onIncomingPrivateMessage(43, 7, "OtherUser", "  [afk] away too", t + 2000L));
        assertEquals(0, engine.getMissedCount());

        // Disabled engine never replies
        engine.setEnabled(false);
        assertNull(engine.onIncomingPrivateMessage(99, 7, "Alice", "Hello!", t + 3000L));
        assertEquals(0, engine.getMissedCount());
    }

    @Test
    public void testAfkCustomPrefixNotDuplicated() {
        AfkAndQuickPhrases.AfkEngine engine = new AfkAndQuickPhrases.AfkEngine();
        engine.setMessage("[AFK] Already prefixed");
        engine.setEnabled(true);

        String reply = engine.onIncomingPrivateMessage(55, 1, "User", "Hi", 10_000L);
        assertEquals("[AFK] Already prefixed", reply);
    }

    @Test
    public void testQuickPhrasesCrudAndJsonRoundTrip() {
        List<String> defaultsRu = AfkAndQuickPhrases.getDefaultPhrases(new Locale("ru"));
        assertFalse(defaultsRu.isEmpty());

        List<String> list = Arrays.asList("Привет!", "Браво!");
        List<String> afterAdd = AfkAndQuickPhrases.addPhraseToList(list, "  Отличный трек!  ");
        assertEquals(3, afterAdd.size());
        assertEquals("Отличный трек!", afterAdd.get(2));

        // Duplicate and blank phrases are ignored
        List<String> afterDup = AfkAndQuickPhrases.addPhraseToList(afterAdd, "Привет!");
        assertEquals(3, afterDup.size());
        List<String> afterBlank = AfkAndQuickPhrases.addPhraseToList(afterAdd, "   ");
        assertEquals(3, afterBlank.size());

        // Remove by index
        List<String> afterRemove = AfkAndQuickPhrases.removePhraseFromList(afterAdd, 1);
        assertEquals(2, afterRemove.size());
        assertEquals("Привет!", afterRemove.get(0));
        assertEquals("Отличный трек!", afterRemove.get(1));

        // JSON serialize / parse round-trip
        String json = AfkAndQuickPhrases.serializePhrasesJson(afterRemove);
        assertNotNull(json);
        List<String> parsed = AfkAndQuickPhrases.parsePhrasesJson(json);
        assertEquals(afterRemove, parsed);

        // Corrupt JSON safely returns empty list
        assertTrue(AfkAndQuickPhrases.parsePhrasesJson("{not-an-array").isEmpty());
    }
}
