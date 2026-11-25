package org.teamtalkextro.backend;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import java.lang.reflect.Array;
import java.util.Arrays;
import org.teamtalkextro.data.Preferences;

public class VoiceChanger {
    private static final int CHORUS_MAX_SAMPLES = 2048;
    private static final int MAX_ALLPASS_SIZE = 1024;
    private static final int MAX_COMB_SIZE = 3200;
    private static final int MAX_ECHO_SAMPLES = 48000;
    private static final int NUM_ALLPASS = 4;
    private static final int NUM_COMBS = 8;
    private static final int PITCH_BUFFER_SIZE = 8192;
    private static final int STEREO_SPREAD = 23;
    private static final int AUTOTUNE_BUF_SIZE = 2048;

    public static final int SCALE_CHROMATIC = 0;
    public static final int SCALE_MAJOR_C = 1;
    public static final int SCALE_MINOR_A = 2;

    private static final int[] SCALE_NOTES_CHROMATIC = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
    private static final int[] SCALE_NOTES_MAJOR_C = {0, 2, 4, 5, 7, 9, 11};
    private static final int[] SCALE_NOTES_MINOR_A = {0, 2, 4, 5, 8, 9, 11};

    private static final int[] COMB_TUNINGS_L;
    private static final int[] ALLPASS_TUNINGS_L;

    private static final float[][] combBufL;
    private static final float[][] combBufR;
    private static final int[] combIndexL;
    private static final int[] combIndexR;
    private static final float[] combFilterL;
    private static final float[] combFilterR;

    private static final float[][] allpassBufL;
    private static final float[][] allpassBufR;
    private static final int[] allpassIndexL;
    private static final int[] allpassIndexR;

    private static final float[] chorusBufL;
    private static final float[] chorusBufR;
    private static int chorusIndex;

    private static double filterX1_L;
    private static double filterX1_R;
    private static double filterX2_L;
    private static double filterX2_R;
    private static double filterY1_L;
    private static double filterY1_R;
    private static double filterY2_L;
    private static double filterY2_R;

    // Additional DSP states for v5.31.0 studio/broadcast/whisper/walkie effects
    private static double lowShelfL, lowShelfR;
    private static double highShelfL, highShelfR;
    private static double bandHpX_L, bandHpY_L, bandHpX_R, bandHpY_R;
    private static double bandLpY_L, bandLpY_R;
    private static double compEnv = 0.0d;
    private static double gateGain = 1.0d;
    private static double whisperEnvL = 0.0d, whisperEnvR = 0.0d;
    private static double whisperNoiseLpL = 0.0d, whisperNoiseLpR = 0.0d;
    private static int noiseSeed = 0x13579BDF;

    // Real-time Auto-Tune pitch detection state
    private static final float[] autotuneRing = new float[AUTOTUNE_BUF_SIZE];
    private static final float[] autotuneWindow = new float[1536];
    private static int autotuneWritePos = 0;
    private static int autotuneFilled = 0;
    private static float autotuneRatio = 1.0f;
    private static volatile int autotuneScale = SCALE_CHROMATIC;

    private static double lfoPhase;
    private static double monsterPhase;
    private static double robotPhase;

    private static short[] scratchSamplesL;
    private static short[] scratchSamplesR;

    private static volatile Mode currentMode = Mode.OFF;
    private static volatile boolean echoEnabled = true;
    private static volatile int echoLevel = 35;
    private static volatile boolean reverbEnabled = true;
    private static volatile int reverbLevel = 50;
    private static volatile int reverbRoomSize = 60;

    private static final short[] pitchBufL = new short[PITCH_BUFFER_SIZE];
    private static final short[] pitchBufR = new short[PITCH_BUFFER_SIZE];
    private static int pitchWritePos = 0;
    private static float pitchPhase = 0.0f;

    private static final float[] echoBufL = new float[MAX_ECHO_SAMPLES];
    private static final float[] echoBufR = new float[MAX_ECHO_SAMPLES];
    private static int echoIndex = 0;
    private static float echoFilterL = 0.0f;
    private static float echoFilterR = 0.0f;

    public enum Mode {
        OFF(0),
        ECHO(1),
        DEEP(2),
        CHIPMUNK(3),
        ROBOT(4),
        RADIO(5),
        MEGAPHONE(6),
        MONSTER(7),
        ALIEN(8),
        UNDERWATER(9),
        CHORUS(10),
        TELEPHONE(11),
        GHOST(12),
        AUTOTUNE(13),
        AUTOTUNE_HARD(14),
        STUDIO(15),
        CONCERT_HALL(16),
        DEMON(17),
        HELIUM(18),
        FEMALE(19),
        MALE(20),
        WALKIE_TALKIE(21),
        WHISPER(22),
        BROADCAST_DJ(23);

        private final int id;

        Mode(int id) {
            this.id = id;
        }

        public static Mode fromId(int id) {
            for (Mode mode : values()) {
                if (mode.id == id) {
                    return mode;
                }
            }
            return OFF;
        }

        public int getId() {
            return this.id;
        }
    }

    static {
        Class<?> cls = Float.TYPE;
        combBufL = (float[][]) Array.newInstance(cls, NUM_COMBS, MAX_COMB_SIZE);
        combBufR = (float[][]) Array.newInstance(cls, NUM_COMBS, MAX_COMB_SIZE);
        combIndexL = new int[NUM_COMBS];
        combIndexR = new int[NUM_COMBS];
        combFilterL = new float[NUM_COMBS];
        combFilterR = new float[NUM_COMBS];

        allpassBufL = (float[][]) Array.newInstance(cls, NUM_ALLPASS, MAX_ALLPASS_SIZE);
        allpassBufR = (float[][]) Array.newInstance(cls, NUM_ALLPASS, MAX_ALLPASS_SIZE);
        allpassIndexL = new int[NUM_ALLPASS];
        allpassIndexR = new int[NUM_ALLPASS];

        COMB_TUNINGS_L = new int[]{1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        ALLPASS_TUNINGS_L = new int[]{556, 441, 341, 225};

        robotPhase = 0.0d;
        lfoPhase = 0.0d;
        monsterPhase = 0.0d;

        filterX1_L = 0.0d;
        filterX2_L = 0.0d;
        filterY1_L = 0.0d;
        filterY2_L = 0.0d;
        filterX1_R = 0.0d;
        filterX2_R = 0.0d;
        filterY1_R = 0.0d;
        filterY2_R = 0.0d;

        chorusBufL = new float[CHORUS_MAX_SAMPLES];
        chorusBufR = new float[CHORUS_MAX_SAMPLES];
        chorusIndex = 0;

        scratchSamplesL = new short[CHORUS_MAX_SAMPLES];
        scratchSamplesR = new short[CHORUS_MAX_SAMPLES];
    }

    public static void loadFromPreferences(Context context) {
        if (context == null) {
            return;
        }
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
            int mode = 0;
            try {
                mode = prefs.getInt(Preferences.PREF_VOICE_CHANGER_MODE, 0);
            } catch (ClassCastException e) {
                try {
                    mode = Integer.parseInt(prefs.getString(Preferences.PREF_VOICE_CHANGER_MODE, "0"));
                } catch (Exception ignored) {
                }
            }
            boolean echoOn = prefs.getBoolean(Preferences.PREF_VOICE_ECHO_ENABLED, true);
            int echoLvl = prefs.getInt(Preferences.PREF_VOICE_ECHO_LEVEL, 35);
            boolean revOn = prefs.getBoolean(Preferences.PREF_VOICE_REVERB_ENABLED, true);
            int revLvl = prefs.getInt(Preferences.PREF_VOICE_REVERB_LEVEL, 50);
            int room = prefs.getInt(Preferences.PREF_VOICE_REVERB_ROOM_SIZE, 60);
            int scale = prefs.getInt(Preferences.PREF_VOICE_AUTOTUNE_SCALE, SCALE_CHROMATIC);
            setEchoReverbConfig(echoOn, echoLvl, revOn, revLvl, room);
            setAutotuneScale(scale);
            setVoiceChangerMode(mode);
        } catch (Throwable ignored) {
        }
    }

