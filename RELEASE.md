<!-- 2.0 - Copyright (C) 2026 Stouthart. All rights reserved. -->

# Release notes

Highlights per version, newest first, with 1.x condensed. 2.0 is the version distributed; the entries below it show how the app got there.

## 2.0 - November 2026

- **New corners:** the cards are a little less round, and the **Current codec** panel less round still, as a panel within a card. Quality's and Appearance's choices, **Lock now**, **Pair headphones** and the **Hi-Res** badge are capsules, with round ends.
- **Play/Pause stays put:** the button in **Current codec** changes at a tap and no longer flips back for a moment when a check of the music app that began before the tap comes back. For a moment after the tap, a check that disagrees is left out while the music app follows and the music goes quiet; the check after that has the last word, also when no app took the key.
- **Locking… stays:** while a lock runs, from the app or the tile, **Lock now** keeps saying **Locking…** and stays greyed out, and the tile keeps its **Locking…**, also when something else redraws them meanwhile, as the headphones' battery changing or Quick Settings opening again. A lock that never reports lets go after 30 seconds.
- **Sturdier:** leaving the app and opening it again quickly no longer leaves a second connection to Android's Bluetooth audio open.

## 1.x - November 2026

The first release, then Sample rate, Quality, new colours, the Play/Pause button and the headphones' battery.

- **1.0** - LDAC at its highest quality with one tap, from a Quick Settings tile or the app, on the headphones Android is playing to. Samsung gets a detour via AAC and ends at 44.1 kHz, 24-bit, 909 kbps, checked against the encoder itself; RedMagic one LDAC change to 48 kHz, 24-bit, 990 kbps, only while playback is paused. The phone's encoder and mixer rate detected once per firmware build, with the `DUMP` permission granted over adb. A screen with the headphones, the current codec and whether it's locked, and a tile that shows the result. 71 KB, no libraries, no internet permission, nothing running in the background.
- **1.1** - Android 14 and 15 as well, untested so far; **Sample rate**, 44.1, 48, 88.2 or 96 kHz, for the app and the tile alike; a new icon in the logo's blues.
- **1.2** - **Quality**, HIGH or MID for a steadier connection, each with its bitrate at the chosen rate; sturdier locks, only on the headphones Android plays to and never on headphones without LDAC; settings that fold away; buttons and choices at one height; **headphones** throughout.
- **1.3** - New colours, white and light grey with one clear blue, or near-black greys; the screen and the tile following the codec as it changes by itself, such as HIGH falling back to MID; no audio skips while music plays, with the encoder as last read and the time it was read; a **Play/Pause** button in **Current codec**; sturdier verdicts, the same in the lock, the screen and the tile; **App settings**.
- **1.4** - Calmer corners, and even spacing around the Sample rate slider and App settings.
- **1.5** - The headphones' battery next to their kind, in orange from 15% down; a refused or failed lock in orange; on RedMagic, what its mixer resamples; the version in App settings; upright on a phone; App settings opening with a tap anywhere on its card; sturdier locks and tile, and a Play/Pause button that no longer jumps back after a pause.
