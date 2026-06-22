# Changelog — SmartTubular

SmartTubular is a RayNeo X3 Pro fork of SmartTube. This changelog covers the
RayNeo-specific additions on top of upstream SmartTube (base version 31.45).
See [`CREDITS.md`](./CREDITS.md) for project lineage and licensing.

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
