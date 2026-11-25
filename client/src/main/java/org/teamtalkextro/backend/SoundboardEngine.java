package org.teamtalkextro.backend;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

/**
 * Built-in Soundboard / Jingle Machine that synthesizes 6 crisp studio PCM
 * sound effects on the fly and mixes them directly into the outgoing voice
 * stream + local monitor, even when no karaoke backing track is active.
 */
public final class SoundboardEngine {

    private static final String TAG = "SoundboardEngine";

    public static final int SFX_APPLAUSE = 0;
    public static final int SFX_DRUMROLL = 1;
    public static final int SFX_FANFARE = 2;
    public static final int SFX_JINGLE = 3;
    public static final int SFX_DING = 4;
    public static final int SFX_BUZZER = 5;

    private final Object lock = new Object();
    private short[] currentPcm;
    private int cursor;
    private int activeSampleRate = 48000;
    private int activeChannels = 1;
    private volatile boolean active = false;
    private volatile boolean monitorEnabled = true;

    private AudioTrack monitor;
    private int monitorRate;
    private int monitorChannels;
    private byte[] monitorBuf = new byte[0];

    public boolean isActive() {
        return this.active;
    }

    public void setMonitorEnabled(boolean enabled) {
        this.monitorEnabled = enabled;
        if (!enabled) {
            releaseMonitor();
        }
    }

    /**
     * Triggers a synthesized soundboard effect. Returns approximate duration in ms.
     */
    public long trigger(int effectId, int sampleRate, int channels) {
        int sr = sampleRate > 0 ? sampleRate : 48000;
        int ch = channels >= 2 ? 2 : 1;
        short[] mono = synthesizeMono(effectId, sr);
        short[] pcm;
        if (ch == 2) {
            pcm = new short[mono.length * 2];
            for (int i = 0; i < mono.length; i++) {
                pcm[i * 2] = mono[i];
                pcm[i * 2 + 1] = mono[i];
            }
        } else {
            pcm = mono;
        }
        synchronized (this.lock) {
            this.currentPcm = pcm;
            this.cursor = 0;
            this.activeSampleRate = sr;
            this.activeChannels = ch;
            this.active = pcm.length > 0;
        }
        return (mono.length * 1000L) / sr;
    }

    public void stop() {
        synchronized (this.lock) {
            this.active = false;
            this.currentPcm = null;
            this.cursor = 0;
        }
        releaseMonitor();
    }

    /**
     * Mixes currently playing soundboard SFX into {@code dst} (16-bit LE PCM)
     * and plays the SFX locally on monitor if enabled.
     */
    public void mixInto(byte[] dst, int offset, int length) {
        if (!this.active || dst == null || length <= 0) {
            return;
        }
        int numSamples = length / 2;
        if (numSamples <= 0) {
            return;
        }

        boolean finishedNow = false;
        int sr;
        int ch;
        byte[] mon = null;

        synchronized (this.lock) {
            short[] pcm = this.currentPcm;
            if (pcm == null || this.cursor >= pcm.length) {
                this.active = false;
                this.currentPcm = null;
                return;
            }
            sr = this.activeSampleRate;
            ch = this.activeChannels;
            int avail = Math.min(numSamples, pcm.length - this.cursor);
            if (this.monitorEnabled) {
                if (this.monitorBuf.length < length) {
                    this.monitorBuf = new byte[length];
                }
                mon = this.monitorBuf;
            }

            for (int i = 0; i < numSamples; i++) {
                short sfx = i < avail ? pcm[this.cursor + i] : 0;
                int bIdx = offset + i * 2;
                short mic = (short) ((dst[bIdx] & 255) | ((dst[bIdx + 1] & 255) << 8));
                short mixed = VoiceChanger.clampToShort((float) mic + (float) sfx);
                dst[bIdx] = (byte) (mixed & 255);
                dst[bIdx + 1] = (byte) ((mixed >> 8) & 255);
                if (mon != null) {
                    mon[i * 2] = (byte) (sfx & 255);
                    mon[i * 2 + 1] = (byte) ((sfx >> 8) & 255);
                }
            }

            this.cursor += avail;
            if (this.cursor >= pcm.length) {
                this.active = false;
                this.currentPcm = null;
                finishedNow = true;
            }
        }

        if (mon != null) {
            AudioTrack track = ensureMonitor(sr, ch);
            if (track != null) {
                try {
                    track.write(mon, 0, length, AudioTrack.WRITE_NON_BLOCKING);
                } catch (Throwable t) {
                    Log.w(TAG, "soundboard monitor write failed", t);
                }
            }
        }
        if (finishedNow && !this.monitorEnabled) {
            releaseMonitor();
        }
    }

