package com.liskovsoft.smartyoutubetv2.tv.ui.common.keyhandler;

import android.app.Activity;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import com.liskovsoft.sharedutils.helpers.PermissionHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Voice search for the RayNeo X3 Pro that bypasses the (unusable) on-device
 * recognizer: records the mic with AudioRecord, transcribes via the free
 * Gemini 2.5 Flash API, and runs the result through SmartTube's search.
 *
 * The Gemini API key is read once from a file the user pushes over adb
 * (see gemini_key.txt) and then cached in prefs.
 */
public final class GeminiVoiceSearch {
    private static final String TAG = "GeminiVoiceSearch";
    private static final String PREFS = "smarttubular_voice";
    private static final String PREF_KEY = "gemini_api_key";
    private static final String PREF_MODEL = "gemini_model";
    // 2.5-flash recovers from "high demand" 503s fastest. If it's overloaded we
    // fall back through the others below, since a 503 is server-side capacity
    // (not your quota) and a different model is usually free right now.
    private static final String DEFAULT_MODEL = "gemini-2.5-flash";
    private static final String[] FALLBACK_MODELS = {
        "gemini-2.5-flash", "gemini-2.0-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash-lite"
    };
    private static volatile String sLastError;

    private static final int SAMPLE_RATE = 16000;
    private static final int MAX_MS = 8000;        // hard cap on a single utterance
    private static final int SILENCE_MS = 1200;    // stop this long after speech ends
    private static final int NO_SPEECH_MS = 4000;  // give up if nothing is said
    private static final double SILENCE_RMS = 600.0; // amplitude threshold (tune on device)

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static volatile boolean sBusy = false;

    private GeminiVoiceSearch() { }

    /** Open search, listen, transcribe with Gemini, and show results. */
    public static void start(final Activity activity) {
        start(activity, false);
    }

    /** Listen, transcribe with Gemini, and show results on the Browse/Home screen when possible. */
    public static void startOnHome(final Activity activity) {
        start(activity, true);
    }

    private static void start(final Activity activity, final boolean preferHomeResults) {
        if (activity == null) {
            return;
        }
        if (sBusy) {
            Log.d(TAG, "already listening");
            return;
        }
        if (!PermissionHelpers.hasMicPermissions(activity)) {
            PermissionHelpers.verifyMicPermissions(activity);
            toast(activity, "Grant microphone permission, then tap search again");
            return;
        }
        final String key = loadKey(activity);
        if (key == null || key.isEmpty()) {
            toast(activity, "No Gemini key set — see gemini_key.txt (adb broadcast)");
            return;
        }

        // NOTE: don't call startSearch(null) here — on an existing results screen
        // it RESTORES the previous query (showing stale results). We let the new
        // transcription drive a fresh search via onSearch(); if the search screen
        // isn't open yet, onSearch() opens it (its getView()==null fallback).

        sBusy = true;
        beep(activity);
        toast(activity, "Listening… speak now");
        EXEC.execute(() -> {
            try {
                byte[] wav = record();
                if (wav == null || wav.length < 4000) {
                    finish(activity, preferHomeResults, null, "Didn't catch that — try again");
                    return;
                }
                UI.post(() -> toast(activity, "Searching…"));
                sLastError = null;
                // Try the chosen model, then fall back to others — a 503 means
                // that model is overloaded right now, so another usually works
                // immediately. Short backoff between attempts.
                String[] models = modelFallbackList(activity);
                String text = null;
                for (int i = 0; i < models.length && text == null; i++) {
                    if (i > 0) {
                        try { Thread.sleep(500L * i); } catch (InterruptedException ignored) { }
                    }
                    text = transcribe(key, models[i], wav);
                }
                finish(activity, preferHomeResults, text, text == null
                        ? (sLastError != null ? sLastError : "Couldn't transcribe — try again") : null);
            } catch (Throwable e) {
                Log.e(TAG, "voice search failed: " + e.getMessage(), e);
                finish(activity, preferHomeResults, null, "Voice error: " + e.getMessage());
            }
        });
    }

