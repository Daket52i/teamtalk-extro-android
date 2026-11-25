package org.teamtalkextro.backend;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Plays a user-supplied backing track (minus) and hands its PCM to the capture
 * loop, where it is mixed into the outgoing voice stream. The listener hears
 * one already-mixed stream, so there is no second stream and no sync delay.
 *
 * Decoding is streaming (MediaExtractor + MediaCodec) into a small ring buffer;
 * nothing is loaded into memory whole, so hour-long tracks are fine.
 *
 * The track is played exactly once: at end of stream the decoder stops and the
 * ring is drained, so the song never restarts on its own and never loops with
 * an audible click. The local monitor (what the singer hears) is fed from the
 * same ring read that feeds the outgoing mix, so both stay sample-aligned.
 */
public final class KaraokeMixer {

    private static final String TAG = "KaraokeMixer";
    private static final int RING_SECONDS = 2;
    private static final int PREROLL_MS = 150;
    private static final int DECODE_TIMEOUT_US = 10000;
    private static final int PITCH_BUF_SIZE = 8192;

    public interface OnFinishedListener {
        void onKaraokeFinished();
    }

    private final Context context;
    private final Object ringLock = new Object();

    private volatile boolean running;
    private volatile boolean paused;
    private volatile boolean eos;
    private volatile boolean drainRequested;
    private volatile boolean primed;
    private volatile int targetSampleRate = 48000;
    private volatile int targetChannels = 1;
    private volatile float gain = 0.75f;
    private volatile boolean monitorEnabled = true;
    private volatile boolean duckingEnabled = false;
    private volatile int pitchSemitones = 0;
    private volatile String title = "";
    private volatile OnFinishedListener finishedListener;

    // Dedicated instance-owned pitch-shift buffers (never collide with VoiceChanger)
    private final short[] pitchBufL = new short[PITCH_BUF_SIZE];
    private final short[] pitchBufR = new short[PITCH_BUF_SIZE];
    private int pitchWritePos = 0;
    private float pitchPhase = 0.0f;
    private short[] frameScratchL = new short[2048];
    private short[] frameScratchR = new short[2048];

    private short[] ring = new short[0];
    private int ringWrite;
    private int ringCount;

    private Thread decodeThread;
    private volatile AudioTrack monitor;
    private Uri source;

