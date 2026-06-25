# Changelog — SmartTubular

SmartTubular is a RayNeo X3 Pro fork of SmartTube. This changelog covers the
RayNeo-specific additions on top of upstream SmartTube (base version 31.45).
See [`CREDITS.md`](./CREDITS.md) for project lineage and licensing.

## [0.4.1-beta] — Navigation fixes + brightness-reboot note (EXPERIMENTAL)

> ⚠️ **Experimental.** Point update on 0.4-beta. Touch-pad navigation fixes plus
> a documented hardware/firmware known issue. No new permissions or setup.

### Fixed
- **Gallery swipes skipped items.** Swiping left/right through a row of videos
  jumped two at a time. The "did the selection actually move?" check (which
  resends the key when nothing moved) only ever read the outer vertical list, so
  a sideways move always looked "unchanged" and got re-sent. It now reads the
  inner row (`HorizontalGridView`) for left/right keys, so each swipe advances
  exactly one tile.
- **Left swipe jumped straight to the main menu.** A left swipe inside a content
  row was hand-moving focus via `focusSearch`, which leapt to the side menu
  instead of the previous tile. Horizontal moves *while inside a row* now defer
  to native leanback navigation (move one item; reach the side menu only at the
  first column), matching how right already behaved. The title search-orb focus
  lock is untouched — tapping search without scrolling down still works.

### Changed
- **Settings now opens with a double-tap on the home screen.** Accessing Settings
  from the home screen requires a double-tap; a single tap no longer opens it.

### Known issues
- **Reboot when raising brightness to high levels on battery.** With the glasses
  unplugged, raising display brightness to roughly 80%+ can hard-reboot the
  device within ~2 seconds. This is **not** a SmartTubular bug and isn't fixable
  in the app — it's a RayNeo power/firmware protection event. Local logs show
  `JBD4020: thermal_decay` (MicroLED panel thermal protection), rapid panel temp
  rise, and battery-voltage sag right after unplug (e.g. 3760 mV at 61%), with no
  kernel panic trail preserved (`pstore/ramoops/last_kmsg` empty) — consistent
  with the display/power firmware resetting on a brightness-driven load spike
  before Android can log a normal crash. The X3 Pro's MicroLED panel is rated up
  to ~6000 nits and reviews widely note weak battery life under high-brightness
  use; ~20% brightness is typically enough indoors. **Workaround:** keep
  brightness moderate on battery, or stay plugged in for high-brightness use.
  This is worth reporting to RayNeo support as a firmware issue (outdated
  firmware is a known factor); the log signature above is what their engineers
  can act on.

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
- **Search magnifying glass sometimes played the video below.** On the glasses a
  tap activates whatever is focused, but focus jumped onto a result video as soon
  as a search loaded — so a tap meant for the magnifying glass played that video.
  Focus now stays in the search bar after results load (you drop into the results
  by swiping down), and the input layer routes any tap while focus is in the
  search bar to a new voice search. The rule keys off whether focus sits in the
  leanback SearchBar rather than fragile orb focus/scale flags, so it's reliable.

### Added
- **Voice list results (discovery + "best/top" lists).** Spoken requests that
  call for *multiple distinct titles* now return a full gallery of results
  instead of one search hit. This covers both similarity ("songs that sound like
  OMD Genetic Engineering", "movies like Cast Away", "more like this") and
  ranked/curated lists ("highest rated animated films", "best new wave songs of
  the 80s"). Gemini returns a list of specific titles; the app searches each one,
  takes the top few hits of each, and shows them as a single row of unique
  results (de-duplicated by video, so nothing repeats). Plain searches are
  unchanged.
- **Cleaner search queries + editable system prompt.** Gemini is now instructed
  to return a single clean search query (it used to occasionally echo
  punctuation/commentary into the search box). It stays verbatim when you name
  something specific and only resolves a real title when you give a vague
  description. The prompt ships baked in but is overridable over adb with a
  `--es prompt "..."` extra — see [`gemini_setup.txt.example`](./gemini_setup.txt.example).
- **Renamed `gemini_key.txt` → `gemini_setup.txt`** (now covers key, model, and
  the system prompt in one place).

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
  `am broadcast` command (see [`gemini_setup.txt.example`](./gemini_setup.txt.example)).
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
