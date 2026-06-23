## SmartTubular 0.3 — Gemini Search (EXPERIMENTAL)

> ⚠️ **Experimental branch (`0.3-geminisearch`).** Adds cloud voice search and
> reworks player/search navigation. Needs a free Gemini API key and is less
> tested than the 0.2 release. Want something settled? Use 0.2.

Built on **[SmartTube](https://github.com/yuliskov/SmartTube)** by yuliskov and the
**[SmartTube – RayNeo X3 Pro Edition](https://github.com/oliverfederico/SmartTube-RayNeo-X3-Pro)**
by **Oliver Federico** — thank you. MIT licensed.

### What's new
- **Voice search via Gemini.** Tap the magnifying glass / mic to record, transcribe
  with Google's free Gemini API, and run it as a YouTube search (the RayNeo on-device
  recognizer isn't usable by third-party apps). Works on the home and search screens.
- **Configurable model + retry.** Defaults to `gemini-2.5-flash-lite` (highest free
  rate limit); auto-retries and reports rate-limit errors clearly.
- **Gallery D-pad mode.** Scrolling below the video into related videos switches to
  home-screen-style D-pad scrolling with the cursor hidden.

### Setup (one time)
1. Get a free key: https://aistudio.google.com/app/apikey
2. Send it to the app over adb (no on-device typing):
   ```
   adb shell am broadcast -a org.smarttube.beta.cc.SET_GEMINI_KEY \
     -n org.smarttube.beta.cc/com.liskovsoft.smartyoutubetv2.tv.ui.common.keyhandler.GeminiKeyReceiver \
     --es key "YOUR_GEMINI_API_KEY"
   ```
3. Open SmartTubular and tap the magnifying glass to search by voice.

Full directions: [`gemini_key.txt.example`](./gemini_key.txt.example). A one-time mic
permission prompt appears on first use.

**Install:** `SmartTube_beta_31.45_arm64-v8a.apk` below. Installs alongside the original SmartTube.