    public static Mode getMode() {
        return currentMode;
    }

    public static int getVoiceChangerMode() {
        return currentMode != null ? currentMode.getId() : 0;
    }

    public static synchronized void setVoiceChangerMode(int id) {
        setMode(Mode.fromId(id));
    }

    public static synchronized void setMode(Mode mode) {
        synchronized (VoiceChanger.class) {
            if (mode == null) {
                mode = Mode.OFF;
            }
            currentMode = mode;
            resetState();
        }
    }

    public static int getAutotuneScale() {
        return autotuneScale;
    }

    public static synchronized void setAutotuneScale(int scale) {
        if (scale < SCALE_CHROMATIC || scale > SCALE_MINOR_A) {
            scale = SCALE_CHROMATIC;
        }
        autotuneScale = scale;
    }

    public static boolean isEchoEnabled() {
        return echoEnabled;
    }

    public static int getEchoLevel() {
        return echoLevel;
    }

    public static boolean isReverbEnabled() {
        return reverbEnabled;
    }

    public static int getReverbLevel() {
        return reverbLevel;
    }

    public static int getReverbRoomSize() {
        return reverbRoomSize;
    }

    public static synchronized void setEchoReverbConfig(boolean echo, int echoLvl, boolean reverb, int reverbLvl, int roomSize) {
        synchronized (VoiceChanger.class) {
            echoEnabled = echo;
            echoLevel = Math.max(0, Math.min(100, echoLvl));
            reverbEnabled = reverb;
            reverbLevel = Math.max(0, Math.min(100, reverbLvl));
            reverbRoomSize = Math.max(0, Math.min(100, roomSize));
        }
    }

    public static void setEchoReverbConfig(boolean echo, int echoLvl, boolean reverb, int reverbLvl) {
        setEchoReverbConfig(echo, echoLvl, reverb, reverbLvl, reverbRoomSize);
    }

    public static short clampToShort(float f) {
        if (f > 28000.0f) {
            float excess = f - 28000.0f;
            f = (excess / (2.1276595E-4f * excess + 1.0f)) + 28000.0f;
            return (short) (f + 0.5f);
        } else if (f < -28000.0f) {
            float excess = (-f) - 28000.0f;
            f = -((excess / (2.1276595E-4f * excess + 1.0f)) + 28000.0f);
            return (short) (f - 0.5f);
        } else {
            return (short) (f >= 0.0f ? f + 0.5f : f - 0.5f);
        }
    }

    private static double softSaturate(double d) {
        if (d > 1.0d) {
            return 0.85d;
        }
        if (d < -1.0d) {
            return -0.85d;
        }
        return d - (((d * d) * d) / 4.0d);
    }

    private static float nextNoise() {
        int x = noiseSeed;
        x ^= (x << 13);
        x ^= (x >>> 17);
        x ^= (x << 5);
        noiseSeed = x;
        return (x & 0xFFFF) / 32768.0f - 1.0f;
    }

    private static float readInterpolated(short[] buf, float pos) {
        double d = pos;
        int floor = ((int) Math.floor(d)) % PITCH_BUFFER_SIZE;
        if (floor < 0) {
            floor += PITCH_BUFFER_SIZE;
        }
        float frac = pos - ((float) Math.floor(d));
        return (buf[(floor + 1) % PITCH_BUFFER_SIZE] * frac) + ((1.0f - frac) * buf[floor]);
    }

    private static void applyPitchShift(short[] sArr, short[] sArr2, int numSamples, float factor, int sampleRate) {
        float min = Math.min(2048, Math.max(512, (sampleRate * 42) / 1000));
        float f6 = (1.0f - factor) / min;
        for (int i5 = 0; i5 < numSamples; i5++) {
            int writePos = pitchWritePos;
            pitchBufL[writePos] = sArr[i5];
            if (sArr2 != null) {
                pitchBufR[writePos] = sArr2[i5];
            }
            pitchPhase += f6;
            while (pitchPhase >= 1.0f) {
                pitchPhase -= 1.0f;
            }
            while (pitchPhase < 0.0f) {
                pitchPhase += 1.0f;
            }
            float phase2 = pitchPhase + 0.5f;
            if (phase2 >= 1.0f) {
                phase2 -= 1.0f;
            }
            float f9 = phase2 * min;
            float pos1 = ((pitchWritePos - (pitchPhase * min)) + PITCH_BUFFER_SIZE) % PITCH_BUFFER_SIZE;
            while (pos1 < 0.0f) {
                pos1 += PITCH_BUFFER_SIZE;
            }
            float pos2 = ((pitchWritePos - f9) + PITCH_BUFFER_SIZE) % PITCH_BUFFER_SIZE;
            while (pos2 < 0.0f) {
                pos2 += PITCH_BUFFER_SIZE;
            }
            float read1 = readInterpolated(pitchBufL, pos1);
            float read2 = readInterpolated(pitchBufL, pos2);
            float cos = (1.0f - ((float) Math.cos(pitchPhase * 6.283185307179586d))) * 0.5f;
            float f12 = 1.0f - cos;
            sArr[i5] = clampToShort((read2 * f12) + (read1 * cos));
            if (sArr2 != null) {
                sArr2[i5] = clampToShort((readInterpolated(pitchBufR, pos2) * f12) + (readInterpolated(pitchBufR, pos1) * cos));
            }
            pitchWritePos = (pitchWritePos + 1) % PITCH_BUFFER_SIZE;
        }
    }

    // --- Real-time Autocorrelation Pitch Detector + Scale Quantizer (Auto-Tune) ---

