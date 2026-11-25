package org.teamtalkextro.backend;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import androidx.preference.PreferenceManager;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * "Talking DJ" — synthesizes arbitrary text with Android TextToSpeech into a temporary
 * WAV file (API 21+ synthesizeToFile) and broadcasts it straight into the outgoing
 * TeamTalk voice stream + local monitor via TeamTalkService / SoundboardEngine.
 */
public final class TtsAirAnnouncer {

    private static final String TAG = "TtsAirAnnouncer";
    private static final String WAV_FILE_NAME = "extro_tts_air.wav";

    public interface Callback {
        void onSynthesizing();
        void onBroadcastStarted(long durationMs);
        void onError(String message);
    }

    private static final class PendingRequest {
        final String text;
        final float rate;
        final float pitch;
        final Callback callback;

        PendingRequest(String text, float rate, float pitch, Callback callback) {
            this.text = text;
            this.rate = rate;
            this.pitch = pitch;
            this.callback = callback;
        }
    }

    private final Context appContext;
    private final TeamTalkService service;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger utteranceCounter = new AtomicInteger(0);

    private TextToSpeech tts;
    private volatile boolean ready = false;
    private volatile boolean initializing = false;
    private PendingRequest pendingRequest;
    private Callback activeCallback;
    private String activeUtteranceId;
    private File activeWavFile;

    public TtsAirAnnouncer(Context context, TeamTalkService service) {
        this.appContext = context.getApplicationContext();
        this.service = service;
    }

    public static float clampFactor(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return 1.0f;
        return Math.max(0.6f, Math.min(1.6f, value));
    }

    public synchronized void speakToChannel(String text, float speechRate, float pitch, Callback callback) {
        if (text == null || text.trim().isEmpty()) {
            notifyError(callback, "Empty text");
            return;
        }
        String trimmed = text.trim();
        if (trimmed.length() > 600) {
            trimmed = trimmed.substring(0, 600);
        }
        float safeRate = clampFactor(speechRate);
        float safePitch = clampFactor(pitch);

        if (this.ready && this.tts != null) {
            startSynthesisLocked(trimmed, safeRate, safePitch, callback);
        } else {
            this.pendingRequest = new PendingRequest(trimmed, safeRate, safePitch, callback);
            notifySynthesizing(callback);
            if (!this.initializing) {
                initTtsLocked();
            }
        }
    }

