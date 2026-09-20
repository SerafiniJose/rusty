# Rusty

**Rusty turns a spare Android screen into an always-on appliance for the room it sits in.**

It starts as a **Spotify Connect receiver**: open it and the device appears as a speaker in any
Spotify client on your network — phone, desktop, web — with audio streamed and decoded on the
device itself, under an ambient, lyrics-aware now-playing screen. Leave it alone and it becomes
whatever else the room needs: a photo frame, a **Home Assistant** dashboard, a wall of camera
feeds, or just a good clock.

Built on **[Rust](https://www.rust-lang.org/)** and **[Kotlin](https://kotlinlang.org/)** — and
built to give new life to rusty devices. Runs on any Android 8.0+ device, and is at home on
always-on screens like the Amazon Echo Show.

<!-- Replace the badge owner/repo if you rename the repository. -->
[![Release](https://img.shields.io/github/v/release/SerafiniJose/rusty?sort=semver)](https://github.com/SerafiniJose/rusty/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## Showcase

https://github.com/user-attachments/assets/973e78b3-98b2-4a9f-96a5-fc913f78ac96

---

## Screenshots

| Now playing | Synced lyrics | Screensaver · Clock |
| --- | --- | --- |
| ![Now playing](screenshots/now-playing.png) | ![Lyrics](screenshots/lyrics.png) | ![Clock screensaver face](screenshots/screensaver-clock.png) |

| Screensaver · OLED | Home Assistant | On-screen launcher |
| --- | --- | --- |
| ![OLED screensaver face](screenshots/screensaver-oled.png) | ![Home Assistant dashboard](screenshots/home-assistant.png) | ![On-screen launcher](screenshots/launcher.png) |

| Settings | Services & status | Cameras |
| --- | --- | --- |
| ![Settings](screenshots/settings.png) | ![Services and status](screenshots/services-status.png) | ![Camera wall](screenshots/cameras.png) |

| Control page | Control page on a phone |
| --- | --- |
| ![Control page](screenshots/control-page.png) | ![Control page on a phone](screenshots/control-page-phone.png) |

> Captured on an Amazon Echo Show 8 (1280×800), except Services & status, which is from a Lenovo
> Tab M10 because that page is taller than an 800 px screen. Cover art is a generated gradient and
> the track, artist, listener and lyrics are placeholders — no copyrighted content. The Home
> Assistant shot uses the public Home Assistant demo. The Cameras shot shows four illustrated test
> scenes served from a laptop, not real cameras, and the control page shots were taken in a browser
> against a tablet running Rusty with those same test cameras.

---

## Features

**Spotify**

- **Connect target** — zero-config discovery; appears automatically in any Spotify client on the
  same network.
- **Streamed straight to the device** — decoded on-device by
  [librespot](https://github.com/librespot-org/librespot) (Rust) through cpal's native **AAudio**
  backend, at up to 320 kbps. The output follows the active audio route, so sound moves with a
  Bluetooth speaker connected or dropped mid-track.
- **Ambient now-playing screen** — album-art colour wash, drifting mesh background, accent-aware
  theming, and time-aligned **lyrics** that scroll with the track, active line highlighted.
  Optionally the track's looping **Spotify Canvas** video in place of static art.
- **Playback takeover** — when a phone or laptop starts playing on this receiver, Rusty can wake
  the screen and bring itself to the front. Both toggles off by default. See
  [Playback takeover](docs/remote-control.md#playback-takeover).

**When nothing is playing**

- **Screensaver** — after an idle timeout, a clean **Clock** face, a burn-in-safe **OLED** face,
  the track's **Canvas**, or your own photos. It wakes gently back to now-playing.
- **Immich Slideshow** — point Rusty at a self-hosted [Immich](https://immich.app) server and the
  idle screen becomes a photo frame: the whole library or just the albums, people and tags you
  pick, with slow Ken Burns motion, a blurred fill and an optional clock. The key it needs is
  read-only — see [Immich API key permissions](docs/requirements.md#immich-api-key-permissions).

**More than a speaker**

- **Home Assistant dashboard** — sign in and Rusty shows your dashboards full-screen and
  kiosk-style, with switcher chips to jump between them and chrome tinted to your theme. It
  discovers your dashboards and sidebar apps itself.
- **Home Assistant media renderer** — Rusty appears as a DLNA `media_player` entity with nothing
  to install on the HA side. Stream internet radio to it, or drive it from automations, scripts
  and dashboard cards.
- **Spoken announcements** — type a message on the control page or send one from a Home Assistant
  automation and the device says it out loud, in a downloadable neural voice.  Spotify pauses or fades while it speaks and resumes afterwards.
- **Cameras** — a wall of your RTSP cameras as snapshot tiles that refresh on a timer, and a tap
  or OK away a full-screen live view with sound, a sub/main switch and a snapshot button. Add
  them by ONVIF scan, by address or by hand. A Rusty with its own camera can share it to the
  others. See [Cameras](docs/cameras.md).

**Living with it**

- **Remote control** — an optional, off-by-default web page and HTTP API the device serves
  itself: switch what Rusty is showing, bring its window forward, set brightness, volume and
  screen on/off, speak a message, and re-aim the Slideshow — from a phone in another room. Scan
  a QR code to open it. See [Remote control](docs/remote-control.md).
- **Services & status** — one page showing every service and feature at a glance, each with its
  state, name and address, reachable from the info button on any screen.
- **Made to sit on a shelf** — start on boot, keep the screen on, hide the system bars, rename
  the receiver live without a restart, update itself from **About & updates** or from another
  room, an on-screen launcher between Spotify, Home Assistant and the screensaver, and settings
  tabbed per feature.

## Install

1. Go to the [**Releases**](https://github.com/SerafiniJose/rusty/releases/latest) page.
2. Download the `.apk` for the latest release (e.g. `rusty-v2.0.0.apk`).
3. Sideload it onto your device:
   ```bash
   adb install -r rusty-v2.0.0.apk
   ```
   (Or enable "Install unknown apps" and open the APK directly on the device.)
4. Launch the app — it begins advertising as a Connect target right away.

> The published APK is **debug-signed** (built with `assembleDebug`). It installs and runs fine for
> sideloading; if you later switch to a release-signed build, uninstall first to avoid a signature
> conflict on upgrade.

## Remote control is open by default — read this first

Rusty's remote-control page and HTTP API are **off** until you switch them on, and once on they
carry **no password unless you set one**: anyone on your local network can drive the device, and
the camera share serves plain RTSP on port 8554. That is a reasonable default on a home network
and a bad one anywhere else. Set a password, never port-forward 8765 or 8554, and reach it from
outside over your own VPN instead. The full detail, threat by threat, is in
[Remote control → Security](docs/remote-control.md#security--please-read-before-enabling).

## Documentation

The detail lives in [`docs/`](docs/), one page per area:

- [**Requirements**](docs/requirements.md) — what Rusty needs from your device and network, and
  the exact read-only permissions for an Immich API key.
- [**Cameras**](docs/cameras.md) — adding RTSP cameras, the snapshot wall and live view, and
  sharing one Rusty device's own camera to the others.
- [**Remote control**](docs/remote-control.md) — the control page and HTTP API end to end: every
  card, the QR code, system brightness, Home Assistant, playback takeover, and the security note.
- [**Build from source**](docs/build.md) — the Gradle build, and rebuilding the Rust native core.
- [**How it works**](docs/how-it-works.md) — the architecture, from librespot up to the UI.

## Work in Progress

- **One track, every room** — play in sync across several Rusty devices at once, so a shelf of
  old screens becomes a multi-room system.
- **Calls between Rusty devices** — turn a pair of Rusty screens into an intercom, room to room.
- **Rusty as a microphone for your voice assistant** — speak to the device and let it feed
  Hermes agents, or a self-hosted assistant over the
  [Wyoming protocol](https://www.home-assistant.io/integrations/wyoming/), turning a screen on a
  shelf into a voice satellite for Home Assistant's Assist.
- **A Home Assistant integration on HACS** — install Rusty support from HACS instead of leaning
  on auto-discovery, with the device's services and status as proper entities.

Ideas and votes are welcome in the [issues](https://github.com/SerafiniJose/rusty/issues).

## Credits & attribution

- Built on **[librespot](https://github.com/librespot-org/librespot)** (MIT) — the open-source
  Spotify client library that does the real protocol and audio work.
- Originally inspired by **[willturr/librespot-android-connect](https://github.com/willturr/librespot-android-connect)**,
  a proof-of-concept that demonstrated driving librespot from Android over JNI.
- Home Assistant dashboard icons are rendered with the **[Material Design Icons](https://pictogrammers.com/library/mdi/)**
  webfont by the [Pictogrammers](https://pictogrammers.com/) group (fonts under the Apache 2.0 license).
- The **Home Assistant** screen embeds your own [Home Assistant](https://www.home-assistant.io/)
  instance (an open-source home-automation platform; this project is not affiliated with it).
- Downloadable announcement voices run on **[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)**
  (Apache 2.0), whose prebuilt Android library is checked in under `app/libs/`. The voices
  themselves are **[Piper](https://github.com/rhasspy/piper)** models repackaged by the sherpa-onnx
  project; each voice carries its own dataset license and attribution, shown in the app's voice
  catalog before download.
- The **Immich Slideshow** connects to your own self-hosted **[Immich](https://immich.app)** server
  ([source](https://github.com/immich-app/immich)) — an open-source, self-hosted photo and video
  library. Rusty reads your photos through Immich's API with a read-only key and bundles none of its
  code; this project is not affiliated with it.

## Disclaimer

This is an unofficial, independent project. It is **not** affiliated with, authorized, or endorsed
by Spotify. "Spotify" is a trademark of Spotify AB. You need your own Spotify Premium account to use
it, and you are responsible for complying with Spotify's Terms of Service. Provided as-is, for
personal and educational use.

## License

Everything in this repository is licensed under the MIT license.
