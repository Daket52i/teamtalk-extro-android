package org.teamtalkextro.bot;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import dk.bearware.AudioBlock;
import dk.bearware.StreamType;
import dk.bearware.TeamTalkBase;
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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decodes online tracks, live radio/HLS streams, and local audio files into
 * 48 kHz stereo PCM16 20ms frames (960 samples/ch = 3840 bytes) and injects
 * them with exact real-time pacing into a dedicated {@link TeamTalkBase} bot
 * instance via {@code insertAudioBlock(AudioBlock)}.
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

    private static final Pattern HLS_BANDWIDTH = Pattern.compile("BANDWIDTH=(\\d+)");
    private static final Pattern HLS_AUDIO_URI = Pattern.compile("URI=\"([^\"]+)\"");
    private static final Pattern ICY_STREAM_TITLE = Pattern.compile("StreamTitle='([^']*)'");

    public interface OnTrackFinishedListener {
        void onTrackFinished(BotMusicSearch.BotTrack track, boolean completedNormally, String errorMessage);
    }

    public interface OnMetadataListener {
        void onStreamTitleChanged(String liveTitle);
    }

    public interface OnListenersListener {
        void onListenersChanged(int count);
    }

    private final Context appContext;
    private volatile TeamTalkBase botClient;
    private volatile boolean running = false;
    private volatile boolean paused = false;
    private volatile int volumePercent = 50;
    private volatile long currentPositionUs = 0L;
    private volatile long totalDurationUs = 0L;
    private volatile boolean pendingSeek = false;
    private volatile long pendingSeekDeltaUs = 0L;
    private volatile BotMusicSearch.BotTrack activeTrack = null;
    private volatile OnTrackFinishedListener finishedListener;
    private volatile OnMetadataListener metadataListener;
    private volatile OnListenersListener listenersListener;

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

    public void setOnTrackFinishedListener(OnTrackFinishedListener listener) {
        this.finishedListener = listener;
    }

    public void setOnMetadataListener(OnMetadataListener listener) {
        this.metadataListener = listener;
    }

    public void setOnListenersListener(OnListenersListener listener) {
        this.listenersListener = listener;
    }

    public void setVolume(int percent) {
        this.volumePercent = Math.max(0, Math.min(100, percent));
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
        return this.currentPositionUs / 1000L;
    }

    public long getTotalDurationMs() {
        return this.totalDurationUs / 1000L;
    }

    public void setPaused(boolean pause) {
        if (this.paused != pause) {
            this.paused = pause;
            if (pause) {
                flushEmptyAudioBlock();
            }
        }
    }

    public void seekBySeconds(int deltaSeconds) {
        if (!this.running) return;
        BotMusicSearch.BotTrack track = this.activeTrack;
        if (track == null || track.isLiveStream) return;
        this.pendingSeekDeltaUs = deltaSeconds * 1_000_000L;
        this.pendingSeek = true;
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
            this.playThread = new Thread(() -> runPlayback(track, introWavFile, gen), "Extro-BotAudio");
            this.playThread.start();
        }
    }

    public void stop() {
        playGeneration.incrementAndGet();
        this.running = false;
        this.paused = false;
        this.pendingSeek = false;
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
                pt.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        flushEmptyAudioBlock();
        this.activeTrack = null;
        this.currentPositionUs = 0L;
        this.totalDurationUs = 0L;
    }

    private float getPerceptualGain() {
        int vol = this.volumePercent;
        if (vol <= 0) return 0f;
        return (float) Math.pow(vol / 100.0, 1.5);
    }

    private void runPlayback(BotMusicSearch.BotTrack initialTrack, File introWavFile, int gen) {
        boolean completedNormally = false;
        String errorMessage = null;
        BotMusicSearch.BotTrack track = initialTrack;
        File tempFile = null;

        try {
            if (introWavFile != null && introWavFile.exists() && introWavFile.length() > 44 && isStillActive(gen)) {
                try {
                    decodeAndStreamFile(introWavFile, gen);
                } catch (Throwable t) {
                    Log.w(TAG, "Intro TTS WAV playback skipped: " + t.getMessage());
                }
            }
            if (!isStillActive(gen)) return;

            track = BotMusicSearch.resolveTrackForPlayback(initialTrack);
            if (!isStillActive(gen)) return;
            this.activeTrack = track;

            if (!TextUtils.isEmpty(track.localUri)) {
                decodeAndStreamLocal(Uri.parse(track.localUri), gen);
                completedNormally = isStillActive(gen);
            } else if (!track.isLiveStream) {
                tempFile = new File(appContext.getCacheDir(), "extro_bot_track.tmp");
                downloadTrackToFile(track.url, track.referer, tempFile, gen);
                if (!isStillActive(gen)) return;
                decodeAndStreamFile(tempFile, gen);
                completedNormally = isStillActive(gen);
            } else {
                String streamUrl = track.url;
                boolean isVolnorez = DjBotCatalogs.VolnorezMetadataReader.supports(streamUrl);
                boolean isHls = streamUrl.toLowerCase(Locale.ROOT).contains(".m3u8");
                if (isHls) {
                    streamUrl = resolveHlsStreamUrl(streamUrl, track.referer);
                    if (!isStillActive(gen)) return;
                } else {
                    if (isVolnorez) {
                        startVolnorezMetadataReader(streamUrl, gen);
                    }
                    streamUrl = resolveRedirectedUrl(streamUrl, track.referer);
                    if (!isStillActive(gen)) return;
                    if (!isVolnorez) {
                        startIcyMetadataPoller(streamUrl, gen);
                    }
                }
                decodeAndStreamUrl(streamUrl, track.referer, gen);
                completedNormally = isStillActive(gen);
            }
        } catch (Throwable t) {
            if (isStillActive(gen)) {
                errorMessage = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                Log.w(TAG, "Bot playback error: " + errorMessage, t);
            }
        } finally {
            stopIcyPoller();
            stopVolnorezReader();
            flushEmptyAudioBlock();
            if (introWavFile != null && introWavFile.exists()) {
                try {
                    introWavFile.delete();
                } catch (Throwable ignored) {
                }
            }
            if (tempFile != null && tempFile.exists()) {
                try {
                    tempFile.delete();
                } catch (Throwable ignored) {
                }
            }
            if (isStillActive(gen)) {
                this.running = false;
                this.activeTrack = null;
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

    private String resolveRedirectedUrl(String urlStr, String referer) {
        String current = urlStr;
        for (int hops = 0; hops < 6; hops++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(current).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", BotMusicSearch.USER_AGENT);
                if (!TextUtils.isEmpty(referer)) {
                    conn.setRequestProperty("Referer", referer);
                }
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    if (TextUtils.isEmpty(loc)) return current;
                    current = new URL(new URL(current), loc).toString();
                    continue;
                }
                return current;
            } catch (Throwable ignored) {
                return current;
            } finally {
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return current;
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

    private void downloadTrackToFile(String urlStr, String referer, File dest, int gen) throws IOException {
        String current = urlStr;
        for (int hops = 0; hops < 5; hops++) {
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

    private String resolveHlsStreamUrl(String playlistUrl, String referer) {
        try {
            String body = BotMusicSearch.httpGet(playlistUrl, referer, null);
            if (body == null || !body.contains("#EXTM3U")) {
                return playlistUrl;
            }
            String[] lines = body.split("\\r?\\n");
            // Prefer dedicated audio rendition if present in master manifest
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#EXT-X-MEDIA:") && trimmed.contains("TYPE=AUDIO")) {
                    Matcher m = HLS_AUDIO_URI.matcher(trimmed);
                    if (m.find() && !TextUtils.isEmpty(m.group(1))) {
                        return new URL(new URL(playlistUrl), m.group(1)).toString();
                    }
                }
            }
            // Otherwise select the lowest bandwidth variant (saves mobile data & decodes smoothly)
            long bestBw = Long.MAX_VALUE;
            String bestUri = null;
            for (int i = 0; i < lines.length; i++) {
                String trimmed = lines[i].trim();
                if (trimmed.startsWith("#EXT-X-STREAM-INF:")) {
                    long bw = Long.MAX_VALUE - 1;
                    Matcher bm = HLS_BANDWIDTH.matcher(trimmed);
                    if (bm.find()) {
                        try {
                            bw = Long.parseLong(bm.group(1));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    for (int j = i + 1; j < lines.length; j++) {
                        String next = lines[j].trim();
                        if (next.isEmpty() || next.startsWith("#")) continue;
                        if (bw < bestBw) {
                            bestBw = bw;
                            bestUri = next;
                        }
                        break;
                    }
                }
            }
            if (!TextUtils.isEmpty(bestUri)) {
                return new URL(new URL(playlistUrl), bestUri).toString();
            }
        } catch (Throwable t) {
            Log.w(TAG, "HLS master resolution fallback to original URL", t);
        }
        return playlistUrl;
    }

    private void decodeAndStreamLocal(Uri uri, int gen) throws IOException {
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

    private void decodeAndStreamFile(File file, int gen) throws IOException {
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

    private void decodeAndStreamUrl(String url, String referer, int gen) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", BotMusicSearch.USER_AGENT);
            if (!TextUtils.isEmpty(referer)) {
                headers.put("Referer", referer);
            }
            extractor.setDataSource(url, headers);
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
            Resampler resampler = new Resampler(srcRate, TARGET_SAMPLE_RATE, TARGET_CHANNELS);
            ChannelMixer mixer = new ChannelMixer(srcChannels, TARGET_CHANNELS);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            short[] pcmScratch = new short[8192];
            float[] floatScratch = new float[8192];
            float[] chanScratch = new float[8192];
            float[] resampledOut = new float[4096];

            short[] frameAccum = new short[FRAME_SHORTS];
            int frameAccumPos = 0;
            byte[] frameBytes = new byte[FRAME_BYTES];
            AudioBlock block = new AudioBlock();
            block.nStreamID = 1;
            block.uStreamTypes = StreamType.STREAMTYPE_VOICE;
            block.nSampleRate = TARGET_SAMPLE_RATE;
            block.nChannels = TARGET_CHANNELS;
            block.nSamples = FRAME_SAMPLES;

            long nextFrameDeadlineNs = SystemClock.elapsedRealtimeNanos();

            while (isStillActive(gen)) {
                if (this.paused) {
                    sleepQuiet(40L);
                    nextFrameDeadlineNs = SystemClock.elapsedRealtimeNanos();
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
                        resampler.reset();
                        frameAccumPos = 0;
                        inputDone = false;
                        this.currentPositionUs = extractor.getSampleTime();
                        nextFrameDeadlineNs = SystemClock.elapsedRealtimeNanos();
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

                int outIndex = codec.dequeueOutputBuffer(info, DECODE_TIMEOUT_US);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFmt = codec.getOutputFormat();
                    int newRate = newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                            ? newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : srcRate;
                    int newCh = newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                            ? newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : srcChannels;
                    if (newRate != srcRate || newCh != srcChannels) {
                        srcRate = newRate;
                        srcChannels = newCh;
                        resampler = new Resampler(srcRate, TARGET_SAMPLE_RATE, TARGET_CHANNELS);
                        mixer = new ChannelMixer(srcChannels, TARGET_CHANNELS);
                    }
                } else if (outIndex >= 0) {
                    if (info.size > 0) {
                        ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                        if (outBuf != null) {
                            outBuf.position(info.offset);
                            outBuf.limit(info.offset + info.size);
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
                                    while (this.paused && isStillActive(gen)) {
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
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        // Pad and send final partial frame if any
                        if (frameAccumPos > 0 && isStillActive(gen)) {
                            float gain = getPerceptualGain();
                            for (int s = 0; s < FRAME_SHORTS; s++) {
                                short v = (s < frameAccumPos && gain > 0.0001f) ? clampShort(frameAccum[s] * gain) : 0;
                                frameBytes[s * 2] = (byte) (v & 0xFF);
                                frameBytes[s * 2 + 1] = (byte) ((v >> 8) & 0xFF);
                            }
                            paceAndSendFrame(block, frameBytes, nextFrameDeadlineNs, gen);
                        }
                        break;
                    }
                }
            }
        } finally {
            try {
                codec.stop();
            } catch (Throwable ignored) {
            }
            try {
                codec.release();
            } catch (Throwable ignored) {
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
            // Re-sync clock after lag/pause so we never burst frames faster than real time
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

    private void startIcyMetadataPoller(String streamUrl, int gen) {
        stopIcyPoller();
        Thread t = new Thread(() -> pollIcyMetadata(streamUrl, gen), "Extro-BotIcy");
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

    private void pollIcyMetadata(String streamUrl, int gen) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(streamUrl).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", BotMusicSearch.USER_AGENT);
            conn.setRequestProperty("Icy-MetaData", "1");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return;

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