    private static void finish(final Activity activity, final boolean preferHomeResults,
                               final String text, final String errMsg) {
        sBusy = false;
        UI.post(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            if (text != null && !text.trim().isEmpty()) {
                String q = text.trim();
                Log.d(TAG, "result query: " + q);
                try {
                    if (!preferHomeResults || !BrowsePresenter.instance(activity).showSearchResultsOnHome(q)) {
                        SearchPresenter.instance(activity).onSearch(q);
                    }
                } catch (Throwable e) {
                    Log.e(TAG, "onSearch failed: " + e.getMessage());
                }
            } else if (errMsg != null) {
                toast(activity, errMsg);
            }
        });
    }

    // ---------- audio capture ----------

    private static byte[] record() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            minBuf = SAMPLE_RATE; // samples
        }
        AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2 * 2);
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            try { rec.release(); } catch (Throwable ignored) { }
            Log.e(TAG, "AudioRecord not initialized");
            return null;
        }
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        short[] buf = new short[minBuf];
        boolean heardVoice = false;
        try {
            rec.startRecording();
            long start = System.currentTimeMillis();
            long lastVoice = start;
            while (System.currentTimeMillis() - start < MAX_MS) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) {
                    continue;
                }
                double sum = 0;
                for (int i = 0; i < n; i++) {
                    sum += (double) buf[i] * buf[i];
                    pcm.write(buf[i] & 0xff);
                    pcm.write((buf[i] >> 8) & 0xff);
                }
                double rms = Math.sqrt(sum / n);
                long now = System.currentTimeMillis();
                if (rms > SILENCE_RMS) {
                    heardVoice = true;
                    lastVoice = now;
                }
                if (heardVoice && now - lastVoice > SILENCE_MS) {
                    break; // trailing silence -> done
                }
                if (!heardVoice && now - start > NO_SPEECH_MS) {
                    break; // nothing said
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "record failed: " + e.getMessage());
        } finally {
            try { rec.stop(); } catch (Throwable ignored) { }
            try { rec.release(); } catch (Throwable ignored) { }
        }
        if (!heardVoice) {
            return null;
        }
        return wavFromPcm(pcm.toByteArray());
    }

    private static byte[] wavFromPcm(byte[] pcm) {
        int dataLen = pcm.length;
        int byteRate = SAMPLE_RATE * 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(new byte[]{'R', 'I', 'F', 'F'});
            writeInt(out, dataLen + 36);
            out.write(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
            writeInt(out, 16);
            writeShort(out, (short) 1);            // PCM
            writeShort(out, (short) 1);            // mono
            writeInt(out, SAMPLE_RATE);
            writeInt(out, byteRate);
            writeShort(out, (short) 2);            // block align
            writeShort(out, (short) 16);           // bits per sample
            out.write(new byte[]{'d', 'a', 't', 'a'});
            writeInt(out, dataLen);
            out.write(pcm);
        } catch (Throwable ignored) {
        }
        return out.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >> 8) & 0xff);
        o.write((v >> 16) & 0xff);
        o.write((v >> 24) & 0xff);
    }

    private static void writeShort(ByteArrayOutputStream o, short v) {
        o.write(v & 0xff);
        o.write((v >> 8) & 0xff);
    }

    // ---------- Gemini ----------

    private static String transcribe(String apiKey, String model, byte[] wav) throws Exception {
        String b64 = Base64.encodeToString(wav, Base64.NO_WRAP);
        String body = "{\"contents\":[{\"parts\":["
                + "{\"text\":\"Transcribe this spoken YouTube search query. Reply with ONLY the words spoken — no quotes, no punctuation, no extra commentary.\"},"
                + "{\"inline_data\":{\"mime_type\":\"audio/wav\",\"data\":\"" + b64 + "\"}}"
                + "]}]}";
        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent?key=" + apiKey);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }
            int code = conn.getResponseCode();
            String resp = readAll(code >= 200 && code < 300
                    ? conn.getInputStream() : conn.getErrorStream());
            Log.d(TAG, "gemini http " + code + " model=" + model);
            if (code < 200 || code >= 300) {
                Log.e(TAG, "gemini error body: " + resp);
                String msg = "Gemini " + code;
                String status = null;
                try {
                    JSONObject error = new JSONObject(resp).getJSONObject("error");
                    status = error.optString("status", null);
                    msg = "Gemini " + code
                            + (status != null && !status.isEmpty() ? " " + status : "")
                            + ": " + error.getString("message");
                } catch (Throwable ignored) { }
                if (code == 429) {
                    msg = "Gemini 429 rate limit/overload. Wait a minute or switch models.";
                } else if (code == 403) {
                    msg = "Gemini 403 forbidden. Check API key, API enablement, billing/quota, or model access.";
                } else if (code == 503) {
                    msg = "Gemini 503 overloaded. Wait a moment and try again.";
                }
                sLastError = msg;
                return null;
            }
            JSONObject root = new JSONObject(resp);
            return root.getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                    .getString("text").trim();
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) {
            return "";
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = is.read(b)) > 0) {
            o.write(b, 0, n);
        }
        return new String(o.toByteArray(), "UTF-8");
    }

    // ---------- API key ----------

    private static String getModel(Context ctx) {
        try {
            String m = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_MODEL, null);
            return (m != null && !m.isEmpty()) ? m : DEFAULT_MODEL;
        } catch (Throwable e) {
            return DEFAULT_MODEL;
        }
    }

    /** The chosen model first, then the fallback models (deduped). */
    private static String[] modelFallbackList(Context ctx) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        set.add(getModel(ctx));
        for (String m : FALLBACK_MODELS) {
            set.add(m);
        }
        return set.toArray(new String[0]);
    }

    /** Override the Gemini model (used by GeminiKeyReceiver, set over adb). */
    public static void saveModel(Context ctx, String model) {
        if (ctx == null || model == null || model.trim().isEmpty()) {
            return;
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_MODEL, model.trim()).apply();
        Log.d(TAG, "Gemini model set to " + model.trim());
    }

    /** Store the API key (used by GeminiKeyReceiver, set over adb). */
    public static void saveKey(Context ctx, String key) {
        if (ctx == null || key == null) {
            return;
        }
        String k = key.trim();
        if (k.isEmpty()) {
            return;
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_KEY, k).apply();
        Log.d(TAG, "Gemini key saved (len=" + k.length() + ")");
    }

    /** The key is set over adb (GeminiKeyReceiver) into prefs — see gemini_key.txt. */
    private static String loadKey(Context ctx) {
        String stored = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_KEY, null);
        return (stored != null && !stored.isEmpty()) ? stored : null;
    }

    private static void toast(Activity a, String msg) {
        try {
            Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    /** Short "listening" beep, matching the original recognizer cue. */
    private static void beep(Activity a) {
        try {
            final android.media.ToneGenerator tone =
                    new android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 80);
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 160);
            UI.postDelayed(() -> {
                try { tone.release(); } catch (Throwable ignored) { }
            }, 250);
        } catch (Throwable ignored) {
        }
    }
}
