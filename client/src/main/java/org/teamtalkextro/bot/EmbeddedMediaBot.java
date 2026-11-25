package org.teamtalkextro.bot;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import dk.bearware.Channel;
import dk.bearware.ClientEvent;
import dk.bearware.EncryptionContext;
import dk.bearware.IntPtr;
import dk.bearware.TTMessage;
import dk.bearware.TeamTalk5;
import dk.bearware.TeamTalkBase;
import dk.bearware.TextMessage;
import dk.bearware.TextMsgType;
import dk.bearware.User;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.teamtalkextro.backend.ServerPeekHelper;

/**
 * Standalone embedded Android TTMediaBot (inspired by Daket52i/TTMediaBot-by-dancho).
 *
 * Runs on an independent {@link TeamTalk5} instance inside {@link org.teamtalkextro.backend.TeamTalkService}
 * with zero interference with the user's own connection or microphone.
 */
public final class EmbeddedMediaBot {

    private static final String TAG = "EmbeddedMediaBot";
    private static final int MAX_LOG_LINES = 80;
    private static final long RECONNECT_BACKOFF_MS = 5000L;

    public enum State {
        STOPPED,
        CONNECTING,
        ONLINE,
        IN_CHANNEL,
        RECONNECTING
    }

    public static final class Snapshot {
        public final State state;
        public final String channelName;
        public final String nowPlaying;
        public final String liveStreamTitle;
        public final boolean playing;
        public final boolean paused;
        public final int volume;
        public final String activeService;
        public final int queueSize;
        public final String hostInfo;

        public Snapshot(State state, String channelName, String nowPlaying, String liveStreamTitle,
                        boolean playing, boolean paused, int volume, String activeService,
                        int queueSize, String hostInfo) {
            this.state = state;
            this.channelName = channelName;
            this.nowPlaying = nowPlaying;
            this.liveStreamTitle = liveStreamTitle;
            this.playing = playing;
            this.paused = paused;
            this.volume = volume;
            this.activeService = activeService;
            this.queueSize = queueSize;
            this.hostInfo = hostInfo;
        }
    }

