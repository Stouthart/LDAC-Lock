<!-- 1.1 - Copyright (C) 2026 Stouthart. All rights reserved. -->

# Release notes

Highlights per version, newest first.

## 1.1 - November 2026

- **Android 14 and 15** as well, besides 16 and 17. Untested on those versions so far: the method was worked out on 16 and 17, and an older Bluetooth stack may behave or report differently.
- **Sample rate** in the Lock section: 44.1, 48, 88.2 or 96 kHz, for the app and the tile alike. Until it's moved, the phone's own: 44.1 kHz on Samsung, 48 kHz on RedMagic. Locks at 88.2 kHz checked on both.
- **New icon:** a gradient in the logo's blues, with a light-blue padlock between the headphones.

## 1.0 - November 2026

The first release: LDAC at its highest quality with one tap, on Samsung and RedMagic phones. The APK is attached to the release.

- **One tap**, from a Quick Settings tile or the app, on the headphone Android is playing to.
- **Samsung:** a detour via AAC makes the phone reopen the stream, and LDAC ends at 44.1 kHz, 24-bit, top quality (909 kbps), checked against the encoder itself.
- **RedMagic:** one LDAC change to 48 kHz, 24-bit, top quality (990 kbps), only while playback is paused, as a change during playback silences the audio chip.
- **Detects the phone's encoder**, software or hardware, and the mixer's rate, once per firmware build. This needs the `DUMP` permission, granted over adb; without it, LDAC Lock changes nothing during playback.
- **A screen** with the headphone, the current codec and whether it is locked, that follows the headphone and Bluetooth as they change. Light or dark as the phone is, or as set under **Appearance**.
- **A tile** that shows the result: as its second line on Samsung, and as its name on RedMagic, whose round tiles hide that line. Greyed out while Bluetooth is off.
- **Small and quiet:** 73 KB, no libraries, no internet permission, nothing running in the background, and a start in about 140 ms.
