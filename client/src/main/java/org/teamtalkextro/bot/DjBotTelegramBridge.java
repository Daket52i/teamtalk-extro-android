package org.teamtalkextro.bot;

import android.util.Log;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pure-Java Telegram Bot API long-poller bridge for Embedded DJ Bot.
 * Provides:
 * - Join/Leave server notifications to notify chat / owner
 * - Two-way TeamTalk PM relay (reply to forwarded TG message -> sends TT PM back)
 * - Media relay (audio / voice / video / audio-video documents -> plays in TT channel)
 * - Full Telegram command set + inline control keyboard
 */
public final class DjBotTelegramBridge {

    private static final String TAG = "DjBotTgBridge";
    private static final String TG_API_BASE = "https://api.telegram.org/bot";
    private static final String TG_FILE_BASE = "https://api.telegram.org/file/bot";
    private static final int POLL_TIMEOUT_SEC = 25;
    private static final int MAX_TRACKED_REPLIES = 200;

    public static final class BotIdentity {
        public final long id;
        public final String username;
        public final String firstName;

        public BotIdentity(long id, String username, String firstName) {
            this.id = id;
            this.username = username != null ? username : "";
            this.firstName = firstName != null ? firstName : "";
        }

        public String formatDisplay() {
            if (!username.isEmpty() && !firstName.isEmpty()) {
                return "@" + username + " (" + firstName + ")";
            }
            if (!username.isEmpty()) return "@" + username;
            return !firstName.isEmpty() ? firstName : ("id=" + id);
        }
    }

    public interface HostCallback {
        BotConfig getConfig();
        void log(String message);
        void onBridgeStateChanged();
        String handleTelegramCommand(String cmd, String arg, boolean isAdmin, String senderLabel);
        String getOnlineSummary();
        String getStatusSummary();
        void sendPrivateToTeamTalkUser(int ttUserId, String text, String tgSenderName);
        void playTelegramMediaFile(File file, String displayTitle, String senderLabel);
        String kickByQuery(String query);
        String banByQuery(String query);
        String broadcastServerText(String text);
        void togglePauseFromTelegram();
        void nextFromTelegram();
        void stopFromTelegram();
        void playPopFromTelegram();
    }