    private static float detectPitchHz(int sampleRate) {
        int winLen = Math.min(autotuneWindow.length, autotuneFilled);
        int maxLag = Math.min(winLen / 2, Math.max(40, sampleRate / 80));
        int minLag = Math.max(16, sampleRate / 700);
        if (winLen < maxLag * 2 || minLag >= maxLag) {
            return 0.0f;
        }

        int start = autotuneWritePos - winLen;
        if (start < 0) {
            start += AUTOTUNE_BUF_SIZE;
        }
        double mean = 0.0d;
        for (int i = 0; i < winLen; i++) {
            int idx = start + i;
            if (idx >= AUTOTUNE_BUF_SIZE) idx -= AUTOTUNE_BUF_SIZE;
            float v = autotuneRing[idx];
            autotuneWindow[i] = v;
            mean += v;
        }
        mean /= winLen;
        double energy = 0.0d;
        for (int i = 0; i < winLen; i++) {
            float v = (float) (autotuneWindow[i] - mean);
            autotuneWindow[i] = v;
            energy += v * v;
        }
        double rms = Math.sqrt(energy / winLen);
        if (rms < 240.0d) {
            return 0.0f;
        }

        int corrLen = winLen - maxLag;
        double bestCorr = 0.0d;
        int bestLag = -1;
        // First pass: coarse step=2 to find peak correlation
        for (int lag = minLag; lag <= maxLag; lag += 2) {
            double num = 0.0d;
            double denA = 0.0d;
            double denB = 0.0d;
            for (int i = 0; i < corrLen; i += 2) {
                double a = autotuneWindow[i];
                double b = autotuneWindow[i + lag];
                num += a * b;
                denA += a * a;
                denB += b * b;
            }
            double den = Math.sqrt(denA * denB);
            if (den > 1e-6d) {
                double r = num / den;
                if (r > bestCorr) {
                    bestCorr = r;
                    bestLag = lag;
                }
            }
        }
        if (bestLag < 0 || bestCorr < 0.45d) {
            return 0.0f;
        }

        // Prevent octave-down lock: prefer earliest strong peak >= 0.88 * bestCorr
        double threshold = bestCorr * 0.88d;
        int chosenLag = bestLag;
        double prevR = 0.0d;
        double currR = 0.0d;
        for (int lag = minLag; lag <= bestLag; lag += 2) {
            double num = 0.0d;
            double denA = 0.0d;
            double denB = 0.0d;
            for (int i = 0; i < corrLen; i += 2) {
                double a = autotuneWindow[i];
                double b = autotuneWindow[i + lag];
                num += a * b;
                denA += a * a;
                denB += b * b;
            }
            double den = Math.sqrt(denA * denB);
            double nextR = den > 1e-6d ? num / den : 0.0d;
            if (lag > minLag + 2 && currR >= threshold && currR >= prevR && currR >= nextR) {
                chosenLag = lag - 2;
                break;
            }
            prevR = currR;
            currR = nextR;
        }

        // Fine search around chosenLag with step=1
        int fineStart = Math.max(minLag, chosenLag - 3);
        int fineEnd = Math.min(maxLag, chosenLag + 3);
        double fineBest = -1.0d;
        int fineLag = chosenLag;
        for (int lag = fineStart; lag <= fineEnd; lag++) {
            double r = normalizedAcf(lag, corrLen);
            if (r > fineBest) {
                fineBest = r;
                fineLag = lag;
            }
        }
        if (fineBest < 0.45d) {
            return 0.0f;
        }

        // Parabolic interpolation around fineLag
        double refinedLag = fineLag;
        if (fineLag > minLag && fineLag < maxLag) {
            double rPrev = normalizedAcf(fineLag - 1, corrLen);
            double rNext = normalizedAcf(fineLag + 1, corrLen);
            double denom = 2.0d * (rPrev - 2.0d * fineBest + rNext);
            if (Math.abs(denom) > 1e-6d) {
                double delta = (rPrev - rNext) / denom;
                if (delta > -0.5d && delta < 0.5d) {
                    refinedLag += delta;
                }
            }
        }
        return (float) (sampleRate / refinedLag);
    }

    private static double normalizedAcf(int lag, int corrLen) {
        double num = 0.0d;
        double denA = 0.0d;
        double denB = 0.0d;
        for (int i = 0; i < corrLen; i++) {
            double a = autotuneWindow[i];
            double b = autotuneWindow[i + lag];
            num += a * b;
            denA += a * a;
            denB += b * b;
        }
        double den = Math.sqrt(denA * denB);
        return den > 1e-6d ? num / den : 0.0d;
    }

