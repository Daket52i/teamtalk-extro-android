package org.teamtalkextro.bot;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;
import dk.bearware.AudioBlock;
import dk.bearware.AudioPreprocessor;
import dk.bearware.AudioPreprocessorType;
import dk.bearware.Codec;
import dk.bearware.MediaFileInfo;
import dk.bearware.MediaFilePlayback;
import dk.bearware.MediaFilePlaybackConstants;
import dk.bearware.MediaFileStatus;
import dk.bearware.StreamType;
import dk.bearware.TTAudioPreprocessor;
import dk.bearware.TeamTalkBase;
import dk.bearware.VideoCodec;
import dk.bearware.WebMVP8Codec;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dual-engine DJ Bot media streamer:
 *
 * 1) PRIMARY ENGINE — Native TeamTalk5 FFmpeg streaming via
 *    {@link TeamTalkBase#startStreamingMediaFileToChannel(String, MediaFilePlayback, VideoCodec)}
 *    paired with {@link LocalMediaRelayServer} on 127.0.0.1 for HTTPS radio,
 *    Volnorez, and HLS (.m3u8) TV streams. Supports BOTH VP8 video + audio for
 *    Live TV and local/Telegram video files, plus real-time volume/pause/seek!
 *
 * 2) AUTOMATIC FALLBACK ENGINE — If TV video fails, retries audio-only first.
 *    If the bot account lacks TRANSMIT_MEDIAFILE permission or native FFmpeg
 *    fails before MFS_PLAYING (or within the 14s startup watchdog), falls back
 *    seamlessly to voice-channel {@code insertAudioBlock}:
 *    - Local / downloaded tracks: MediaExtractor + MediaCodec -> 48kHz stereo PCM
 *    - Live HTTP/HTTPS MP3 & ADTS-AAC radio/Volnorez: pure-Java frame parser
 *      feeding MediaCodec directly (no MediaExtractor!) so radio still plays!
 */
public final class BotAudioStreamer {

    private static final String TAG = "BotAudioStreamer";
    private static final int TARGET_SAMPLE_RATE = 48000;
    private static final int TARGET_CHANNELS = 2;
    private static final int FRAME_SAMPLES = 960; // 20 ms at 48 kHz
    private static final int FRAME_SHORTS = FRAME_SAMPLES * TARGET_CHANNELS;
    private static final int FRAME_BYTES = FRAME_SHORTS * 2;
    private static final long FRAME_DURATION_NS = 20_000_000L;
    private static final int DECODE_TIMEOUT_US = 10000;
    private static final int MIN_TRACK_BYTES = 4096;
    private static final long LIVE_STARTUP_WATCHDOG_MS = 14_000L;

    private static final Pattern ICY_STREAM_TITLE = Pattern.compile("StreamTitle='([^']*)'");

    private static final int[] MP3_BITRATES_V1_L3 = {
            0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0
    };
    private static final int[] MP3_BITRATES_V2_L3 = {
            0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0
    };
    private static final int[] MP3_SAMPLERATES_V1 = { 44100, 48000, 32000, 0 };
    private static final int[] MP3_SAMPLERATES_V2 = { 22050, 24000, 16000, 0 };
    private static final int[] MP3_SAMPLERATES_V25 = { 11025, 12000, 8000, 0 };
    private static final int[] ADTS_SAMPLERATES = {
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
            16000, 12000, 11025, 8000, 7350, 0, 0, 0
    };

    public interface OnTrackFinishedListener {
        void onTrackFinished(BotMusicSearch.BotTrack track, boolean completedNormally, String errorMessage);
    }

    public interface OnMetadataListener {
        void onStreamTitleChanged(String liveTitle);
    }

    public interface OnListenersListener {
        void onListenersChanged(int count);
    }

    public interface OnStateNoteListener {
        void onStreamerLog(String message);
        void onVideoStateChanged(boolean streamingVideo);
    }

    private final Context appContext;
    private final LocalMediaRelayServer relayServer = new LocalMediaRelayServer();
    private volatile TeamTalkBase botClient;
    private volatile boolean running = false;
    private volatile boolean paused = false;
    private volatile int volumePercent = 50;
    private volatile String yandexToken = "";
    private volatile int tvVideoBitrate = BotConfig.DEFAULT_TV_VIDEO_BITRATE;
    private volatile boolean tvAudioOnly = false;

    // Native MediaFile state
    private volatile boolean nativeMediaActive = false;
    private volatile boolean streamingVideo = false;
    private volatile int activeVideoBitrate = BotConfig.DEFAULT_TV_VIDEO_BITRATE;
    private volatile int nativeElapsedMs = 0;
    private volatile int nativeDurationMs = 0;
    private volatile boolean nativePlayingConfirmed = false;
    private volatile boolean nativeErrorBeforePlay = false;
    private volatile int nativeFinalStatus = MediaFileStatus.MFS_CLOSED;
    private final Object nativeEventLock = new Object();
    private volatile CountDownLatch nativeFinishLatch = null;

    // PCM fallback state
    private volatile long currentPositionUs = 0L;
    private volatile long totalDurationUs = 0L;
    private volatile boolean pendingSeek = false;
    private volatile long pendingSeekDeltaUs = 0L;
    private volatile BotMusicSearch.BotTrack activeTrack = null;
    private volatile OnTrackFinishedListener finishedListener;
    private volatile OnMetadataListener metadataListener;
    private volatile OnListenersListener listenersListener;
    private volatile OnStateNoteListener stateNoteListener;

    private final AtomicInteger playGeneration = new AtomicInteger(0);
    private final Object threadLock = new Object();
    private Thread playThread;
    private Thread icyThread;
    private DjBotCatalogs.VolnorezMetadataReader volnReader;
    private int sampleIndex = 0;