    private synchronized AudioTrack ensureMonitor(int sampleRate, int channels) {
        if (this.monitor != null && this.monitor.getState() == AudioTrack.STATE_INITIALIZED
                && this.monitorRate == sampleRate && this.monitorChannels == channels) {
            return this.monitor;
        }
        releaseMonitor();
        int mask = channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int min = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            min = sampleRate * channels;
        }
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(mask)
                    .build();
            AudioTrack track = new AudioTrack(attrs, fmt, min * 2,
                    AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            track.play();
            this.monitor = track;
            this.monitorRate = sampleRate;
            this.monitorChannels = channels;
            return track;
        } catch (Throwable t) {
            Log.w(TAG, "cannot open soundboard monitor", t);
            return null;
        }
    }

    public synchronized void releaseMonitor() {
        AudioTrack track = this.monitor;
        this.monitor = null;
        if (track != null) {
            try { track.pause(); } catch (Throwable ignored) {}
            try { track.flush(); } catch (Throwable ignored) {}
            try { track.release(); } catch (Throwable ignored) {}
        }
    }

    private static short[] synthesizeMono(int effectId, int sr) {
        switch (effectId) {
            case SFX_APPLAUSE:
                return synthApplause(sr);
            case SFX_DRUMROLL:
                return synthDrumroll(sr);
            case SFX_FANFARE:
                return synthFanfare(sr);
            case SFX_JINGLE:
                return synthJingle(sr);
            case SFX_DING:
                return synthDing(sr);
            case SFX_BUZZER:
                return synthBuzzer(sr);
            default:
                return synthDing(sr);
        }
    }

    private static int xorshift(int s) {
        s ^= (s << 13);
        s ^= (s >>> 17);
        s ^= (s << 5);
        return s;
    }

    private static short[] synthApplause(int sr) {
        int len = (int) (sr * 2.2);
        short[] out = new short[len];
        int seed = 0x51A3C92B;
        double bpY1 = 0.0, bpY2 = 0.0;
        double lp = 0.0, hp = 0.0, prevX = 0.0;
        // Multiple asynchronous clapper envelopes
        int numClappers = 10;
        double[] nextClap = new double[numClappers];
        double[] clapEnv = new double[numClappers];
        for (int c = 0; c < numClappers; c++) {
            seed = xorshift(seed);
            nextClap[c] = ((seed & 0xFFFF) / 65536.0) * 0.14;
        }
        double dt = 1.0 / sr;
        for (int i = 0; i < len; i++) {
            double t = i * dt;
            double masterEnv = Math.min(1.0, t / 0.12) * Math.min(1.0, (2.2 - t) / 0.45);
            double clapSum = 0.0;
            for (int c = 0; c < numClappers; c++) {
                if (t >= nextClap[c]) {
                    clapEnv[c] = 1.0;
                    seed = xorshift(seed);
                    nextClap[c] = t + 0.085 + ((seed & 0xFFFF) / 65536.0) * 0.095;
                }
                clapEnv[c] *= Math.exp(-dt / 0.018);
                clapSum += clapEnv[c];
            }
            seed = xorshift(seed);
            double noise = ((seed & 0xFFFF) / 32768.0) - 1.0;
            // Bandpass ~800-3500 Hz for natural palm-clap resonance
            hp = 0.88 * (hp + noise - prevX);
            prevX = noise;
            lp += (hp - lp) * 0.35;
            bpY1 += (lp - bpY1) * 0.5;
            bpY2 = (bpY1 * (0.28 + 0.16 * clapSum)) * masterEnv;
            out[i] = VoiceChanger.clampToShort((float) (bpY2 * 24000.0));
        }
        return out;
    }

    private static short[] synthDrumroll(int sr) {
        int len = (int) (sr * 2.1);
        short[] out = new short[len];
        int seed = 0x2F4E8A11;
        double dt = 1.0 / sr;
        double nextHit = 0.0;
        double hitEnv = 0.0;
        double crashEnv = 0.0;
        double snarePhase = 0.0;
        double hp = 0.0, prevN = 0.0;
        boolean crashed = false;

        for (int i = 0; i < len; i++) {
            double t = i * dt;
            if (t < 1.55) {
                if (t >= nextHit) {
                    hitEnv = 0.65 + 0.35 * (t / 1.55);
                    double interval = 0.075 - 0.032 * (t / 1.55);
                    nextHit = t + interval;
                }
            } else if (!crashed) {
                crashed = true;
                crashEnv = 1.0;
            }
            hitEnv *= Math.exp(-dt / 0.025);
            crashEnv *= Math.exp(-dt / 0.28);

            seed = xorshift(seed);
            double noise = ((seed & 0xFFFF) / 32768.0) - 1.0;
            hp = 0.90 * (hp + noise - prevN);
            prevN = noise;

            snarePhase += 2.0 * Math.PI * 185.0 * dt;
            double drum = (Math.sin(snarePhase) * 0.45 + hp * 0.75) * hitEnv;
            double cymbal = (hp * 0.85 + Math.sin(t * 2.0 * Math.PI * 520.0) * 0.15) * crashEnv;
            double fadeOut = Math.min(1.0, (2.1 - t) / 0.15);
            out[i] = VoiceChanger.clampToShort((float) ((drum * 0.7 + cymbal * 0.75) * fadeOut * 23000.0));
        }
        return out;
    }

    private static short[] synthFanfare(int sr) {
        int len = (int) (sr * 2.0);
        short[] out = new short[len];
        double dt = 1.0 / sr;
        // C5 (523.25), E5 (659.25), G5 (783.99), C6 (1046.5)
        double[] freqs = {523.25, 659.25, 783.99, 1046.50};
        double[] starts = {0.0, 0.22, 0.44, 0.70};
        double[] durs = {0.21, 0.21, 0.24, 1.25};

        for (int i = 0; i < len; i++) {
            double t = i * dt;
            double sample = 0.0;
            for (int n = 0; n < freqs.length; n++) {
                double nt = t - starts[n];
                if (nt >= 0.0 && nt < durs[n]) {
                    double env = Math.min(1.0, nt / 0.022) * Math.min(1.0, (durs[n] - nt) / 0.14);
                    if (n == 3) {
                        env *= Math.exp(-nt * 0.9);
                    }
                    double f = freqs[n];
                    double brass = Math.sin(2.0 * Math.PI * f * nt)
                            + 0.45 * Math.sin(2.0 * Math.PI * f * 2.0 * nt)
                            + 0.22 * Math.sin(2.0 * Math.PI * f * 3.0 * nt)
                            + 0.10 * Math.sin(2.0 * Math.PI * f * 4.0 * nt);
                    sample += brass * env * 0.34;
                }
            }
            // Add supporting C major chord on final hit
            if (t >= 0.70) {
                double nt = t - 0.70;
                double env = Math.min(1.0, nt / 0.03) * Math.exp(-nt * 1.1) * Math.min(1.0, (2.0 - t) / 0.15);
                sample += (Math.sin(2.0 * Math.PI * 523.25 * nt) * 0.18
                        + Math.sin(2.0 * Math.PI * 659.25 * nt) * 0.16
                        + Math.sin(2.0 * Math.PI * 783.99 * nt) * 0.16) * env;
            }
            out[i] = VoiceChanger.clampToShort((float) (sample * 20000.0));
        }
        return out;
    }

    private static short[] synthJingle(int sr) {
        int len = (int) (sr * 1.4);
        short[] out = new short[len];
        double dt = 1.0 / sr;
        // Radio chime E5 - G#5 - B5 - E6
        double[] freqs = {659.25, 830.61, 987.77, 1318.51};
        double[] starts = {0.0, 0.16, 0.32, 0.48};
        for (int i = 0; i < len; i++) {
            double t = i * dt;
            double sample = 0.0;
            for (int n = 0; n < freqs.length; n++) {
                double nt = t - starts[n];
                if (nt >= 0.0) {
                    double env = Math.min(1.0, nt / 0.008) * Math.exp(-nt * 3.8);
                    double f = freqs[n];
                    double tone = Math.sin(2.0 * Math.PI * f * nt)
                            + 0.30 * Math.sin(2.0 * Math.PI * f * 2.0 * nt)
                            + 0.12 * Math.sin(2.0 * Math.PI * f * 3.0 * nt);
                    sample += tone * env * 0.32;
                }
            }
            double tailFade = Math.min(1.0, (1.4 - t) / 0.10);
            out[i] = VoiceChanger.clampToShort((float) (sample * tailFade * 21000.0));
        }
        return out;
    }

    private static short[] synthDing(int sr) {
        int len = (int) (sr * 1.3);
        short[] out = new short[len];
        double dt = 1.0 / sr;
        double f0 = 1046.50; // C6 bell
        for (int i = 0; i < len; i++) {
            double t = i * dt;
            double attack = Math.min(1.0, t / 0.003);
            double s = Math.sin(2.0 * Math.PI * f0 * t) * Math.exp(-t * 2.6)
                    + 0.48 * Math.sin(2.0 * Math.PI * f0 * 2.0 * t) * Math.exp(-t * 4.2)
                    + 0.25 * Math.sin(2.0 * Math.PI * f0 * 2.76 * t) * Math.exp(-t * 6.0)
                    + 0.14 * Math.sin(2.0 * Math.PI * f0 * 4.07 * t) * Math.exp(-t * 8.5);
            double tailFade = Math.min(1.0, (1.3 - t) / 0.08);
            out[i] = VoiceChanger.clampToShort((float) (s * attack * tailFade * 17000.0));
        }
        return out;
    }

    private static short[] synthBuzzer(int sr) {
        int len = (int) (sr * 0.85);
        short[] out = new short[len];
        double dt = 1.0 / sr;
        for (int i = 0; i < len; i++) {
            double t = i * dt;
            double env = Math.min(1.0, t / 0.012) * Math.min(1.0, (0.85 - t) / 0.08);
            // Layered detuned brassy game-show horn (155 Hz + 162 Hz + 310 Hz)
            double s1 = Math.sin(2.0 * Math.PI * 155.0 * t);
            double s2 = Math.sin(2.0 * Math.PI * 162.5 * t);
            double s3 = Math.sin(2.0 * Math.PI * 310.0 * t) * 0.55;
            double s4 = Math.sin(2.0 * Math.PI * 465.0 * t) * 0.35;
            double raw = (s1 + s2 + s3 + s4) * 0.55;
            // Soft square-ish saturation
            double clipped = Math.tanh(raw * 2.1);
            out[i] = VoiceChanger.clampToShort((float) (clipped * env * 19500.0));
        }
        return out;
    }
}
