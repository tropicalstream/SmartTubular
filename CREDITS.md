# Credits & Lineage

**SmartTubular** is a community fork in a chain of open-source work. It is released
under the MIT License (see [`LICENSE`](./LICENSE)), preserving every upstream
copyright notice as that license requires.

## Project lineage

1. **[SmartTube](https://github.com/yuliskov/SmartTube)** — by **yuliskov** and contributors.
   The original free, open-source media client for Android TV. Licensed MIT.

2. **[SmartTube - RayNeo X3 Pro Edition](https://github.com/oliverfederico/SmartTube-RayNeo-X3-Pro)**
   — by **Oliver Federico**. Forked SmartTube and adapted it for the RayNeo X3 Pro
   AR glasses (binocular display handling, device integration). Licensed MIT.

3. **SmartTubular** — by **Mars / tropicalstream**. This fork. Builds on the two
   projects above and adds RayNeo X3 Pro temple-trackpad UX:
   - On-screen mouse pointer for player controls, with tap-to-seek on the timeline scrubber.
   - Swipe-up "dim caption mode" (clock + live captions, audio keeps playing), double-swipe to toggle.
   - Swipe left / right to skip to the previous / next video in the queue.
   - Native trackpad navigation tuned for leanback grids (one row per swipe).
   - "SmartTubular" rebrand (separate package + icon so it installs alongside the original).

   Licensed MIT.

## Third-party components not covered by this license

- **MercuryAndroidSDK.aar** (`smarttubetv/libs/`) is the proprietary RayNeo Mercury
  Android SDK (`com.ffalcon.mercury.android.sdk.*`), © RayNeo / FFALCON. It is **not**
  MIT-licensed and is included only to enable the temple-pad integration on RayNeo
  hardware. All rights to that component remain with its owner.

## Acknowledgements

Thanks to yuliskov for the original SmartTube, to Oliver Federico for the X3 Pro
port and for collaborating on this fork, and to the wider SmartTube community.