    public BotAudioStreamer(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void setBotClient(TeamTalkBase client) {
        this.botClient = client;
    }

    public void configure(String yandexToken, int tvVideoBitrate, boolean tvAudioOnly) {
        this.yandexToken = yandexToken != null ? yandexToken.trim() : "";
        this.tvVideoBitrate = Math.max(BotConfig.MIN_TV_VIDEO_BITRATE,
                Math.min(BotConfig.MAX_TV_VIDEO_BITRATE, tvVideoBitrate > 0 ? tvVideoBitrate : BotConfig.DEFAULT_TV_VIDEO_BITRATE));
        this.tvAudioOnly = tvAudioOnly;
    }

    public void setOnTrackFinishedListener(OnTrackFinishedListener listener) {
        this.finishedListener = listener;
    }

    public void setOnMetadataListener(OnMetadataListener listener) {
        this.metadataListener = listener;
    }

    public void setOnListenersListener(OnListenersListener listener) {
        this.listenersListener = listener;
    }

    public void setOnStateNoteListener(OnStateNoteListener listener) {
        this.stateNoteListener = listener;
    }

    public boolean isStreamingVideo() {
        return this.running && this.streamingVideo;
    }

    public int getRelayPort() {
        return this.relayServer.getPort();
    }

    public void setVolume(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        this.volumePercent = clamped;
        if (this.nativeMediaActive) {
            pushNativePlaybackUpdate(MediaFilePlaybackConstants.TT_MEDIAPLAYBACK_OFFSET_IGNORE, this.paused);
        }
    }

    public int getVolume() {
        return this.volumePercent;
    }

    public boolean isPlaying() {
        return this.running && !this.paused;
    }

    public boolean isRunning() {
        return this.running;
    }

    public boolean isPaused() {
        return this.paused;
    }

    public BotMusicSearch.BotTrack getActiveTrack() {
        return this.activeTrack;
    }

    public long getCurrentPositionMs() {
        if (this.nativeMediaActive) {
            return this.nativeElapsedMs;
        }
        return this.currentPositionUs / 1000L;
    }

    public long getTotalDurationMs() {
        if (this.nativeMediaActive) {
            return this.nativeDurationMs;
        }
        return this.totalDurationUs / 1000L;
    }

    public void setPaused(boolean pause) {
        if (this.paused != pause) {
            this.paused = pause;
            if (this.nativeMediaActive) {
                pushNativePlaybackUpdate(MediaFilePlaybackConstants.TT_MEDIAPLAYBACK_OFFSET_IGNORE, pause);
            } else if (pause) {
                flushEmptyAudioBlock();
            }
        }
    }

    public void seekBySeconds(int deltaSeconds) {
        if (!this.running) return;
        BotMusicSearch.BotTrack track = this.activeTrack;
        if (track == null || track.isLiveStream) return;
        if (this.nativeMediaActive) {
            int cur = Math.max(0, this.nativeElapsedMs);
            int target = Math.max(0, cur + deltaSeconds * 1000);
            if (this.nativeDurationMs > 0 && target > this.nativeDurationMs - 1000) {
                target = Math.max(0, this.nativeDurationMs - 1000);
            }
            this.nativeElapsedMs = target;
            pushNativePlaybackUpdate(target, this.paused);
        } else {
            this.pendingSeekDeltaUs = deltaSeconds * 1_000_000L;
            this.pendingSeek = true;
        }
    }

    /**
     * Called from {@link EmbeddedMediaBot} when {@code CLIENTEVENT_STREAM_MEDIAFILE} arrives.
     */
    public void onStreamMediaFileEvent(MediaFileInfo info) {
        if (info == null) return;
        synchronized (nativeEventLock) {
            if (info.uDurationMSec > 0) {
                this.nativeDurationMs = info.uDurationMSec;
            }
            if (info.uElapsedMSec >= 0) {
                this.nativeElapsedMs = info.uElapsedMSec;
            }
            if (info.videoFmt != null && info.videoFmt.picFourCC != 0 && this.streamingVideo) {
                notifyVideoState(true);
            }
            switch (info.nStatus) {
                case MediaFileStatus.MFS_STARTED:
                case MediaFileStatus.MFS_PLAYING:
                    this.nativePlayingConfirmed = true;
                    break;
                case MediaFileStatus.MFS_PAUSED:
                    this.nativePlayingConfirmed = true;
                    break;
                case MediaFileStatus.MFS_ERROR:
                    if (!this.nativePlayingConfirmed) {
                        this.nativeErrorBeforePlay = true;
                    }
                    this.nativeFinalStatus = info.nStatus;
                    CountDownLatch errLatch = this.nativeFinishLatch;
                    if (errLatch != null) errLatch.countDown();
                    break;
                case MediaFileStatus.MFS_FINISHED:
                case MediaFileStatus.MFS_ABORTED:
                case MediaFileStatus.MFS_CLOSED:
                    this.nativeFinalStatus = info.nStatus;
                    CountDownLatch doneLatch = this.nativeFinishLatch;
                    if (doneLatch != null) doneLatch.countDown();
                    break;
                default:
                    break;
            }
        }
    }

    public void play(BotMusicSearch.BotTrack track) {
        play(track, null);
    }

    public void play(BotMusicSearch.BotTrack track, File introWavFile) {
        stop();
        if (track == null) return;

        final int gen = playGeneration.incrementAndGet();
        synchronized (threadLock) {
            this.activeTrack = track;
            this.running = true;
            this.paused = false;
            this.pendingSeek = false;
            this.pendingSeekDeltaUs = 0L;
            this.currentPositionUs = 0L;
            this.totalDurationUs = 0L;
            this.nativeMediaActive = false;
            this.streamingVideo = false;
            this.nativeElapsedMs = 0;
            this.nativeDurationMs = 0;
            this.playThread = new Thread(() -> runPlayback(track, introWavFile, gen), "Extro-BotAudio");
            this.playThread.start();
        }
    }

    public void stop() {
        playGeneration.incrementAndGet();
        this.running = false;
        this.paused = false;
        this.pendingSeek = false;
        this.nativeMediaActive = false;
        if (this.streamingVideo) {
            this.streamingVideo = false;
            notifyVideoState(false);
        }
        synchronized (nativeEventLock) {
            CountDownLatch l = this.nativeFinishLatch;
            if (l != null) l.countDown();
        }
        stopNativeMediaFile();

        Thread pt;
        Thread it;
        DjBotCatalogs.VolnorezMetadataReader vr;
        synchronized (threadLock) {
            pt = this.playThread;
            it = this.icyThread;
            vr = this.volnReader;
            this.playThread = null;
            this.icyThread = null;
            this.volnReader = null;
        }
        if (vr != null) {
            try {
                vr.stop();
            } catch (Throwable ignored) {
            }
        }
        if (it != null) {
            it.interrupt();
        }
        if (pt != null) {
            pt.interrupt();
            try {
                pt.join(600);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        flushEmptyAudioBlock();
        this.activeTrack = null;
        this.currentPositionUs = 0L;
        this.totalDurationUs = 0L;
        this.nativeElapsedMs = 0;
        this.nativeDurationMs = 0;
    }

    public void shutdown() {
        stop();
        this.relayServer.stop();
    }

    // --- Native TeamTalk MediaFile helpers ---

    public static MediaFilePlayback buildPlayback(int offsetMs, boolean paused, int volumePercent) {
        MediaFilePlayback pb = new MediaFilePlayback();
        pb.uOffsetMSec = offsetMs;
        pb.bPaused = paused;
        AudioPreprocessor ap = new AudioPreprocessor(AudioPreprocessorType.TEAMTALK_AUDIOPREPROCESSOR, false);
        ap.nPreprocessor = AudioPreprocessorType.TEAMTALK_AUDIOPREPROCESSOR;
        if (ap.ttpreprocessor == null) {
            ap.ttpreprocessor = new TTAudioPreprocessor(false);
        }
        int clampedVol = Math.max(0, Math.min(100, volumePercent));
        ap.ttpreprocessor.nGainLevel = Math.max(0, Math.min(32000, clampedVol * 10));
        pb.audioPreprocessor = ap;
        return pb;
    }

    public static VideoCodec buildVideoCodec(boolean enableVideo, int bitrateKbps) {
        VideoCodec vc = new VideoCodec();
        if (enableVideo) {
            vc.nCodec = Codec.WEBM_VP8_CODEC;
            vc.webm_vp8 = new WebMVP8Codec();
            vc.webm_vp8.nRcTargetBitrate = Math.max(BotConfig.MIN_TV_VIDEO_BITRATE,
                    Math.min(BotConfig.MAX_TV_VIDEO_BITRATE, bitrateKbps));
        } else {
            vc.nCodec = Codec.NO_CODEC;
        }
        return vc;
    }

    private void pushNativePlaybackUpdate(int offsetMs, boolean pausedNow) {
        TeamTalkBase client = this.botClient;
        if (client == null) return;
        try {
            MediaFilePlayback pb = buildPlayback(offsetMs, pausedNow, this.volumePercent);
            VideoCodec vc = buildVideoCodec(this.streamingVideo, this.activeVideoBitrate);
            client.updateStreamingMediaFileToChannel(pb, vc);
        } catch (Throwable ignored) {
        }
    }

    private void stopNativeMediaFile() {
        TeamTalkBase client = this.botClient;
        if (client != null) {
            try {
                client.stopStreamingMediaFileToChannel();
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean detectLocalFileHasVideo(String filePath) {
        if (TextUtils.isEmpty(filePath)) return false;
        try {
            MediaFileInfo info = new MediaFileInfo();
            if (TeamTalkBase.getMediaFileInfo(filePath, info)) {
                return info.videoFmt != null && info.videoFmt.picFourCC != 0;
            }
        } catch (Throwable ignored) {
        }
        String low = filePath.toLowerCase(Locale.ROOT);
        return low.endsWith(".mp4") || low.endsWith(".mkv") || low.endsWith(".webm")
                || low.endsWith(".mov") || low.endsWith(".avi") || low.endsWith(".ts");
    }

    private enum NativeResult {
        COMPLETED,
        FAILED_BEFORE_PLAY,
        STOPPED
    }

    /**
     * Streams a local file or loopback relay URL via TeamTalk5's native FFmpeg.
     * Waits for MFS_FINISHED/MFS_CLOSED (or enforces 14s startup watchdog for live streams).
     */
    private NativeResult streamWithNativeMediaFile(String pathOrUrl, boolean enableVideo,
                                                   boolean isLive, int gen) {
        TeamTalkBase client = this.botClient;
        if (client == null || !isStillActive(gen)) {
            return NativeResult.STOPPED;
        }
        CountDownLatch latch = new CountDownLatch(1);
        synchronized (nativeEventLock) {
            this.nativeFinishLatch = latch;
            this.nativePlayingConfirmed = false;
            this.nativeErrorBeforePlay = false;
            this.nativeFinalStatus = MediaFileStatus.MFS_CLOSED;
            this.nativeElapsedMs = 0;
            this.nativeDurationMs = 0;
        }
        this.activeVideoBitrate = this.tvVideoBitrate;
        this.streamingVideo = enableVideo;
        this.nativeMediaActive = true;
        notifyVideoState(enableVideo);

        MediaFilePlayback pb = buildPlayback(0, this.paused, this.volumePercent);
        VideoCodec vc = buildVideoCodec(enableVideo, this.activeVideoBitrate);

        boolean started;
        try {
            started = client.startStreamingMediaFileToChannel(pathOrUrl, pb, vc);
        } catch (Throwable t) {
            Log.w(TAG, "startStreamingMediaFileToChannel threw: " + t.getMessage());
            started = false;
        }

        if (!started) {
            this.nativeMediaActive = false;
            this.streamingVideo = false;
            notifyVideoState(false);
            return NativeResult.FAILED_BEFORE_PLAY;
        }

        long startMs = SystemClock.uptimeMillis();
        try {
            while (isStillActive(gen)) {
                if (latch.await(250, TimeUnit.MILLISECONDS)) {
                    break;
                }
                if (!this.nativePlayingConfirmed) {
                    long waited = SystemClock.uptimeMillis() - startMs;
                    long limit = isLive ? LIVE_STARTUP_WATCHDOG_MS : 12_000L;
                    if (waited >= limit) {
                        logNote("⚠️ Таймаут запуска потока (" + (limit / 1000) + " с), пробую резервный режим…");
                        stopNativeMediaFile();
                        this.nativeMediaActive = false;
                        this.streamingVideo = false;
                        notifyVideoState(false);
                        return NativeResult.FAILED_BEFORE_PLAY;
                    }
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            stopNativeMediaFile();
            this.nativeMediaActive = false;
            if (this.streamingVideo) {
                this.streamingVideo = false;
                notifyVideoState(false);
            }
        }

        if (!isStillActive(gen)) {
            return NativeResult.STOPPED;
        }
        if (this.nativeErrorBeforePlay || (!this.nativePlayingConfirmed && this.nativeFinalStatus == MediaFileStatus.MFS_ERROR)) {
            return NativeResult.FAILED_BEFORE_PLAY;
        }
        return NativeResult.COMPLETED;
    }

    // --- Main playback worker ---

    private void runPlayback(BotMusicSearch.BotTrack initialTrack, File introWavFile, int gen) {
        boolean completedNormally = false;
        String errorMessage = null;
        BotMusicSearch.BotTrack track = initialTrack;
        File tempFile = null;
        File copiedLocalFile = null;

        try {
            // Optional TTS intro WAV (/vo)
            if (introWavFile != null && introWavFile.exists() && introWavFile.length() > 44 && isStillActive(gen)) {
                NativeResult introRes = streamWithNativeMediaFile(introWavFile.getAbsolutePath(), false, false, gen);
                if (introRes == NativeResult.FAILED_BEFORE_PLAY && isStillActive(gen)) {
                    try {
                        decodeAndStreamFilePcm(introWavFile, gen);
                    } catch (Throwable t) {
                        Log.w(TAG, "Intro TTS WAV fallback skipped: " + t.getMessage());
                    }
                }
            }
            if (!isStillActive(gen)) return;

            track = BotMusicSearch.resolveTrackForPlayback(initialTrack, this.yandexToken);
            if (!isStillActive(gen)) return;
            this.activeTrack = track;

            if (!TextUtils.isEmpty(track.localUri)) {
                String localPath = prepareLocalMediaPath(track.localUri, track.title);
                if (localPath != null) {
                    if (!localPath.equals(track.localUri) && localPath.startsWith(appContext.getCacheDir().getAbsolutePath())) {
                        copiedLocalFile = new File(localPath);
                    }
                    boolean hasVideo = detectLocalFileHasVideo(localPath);
                    NativeResult res = streamWithNativeMediaFile(localPath, hasVideo, false, gen);
                    if (res == NativeResult.FAILED_BEFORE_PLAY && hasVideo && isStillActive(gen)) {
                        logNote("Видео-режим недоступен для файла — переключаюсь на аудио…");
                        res = streamWithNativeMediaFile(localPath, false, false, gen);
                    }
                    if (res == NativeResult.FAILED_BEFORE_PLAY && isStillActive(gen)) {
                        logNote("Переход на голосовой декодер (нет прав MediaFile)…");
                        decodeAndStreamFilePcm(new File(localPath), gen);
                    }
                    completedNormally = isStillActive(gen);
                } else {
                    decodeAndStreamLocalPcm(Uri.parse(track.localUri), gen);
                    completedNormally = isStillActive(gen);
                }
            } else if (!track.isLiveStream) {
                String ext = guessAudioExtension(track.url, ".mp3");
                tempFile = new File(appContext.getCacheDir(), "extro_bot_track_" + gen + ext);
                downloadTrackToFile(track.url, track.referer, tempFile, gen);
                if (!isStillActive(gen)) return;

                boolean hasVideo = detectLocalFileHasVideo(tempFile.getAbsolutePath());
                NativeResult res = streamWithNativeMediaFile(tempFile.getAbsolutePath(), hasVideo, false, gen);
                if (res == NativeResult.FAILED_BEFORE_PLAY && hasVideo && isStillActive(gen)) {
                    res = streamWithNativeMediaFile(tempFile.getAbsolutePath(), false, false, gen);
                }
                if (res == NativeResult.FAILED_BEFORE_PLAY && isStillActive(gen)) {
                    logNote("Переход на голосовой декодер (insertAudioBlock)…");
                    decodeAndStreamFilePcm(tempFile, gen);
                }
                completedNormally = isStillActive(gen);
            } else {
                // Live stream: Radio, Volnorez, TV (.m3u8), Rutube (.m3u8), or direct live URL
                String streamUrl = track.url;
                boolean isTv = DjBotCatalogs.SERVICE_TV.equals(track.service);
                boolean isRutube = BotMusicSearch.SERVICE_RUTUBE.equals(track.service);
                boolean isVolnorez = DjBotCatalogs.VolnorezMetadataReader.supports(streamUrl)
                        || DjBotCatalogs.SERVICE_VOLN.equals(track.service);
                boolean isHls = isTv || isRutube || LocalMediaRelayServer.looksLikeHlsUrl(streamUrl);
                boolean wantVideo = (isTv && !this.tvAudioOnly);

                // Start live metadata listeners in parallel (Volnorez WebSocket / ICY)
                if (isVolnorez) {
                    startVolnorezMetadataReader(streamUrl, gen);
                } else if (!isHls) {
                    startIcyMetadataPoller(streamUrl, track.referer, gen);
                }

                // Register on loopback LocalMediaRelayServer so native FFmpeg receives clean HTTP/1.1 on 127.0.0.1
                String relayUrl = isHls
                        ? relayServer.registerHls(streamUrl, track.referer, wantVideo)
                        : relayServer.registerLive(streamUrl, track.referer);

                NativeResult res = streamWithNativeMediaFile(relayUrl, wantVideo, true, gen);

                // If TV with video failed before playing, retry TV audio-only first
                if (res == NativeResult.FAILED_BEFORE_PLAY && wantVideo && isStillActive(gen)) {
                    logNote("📺 ТВ-видео недоступно на этом сервере/канале — запускаю ТВ в аудио-режиме…");
                    String audioRelayUrl = relayServer.registerHls(streamUrl, track.referer, false);
                    res = streamWithNativeMediaFile(audioRelayUrl, false, true, gen);
                }

                // If MediaFile is rejected (e.g. account lacks TRANSMIT_MEDIAFILE), fall back to voice PCM
                if (res == NativeResult.FAILED_BEFORE_PLAY && isStillActive(gen)) {
                    if (!isHls) {
                        logNote("📻 Нет прав MediaFile — запускаю прямой голосовой декодер радио (MP3/AAC)…");
                        decodeAndStreamLiveRadioPcm(streamUrl, track.referer, gen);
                        completedNormally = isStillActive(gen);
                    } else {
                        throw new IOException("Не удалось запустить HLS/ТВ-поток (проверьте права TRANSMIT_MEDIAFILE и доступность канала)");
                    }
                } else {
                    completedNormally = isStillActive(gen);
                }
            }
        } catch (Throwable t) {
            if (isStillActive(gen)) {
                errorMessage = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                Log.w(TAG, "Bot playback error: " + errorMessage, t);
            }
        } finally {
            stopIcyPoller();
            stopVolnorezReader();
            stopNativeMediaFile();
            flushEmptyAudioBlock();
            deleteQuiet(introWavFile);
            deleteQuiet(tempFile);
            deleteQuiet(copiedLocalFile);
            if (isStillActive(gen)) {
                this.running = false;
                this.activeTrack = null;
                if (this.streamingVideo) {
                    this.streamingVideo = false;
                    notifyVideoState(false);
                }
                OnTrackFinishedListener listener = this.finishedListener;
                if (listener != null) {
                    try {
                        listener.onTrackFinished(track, completedNormally, errorMessage);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    /**
     * Resolves a local file path or copies a content:// URI into cache while preserving extension.
     */
    private String prepareLocalMediaPath(String localUriOrPath, String displayName) {
        if (TextUtils.isEmpty(localUriOrPath)) return null;
        if (!localUriOrPath.startsWith("content://") && !localUriOrPath.startsWith("file://")) {
            File direct = new File(localUriOrPath);
            if (direct.exists() && direct.canRead()) {
                return direct.getAbsolutePath();
            }
        }
        Uri uri = Uri.parse(localUriOrPath);
        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            File f = new File(uri.getPath());
            if (f.exists() && f.canRead()) {
                return f.getAbsolutePath();
            }
        }
        String name = displayName;
        if (TextUtils.isEmpty(name) || !name.contains(".")) {
            String queried = queryDisplayName(uri);
            if (!TextUtils.isEmpty(queried)) {
                name = queried;
            }
        }
        String ext = guessAudioExtension(name, ".mp3");
        File dest = new File(appContext.getCacheDir(), "extro_bot_local_" + System.currentTimeMillis() + ext);
        try (InputStream is = appContext.getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(dest)) {
            if (is == null) return null;
            byte[] buf = new byte[16384];
            int r;
            while ((r = is.read(buf)) > 0) {
                fos.write(buf, 0, r);
            }
            fos.flush();
            return dest.getAbsolutePath();
        } catch (Throwable t) {
            deleteQuiet(dest);
            return null;
        }
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = appContext.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Throwable ignored) {
        }
        return uri.getLastPathSegment();
    }

    private static String guessAudioExtension(String nameOrUrl, String fallback) {
        if (TextUtils.isEmpty(nameOrUrl)) return fallback;
        String clean = nameOrUrl;
        int q = clean.indexOf('?');
        if (q >= 0) clean = clean.substring(0, q);
        String low = clean.toLowerCase(Locale.ROOT);
        String[] known = { ".mp3", ".m4a", ".mp4", ".ogg", ".opus", ".wav", ".flac", ".aac", ".webm", ".mkv", ".mov" };
        for (String ext : known) {
            if (low.endsWith(ext)) return ext;
        }
        return fallback;
    }

    private void startVolnorezMetadataReader(String streamUrl, int gen) {
        stopVolnorezReader();
        DjBotCatalogs.VolnorezMetadataReader reader = new DjBotCatalogs.VolnorezMetadataReader(
                streamUrl,
                new DjBotCatalogs.VolnorezMetadataReader.Callback() {
                    @Override
                    public void onMetadata(String djAndTrack) {
                        if (!isStillActive(gen)) return;
                        OnMetadataListener l = metadataListener;
                        if (l != null && !TextUtils.isEmpty(djAndTrack)) {
                            l.onStreamTitleChanged(djAndTrack);
                        }
                    }

                    @Override
                    public void onListeners(int count) {
                        if (!isStillActive(gen)) return;
                        OnListenersListener l = listenersListener;
                        if (l != null) {
                            l.onListenersChanged(count);
                        }
                    }
                });
        synchronized (threadLock) {
            this.volnReader = reader;
        }
        reader.start();
    }

    private void stopVolnorezReader() {
        DjBotCatalogs.VolnorezMetadataReader vr;
        synchronized (threadLock) {
            vr = this.volnReader;
            this.volnReader = null;
        }
        if (vr != null) {
            try {
                vr.stop();
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean isStillActive(int gen) {
        return this.running && this.playGeneration.get() == gen;
    }

    private float getPerceptualGain() {
        int vol = this.volumePercent;
        if (vol <= 0) return 0f;
        return (float) Math.pow(vol / 100.0, 1.5);
    }

    private void downloadTrackToFile(String urlStr, String referer, File dest, int gen) throws IOException {
        String current = urlStr;
        for (int hops = 0; hops < 6; hops++) {
            if (!isStillActive(gen)) return;
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", BotMusicSearch.USER_AGENT);
            conn.setRequestProperty("Accept", "*/*");
            if (!TextUtils.isEmpty(referer)) {
                conn.setRequestProperty("Referer", referer);
            }
            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (TextUtils.isEmpty(loc)) {
                    throw new IOException("Redirect without Location");
                }
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            if (code < 200 || code >= 300) {
                conn.disconnect();
                throw new IOException("HTTP " + code);
            }
            String contentType = conn.getContentType();
            if (contentType != null && (contentType.toLowerCase(Locale.ROOT).contains("text/html")
                    || contentType.toLowerCase(Locale.ROOT).contains("application/xhtml"))) {
                conn.disconnect();
                throw new IOException("Server returned HTML instead of audio");
            }

            long total = 0;
            boolean checkedHeader = false;
            try (InputStream is = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(dest)) {
                byte[] buf = new byte[16384];
                int read;
                while (isStillActive(gen) && (read = is.read(buf)) > 0) {
                    if (!checkedHeader) {
                        checkedHeader = true;
                        String head = new String(buf, 0, Math.min(read, 128), StandardCharsets.US_ASCII)
                                .trim().toLowerCase(Locale.ROOT);
                        if (head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("<?xml")) {
                            throw new IOException("Downloaded HTML/XML page instead of audio");
                        }
                    }
                    fos.write(buf, 0, read);
                    total += read;
                }
                fos.flush();
            } finally {
                conn.disconnect();
            }

            if (!isStillActive(gen)) return;
            if (total < MIN_TRACK_BYTES) {
                throw new IOException("Audio file too small (" + total + " bytes)");
            }
            return;
        }
        throw new IOException("Too many redirects");
    }

    // --- FALLBACK 1: Local/Downloaded File PCM Decode via MediaExtractor + MediaCodec ---

    private void decodeAndStreamLocalPcm(Uri uri, int gen) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(appContext, uri, null);
            runExtractorDecodeLoop(extractor, gen);
        } finally {
            try {
                extractor.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private void decodeAndStreamFilePcm(File file, int gen) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            runExtractorDecodeLoop(extractor, gen);
        } finally {
            try {
                extractor.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private void runExtractorDecodeLoop(MediaExtractor extractor, int gen) throws IOException {
        int trackIndex = -1;
        MediaFormat format = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                trackIndex = i;
                format = f;
                break;
            }
        }
        if (trackIndex < 0 || format == null) {
            throw new IOException("No audio track found");
        }
        extractor.selectTrack(trackIndex);
        if (format.containsKey(MediaFormat.KEY_DURATION)) {
            this.totalDurationUs = format.getLong(MediaFormat.KEY_DURATION);
        }

        String mime = format.getString(MediaFormat.KEY_MIME);
        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        try {
            codec.configure(format, null, null, 0);
            codec.start();

            int srcRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : TARGET_SAMPLE_RATE;
            int srcChannels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            PcmOutputPipeline pipeline = new PcmOutputPipeline(srcRate, srcChannels);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;

            while (isStillActive(gen)) {
                if (this.paused) {
                    sleepQuiet(40L);
                    pipeline.resetClock();
                    continue;
                }

                if (this.pendingSeek) {
                    this.pendingSeek = false;
                    long targetUs = Math.max(0L, this.currentPositionUs + this.pendingSeekDeltaUs);
                    if (this.totalDurationUs > 0 && targetUs > this.totalDurationUs - 1_000_000L) {
                        targetUs = Math.max(0L, this.totalDurationUs - 1_000_000L);
                    }
                    try {
                        extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        codec.flush();
                        pipeline.reset();
                        inputDone = false;
                        this.currentPositionUs = extractor.getSampleTime();
                    } catch (Throwable t) {
                        Log.w(TAG, "Seek failed", t);
                    }
                }

                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(DECODE_TIMEOUT_US);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                        int size = inBuf == null ? -1 : extractor.readSampleData(inBuf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long pts = extractor.getSampleTime();
                            if (pts >= 0) {
                                this.currentPositionUs = pts;
                            }
                            codec.queueInputBuffer(inIndex, 0, size, pts, 0);
                            extractor.advance();
                        }
                    }
                }

                if (drainCodecOutput(codec, info, pipeline, gen)) {
                    break;
                }
            }
        } finally {
            releaseCodecQuiet(codec);
        }
    }

    // --- FALLBACK 2: Pure-Java Live HTTP MP3 / ADTS-AAC Frame Feeder -> MediaCodec (NO MediaExtractor!) ---

    private void decodeAndStreamLiveRadioPcm(String urlStr, String referer, int gen) throws IOException {
        String current = urlStr;
        HttpURLConnection conn = null;
        for (int hops = 0; hops < 8; hops++) {
            conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", BotMusicSearch.USER_AGENT);
            conn.setRequestProperty("Accept", "*/*");
            if (!TextUtils.isEmpty(referer)) {
                conn.setRequestProperty("Referer", referer);
            }
            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (TextUtils.isEmpty(loc)) throw new IOException("Redirect without Location");
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            if (code < 200 || code >= 300) {
                conn.disconnect();
                throw new IOException("Radio HTTP " + code);
            }
            break;
        }
        if (conn == null) throw new IOException("Failed to open live radio stream");

        String ct = conn.getContentType();
        String ctLow = ct != null ? ct.toLowerCase(Locale.ROOT) : "";
        boolean preferAac = ctLow.contains("aac") || current.toLowerCase(Locale.ROOT).contains(".aac");

        MediaCodec codec = null;
        try (InputStream rawIn = new BufferedInputStream(conn.getInputStream(), 32768)) {
            byte[] ring = new byte[65536];
            int len = 0;
            PcmOutputPipeline pipeline = null;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long ptsUs = 0L;
            boolean isAac = preferAac;

            while (isStillActive(gen)) {
                if (this.paused) {
                    sleepQuiet(40L);
                    if (pipeline != null) pipeline.resetClock();
                    continue;
                }
                if (len < 8192) {
                    int n = rawIn.read(ring, len, ring.length - len);
                    if (n < 0) break;
                    len += n;
                }

                // Skip ID3v2 header if present
                if (len >= 10 && ring[0] == 'I' && ring[1] == 'D' && ring[2] == '3') {
                    int tagSize = ((ring[6] & 0x7F) << 21) | ((ring[7] & 0x7F) << 14)
                            | ((ring[8] & 0x7F) << 7) | (ring[9] & 0x7F);
                    int totalTag = 10 + tagSize;
                    if (totalTag <= len) {
                        System.arraycopy(ring, totalTag, ring, 0, len - totalTag);
                        len -= totalTag;
                        continue;
                    } else {
                        int toSkip = totalTag - len;
                        len = 0;
                        while (toSkip > 0 && isStillActive(gen)) {
                            long skipped = rawIn.skip(toSkip);
                            if (skipped <= 0) {
                                if (rawIn.read() < 0) break;
                                toSkip--;
                            } else {
                                toSkip -= (int) skipped;
                            }
                        }
                        continue;
                    }
                }

                int[] frameInfo = findNextFrame(ring, len, isAac);
                if (frameInfo == null && codec == null) {
                    // Try opposite format (MP3 <-> ADTS-AAC) before giving up on sync
                    frameInfo = findNextFrame(ring, len, !isAac);
                    if (frameInfo != null) {
                        isAac = !isAac;
                    }
                }
                if (frameInfo == null) {
                    // Discard oldest half to resync
                    if (len > 4096) {
                        int drop = len - 2048;
                        System.arraycopy(ring, drop, ring, 0, len - drop);
                        len -= drop;
                    } else {
                        int n = rawIn.read(ring, len, ring.length - len);
                        if (n < 0) break;
                        len += n;
                    }
                    continue;
                }

                int offset = frameInfo[0];
                int frameLen = frameInfo[1];
                int sampleRate = frameInfo[2];
                int channels = frameInfo[3];
                int payloadOffset = frameInfo[4];
                int payloadLen = frameInfo[5];
                int aacProfile = frameInfo[6];
                int aacFreqIdx = frameInfo[7];

                if (offset + frameLen > len) {
                    if (offset > 0) {
                        System.arraycopy(ring, offset, ring, 0, len - offset);
                        len -= offset;
                    }
                    int n = rawIn.read(ring, len, ring.length - len);
                    if (n < 0) break;
                    len += n;
                    continue;
                }

                if (codec == null) {
                    if (isAac) {
                        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
                        fmt.setInteger(MediaFormat.KEY_IS_ADTS, 0);
                        int objType = aacProfile + 1;
                        byte[] csd = new byte[] {
                                (byte) (((objType << 3) & 0xF8) | ((aacFreqIdx >> 1) & 0x07)),
                                (byte) (((aacFreqIdx << 7) & 0x80) | ((channels << 3) & 0x78))
                        };
                        fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
                        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                        codec.configure(fmt, null, null, 0);
                    } else {
                        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_MPEG, sampleRate, channels);
                        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_MPEG);
                        codec.configure(fmt, null, null, 0);
                    }
                    codec.start();
                    pipeline = new PcmOutputPipeline(sampleRate, channels);
                }

                int inIndex = codec.dequeueInputBuffer(DECODE_TIMEOUT_US);
                if (inIndex >= 0) {
                    ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                    if (inBuf != null) {
                        inBuf.clear();
                        inBuf.put(ring, offset + payloadOffset, payloadLen);
                        codec.queueInputBuffer(inIndex, 0, payloadLen, ptsUs, 0);
                        ptsUs += (long) ((isAac ? 1024.0 : 1152.0) * 1_000_000.0 / Math.max(8000, sampleRate));
                    }
                    int consume = offset + frameLen;
                    System.arraycopy(ring, consume, ring, 0, len - consume);
                    len -= consume;
                }

                drainCodecOutput(codec, info, pipeline, gen);
            }
        } finally {
            releaseCodecQuiet(codec);
            try {
                conn.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Finds next ADTS-AAC or MPEG-1/2 Layer III frame in buffer.
     * Returns {offset, frameLen, sampleRate, channels, payloadOffset, payloadLen, aacProfile, aacFreqIdx}.
     */
    private static int[] findNextFrame(byte[] buf, int len, boolean aac) {
        if (aac) {
            for (int i = 0; i <= len - 7; i++) {
                if ((buf[i] & 0xFF) == 0xFF && (buf[i + 1] & 0xF6) == 0xF0) {
                    int profile = (buf[i + 2] & 0xC0) >>> 6;
                    int freqIdx = (buf[i + 2] & 0x3C) >>> 2;
                    int chanCfg = ((buf[i + 2] & 0x01) << 2) | ((buf[i + 3] & 0xC0) >>> 6);
                    int frameLen = ((buf[i + 3] & 0x03) << 11)
                            | ((buf[i + 4] & 0xFF) << 3)
                            | ((buf[i + 5] & 0xE0) >>> 5);
                    boolean noCrc = (buf[i + 1] & 0x01) != 0;
                    int hdrLen = noCrc ? 7 : 9;
                    if (freqIdx < ADTS_SAMPLERATES.length && ADTS_SAMPLERATES[freqIdx] > 0
                            && frameLen > hdrLen && frameLen < 8192) {
                        int ch = chanCfg == 0 ? 2 : Math.min(2, chanCfg);
                        return new int[] {
                                i, frameLen, ADTS_SAMPLERATES[freqIdx], ch,
                                hdrLen, frameLen - hdrLen, profile, freqIdx
                        };
                    }
                }
            }
            return null;
        }

        // MPEG Layer III (MP3) frame sync
        for (int i = 0; i <= len - 4; i++) {
            int b0 = buf[i] & 0xFF;
            int b1 = buf[i + 1] & 0xFF;
            if (b0 != 0xFF || (b1 & 0xE0) != 0xE0) continue;
            int versionBits = (b1 >> 3) & 0x03;
            int layerBits = (b1 >> 1) & 0x03;
            if (versionBits == 1 || layerBits != 1) continue; // Layer III only
            int b2 = buf[i + 2] & 0xFF;
            int b3 = buf[i + 3] & 0xFF;
            int bitrateIdx = (b2 >> 4) & 0x0F;
            int srIdx = (b2 >> 2) & 0x03;
            int padding = (b2 >> 1) & 0x01;
            int chMode = (b3 >> 6) & 0x03;
            if (bitrateIdx == 0 || bitrateIdx == 15 || srIdx == 3) continue;

            int bitrate = (versionBits == 3) ? MP3_BITRATES_V1_L3[bitrateIdx] : MP3_BITRATES_V2_L3[bitrateIdx];
            int sr;
            if (versionBits == 3) sr = MP3_SAMPLERATES_V1[srIdx];
            else if (versionBits == 2) sr = MP3_SAMPLERATES_V2[srIdx];
            else sr = MP3_SAMPLERATES_V25[srIdx];
            if (bitrate <= 0 || sr <= 0) continue;

            int frameLen = ((versionBits == 3 ? 144000 : 72000) * bitrate) / sr + padding;
            if (frameLen < 24 || frameLen > 4096) continue;
            int channels = (chMode == 3) ? 1 : 2;
            return new int[] { i, frameLen, sr, channels, 0, frameLen, 0, 0 };
        }
        return null;
    }

    private boolean drainCodecOutput(MediaCodec codec, MediaCodec.BufferInfo info,
                                     PcmOutputPipeline pipeline, int gen) {
        int outIndex = codec.dequeueOutputBuffer(info, DECODE_TIMEOUT_US);
        if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            MediaFormat newFmt = codec.getOutputFormat();
            int newRate = newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : pipeline.srcRate;
            int newCh = newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : pipeline.srcChannels;
            pipeline.reconfigure(newRate, newCh);
        } else if (outIndex >= 0) {
            if (info.size > 0) {
                ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                if (outBuf != null) {
                    outBuf.position(info.offset);
                    outBuf.limit(info.offset + info.size);
                    pipeline.processBuffer(outBuf, gen);
                }
            }
            codec.releaseOutputBuffer(outIndex, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                pipeline.flushPartial(gen);
                return true;
            }
        }
        return false;
    }

    private final class PcmOutputPipeline {
        int srcRate;
        int srcChannels;
        Resampler resampler;
        ChannelMixer mixer;
        short[] pcmScratch = new short[8192];
        float[] floatScratch = new float[8192];
        float[] chanScratch = new float[8192];
        float[] resampledOut = new float[4096];
        short[] frameAccum = new short[FRAME_SHORTS];
        int frameAccumPos = 0;
        byte[] frameBytes = new byte[FRAME_BYTES];
        AudioBlock block = new AudioBlock();
        long nextFrameDeadlineNs = SystemClock.elapsedRealtimeNanos();

        PcmOutputPipeline(int srcRate, int srcChannels) {
            this.srcRate = Math.max(8000, srcRate);
            this.srcChannels = Math.max(1, srcChannels);
            this.resampler = new Resampler(this.srcRate, TARGET_SAMPLE_RATE, TARGET_CHANNELS);
            this.mixer = new ChannelMixer(this.srcChannels, TARGET_CHANNELS);
            this.block.nStreamID = 1;
            this.block.uStreamTypes = StreamType.STREAMTYPE_VOICE;
            this.block.nSampleRate = TARGET_SAMPLE_RATE;
            this.block.nChannels = TARGET_CHANNELS;
            this.block.nSamples = FRAME_SAMPLES;
        }

        void reconfigure(int newRate, int newCh) {
            if (newRate != this.srcRate || newCh != this.srcChannels) {
                this.srcRate = Math.max(8000, newRate);
                this.srcChannels = Math.max(1, newCh);
                this.resampler = new Resampler(this.srcRate, TARGET_SAMPLE_RATE, TARGET_CHANNELS);
                this.mixer = new ChannelMixer(this.srcChannels, TARGET_CHANNELS);
            }
        }

        void reset() {
            this.resampler.reset();
            this.frameAccumPos = 0;
            this.nextFrameDeadlineNs = SystemClock.elapsedRealtimeNanos();
        }

        void resetClock() {
            this.nextFrameDeadlineNs = SystemClock.elapsedRealtimeNanos();
        }

        void processBuffer(ByteBuffer outBuf, int gen) {
            ShortBuffer shorts = outBuf.order(ByteOrder.nativeOrder()).asShortBuffer();
            int samples = shorts.remaining();
            int dstSamples = (samples / Math.max(1, srcChannels)) * TARGET_CHANNELS;
            if (pcmScratch.length < samples) pcmScratch = new short[samples];
            if (floatScratch.length < samples) floatScratch = new float[samples];
            if (chanScratch.length < dstSamples) chanScratch = new float[dstSamples];
            shorts.get(pcmScratch, 0, samples);
            for (int i = 0; i < samples; i++) {
                floatScratch[i] = pcmScratch[i];
            }
            int framed = mixer.convert(floatScratch, samples, chanScratch);
            resampler.push(chanScratch, framed);

            int producedFrames;
            while (isStillActive(gen) && (producedFrames = resampler.pull(resampledOut, resampledOut.length / TARGET_CHANNELS)) > 0) {
                int count = producedFrames * TARGET_CHANNELS;
                int idx = 0;
                while (idx < count && isStillActive(gen)) {
                    while (paused && isStillActive(gen)) {
                        sleepQuiet(40L);
                        nextFrameDeadlineNs = SystemClock.elapsedRealtimeNanos();
                    }
                    if (!isStillActive(gen)) break;

                    int take = Math.min(FRAME_SHORTS - frameAccumPos, count - idx);
                    for (int k = 0; k < take; k++) {
                        frameAccum[frameAccumPos + k] = clampShort(resampledOut[idx + k]);
                    }
                    frameAccumPos += take;
                    idx += take;

                    if (frameAccumPos >= FRAME_SHORTS) {
                        float gain = getPerceptualGain();
                        for (int s = 0; s < FRAME_SHORTS; s++) {
                            short v = gain <= 0.0001f ? 0 : clampShort(frameAccum[s] * gain);
                            frameBytes[s * 2] = (byte) (v & 0xFF);
                            frameBytes[s * 2 + 1] = (byte) ((v >> 8) & 0xFF);
                        }
                        nextFrameDeadlineNs = paceAndSendFrame(block, frameBytes, nextFrameDeadlineNs, gen);
                        frameAccumPos = 0;
                    }
                }
            }
        }

        void flushPartial(int gen) {
            if (frameAccumPos > 0 && isStillActive(gen)) {
                float gain = getPerceptualGain();
                for (int s = 0; s < FRAME_SHORTS; s++) {
                    short v = (s < frameAccumPos && gain > 0.0001f) ? clampShort(frameAccum[s] * gain) : 0;
                    frameBytes[s * 2] = (byte) (v & 0xFF);
                    frameBytes[s * 2 + 1] = (byte) ((v >> 8) & 0xFF);
                }
                paceAndSendFrame(block, frameBytes, nextFrameDeadlineNs, gen);
                frameAccumPos = 0;
            }
        }
    }

    private long paceAndSendFrame(AudioBlock block, byte[] frameBytes, long deadlineNs, int gen) {
        long now = SystemClock.elapsedRealtimeNanos();
        long waitNs = deadlineNs - now;
        if (waitNs > 0) {
            long waitMs = waitNs / 1_000_000L;
            int remNs = (int) (waitNs % 1_000_000L);
            if (waitMs > 0 || remNs > 200_000) {
                try {
                    Thread.sleep(waitMs, remNs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return SystemClock.elapsedRealtimeNanos();
                }
            }
        } else if (waitNs < -250_000_000L) {
            deadlineNs = SystemClock.elapsedRealtimeNanos();
        }

        TeamTalkBase client = this.botClient;
        if (client != null && isStillActive(gen)) {
            block.lpRawAudio = frameBytes;
            block.uSampleIndex = this.sampleIndex;
            this.sampleIndex += FRAME_SAMPLES;
            int retries = 0;
            while (isStillActive(gen) && !client.insertAudioBlock(block)) {
                retries++;
                if (retries >= 3) break;
                SystemClock.sleep(2L);
            }
        }
        return deadlineNs + FRAME_DURATION_NS;
    }

    public void flushEmptyAudioBlock() {
        TeamTalkBase client = this.botClient;
        if (client != null) {
            try {
                AudioBlock empty = new AudioBlock();
                client.insertAudioBlock(empty);
            } catch (Throwable ignored) {
            }
        }
    }

    private void startIcyMetadataPoller(String streamUrl, String referer, int gen) {
        stopIcyPoller();
        Thread t = new Thread(() -> pollIcyMetadata(streamUrl, referer, gen), "Extro-BotIcy");
        t.setDaemon(true);
        synchronized (threadLock) {
            this.icyThread = t;
        }
        t.start();
    }

    private void stopIcyPoller() {
        Thread t;
        synchronized (threadLock) {
            t = this.icyThread;
            this.icyThread = null;
        }
        if (t != null) {
            t.interrupt();
        }
    }

    private void pollIcyMetadata(String streamUrl, String referer, int gen) {
        HttpURLConnection conn = null;
        try {
            String current = streamUrl;
            for (int hop = 0; hop < 6; hop++) {
                conn = (HttpURLConnection) new URL(current).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", BotMusicSearch.USER_AGENT);
                conn.setRequestProperty("Icy-MetaData", "1");
                if (!TextUtils.isEmpty(referer)) {
                    conn.setRequestProperty("Referer", referer);
                }
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (TextUtils.isEmpty(loc)) return;
                    current = new URL(new URL(current), loc).toString();
                    continue;
                }
                if (code < 200 || code >= 300) return;
                break;
            }
            if (conn == null) return;

            String metaIntHeader = conn.getHeaderField("icy-metaint");
            if (TextUtils.isEmpty(metaIntHeader)) return;
            int metaInt = Integer.parseInt(metaIntHeader.trim());
            if (metaInt <= 0 || metaInt > 1024 * 1024) return;

            String lastTitle = "";
            byte[] skipBuf = new byte[4096];
            try (InputStream is = conn.getInputStream()) {
                while (isStillActive(gen)) {
                    int remaining = metaInt;
                    while (remaining > 0 && isStillActive(gen)) {
                        int r = is.read(skipBuf, 0, Math.min(skipBuf.length, remaining));
                        if (r < 0) return;
                        remaining -= r;
                    }
                    if (!isStillActive(gen)) return;
                    int lenByte = is.read();
                    if (lenByte < 0) return;
                    int metaLen = lenByte * 16;
                    if (metaLen > 0) {
                        byte[] metaBuf = new byte[metaLen];
                        int pos = 0;
                        while (pos < metaLen && isStillActive(gen)) {
                            int r = is.read(metaBuf, pos, metaLen - pos);
                            if (r < 0) return;
                            pos += r;
                        }
                        String metaStr = new String(metaBuf, StandardCharsets.UTF_8);
                        Matcher m = ICY_STREAM_TITLE.matcher(metaStr);
                        if (m.find()) {
                            String title = m.group(1) != null ? m.group(1).trim() : "";
                            if (!title.isEmpty() && !title.equals(lastTitle)) {
                                lastTitle = title;
                                OnMetadataListener listener = this.metadataListener;
                                if (listener != null) {
                                    listener.onStreamTitleChanged(title);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void logNote(String message) {
        OnStateNoteListener l = this.stateNoteListener;
        if (l != null) {
            try {
                l.onStreamerLog(message);
            } catch (Throwable ignored) {
            }
        }
    }

    private void notifyVideoState(boolean video) {
        OnStateNoteListener l = this.stateNoteListener;
        if (l != null) {
            try {
                l.onVideoStateChanged(video);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void releaseCodecQuiet(MediaCodec codec) {
        if (codec == null) return;
        try {
            codec.stop();
        } catch (Throwable ignored) {
        }
        try {
            codec.release();
        } catch (Throwable ignored) {
        }
    }

    private static void deleteQuiet(File f) {
        if (f != null && f.exists()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            } catch (Throwable ignored) {
            }
        }
    }

    private static short clampShort(float v) {
        if (v > 32767f) return 32767;
        if (v < -32768f) return -32768;
        return (short) Math.round(v);
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class ChannelMixer {
        private final int srcChannels;
        private final int dstChannels;

        ChannelMixer(int srcChannels, int dstChannels) {
            this.srcChannels = Math.max(1, srcChannels);
            this.dstChannels = Math.max(1, dstChannels);
        }

        int convert(float[] src, int samples, float[] dst) {
            int frames = samples / this.srcChannels;
            if (this.srcChannels == this.dstChannels) {
                System.arraycopy(src, 0, dst, 0, frames * this.srcChannels);
                return frames * this.dstChannels;
            }
            for (int f = 0; f < frames; f++) {
                int base = f * this.srcChannels;
                if (this.srcChannels == 1) {
                    dst[f * 2] = src[base];
                    dst[f * 2 + 1] = src[base];
                } else {
                    dst[f * 2] = src[base];
                    dst[f * 2 + 1] = src[base + 1];
                }
            }
            return frames * this.dstChannels;
        }
    }

    private static final class Resampler {
        private final double step;
        private final int frameSize;
        private float[] buf = new float[16384];
        private int len = 0;
        private double pos = 0.0;

        Resampler(int srcRate, int dstRate, int channels) {
            this.step = (double) Math.max(1, srcRate) / Math.max(1, dstRate);
            this.frameSize = Math.max(1, channels);
        }

        void reset() {
            this.len = 0;
            this.pos = 0.0;
        }

        void push(float[] src, int count) {
            if (this.len + count > this.buf.length) {
                float[] grown = new float[Math.max(this.buf.length * 2, this.len + count)];
                System.arraycopy(this.buf, 0, grown, 0, this.len);
                this.buf = grown;
            }
            System.arraycopy(src, 0, this.buf, this.len, count);
            this.len += count;
        }

        int pull(float[] dst, int maxFrames) {
            int produced = 0;
            while (produced < maxFrames) {
                int idx = (int) this.pos;
                int need = (idx + 1) * this.frameSize + this.frameSize;
                if (need > this.len) break;
                double frac = this.pos - idx;
                int a = idx * this.frameSize;
                int b = a + this.frameSize;
                int o = produced * this.frameSize;
                for (int c = 0; c < this.frameSize; c++) {
                    dst[o + c] = (float) (this.buf[a + c] * (1.0 - frac) + this.buf[b + c] * frac);
                }
                this.pos += this.step;
                produced++;
            }
            int drop = (int) this.pos;
            if (drop > 4096) {
                int dropSamples = drop * this.frameSize;
                System.arraycopy(this.buf, dropSamples, this.buf, 0, this.len - dropSamples);
                this.len -= dropSamples;
                this.pos -= drop;
            }
            return produced;
        }
    }
}
