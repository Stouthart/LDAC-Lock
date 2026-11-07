<!-- 1.5 - Copyright (C) 2026 Stouthart. All rights reserved. -->

# Lock Bluetooth LDAC at its highest quality

A small Android app that sets your headphones' **LDAC** connection to its highest quality with one tap, from a Quick Settings tile or the app itself. Android starts every LDAC connection at adaptive quality, which lowers the bitrate whenever the connection gets busy, and on Samsung phones a plain change of the quality setting doesn't even reach the encoder. LDAC Lock makes the change stick, then checks what the encoder actually runs. Where the connection can't hold the top quality, it locks a lower fixed one instead. No internet access, no ads, no account, no background service, and nothing beyond Android itself: the app is 94 KB.

<p align="center"><img src="docs/screenshot-s25.png" width="360" alt="LDAC Lock on a Galaxy S25 Edge, locked at LDAC 44.1 kHz, 24-bit, 909 kbps, the headphones at 100%, with music playing"></p>

This is **version 1.5**. The [release notes](RELEASE.md) list what each version changed.

## What it sets

| Phone                   | LDAC encoder                      | LDAC Lock sets                     | Why that rate                                                                                   |
| ----------------------- | --------------------------------- | ---------------------------------- | ----------------------------------------------------------------------------------------------- |
| Samsung Galaxy (One UI) | In the Bluetooth stack (software) | 44.1 kHz, 24-bit, HIGH (909 kbps)  | The mixer follows the codec, so music at 44.1 kHz, such as Spotify, isn't resampled at all     |
| RedMagic                | In the audio chip (hardware)      | 48 kHz, 24-bit, HIGH (990 kbps)    | The mixer runs at a fixed 48 kHz, so LDAC at 48 kHz avoids a second resample back to 44.1 kHz  |

HIGH is LDAC's top quality, known as 990 kbps: it runs at 990 kbps at 48 and 96 kHz, and at 909 kbps at 44.1 and 88.2 kHz.

These are the defaults. **Sample rate** in the Lock section sets 44.1, 48, 88.2 or 96 kHz instead, for the app and the tile alike; a rate the headphones don't offer falls back to 44.1 or 48 kHz. Mind the mixer: on Samsung it follows the codec, so above 44.1 kHz the phone resamples 44.1 kHz music; RedMagic's stays at 48 kHz, so any other rate adds a resample in the audio chip.

**Quality**, below it, sets HIGH (the default) or MID: 660 kbps at 48 and 96 kHz, 606 kbps at 44.1 and 88.2 kHz. MID is for places where the air is busy, such as an office full of headphones and WiFi, and HIGH keeps dropping out. It's your choice: LDAC Lock never switches quality by itself. At MID, 44.1 or 48 kHz is the better choice: 88.2 and 96 kHz spread the same bitrate over twice the frequency range.

## Requirements

