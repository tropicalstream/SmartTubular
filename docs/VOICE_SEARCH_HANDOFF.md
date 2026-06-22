# SmartTubular — Voice Search Handoff (RayNeo X3 Pro)

Status: **UNRESOLVED.** On-device voice search does not work. This document
captures everything tried, the root cause, and the recommended path so a fresh
session can continue without re-deriving it.

## TL;DR

- The RayNeo X3 Pro exposes **no usable speech recognizer to third-party apps.**
  Every standard and private path was tried and each dead-ends (details below).
- The reference app **TAPLINKX3** (same hardware) does NOT use the RayNeo
  recognizer. It records raw mic audio and transcribes via the **Groq cloud
  Whisper API**. That is the proven-working approach on this device.
- **Recommended fix:** stop calling any RayNeo / Android `SpeechRecognizer`.
  Capture audio with `AudioRecord`, send it to a transcription API, put the
  returned text into the SmartTube search field. Two viable backends:
  - **Groq Whisper (cloud)** — high accuracy, needs an API key + network.
  - **Vosk (offline, on-device)** — no key/network, needs a bundled model (~50MB).

## The goal (user's words)

When the search screen opens, the mic should auto-activate and perform a voice
search. No system keyboard (it can't render across both lenses and triggers a
contacts prompt — both confirmed). Voice is the primary input for the glasses.

## What was tried, and the exact failure (from logcat)

1. **Android `SpeechRecognizer` (implicit), gotev wrapper, instant-voice-on-open.**
   RayNeo routes to `com.rayneo.live.ai/.wakeup.RayNeoRecognitionService`. It
   accepts `startListening` but **never fires `onReadyForSpeech`/results.**
   Symptom: two beeps, red mic flips grey instantly.
2. **Explicit bind to `RayNeoRecognitionService` + 4s watchdog.**
   Result: `ready timeout; RayNeo service gave no callbacks`. Confirms the public
   `RecognitionService` is non-functional for third-party callers.
3. **Launcher speech IPC** — bind `com.ffalconxr.mercury.launcher/.ipc.RemoteMultiService`,
   speech binder type `1`, `startDialog`.
   Result: `ipc speech binder unavailable`.
4. **RayNeo AI Runtime ASR IPC** (`RayNeoAiRuntimeAsrClient`).
   Result: `airuntime connected` then `airuntime result finished=false
   text=initWorkflow=false` → `initWorkflow returned false`. Connects but the
   private workflow init is rejected (undocumented protocol / permission).

Conclusion: RayNeo's ASR is locked to their own assistant stack
(`RayNeoAsrManager`, `ISpeechInterface`, `onAsrResult`) and is not reachable by
normal apps. **Do not spend more time on RayNeo IPC.**

## Recommended implementation (record + transcribe ourselves)

### Shared piece — audio capture
- Use `android.media.AudioRecord`, 16 kHz mono PCM16 (`MediaRecorder.AudioSource.MIC`).
- `RECORD_AUDIO` is already in `smarttubetv/src/main/AndroidManifest.xml`; request
  it at runtime once (`PermissionHelpers.verifyMicPermissions`).
- Start on search-screen open (or mic-orb tap); stop on a simple end condition
  (silence via RMS threshold for ~1.2s, or a max 8–10s, or second tap).
- Show the existing red "listening" mic state while recording.

### Option A — Groq Whisper (cloud) — matches TAPLINKX3
- Endpoint: `POST https://api.groq.com/openai/v1/audio/transcriptions`
- Multipart form: `file` = WAV bytes, `model` = `whisper-large-v3-turbo`,
  `response_format` = `text` (or `json`).
- Header: `Authorization: Bearer <GROQ_API_KEY>`.
- OkHttp is already available (`com.squareup.okhttp3:okhttp`, via
  `:exoplayer-extension-okhttp`; or `leanbackassistant` pulls okhttp directly).
- API key: add a settings field (reuse SmartTube's prefs/AppDialog pattern) OR a
  build-time constant for testing. TAPLINKX3 also lets the user enter the key.
- On 200, take the text → `SearchTagsFragment.loadSearchResult(text)` /
  `onQueryTextSubmit(text)` so results load + the query shows in the bar.

### Option B — Vosk (offline) — no key, no network
- Add `com.alphacephei:vosk-android` + a small model (e.g.
  `vosk-model-small-en-us-0.15`, ~40MB) under `assets/` (or download on first run).
- Feed `AudioRecord` PCM into Vosk's `Recognizer`; read partial/final JSON.
- Final text → same `loadSearchResult(text)` path.
- Pro: fully self-contained, no RayNeo, no cloud. Con: app size + slightly lower
  accuracy. Best if you don't want users managing API keys.

### Wiring the result into SmartTube search
- Search UI: `SearchTagsFragment` / `SearchTagsFragmentBase`
  (`smarttubetv/.../ui/search/tags/`). Use `onQueryTextSubmit(query)` →
  `loadSearchResult(query)` to run the search and display the typed query.
- Make sure the system IME never shows: keep instant-voice on and don't focus the
  `SearchEditText` (already handled in `RayNeoInputInterceptor.injectClick` — an
  EditText tap calls `startVoice()` instead of showing the keyboard).

## Current code state / files to know

- `smarttubetv/.../ui/search/tags/vineyard/SearchTagsFragmentBase.java` — heavily
  modified by Codex with RayNeo speech experiments + `RayNeoVoiceSearch` logs.
  **Rip out the RayNeo IPC attempts** when implementing record+transcribe.
- `smarttubetv/.../ui/search/tags/vineyard/RayNeoSpeechIpcClient.java` and
  `RayNeoAiRuntimeAsrClient.java` — Codex's dead-end IPC clients; delete or keep
  for reference only.
- `smarttubetv/.../ui/common/keyhandler/RayNeoInputInterceptor.java` — constructor
  sets `SearchData` to GOTEV + instant voice; `injectClick` launches search via
  `SearchPresenter.startSearch/startVoice` and avoids the keyboard. (Note: GOTEV
  still uses Android SpeechRecognizer under the hood, so it hits the same RayNeo
  wall — once a custom recorder exists, this setting becomes irrelevant.)
- `common/.../prefs/SearchData.java` — `SPEECH_RECOGNIZER_*`, instant-voice flag.
- `smarttubetv/src/main/AndroidManifest.xml` — has `RECORD_AUDIO`; Codex added a
  `<queries>` block for RayNeo packages (harmless, can stay).

## Test / log commands

```
cd ~/Downloads/SmartTube-RayNeo-X3-Pro
./gradlew :smarttubetv:assembleStbetaDebug && \
adb install -r smarttubetv/build/outputs/apk/stbeta/debug/SmartTube_beta_31.45_arm64-v8a.apk
adb logcat -c
adb logcat -v time RayNeoVoiceSearch:I SearchTagsFragmentBase:I '*:S'
```

## Suggested next-session prompt

"Implement voice search for SmartTubular on the RayNeo X3 Pro by recording mic
audio with AudioRecord and transcribing it via the Groq Whisper API
(`/openai/v1/audio/transcriptions`, model `whisper-large-v3-turbo`), since the
RayNeo on-device recognizer is unusable by third-party apps (see this handoff).
Add a settings field for the Groq API key, capture 16kHz mono PCM on search open
with silence-based stop, POST as WAV, and feed the returned text into
`SearchTagsFragment.loadSearchResult()`. Remove the dead RayNeo IPC clients."
