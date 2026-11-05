<!-- 1.4 - Copyright (C) 2026 Stouthart. All rights reserved. -->

# Release notes

Highlights per version, newest first.

## 1.4 - November 2026

- **Calmer corners:** the cards, the **Current codec** panel, Quality's choices and **Lock now** share one corner, less round than before.
- **Even spacing:** the Sample rate slider sits close under its name, its bars icon on the name's line, and as far from **Quality** as the other sections are apart. App settings' ⌄ lines up with the card's content, and its title shows its whole highlight when tapped.

## 1.3 - November 2026

- **New colours:** white and light-grey surfaces with one clear blue in light mode, near-black greys in dark; the icon on dark grey, with a touch of that blue. The **Hi-Res** badge is green, as a lock's line, and **Bluetooth off** and **No headphones connected** show in orange.
- **Follows the codec:** the screen, and the tile while Quick Settings is open, notice the codec changing by itself, such as HIGH falling back to MID, rather than at the next look. One check per change, never on a timer: on Samsung a check reads the Bluetooth stack's state, which keeps it busy for a moment.
- **No skips while music plays:** reading Samsung's encoder made the audio skip, at its worst when the app came back to the front. While music plays, the screen and the tile now show the encoder as last read, the screen with the time it was read, such as **Locked · HIGH quality · checked 08:06**, and read it again once playback stops. Without a read for the codec Android reports, the screen says **Not verified · checked when playback stops**. The Play/Pause button needs no read for a playing app.
- **Play and pause in the panel:** while a music app plays or waits, a round Play/Pause button shows in **Current codec**, the key a headset's button sends: pause before a lock where one needs that, play on after. It follows playback in any app, read once per change.
- **Sturdier verdicts:** the lock, the screen and the tile now judge a lock the same way. Without `DUMP` a match of rate and bit depth reads **Not verified · needs DUMP to read the encoder** rather than Locked. On RedMagic a lock also waits for the quality after a change of rate or bit depth, and every lock checks that the same headphones are still playing before each change and before it reports. A lock that fails, as with the headphones gone, no longer turns one that still holds into **Not locked**. A phone without A2DP offload is no longer taken for a hardware encoder.
- **Steadier tile and screen:** the tile also notices headphones disconnecting or another pair taking over while Quick Settings is open, checks one at a time, with one more when something changed during a check, reads the encoder once music stops, and never clears a lock that finished after its check began. The screen no longer brings back an old status after the headphones disconnect, and a read that a lock overtook no longer replaces the lock's own.
- **App settings:** the Settings card is now called App settings; opening it scrolls all of it into view, its lower edge included.

## 1.2 - November 2026

- **Quality** in the Lock section: HIGH, LDAC's top quality, or MID for a steadier connection where HIGH keeps dropping out, as in a busy office. Each choice shows its bitrate at the chosen Sample rate: 909 or 606 kbps at 44.1 and 88.2 kHz, 990 or 660 kbps at 48 and 96 kHz. The status, the summary and the tile follow it.
- **Sturdier locks:** only the headphones Android plays to, never a guess between two pairs; headphones without LDAC are left alone; on RedMagic a change of quality alone is waited for; on Samsung an unreadable encoder shows **Not verified** instead of Locked, and the tile also notices the encoder falling back to adaptive. A failed lock no longer turns an earlier one into "failed" after a reconnect, and an uncertain encoder detection is read again rather than kept.
- **Settings fold away:** a tap on the title opens or closes them; closed at first, then as you left them.
- **Buttons and choices at one height.**
- **Headphones** throughout, as audiophiles say: **Pair headphones**, **No headphones connected**.

## 1.1 - November 2026

- **Android 14 and 15** as well, besides 16 and 17. Untested on those versions so far: the method was worked out on 16 and 17, and an older Bluetooth stack may behave or report differently.
- **Sample rate** in the Lock section: 44.1, 48, 88.2 or 96 kHz, for the app and the tile alike. Until it's moved, the phone's own: 44.1 kHz on Samsung, 48 kHz on RedMagic. Locks at 88.2 kHz checked on both.
- **New icon:** a gradient in the logo's blues, with a light-blue padlock between the headphones.

## 1.0 - November 2026

The first release: LDAC at its highest quality with one tap, on Samsung and RedMagic phones. The APK is attached to the release.

- **One tap**, from a Quick Settings tile or the app, on the headphones Android is playing to.
- **Samsung:** a detour via AAC makes the phone reopen the stream, and LDAC ends at 44.1 kHz, 24-bit, top quality (909 kbps), checked against the encoder itself.
- **RedMagic:** one LDAC change to 48 kHz, 24-bit, top quality (990 kbps), only while playback is paused, as a change during playback silences the audio chip.
- **Detects the phone's encoder**, software or hardware, and the mixer's rate, once per firmware build. This needs the `DUMP` permission, granted over adb; without it, LDAC Lock changes nothing during playback.
- **A screen** with the headphones, the current codec and whether it is locked, that follows the headphones and Bluetooth as they change. Light or dark as the phone is, or as set under **Appearance**.
- **A tile** that shows the result: as its second line on Samsung, and as its name on RedMagic, whose round tiles hide that line. Greyed out while Bluetooth is off.
- **Small and quiet:** 73 KB, no libraries, no internet permission, nothing running in the background, and a start in about 140 ms.