- **Android 14** or newer
- Headphones with **LDAC**
- **adb** on a computer, to grant one permission (recommended, see [Installation](#installation))
- To build it yourself: **JDK 21** and the **Android SDK** command-line tools with `platforms;android-37.0` and `build-tools;37.0.0`. The project brings its own Gradle.

Developed and tested on a **Samsung Galaxy S25 Edge** (One UI 9, Android 17) and a **RedMagic Astra** (REDMAGIC OS 11, Android 16), with Noble FoKus Prestige Encore and FoKus Amadeus headphones. Other Samsung and RedMagic devices should work alike. On other phones LDAC Lock detects which kind of encoder they have and picks the matching method, but that is untested. So are Android 14 and 15: the method was worked out on Android 16 and 17, and an older Bluetooth stack may behave or report differently.

## Installation

Download `LDAC-Lock-1.5.apk` from the [latest release] and open it on the phone; Android asks to allow installing apps from that source first. Or install it from a computer with the phone connected over USB or wireless debugging:

```bash
adb install LDAC-Lock-1.5.apk
```

Then grant the one permission an app can't ask for itself:

```bash
adb shell pm grant nl.stouthart.ldaclock android.permission.DUMP
```

`DUMP` lets LDAC Lock read the encoder's real state and tell a software encoder from a hardware one; [Permissions](#permissions) says why that matters.

### Building it yourself

```bash
git clone https://github.com/Stouthart/LDAC-Lock.git
cd LDAC-Lock
echo "sdk.dir=$ANDROID_HOME" >local.properties   # the SDK's location
./gradlew assembleRelease
adb install app/build.nosync/outputs/apk/release/app-release.apk
```

`./gradlew test` runs the unit tests of the dump readers, the rate fallback, the lock verdict and the format line, on the computer.

Your build is signed with your own debug key, the release APK with mine. Android won't update one with the other, so uninstall the downloaded app before installing your own build, and grant `DUMP` again afterwards.

[latest release]: https://github.com/Stouthart/LDAC-Lock/releases/latest

## Setup

1. Open **LDAC Lock** and allow **Nearby devices**.
2. With the headphones connected, tap **Pair headphones** and confirm the system dialog. Android lets an app change the headphones' codec only after this companion-device pairing; repeat it for each pair.
3. Add the **LDAC Lock** tile: pull down Quick Settings, tap the pencil, and drag it into the panel.

## Usage

Tap the tile, or **Lock now** in the app. LDAC Lock works on the headphones Android is playing to; with two pairs connected, the other is left alone. Headphones without LDAC are left alone too: the lock says **These headphones don't support LDAC** and changes nothing, rather than detour to a codec it couldn't come back from.

- **Samsung (software encoder):** it switches to AAC and back to LDAC, at 44.1 kHz or the chosen **Sample rate**, with 32-bit and then 24-bit, each at the chosen **Quality**. The detour makes the phone reopen the stream, which a plain LDAC change doesn't do on Samsung, and the 32 → 24-bit step makes the stack pass the quality on. Music pauses for about a second and a half. Samsung resets LDAC on every connection, so tap once per connection.
- **RedMagic (hardware encoder):** one LDAC change to 48 kHz or the chosen **Sample rate**, 24-bit, at the chosen **Quality**, and only with playback paused: a change while music plays leaves the audio chip silent, sometimes until the headphones reconnect. While music plays, a tap only shows **Pause first**. The setting stays across reconnects.

The screen shows the headphones, the current codec and whether it is locked, and follows the headphones, Bluetooth and the codec as they change, such as HIGH falling back to MID.

### The tile

| Tile                        | Samsung (second line)          | RedMagic (name) |
| --------------------------- | ------------------------------ | --------------- |
| Highlighted                 | The bitrate, such as `909 kbps` | `LDAC Locked`   |
| Normal                      | `Not locked`, `Failed` or `Not verified` | `LDAC Lock` |
| While it runs               | `Locking…`                     | `Locking…`      |
| Tap refused during playback | `Pause playback first` *       | `Pause first`   |
| Greyed out                  | `Bluetooth off`                | `Bluetooth off` |

\* On Samsung only without the `DUMP` permission.

RedMagic's round tiles don't show a second line, so there the tile's name carries the status. When you open Quick Settings, a tile that claims a lock checks the codec again, and turns normal after a reconnect; while the panel stays open, it also notices the headphones disconnecting, another pair taking over, Bluetooth coming back on, and the codec changing by itself, such as HIGH falling back to MID; a change during a check gets one more. A check only clears the lock it was about: a lock finished meanwhile stays. On Samsung with `DUMP` it also reads the encoder, so it turns normal too when the encoder fell back to adaptive while the report still says HIGH.

### The screen

| Status line                                    | Meaning                                                                                                          |
| ---------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| `Locked · <mode> quality`                      | The encoder runs at the chosen quality, HIGH or MID, read from the encoder itself                                |
| `Not locked · <mode> quality`                  | LDAC, but the encoder runs adaptive (ABR) or another quality than the chosen one                                 |
| `Locked · quality not readable on this device` | Rate, bit depth and the reported quality match; RedMagic's audio chip doesn't report what it actually runs     |
| `Not verified · encoder not readable`          | Samsung with `DUMP`: the encoder's state couldn't be read this time, so the quality is unknown; lock again       |
| `Not verified · needs DUMP to read the encoder` | Without `DUMP`, rate and bit depth match, but nothing reads the encoder or tells which one the phone has; grant `DUMP` |
| `… · checked 08:06`                            | While music plays: the encoder as read at that time, as Android still reports the same codec                     |
| `Not verified · checked when playback stops`   | While music plays, with no read for the codec Android reports now: it's read once playback stops                 |
| `Not locked`                                   | Another codec, rate or bit depth                                                                                 |

At the top, the headphones' name and kind, and their battery when they report it to the phone, such as **Headset · 80%**, in orange from 15% down.

On RedMagic, a line under the format in **Current codec** tells what its mixer, fixed at 48 kHz, resamples: `Music at 44.1 kHz is resampled once, to the mixer's 48 kHz.`, or with another Sample rate, in orange, as setting 48 kHz avoids it, `The mixer runs at 48 kHz: LDAC at 44.1 kHz adds a resample.` On Samsung the mixer follows the codec, so there's no such line.

In the **Current codec** panel, a round **Play/Pause** button shows while a music app plays or waits, such as Spotify paused. It sends the key a headset's button sends, so it pauses before a lock where one needs that, and plays on after. With `DUMP` it follows the app that gets the keys; without, it shows **Pause** only while something plays, as **Play** might start an app nobody meant.

Above **Lock now**, **Sample rate** and **Quality** set what the next lock aims at; Quality's two choices show their bitrate at the chosen rate, such as `909 kbps` and `606 kbps`. Changing either never locks by itself. Below, the last lock shows its time and how long it took, or in orange why it failed, or that playback needs pausing first.

**App settings** opens with a tap anywhere on it and closes with a tap on its title; it starts closed and then stays as you left it. When it opens, all of it scrolls into view. In it, **Appearance** sets the app's look: **System default** follows the phone, **Light mode** and **Dark mode** set it for LDAC Lock alone. Its last line is the app's version and copyright.

## Permissions

| Permission                             | Why                                                                                                                                                                                                                                                  |
| -------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Nearby devices (`BLUETOOTH_CONNECT`)   | Reading and changing the codec                                                                                                                                                                                                                       |
| Companion device pairing               | Android lets an app change the headphones' codec only after you pair the app with them                                                                                                                                                               |
| Foreground service (`connectedDevice`) | Keeps a lock running when the Quick Settings panel closes. It shows no notification, as the app doesn't ask to show one.                                                                                                                             |
| Starting from the background as a companion app | Lets the tile start that service                                                                                                                                                                                                             |
| `DUMP`, granted over adb               | Reads the encoder's real state from `dumpsys bluetooth_manager`, and detects a hardware encoder and the mixer's rate; and from `dumpsys media_session` whether the music app plays or waits, for the Play/Pause button. Without it, LDAC Lock can't verify the quality or tell which encoder the phone has, so to be safe it changes nothing during playback. |

No internet permission: nothing can leave the phone.

## Notes

- The codec calls are hidden Android APIs, reached by reflection: `getCodecStatus`, `setCodecConfigPreference` and `getActiveDevice`. They may change with an Android update; on a phone without them, the lock says so instead of the app failing to start.
- LDAC Lock detects the phone's encoder once per firmware build, and a lock detects it again after each restart, so switching hardware offload in Developer options is picked up; the screen and the tile keep showing the last one meanwhile. It keeps only a certain answer: a dump that timed out or came back incomplete is read again at the next lock. Each dump read gives up after 2 seconds.
- An app that sets codecs automatically, such as a codec switcher with an auto-switch, can undo a lock. Turn that off.
- While music plays, LDAC Lock doesn't read the encoder: on Samsung that read makes the audio skip. While the codec Android reports is the same, the screen shows the encoder as last read, with the time, and the tile goes by it. Otherwise the report alone tells: **Not locked** after a reconnect, else **Not verified · checked when playback stops**, and the tile stays as it is. Both read it again once playback stops. Right after a lock it's still read, as the lock restarts the stream anyway.
- Nothing runs in the background: LDAC Lock only works when you tap, and listens to Bluetooth only while its screen or Quick Settings is open. It starts in about 140 ms.
- The screen stays upright on a phone; on a tablet, from 600dp wide, it turns.
- Colours mean the same throughout: blue is what's chosen or on; green good news, such as a lock that holds and the **Hi-Res** badge; orange a problem, such as **Not locked**, a failed lock, Bluetooth off or no headphones connected.
- Build output goes to `build.nosync` folders, which iCloud Drive doesn't sync.

### Device quirks

- **Samsung** lets the headphones set up each connection, and then ignores LDAC changes that keep the codec: hence the detour via AAC. Its reported quality setting can say 990 while the encoder runs adaptive, so LDAC Lock reads the encoder itself.
- **RedMagic** encodes LDAC in its Qualcomm audio chip, whose driver can't reconfigure a stream while it plays. Its mixer is fixed at 48 kHz, so music at 44.1 kHz is always resampled once. RedMagic's DTS sound processing also runs in the audio path: turn **DTS Sound** off in Settings › Sound for an unaltered signal.

## Credits

The icons are Google's Material Icons `check_circle`, `error`, `expand_more`, `headphones`, `pause` and `play_arrow`, under the Apache License 2.0.

## License

Copyright (C) 2026 Stouthart. All rights reserved.

_The source code in this repository is free for personal use. However, it is NOT published under a software license. This implies - as stated in the [GitHub Docs] - that standard copyright law applies, meaning the owner retains all rights to the source code and no one may reproduce, distribute, or create derivative works from this work._

[GitHub Docs]: https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository
