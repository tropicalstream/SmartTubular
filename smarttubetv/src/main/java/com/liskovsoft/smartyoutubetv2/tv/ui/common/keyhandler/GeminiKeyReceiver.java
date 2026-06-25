package com.liskovsoft.smartyoutubetv2.tv.ui.common.keyhandler;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

/**
 * Lets the Gemini voice-search API key be set over adb without typing it on the
 * glasses (the on-device keyboard doesn't render across both lenses):
 *
 *   adb shell am broadcast -a ${applicationId}.SET_GEMINI_KEY \
 *       -n ${applicationId}/com.liskovsoft.smartyoutubetv2.tv.ui.common.keyhandler.GeminiKeyReceiver \
 *       --es key "YOUR_GEMINI_API_KEY"
 *
 * The same receiver also accepts optional "model" and "prompt" extras to override
 * the Gemini model and the voice-search system prompt (see gemini_setup.txt).
 *
 * The values are stored in app-private prefs. This receiver only WRITES them; it
 * never reads them back out. It can be removed once the key is set if desired.
 */
public class GeminiKeyReceiver extends BroadcastReceiver {
    private static final String TAG = "GeminiVoiceSearch";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String model = intent.getStringExtra("model");
        if (model != null && !model.trim().isEmpty()) {
            GeminiVoiceSearch.saveModel(context, model);
            try {
                Toast.makeText(context, "Gemini model: " + model.trim(), Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }
        String prompt = intent.getStringExtra("prompt");
        if (prompt != null && !prompt.trim().isEmpty()) {
            GeminiVoiceSearch.savePrompt(context, prompt);
            try {
                Toast.makeText(context, "Gemini prompt updated", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }
        String key = intent.getStringExtra("key");
        if (key == null || key.trim().isEmpty()) {
            if (model == null && prompt == null) {
                Log.e(TAG, "SET_GEMINI_KEY received with no 'key', 'model', or 'prompt' extra");
            }
            return;
        }
        GeminiVoiceSearch.saveKey(context, key);
        try {
            Toast.makeText(context, "Gemini key saved", Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