    private final File inboxDir;
    private final HostCallback host;
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Extro-DjBotTgSend");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, Integer> forwardedMsgToTtUser = Collections.synchronizedMap(
            new LinkedHashMap<Long, Integer>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Integer> eldest) {
                    return size() > MAX_TRACKED_REPLIES;
                }
            });

    private volatile boolean running = false;
    private volatile boolean connected = false;
    private volatile String activeToken = "";
    private volatile String botUsername = "";
    private volatile String statusText = "";
    private volatile Thread pollThread = null;
    private volatile long lastUpdateId = 0L;

    public DjBotTelegramBridge(File cacheDir, HostCallback host) {
        this.inboxDir = new File(cacheDir, "djbot_tg_inbox");
        this.host = host;
    }

    public static BotIdentity verifyBotToken(String token) throws IOException {
        if (token == null || token.trim().isEmpty()) {
            throw new IOException("Не указан токен Telegram-бота");
        }
        JsonObject res = callApi(token.trim(), "getMe", null, 10_000, 12_000);
        if (res == null || !optBool(res, "ok", false)) {
            String desc = res != null ? optStr(res, "description", "Ошибка Telegram API") : "Нет ответа";
            throw new IOException(desc);
        }
        JsonObject me = optObj(res, "result");
        if (me == null) {
            throw new IOException("Пустой ответ getMe");
        }
        return new BotIdentity(
                optLong(me, "id", 0L),
                optStr(me, "username", ""),
                optStr(me, "first_name", ""));
    }

    public synchronized void start(String token) {
        stop();
        String clean = token != null ? token.trim() : "";
        if (clean.isEmpty()) {
            this.statusText = "";
            return;
        }
        this.activeToken = clean;
        this.running = true;
        this.connected = false;
        this.botUsername = "";
        this.statusText = "Подключение к Telegram…";
        notifyHostState();

        Thread t = new Thread(() -> runPollLoop(clean), "Extro-DjBotTgPoll");
        t.setDaemon(true);
        this.pollThread = t;
        t.start();
    }

    public synchronized void stop() {
        this.running = false;
        this.connected = false;
        this.activeToken = "";
        this.botUsername = "";
        this.statusText = "";
        Thread t = this.pollThread;
        this.pollThread = null;
        if (t != null) {
            t.interrupt();
        }
        notifyHostState();
    }

    public boolean isRunning() {
        return this.running;
    }

    public boolean isConnected() {
        return this.running && this.connected;
    }

    public String getBotUsername() {
        return this.botUsername;
    }

    public String getStatusText() {
        return this.statusText;
    }

    public void notifyJoinLeave(String nickname, String username, String channelName, boolean joined) {
        BotConfig cfg = host.getConfig();
        if (!running || cfg == null || !cfg.telegramNotifyJoinLeave) return;
        String chatId = cfg.getEffectiveTelegramNotifyChatId();
        String token = this.activeToken;
        if (chatId.isEmpty() || token.isEmpty()) return;

        String srvName = cfg.getEffectiveTelegramServerName();
        String who = (nickname != null && !nickname.trim().isEmpty())
                ? nickname.trim()
                : ((username != null && !username.trim().isEmpty()) ? username.trim() : "Гость");
        String userTag = (username != null && !username.trim().isEmpty() && !username.equalsIgnoreCase(who))
                ? (" (" + username.trim() + ")") : "";
        String chSuffix = (channelName != null && !channelName.trim().isEmpty())
                ? (" → " + channelName.trim()) : "";
        String text = (joined ? "🟢 [" : "🔴 [") + srvName + "] "
                + who + userTag + (joined ? " вошёл" : " вышел") + chSuffix;
        sendAsync(token, chatId, text, null, -1);
    }

    public void forwardPrivateMessage(int ttUserId, String nickname, String username, String message) {
        BotConfig cfg = host.getConfig();
        if (!running || cfg == null || !cfg.telegramForwardPrivateMsgs) return;
        String chatId = cfg.getEffectiveTelegramNotifyChatId();
        String token = this.activeToken;
        if (chatId.isEmpty() || token.isEmpty()) return;

        String srvName = cfg.getEffectiveTelegramServerName();
        String who = (nickname != null && !nickname.trim().isEmpty()) ? nickname.trim() : ("#" + ttUserId);
        String loginPart = (username != null && !username.trim().isEmpty()) ? (" (" + username.trim() + ")") : "";
        String text = "💬 ЛС [" + srvName + "] от " + who + loginPart + ":\n" + message
                + "\n\n↩️ Ответьте (Reply) на это сообщение, чтобы отправить ответ в TeamTalk.";
        sendAsync(token, chatId, text, null, ttUserId);
    }

    private void runPollLoop(String token) {
        try {
            BotIdentity me = verifyBotToken(token);
            if (!running) return;
            this.botUsername = me.username;
            this.connected = true;
            this.statusText = "@" + me.username;
            host.log("✈️ Telegram-бот подключён: " + me.formatDisplay());
            notifyHostState();
            registerBotCommands(token);
        } catch (Throwable t) {
            if (!running) return;
            this.connected = false;
            this.statusText = "Ошибка TG: " + safeMsg(t);
            host.log("⚠️ Ошибка Telegram-бота: " + safeMsg(t));
            notifyHostState();
        }

        while (running && token.equals(this.activeToken)) {
            try {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("timeout", String.valueOf(POLL_TIMEOUT_SEC));
                params.put("allowed_updates", "[\"message\",\"callback_query\"]");
                if (lastUpdateId > 0) {
                    params.put("offset", String.valueOf(lastUpdateId + 1));
                }
                JsonObject resp = callApi(token, "getUpdates", params, 10_000, (POLL_TIMEOUT_SEC + 10) * 1000);
                if (resp != null && optBool(resp, "ok", false)) {
                    if (!this.connected) {
                        this.connected = true;
                        this.statusText = !this.botUsername.isEmpty() ? ("@" + this.botUsername) : "OK";
                        notifyHostState();
                    }
                    JsonArray updates = optArr(resp, "result");
                    if (updates != null) {
                        for (int i = 0; i < updates.size() && running; i++) {
                            JsonElement el = updates.get(i);
                            if (el == null || !el.isJsonObject()) continue;
                            JsonObject upd = el.getAsJsonObject();
                            long uid = optLong(upd, "update_id", 0L);
                            if (uid > lastUpdateId) lastUpdateId = uid;
                            processUpdate(token, upd);
                        }
                    }
                } else {
                    sleepQuiet(3000L);
                }
            } catch (Throwable t) {
                if (!running) break;
                Log.w(TAG, "TG poll error: " + t.getMessage());
                sleepQuiet(4000L);
            }
        }
    }

    private void registerBotCommands(String token) {
        try {
            String cmds = "["
                    + "{\"command\":\"start\",\"description\":\"Панель управления DJ Bot\"},"
                    + "{\"command\":\"online\",\"description\":\"Кто сейчас на сервере\"},"
                    + "{\"command\":\"now\",\"description\":\"Что сейчас в эфире\"},"
                    + "{\"command\":\"status\",\"description\":\"Статус бота и канала\"},"
                    + "{\"command\":\"play\",\"description\":\"Найти и включить трек / URL\"},"
                    + "{\"command\":\"ym\",\"description\":\"Яндекс.Музыка (поиск / chart / likes)\"},"
                    + "{\"command\":\"radio\",\"description\":\"FM-радиостанции\"},"
                    + "{\"command\":\"voln\",\"description\":\"Станции Волнореза\"},"
                    + "{\"command\":\"tv\",\"description\":\"ТВ-каналы (видео + звук + EPG)\"},"
                    + "{\"command\":\"pop\",\"description\":\"Случайный русский хит\"},"
                    + "{\"command\":\"fav\",\"description\":\"Избранное\"},"
                    + "{\"command\":\"pause\",\"description\":\"Пауза / продолжить\"},"
                    + "{\"command\":\"next\",\"description\":\"Следующий трек\"},"
                    + "{\"command\":\"stop\",\"description\":\"Остановить воспроизведение\"},"
                    + "{\"command\":\"v\",\"description\":\"Громкость 0-100\"},"
                    + "{\"command\":\"help\",\"description\":\"Справка по командам\"}"
                    + "]";
            Map<String, String> params = new LinkedHashMap<>();
            params.put("commands", cmds);
            callApi(token, "setMyCommands", params, 8000, 10000);
        } catch (Throwable ignored) {
        }
    }

    private void processUpdate(String token, JsonObject upd) {
        JsonObject cq = optObj(upd, "callback_query");
        if (cq != null) {
            handleCallbackQuery(token, cq);
            return;
        }
        JsonObject msg = optObj(upd, "message");
        if (msg != null) {
            handleMessage(token, msg);
        }
    }

    private void handleCallbackQuery(String token, JsonObject cq) {
        String cqId = optStr(cq, "id", "");
        String data = optStr(cq, "data", "");
        JsonObject from = optObj(cq, "from");
        long fromId = optLong(from, "id", 0L);
        JsonObject msg = optObj(cq, "message");
        JsonObject chat = optObj(msg, "chat");
        String chatId = chat != null ? String.valueOf(optLong(chat, "id", 0L)) : "";

        BotConfig cfg = host.getConfig();
        if (cfg != null && !isUserAllowed(cfg, fromId, chatId)) {
            answerCallback(token, cqId, "Нет доступа", true);
            return;
        }

        String toast = "OK";
        switch (data) {
            case "bot:pause":
                host.togglePauseFromTelegram();
                toast = "⏯ Пауза / Продолжить";
                break;
            case "bot:next":
                host.nextFromTelegram();
                toast = "⏭ Следующий трек";
                break;
            case "bot:stop":
                host.stopFromTelegram();
                toast = "⏹ Остановлено";
                break;
            case "bot:pop":
                host.playPopFromTelegram();
                toast = "🎲 Подбираю случайный хит…";
                break;
            case "bot:online":
                toast = "Обновлено";
                if (!chatId.isEmpty() && !"0".equals(chatId)) {
                    sendAsync(token, chatId, host.getOnlineSummary(), buildControlKeyboardJson(), -1);
                }
                break;
            default:
                break;
        }
        answerCallback(token, cqId, toast, false);
    }

    private void handleMessage(String token, JsonObject msg) {
        JsonObject chat = optObj(msg, "chat");
        if (chat == null) return;
        String chatId = String.valueOf(optLong(chat, "id", 0L));
        JsonObject from = optObj(msg, "from");
        long fromId = optLong(from, "id", 0L);
        String senderLabel = buildSenderLabel(from);

        BotConfig cfg = host.getConfig();
        if (cfg != null && !isUserAllowed(cfg, fromId, chatId)) {
            return;
        }
        boolean isAdmin = cfg == null || cfg.isTelegramAdmin(fromId);

        // 1) Check if user replied to a forwarded TeamTalk PM
        JsonObject replyTo = optObj(msg, "reply_to_message");
        String text = optStr(msg, "text", "").trim();
        if (replyTo != null && !text.isEmpty() && !text.startsWith("/")) {
            long repliedId = optLong(replyTo, "message_id", 0L);
            Integer ttUserId = forwardedMsgToTtUser.get(repliedId);
            if (ttUserId != null && ttUserId > 0) {
                host.sendPrivateToTeamTalkUser(ttUserId, text, senderLabel);
                sendAsync(token, chatId, "✅ Ответ отправлен пользователю TeamTalk (#" + ttUserId + ").", null, -1);
                return;
            }
        }

        // 2) Check media relay (audio / voice / video / audio-video document)
        if (cfg == null || cfg.telegramRelayMediaFiles) {
            MediaAttachment att = extractMediaAttachment(msg);
            if (att != null) {
                sendAsync(token, chatId, "⬇️ Загружаю медиафайл для эфира: " + att.displayTitle + "…", null, -1);
                downloadAndPlayMedia(token, chatId, att, senderLabel);
                return;
            }
        }

        if (text.isEmpty()) return;

        // 3) Parse Telegram command (/cmd@BotName arg)
        String clean = text.startsWith("/") ? text.substring(1).trim() : text;
        if (clean.isEmpty()) return;
        String[] parts = clean.split("\\s+", 2);
        String rawCmd = parts[0].toLowerCase(Locale.ROOT);
        int atIdx = rawCmd.indexOf('@');
        String cmd = atIdx > 0 ? rawCmd.substring(0, atIdx) : rawCmd;
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (cmd) {
            case "start":
            case "help":
                sendAsync(token, chatId, buildHelpText(cfg, isAdmin), buildControlKeyboardJson(), -1);
                break;

            case "online":
                sendAsync(token, chatId, host.getOnlineSummary(), buildControlKeyboardJson(), -1);
                break;

            case "status":
                sendAsync(token, chatId, host.getStatusSummary(), buildControlKeyboardJson(), -1);
                break;

            case "kick":
                if (!isAdmin) {
                    sendAsync(token, chatId, "⛔ Только для администраторов Telegram-бота.", null, -1);
                } else if (arg.isEmpty()) {
                    sendAsync(token, chatId, "Использование: /kick <ник | логин | ID>", null, -1);
                } else {
                    sendAsync(token, chatId, host.kickByQuery(arg), null, -1);
                }
                break;

            case "ban":
                if (!isAdmin) {
                    sendAsync(token, chatId, "⛔ Только для администраторов Telegram-бота.", null, -1);
                } else if (arg.isEmpty()) {
                    sendAsync(token, chatId, "Использование: /ban <ник | логин | ID>", null, -1);
                } else {
                    sendAsync(token, chatId, host.banByQuery(arg), null, -1);
                }
                break;

            case "net":
                if (!isAdmin) {
                    sendAsync(token, chatId, "⛔ Только для администраторов Telegram-бота.", null, -1);
                } else if (arg.isEmpty()) {
                    sendAsync(token, chatId, "Использование: /net <сообщение на весь сервер>", null, -1);
                } else {
                    sendAsync(token, chatId, host.broadcastServerText(arg), null, -1);
                }
                break;

            default: {
                String response = host.handleTelegramCommand(cmd, arg, isAdmin, senderLabel);
                if (response != null && !response.trim().isEmpty()) {
                    String kb = ("now".equals(cmd) || "p".equals(cmd) || "play".equals(cmd)
                            || "ym".equals(cmd) || "radio".equals(cmd) || "voln".equals(cmd)
                            || "tv".equals(cmd) || "pop".equals(cmd))
                            ? buildControlKeyboardJson() : null;
                    sendAsync(token, chatId, response, kb, -1);
                }
                break;
            }
        }
    }

    private void downloadAndPlayMedia(String token, String chatId, MediaAttachment att, String senderLabel) {
        sendExecutor.execute(() -> {
            try {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("file_id", att.fileId);
                JsonObject resp = callApi(token, "getFile", params, 10_000, 15_000);
                if (resp == null || !optBool(resp, "ok", false)) {
                    sendSync(token, chatId, "⚠️ Не удалось получить файл из Telegram.", null, -1);
                    return;
                }
                JsonObject res = optObj(resp, "result");
                String filePath = optStr(res, "file_path", "");
                if (filePath.isEmpty()) {
                    sendSync(token, chatId, "⚠️ Telegram не вернул file_path (файл > 20 МБ?).", null, -1);
                    return;
                }
                if (!inboxDir.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    inboxDir.mkdirs();
                }
                cleanOldInboxFiles();
                String ext = extractExtension(att.fileName, extractExtension(filePath, att.defaultExt));
                File localFile = new File(inboxDir, "tg_" + System.currentTimeMillis() + ext);
                downloadFile(TG_FILE_BASE + token + "/" + filePath, localFile);
                host.playTelegramMediaFile(localFile, att.displayTitle, senderLabel);
                sendSync(token, chatId, "▶ Включаю в эфире TeamTalk: " + att.displayTitle, buildControlKeyboardJson(), -1);
            } catch (Throwable t) {
                sendSync(token, chatId, "⚠️ Ошибка загрузки файла из Telegram: " + safeMsg(t), null, -1);
            }
        });
    }

    private void cleanOldInboxFiles() {
        try {
            File[] files = inboxDir.listFiles();
            if (files != null && files.length > 10) {
                for (File f : files) {
                    if (System.currentTimeMillis() - f.lastModified() > 30 * 60 * 1000L) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static final class MediaAttachment {
        final String fileId;
        final String fileName;
        final String displayTitle;
        final String defaultExt;

        MediaAttachment(String fileId, String fileName, String displayTitle, String defaultExt) {
            this.fileId = fileId;
            this.fileName = fileName;
            this.displayTitle = displayTitle;
            this.defaultExt = defaultExt;
        }
    }

    private static MediaAttachment extractMediaAttachment(JsonObject msg) {
        JsonObject audio = optObj(msg, "audio");
        if (audio != null) {
            String fid = optStr(audio, "file_id", "");
            String fn = optStr(audio, "file_name", "audio.mp3");
            String perf = optStr(audio, "performer", "");
            String title = optStr(audio, "title", "");
            String disp = (!perf.isEmpty() && !title.isEmpty()) ? (perf + " — " + title)
                    : (!title.isEmpty() ? title : fn);
            if (!fid.isEmpty()) return new MediaAttachment(fid, fn, disp, ".mp3");
        }
        JsonObject voice = optObj(msg, "voice");
        if (voice != null) {
            String fid = optStr(voice, "file_id", "");
            if (!fid.isEmpty()) return new MediaAttachment(fid, "voice.ogg", "Голосовое из Telegram", ".ogg");
        }
        JsonObject video = optObj(msg, "video");
        if (video != null) {
            String fid = optStr(video, "file_id", "");
            String fn = optStr(video, "file_name", "video.mp4");
            String caption = optStr(msg, "caption", "");
            String disp = !caption.isEmpty() ? caption : fn;
            if (!fid.isEmpty()) return new MediaAttachment(fid, fn, disp, ".mp4");
        }
        JsonObject doc = optObj(msg, "document");
        if (doc != null) {
            String fid = optStr(doc, "file_id", "");
            String mime = optStr(doc, "mime_type", "").toLowerCase(Locale.ROOT);
            String fn = optStr(doc, "file_name", "");
            String lowFn = fn.toLowerCase(Locale.ROOT);
            boolean isMedia = mime.startsWith("audio/") || mime.startsWith("video/")
                    || lowFn.endsWith(".mp3") || lowFn.endsWith(".m4a") || lowFn.endsWith(".ogg")
                    || lowFn.endsWith(".opus") || lowFn.endsWith(".wav") || lowFn.endsWith(".flac")
                    || lowFn.endsWith(".mp4") || lowFn.endsWith(".mkv") || lowFn.endsWith(".webm");
            if (isMedia && !fid.isEmpty()) {
                return new MediaAttachment(fid, fn, !fn.isEmpty() ? fn : "Файл из Telegram", ".mp3");
            }
        }
        return null;
    }

    private static boolean isUserAllowed(BotConfig cfg, long fromId, String chatId) {
        boolean hasAnyConstraint = !isBlank(cfg.telegramOwnerUserId)
                || !isBlank(cfg.telegramAdminIds)
                || !isBlank(cfg.telegramNotifyChatId);
        if (!hasAnyConstraint) return true;
        if (cfg.isTelegramAdmin(fromId)) return true;
        String notify = cfg.getEffectiveTelegramNotifyChatId();
        return !notify.isEmpty() && notify.equals(chatId);
    }

    private static String buildHelpText(BotConfig cfg, boolean isAdmin) {
        String srv = cfg != null ? cfg.getEffectiveTelegramServerName() : "TeamTalk";
        StringBuilder sb = new StringBuilder();
        sb.append("🎧 DJ Bot Extro — ").append(srv).append("\n\n");
        sb.append("👥 /online — кто на сервере | /status — статус бота\n");
        sb.append("🎵 /play <запрос|URL> (или /p) — найти и включить\n");
        sb.append("🟡 /ym <запрос | chart | likes> — Яндекс.Музыка\n");
        sb.append("📻 /radio [№|поиск] — FM-радио | 🌊 /voln — Волнорез\n");
        sb.append("📺 /tv [№|поиск|epg] — ТВ (видео + звук) | 🎲 /pop — хит\n");
        sb.append("⭐ /fav — избранное | ℹ️ /now — сейчас в эфире\n");
        sb.append("⏯ /pause | ⏭ /next | ⏮ /prev | ⏹ /stop | 🔊 /v <0-100> | /sv <сервис>\n");
        sb.append("📎 Отправьте аудио, голосовое или видео — бот включит его прямо в канале!");
        if (isAdmin) {
            sb.append("\n\n🛡️ Админ-команды:\n");
            sb.append("/kick <ник|ID> | /ban <ник|ID> | /net <текст на весь сервер>\n");
            sb.append("/welcome <текст> | /settings | /set <ключ> <значение>");
        }
        return sb.toString();
    }

    private static String buildControlKeyboardJson() {
        return "{\"inline_keyboard\":["
                + "[{\"text\":\"⏯ Пауза\",\"callback_data\":\"bot:pause\"},"
                + "{\"text\":\"⏭ Далее\",\"callback_data\":\"bot:next\"},"
                + "{\"text\":\"⏹ Стоп\",\"callback_data\":\"bot:stop\"}],"
                + "[{\"text\":\"🎲 Хит /pop\",\"callback_data\":\"bot:pop\"},"
                + "{\"text\":\"👥 Онлайн\",\"callback_data\":\"bot:online\"}]"
                + "]}";
    }

    private void sendAsync(String token, String chatId, String text, String replyMarkupJson, int trackTtUserId) {
        sendExecutor.execute(() -> sendSync(token, chatId, text, replyMarkupJson, trackTtUserId));
    }

    private void sendSync(String token, String chatId, String text, String replyMarkupJson, int trackTtUserId) {
        if (isBlank(token) || isBlank(chatId) || isBlank(text)) return;
        try {
            String safeText = text.length() > 3900 ? (text.substring(0, 3900) + "…") : text;
            Map<String, String> params = new LinkedHashMap<>();
            params.put("chat_id", chatId);
            params.put("text", safeText);
            if (!isBlank(replyMarkupJson)) {
                params.put("reply_markup", replyMarkupJson);
            }
            JsonObject resp = callApi(token, "sendMessage", params, 10_000, 15_000);
            if (trackTtUserId > 0 && resp != null && optBool(resp, "ok", false)) {
                JsonObject res = optObj(resp, "result");
                long msgId = optLong(res, "message_id", 0L);
                if (msgId > 0) {
                    forwardedMsgToTtUser.put(msgId, trackTtUserId);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "TG sendMessage failed: " + t.getMessage());
        }
    }

    private void answerCallback(String token, String cqId, String text, boolean alert) {
        if (isBlank(cqId)) return;
        sendExecutor.execute(() -> {
            try {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("callback_query_id", cqId);
                if (!isBlank(text)) params.put("text", text);
                if (alert) params.put("show_alert", "true");
                callApi(token, "answerCallbackQuery", params, 8000, 10000);
            } catch (Throwable ignored) {
            }
        });
    }

    private static JsonObject callApi(String token, String method, Map<String, String> formParams,
                                      int connectMs, int readMs) throws IOException {
        URL url = new URL(TG_API_BASE + token + "/" + method);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            if (formParams != null && !formParams.isEmpty()) {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                StringBuilder form = new StringBuilder();
                for (Map.Entry<String, String> e : formParams.entrySet()) {
                    if (form.length() > 0) form.append('&');
                    form.append(URLEncoder.encode(e.getKey(), "UTF-8"));
                    form.append('=');
                    form.append(URLEncoder.encode(e.getValue() != null ? e.getValue() : "", "UTF-8"));
                }
                byte[] bytes = form.toString().getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                    os.flush();
                }
            } else {
                conn.setRequestMethod("GET");
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(is);
            JsonElement el = JsonParser.parseString(body);
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        } finally {
            conn.disconnect();
        }
    }

    private static void downloadFile(String urlStr, File dest) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setConnectTimeout(12_000);
            conn.setReadTimeout(30_000);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream is = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(dest)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = is.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
                fos.flush();
            }
        } finally {
            conn.disconnect();
        }
    }

    private void notifyHostState() {
        try {
            host.onBridgeStateChanged();
        } catch (Throwable ignored) {
        }
    }

    private static String buildSenderLabel(JsonObject from) {
        if (from == null) return "Telegram";
        String first = optStr(from, "first_name", "");
        String user = optStr(from, "username", "");
        if (!first.isEmpty() && !user.isEmpty()) return first + " (@" + user + ")";
        if (!first.isEmpty()) return first;
        if (!user.isEmpty()) return "@" + user;
        return "TG#" + optLong(from, "id", 0L);
    }

    private static String extractExtension(String name, String fallback) {
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot > 0 && dot < name.length() - 1) {
                String ext = name.substring(dot).toLowerCase(Locale.ROOT);
                if (ext.length() <= 6 && ext.matches("\\.[a-z0-9]+")) {
                    return ext;
                }
            }
        }
        return fallback;
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

    private static JsonObject optObj(JsonObject o, String k) {
        return (o != null && o.has(k) && o.get(k).isJsonObject()) ? o.getAsJsonObject(k) : null;
    }

    private static JsonArray optArr(JsonObject o, String k) {
        return (o != null && o.has(k) && o.get(k).isJsonArray()) ? o.getAsJsonArray(k) : null;
    }

    private static String optStr(JsonObject o, String k, String def) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull()) return def;
        try {
            return o.get(k).getAsString();
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static long optLong(JsonObject o, String k, long def) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull()) return def;
        try {
            return o.get(k).getAsLong();
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static boolean optBool(JsonObject o, String k, boolean def) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull()) return def;
        try {
            return o.get(k).getAsBoolean();
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String safeMsg(Throwable t) {
        return t != null && t.getMessage() != null ? t.getMessage() : (t != null ? t.getClass().getSimpleName() : "unknown");
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