    public KaraokeMixer(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isActive() {
        return this.running && !this.eos;
    }

    public boolean isRunning() {
        return this.running;
    }

    public boolean isPaused() {
        return this.paused;
    }

    public String getTitle() {
        return this.title;
    }

    public Uri getSource() {
        return this.source;
    }

    public void setGain(float value) {
        this.gain = Math.max(0f, Math.min(1f, value));
    }

    public float getGain() {
        return this.gain;
    }

    private float getEffectiveGain() {
        float g = this.gain;
        if (g <= 0.001f) {
            return 0f;
        }
        // Perceptual volume curve with 0.75 headroom at 100% so a mastered
        // backing track leaves room for the singer's voice without clipping.
        return (float) (Math.pow(g, 1.5) * 0.75);
    }

    public boolean isMonitorEnabled() {
        return this.monitorEnabled;
    }

    public void setMonitorEnabled(boolean enabled) {
        this.monitorEnabled = enabled;
        if (!enabled) {
            releaseMonitor();
        } else if (this.running) {
            ensureMonitor();
        }
    }

    public boolean isDuckingEnabled() {
        return this.duckingEnabled;
    }

    public void setDuckingEnabled(boolean enabled) {
        this.duckingEnabled = enabled;
    }

    public int getPitchSemitones() {
        return this.pitchSemitones;
    }

    public void setPitchSemitones(int semitones) {
        int clamped = Math.max(-6, Math.min(6, semitones));
        if (this.pitchSemitones != clamped) {
            this.pitchSemitones = clamped;
            if (clamped == 0) {
                resetPitchState();
            }
        }
    }

    public void setOnFinishedListener(OnFinishedListener listener) {
        this.finishedListener = listener;
    }

    private void resetPitchState() {
        java.util.Arrays.fill(this.pitchBufL, (short) 0);
        java.util.Arrays.fill(this.pitchBufR, (short) 0);
        this.pitchWritePos = 0;
        this.pitchPhase = 0.0f;
    }

    public void setPaused(boolean value) {
        this.paused = value;
        AudioTrack track = this.monitor;
        if (track != null && track.getState() == AudioTrack.STATE_INITIALIZED) {
            try {
                if (value) {
                    track.pause();
                } else {
                    track.play();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public synchronized void start(Uri uri, int sampleRate, int channels) {
        stop();
        if (uri == null || sampleRate <= 0 || channels <= 0) {
            return;
        }
        this.source = uri;
        this.targetSampleRate = sampleRate;
        this.targetChannels = channels;
        this.paused = false;
        this.eos = false;
        this.drainRequested = false;
        this.primed = false;
        resetPitchState();
        synchronized (this.ringLock) {
            this.ring = new short[Math.max(sampleRate * RING_SECONDS * channels, 4096)];
            this.ringWrite = 0;
            this.ringCount = 0;
        }
        this.title = readTitle(uri);
        this.running = true;
        if (this.monitorEnabled) {
            ensureMonitor();
        }
        this.decodeThread = new Thread(this::decodeLoop, "KaraokeDecode");
        this.decodeThread.start();
    }

    public synchronized void stop() {
        this.running = false;
        Thread t = this.decodeThread;
        this.decodeThread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this.ringLock) {
            this.ringCount = 0;
            this.ringWrite = 0;
        }
        releaseMonitor();
        this.eos = false;
        this.drainRequested = false;
        this.primed = false;
        this.source = null;
        this.title = "";
    }

    /**
     * Fills {@code dst} with the backing track in the target format. Zeroes are
     * written on underrun, so the capture loop never blocks on the decoder.
     */
    public void read(byte[] dst, int offset, int length) {
        read(dst, offset, length, 1.0f);
    }

    /**
     * Fills {@code dst} with the backing track in the target format, applying
     * optional real-time key shift and smooth auto-ducking multiplier.
     */
    public void read(byte[] dst, int offset, int length, float duckMultiplier) {
        if (dst == null || length <= 0) {
            return;
        }
        for (int i = offset; i < offset + length; i++) {
            dst[i] = 0;
        }
        if (!this.running || this.paused) {
            return;
        }
        int samples = length / 2;
        if (samples <= 0) {
            return;
        }

        int channels = Math.max(1, this.targetChannels);
        int sr = Math.max(8000, this.targetSampleRate);
        int semi = this.pitchSemitones;
        float clampedDuck = Math.max(0.15f, Math.min(1.0f, duckMultiplier));
        float effectiveGain = getEffectiveGain() * clampedDuck;

        int got = 0;
        synchronized (this.ringLock) {
            // Do not start the song with a half-empty buffer: the first frames
            // would be silence and the voice would arrive before the track.
            if (!this.primed) {
                int preroll = Math.min(sr * PREROLL_MS / 1000 * channels,
                        this.ring.length / 2);
                if (this.paused || (this.ringCount < preroll && !this.drainRequested)) {
                    return;
                }
                this.primed = true;
            }
            int available = Math.min(samples, this.ringCount);
            int readPos = this.ringWrite - this.ringCount;
            if (readPos < 0) {
                readPos += this.ring.length;
            }

            if (semi == 0) {
                for (int i = 0; i < available; i++) {
                    short s = this.ring[readPos];
                    short out = effectiveGain <= 0f ? 0 : VoiceChanger.clampToShort(s * effectiveGain);
                    dst[offset + i * 2] = (byte) (out & 255);
                    dst[offset + i * 2 + 1] = (byte) ((out >> 8) & 255);
                    readPos++;
                    if (readPos >= this.ring.length) {
                        readPos = 0;
                    }
                }
            } else {
                int frames = available / channels;
                if (frames > 0) {
                    if (this.frameScratchL.length < frames) {
                        this.frameScratchL = new short[Math.max(frames, this.frameScratchL.length * 2)];
                        this.frameScratchR = new short[this.frameScratchL.length];
                    }
                    for (int f = 0; f < frames; f++) {
                        this.frameScratchL[f] = this.ring[readPos];
                        if (++readPos >= this.ring.length) readPos = 0;
                        if (channels > 1) {
                            this.frameScratchR[f] = this.ring[readPos];
                            if (++readPos >= this.ring.length) readPos = 0;
                        }
                    }
                    float factor = (float) Math.pow(2.0, semi / 12.0);
                    applyPitchShiftInternal(this.frameScratchL, channels > 1 ? this.frameScratchR : null, frames, factor, sr);
                    for (int f = 0; f < frames; f++) {
                        short outL = effectiveGain <= 0f ? 0 : VoiceChanger.clampToShort(this.frameScratchL[f] * effectiveGain);
                        int bIdx = offset + (f * channels * 2);
                        dst[bIdx] = (byte) (outL & 255);
                        dst[bIdx + 1] = (byte) ((outL >> 8) & 255);
                        if (channels > 1) {
                            short outR = effectiveGain <= 0f ? 0 : VoiceChanger.clampToShort(this.frameScratchR[f] * effectiveGain);
                            dst[bIdx + 2] = (byte) (outR & 255);
                            dst[bIdx + 3] = (byte) ((outR >> 8) & 255);
                        }
                    }
                    available = frames * channels;
                }
            }
            this.ringCount -= available;
            got = available;
        }

        if (!this.monitorEnabled || this.paused) {
            return;
        }
        AudioTrack track = this.monitor;
        if (track != null && got > 0) {
            try {
                // Always hand the full frame to the track; the tail is already
                // zeroed, which keeps the monitor clock steady on underrun.
                track.write(dst, offset, samples * 2, AudioTrack.WRITE_NON_BLOCKING);
            } catch (Throwable t) {
                Log.w(TAG, "monitor write failed", t);
            }
        }
    }

    private float readPitchInterp(short[] buf, float pos) {
        int floor = ((int) Math.floor(pos)) % PITCH_BUF_SIZE;
        if (floor < 0) floor += PITCH_BUF_SIZE;
        float frac = pos - (float) Math.floor(pos);
        return buf[(floor + 1) % PITCH_BUF_SIZE] * frac + buf[floor] * (1.0f - frac);
    }

    private void applyPitchShiftInternal(short[] sL, short[] sR, int numFrames, float factor, int sampleRate) {
        float win = Math.min(2048, Math.max(512, (sampleRate * 42) / 1000));
        float phaseStep = (1.0f - factor) / win;
        for (int i = 0; i < numFrames; i++) {
            int wp = this.pitchWritePos;
            this.pitchBufL[wp] = sL[i];
            if (sR != null) {
                this.pitchBufR[wp] = sR[i];
            }
            this.pitchPhase += phaseStep;
            while (this.pitchPhase >= 1.0f) this.pitchPhase -= 1.0f;
            while (this.pitchPhase < 0.0f) this.pitchPhase += 1.0f;
            float phase2 = this.pitchPhase + 0.5f;
            if (phase2 >= 1.0f) phase2 -= 1.0f;

            float pos1 = ((wp - (this.pitchPhase * win)) + PITCH_BUF_SIZE) % PITCH_BUF_SIZE;
            if (pos1 < 0f) pos1 += PITCH_BUF_SIZE;
            float pos2 = ((wp - (phase2 * win)) + PITCH_BUF_SIZE) % PITCH_BUF_SIZE;
            if (pos2 < 0f) pos2 += PITCH_BUF_SIZE;

            float w1 = (1.0f - (float) Math.cos(this.pitchPhase * 6.283185307179586d)) * 0.5f;
            float w2 = 1.0f - w1;
            sL[i] = VoiceChanger.clampToShort(readPitchInterp(this.pitchBufL, pos1) * w1 + readPitchInterp(this.pitchBufL, pos2) * w2);
            if (sR != null) {
                sR[i] = VoiceChanger.clampToShort(readPitchInterp(this.pitchBufR, pos1) * w1 + readPitchInterp(this.pitchBufR, pos2) * w2);
            }
            this.pitchWritePos = (wp + 1) % PITCH_BUF_SIZE;
        }
    }

    private AudioTrack ensureMonitor() {
        AudioTrack track = this.monitor;
        if (track != null && track.getState() == AudioTrack.STATE_INITIALIZED) {
            return track;
        }
        releaseMonitor();
        int channels = this.targetChannels;
        int channelMask = channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int min = AudioTrack.getMinBufferSize(this.targetSampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            min = this.targetSampleRate * channels;
        }
        try {
            // minSdk is 21, so no AudioTrack.Builder (API 23) here.
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(this.targetSampleRate)
                    .setChannelMask(channelMask)
                    .build();
            AudioTrack created = new AudioTrack(attrs, fmt, min * 2,
                    AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            created.play();
            this.monitor = created;
            return created;
        } catch (Throwable t) {
            Log.w(TAG, "cannot open local monitor", t);
            return null;
        }
    }

    private void releaseMonitor() {
        AudioTrack track = this.monitor;
        this.monitor = null;
        if (track != null) {
            try {
                track.pause();
            } catch (Throwable ignored) {
            }
            try {
                track.flush();
            } catch (Throwable ignored) {
            }
            try {
                track.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private String readTitle(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this.context, uri);
            String t = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            return t != null ? t : "";
        } catch (Throwable ignored) {
            return "";
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private void decodeLoop() {
        boolean failed = false;
        try {
            processMedia();
        } catch (Throwable t) {
            failed = true;
            Log.e(TAG, "karaoke decode failed", t);
        }
        if (!this.running) {
            return;
        }
        if (failed) {
            this.eos = true;
            releaseMonitor();
            return;
        }
        // The decoder is done, but up to RING_SECONDS of audio may still sit in
        // the ring. Let the capture loop play it out before reporting the end,
        // otherwise the last seconds of every song are cut.
        this.drainRequested = true;
        while (this.running && ringCountLocked() > 0) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                return;
            }
        }
        if (!this.running) {
            return;
        }
        this.eos = true;
        releaseMonitor();
        OnFinishedListener listener = this.finishedListener;
        if (listener != null) {
            try {
                listener.onKaraokeFinished();
            } catch (Throwable ignored) {
            }
        }
    }

    private int ringCountLocked() {
        synchronized (this.ringLock) {
            return this.ringCount;
        }
    }

    private void processMedia() throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(this.context, this.source, null);
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
                Log.w(TAG, "no audio track in backing file");
                return;
            }
            extractor.selectTrack(trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            int srcRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : this.targetSampleRate;
            int srcChannels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            Resampler resampler = new Resampler(srcRate, this.targetSampleRate, this.targetChannels);
            ChannelMixer channelMixer = new ChannelMixer(srcChannels, this.targetChannels);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            float[] converted = new float[8192];
            float[] channelBuf = new float[8192];
            short[] pcm = new short[8192];
            float[] out = new float[4096];

            while (this.running) {
                while (this.paused && this.running) {
                    sleepQuiet(50L);
                }
                if (this.running && !inputDone) {
                    int inIndex = codec.dequeueInputBuffer(DECODE_TIMEOUT_US);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                        int size = inBuf == null ? -1 : extractor.readSampleData(inBuf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, DECODE_TIMEOUT_US);
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                        if (outBuf != null) {
                            outBuf.position(info.offset);
                            outBuf.limit(info.offset + info.size);
                            ShortBuffer shorts = outBuf.order(ByteOrder.nativeOrder()).asShortBuffer();
                            int samples = shorts.remaining();
                            // Mono source into a stereo target doubles the
                            // sample count, so size the channel buffer for
                            // the converted length, not the source length.
                            int dstSamples = samples / srcChannels * this.targetChannels;
                            if (converted.length < samples) {
                                converted = new float[samples];
                            }
                            if (channelBuf.length < dstSamples) {
                                channelBuf = new float[dstSamples];
                            }
                            if (pcm.length < samples) {
                                pcm = new short[samples];
                            }
                            shorts.get(pcm, 0, samples);
                            for (int i = 0; i < samples; i++) {
                                converted[i] = pcm[i];
                            }
                            int framed = channelMixer.convert(converted, samples, channelBuf);
                            resampler.push(channelBuf, framed);
                            int produced;
                            while (this.running && (produced = resampler.pull(out, out.length / this.targetChannels)) > 0) {
                                int count = produced * this.targetChannels;
                                while (this.running && !this.paused && ringSpace() < count) {
                                    sleepQuiet(10L);
                                }
                                if (!this.running) {
                                    break;
                                }
                                writeToRing(out, count);
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        // The backing track plays exactly once. Restarting the
                        // decoder here made the song loop and click on every
                        // restart, which sounded like a distorted repeat.
                        break;
                    }
                }

                // Keep the ring close to full; when it is there is nothing to
                // do but wait for the capture thread to drain it.
                while (this.running && !this.paused && ringFull()) {
                    sleepQuiet(10L);
                }
            }
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignored) {
                }
                try {
                    codec.release();
                } catch (Throwable ignored) {
                }
            }
            try {
                extractor.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean ringFull() {
        synchronized (this.ringLock) {
            return this.ringCount >= this.ring.length - 1024;
        }
    }

    private int ringSpace() {
        synchronized (this.ringLock) {
            return this.ring.length - this.ringCount;
        }
    }

    private void writeToRing(float[] src, int count) {
        synchronized (this.ringLock) {
            int capacity = this.ring.length;
            int space = capacity - this.ringCount;
            if (space <= 0) {
                // Latency is bounded: drop the oldest audio instead of new
                // frames, so playback stays current rather than lagging behind.
                int drop = Math.min(count, capacity / 2);
                this.ringCount -= drop;
                if (this.ringCount < 0) {
                    this.ringCount = 0;
                }
                space = capacity - this.ringCount;
            }
            int n = Math.min(count, space);
            for (int i = 0; i < n; i++) {
                // src[i] is already in 16-bit PCM range [-32768..32767] from ShortsBuffer.
                this.ring[this.ringWrite] = VoiceChanger.clampToShort(src[i]);
                this.ringWrite++;
                if (this.ringWrite >= capacity) {
                    this.ringWrite = 0;
                }
            }
            this.ringCount += n;
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** Converts interleaved input frames to the target channel count. */
    private static final class ChannelMixer {
        private final int srcChannels;
        private final int dstChannels;

        ChannelMixer(int srcChannels, int dstChannels) {
            this.srcChannels = Math.max(1, srcChannels);
            this.dstChannels = Math.max(1, dstChannels);
        }

        /** Returns the number of samples in {@code dst}; {@code dst} must hold src*frames*2. */
        int convert(float[] src, int samples, float[] dst) {
            int frames = samples / this.srcChannels;
            if (this.srcChannels == this.dstChannels) {
                if (dst != src) {
                    System.arraycopy(src, 0, dst, 0, frames * this.srcChannels);
                }
                return frames * this.dstChannels;
            }
            for (int f = 0; f < frames; f++) {
                int base = f * this.srcChannels;
                if (this.dstChannels == 1) {
                    float sum = 0f;
                    for (int c = 0; c < this.srcChannels; c++) {
                        sum += src[base + c];
                    }
                    dst[f] = sum / this.srcChannels;
                } else {
                    if (this.srcChannels == 1) {
                        dst[f * 2] = src[base];
                        dst[f * 2 + 1] = src[base];
                    } else {
                        dst[f * 2] = src[base];
                        dst[f * 2 + 1] = src[base + 1];
                    }
                }
            }
            return frames * this.dstChannels;
        }
    }

    /** Streaming linear resampler that keeps its fractional position across pushes. */
    private static final class Resampler {
        private final double step;
        private final int frameSize;
        private float[] buf = new float[16384];
        private int len;
        private double pos;

        Resampler(int srcRate, int dstRate, int channels) {
            this.step = (double) Math.max(1, srcRate) / Math.max(1, dstRate);
            this.frameSize = Math.max(1, channels);
        }

        void reset() {
            this.len = 0;
            this.pos = 0;
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

        /** Returns the number of frames written into {@code dst}. */
        int pull(float[] dst, int maxFrames) {
            int produced = 0;
            while (produced < maxFrames) {
                int idx = (int) this.pos;
                int need = (idx + 1) * this.frameSize + this.frameSize;
                if (need > this.len) {
                    break;
                }
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
