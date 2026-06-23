## SmartTubular 0.2

A RayNeo X3 Pro fork of SmartTube. Built on **[SmartTube](https://github.com/yuliskov/SmartTube)** by yuliskov and the **[SmartTube – RayNeo X3 Pro Edition](https://github.com/oliverfederico/SmartTube-RayNeo-X3-Pro)** by **Oliver Federico**, whose X3 Pro port this branches from — thank you. MIT licensed; see CREDITS.md.

### What's new in 0.2
- Play-bar buttons (Play, CC, Settings, etc.) each activate correctly instead of moving the timeline.
- Tap-to-seek on the progress bar; thumbnails open and autoplay; reliable double-tap-to-exit.
- Edge-scroll: push the pointer against a screen edge to reach related videos / chapters and side panels.
- Closed captions prefer integrated captions, falling back to auto-generated.
- Home-screen search opens the search screen directly.

### Known issue
- **Voice / microphone search does not work** — the RayNeo X3 Pro doesn't expose a usable speech recognizer to third-party apps. Details and an implementation plan are in `docs/VOICE_SEARCH_HANDOFF.md`.

**Install:** download `SmartTube_beta_31.45_arm64-v8a.apk` below. Installs alongside the original SmartTube.