    private void initTtsLocked() {
        this.initializing = true;
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this.appContext);
            String engine = prefs.getString("pref_tts_engine", "");
            TextToSpeech.OnInitListener initListener = this::onTtsInit;
            if (engine != null && !engine.trim().isEmpty()) {
                this.tts = new TextToSpeech(this.appContext, initListener, engine.trim());
            } else {
                this.tts = new TextToSpeech(this.appContext, initListener);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to init TextToSpeech", t);
            this.initializing = false;
            this.ready = false;
            PendingRequest req = this.pendingRequest;
            this.pendingRequest = null;
            if (req != null) {
                notifyError(req.callback, "TTS engine unavailable");
            }
        }
    }

    private synchronized void onTtsInit(int status) {
        this.initializing = false;
        if (status != TextToSpeech.SUCCESS || this.tts == null) {
            this.ready = false;
            PendingRequest req = this.pendingRequest;
            this.pendingRequest = null;
            if (req != null) {
                notifyError(req.callback, "TTS initialization failed (" + status + ")");
            }
            return;
        }

        configureLocale(this.tts);
        this.tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                // Synthesis started
            }

            @Override
            public void onDone(String utteranceId) {
                handleSynthesisDone(utteranceId);
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onError(String utteranceId) {
                handleSynthesisError(utteranceId, "TTS synthesis error");
            }

            @Override
            public void onError(String utteranceId, int errorCode) {
                handleSynthesisError(utteranceId, "TTS synthesis error (" + errorCode + ")");
            }
        });

        this.ready = true;
        PendingRequest req = this.pendingRequest;
        this.pendingRequest = null;
        if (req != null) {
            startSynthesisLocked(req.text, req.rate, req.pitch, req.callback);
        }
    }

    private void configureLocale(TextToSpeech engine) {
        try {
            Locale appLocale;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                appLocale = this.appContext.getResources().getConfiguration().getLocales().get(0);
            } else {
                appLocale = this.appContext.getResources().getConfiguration().locale;
            }
            int res = engine.setLanguage(appLocale != null ? appLocale : Locale.getDefault());
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.getDefault());
            }
        } catch (Throwable ignored) {
        }
    }

    private void startSynthesisLocked(String text, float rate, float pitch, Callback callback) {
        if (this.tts == null) {
            notifyError(callback, "TTS not ready");
            return;
        }
        File outFile = new File(this.appContext.getCacheDir(), WAV_FILE_NAME);
        if (outFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            outFile.delete();
        }
        String uttId = "extro_tts_air_" + this.utteranceCounter.incrementAndGet();
        this.activeUtteranceId = uttId;
        this.activeWavFile = outFile;
        this.activeCallback = callback;

        notifySynthesizing(callback);

        try {
            configureLocale(this.tts);
            this.tts.setSpeechRate(rate);
            this.tts.setPitch(pitch);
            Bundle params = new Bundle();
            int result = this.tts.synthesizeToFile(text, params, outFile, uttId);
            if (result != TextToSpeech.SUCCESS) {
                this.activeUtteranceId = null;
                this.activeCallback = null;
                notifyError(callback, "TTS synthesizeToFile failed");
            }
        } catch (Throwable t) {
            Log.e(TAG, "TTS synthesizeToFile exception", t);
            this.activeUtteranceId = null;
            this.activeCallback = null;
            notifyError(callback, t.getMessage() != null ? t.getMessage() : "TTS error");
        }
    }

    private void handleSynthesisDone(String utteranceId) {
        final File wavFile;
        final Callback cb;
        synchronized (this) {
            if (utteranceId == null || !utteranceId.equals(this.activeUtteranceId)) {
                return;
            }
            wavFile = this.activeWavFile;
            cb = this.activeCallback;
            this.activeUtteranceId = null;
            this.activeCallback = null;
        }
        if (wavFile == null || !wavFile.exists() || wavFile.length() <= 44) {
            notifyError(cb, "Generated audio file is empty");
            return;
        }
        this.ioExecutor.execute(() -> {
            long durMs = this.service.playSoundboardAudioFile(wavFile);
            if (durMs > 0) {
                notifyStarted(cb, durMs);
            } else {
                notifyError(cb, "Failed to stream synthesized TTS audio");
            }
        });
    }

    private void handleSynthesisError(String utteranceId, String msg) {
        final Callback cb;
        synchronized (this) {
            if (utteranceId != null && !utteranceId.equals(this.activeUtteranceId)) {
                return;
            }
            cb = this.activeCallback;
            this.activeUtteranceId = null;
            this.activeCallback = null;
        }
        notifyError(cb, msg);
    }

    public synchronized void shutdown() {
        this.pendingRequest = null;
        this.activeCallback = null;
        this.activeUtteranceId = null;
        this.ready = false;
        this.initializing = false;
        if (this.tts != null) {
            try { this.tts.stop(); } catch (Throwable ignored) {}
            try { this.tts.shutdown(); } catch (Throwable ignored) {}
            this.tts = null;
        }
        try {
            File outFile = new File(this.appContext.getCacheDir(), WAV_FILE_NAME);
            if (outFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outFile.delete();
            }
        } catch (Throwable ignored) {
        }
        this.ioExecutor.shutdownNow();
    }

    private void notifySynthesizing(Callback cb) {
        if (cb != null) {
            this.mainHandler.post(cb::onSynthesizing);
        }
    }

    private void notifyStarted(Callback cb, long durationMs) {
        if (cb != null) {
            this.mainHandler.post(() -> cb.onBroadcastStarted(durationMs));
        }
    }

    private void notifyError(Callback cb, String msg) {
        if (cb != null) {
            this.mainHandler.post(() -> cb.onError(msg));
        }
    }
}