    public interface StateListener {
        void onBotStateChanged(Snapshot snapshot);
        void onBotLogUpdated(List<String> logs);
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final BotAudioStreamer streamer;
    private final CopyOnWriteArrayList<StateListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Extro-BotCmd");
        t.setDaemon(true);
        return t;
    });

    private final Object stateLock = new Object();
    private final Deque<String> logRing = new ArrayDeque<>(MAX_LOG_LINES + 4);
    private final List<BotMusicSearch.BotTrack> queue = new ArrayList<>();
    private final List<BotMusicSearch.BotTrack> history = new ArrayList<>();
    private List<BotMusicSearch.BotTrack> lastSearchResults = new ArrayList<>();
    private final Map<Integer, Channel> channelsMap = new HashMap<>();
    private final Map<Integer, User> usersMap = new HashMap<>();

    private volatile boolean running = false;
    private volatile State state = State.STOPPED;
    private volatile BotConfig activeConfig = BotConfig.createDefault();
    private volatile TeamTalkBase botClient;
    private volatile Thread botThread;
    private volatile int myUserId = -1;
    private volatile int currentChannelId = 0;
    private volatile String currentChannelName = "";
    private volatile BotMusicSearch.BotTrack currentTrack = null;
    private volatile String liveIcyTitle = "";
    private volatile String activeService = BotMusicSearch.SERVICE_HITMO;
    private volatile boolean selectMode = false;
    private volatile String targetChannelOverride = null;
    private volatile String targetChannelPassOverride = null;

    public EmbeddedMediaBot(Context context) {
        this.appContext = context.getApplicationContext();
        this.streamer = new BotAudioStreamer(appContext);
        this.streamer.setOnTrackFinishedListener(this::onTrackFinished);
        this.streamer.setOnMetadataListener(this::onIcyTitleChanged);
    }

    public void addStateListener(StateListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
            Snapshot snap = getSnapshot();
            List<String> logs = getLogs();
            mainHandler.post(() -> {
                listener.onBotStateChanged(snap);
                listener.onBotLogUpdated(logs);
            });
        }
    }

    public void removeStateListener(StateListener listener) {
        listeners.remove(listener);
    }

    public boolean isRunning() {
        return this.running;
    }

    public BotConfig getActiveConfig() {
        return this.activeConfig != null ? this.activeConfig.copy() : BotConfig.createDefault();
    }

    public Snapshot getSnapshot() {
        synchronized (stateLock) {
            String host = activeConfig != null && !TextUtils.isEmpty(activeConfig.host)
                    ? (activeConfig.host + ":" + activeConfig.tcpPort) : "";
            String now = currentTrack != null ? currentTrack.getDisplayName() : "";
            return new Snapshot(
                    state,
                    currentChannelName,
                    now,
                    liveIcyTitle,
                    streamer.isPlaying(),
                    streamer.isPaused(),
                    streamer.getVolume(),
                    activeService,
                    queue.size(),
                    host
            );
        }
    }

    public String getNotificationSummary() {
        Snapshot s = getSnapshot();
        if (!TextUtils.isEmpty(s.nowPlaying)) {
            return "🎵 " + s.nowPlaying;
        }
        if (s.state == State.IN_CHANNEL && !TextUtils.isEmpty(s.channelName)) {
            return s.channelName;
        }
        return !TextUtils.isEmpty(s.hostInfo) ? s.hostInfo : "TTMediaBot";
    }

    public List<String> getLogs() {
        synchronized (logRing) {
            return new ArrayList<>(logRing);
        }
    }

    public void clearLogs() {
        synchronized (logRing) {
            logRing.clear();
        }
        notifyLogsChanged();
    }

    public synchronized void start(BotConfig config) {
        stop();
        if (config == null || TextUtils.isEmpty(config.host)) {
            appendLog("Ошибка: не указан адрес сервера");
            return;
        }
        BotConfig cfg = config.copy();
        cfg.normalize();
        this.activeConfig = cfg;
        this.activeService = BotMusicSearch.normalizeService(cfg.defaultService);
        this.streamer.setVolume(cfg.volume);
        this.selectMode = false;
        this.targetChannelOverride = null;
        this.targetChannelPassOverride = null;
        this.liveIcyTitle = "";
        this.currentTrack = null;
        synchronized (stateLock) {
            this.queue.clear();
            this.history.clear();
            this.lastSearchResults.clear();
            this.channelsMap.clear();
            this.usersMap.clear();
        }

        this.running = true;
        setState(State.CONNECTING);
        appendLog("Запуск бота «" + cfg.nickname + "» → " + cfg.host + ":" + cfg.tcpPort
                + (cfg.encrypted ? " (TLS)" : ""));
        this.botThread = new Thread(() -> runBotLoop(cfg), "Extro-MediaBot");
        this.botThread.start();
    }

    public synchronized void stop() {
        this.running = false;
        this.streamer.stop();
        this.streamer.setBotClient(null);

        TeamTalkBase client = this.botClient;
        if (client != null) {
            try {
                client.disconnect();
            } catch (Throwable ignored) {
            }
        }
        Thread t = this.botThread;
        this.botThread = null;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try {
                t.join(1200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        this.currentChannelId = 0;
        this.currentChannelName = "";
        this.currentTrack = null;
        this.liveIcyTitle = "";
        this.myUserId = -1;
        setState(State.STOPPED);
    }

    // --- UI Direct Controls ---

    public void setService(String service) {
        this.activeService = BotMusicSearch.normalizeService(service);
        appendLog("Сервис поиска: " + BotMusicSearch.getServiceLabel(this.activeService));
        notifyStateChanged();
    }

    public void setVolume(int vol) {
        int clamped = Math.max(0, Math.min(100, vol));
        this.streamer.setVolume(clamped);
        if (this.activeConfig != null) {
            this.activeConfig.volume = clamped;
        }
        notifyStateChanged();
    }

    public void togglePause() {
        if (!streamer.isRunning()) return;
        boolean next = !streamer.isPaused();
        streamer.setPaused(next);
        appendLog(next ? "Пауза" : "Воспроизведение продолжено");
        notifyStateChanged();
    }

    public void stopPlayback() {
        streamer.stop();
        this.currentTrack = null;
        this.liveIcyTitle = "";
        updateBotStatus("");
        appendLog("Воспроизведение остановлено");
        notifyStateChanged();
    }

    public void nextTrack() {
        BotMusicSearch.BotTrack next;
        synchronized (stateLock) {
            if (queue.isEmpty()) {
                next = null;
            } else {
                next = queue.remove(0);
            }
        }
        if (next != null) {
            startTrack(next, true);
        } else {
            stopPlayback();
        }
    }

    public void prevTrack() {
        BotMusicSearch.BotTrack prev;
        synchronized (stateLock) {
            if (history.size() < 2) {
                prev = history.isEmpty() ? null : history.get(history.size() - 1);
            } else {
                // history tail is current track; pop it and grab previous
                history.remove(history.size() - 1);
                prev = history.remove(history.size() - 1);
            }
        }
        if (prev != null) {
            startTrack(prev, true);
        } else {
            appendLog("Нет предыдущего трека в истории");
        }
    }

    public void seekBySeconds(int deltaSec) {
        streamer.seekBySeconds(deltaSec);
        appendLog("Перемотка: " + (deltaSec >= 0 ? "+" : "") + deltaSec + " сек");
    }

    public void playQueryFromUi(String service, String query) {
        if (TextUtils.isEmpty(query)) return;
        final String svc = BotMusicSearch.normalizeService(service);
        this.activeService = svc;
        notifyStateChanged();
        commandExecutor.execute(() -> {
            appendLog("Поиск [" + svc + "]: " + query);
            try {
                List<BotMusicSearch.BotTrack> found = BotMusicSearch.search(svc, query);
                if (found == null || found.isEmpty()) {
                    appendLog("Ничего не найдено по запросу: " + query);
                    return;
                }
                synchronized (stateLock) {
                    lastSearchResults = new ArrayList<>(found);
                }
                startTrack(found.get(0), true);
            } catch (Throwable t) {
                appendLog("Ошибка поиска: " + t.getMessage());
            }
        });
    }

    public void playLocalFileFromUi(Uri uri, String displayName) {
        if (uri == null) return;
        String name = !TextUtils.isEmpty(displayName) ? displayName : uri.getLastPathSegment();
        if (TextUtils.isEmpty(name)) name = "Локальный трек";
        BotMusicSearch.BotTrack track = new BotMusicSearch.BotTrack(
                name, "Телефон", uri.toString(), "local", false, uri.toString(), null);
        startTrack(track, true);
    }

    // --- Main TeamTalk5 Bot Event Loop ---

    private void runBotLoop(BotConfig cfg) {
        ServerPeekHelper.ensureNativeLibraryLoaded();

        while (this.running) {
            TeamTalkBase client = null;
            try {
                setState(State.CONNECTING);
                client = new TeamTalk5();
                this.botClient = client;
                this.streamer.setBotClient(client);

                if (cfg.encrypted) {
                    EncryptionContext enc = new EncryptionContext();
                    enc.bVerifyPeer = cfg.verifyPeer;
                    if (!enc.bVerifyPeer) {
                        enc.nVerifyDepth = -1;
                    }
                    client.setEncryptionContext(enc);
                }

                if (!client.connect(cfg.host, cfg.tcpPort, cfg.udpPort, 0, 0, cfg.encrypted)) {
                    appendLog("Не удалось подключиться к " + cfg.host + ":" + cfg.tcpPort);
                    sleepReconnect();
                    continue;
                }

                boolean loggedIn = false;
                boolean joinSent = false;
                long loggedInAt = 0L;
                int loginCmdId = -1;
                boolean connectionBroken = false;

                while (this.running && !connectionBroken) {
                    // Fallback join if CMD_SUCCESS didn't arrive within 1200ms after MYSELF_LOGGEDIN
                    if (loggedIn && !joinSent && (SystemClock.uptimeMillis() - loggedInAt >= 1200L)) {
                        joinSent = true;
                        joinConfiguredChannel(client, cfg);
                    }

                    // Fresh TTMessage on every poll (prevents stale JNI fields)
                    TTMessage msg = new TTMessage();
                    if (!client.getMessage(msg, 40)) {
                        continue;
                    }

                    switch (msg.nClientEvent) {
                        case ClientEvent.CLIENTEVENT_CON_SUCCESS:
                            appendLog("TCP-соединение установлено, выполняем вход…");
                            loginCmdId = client.doLoginEx(cfg.nickname, cfg.username, cfg.password, "TTMediaBot Extro");
                            if (loginCmdId <= 0) {
                                appendLog("Ошибка вызова doLoginEx");
                                connectionBroken = true;
                            }
                            break;

                        case ClientEvent.CLIENTEVENT_CON_FAILED:
                            appendLog("Ошибка подключения к серверу");
                            connectionBroken = true;
                            break;

                        case ClientEvent.CLIENTEVENT_CON_CRYPT_ERROR:
                            appendLog("Ошибка TLS-шифрования (проверьте настройку TLS)");
                            connectionBroken = true;
                            break;

                        case ClientEvent.CLIENTEVENT_CON_LOST:
                            appendLog("Соединение с сервером потеряно");
                            connectionBroken = true;
                            break;

                        case ClientEvent.CLIENTEVENT_CMD_ERROR: {
                            int errNo = msg.clienterrormsg != null ? msg.clienterrormsg.nErrorNo : 0;
                            String errText = msg.clienterrormsg != null && !TextUtils.isEmpty(msg.clienterrormsg.szErrorMsg)
                                    ? msg.clienterrormsg.szErrorMsg
                                    : (errNo != 0 ? TeamTalkBase.getErrorMessage(errNo) : "Unknown error");
                            appendLog("Ошибка команды (#" + errNo + "): " + errText);
                            if (!loggedIn && loginCmdId > 0 && msg.nSource == loginCmdId) {
                                connectionBroken = true;
                            }
                            break;
                        }

                        case ClientEvent.CLIENTEVENT_CMD_MYSELF_LOGGEDIN:
                            loggedIn = true;
                            loggedInAt = SystemClock.uptimeMillis();
                            myUserId = client.getMyUserID();
                            setState(State.ONLINE);
                            appendLog("Авторизация успешна (userId=" + myUserId + ")");
                            break;

                        case ClientEvent.CLIENTEVENT_CMD_SUCCESS:
                            if (loginCmdId > 0 && msg.nSource == loginCmdId && !joinSent) {
                                joinSent = true;
                                refreshNativeCaches(client);
                                joinConfiguredChannel(client, cfg);
                            }
                            break;

                        case ClientEvent.CLIENTEVENT_CMD_CHANNEL_NEW:
                        case ClientEvent.CLIENTEVENT_CMD_CHANNEL_UPDATE:
                            if (msg.channel != null && msg.channel.nChannelID > 0) {
                                synchronized (stateLock) {
                                    channelsMap.put(msg.channel.nChannelID, copyChannel(msg.channel));
                                }
                            }
                            break;

                        case ClientEvent.CLIENTEVENT_CMD_CHANNEL_REMOVE:
                            if (msg.channel != null) {
                                synchronized (stateLock) {
                                    channelsMap.remove(msg.channel.nChannelID);
                                }
                            }
                            break;

                        case ClientEvent.CLIENTEVENT_CMD_USER_LOGGEDIN:
                        case ClientEvent.CLIENTEVENT_CMD_USER_UPDATE:
                            if (msg.user != null && msg.user.nUserID > 0) {
                                synchronized (stateLock) {
                                    usersMap.put(msg.user.nUserID, copyUser(msg.user));
                                }
                            }
                            break;

                        case ClientEvent.CLIENTEVENT_CMD_USER_LOGGEDOUT:
                            if (msg.user != null) {
                                synchronized (stateLock) {
                                    usersMap.remove(msg.user.nUserID);
                                }
                            }
                            break;

                        case ClientEvent.CLIENTEVENT_CMD_USER_JOINED:
                            if (msg.user != null && msg.user.nUserID > 0) {
                                User u = copyUser(msg.user);
                                synchronized (stateLock) {
                                    usersMap.put(u.nUserID, u);
                                }
                                if (u.nUserID == myUserId) {
                                    currentChannelId = u.nChannelID;
                                    currentChannelName = resolveChannelDisplayPath(client, u.nChannelID);
                                    setState(State.IN_CHANNEL);
                                    appendLog("Бот вошёл в канал: " + currentChannelName + " (ID " + currentChannelId + ")");
                                } else if (currentChannelId > 0 && u.nChannelID == currentChannelId
                                        && !TextUtils.isEmpty(cfg.welcomeMessage)) {
                                    String welcome = cfg.welcomeMessage.replace("{user}",
                                            !TextUtils.isEmpty(u.szNickname) ? u.szNickname : u.szUsername);
                                    sendPrivateMessage(u.nUserID, welcome);
                                }
                            }
                            break;

                        case ClientEvent.CLIENTEVENT_CMD_USER_LEFT:
                            if (msg.user != null && msg.user.nUserID > 0) {
                                synchronized (stateLock) {
                                    User existing = usersMap.get(msg.user.nUserID);
                                    if (existing != null) existing.nChannelID = 0;
                                }
                                if (msg.user.nUserID == myUserId) {
                                    currentChannelId = 0;
                                    currentChannelName = "";
                                    setState(State.ONLINE);
                                }
                            }
                            break;

                        case ClientEvent.CLIENTEVENT_CMD_MYSELF_KICKED:
                            appendLog("Бот был кикнут из канала");
                            currentChannelId = 0;
                            currentChannelName = "";
                            streamer.stop();
                            setState(State.ONLINE);
                            break;

                        case ClientEvent.CLIENTEVENT_CMD_USER_TEXTMSG:
                            if (msg.textmessage != null) {
                                handleIncomingTextMessage(msg.textmessage, cfg);
                            }
                            break;

                        default:
                            break;
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "Bot loop exception", t);
                appendLog("Сбой цикла бота: " + t.getMessage());
            } finally {
                streamer.stop();
                streamer.setBotClient(null);
                this.botClient = null;
                this.currentChannelId = 0;
                this.currentChannelName = "";
                if (client != null) {
                    try {
                        client.disconnect();
                    } catch (Throwable ignored) {
                    }
                    try {
                        client.closeTeamTalk();
                    } catch (Throwable ignored) {
                    }
                }
            }

            if (this.running) {
                sleepReconnect();
            }
        }
        setState(State.STOPPED);
    }

    private void sleepReconnect() {
        setState(State.RECONNECTING);
        appendLog("Переподключение через 5 сек…");
        long end = SystemClock.uptimeMillis() + RECONNECT_BACKOFF_MS;
        while (this.running && SystemClock.uptimeMillis() < end) {
            try {
                Thread.sleep(250L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void refreshNativeCaches(TeamTalkBase client) {
        try {
            IntPtr chCount = new IntPtr(0);
            if (client.getServerChannels(null, chCount) && chCount.value > 0) {
                Channel[] arr = new Channel[chCount.value];
                if (client.getServerChannels(arr, chCount)) {
                    synchronized (stateLock) {
                        for (int i = 0; i < chCount.value && i < arr.length; i++) {
                            if (arr[i] != null && arr[i].nChannelID > 0) {
                                channelsMap.put(arr[i].nChannelID, copyChannel(arr[i]));
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void joinConfiguredChannel(TeamTalkBase client, BotConfig cfg) {
        String target = !TextUtils.isEmpty(targetChannelOverride) ? targetChannelOverride : cfg.channel;
        String pass = targetChannelPassOverride != null ? targetChannelPassOverride : cfg.channelPassword;
        int chId = resolveChannelId(client, target);
        if (chId <= 0) {
            chId = client.getRootChannelID();
            if (chId <= 0) chId = 1;
        }
        appendLog("Вход в канал «" + (TextUtils.isEmpty(target) ? "/" : target) + "» (ID " + chId + ")…");
        client.doJoinChannelByID(chId, pass != null ? pass : "");
    }

    private int resolveChannelId(TeamTalkBase client, String spec) {
        if (TextUtils.isEmpty(spec) || "/".equals(spec.trim())) {
            int root = client.getRootChannelID();
            return root > 0 ? root : 1;
        }
        String trimmed = spec.trim();
        try {
            int parsed = Integer.parseInt(trimmed);
            if (parsed > 0) return parsed;
        } catch (NumberFormatException ignored) {
        }

        String withSlash = trimmed.startsWith("/") ? trimmed : ("/" + trimmed);
        int id = client.getChannelIDFromPath(withSlash);
        if (id > 0) return id;
        if (!withSlash.endsWith("/")) {
            id = client.getChannelIDFromPath(withSlash + "/");
            if (id > 0) return id;
        }

        // Case-insensitive match against collected channels map (by full path or leaf name)
        String lowTarget = withSlash.replaceAll("/+$", "").toLowerCase(Locale.ROOT);
        String lowLeaf = trimmed.replaceAll("^/+|/+$", "").toLowerCase(Locale.ROOT);
        synchronized (stateLock) {
            for (Channel ch : channelsMap.values()) {
                String path = buildChannelPath(ch, channelsMap).replaceAll("/+$", "").toLowerCase(Locale.ROOT);
                if (path.equals(lowTarget)) {
                    return ch.nChannelID;
                }
            }
            for (Channel ch : channelsMap.values()) {
                if (ch.szName != null && ch.szName.trim().toLowerCase(Locale.ROOT).equals(lowLeaf)) {
                    return ch.nChannelID;
                }
            }
        }
        return -1;
    }

    private String resolveChannelDisplayPath(TeamTalkBase client, int chId) {
        try {
            String p = client.getChannelPath(chId);
            if (!TextUtils.isEmpty(p)) return p;
        } catch (Throwable ignored) {
        }
        synchronized (stateLock) {
            Channel ch = channelsMap.get(chId);
            if (ch != null) return buildChannelPath(ch, channelsMap);
        }
        return "#" + chId;
    }

    // --- TTMediaBot-by-dancho Command Engine ---

    private void handleIncomingTextMessage(TextMessage tm, BotConfig cfg) {
        if (tm == null || TextUtils.isEmpty(tm.szMessage)) return;
        if (tm.nFromUserID == myUserId) return;

        final boolean isPrivate = tm.nMsgType == TextMsgType.MSGTYPE_USER;
        final boolean isChannel = tm.nMsgType == TextMsgType.MSGTYPE_CHANNEL;
        if (isPrivate && !cfg.allowPrivateCommands) return;
        if (isChannel && !cfg.allowChannelCommands) return;
        if (!isPrivate && !isChannel) return;

        final String raw = tm.szMessage.trim();
        if (raw.isEmpty()) return;

        // Parse command (accept both "p ..." and "/p ...")
        String clean = raw.startsWith("/") ? raw.substring(1).trim() : raw;
        if (clean.isEmpty()) return;

        String[] parts = clean.split("\\s+", 2);
        final String cmd = parts[0].toLowerCase(Locale.ROOT);
        final String arg = parts.length > 1 ? parts[1].trim() : "";

        // For channel messages, only react to recognized bot commands so normal chat isn't spammed
        if (!isKnownCommand(cmd)) {
            return;
        }

        final int fromUserId = tm.nFromUserID;
        final String fromUsername = tm.szFromUsername != null ? tm.szFromUsername : "";
        final boolean replyToChannel = isChannel;

        appendLog("Команда от " + (!TextUtils.isEmpty(fromUsername) ? fromUsername : ("#" + fromUserId))
                + (isChannel ? " [канал]" : " [ЛС]") + ": " + raw);

        commandExecutor.execute(() -> executeCommand(cmd, arg, fromUserId, fromUsername, replyToChannel, cfg));
    }

    private static boolean isKnownCommand(String cmd) {
        switch (cmd) {
            case "p":
            case "play":
            case "sr":
            case "sl":
            case "sv":
            case "r":
            case "radio":
            case "s":
            case "stop":
            case "sp":
            case "pause":
            case "n":
            case "next":
            case "skip":
            case "b":
            case "prev":
            case "q":
            case "queue":
            case "c":
            case "clear":
            case "v":
            case "vol":
            case "sf":
            case "sb":
            case "now":
            case "cg":
            case "j":
            case "st":
            case "h":
            case "help":
                return true;
            default:
                return false;
        }
    }

    private void executeCommand(String cmd, String arg, int fromUserId, String fromUsername,
                                boolean replyToChannel, BotConfig cfg) {
        switch (cmd) {
            case "h":
            case "help":
                reply(fromUserId, replyToChannel,
                        "Команды TTMediaBot Extro:\n"
                        + "p [запрос/URL] — найти и включить (или пауза)\n"
                        + "sr — режим выбора из списка (sl <№>)\n"
                        + "sv [hitmo|mf|rt|radio] — сменить сервис (сейчас: " + activeService + ")\n"
                        + "r [№/запрос] — радиостанции\n"
                        + "sp — пауза/продолжить | s — стоп\n"
                        + "n — следующий | b — предыдущий\n"
                        + "q — очередь | c — очистить очередь\n"
                        + "v [0-100] — громкость | sf/sb <сек> — перемотка\n"
                        + "now / cg — что играет\n"
                        + "j <канал> — перейти в канал | st <текст> — статус");
                break;

            case "p":
            case "play":
                if (TextUtils.isEmpty(arg)) {
                    if (streamer.isRunning()) {
                        togglePause();
                        reply(fromUserId, replyToChannel, streamer.isPaused() ? "⏸ Пауза" : "▶ Продолжено");
                    } else {
                        reply(fromUserId, replyToChannel, "Укажите запрос или ссылку: p <песня>");
                    }
                    return;
                }
                try {
                    List<BotMusicSearch.BotTrack> found = BotMusicSearch.search(activeService, arg);
                    if (found == null || found.isEmpty()) {
                        reply(fromUserId, replyToChannel, "Ничего не найдено (" + activeService + "): " + arg);
                        return;
                    }
                    synchronized (stateLock) {
                        lastSearchResults = new ArrayList<>(found);
                    }
                    if (selectMode && !BotMusicSearch.isDirectUrl(arg) && found.size() > 1) {
                        StringBuilder sb = new StringBuilder("Результаты (" + activeService + "), выберите sl <№>:\n");
                        int lim = Math.min(found.size(), 10);
                        for (int i = 0; i < lim; i++) {
                            sb.append(i + 1).append(". ").append(found.get(i).getDisplayName()).append("\n");
                        }
                        reply(fromUserId, replyToChannel, sb.toString().trim());
                    } else {
                        BotMusicSearch.BotTrack chosen = found.get(0);
                        enqueueOrPlay(chosen, fromUserId, replyToChannel);
                    }
                } catch (Throwable t) {
                    reply(fromUserId, replyToChannel, "Ошибка поиска: " + t.getMessage());
                }
                break;

            case "sr":
                selectMode = !selectMode;
                reply(fromUserId, replyToChannel, selectMode
                        ? "Режим выбора результатов (sr) ВКЛ — после p <запрос> выбирайте через sl <№>"
                        : "Режим выбора результатов (sr) ВЫКЛ — сразу играет первый трек");
                break;

            case "sl": {
                int idx;
                try {
                    idx = Integer.parseInt(arg);
                } catch (NumberFormatException e) {
                    reply(fromUserId, replyToChannel, "Использование: sl <номер>");
                    return;
                }
                BotMusicSearch.BotTrack picked = null;
                synchronized (stateLock) {
                    if (idx >= 1 && idx <= lastSearchResults.size()) {
                        picked = lastSearchResults.get(idx - 1);
                    }
                }
                if (picked == null) {
                    reply(fromUserId, replyToChannel, "Трек #" + idx + " не найден в результатах поиска");
                    return;
                }
                enqueueOrPlay(picked, fromUserId, replyToChannel);
                break;
            }

            case "sv":
                if (TextUtils.isEmpty(arg)) {
                    reply(fromUserId, replyToChannel, "Текущий сервис: " + BotMusicSearch.getServiceLabel(activeService)
                            + ". Доступны: hitmo, mf, rt, radio");
                } else {
                    String nextSvc = BotMusicSearch.normalizeService(arg);
                    setService(nextSvc);
                    reply(fromUserId, replyToChannel, "Сервис переключён на: " + BotMusicSearch.getServiceLabel(nextSvc));
                }
                break;

            case "r":
            case "radio":
                if (TextUtils.isEmpty(arg)) {
                    StringBuilder sb = new StringBuilder("Встроенные радиостанции (введите r <№> или r <название>):\n");
                    for (int i = 0; i < BotMusicSearch.BUILT_IN_RADIO.size(); i++) {
                        sb.append(i + 1).append(". ").append(BotMusicSearch.BUILT_IN_RADIO.get(i).title).append("\n");
                    }
                    reply(fromUserId, replyToChannel, sb.toString().trim());
                } else {
                    try {
                        List<BotMusicSearch.BotTrack> found = BotMusicSearch.searchRadio(arg);
                        if (found.isEmpty()) {
                            reply(fromUserId, replyToChannel, "Радиостанция не найдена: " + arg);
                            return;
                        }
                        synchronized (stateLock) {
                            lastSearchResults = new ArrayList<>(found);
                        }
                        startTrack(found.get(0), false);
                        reply(fromUserId, replyToChannel, "📻 Радио: " + found.get(0).getDisplayName());
                    } catch (Throwable t) {
                        reply(fromUserId, replyToChannel, "Ошибка поиска радио: " + t.getMessage());
                    }
                }
                break;

            case "s":
            case "stop":
                stopPlayback();
                reply(fromUserId, replyToChannel, "⏹ Остановлено");
                break;

            case "sp":
            case "pause":
                if (!streamer.isRunning()) {
                    reply(fromUserId, replyToChannel, "Сейчас ничего не играет");
                } else {
                    togglePause();
                    reply(fromUserId, replyToChannel, streamer.isPaused() ? "⏸ Пауза" : "▶ Продолжено");
                }
                break;

            case "n":
            case "next":
            case "skip":
                nextTrack();
                if (currentTrack != null) {
                    reply(fromUserId, replyToChannel, "⏭ Далее: " + currentTrack.getDisplayName());
                } else {
                    reply(fromUserId, replyToChannel, "Очередь пуста, воспроизведение остановлено");
                }
                break;

            case "b":
            case "prev":
                prevTrack();
                if (currentTrack != null) {
                    reply(fromUserId, replyToChannel, "⏮ Назад: " + currentTrack.getDisplayName());
                } else {
                    reply(fromUserId, replyToChannel, "История пуста");
                }
                break;

            case "q":
            case "queue": {
                StringBuilder sb = new StringBuilder();
                if (currentTrack != null) {
                    sb.append("Сейчас играет: ").append(currentTrack.getDisplayName()).append("\n");
                }
                synchronized (stateLock) {
                    if (queue.isEmpty()) {
                        sb.append("Очередь пуста.");
                    } else {
                        sb.append("Очередь (").append(queue.size()).append("):\n");
                        for (int i = 0; i < Math.min(queue.size(), 10); i++) {
                            sb.append(i + 1).append(". ").append(queue.get(i).getDisplayName()).append("\n");
                        }
                    }
                }
                reply(fromUserId, replyToChannel, sb.toString().trim());
                break;
            }

            case "c":
            case "clear":
                synchronized (stateLock) {
                    queue.clear();
                }
                notifyStateChanged();
                reply(fromUserId, replyToChannel, "Очередь очищена");
                break;

            case "v":
            case "vol":
                if (TextUtils.isEmpty(arg)) {
                    reply(fromUserId, replyToChannel, "🔊 Громкость: " + streamer.getVolume() + "%");
                } else {
                    try {
                        int val = Integer.parseInt(arg);
                        setVolume(val);
                        reply(fromUserId, replyToChannel, "🔊 Громкость установлена: " + streamer.getVolume() + "%");
                    } catch (NumberFormatException e) {
                        reply(fromUserId, replyToChannel, "Укажите число от 0 до 100");
                    }
                }
                break;

            case "sf":
            case "sb": {
                int sec = 15;
                if (!TextUtils.isEmpty(arg)) {
                    try {
                        sec = Math.max(1, Math.min(3600, Integer.parseInt(arg)));
                    } catch (NumberFormatException ignored) {
                    }
                }
                int delta = "sb".equals(cmd) ? -sec : sec;
                seekBySeconds(delta);
                reply(fromUserId, replyToChannel, "⏩ Перемотка " + (delta >= 0 ? "+" : "") + delta + " сек");
                break;
            }

            case "now":
            case "cg":
                if (currentTrack == null) {
                    reply(fromUserId, replyToChannel, "Сейчас ничего не играет");
                } else {
                    String info = "🎵 " + currentTrack.getDisplayName();
                    if (!TextUtils.isEmpty(liveIcyTitle)) {
                        info += "\nЭфир: " + liveIcyTitle;
                    }
                    if (streamer.isPaused()) {
                        info += " (на паузе)";
                    }
                    reply(fromUserId, replyToChannel, info);
                }
                break;

            case "j":
                if (!cfg.isAdmin(fromUsername)) {
                    reply(fromUserId, replyToChannel, "Нет прав администратора для смены канала");
                    return;
                }
                if (TextUtils.isEmpty(arg)) {
                    reply(fromUserId, replyToChannel, "Использование: j <путь или ID канала>");
                    return;
                }
                TeamTalkBase client = this.botClient;
                if (client != null) {
                    String[] jp = arg.split("\\s+", 2);
                    String chTarget = jp[0];
                    String chPass = jp.length > 1 ? jp[1] : "";
                    int chId = resolveChannelId(client, arg);
                    if (chId <= 0) {
                        chId = resolveChannelId(client, chTarget);
                    } else {
                        chPass = "";
                    }
                    if (chId > 0) {
                        targetChannelOverride = String.valueOf(chId);
                        targetChannelPassOverride = chPass;
                        client.doJoinChannelByID(chId, chPass);
                        reply(fromUserId, replyToChannel, "Перехожу в канал #" + chId);
                    } else {
                        reply(fromUserId, replyToChannel, "Канал не найден: " + arg);
                    }
                }
                break;

            case "st":
                if (!cfg.isAdmin(fromUsername)) {
                    reply(fromUserId, replyToChannel, "Нет прав администратора для смены статуса");
                    return;
                }
                updateBotStatus(arg);
                reply(fromUserId, replyToChannel, "Статус обновлён");
                break;

            default:
                break;
        }
    }

    private void enqueueOrPlay(BotMusicSearch.BotTrack track, int fromUserId, boolean replyToChannel) {
        if (streamer.isRunning() && currentTrack != null && !currentTrack.isLiveStream) {
            int pos;
            synchronized (stateLock) {
                queue.add(track);
                pos = queue.size();
            }
            notifyStateChanged();
            reply(fromUserId, replyToChannel, "➕ В очередь (#" + pos + "): " + track.getDisplayName());
        } else {
            startTrack(track, false);
            reply(fromUserId, replyToChannel, "▶ Играет: " + track.getDisplayName());
        }
    }

    private void startTrack(BotMusicSearch.BotTrack track, boolean announceToChannel) {
        if (track == null) return;
        synchronized (stateLock) {
            this.currentTrack = track;
            this.liveIcyTitle = "";
            if (history.isEmpty() || !history.get(history.size() - 1).url.equals(track.url)) {
                history.add(track);
                if (history.size() > 30) {
                    history.remove(0);
                }
            }
        }
        appendLog("▶ Старт: " + track.getDisplayName());
        BotConfig cfg = this.activeConfig;
        if (cfg != null && cfg.autoStatusTrack) {
            updateBotStatus("🎵 " + track.getDisplayName());
        }
        if (announceToChannel && cfg != null && cfg.sendChannelAnnouncements && currentChannelId > 0) {
            sendChannelMessage("🎵 " + track.getDisplayName());
        }
        streamer.play(track);
        notifyStateChanged();
    }

    private void onTrackFinished(BotMusicSearch.BotTrack track, boolean completedNormally, String errorMessage) {
        if (!TextUtils.isEmpty(errorMessage)) {
            appendLog("Ошибка воспроизведения: " + errorMessage);
        }
        BotMusicSearch.BotTrack next = null;
        synchronized (stateLock) {
            if (!queue.isEmpty()) {
                next = queue.remove(0);
            }
        }
        if (next != null && this.running) {
            startTrack(next, true);
        } else {
            this.currentTrack = null;
            this.liveIcyTitle = "";
            updateBotStatus("");
            notifyStateChanged();
        }
    }

    private void onIcyTitleChanged(String liveTitle) {
        this.liveIcyTitle = liveTitle != null ? liveTitle : "";
        appendLog("📻 Эфир: " + this.liveIcyTitle);
        BotConfig cfg = this.activeConfig;
        if (cfg != null && cfg.autoStatusTrack && !TextUtils.isEmpty(this.liveIcyTitle)) {
            updateBotStatus("📻 " + this.liveIcyTitle);
        }
        notifyStateChanged();
    }

    private void updateBotStatus(String statusMsg) {
        TeamTalkBase client = this.botClient;
        if (client != null) {
            try {
                client.doChangeStatus(0, statusMsg != null ? statusMsg : "");
            } catch (Throwable ignored) {
            }
        }
    }

    private void reply(int userId, boolean toChannel, String text) {
        if (toChannel && currentChannelId > 0) {
            sendChannelMessage(text);
        } else {
            sendPrivateMessage(userId, text);
        }
    }

    private void sendChannelMessage(String text) {
        TeamTalkBase client = this.botClient;
        if (client == null || currentChannelId <= 0 || TextUtils.isEmpty(text)) return;
        for (String chunk : splitMessage(text, 400)) {
            TextMessage tm = new TextMessage();
            tm.nMsgType = TextMsgType.MSGTYPE_CHANNEL;
            tm.nChannelID = currentChannelId;
            tm.szMessage = chunk;
            try {
                client.doTextMessage(tm);
            } catch (Throwable ignored) {
            }
        }
    }

    private void sendPrivateMessage(int toUserId, String text) {
        TeamTalkBase client = this.botClient;
        if (client == null || toUserId <= 0 || TextUtils.isEmpty(text)) return;
        for (String chunk : splitMessage(text, 400)) {
            TextMessage tm = new TextMessage();
            tm.nMsgType = TextMsgType.MSGTYPE_USER;
            tm.nToUserID = toUserId;
            tm.szMessage = chunk;
            try {
                client.doTextMessage(tm);
            } catch (Throwable ignored) {
            }
        }
    }

    private static List<String> splitMessage(String text, int maxChars) {
        List<String> chunks = new ArrayList<>();
        if (text.length() <= maxChars) {
            chunks.add(text);
            return chunks;
        }
        String[] lines = text.split("\n");
        StringBuilder cur = new StringBuilder();
        for (String line : lines) {
            if (cur.length() + line.length() + 1 > maxChars && cur.length() > 0) {
                chunks.add(cur.toString().trim());
                cur.setLength(0);
            }
            if (line.length() > maxChars) {
                chunks.add(line.substring(0, maxChars));
            } else {
                if (cur.length() > 0) cur.append('\n');
                cur.append(line);
            }
        }
        if (cur.length() > 0) {
            chunks.add(cur.toString().trim());
        }
        return chunks;
    }

    private void setState(State newState) {
        this.state = newState;
        notifyStateChanged();
    }

    private void notifyStateChanged() {
        final Snapshot snap = getSnapshot();
        mainHandler.post(() -> {
            for (StateListener l : listeners) {
                try {
                    l.onBotStateChanged(snap);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void notifyLogsChanged() {
        final List<String> copy = getLogs();
        mainHandler.post(() -> {
            for (StateListener l : listeners) {
                try {
                    l.onBotLogUpdated(copy);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void appendLog(String msg) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.US);
        String line = "[" + sdf.format(new Date()) + "] " + msg;
        synchronized (logRing) {
            logRing.addLast(line);
            while (logRing.size() > MAX_LOG_LINES) {
                logRing.removeFirst();
            }
        }
        Log.d(TAG, line);
        notifyLogsChanged();
    }

    private static Channel copyChannel(Channel src) {
        Channel c = new Channel();
        c.nChannelID = src.nChannelID;
        c.nParentID = src.nParentID;
        c.szName = src.szName != null ? src.szName : "";
        return c;
    }

    private static User copyUser(User src) {
        User u = new User();
        u.nUserID = src.nUserID;
        u.nChannelID = src.nChannelID;
        u.szNickname = src.szNickname != null ? src.szNickname : "";
        u.szUsername = src.szUsername != null ? src.szUsername : "";
        return u;
    }

    private static String buildChannelPath(Channel ch, Map<Integer, Channel> all) {
        if (ch == null) return "/";
        List<String> parts = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        Channel cur = ch;
        while (cur != null && visited.add(cur.nChannelID)) {
            if (!TextUtils.isEmpty(cur.szName)) {
                parts.add(cur.szName);
            }
            if (cur.nParentID <= 0) break;
            cur = all.get(cur.nParentID);
        }
        if (parts.isEmpty()) return "/";
        Collections.reverse(parts);
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            sb.append('/').append(p);
        }
        return sb.toString();
    }
}
