# Changelog — SmartTubular

SmartTubular is a RayNeo X3 Pro fork of SmartTube. This changelog covers the
RayNeo-specific additions on top of upstream SmartTube (base version 31.45).
See [`CREDITS.md`](./CREDITS.md) for project lineage and licensing.

## [0.4-beta] — Search reliability fix (EXPERIMENTAL)

> ⚠️ **Experimental.** Builds on 0.3-geminisearch (Gemini voice search). Still
> needs a free Gemini API key. Use the stable 0.2 release if you want something
> settled.

### Fixed
- **Intermittent "Can't load content" / empty search results.** Searches (voice
  or typed) would sometimes return nothing and show the sad-cloud screen, then
  work after a few retries with the same term. Root cause was a stale YouTube
  data layer: the fork's `MediaServiceCore` predated upstream SmartTube's
  poToken / innertube / search fixes (shipped upstream as "Empty search results
  fix"). Bumped `MediaServiceCore` to upstream master so those fixes are
  included. Search now loads reliably on the first try.

### Changed
- **Default Gemini model is now `gemini-2.5-flash`** (recovers fastest from the
  free-tier "high demand" 503s), with automatic fallback through
  `gemini-2.0-flash` → `gemini-2.5-flash-lite` → `gemini-2.0-flash-lite` and
  exponential backoff. Still overridable over adb.

### Notes
- Only the YouTube data layer changed; the RayNeo voice/navigation features from
  0.3 are unchanged.
- The data-layer bump required a one-line compatibility patch in
  `MediaServiceCore` (prefs API), carried in the tropicalstream MediaServiceCore
  fork the submodule now points at — see `RELEASE_NOTES_0.4.md`.

## [0.3-geminisearch] — Gemini voice search (EXPERIMENTAL)

> ⚠️ **Experimental branch.** This build adds cloud voice search and reworks the
> player/search navigation. It needs a (free) Gemini API key and is less tested
> than the 0.2 release. Use the stable 0.2 release if you want something settled.

### Added
- **Voice search via Gemini.** Tapping the magnifying glass / mic records the
  microphone, transcribes it with Google's free **Gemini API**, and runs the
  result as a YouTube search — bypassing the RayNeo on-device recognizer, which
  isn't usable by third-party apps. Works from the home screen and the search
  screen (mic and magnifying glass).
- **API key over adb.** No on-device typing: set the key with a single
  `am broadcast` command (see [`gemini_key.txt.example`](./gemini_key.txt.example)).
  Stored only in app-private prefs.
- **Configurable model + resilience.** Defaults to `gemini-2.5-flash-lite`
  (highest free-tier rate limit), with an automatic retry and a clearer error
  (e.g. "rate limit — try again") on the occasional "spike in usage". The model
  is overridable over adb.
- **Gallery D-pad mode.** Scrolling below the playing video into the related-
  videos gallery now switches to plain home-screen-style D-pad scrolling with the
  cursor hidden; the controls above still use the pointer.

### Notes
- A one-time microphone-permission prompt appears on first voice use.
- Voice quality/availability depends on the Gemini free tier.

## [0.2] — Player polish, search, and known voice limitation

### Added
- **Edge-scroll.** Push the pointer firmly against a screen edge to drive the
  app's built-in D-pad navigation that direction — e.g. push the bottom edge to
  reach the related videos / chapters below, or a side edge for side panels.
  After it moves, the pointer snaps onto the newly focused control.
- **Tap-to-seek on the timeline.** Click the progress bar to seek to that point.
- **Swipe to change video in dim mode.** With dim caption mode up, swipe
  right/left to skip to the next/previous video.

### Fixed
- **Play-bar control clicks.** Play, Closed Captions, Settings and the other
  control-bar buttons now each activate their own action instead of being
  swallowed by the seek bar.
- **Thumbnails open and autoplay.** Tapping a video thumbnail opens it through
  the proper path so it plays immediately.
- **Play button** plays via the media key (no more accidental fast-forward /
  rewind), and the **CC/timeline overlap** that moved the timeline when tapping
  the captions button.
- **Double-tap to exit** now closes the video reliably (was intermittently only
  hiding the controls).
- **Closed captions** prefer integrated (human-made) captions and fall back to
  auto-generated, matched by language — so videos with only integrated captions
  now show captions when you tap CC.
- **Home-screen search** opens the search screen directly (the magnifying glass
  previously focused/played a video below it on this device).
- Pointer **auto-hide** now restarts from your last interaction.

### Changed
- Added project **credit/license/changelog** files (`LICENSE`, `CREDITS.md`,
  `CHANGELOG.md`) — MIT, preserving upstream attribution.

### Known issues
- **Voice / microphone search does not work on the RayNeo X3 Pro.** The glasses
  do not expose a usable speech recognizer to third-party apps — the standard
  Android `SpeechRecognizer`, the launcher speech IPC, and the AI-runtime IPC
  were all tried and each dead-ends (no callbacks / binder unavailable /
  `initWorkflow` rejected). The working approach on this hardware (used by the
  TapLink X3 browser) is to record audio and transcribe it via a cloud API
  (Groq Whisper) or an offline engine (Vosk); that is not yet implemented. Full
  diagnosis and an implementation plan are in
  [`docs/VOICE_SEARCH_HANDOFF.md`](./docs/VOICE_SEARCH_HANDOFF.md). For now,
  search is reachable but text entry is not practical on-device.

## [0.1] — Initial RayNeo X3 Pro release

### Added
- **Mouse cursor for the player** driven by the temple trackpad, with auto-hide.
- **Dim caption mode** — double-swipe up for a black screen with a clock and the
  live captions (audio keeps playing); double-swipe down to exit.
- **Native trackpad navigation** tuned for the leanback grids (one row per swipe),
  with net-displacement swipe direction and smoothed cursor motion.

### Changed
- **Rebranded to "SmartTubular"** with its own package id and launcher icon, so
  it installs alongside the original SmartTube.
