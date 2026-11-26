package org.teamtalkextro.bot;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import dk.bearware.BanType;
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
import java.io.File;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.teamtalkextro.backend.ServerPeekHelper;

/**
 * Standalone embedded Android DJ Bot (analog of TT-DJbot / welcome, guard, music,
 * radio, Volnorez, Russian Live TV + XMLTV EPG, favorites, and TTS /vo).
 *
 * Runs on an independent {@link TeamTalk5} instance inside
 * {@link org.teamtalkextro.backend.TeamTalkService} with zero interference with
 * the user's own connection or microphone.
 */
public final class EmbeddedMediaBot {

    private static final String TAG = "EmbeddedDjBot";
    private static final int MAX_LOG_LINES = 100;
    private static final long RECONNECT_BACKOFF_MS = 5000L;
    private static final int PAGE_SIZE = 15;
    private static final long EPG_POLL_INTERVAL_MS = 60_000L;

    public static final String[] DEFAULT_MAT_STEMS = {
            "пизд", "хуй", "хуе", "хуя", "бля", "еба", "ебл", "ебн", "ебок",
            "заеб", "наеб", "поеб", "говн", "гандон", "залуп", "шлюх", "дроч",
            "манд", "мудак", "мудач", "пидор", "пидр", "сука", "суки", "суку",
            "херн", "херо"
    };

    private static final Pattern BOTNET_WORD_RE =
            Pattern.compile("[A-Za-z]{2,}_[A-Za-z0-9]{1,}(?:_[0-9]{1,})?");

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
        public final String epgSummary;
        public final int listenersCount;
        public final boolean playing;
        public final boolean paused;
        public final int volume;
        public final String activeService;
        public final int queueSize;
        public final String hostInfo;
        public final boolean voiceAnnounce;
        public final boolean guardEnabled;
        public final boolean streamingVideo;
        public final int relayPort;
        public final boolean telegramConnected;
        public final String telegramBotUsername;
        public final String telegramStatusText;

