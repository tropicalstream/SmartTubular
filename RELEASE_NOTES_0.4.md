# SmartTubular 0.4-beta — Search reliability fix

> ⚠️ **Experimental build.** This continues the 0.3-geminisearch line (cloud
> voice search on the RayNeo X3 Pro). It still needs a free Google Gemini API
> key. If you want something settled, use the stable **0.2** release.

## What this fixes

In 0.3, search worked — but not every time. You'd say or type a query, the
voice part would transcribe fine, and then YouTube would show the sad-cloud
**"Can't load content"** screen with no results. Trying the *same* term again a
few times would eventually work. Annoying and unpredictable.

The voice side was never the problem. The problem was the **YouTube data layer**.

SmartTube talks to YouTube through a component called `MediaServiceCore`. YouTube
periodically changes how it validates requests (the "poToken" / innertube
machinery), and when it does, search results can come back empty until the app
adapts. Upstream SmartTube shipped a fix for exactly this — its changelog calls
it the *"Empty search results fix."* This fork was built on an **older**
`MediaServiceCore` that predated that fix, so it kept hitting the old bug.

## What changed

- **Bumped `MediaServiceCore` to upstream master**, which includes the poToken /
  innertube / search fixes. Search now loads reliably on the first try.
- That's the whole functional change. All the RayNeo features from 0.3 — Gemini
  voice search, the magnifying-glass trigger, gallery D-pad scrolling — are
  untouched.

### One mechanical detail (for anyone building from source)

The newer `MediaServiceCore` expects a newer `SharedModules` (a shared utility
library), but that newer `SharedModules` is **incompatible with this fork's
31.45 app code** (it makes some preference methods `final` and changes a couple
of file helpers). Upgrading everything would mean rewriting app code — not worth
it for one data-layer fix.

So instead: `SharedModules` stays at the app-compatible version, and
`MediaServiceCore` carries a **one-line compatibility patch** (it uses the older
prefs API). That patched `MediaServiceCore` lives in a fork at
**`github.com/tropicalstream/MediaServiceCore`**, and this repo's submodule
points at it. A normal `git clone --recursive` therefore builds with no manual
steps.

## Setup (unchanged from 0.3)

1. Get a free Gemini API key from Google AI Studio.
2. Install the APK on the glasses.
3. Set the key over adb (no on-device typing):
   ```
   adb shell am broadcast -a org.smarttube.beta.cc.SET_GEMINI_KEY \
     --es key "YOUR_KEY_HERE"
   ```
   Optionally override the model:
   ```
   adb shell am broadcast -a org.smarttube.beta.cc.SET_GEMINI_KEY \
     --es key "YOUR_KEY_HERE" --es model "gemini-2.5-flash"
   ```
4. Tap the magnifying glass to start voice search.

See [`gemini_key.txt.example`](./gemini_key.txt.example) for the full key
instructions. Your key is stored only in app-private preferences and is **not**
committed to the repo.

## Credits

Built on [yuliskov/SmartTube](https://github.com/yuliskov/SmartTube) and the
RayNeo X3 Pro fork by
[Oliver Federico](https://github.com/oliverfederico/SmartTube-RayNeo-X3-Pro).
The data-layer fix is upstream SmartTube's work; this release just pulls it in.