    private static double quantizeMidiToScale(double midi, int scale) {
        int[] notes;
        if (scale == SCALE_MAJOR_C) {
            notes = SCALE_NOTES_MAJOR_C;
        } else if (scale == SCALE_MINOR_A) {
            notes = SCALE_NOTES_MINOR_A;
        } else {
            notes = SCALE_NOTES_CHROMATIC;
        }
        int baseOctave = ((int) Math.floor(midi / 12.0d)) * 12;
        double bestMidi = Math.round(midi);
        double bestDist = 100.0d;
        for (int octShift = -12; octShift <= 12; octShift += 12) {
            for (int n : notes) {
                double candidate = baseOctave + octShift + n;
                double dist = Math.abs(candidate - midi);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestMidi = candidate;
                }
            }
        }
        return bestMidi;
    }

    private static void applyAutotuneEffect(short[] sArr, short[] sArr2, int numSamples, int sampleRate, boolean hardMode) {
        for (int i = 0; i < numSamples; i++) {
            float mono = sArr2 != null ? (sArr[i] + sArr2[i]) * 0.5f : sArr[i];
            autotuneRing[autotuneWritePos] = mono;
            autotuneWritePos++;
            if (autotuneWritePos >= AUTOTUNE_BUF_SIZE) {
                autotuneWritePos = 0;
            }
            if (autotuneFilled < AUTOTUNE_BUF_SIZE) {
                autotuneFilled++;
            }
        }

        float f0 = detectPitchHz(sampleRate);
        float targetRatio = 1.0f;
        if (f0 >= 75.0f && f0 <= 720.0f) {
            double midi = 69.0d + 12.0d * (Math.log(f0 / 440.0d) / Math.log(2.0d));
            double snappedMidi = quantizeMidiToScale(midi, autotuneScale);
            double targetHz = 440.0d * Math.pow(2.0d, (snappedMidi - 69.0d) / 12.0d);
            targetRatio = (float) (targetHz / f0);
            targetRatio = Math.max(0.75f, Math.min(1.35f, targetRatio));
        }

        float glide = hardMode ? 0.82f : 0.24f;
        if (f0 <= 0.0f) {
            glide = 0.14f;
        }
        autotuneRatio += (targetRatio - autotuneRatio) * glide;

        applyPitchShift(sArr, sArr2, numSamples, autotuneRatio, sampleRate);

        if (hardMode) {
            // Subtle bright chorus shimmer for crisp T-Pain pop polish
            applySubtleChorusShimmer(sArr, sArr2, numSamples, sampleRate, 0.22f);
            applyToneShelves(sArr, sArr2, numSamples, sampleRate, 0.0f, 0.25f);
        } else {
            applyToneShelves(sArr, sArr2, numSamples, sampleRate, 0.10f, 0.15f);
        }
    }

    private static void applySubtleChorusShimmer(short[] sArr, short[] sArr2, int numSamples, int sampleRate, float wet) {
        double d4 = 6.911503837897545d / sampleRate; // ~1.1 Hz
        float fSampleRate = sampleRate;
        int baseDelay = Math.round(0.012f * fSampleRate);
        int depth = Math.round(0.0035f * fSampleRate);
        float dry = 1.0f - wet;
        for (int i = 0; i < numSamples; i++) {
            float inL = sArr[i];
            float inR = sArr2 != null ? sArr2[i] : inL;
            chorusBufL[chorusIndex] = inL;
            chorusBufR[chorusIndex] = inR;
            float readPos = ((chorusIndex - (float) (baseDelay + Math.sin(lfoPhase) * depth)) + CHORUS_MAX_SAMPLES) % CHORUS_MAX_SAMPLES;
            if (readPos < 0.0f) readPos += CHORUS_MAX_SAMPLES;
            int i0 = (int) readPos;
            int i1 = (i0 + 1) % CHORUS_MAX_SAMPLES;
            float frac = readPos - i0;
            float delL = chorusBufL[i0] * (1.0f - frac) + chorusBufL[i1] * frac;
            float delR = chorusBufR[i0] * (1.0f - frac) + chorusBufR[i1] * frac;
            sArr[i] = clampToShort(inL * dry + delL * wet);
            if (sArr2 != null) {
                sArr2[i] = clampToShort(inR * dry + delR * wet);
            }
            chorusIndex = (chorusIndex + 1) % CHORUS_MAX_SAMPLES;
            lfoPhase += d4;
            if (lfoPhase > 6.283185307179586d) {
                lfoPhase -= 6.283185307179586d;
            }
        }
    }

    // --- Studio / EQ / Compressor / Broadcast / Character Helpers ---

    private static void applyToneShelves(short[] sArr, short[] sArr2, int numSamples, int sampleRate, float bassBoost, float trebleBoost) {
        double lpAlpha = 1.0d - Math.exp(-2.0d * Math.PI * 200.0d / sampleRate);
        double hpAlpha = 1.0d - Math.exp(-2.0d * Math.PI * 2800.0d / sampleRate);
        for (int i = 0; i < numSamples; i++) {
            double inL = sArr[i];
            lowShelfL += (inL - lowShelfL) * lpAlpha;
            highShelfL += (inL - highShelfL) * hpAlpha;
            double highsL = inL - highShelfL;
            double outL = inL + (lowShelfL * bassBoost) + (highsL * trebleBoost);
            sArr[i] = clampToShort((float) outL);
            if (sArr2 != null) {
                double inR = sArr2[i];
                lowShelfR += (inR - lowShelfR) * lpAlpha;
                highShelfR += (inR - highShelfR) * hpAlpha;
                double highsR = inR - highShelfR;
                double outR = inR + (lowShelfR * bassBoost) + (highsR * trebleBoost);
                sArr2[i] = clampToShort((float) outR);
            }
        }
    }

    private static void applyStudioDynamics(short[] sArr, short[] sArr2, int numSamples, int sampleRate,
                                            double gateThreshold, double compThreshold, double compRatio, double makeupGain) {
        double attackCoeff = 1.0d - Math.exp(-1.0d / (sampleRate * 0.006d));
        double releaseCoeff = 1.0d - Math.exp(-1.0d / (sampleRate * 0.080d));
        double gateAttack = 1.0d - Math.exp(-1.0d / (sampleRate * 0.005d));
        double gateRelease = 1.0d - Math.exp(-1.0d / (sampleRate * 0.060d));

        for (int i = 0; i < numSamples; i++) {
            double inL = sArr[i] / 32768.0d;
            double inR = sArr2 != null ? (sArr2[i] / 32768.0d) : inL;
            double level = Math.max(Math.abs(inL), Math.abs(inR));

            if (level > compEnv) {
                compEnv += (level - compEnv) * attackCoeff;
            } else {
                compEnv += (level - compEnv) * releaseCoeff;
            }

            double targetGate = (gateThreshold > 0.0d && compEnv < gateThreshold) ? 0.12d : 1.0d;
            gateGain += (targetGate - gateGain) * (targetGate > gateGain ? gateAttack : gateRelease);

            double compGain = 1.0d;
            if (compEnv > compThreshold) {
                double over = compEnv / compThreshold;
                double reduced = Math.pow(over, (1.0d / compRatio) - 1.0d);
                compGain = reduced;
            }

            double totalGain = gateGain * compGain * makeupGain;
            sArr[i] = clampToShort((float) (softSaturate(inL * totalGain) * 32767.0d));
            if (sArr2 != null) {
                sArr2[i] = clampToShort((float) (softSaturate(inR * totalGain) * 32767.0d));
            }
        }
    }

    private static void applyStudioEffect(short[] sArr, short[] sArr2, int numSamples, int sampleRate) {
        applyStudioDynamics(sArr, sArr2, numSamples, sampleRate, 0.008d, 0.18d, 3.0d, 1.35d);
        applyToneShelves(sArr, sArr2, numSamples, sampleRate, 0.28f, 0.30f);
    }

    private static void applyConcertHallEffect(short[] sArr, short[] sArr2, int numSamples, int sampleRate) {
        applyStudioDynamics(sArr, sArr2, numSamples, sampleRate, 0.006d, 0.20d, 2.8d, 1.25d);
        applyToneShelves(sArr, sArr2, numSamples, sampleRate, 0.18f, 0.22f);
        applyReverbCustom(sArr, sArr2, numSamples, sampleRate, 62, 88, 0.28f);
    }

    private static void applyDemonEffect(short[] sArr, short[] sArr2, int numSamples, int sampleRate) {
        applyPitchShift(sArr, sArr2, numSamples, 0.56f, sampleRate);
        double dPhase = (2.0d * Math.PI * 24.0d) / sampleRate;
        for (int i = 0; i < numSamples; i++) {
            double growl = 0.78d + 0.22d * Math.sin(monsterPhase);
            monsterPhase += dPhase;
            if (monsterPhase > 6.283185307179586d) {
                monsterPhase -= 6.283185307179586d;
            }
            sArr[i] = clampToShort((float) (sArr[i] * growl));
            if (sArr2 != null) {
                sArr2[i] = clampToShort((float) (sArr2[i] * growl));
            }
        }
        applyToneShelves(sArr, sArr2, numSamples, sampleRate, 0.45f, -0.25f);
        applyReverbCustom(sArr, sArr2, numSamples, sampleRate, 46, 76, 0.55f);
    }

    private static void applyHeliumEffect(short[] sArr, short[] sArr2, int numSamples, int sampleRate) {
        applyPitchShift(sArr, sArr2, numSamples, 1.65f, sampleRate);
        applyToneShelves(sArr, sArr2, numSamples, sampleRate, -0.15f, 0.32f);
    }

    private static void applyFemaleEffect(short[] sArr, short[] sArr2, int numSamples, int sampleRate) {
        applyPitchShift(sArr, sArr2, numSamples, 1.24f, sampleRate);
        applyToneShelves(sArr, sArr2, numSamples, sampleRate, -0.08f, 0.28f);
    }

    private static void applyMaleEffect(short[] sArr, short[] sArr2, int numSamples, int sampleRate) {
        applyPitchShift(sArr, sArr2, numSamples, 0.82f, sampleRate);
        applyToneShelves(sArr, sArr2, numSamples, sampleRate, 0.38f, 0.08f);
    }

    private static void applyWalkieTalkieEffect(short[] sArr, short[] sArr2, int numSamples, int sampleRate) {
        // 400 Hz high-pass + 2800 Hz low-pass narrow bandpass + crisp overdrive
        double dt = 1.0d / sampleRate;
        double rcHp = 1.0d / (2.0d * Math.PI * 400.0d);
        double hpAlpha = rcHp / (rcHp + dt);
        double rcLp = 1.0d / (2.0d * Math.PI * 2800.0d);
        double lpAlpha = dt / (rcLp + dt);

        for (int i = 0; i < numSamples; i++) {
            double inL = sArr[i] / 32768.0d;
            double hpL = hpAlpha * (bandHpY_L + inL - bandHpX_L);
            bandHpX_L = inL;
            bandHpY_L = hpL;
            bandLpY_L += (hpL - bandLpY_L) * lpAlpha;
            double drivenL = softSaturate(bandLpY_L * 2.25d) * 0.92d;
            sArr[i] = clampToShort((float) (drivenL * 32767.0d));

            if (sArr2 != null) {
                double inR = sArr2[i] / 32768.0d;
                double hpR = hpAlpha * (bandHpY_R + inR - bandHpX_R);
                bandHpX_R = inR;
                bandHpY_R = hpR;
                bandLpY_R += (hpR - bandLpY_R) * lpAlpha;
                double drivenR = softSaturate(bandLpY_R * 2.25d) * 0.92d;
                sArr2[i] = clampToShort((float) (drivenR * 32767.0d));
            }
        }
    }

    private static void applyWhisperEffect(short[] sArr, short[] sArr2, int numSamples, int sampleRate) {
        double dt = 1.0d / sampleRate;
        double rcHp = 1.0d / (2.0d * Math.PI * 750.0d);
        double hpAlpha = rcHp / (rcHp + dt);
        double envAttack = 1.0d - Math.exp(-1.0d / (sampleRate * 0.008d));
        double envRelease = 1.0d - Math.exp(-1.0d / (sampleRate * 0.040d));
        double noiseLpAlpha = 1.0d - Math.exp(-2.0d * Math.PI * 3600.0d / sampleRate);

        for (int i = 0; i < numSamples; i++) {
            double inL = sArr[i] / 32768.0d;
            double hpL = hpAlpha * (bandHpY_L + inL - bandHpX_L);
            bandHpX_L = inL;
            bandHpY_L = hpL;
            double absL = Math.abs(inL);
            whisperEnvL += (absL - whisperEnvL) * (absL > whisperEnvL ? envAttack : envRelease);
            double nL = nextNoise();
            whisperNoiseLpL += (nL - whisperNoiseLpL) * noiseLpAlpha;
            double breathL = whisperNoiseLpL * whisperEnvL * 2.4d;
            double outL = softSaturate((hpL * 0.42d) + (breathL * 0.78d));
            sArr[i] = clampToShort((float) (outL * 32767.0d));

            if (sArr2 != null) {
                double inR = sArr2[i] / 32768.0d;
                double hpR = hpAlpha * (bandHpY_R + inR - bandHpX_R);
                bandHpX_R = inR;
                bandHpY_R = hpR;
                double absR = Math.abs(inR);
                whisperEnvR += (absR - whisperEnvR) * (absR > whisperEnvR ? envAttack : envRelease);
                double nR = nextNoise();
                whisperNoiseLpR += (nR - whisperNoiseLpR) * noiseLpAlpha;
                double breathR = whisperNoiseLpR * whisperEnvR * 2.4d;
                double outR = softSaturate((hpR * 0.42d) + (breathR * 0.78d));
                sArr2[i] = clampToShort((float) (outR * 32767.0d));
            }
        }
    }

    private static void applyBroadcastDjEffect(short[] sArr, short[] sArr2, int numSamples, int sampleRate) {
        applyStudioDynamics(sArr, sArr2, numSamples, sampleRate, 0.007d, 0.12d, 4.5d, 1.58d);
        applyToneShelves(sArr, sArr2, numSamples, sampleRate, 0.42f, 0.36f);
    }

    private static void applyReverbCustom(short[] sArr, short[] sArr2, int numSamples, int sampleRate,
                                          int revLevelPct, int roomSizePct, float damping) {
        boolean stereo = sArr2 != null;
        float wetNorm = revLevelPct / 100.0f;
        float wetGain = wetNorm * wetNorm;
        float roomNorm = roomSizePct / 100.0f;
        float scaleFactor = (0.55f * roomNorm) + 0.72f;
        float feedback = (((float) Math.pow(roomNorm, 1.2d)) * 0.27f) + 0.7f;
        float rateScale = (sampleRate / 44100.0f) * scaleFactor;
        float dampInv = 1.0f - damping;

        int[] combActualL = new int[NUM_COMBS];
        int[] combActualR = stereo ? new int[NUM_COMBS] : null;
        for (int i = 0; i < NUM_COMBS; i++) {
            combActualL[i] = Math.min(3199, Math.max(16, Math.round(COMB_TUNINGS_L[i] * rateScale)));
            if (stereo) {
                combActualR[i] = Math.min(3199, Math.max(16, Math.round((COMB_TUNINGS_L[i] + STEREO_SPREAD) * rateScale)));
            }
        }
        int[] apActualL = new int[NUM_ALLPASS];
        int[] apActualR = stereo ? new int[NUM_ALLPASS] : null;
        for (int i = 0; i < NUM_ALLPASS; i++) {
            apActualL[i] = Math.min(1023, Math.max(8, Math.round(ALLPASS_TUNINGS_L[i] * rateScale)));
            if (stereo) {
                apActualR[i] = Math.min(1023, Math.max(8, Math.round((ALLPASS_TUNINGS_L[i] + STEREO_SPREAD) * rateScale)));
            }
        }
        float dryGain = Math.max(0.35f, 1.0f - (wetGain * 0.35f));

        for (int i = 0; i < numSamples; i++) {
            float inL = sArr[i];
            float inR = stereo ? sArr2[i] : inL;
            float combSumL = 0.0f;
            float combSumR = 0.0f;
            for (int c = 0; c < NUM_COMBS; c++) {
                int idxL = combIndexL[c];
                float outL = combBufL[c][idxL];
                float filtL = (combFilterL[c] * damping) + (outL * dampInv);
                combFilterL[c] = filtL;
                combBufL[c][idxL] = (filtL * feedback) + inL;
                if (++idxL >= combActualL[c]) idxL = 0;
                combIndexL[c] = idxL;
                combSumL += outL;

                if (stereo) {
                    int idxR = combIndexR[c];
                    float outR = combBufR[c][idxR];
                    float filtR = (combFilterR[c] * damping) + (outR * dampInv);
                    combFilterR[c] = filtR;
                    combBufR[c][idxR] = (filtR * feedback) + inR;
                    if (++idxR >= combActualR[c]) idxR = 0;
                    combIndexR[c] = idxR;
                    combSumR += outR;
                }
            }
            float revL = combSumL * 0.125f;
            if (Math.abs(revL) < 1.0E-15f) revL = 0.0f;
            float revR = stereo ? combSumR * 0.125f : 0.0f;
            if (Math.abs(revR) < 1.0E-15f) revR = 0.0f;

            for (int a = 0; a < NUM_ALLPASS; a++) {
                int idxL = allpassIndexL[a];
                float bufL = allpassBufL[a][idxL];
                allpassBufL[a][idxL] = (bufL * 0.5f) + revL;
                revL = (-revL) + bufL;
                if (++idxL >= apActualL[a]) idxL = 0;
                allpassIndexL[a] = idxL;

                if (stereo) {
                    int idxR = allpassIndexR[a];
                    float bufR = allpassBufR[a][idxR];
                    allpassBufR[a][idxR] = (bufR * 0.5f) + revR;
                    revR = (-revR) + bufR;
                    if (++idxR >= apActualR[a]) idxR = 0;
                    allpassIndexR[a] = idxR;
                }
            }
            sArr[i] = clampToShort((inL * dryGain) + (revL * wetGain));
            if (stereo) {
                sArr2[i] = clampToShort((inR * dryGain) + (revR * wetGain));
            }
        }
    }

    // --- Existing Modes 1..12 ---

    private static void applyAlienEffect(short[] sArr, short[] sArr2, int numSamples, int sampleRate) {
        applyPitchShift(sArr, sArr2, numSamples, 1.15f, sampleRate);
        double d4 = 75.39822368615503d / sampleRate;
        for (int i5 = 0; i5 < numSamples; i5++) {
            double sin = (((Math.sin(lfoPhase) * 0.5d) + 0.5d) * 0.65d) + 0.35d;
            lfoPhase += d4;
            if (lfoPhase > 6.283185307179586d) {
                lfoPhase -= 6.283185307179586d;
            }
            sArr[i5] = clampToShort((float) (sArr[i5] * sin));
            if (sArr2 != null) {
                sArr2[i5] = clampToShort((float) (sArr2[i5] * sin));
            }
        }
    }

    private static void applyChorusEffect(short[] sArr, short[] sArr2, int numSamples, int sampleRate) {
        double d4 = 5.340707511102648d / sampleRate;
        float fSampleRate = sampleRate;
        int round = Math.round(0.02f * fSampleRate);
        int round2 = Math.round(fSampleRate * 0.006f);
        for (int i5 = 0; i5 < numSamples; i5++) {
            float f5 = sArr[i5];
            float f6 = sArr2 != null ? sArr2[i5] : f5;
            int idx = chorusIndex;
            chorusBufL[idx] = f5;
            chorusBufR[idx] = f6;
            float sin = ((chorusIndex - ((float) (round + (Math.sin(lfoPhase) * round2)))) + 2048.0f) % 2048.0f;
            int i7 = (int) sin;
            int i8 = (i7 + 1) % 2048;
            float f7 = sin - i7;
            float f8 = 1.0f - f7;
            float f9 = (chorusBufL[i8] * f7) + (chorusBufL[i7] * f8);
            float f10 = (chorusBufR[i8] * f7) + (chorusBufR[i7] * f8);
            sArr[i5] = clampToShort((f9 * 0.4f) + (f5 * 0.6f));
            if (sArr2 != null) {
                sArr2[i5] = clampToShort((f10 * 0.4f) + (f6 * 0.6f));
            }
            chorusIndex = (chorusIndex + 1) % 2048;
            lfoPhase += d4;
            if (lfoPhase > 6.283185307179586d) {
                lfoPhase -= 6.283185307179586d;
            }
        }
    }

    private static void applyEchoAndReverbEffect(short[] sArr, short[] sArr2, int numSamples, int sampleRate) {
        boolean z3 = echoEnabled && echoLevel > 0;
        boolean z4 = reverbEnabled && reverbLevel > 0;
        if (!z3 && !z4) {
            return;
        }
        boolean z5 = sArr2 != null;
        float f11 = reverbLevel / 100.0f;
        float f12 = f11 * f11;
        float f13 = reverbRoomSize / 100.0f;
        float f14 = (0.55f * f13) + 0.72f;
        float pow = (((float) Math.pow(f13, 1.2d)) * 0.27f) + 0.7f;
        float f15 = (sampleRate / 44100.0f) * f14;
        int[] combTuningsActualL = new int[NUM_COMBS];
        int[] combTuningsActualR = z5 ? new int[NUM_COMBS] : null;
        for (int i = 0; i < NUM_COMBS; i++) {
            combTuningsActualL[i] = Math.min(3199, Math.max(16, Math.round(COMB_TUNINGS_L[i] * f15)));
            if (z5) {
                combTuningsActualR[i] = Math.min(3199, Math.max(16, Math.round((COMB_TUNINGS_L[i] + STEREO_SPREAD) * f15)));
            }
        }
        int[] allpassTuningsActualL = new int[NUM_ALLPASS];
        int[] allpassTuningsActualR = z5 ? new int[NUM_ALLPASS] : null;
        for (int i = 0; i < NUM_ALLPASS; i++) {
            allpassTuningsActualL[i] = Math.min(1023, Math.max(8, Math.round(ALLPASS_TUNINGS_L[i] * f15)));
            if (z5) {
                allpassTuningsActualR[i] = Math.min(1023, Math.max(8, Math.round((ALLPASS_TUNINGS_L[i] + STEREO_SPREAD) * f15)));
            }
        }
        int echoDelaySamples = Math.min(47999, (sampleRate * 190) / 1000);
        float echoFeedback = ((echoLevel / 100.0f) * 0.45f) + 0.1f;
        float echoGain = (float) Math.pow(echoLevel / 100.0f, 1.5d);
        float dryGain = Math.max(0.2f, (1.0f - (z4 ? f12 * 0.35f : 0.0f)) - (z3 ? echoGain * 0.35f : 0.0f));

        for (int i8 = 0; i8 < numSamples; i8++) {
            float inL = sArr[i8];
            float inR = z5 ? sArr2[i8] : inL;
            float revOutL = 0.0f;
            float revOutR = 0.0f;
            if (z4) {
                float combSumL = 0.0f;
                float combSumR = 0.0f;
                for (int c = 0; c < NUM_COMBS; c++) {
                    int idxL = combIndexL[c];
                    int maxL = combTuningsActualL[c];
                    float outL = combBufL[c][idxL];
                    float filteredL = (combFilterL[c] * 0.36f) + (outL * 0.64f);
                    combFilterL[c] = filteredL;
                    combBufL[c][idxL] = (filteredL * pow) + inL;
                    int nextL = idxL + 1;
                    if (nextL >= maxL) nextL = 0;
                    combIndexL[c] = nextL;
                    combSumL += outL;

                    if (z5) {
                        int idxR = combIndexR[c];
                        int maxR = combTuningsActualR[c];
                        float outR = combBufR[c][idxR];
                        float filteredR = (combFilterR[c] * 0.36f) + (outR * 0.64f);
                        combFilterR[c] = filteredR;
                        combBufR[c][idxR] = (filteredR * pow) + inR;
                        int nextR = idxR + 1;
                        if (nextR >= maxR) nextR = 0;
                        combIndexR[c] = nextR;
                        combSumR += outR;
                    }
                }
                float f27 = combSumL * 0.125f;
                if (Math.abs(f27) < 1.0E-15f) f27 = 0.0f;
                if (z5) {
                    combSumR *= 0.125f;
                    if (Math.abs(combSumR) < 1.0E-15f) combSumR = 0.0f;
                }
                revOutL = f27;
                revOutR = combSumR;

                for (int a = 0; a < NUM_ALLPASS; a++) {
                    int idxL = allpassIndexL[a];
                    int maxL = allpassTuningsActualL[a];
                    float bufValL = allpassBufL[a][idxL];
                    allpassBufL[a][idxL] = (bufValL * 0.5f) + revOutL;
                    float outL = (-revOutL) + bufValL;
                    int nextL = idxL + 1;
                    if (nextL >= maxL) nextL = 0;
                    allpassIndexL[a] = nextL;
                    revOutL = outL;

                    if (z5) {
                        int idxR = allpassIndexR[a];
                        int maxR = allpassTuningsActualR[a];
                        float bufValR = allpassBufR[a][idxR];
                        allpassBufR[a][idxR] = (bufValR * 0.5f) + revOutR;
                        float outR = (-revOutR) + bufValR;
                        int nextR = idxR + 1;
                        if (nextR >= maxR) nextR = 0;
                        allpassIndexR[a] = nextR;
                        revOutR = outR;
                    }
                }
            }

            float echoOutL = 0.0f;
            float echoOutR = 0.0f;
            if (z3) {
                int readIdx = echoIndex - echoDelaySamples;
                if (readIdx < 0) readIdx += MAX_ECHO_SAMPLES;
                echoFilterL = (echoBufL[readIdx] * 0.65f) + (echoFilterL * 0.35f);
                echoOutL = echoFilterL;
                echoBufL[echoIndex] = (echoOutL * echoFeedback) + inL;

                if (z5) {
                    echoFilterR = (echoBufR[readIdx] * 0.65f) + (echoFilterR * 0.35f);
                    echoOutR = echoFilterR;
                    echoBufR[echoIndex] = (echoOutR * echoFeedback) + inR;
                }
                echoIndex++;
                if (echoIndex >= MAX_ECHO_SAMPLES) echoIndex = 0;
            }

            sArr[i8] = clampToShort((inL * dryGain) + (z4 ? revOutL * f12 : 0.0f) + (z3 ? echoOutL * echoGain : 0.0f));
            if (z5) {
                sArr2[i8] = clampToShort((inR * dryGain) + (z4 ? revOutR * f12 : 0.0f) + (z3 ? echoOutR * echoGain : 0.0f));
            }
        }
    }

    private static void applyGhostEffect(short[] sArr, short[] sArr2, int numSamples, int sampleRate) {
        float sin = (((float) Math.sin(lfoPhase)) * 0.1f) + 0.95f;
        lfoPhase += 7.5398223686155035d / sampleRate;
        if (lfoPhase > 6.283185307179586d) {
            lfoPhase -= 6.283185307179586d;
        }
        applyPitchShift(sArr, sArr2, numSamples, sin, sampleRate);
        for (int i5 = 0; i5 < numSamples; i5++) {
            float f4 = sArr[i5];
            float f5 = sArr2 != null ? sArr2[i5] : f4;
            int idxL = allpassIndexL[0];
            float f6 = allpassBufL[0][idxL];
            allpassBufL[0][idxL] = (f6 * 0.6f) + f4;
            allpassIndexL[0] = (idxL + 1) % 400;
            sArr[i5] = clampToShort((((-f4) + f6) * 0.45f) + (f4 * 0.55f));
            if (sArr2 != null) {
                int idxR = allpassIndexR[0];
                float f7 = allpassBufR[0][idxR];
                allpassBufR[0][idxR] = (0.6f * f7) + f5;
                allpassIndexR[0] = (idxR + 1) % 423;
                sArr2[i5] = clampToShort((((-f5) + f7) * 0.45f) + (f5 * 0.55f));
            }
        }
    }

    private static void applyMegaphoneEffect(short[] sArr, short[] sArr2, int numSamples, int sampleRate) {
        for (int i5 = 0; i5 < numSamples; i5++) {
            double inL = sArr[i5] / 32768.0d;
            double outL = ((filterY2_L + inL) - filterX2_L) * 0.55d;
            filterY2_L = outL;
            filterX2_L = inL;
            sArr[i5] = clampToShort((float) (softSaturate(outL * 1.35d) * 0.88d * 32767.0d));
            if (sArr2 != null) {
                double inR = sArr2[i5] / 32768.0d;
                double outR = ((filterY2_R + inR) - filterX2_R) * 0.55d;
                filterY2_R = outR;
                filterX2_R = inR;
                sArr2[i5] = clampToShort((float) (softSaturate(outR * 1.35d) * 0.88d * 32767.0d));
            }
        }
    }

    private static void applyMonsterEffect(short[] sArr, short[] sArr2, int numSamples, int sampleRate) {
        applyPitchShift(sArr, sArr2, numSamples, 0.6f, sampleRate);
        double d4 = 28.274333882308138d / sampleRate;
        for (int i5 = 0; i5 < numSamples; i5++) {
            double sin = (Math.sin(monsterPhase) * 0.18d) + 0.82d;
            monsterPhase += d4;
            if (monsterPhase > 6.283185307179586d) {
                monsterPhase -= 6.283185307179586d;
            }
            sArr[i5] = clampToShort((float) (sArr[i5] * sin));
            if (sArr2 != null) {
                sArr2[i5] = clampToShort((float) (sArr2[i5] * sin));
            }
        }
    }

    private static void applyRadioEffect(short[] sArr, short[] sArr2, int numSamples, int sampleRate) {
        for (int i5 = 0; i5 < numSamples; i5++) {
            double inL = sArr[i5] / 32768.0d;
            double outL = ((filterY1_L + inL) - filterX1_L) * 0.7d;
            filterY1_L = outL;
            filterX1_L = inL;
            sArr[i5] = clampToShort((float) (softSaturate(outL * 1.25d) * 0.9d * 32767.0d));
            if (sArr2 != null) {
                double inR = sArr2[i5] / 32768.0d;
                double outR = ((filterY1_R + inR) - filterX1_R) * 0.7d;
                filterY1_R = outR;
                filterX1_R = inR;
                sArr2[i5] = clampToShort((float) (softSaturate(outR * 1.25d) * 0.9d * 32767.0d));
            }
        }
    }

    private static void applyRobotEffect(short[] sArr, short[] sArr2, int numSamples, int sampleRate) {
        double d4 = 364.424747816416d / sampleRate;
        for (int i5 = 0; i5 < numSamples; i5++) {
            double sin = Math.sin(robotPhase);
            robotPhase += d4;
            if (robotPhase > 6.283185307179586d) {
                robotPhase -= 6.283185307179586d;
            }
            double factor = (sin * 0.7d) + 0.3d;
            sArr[i5] = clampToShort((float) (sArr[i5] * factor));
            if (sArr2 != null) {
                sArr2[i5] = clampToShort((float) (sArr2[i5] * factor));
            }
        }
    }

    private static void applyTelephoneEffect(short[] sArr, short[] sArr2, int numSamples, int sampleRate) {
        for (int i5 = 0; i5 < numSamples; i5++) {
            double inL = sArr[i5] / 32768.0d;
            double outL = ((filterY1_L + inL) - filterX1_L) * 0.8d;
            filterY1_L = outL;
            filterX1_L = inL;
            sArr[i5] = clampToShort((float) (softSaturate(outL * 1.3d) * 0.85d * 32767.0d));
            if (sArr2 != null) {
                double inR = sArr2[i5] / 32768.0d;
                double outR = ((filterY1_R + inR) - filterX1_R) * 0.8d;
                filterY1_R = outR;
                filterX1_R = inR;
                sArr2[i5] = clampToShort((float) (softSaturate(outR * 1.3d) * 0.85d * 32767.0d));
            }
        }
    }

    private static void applyUnderwaterEffect(short[] sArr, short[] sArr2, int numSamples, int sampleRate) {
        double d4 = sampleRate;
        double d5 = 1.0d / d4;
        double d6 = d5 / (2.448537586029159E-4d + d5);
        double d7 = 5.654866776461628d / d4;
        for (int i5 = 0; i5 < numSamples; i5++) {
            filterY1_L = ((sArr[i5] - filterY1_L) * d6) + filterY1_L;
            double sin = (Math.sin(lfoPhase) * 0.15d) + 0.85d;
            sArr[i5] = clampToShort((float) (filterY1_L * sin));
            if (sArr2 != null) {
                filterY1_R = ((sArr2[i5] - filterY1_R) * d6) + filterY1_R;
                sArr2[i5] = clampToShort((float) (filterY1_R * sin));
            }
            lfoPhase += d7;
            if (lfoPhase > 6.283185307179586d) {
                lfoPhase -= 6.283185307179586d;
            }
        }
    }

    public static void process(byte[] bArr, int offset, int length, int sampleRate, int channels) {
        Mode mode = currentMode;
        if (mode == Mode.OFF || bArr == null || length <= 0 || channels <= 0 || sampleRate <= 0) {
            return;
        }
        synchronized (VoiceChanger.class) {
            Mode activeMode = currentMode;
            if (activeMode == Mode.OFF) {
                return;
            }
            int numSamples = (length / 2) / channels;
            if (numSamples <= 0) {
                return;
            }
            if (numSamples > scratchSamplesL.length) {
                int newLen = Math.max(numSamples, scratchSamplesL.length * 2);
                scratchSamplesL = new short[newLen];
                scratchSamplesR = new short[newLen];
            }
            short[] samplesL = scratchSamplesL;
            short[] samplesR = channels > 1 ? scratchSamplesR : null;

            for (int i = 0; i < numSamples; i++) {
                int byteIdx = offset + (i * channels * 2);
                samplesL[i] = (short) ((bArr[byteIdx] & 255) | ((bArr[byteIdx + 1] & 255) << 8));
                if (channels > 1) {
                    samplesR[i] = (short) ((bArr[byteIdx + 2] & 255) | ((bArr[byteIdx + 3] & 255) << 8));
                }
            }

            switch (activeMode) {
                case ECHO:
                    applyEchoAndReverbEffect(samplesL, samplesR, numSamples, sampleRate);
                    break;
                case DEEP:
                    applyPitchShift(samplesL, samplesR, numSamples, 0.74f, sampleRate);
                    break;
                case CHIPMUNK:
                    applyPitchShift(samplesL, samplesR, numSamples, 1.44f, sampleRate);
                    break;
                case ROBOT:
                    applyRobotEffect(samplesL, samplesR, numSamples, sampleRate);
                    break;
                case RADIO:
                    applyRadioEffect(samplesL, samplesR, numSamples, sampleRate);
                    break;
                case MEGAPHONE:
                    applyMegaphoneEffect(samplesL, samplesR, numSamples, sampleRate);
                    break;
                case MONSTER:
                    applyMonsterEffect(samplesL, samplesR, numSamples, sampleRate);
                    break;
                case ALIEN:
                    applyAlienEffect(samplesL, samplesR, numSamples, sampleRate);
                    break;
                case UNDERWATER:
                    applyUnderwaterEffect(samplesL, samplesR, numSamples, sampleRate);
                    break;
                case CHORUS:
                    applyChorusEffect(samplesL, samplesR, numSamples, sampleRate);
                    break;
                case TELEPHONE:
                    applyTelephoneEffect(samplesL, samplesR, numSamples, sampleRate);
                    break;
                case GHOST:
                    applyGhostEffect(samplesL, samplesR, numSamples, sampleRate);
                    break;
                case AUTOTUNE:
                    applyAutotuneEffect(samplesL, samplesR, numSamples, sampleRate, false);
                    break;
                case AUTOTUNE_HARD:
                    applyAutotuneEffect(samplesL, samplesR, numSamples, sampleRate, true);
                    break;
                case STUDIO:
                    applyStudioEffect(samplesL, samplesR, numSamples, sampleRate);
                    break;
                case CONCERT_HALL:
                    applyConcertHallEffect(samplesL, samplesR, numSamples, sampleRate);
                    break;
                case DEMON:
                    applyDemonEffect(samplesL, samplesR, numSamples, sampleRate);
                    break;
                case HELIUM:
                    applyHeliumEffect(samplesL, samplesR, numSamples, sampleRate);
                    break;
                case FEMALE:
                    applyFemaleEffect(samplesL, samplesR, numSamples, sampleRate);
                    break;
                case MALE:
                    applyMaleEffect(samplesL, samplesR, numSamples, sampleRate);
                    break;
                case WALKIE_TALKIE:
                    applyWalkieTalkieEffect(samplesL, samplesR, numSamples, sampleRate);
                    break;
                case WHISPER:
                    applyWhisperEffect(samplesL, samplesR, numSamples, sampleRate);
                    break;
                case BROADCAST_DJ:
                    applyBroadcastDjEffect(samplesL, samplesR, numSamples, sampleRate);
                    break;
                case OFF:
                default:
                    break;
            }

            for (int i = 0; i < numSamples; i++) {
                int byteIdx = offset + (i * channels * 2);
                short sL = samplesL[i];
                bArr[byteIdx] = (byte) (sL & 255);
                bArr[byteIdx + 1] = (byte) ((sL >> 8) & 255);
                if (channels > 1 && samplesR != null) {
                    short sR = samplesR[i];
                    bArr[byteIdx + 2] = (byte) (sR & 255);
                    bArr[byteIdx + 3] = (byte) ((sR >> 8) & 255);
                }
            }
        }
    }

    public static synchronized void resetState() {
        synchronized (VoiceChanger.class) {
            Arrays.fill(pitchBufL, (short) 0);
            Arrays.fill(pitchBufR, (short) 0);
            pitchWritePos = 0;
            pitchPhase = 0.0f;
            Arrays.fill(echoBufL, 0.0f);
            Arrays.fill(echoBufR, 0.0f);
            echoIndex = 0;
            echoFilterL = 0.0f;
            echoFilterR = 0.0f;
            for (int i = 0; i < NUM_COMBS; i++) {
                Arrays.fill(combBufL[i], 0.0f);
                Arrays.fill(combBufR[i], 0.0f);
                combIndexL[i] = 0;
                combIndexR[i] = 0;
                combFilterL[i] = 0.0f;
                combFilterR[i] = 0.0f;
            }
            for (int i = 0; i < NUM_ALLPASS; i++) {
                Arrays.fill(allpassBufL[i], 0.0f);
                Arrays.fill(allpassBufR[i], 0.0f);
                allpassIndexL[i] = 0;
                allpassIndexR[i] = 0;
            }
            Arrays.fill(chorusBufL, 0.0f);
            Arrays.fill(chorusBufR, 0.0f);
            chorusIndex = 0;
            robotPhase = 0.0d;
            lfoPhase = 0.0d;
            monsterPhase = 0.0d;
            filterX1_L = 0.0d;
            filterX2_L = 0.0d;
            filterY1_L = 0.0d;
            filterY2_L = 0.0d;
            filterX1_R = 0.0d;
            filterX2_R = 0.0d;
            filterY1_R = 0.0d;
            filterY2_R = 0.0d;
            lowShelfL = 0.0d;
            lowShelfR = 0.0d;
            highShelfL = 0.0d;
            highShelfR = 0.0d;
            bandHpX_L = 0.0d;
            bandHpY_L = 0.0d;
            bandHpX_R = 0.0d;
            bandHpY_R = 0.0d;
            bandLpY_L = 0.0d;
            bandLpY_R = 0.0d;
            compEnv = 0.0d;
            gateGain = 1.0d;
            whisperEnvL = 0.0d;
            whisperEnvR = 0.0d;
            whisperNoiseLpL = 0.0d;
            whisperNoiseLpR = 0.0d;
            Arrays.fill(autotuneRing, 0.0f);
            autotuneWritePos = 0;
            autotuneFilled = 0;
            autotuneRatio = 1.0f;
        }
    }
}