        public Snapshot(State state, String channelName, String nowPlaying, String liveStreamTitle,
                        String epgSummary, int listenersCount, boolean playing, boolean paused,
                        int volume, String activeService, int queueSize, String hostInfo,
                        boolean voiceAnnounce, boolean guardEnabled, boolean streamingVideo,
                        int relayPort, boolean telegramConnected, String telegramBotUsername,
                        String telegramStatusText) {
            this.state = state;
            this.channelName = channelName;
            this.nowPlaying = nowPlaying;
            this.liveStreamTitle = liveStreamTitle;
            this.epgSummary = epgSummary;
            this.listenersCount = listenersCount;
            this.playing = playing;
            this.paused = paused;
            this.volume = volume;
            this.activeService = activeService;
            this.queueSize = queueSize;
            this.hostInfo = hostInfo;
            this.voiceAnnounce = voiceAnnounce;
            this.guardEnabled = guardEnabled;
            this.streamingVideo = streamingVideo;
            this.relayPort = relayPort;
            this.telegramConnected = telegramConnected;
            this.telegramBotUsername = telegramBotUsername != null ? telegramBotUsername : "";
            this.telegramStatusText = telegramStatusText != null ? telegramStatusText : "";
        }
    }

    public interface StateListener {
        void onBotStateChanged(Snapshot snapshot);
        void onBotLogUpdated(List<String> logs);
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final BotAudioStreamer streamer;
    private final DjBotEpg epg;
    private final DjBotTelegramBridge telegramBridge;
    private final CopyOnWriteArrayList<StateListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Extro-DjBotCmd");
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
    private final Set<Integer> welcomedUserIds = new HashSet<>();

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
    private volatile String currentEpgSummary = "";
    private volatile int liveListeners = -1;
    private volatile String activeService = BotMusicSearch.SERVICE_HITMO;
    private volatile boolean selectMode = false;
    private volatile String targetChannelOverride = null;
    private volatile String targetChannelPassOverride = null;
    private volatile long lastEpgTickMs = 0L;

    // Lazy TTS synthesizer for /vo track voice announcements
    private final Object ttsLock = new Object();
    private TextToSpeech botTts;
    private volatile boolean botTtsReady = false;
    private final AtomicInteger voCounter = new AtomicInteger(0);

    public EmbeddedMediaBot(Context context) {
        this.appContext = context.getApplicationContext();
        this.streamer = new BotAudioStreamer(appContext);
        this.streamer.setOnTrackFinishedListener(this::onTrackFinished);
        this.streamer.setOnMetadataListener(this::onLiveTitleChanged);
        this.streamer.setOnListenersListener(this::onLiveListenersChanged);
        this.streamer.setOnStateNoteListener(new BotAudioStreamer.OnStateNoteListener() {
            @Override
            public void onStreamerLog(String message) {
                appendLog(message);
            }

            @Override
            public void onVideoStateChanged(boolean streamingVideo) {
                notifyStateChanged();
            }
        });
        this.epg = new DjBotEpg(appContext.getCacheDir(), this::appendLog);
        this.telegramBridge = new DjBotTelegramBridge(appContext.getCacheDir(), createTelegramHostCallback());
    }

    // --- Pure-Java Guard Evaluation (unit-testable) ---

    /**
     * Evaluates a user against DJ Bot Guard rules.
     * Returns null if user is allowed, or a Russian violation reason if user should be kicked/banned.
     */
    public static String evaluateGuard(BotConfig cfg, String nickname, String username) {
        if (cfg == null || !cfg.guardEnabled) {
            return null;
        }
        if (cfg.isTrusted(username, nickname)) {
            return null;
        }
        String nick = nickname != null ? nickname.trim() : "";
        if (cfg.guardKickEmptyNick && nick.isEmpty()) {
            return "пустой ник";
        }
        if (cfg.guardMaxNickLen > 0 && nick.length() > cfg.guardMaxNickLen) {
            return "слишком длинный ник (" + nick.length() + " > " + cfg.guardMaxNickLen + ")";
        }
        if (cfg.guardMatCheck && containsProfanity(nick)) {
            return "мат в нике";
        }
        if (cfg.guardBotnetNickCheck && isBotnetNick(nick)) {
            return "ботнет-ник";
        }
        return null;
    }

    public static boolean containsProfanity(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String norm = text.toLowerCase(Locale.ROOT).replace('ё', 'е');
        for (String stem : DEFAULT_MAT_STEMS) {
            if (norm.contains(stem)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Botnet nick heuristic matching TT-DJbot:
     * >= 2 underscores + Latin word_word pattern; if emoji present -> botnet,
     * otherwise requires >= 3 underscores so normal nicks like "kirill_mobile" are safe.
     */
    public static boolean isBotnetNick(String nick) {
        if (nick == null) return false;
        String n = nick.trim();
        if (n.isEmpty()) return false;
        int underscores = 0;
        for (int i = 0; i < n.length(); i++) {
            if (n.charAt(i) == '_') underscores++;
        }
        if (underscores < 2) return false;
        if (!BOTNET_WORD_RE.matcher(n).find()) return false;
        if (containsEmoji(n)) return true;
        return underscores >= 3;
    }

    private static boolean containsEmoji(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if ((cp >= 0x1F000 && cp <= 0x1FAFF)
                    || (cp >= 0x2600 && cp <= 0x27BF)
                    || (cp >= 0x2B00 && cp <= 0x2BFF)
                    || (cp >= 0x2190 && cp <= 0x21FF)
                    || (cp >= 0x2300 && cp <= 0x23FF)
                    || cp == 0xFE0F) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // --- Listeners & State ---

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

    public DjBotEpg getEpg() {
        return this.epg;
    }

    public int getRelayPort() {
        return this.streamer.getRelayPort();
    }

    public boolean isStreamingVideo() {
        return this.streamer.isStreamingVideo();
    }

    public boolean isTelegramConnected() {
        return this.telegramBridge.isConnected();
    }

    public String getTelegramBotUsername() {
        return this.telegramBridge.getBotUsername();
    }

    public String getTelegramStatusText() {
        return this.telegramBridge.getStatusText();
    }

    public Snapshot getSnapshot() {
        synchronized (stateLock) {
            String host = activeConfig != null && !isBlank(activeConfig.host)
                    ? (activeConfig.host + ":" + activeConfig.tcpPort) : "";
            String now = currentTrack != null ? currentTrack.getDisplayName() : "";
            boolean vo = activeConfig != null && activeConfig.voiceAnnounceTrack;
            boolean guard = activeConfig != null && activeConfig.guardEnabled;
            return new Snapshot(
                    state,
                    currentChannelName,
                    now,
                    liveIcyTitle,
                    currentEpgSummary,
                    liveListeners,
                    streamer.isPlaying(),
                    streamer.isPaused(),
                    streamer.getVolume(),
                    activeService,
                    queue.size(),
                    host,
                    vo,
                    guard,
                    streamer.isStreamingVideo(),
                    streamer.getRelayPort(),
                    telegramBridge.isConnected(),
                    telegramBridge.getBotUsername(),
                    telegramBridge.getStatusText()
            );
        }
    }

    public String getNotificationSummary() {
        Snapshot s = getSnapshot();
        if (!isBlank(s.epgSummary)) {
            return s.epgSummary;
        }
        if (!isBlank(s.nowPlaying)) {
            return "🎵 " + s.nowPlaying;
        }
        if (s.state == State.IN_CHANNEL && !isBlank(s.channelName)) {
            return s.channelName;
        }
        return !isBlank(s.hostInfo) ? s.hostInfo : "DJ Bot";
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
        if (config == null || isBlank(config.host)) {
            appendLog("Ошибка: не указан адрес сервера");
            return;
        }
        BotConfig cfg = config.copy();
        cfg.normalize();
        this.activeConfig = cfg;
        this.activeService = BotMusicSearch.normalizeService(cfg.defaultService);
        this.streamer.setVolume(cfg.volume);
        this.streamer.configure(cfg.yandexToken, cfg.tvVideoBitrate, cfg.tvAudioOnly);
        this.selectMode = false;
        this.targetChannelOverride = null;
        this.targetChannelPassOverride = null;
        this.liveIcyTitle = "";
        this.currentEpgSummary = "";
        this.liveListeners = -1;
        this.currentTrack = null;
        synchronized (stateLock) {
            this.queue.clear();
            this.history.clear();
            this.lastSearchResults.clear();
            this.channelsMap.clear();
            this.usersMap.clear();
            this.welcomedUserIds.clear();
        }

        this.running = true;
        setState(State.CONNECTING);
        this.epg.ensureLoadedAsync();
        if (!isBlank(cfg.telegramBotToken)) {
            this.telegramBridge.start(cfg.telegramBotToken);
        }
        appendLog("Запуск DJ Bot «" + cfg.nickname + "» → " + cfg.host + ":" + cfg.tcpPort
                + (cfg.encrypted ? " (TLS)" : "")
                + (cfg.guardEnabled ? " [Guard ВКЛ]" : "")
                + (!isBlank(cfg.yandexToken) ? " [ЯМ]" : "")
                + (!isBlank(cfg.telegramBotToken) ? " [TG]" : ""));
        this.botThread = new Thread(() -> runBotLoop(cfg), "Extro-DjBot");
        this.botThread.start();
    }

    public synchronized void stop() {
        this.running = false;
        this.telegramBridge.stop();
        this.streamer.shutdown();
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
        this.currentEpgSummary = "";
        this.liveListeners = -1;
        this.myUserId = -1;
        setState(State.STOPPED);
    }

    // --- UI Direct Controls ---

    public void setService(String service) {
        this.activeService = BotMusicSearch.normalizeService(service);
        if (this.activeConfig != null) {
            this.activeConfig.defaultService = this.activeService;
        }
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
        this.currentEpgSummary = "";
        this.liveListeners = -1;
        updateBotStatus("");
        appendLog("Воспроизведение остановлено");
        notifyStateChanged();
    }

    public void nextTrack() {
        BotMusicSearch.BotTrack next;
        synchronized (stateLock) {
            next = queue.isEmpty() ? null : queue.remove(0);
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
        if (isBlank(query)) return;
        final String svc = BotMusicSearch.normalizeService(service);
        this.activeService = svc;
        notifyStateChanged();
        commandExecutor.execute(() -> {
            appendLog("Поиск [" + svc + "]: " + query);
            try {
                String ymToken = activeConfig != null ? activeConfig.yandexToken : "";
                List<BotMusicSearch.BotTrack> found = BotMusicSearch.search(svc, query, ymToken);
                if (found == null || found.isEmpty()) {
                    appendLog("Ничего не найдено по запросу: " + query);
                    return;
                }
                synchronized (stateLock) {
                    lastSearchResults = new ArrayList<>(found);
                }
                if (YandexMusicClient.isYandexMusicUrl(query) && found.size() > 1) {
                    enqueuePlaylistTracks(found, true);
                } else {
                    startTrack(found.get(0), true);
                }
            } catch (Throwable t) {
                appendLog("Ошибка поиска: " + t.getMessage());
            }
        });
    }

    public void playYandexChartFromUi() {
        commandExecutor.execute(() -> {
            String ymToken = activeConfig != null ? activeConfig.yandexToken : "";
            if (isBlank(ymToken)) {
                appendLog("⚠️ Яндекс.Музыка: не указан OAuth-токен в профиле");
                return;
            }
            appendLog("📈 Загрузка чарта Яндекс.Музыки…");
            try {
                List<BotMusicSearch.BotTrack> tracks = YandexMusicClient.getChartTracks(ymToken, 20);
                if (tracks.isEmpty()) {
                    appendLog("Чарт Яндекс.Музыки пуст");
                    return;
                }
                enqueuePlaylistTracks(tracks, true);
            } catch (Throwable t) {
                appendLog("Ошибка чарта ЯМ: " + t.getMessage());
            }
        });
    }

    public void playYandexLikesFromUi() {
        commandExecutor.execute(() -> {
            String ymToken = activeConfig != null ? activeConfig.yandexToken : "";
            if (isBlank(ymToken)) {
                appendLog("⚠️ Яндекс.Музыка: не указан OAuth-токен в профиле");
                return;
            }
            appendLog("❤️ Загрузка любимых треков Яндекс.Музыки…");
            try {
                List<BotMusicSearch.BotTrack> tracks = YandexMusicClient.getLikedTracks(ymToken, 25);
                if (tracks.isEmpty()) {
                    appendLog("Список любимых треков ЯМ пуст");
                    return;
                }
                enqueuePlaylistTracks(tracks, true);
            } catch (Throwable t) {
                appendLog("Ошибка лайков ЯМ: " + t.getMessage());
            }
        });
    }

    private void enqueuePlaylistTracks(List<BotMusicSearch.BotTrack> tracks, boolean announce) {
        if (tracks == null || tracks.isEmpty()) return;
        BotMusicSearch.BotTrack first = tracks.get(0);
        if (tracks.size() > 1) {
            synchronized (stateLock) {
                for (int i = 1; i < tracks.size(); i++) {
                    queue.add(tracks.get(i));
                }
            }
            appendLog("➕ В очередь добавлено треков: " + (tracks.size() - 1));
        }
        startTrack(first, announce);
    }

    public void playTrackDirectFromUi(BotMusicSearch.BotTrack track) {
        if (track == null) return;
        commandExecutor.execute(() -> {
            if (DjBotCatalogs.SERVICE_TV.equals(track.service)) {
                startTvTrack(track, -1, false, true);
            } else {
                startTrack(track, true);
            }
        });
    }

    public void playRandomPopFromUi() {
        commandExecutor.execute(() -> {
            appendLog("🎲 Подбор случайного хита (/pop)…");
            try {
                BotMusicSearch.BotTrack track = BotMusicSearch.searchRandomPop();
                startTrack(track, true);
            } catch (Throwable t) {
                appendLog("Ошибка /pop: " + t.getMessage());
            }
        });
    }

    public void playLocalFileFromUi(Uri uri, String displayName) {
        if (uri == null) return;
        String name = !isBlank(displayName) ? displayName : uri.getLastPathSegment();
        if (isBlank(name)) name = "Локальный трек";
        BotMusicSearch.BotTrack track = new BotMusicSearch.BotTrack(
                name, "Телефон", uri.toString(), "local", false, uri.toString(), null);
        commandExecutor.execute(() -> startTrack(track, true));
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
                boolean initialLoginDone = false;
                boolean joinSent = false;
                long loggedInAt = 0L;
                int loginCmdId = -1;
                boolean connectionBroken = false;
                synchronized (stateLock) {
                    welcomedUserIds.clear();
                }

                while (this.running && !connectionBroken) {
                    long nowUp = SystemClock.uptimeMillis();
                    if (loggedIn && !joinSent && (nowUp - loggedInAt >= 1200L)) {
                        joinSent = true;
                        initialLoginDone = true;
                        joinConfiguredChannel(client, activeConfig != null ? activeConfig : cfg);
                    }

                    // Periodic EPG status refresh while TV is playing
                    if (currentTrack != null && DjBotCatalogs.SERVICE_TV.equals(currentTrack.service)
                            && (nowUp - lastEpgTickMs >= EPG_POLL_INTERVAL_MS)) {
                        lastEpgTickMs = nowUp;
                        refreshCurrentTvEpg(false);
                    }

                    TTMessage msg = new TTMessage();
                    if (!client.getMessage(msg, 40)) {
                        continue;
                    }

                    BotConfig liveCfg = activeConfig != null ? activeConfig : cfg;
                    switch (msg.nClientEvent) {
                        case ClientEvent.CLIENTEVENT_CON_SUCCESS:
                            appendLog("TCP-соединение установлено, выполняем вход…");
                            loginCmdId = client.doLoginEx(liveCfg.nickname, liveCfg.username, liveCfg.password, "DJ Bot Extro");
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
                            String errText = msg.clienterrormsg != null && !isBlank(msg.clienterrormsg.szErrorMsg)
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
                                initialLoginDone = true;
                                refreshNativeCaches(client);
                                joinConfiguredChannel(client, liveCfg);
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
                            if (msg.user != null && msg.user.nUserID > 0) {
                                User u = copyUser(msg.user);
                                synchronized (stateLock) {
                                    usersMap.put(u.nUserID, u);
                                }
                                if (initialLoginDone && u.nUserID != myUserId) {
                                    boolean violated = enforceGuardIfNeeded(client, liveCfg, u);
                                    if (!violated) {
                                        maybeSendWelcome(liveCfg, u);
                                        telegramBridge.notifyJoinLeave(u.szNickname, u.szUsername, "", true);
                                    }
                                }
                            }
                            break;

                        case ClientEvent.CLIENTEVENT_CMD_USER_UPDATE:
                            if (msg.user != null && msg.user.nUserID > 0) {
                                User u = copyUser(msg.user);
                                synchronized (stateLock) {
                                    usersMap.put(u.nUserID, u);
                                }
                                if (initialLoginDone && u.nUserID != myUserId) {
                                    enforceGuardIfNeeded(client, liveCfg, u);
                                }
                            }
                            break;

                        case ClientEvent.CLIENTEVENT_CMD_USER_LOGGEDOUT:
                            if (msg.user != null) {
                                User removed;
                                synchronized (stateLock) {
                                    removed = usersMap.remove(msg.user.nUserID);
                                    welcomedUserIds.remove(msg.user.nUserID);
                                }
                                if (initialLoginDone && msg.user.nUserID != myUserId) {
                                    User who = removed != null ? removed : msg.user;
                                    telegramBridge.notifyJoinLeave(who.szNickname, who.szUsername, "", false);
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
                                    appendLog("DJ Bot вошёл в канал: " + currentChannelName + " (ID " + currentChannelId + ")");
                                } else if (initialLoginDone && currentChannelId > 0 && u.nChannelID == currentChannelId) {
                                    boolean violated = enforceGuardIfNeeded(client, liveCfg, u);
                                    if (!violated) {
                                        maybeSendWelcome(liveCfg, u);
                                    }
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
                            appendLog("DJ Bot был кикнут из канала");
                            currentChannelId = 0;
                            currentChannelName = "";
                            streamer.stop();
                            setState(State.ONLINE);
                            break;

                        case ClientEvent.CLIENTEVENT_CMD_USER_TEXTMSG:
                            if (msg.textmessage != null) {
                                handleIncomingTextMessage(msg.textmessage, liveCfg);
                            }
                            break;

                        case ClientEvent.CLIENTEVENT_STREAM_MEDIAFILE:
                            if (msg.mediafileinfo != null) {
                                streamer.onStreamMediaFileEvent(msg.mediafileinfo);
                            }
                            break;

                        default:
                            break;
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "DJ Bot loop exception", t);
                appendLog("Сбой цикла DJ Bot: " + t.getMessage());
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

    private boolean enforceGuardIfNeeded(TeamTalkBase client, BotConfig cfg, User u) {
        if (client == null || cfg == null || u == null || u.nUserID <= 0 || u.nUserID == myUserId) {
            return false;
        }
        String reason = evaluateGuard(cfg, u.szNickname, u.szUsername);
        if (reason == null) {
            return false;
        }
        String who = !isBlank(u.szNickname) ? u.szNickname : (!isBlank(u.szUsername) ? u.szUsername : ("#" + u.nUserID));
        if (cfg.guardBanOnViolation) {
            try {
                client.doBanUserEx(u.nUserID, BanType.BANTYPE_IPADDR);
            } catch (Throwable ignored) {
            }
            try {
                client.doKickUser(u.nUserID, 0);
            } catch (Throwable ignored) {
            }
            String note = "🛡️ Guard БАН: «" + who + "» (логин: " + u.szUsername + ") — " + reason;
            appendLog(note);
            if (cfg.sendChannelAnnouncements && currentChannelId > 0) {
                sendChannelMessage(note);
            }
        } else {
            try {
                client.doKickUser(u.nUserID, 0);
            } catch (Throwable ignored) {
            }
            String note = "🛡️ Guard КИК: «" + who + "» (логин: " + u.szUsername + ") — " + reason;
            appendLog(note);
            if (cfg.sendChannelAnnouncements && currentChannelId > 0) {
                sendChannelMessage(note);
            }
        }
        return true;
    }

    private void maybeSendWelcome(BotConfig cfg, User u) {
        if (cfg == null || u == null || !cfg.welcomeEnabled || isBlank(cfg.welcomeText)) {
            return;
        }
        if (u.nUserID <= 0 || u.nUserID == myUserId) {
            return;
        }
        if (cfg.isTrusted(u.szUsername, u.szNickname)) {
            return;
        }
        boolean shouldSend;
        synchronized (stateLock) {
            shouldSend = welcomedUserIds.add(u.nUserID);
        }
        if (!shouldSend) return;

        String name = !isBlank(u.szNickname) ? u.szNickname : (!isBlank(u.szUsername) ? u.szUsername : "гость");
        String text = cfg.welcomeText.replace("{user}", name);
        sendPrivateMessage(u.nUserID, text);
        appendLog("👋 Приветствие отправлено: " + name);
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
        String target = !isBlank(targetChannelOverride) ? targetChannelOverride : cfg.channel;
        String pass = targetChannelPassOverride != null ? targetChannelPassOverride : cfg.channelPassword;
        int chId = resolveChannelId(client, target);
        if (chId <= 0) {
            chId = client.getRootChannelID();
            if (chId <= 0) chId = 1;
        }
        appendLog("Вход в канал «" + (isBlank(target) ? "/" : target) + "» (ID " + chId + ")…");
        client.doJoinChannelByID(chId, pass != null ? pass : "");
    }

    private int resolveChannelId(TeamTalkBase client, String spec) {
        if (isBlank(spec) || "/".equals(spec.trim())) {
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
            if (!isBlank(p)) return p;
        } catch (Throwable ignored) {
        }
        synchronized (stateLock) {
            Channel ch = channelsMap.get(chId);
            if (ch != null) return buildChannelPath(ch, channelsMap);
        }
        return "#" + chId;
    }

    // --- Full TT-DJbot Command Engine ---

    private void handleIncomingTextMessage(TextMessage tm, BotConfig cfg) {
        if (tm == null || isBlank(tm.szMessage)) return;
        if (tm.nFromUserID == myUserId) return;

        final boolean isPrivate = tm.nMsgType == TextMsgType.MSGTYPE_USER;
        final boolean isChannel = tm.nMsgType == TextMsgType.MSGTYPE_CHANNEL;
        if (isPrivate && !cfg.allowPrivateCommands) return;
        if (isChannel && !cfg.allowChannelCommands) return;
        if (!isPrivate && !isChannel) return;

        final String raw = tm.szMessage.trim();
        if (raw.isEmpty()) return;

        String clean = raw.startsWith("/") ? raw.substring(1).trim() : raw;
        if (clean.isEmpty()) return;

        String[] parts = clean.split("\\s+", 2);
        final String cmd = parts[0].toLowerCase(Locale.ROOT);
        final String arg = parts.length > 1 ? parts[1].trim() : "";

        if (!isKnownCommand(cmd)) {
            if (isPrivate) {
                User senderUser;
                synchronized (stateLock) {
                    senderUser = usersMap.get(tm.nFromUserID);
                }
                String nick = senderUser != null && !isBlank(senderUser.szNickname)
                        ? senderUser.szNickname : tm.szFromUsername;
                telegramBridge.forwardPrivateMessage(tm.nFromUserID, nick, tm.szFromUsername, raw);
            }
            return;
        }

        final int fromUserId = tm.nFromUserID;
        final String fromUsername = tm.szFromUsername != null ? tm.szFromUsername : "";
        final boolean replyToChannel = isChannel;

        appendLog("Команда от " + (!isBlank(fromUsername) ? fromUsername : ("#" + fromUserId))
                + (isChannel ? " [канал]" : " [ЛС]") + ": " + raw);

        commandExecutor.execute(() -> executeCommand(cmd, arg, fromUserId, fromUsername, replyToChannel));
    }

    public static boolean isKnownCommand(String cmd) {
        if (cmd == null) return false;
        switch (cmd) {
            case "p":
            case "play":
            case "s":
            case "stop":
            case "n":
            case "next":
            case "skip":
            case "sp":
            case "pause":
            case "b":
            case "prev":
            case "v":
            case "vol":
            case "sf":
            case "sb":
            case "q":
            case "queue":
            case "playlist":
            case "c":
            case "clear":
            case "r":
            case "radio":
            case "voln":
            case "now":
            case "cg":
            case "tv":
            case "pop":
            case "ym":
            case "sv":
            case "fav":
            case "f":
            case "vo":
            case "welcome":
            case "settings":
            case "set":
            case "j":
            case "st":
            case "sr":
            case "sl":
            case "h":
            case "help":
                return true;
            default:
                return false;
        }
    }

    private void executeCommand(String cmd, String arg, int fromUserId, String fromUsername, boolean replyToChannel) {
        BotConfig cfg = this.activeConfig != null ? this.activeConfig : BotConfig.createDefault();
        switch (cmd) {
            case "h":
            case "help":
                reply(fromUserId, replyToChannel,
                        "🎧 Команды DJ Bot Extro (работают с / и без /):\n"
                        + "/play <запрос|URL> (p) — найти и включить\n"
                        + "/ym <запрос | chart | likes> — Яндекс.Музыка\n"
                        + "/stop (s) | /skip (n) | /pause (sp) | /v <0-100>\n"
                        + "/sf <сек> | /sb <сек> — перемотка | /playlist (q) — очередь\n"
                        + "/radio [l <стр> | <№> | <поиск>] (r) — FM-радиостанции\n"
                        + "/voln [l <стр> | <№> | <поиск>] — живые станции Волнореза\n"
                        + "/tv [l <стр> | <№> | <поиск> | epg | stop] — ТВ-каналы (видео+звук) + EPG\n"
                        + "/now — что сейчас в эфире (трек / ICY / Волнорез / ТВ EPG)\n"
                        + "/pop — случайный популярный русский хит\n"
                        + "/sv [hitmo|mf|rt|ym] — сервис музыки (сейчас: " + activeService + ")\n"
                        + "/fav [+ | - <№> | <№>] — избранное | /vo — озвучка треков\n"
                        + "Админам: /welcome <текст>, /settings, /set <ключ> <знач>, /j <канал>");
                break;

            case "p":
            case "play":
                if (isBlank(arg)) {
                    if (streamer.isRunning()) {
                        togglePause();
                        reply(fromUserId, replyToChannel, streamer.isPaused() ? "⏸ Пауза" : "▶ Продолжено");
                    } else {
                        reply(fromUserId, replyToChannel, "Укажите запрос или ссылку: /play <песня>");
                    }
                    return;
                }
                try {
                    List<BotMusicSearch.BotTrack> found = BotMusicSearch.search(activeService, arg, cfg.yandexToken);
                    if (found == null || found.isEmpty()) {
                        reply(fromUserId, replyToChannel, "Ничего не найдено (" + activeService + "): " + arg);
                        return;
                    }
                    synchronized (stateLock) {
                        lastSearchResults = new ArrayList<>(found);
                    }
                    if (YandexMusicClient.isYandexMusicUrl(arg) && found.size() > 1) {
                        enqueuePlaylistTracks(found, false);
                        reply(fromUserId, replyToChannel, "🟡 Яндекс.Музыка: играет " + found.get(0).getDisplayName()
                                + " (+ в очереди " + (found.size() - 1) + ")");
                    } else if (selectMode && !BotMusicSearch.isDirectUrl(arg) && found.size() > 1) {
                        StringBuilder sb = new StringBuilder("Результаты (" + activeService + "), выберите /sl <№>:\n");
                        int lim = Math.min(found.size(), 10);
                        for (int i = 0; i < lim; i++) {
                            sb.append(i + 1).append(". ").append(found.get(i).getDisplayName()).append("\n");
                        }
                        reply(fromUserId, replyToChannel, sb.toString().trim());
                    } else {
                        enqueueOrPlay(found.get(0), fromUserId, replyToChannel);
                    }
                } catch (Throwable t) {
                    reply(fromUserId, replyToChannel, "Ошибка поиска: " + t.getMessage());
                }
                break;

            case "ym":
                handleYandexCommand(arg, fromUserId, replyToChannel, cfg);
                break;

            case "sr":
                selectMode = !selectMode;
                reply(fromUserId, replyToChannel, selectMode
                        ? "Режим выбора результатов (sr) ВКЛ — после /play выбирайте /sl <№>"
                        : "Режим выбора результатов (sr) ВЫКЛ — сразу играет первый трек");
                break;

            case "sl": {
                int idx;
                try {
                    idx = Integer.parseInt(arg);
                } catch (NumberFormatException e) {
                    reply(fromUserId, replyToChannel, "Использование: /sl <номер>");
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
                if (isBlank(arg)) {
                    reply(fromUserId, replyToChannel, "Текущий сервис: " + BotMusicSearch.getServiceLabel(activeService)
                            + ". Доступны: hitmo, mf, rt, ym");
                } else {
                    String nextSvc = BotMusicSearch.normalizeService(arg);
                    if (BotMusicSearch.SERVICE_RADIO.equals(nextSvc)) {
                        nextSvc = BotMusicSearch.SERVICE_HITMO;
                    }
                    setService(nextSvc);
                    persistActiveConfig();
                    reply(fromUserId, replyToChannel, "Сервис музыки переключён на: " + BotMusicSearch.getServiceLabel(nextSvc));
                }
                break;

            case "pop":
                reply(fromUserId, replyToChannel, "🎲 Подбираю случайный русский хит…");
                try {
                    BotMusicSearch.BotTrack track = BotMusicSearch.searchRandomPop();
                    startTrack(track, false);
                    reply(fromUserId, replyToChannel, "🎲 Случайный хит: " + track.getDisplayName());
                } catch (Throwable t) {
                    reply(fromUserId, replyToChannel, "Не удалось подобрать трек: " + t.getMessage());
                }
                break;

            case "r":
            case "radio":
                handleRadioCommand(arg, fromUserId, replyToChannel);
                break;

            case "voln":
                handleVolnorezCommand(arg, fromUserId, replyToChannel);
                break;

            case "tv":
                handleTvCommand(arg, fromUserId, replyToChannel);
                break;

            case "fav":
            case "f":
                handleFavCommand(arg, fromUserId, replyToChannel);
                break;

            case "vo":
                cfg.voiceAnnounceTrack = !cfg.voiceAnnounceTrack;
                persistActiveConfig();
                notifyStateChanged();
                reply(fromUserId, replyToChannel, cfg.voiceAnnounceTrack
                        ? "🗣️ Озвучка названия треков в эфир (/vo): ВКЛ"
                        : "🔇 Озвучка названия треков в эфир (/vo): ВЫКЛ");
                break;

            case "welcome":
                if (!cfg.isAdmin(fromUsername)) {
                    reply(fromUserId, replyToChannel, "Нет прав администратора для смены приветствия");
                    return;
                }
                if (isBlank(arg)) {
                    reply(fromUserId, replyToChannel, "Приветствие (" + (cfg.welcomeEnabled ? "ВКЛ" : "ВЫКЛ") + "): "
                            + (isBlank(cfg.welcomeText) ? "(пусто)" : cfg.welcomeText));
                } else {
                    cfg.welcomeText = arg;
                    cfg.welcomeEnabled = true;
                    persistActiveConfig();
                    reply(fromUserId, replyToChannel, "👋 Приветствие сервера обновлено и включено.");
                }
                break;

            case "settings":
                reply(fromUserId, replyToChannel, formatSettingsSummary(cfg));
                break;

            case "set":
                handleSetCommand(arg, fromUserId, fromUsername, replyToChannel, cfg);
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
            case "queue":
            case "playlist": {
                StringBuilder sb = new StringBuilder();
                if (currentTrack != null) {
                    sb.append("Сейчас в эфире: ").append(currentTrack.getDisplayName()).append("\n");
                }
                synchronized (stateLock) {
                    if (queue.isEmpty()) {
                        sb.append("Очередь пуста.");
                    } else {
                        sb.append("Очередь (").append(queue.size()).append("):\n");
                        for (int i = 0; i < Math.min(queue.size(), 15); i++) {
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
                if (isBlank(arg)) {
                    reply(fromUserId, replyToChannel, "🔊 Громкость: " + streamer.getVolume() + "%");
                } else {
                    try {
                        int val = Integer.parseInt(arg);
                        setVolume(val);
                        persistActiveConfig();
                        reply(fromUserId, replyToChannel, "🔊 Громкость установлена: " + streamer.getVolume() + "%");
                    } catch (NumberFormatException e) {
                        reply(fromUserId, replyToChannel, "Укажите число от 0 до 100");
                    }
                }
                break;

            case "sf":
            case "sb": {
                int delta;
                if (isBlank(arg)) {
                    delta = "sb".equals(cmd) ? -15 : 15;
                } else {
                    try {
                        int parsed = Integer.parseInt(arg);
                        int clamped = Math.max(-3600, Math.min(3600, parsed));
                        delta = "sb".equals(cmd) ? -Math.abs(clamped) : clamped;
                    } catch (NumberFormatException e) {
                        delta = "sb".equals(cmd) ? -15 : 15;
                    }
                }
                seekBySeconds(delta);
                reply(fromUserId, replyToChannel, "⏩ Перемотка " + (delta >= 0 ? "+" : "") + delta + " сек");
                break;
            }

            case "now":
            case "cg":
                reply(fromUserId, replyToChannel, buildNowPlayingReply());
                break;

            case "j":
                if (!cfg.isAdmin(fromUsername)) {
                    reply(fromUserId, replyToChannel, "Нет прав администратора для смены канала");
                    return;
                }
                if (isBlank(arg)) {
                    reply(fromUserId, replyToChannel, "Использование: /j <путь или ID канала>");
                    return;
                }
                switchChannelFromCommand(arg, fromUserId, replyToChannel, false);
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

    private void handleRadioCommand(String arg, int fromUserId, boolean replyToChannel) {
        if (isBlank(arg)) {
            reply(fromUserId, replyToChannel, formatPagedCatalog("📻 Радиостанции", "/radio", DjBotCatalogs.BUILT_IN_RADIO, 1));
            return;
        }
        Integer page = parseListPageArg(arg);
        if (page != null) {
            reply(fromUserId, replyToChannel, formatPagedCatalog("📻 Радиостанции", "/radio", DjBotCatalogs.BUILT_IN_RADIO, page));
            return;
        }
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

    private void handleVolnorezCommand(String arg, int fromUserId, boolean replyToChannel) {
        List<BotMusicSearch.BotTrack> all = DjBotCatalogs.getVolnorezStations(false);
        if (isBlank(arg)) {
            reply(fromUserId, replyToChannel, formatPagedCatalog("🌊 Станции Волнореза", "/voln", all, 1));
            return;
        }
        Integer page = parseListPageArg(arg);
        if (page != null) {
            reply(fromUserId, replyToChannel, formatPagedCatalog("🌊 Станции Волнореза", "/voln", all, page));
            return;
        }
        List<BotMusicSearch.BotTrack> found = DjBotCatalogs.searchVolnorez(arg);
        if (found.isEmpty()) {
            reply(fromUserId, replyToChannel, "Станция Волнореза не найдена: " + arg);
            return;
        }
        startTrack(found.get(0), false);
        reply(fromUserId, replyToChannel, "🌊 Волнорез: " + found.get(0).title);
    }

    private void handleTvCommand(String arg, int fromUserId, boolean replyToChannel) {
        epg.ensureLoadedAsync();
        if (isBlank(arg)) {
            reply(fromUserId, replyToChannel, formatPagedCatalog("📺 ТВ-каналы", "/tv", DjBotCatalogs.BUILT_IN_TV, 1));
            return;
        }
        String low = arg.toLowerCase(Locale.ROOT).trim();
        if ("stop".equals(low) || "s".equals(low)) {
            stopPlayback();
            reply(fromUserId, replyToChannel, "📺 ТВ остановлено");
            return;
        }
        if ("epg".equals(low) || low.startsWith("epg ")) {
            String queryCh = low.length() > 3 ? arg.substring(3).trim() : "";
            String targetChannelName = queryCh;
            if (isBlank(targetChannelName) && currentTrack != null && DjBotCatalogs.SERVICE_TV.equals(currentTrack.service)) {
                targetChannelName = currentTrack.title;
            }
            if (isBlank(targetChannelName)) {
                reply(fromUserId, replyToChannel, "Укажите канал: /tv epg <название> или включите ТВ-канал.");
                return;
            }
            DjBotEpg.EpgInfo info = epg.getNow(targetChannelName);
            if (info == null) {
                reply(fromUserId, replyToChannel, epg.isLoaded()
                        ? ("Телепрограмма для «" + targetChannelName + "» не найдена.")
                        : "Телепрограмма (XMLTV EPG) загружается, повторите через несколько секунд…");
            } else {
                reply(fromUserId, replyToChannel, info.formatDetailed(targetChannelName));
            }
            return;
        }
        Integer page = parseListPageArg(arg);
        if (page != null) {
            reply(fromUserId, replyToChannel, formatPagedCatalog("📺 ТВ-каналы", "/tv", DjBotCatalogs.BUILT_IN_TV, page));
            return;
        }
        List<BotMusicSearch.BotTrack> found = DjBotCatalogs.searchTv(arg);
        if (found.isEmpty()) {
            reply(fromUserId, replyToChannel, "ТВ-канал не найден: " + arg);
            return;
        }
        startTvTrack(found.get(0), fromUserId, replyToChannel, false);
    }

    private void startTvTrack(BotMusicSearch.BotTrack channel, int fromUserId, boolean replyToChannel, boolean fromUi) {
        appendLog("📺 Проверка ТВ-потока: " + channel.title);
        String blockedReason = DjBotCatalogs.probeTvUrlBlocked(channel.url);
        if (blockedReason != null) {
            String err = "⚠️ ТВ «" + channel.title + "» недоступен: " + blockedReason;
            appendLog(err);
            if (!fromUi && fromUserId > 0) {
                reply(fromUserId, replyToChannel, err);
            }
            return;
        }
        startTrack(channel, fromUi);
        lastEpgTickMs = SystemClock.uptimeMillis();
        DjBotEpg.EpgInfo epgInfo = refreshCurrentTvEpg(false);
        if (!fromUi && fromUserId > 0) {
            if (epgInfo != null) {
                reply(fromUserId, replyToChannel, epgInfo.formatDetailed(channel.title));
            } else {
                reply(fromUserId, replyToChannel, "📺 Включён ТВ-канал: " + channel.title);
            }
        }
    }

    private void handleFavCommand(String arg, int fromUserId, boolean replyToChannel) {
        List<BotConfig.FavoriteItem> favs = BotConfig.loadFavorites(appContext);
        if (isBlank(arg)) {
            if (favs.isEmpty()) {
                reply(fromUserId, replyToChannel, "Избранное пусто. Добавьте: /fav + (текущий трек) или /fav + <ссылка>");
                return;
            }
            StringBuilder sb = new StringBuilder("⭐ Избранное:\n");
            for (int i = 0; i < favs.size(); i++) {
                sb.append(i + 1).append(". ").append(favs.get(i).label).append("\n");
            }
            sb.append("/fav <№> — включить | /fav + — добавить | /fav - <№> — удалить");
            reply(fromUserId, replyToChannel, sb.toString().trim());
            return;
        }
        if (arg.equals("+")) {
            BotMusicSearch.BotTrack cur = this.currentTrack;
            if (cur == null || isBlank(cur.url)) {
                reply(fromUserId, replyToChannel, "Сейчас ничего не играет. Используйте: /fav + <ссылка>");
                return;
            }
            for (BotConfig.FavoriteItem it : favs) {
                if (cur.url.equals(it.url)) {
                    reply(fromUserId, replyToChannel, "Уже есть в избранном: " + it.label);
                    return;
                }
            }
            favs.add(new BotConfig.FavoriteItem(cur.getDisplayName(), cur.url, cur.service, cur.isLiveStream));
            BotConfig.saveFavorites(appContext, favs);
            reply(fromUserId, replyToChannel, "⭐ Добавлено в избранное (#" + favs.size() + "): " + cur.getDisplayName());
            return;
        }
        if (arg.startsWith("+ ")) {
            String url = arg.substring(2).trim();
            if (!BotMusicSearch.isDirectUrl(url)) {
                reply(fromUserId, replyToChannel, "Укажите корректную ссылку http(s)://…");
                return;
            }
            favs.add(new BotConfig.FavoriteItem(url, url, "url", true));
            BotConfig.saveFavorites(appContext, favs);
            reply(fromUserId, replyToChannel, "⭐ Ссылка добавлена в избранное (#" + favs.size() + ")");
            return;
        }
        if (arg.startsWith("-")) {
            String numStr = arg.substring(1).trim();
            try {
                int idx = Integer.parseInt(numStr);
                if (idx >= 1 && idx <= favs.size()) {
                    BotConfig.FavoriteItem removed = favs.remove(idx - 1);
                    BotConfig.saveFavorites(appContext, favs);
                    reply(fromUserId, replyToChannel, "Удалено из избранного: " + removed.label);
                } else {
                    reply(fromUserId, replyToChannel, "Нет записи #" + idx);
                }
            } catch (NumberFormatException e) {
                reply(fromUserId, replyToChannel, "Использование: /fav - <номер>");
            }
            return;
        }
        try {
            int idx = Integer.parseInt(arg.trim());
            if (idx >= 1 && idx <= favs.size()) {
                BotConfig.FavoriteItem it = favs.get(idx - 1);
                BotMusicSearch.BotTrack track = new BotMusicSearch.BotTrack(
                        it.label, "", it.url, !isBlank(it.service) ? it.service : "url", it.isLive, null, null);
                if (DjBotCatalogs.SERVICE_TV.equals(track.service)) {
                    startTvTrack(track, fromUserId, replyToChannel, false);
                } else {
                    startTrack(track, false);
                    reply(fromUserId, replyToChannel, "⭐ Играет из избранного: " + it.label);
                }
            } else {
                reply(fromUserId, replyToChannel, "Нет записи #" + idx + " в избранном");
            }
        } catch (NumberFormatException e) {
            reply(fromUserId, replyToChannel, "Использование: /fav [<№> | + | - <№>]");
        }
    }

    private void handleYandexCommand(String arg, int fromUserId, boolean replyToChannel, BotConfig cfg) {
        if (cfg == null || isBlank(cfg.yandexToken)) {
            reply(fromUserId, replyToChannel,
                    "🟡 Яндекс.Музыка: не указан OAuth-токен. Укажите его в профиле DJ Bot или командой /set services.ym.token <токен>");
            return;
        }
        if (isBlank(arg)) {
            reply(fromUserId, replyToChannel,
                    "🟡 Яндекс.Музыка:\n/ym <запрос или ссылка> — поиск и воспроизведение\n/ym chart — топ-чарт\n/ym likes — ваши лайки");
            return;
        }
        String low = arg.trim().toLowerCase(Locale.ROOT);
        try {
            if ("chart".equals(low) || "чарт".equals(low) || "top".equals(low)) {
                List<BotMusicSearch.BotTrack> tracks = YandexMusicClient.getChartTracks(cfg.yandexToken, 20);
                if (tracks.isEmpty()) {
                    reply(fromUserId, replyToChannel, "Чарт Яндекс.Музыки пуст.");
                    return;
                }
                enqueuePlaylistTracks(tracks, false);
                reply(fromUserId, replyToChannel, "📈 Чарт Яндекс.Музыки: " + tracks.get(0).getDisplayName()
                        + " (+ в очереди " + (tracks.size() - 1) + ")");
                return;
            }
            if ("likes".equals(low) || "like".equals(low) || "лайки".equals(low) || "любимое".equals(low)) {
                List<BotMusicSearch.BotTrack> tracks = YandexMusicClient.getLikedTracks(cfg.yandexToken, 25);
                if (tracks.isEmpty()) {
                    reply(fromUserId, replyToChannel, "Список любимых треков Яндекс.Музыки пуст.");
                    return;
                }
                enqueuePlaylistTracks(tracks, false);
                reply(fromUserId, replyToChannel, "❤️ Лайки Яндекс.Музыки: " + tracks.get(0).getDisplayName()
                        + " (+ в очереди " + (tracks.size() - 1) + ")");
                return;
            }
            List<BotMusicSearch.BotTrack> found = YandexMusicClient.isYandexMusicUrl(arg)
                    ? YandexMusicClient.resolveYandexUrl(cfg.yandexToken, arg)
                    : YandexMusicClient.searchTracks(cfg.yandexToken, arg, 15);
            if (found == null || found.isEmpty()) {
                reply(fromUserId, replyToChannel, "🟡 Яндекс.Музыка: ничего не найдено по запросу «" + arg + "»");
                return;
            }
            synchronized (stateLock) {
                lastSearchResults = new ArrayList<>(found);
            }
            if (YandexMusicClient.isYandexMusicUrl(arg) && found.size() > 1) {
                enqueuePlaylistTracks(found, false);
                reply(fromUserId, replyToChannel, "🟡 Плейлист ЯМ: " + found.get(0).getDisplayName()
                        + " (+ в очереди " + (found.size() - 1) + ")");
            } else {
                enqueueOrPlay(found.get(0), fromUserId, replyToChannel);
            }
        } catch (Throwable t) {
            reply(fromUserId, replyToChannel, "🟡 Ошибка Яндекс.Музыки: " + t.getMessage());
        }
    }

    private void handleSetCommand(String arg, int fromUserId, String fromUsername, boolean replyToChannel, BotConfig cfg) {
        if (!cfg.isAdmin(fromUsername)) {
            reply(fromUserId, replyToChannel, "Нет прав администратора для изменения настроек");
            return;
        }
        if (isBlank(arg)) {
            reply(fromUserId, replyToChannel,
                    "Использование: /set <ключ> <значение>\n"
                    + "Ключи: welcome, welcome.enabled, volume, channel, guard, guard.empty_nick, guard.mat, "
                    + "guard.botnet, guard.ban, service, vo, services.ym.token, telegram.token, telegram.owner, "
                    + "telegram.notify_chat, tv.bitrate, tv.audio_only");
            return;
        }
        String[] kv = arg.split("\\s+", 2);
        String key = kv[0].trim().toLowerCase(Locale.ROOT);
        String val = kv.length > 1 ? kv[1].trim() : "";
        if (val.isEmpty()) {
            reply(fromUserId, replyToChannel, "Укажите значение для " + key);
            return;
        }
        switch (key) {
            case "welcome":
            case "welcome.text":
                cfg.welcomeText = val;
                cfg.welcomeEnabled = true;
                persistActiveConfig();
                reply(fromUserId, replyToChannel, "✅ welcome обновлён и включён.");
                break;

            case "welcome.enabled":
                cfg.welcomeEnabled = parseOnOff(val);
                persistActiveConfig();
                reply(fromUserId, replyToChannel, "✅ welcome.enabled = " + (cfg.welcomeEnabled ? "on" : "off"));
                break;

            case "volume":
            case "playback.default_volume":
                try {
                    int vol = Integer.parseInt(val);
                    setVolume(vol);
                    persistActiveConfig();
                    reply(fromUserId, replyToChannel, "✅ volume = " + streamer.getVolume() + "%");
                } catch (NumberFormatException e) {
                    reply(fromUserId, replyToChannel, "Укажите громкость 0–100");
                }
                break;

            case "channel":
            case "server.channel":
                switchChannelFromCommand(val, fromUserId, replyToChannel, true);
                break;

            case "guard":
            case "guard.enabled":
                cfg.guardEnabled = parseOnOff(val);
                persistActiveConfig();
                notifyStateChanged();
                reply(fromUserId, replyToChannel, "🛡️ Guard = " + (cfg.guardEnabled ? "ВКЛ" : "ВЫКЛ"));
                break;

            case "guard.empty_nick":
                cfg.guardKickEmptyNick = parseOnOff(val);
                persistActiveConfig();
                reply(fromUserId, replyToChannel, "🛡️ guard.empty_nick = " + (cfg.guardKickEmptyNick ? "on" : "off"));
                break;

            case "guard.mat":
                cfg.guardMatCheck = parseOnOff(val);
                persistActiveConfig();
                reply(fromUserId, replyToChannel, "🛡️ guard.mat = " + (cfg.guardMatCheck ? "on" : "off"));
                break;

            case "guard.botnet":
                cfg.guardBotnetNickCheck = parseOnOff(val);
                persistActiveConfig();
                reply(fromUserId, replyToChannel, "🛡️ guard.botnet = " + (cfg.guardBotnetNickCheck ? "on" : "off"));
                break;

            case "guard.ban":
                cfg.guardBanOnViolation = parseOnOff(val);
                persistActiveConfig();
                reply(fromUserId, replyToChannel, "🛡️ guard.ban = " + (cfg.guardBanOnViolation ? "on" : "off"));
                break;

            case "service":
                String svc = BotMusicSearch.normalizeService(val);
                setService(svc);
                persistActiveConfig();
                reply(fromUserId, replyToChannel, "✅ service = " + BotMusicSearch.getServiceLabel(svc));
                break;

            case "vo":
                cfg.voiceAnnounceTrack = parseOnOff(val);
                persistActiveConfig();
                notifyStateChanged();
                reply(fromUserId, replyToChannel, "✅ vo = " + (cfg.voiceAnnounceTrack ? "on" : "off"));
                break;

            case "services.ym.token":
            case "ym.token":
                cfg.yandexToken = "clear".equalsIgnoreCase(val) || "off".equalsIgnoreCase(val) ? "" : val;
                streamer.configure(cfg.yandexToken, cfg.tvVideoBitrate, cfg.tvAudioOnly);
                persistActiveConfig();
                reply(fromUserId, replyToChannel, isBlank(cfg.yandexToken)
                        ? "✅ Токен Яндекс.Музыки очищен"
                        : "✅ Токен Яндекс.Музыки сохранён (***)");
                break;

            case "telegram.token":
            case "tg.token":
                cfg.telegramBotToken = "clear".equalsIgnoreCase(val) || "off".equalsIgnoreCase(val) ? "" : val;
                persistActiveConfig();
                if (isBlank(cfg.telegramBotToken)) {
                    telegramBridge.stop();
                    reply(fromUserId, replyToChannel, "✅ Telegram-бот отключён");
                } else {
                    telegramBridge.start(cfg.telegramBotToken);
                    reply(fromUserId, replyToChannel, "✅ Токен Telegram-бота сохранён (***) и запущен");
                }
                break;

            case "telegram.owner":
            case "tg.owner":
                cfg.telegramOwnerUserId = val;
                persistActiveConfig();
                reply(fromUserId, replyToChannel, "✅ telegram.owner = " + cfg.telegramOwnerUserId);
                break;

            case "telegram.notify_chat":
            case "tg.notify_chat":
                cfg.telegramNotifyChatId = val;
                persistActiveConfig();
                reply(fromUserId, replyToChannel, "✅ telegram.notify_chat = " + cfg.telegramNotifyChatId);
                break;

            case "tv.bitrate":
                try {
                    int kbps = Integer.parseInt(val);
                    cfg.tvVideoBitrate = Math.max(BotConfig.MIN_TV_VIDEO_BITRATE,
                            Math.min(BotConfig.MAX_TV_VIDEO_BITRATE, kbps));
                    streamer.configure(cfg.yandexToken, cfg.tvVideoBitrate, cfg.tvAudioOnly);
                    persistActiveConfig();
                    reply(fromUserId, replyToChannel, "✅ tv.bitrate = " + cfg.tvVideoBitrate + " kbps");
                } catch (NumberFormatException e) {
                    reply(fromUserId, replyToChannel, "Укажите битрейт 250–2500 kbps");
                }
                break;

            case "tv.audio_only":
                cfg.tvAudioOnly = parseOnOff(val);
                streamer.configure(cfg.yandexToken, cfg.tvVideoBitrate, cfg.tvAudioOnly);
                persistActiveConfig();
                reply(fromUserId, replyToChannel, "✅ tv.audio_only = " + (cfg.tvAudioOnly ? "on" : "off"));
                break;

            default:
                reply(fromUserId, replyToChannel, "Неизвестный ключ: " + key);
                break;
        }
    }

    private void switchChannelFromCommand(String arg, int fromUserId, boolean replyToChannel, boolean persist) {
        TeamTalkBase client = this.botClient;
        if (client == null) return;
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
            if (persist && activeConfig != null) {
                activeConfig.channel = arg;
                persistActiveConfig();
            }
            client.doJoinChannelByID(chId, chPass);
            reply(fromUserId, replyToChannel, "Перехожу в канал #" + chId);
        } else {
            reply(fromUserId, replyToChannel, "Канал не найден: " + arg);
        }
    }

    private String formatSettingsSummary(BotConfig cfg) {
        String maskedPass = isBlank(cfg.password) ? "(нет)" : "***";
        String maskedChanPass = isBlank(cfg.channelPassword) ? "(нет)" : "***";
        String maskedYm = isBlank(cfg.yandexToken) ? "(не задан)" : "***";
        String maskedTg = isBlank(cfg.telegramBotToken) ? "(выкл)" : "***";
        String tgState = telegramBridge.isConnected() && !isBlank(telegramBridge.getBotUsername())
                ? ("@" + telegramBridge.getBotUsername())
                : (isBlank(cfg.telegramBotToken) ? "выкл" : "подключение");
        return "⚙️ Настройки DJ Bot:\n"
                + "Сервер: " + cfg.host + ":" + cfg.tcpPort + (cfg.encrypted ? " (TLS)" : "") + "\n"
                + "Аккаунт: " + (isBlank(cfg.username) ? "(гость)" : cfg.username) + " / пароль: " + maskedPass + "\n"
                + "Ник: " + cfg.nickname + " | Канал: " + cfg.channel + " (пароль: " + maskedChanPass + ")\n"
                + "Сервис: " + BotMusicSearch.getServiceLabel(activeService) + " | Громкость: " + streamer.getVolume() + "%\n"
                + "Яндекс.Музыка: " + maskedYm + " | ТВ видео: "
                + (cfg.tvAudioOnly ? "только звук" : (cfg.tvVideoBitrate + " kbps")) + "\n"
                + "Telegram-бот: " + maskedTg + " (" + tgState + ")\n"
                + "Озвучка треков (/vo): " + (cfg.voiceAnnounceTrack ? "ВКЛ" : "ВЫКЛ") + "\n"
                + "Приветствие: " + (cfg.welcomeEnabled ? "ВКЛ" : "ВЫКЛ")
                + (isBlank(cfg.welcomeText) ? "" : (" — " + cfg.welcomeText)) + "\n"
                + "🛡️ Guard: " + (cfg.guardEnabled ? "ВКЛ" : "ВЫКЛ")
                + " (пустые=" + (cfg.guardKickEmptyNick ? "да" : "нет")
                + ", мат=" + (cfg.guardMatCheck ? "да" : "нет")
                + ", ботнет=" + (cfg.guardBotnetNickCheck ? "да" : "нет")
                + ", бан=" + (cfg.guardBanOnViolation ? "IP-бан" : "кик") + ")";
    }

    private String buildNowPlayingReply() {
        BotMusicSearch.BotTrack cur = this.currentTrack;
        if (cur == null) {
            return "Сейчас ничего не играет";
        }
        if (DjBotCatalogs.SERVICE_TV.equals(cur.service)) {
            DjBotEpg.EpgInfo info = epg.getNow(cur.title);
            if (info != null) {
                return info.formatDetailed(cur.title);
            }
            return "📺 ТВ: " + cur.title + (streamer.isPaused() ? " (на паузе)" : "");
        }
        StringBuilder sb = new StringBuilder();
        if (DjBotCatalogs.SERVICE_VOLN.equals(cur.service)) {
            sb.append("🌊 Волнорез: ").append(cur.title);
        } else if (BotMusicSearch.SERVICE_RADIO.equals(cur.service)) {
            sb.append("📻 Радио: ").append(cur.title);
        } else {
            sb.append("🎵 ").append(cur.getDisplayName());
        }
        if (!isBlank(liveIcyTitle)) {
            sb.append("\nЭфир: ").append(liveIcyTitle);
        }
        if (liveListeners >= 0) {
            sb.append("\nСлушателей на станции: ").append(liveListeners);
        }
        if (streamer.isPaused()) {
            sb.append(" (на паузе)");
        }
        return sb.toString();
    }

    private static Integer parseListPageArg(String arg) {
        if (isBlank(arg)) return null;
        String[] parts = arg.trim().split("\\s+");
        if (parts.length >= 1 && ("l".equalsIgnoreCase(parts[0]) || "list".equalsIgnoreCase(parts[0]))) {
            if (parts.length == 1) return 1;
            try {
                return Math.max(1, Integer.parseInt(parts[1]));
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        return null;
    }

    private static String formatPagedCatalog(String title, String cmdPrefix, List<BotMusicSearch.BotTrack> items, int page) {
        if (items == null || items.isEmpty()) {
            return title + ": список пуст.";
        }
        int totalPages = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int safePage = Math.max(1, Math.min(totalPages, page));
        int from = (safePage - 1) * PAGE_SIZE;
        int to = Math.min(items.size(), from + PAGE_SIZE);

        StringBuilder sb = new StringBuilder();
        sb.append(title).append(" (стр. ").append(safePage).append("/").append(totalPages)
                .append(", всего ").append(items.size()).append("):\n");
        for (int i = from; i < to; i++) {
            sb.append(i + 1).append(". ").append(items.get(i).title).append("\n");
        }
        sb.append("Включить: ").append(cmdPrefix).append(" <№> | Поиск: ").append(cmdPrefix).append(" <текст>");
        if (totalPages > 1) {
            sb.append(" | Стр.: ").append(cmdPrefix).append(" l <стр>");
        }
        return sb.toString().trim();
    }

    private static boolean parseOnOff(String val) {
        String v = val != null ? val.trim().toLowerCase(Locale.ROOT) : "";
        return "on".equals(v) || "1".equals(v) || "true".equals(v) || "yes".equals(v) || "да".equals(v) || "вкл".equals(v);
    }

    private void persistActiveConfig() {
        BotConfig cfg = this.activeConfig;
        if (cfg != null) {
            try {
                BotConfig.upsert(appContext, cfg);
            } catch (Throwable ignored) {
            }
        }
    }

    // --- Playback & Track Announcements ---

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
            this.currentEpgSummary = "";
            this.liveListeners = -1;
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
            if (DjBotCatalogs.SERVICE_TV.equals(track.service)) {
                updateBotStatus("📺 " + track.title);
            } else if (DjBotCatalogs.SERVICE_VOLN.equals(track.service)) {
                updateBotStatus("🌊 " + track.title);
            } else if (BotMusicSearch.SERVICE_RADIO.equals(track.service)) {
                updateBotStatus("📻 " + track.title);
            } else {
                updateBotStatus("🎵 " + track.getDisplayName());
            }
        }
        if (announceToChannel && cfg != null && cfg.sendChannelAnnouncements && currentChannelId > 0) {
            sendChannelMessage("🎵 " + track.getDisplayName());
        }

        File introWav = null;
        if (cfg != null && cfg.voiceAnnounceTrack) {
            String spoken = DjBotCatalogs.SERVICE_TV.equals(track.service)
                    ? ("Телеканал " + track.title)
                    : ("Сейчас играет " + track.getDisplayName());
            introWav = synthesizeAnnouncementWav(spoken);
        }

        streamer.play(track, introWav);
        notifyStateChanged();
    }

    private DjBotEpg.EpgInfo refreshCurrentTvEpg(boolean quiet) {
        BotMusicSearch.BotTrack cur = this.currentTrack;
        if (cur == null || !DjBotCatalogs.SERVICE_TV.equals(cur.service)) return null;
        DjBotEpg.EpgInfo info = epg.getNow(cur.title);
        if (info != null) {
            String status = info.formatStatus(cur.title);
            this.currentEpgSummary = status;
            BotConfig cfg = this.activeConfig;
            if (cfg != null && cfg.autoStatusTrack) {
                updateBotStatus(status);
            }
            if (!quiet) {
                appendLog(status);
            }
            notifyStateChanged();
        }
        return info;
    }

    private File synthesizeAnnouncementWav(String text) {
        if (isBlank(text)) return null;
        try {
            ensureBotTtsReady();
            if (!botTtsReady || botTts == null) return null;

            File outFile = new File(appContext.getCacheDir(), "djbot_vo_" + voCounter.incrementAndGet() + ".wav");
            if (outFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outFile.delete();
            }
            final String uttId = "djbot_vo_" + voCounter.get();
            final CountDownLatch doneLatch = new CountDownLatch(1);
            final boolean[] ok = {false};

            synchronized (ttsLock) {
                botTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) {}
                    @Override public void onDone(String utteranceId) {
                        if (uttId.equals(utteranceId)) {
                            ok[0] = true;
                            doneLatch.countDown();
                        }
                    }
                    @Override @SuppressWarnings("deprecation") public void onError(String utteranceId) {
                        if (uttId.equals(utteranceId)) doneLatch.countDown();
                    }
                    @Override public void onError(String utteranceId, int errorCode) {
                        if (uttId.equals(utteranceId)) doneLatch.countDown();
                    }
                });
                int res = botTts.synthesizeToFile(text.trim(), new Bundle(), outFile, uttId);
                if (res != TextToSpeech.SUCCESS) {
                    return null;
                }
            }
            doneLatch.await(3500, TimeUnit.MILLISECONDS);
            if (ok[0] && outFile.exists() && outFile.length() > 44) {
                return outFile;
            }
            if (outFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outFile.delete();
            }
        } catch (Throwable t) {
            Log.w(TAG, "DJ Bot TTS /vo synthesis failed: " + t.getMessage());
        }
        return null;
    }

    private void ensureBotTtsReady() {
        if (botTtsReady && botTts != null) return;
        CountDownLatch initLatch = new CountDownLatch(1);
        synchronized (ttsLock) {
            if (botTtsReady && botTts != null) return;
            try {
                botTts = new TextToSpeech(appContext, status -> {
                    if (status == TextToSpeech.SUCCESS && botTts != null) {
                        try {
                            Locale ru = new Locale("ru", "RU");
                            int r = botTts.setLanguage(ru);
                            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                                botTts.setLanguage(Locale.getDefault());
                            }
                            botTts.setSpeechRate(1.05f);
                        } catch (Throwable ignored) {
                        }
                        botTtsReady = true;
                    }
                    initLatch.countDown();
                });
            } catch (Throwable t) {
                initLatch.countDown();
            }
        }
        try {
            initLatch.await(2500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void onTrackFinished(BotMusicSearch.BotTrack track, boolean completedNormally, String errorMessage) {
        if (!isBlank(errorMessage)) {
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
            this.currentEpgSummary = "";
            this.liveListeners = -1;
            updateBotStatus("");
            notifyStateChanged();
        }
    }

    private void onLiveTitleChanged(String liveTitle) {
        this.liveIcyTitle = liveTitle != null ? liveTitle : "";
        boolean isVoln = currentTrack != null && DjBotCatalogs.SERVICE_VOLN.equals(currentTrack.service);
        String prefix = isVoln ? "🌊 " : "📻 ";
        appendLog(prefix + "Эфир: " + this.liveIcyTitle);
        BotConfig cfg = this.activeConfig;
        if (cfg != null && cfg.autoStatusTrack && !isBlank(this.liveIcyTitle)) {
            updateBotStatus(prefix + this.liveIcyTitle);
        }
        notifyStateChanged();
    }

    private void onLiveListenersChanged(int count) {
        this.liveListeners = count;
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

    private final ThreadLocal<StringBuilder> replyCapture = new ThreadLocal<>();

    private void reply(int userId, boolean toChannel, String text) {
        StringBuilder cap = replyCapture.get();
        if (cap != null) {
            if (!isBlank(text)) {
                if (cap.length() > 0) cap.append("\n\n");
                cap.append(text.trim());
            }
            return;
        }
        if (toChannel && currentChannelId > 0) {
            sendChannelMessage(text);
        } else {
            sendPrivateMessage(userId, text);
        }
    }

    private DjBotTelegramBridge.HostCallback createTelegramHostCallback() {
        return new DjBotTelegramBridge.HostCallback() {
            @Override
            public BotConfig getConfig() {
                return activeConfig;
            }

            @Override
            public void log(String message) {
                appendLog(message);
            }

            @Override
            public void onBridgeStateChanged() {
                notifyStateChanged();
            }

            @Override
            public String handleTelegramCommand(String cmd, String arg, boolean isAdmin, String senderLabel) {
                if (!isKnownCommand(cmd)) {
                    return "Неизвестная команда: /" + cmd + ". Список команд: /help";
                }
                appendLog("✈️ TG [" + senderLabel + "]: /" + cmd + (isBlank(arg) ? "" : (" " + arg)));
                StringBuilder buf = new StringBuilder();
                replyCapture.set(buf);
                try {
                    BotConfig cfg = activeConfig != null ? activeConfig : BotConfig.createDefault();
                    String effectiveUser = isAdmin ? firstAdminNameOrSelf(cfg) : "__tg_guest__";
                    executeCommand(cmd, arg, -1, effectiveUser, false);
                } catch (Throwable t) {
                    return "Ошибка команды: " + t.getMessage();
                } finally {
                    replyCapture.remove();
                }
                return buf.toString().trim();
            }

            @Override
            public String getOnlineSummary() {
                return buildOnlineSummaryText();
            }

            @Override
            public String getStatusSummary() {
                return buildBotStatusSummaryText();
            }

            @Override
            public void sendPrivateToTeamTalkUser(int ttUserId, String text, String tgSenderName) {
                sendPrivateMessage(ttUserId, "✈️ [Telegram • " + tgSenderName + "]: " + text);
                appendLog("✈️ Ответ из TG пользователю #" + ttUserId + ": " + text);
            }

            @Override
            public void playTelegramMediaFile(File file, String displayTitle, String senderLabel) {
                if (file == null || !file.exists()) return;
                appendLog("✈️ Медиа из TG [" + senderLabel + "]: " + displayTitle);
                BotMusicSearch.BotTrack track = new BotMusicSearch.BotTrack(
                        displayTitle, "Telegram • " + senderLabel, file.getAbsolutePath(),
                        "tg", false, file.getAbsolutePath(), null);
                commandExecutor.execute(() -> startTrack(track, true));
            }

            @Override
            public String kickByQuery(String query) {
                return kickOrBanUserFromTelegram(query, false);
            }

            @Override
            public String banByQuery(String query) {
                return kickOrBanUserFromTelegram(query, true);
            }

            @Override
            public String broadcastServerText(String text) {
                TeamTalkBase client = botClient;
                if (client == null || isBlank(text)) {
                    return "Бот не подключён к серверу.";
                }
                TextMessage tm = new TextMessage();
                tm.nMsgType = TextMsgType.MSGTYPE_BROADCAST;
                tm.szMessage = text.trim();
                try {
                    client.doTextMessage(tm);
                    appendLog("📢 Рассылка из TG: " + text.trim());
                    return "📢 Сообщение отправлено на весь сервер.";
                } catch (Throwable t) {
                    return "Ошибка рассылки: " + t.getMessage();
                }
            }

            @Override
            public void togglePauseFromTelegram() {
                togglePause();
            }

            @Override
            public void nextFromTelegram() {
                nextTrack();
            }

            @Override
            public void stopFromTelegram() {
                stopPlayback();
            }

            @Override
            public void playPopFromTelegram() {
                playRandomPopFromUi();
            }
        };
    }

    private static String firstAdminNameOrSelf(BotConfig cfg) {
        if (cfg == null || isBlank(cfg.adminUsers)) return "admin";
        String[] parts = cfg.adminUsers.split("[,;\\n\\s]+");
        for (String p : parts) {
            if (!isBlank(p)) return p.trim();
        }
        return "admin";
    }

    private String buildOnlineSummaryText() {
        BotConfig cfg = activeConfig != null ? activeConfig : BotConfig.createDefault();
        String srvName = cfg.getEffectiveTelegramServerName();
        StringBuilder sb = new StringBuilder();
        sb.append("👥 Онлайн на ").append(srvName).append(":\n");
        synchronized (stateLock) {
            if (usersMap.isEmpty()) {
                sb.append("Никого нет на сервере.");
            } else {
                int count = 0;
                for (User u : usersMap.values()) {
                    if (u == null) continue;
                    count++;
                    String nick = !isBlank(u.szNickname) ? u.szNickname : ("#" + u.nUserID);
                    String login = !isBlank(u.szUsername) ? (" (" + u.szUsername + ")") : "";
                    String ch = "";
                    if (u.nChannelID > 0) {
                        Channel c = channelsMap.get(u.nChannelID);
                        ch = c != null && !isBlank(c.szName) ? (" — " + c.szName) : (" — #" + u.nChannelID);
                    }
                    sb.append(count).append(". ").append(nick).append(login).append(ch).append("\n");
                }
            }
        }
        return sb.toString().trim();
    }

    private String buildBotStatusSummaryText() {
        Snapshot s = getSnapshot();
        StringBuilder sb = new StringBuilder();
        sb.append("🎧 Статус DJ Bot:\n");
        sb.append("Состояние: ").append(s.state.name());
        if (!isBlank(s.channelName)) sb.append(" (").append(s.channelName).append(")");
        sb.append("\n");
        sb.append("Эфир: ").append(!isBlank(s.nowPlaying) ? s.nowPlaying : "тишина");
        if (s.streamingVideo) sb.append(" [📺 Видео + звук]");
        if (s.paused) sb.append(" (пауза)");
        sb.append("\n");
        if (!isBlank(s.liveStreamTitle)) {
            sb.append("Метаданные: ").append(s.liveStreamTitle).append("\n");
        }
        if (!isBlank(s.epgSummary)) {
            sb.append(s.epgSummary).append("\n");
        }
        sb.append("Громкость: ").append(s.volume).append("% | Очередь: ").append(s.queueSize)
                .append(" | Сервис: ").append(s.activeService);
        return sb.toString().trim();
    }

    private String kickOrBanUserFromTelegram(String query, boolean ban) {
        TeamTalkBase client = this.botClient;
        if (client == null) return "Бот не подключён к серверу.";
        String q = query.trim().toLowerCase(Locale.ROOT);
        User target = null;
        synchronized (stateLock) {
            for (User u : usersMap.values()) {
                if (u == null || u.nUserID == myUserId) continue;
                if (String.valueOf(u.nUserID).equals(q)
                        || (u.szNickname != null && u.szNickname.toLowerCase(Locale.ROOT).contains(q))
                        || (u.szUsername != null && u.szUsername.toLowerCase(Locale.ROOT).equals(q))) {
                    target = u;
                    break;
                }
            }
        }
        if (target == null) {
            return "Пользователь не найден: " + query;
        }
        String who = !isBlank(target.szNickname) ? target.szNickname : ("#" + target.nUserID);
        try {
            if (ban) {
                client.doBanUserEx(target.nUserID, BanType.BANTYPE_IPADDR);
            }
            client.doKickUser(target.nUserID, 0);
            String note = (ban ? "🔨 Забанен и кикнут: " : "👢 Кикнут: ") + who + " (#" + target.nUserID + ")";
            appendLog("✈️ TG " + note);
            return note;
        } catch (Throwable t) {
            return "Ошибка: " + t.getMessage();
        }
    }

    private void sendChannelMessage(String text) {
        TeamTalkBase client = this.botClient;
        if (client == null || currentChannelId <= 0 || isBlank(text)) return;
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
        if (client == null || toUserId <= 0 || isBlank(text)) return;
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
        u.szIPAddress = src.szIPAddress != null ? src.szIPAddress : "";
        return u;
    }

    private static String buildChannelPath(Channel ch, Map<Integer, Channel> all) {
        if (ch == null) return "/";
        List<String> parts = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        Channel cur = ch;
        while (cur != null && visited.add(cur.nChannelID)) {
            if (!isBlank(cur.szName)) {
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
