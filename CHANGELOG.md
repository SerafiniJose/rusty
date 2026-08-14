# Changelog

All notable changes to Rusty are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The section matching each release tag is published as that release's notes and is
what the app shows under **About & updates → What's new**, so keep entries short and
user-facing.

## [Unreleased]

## [2.4.0] - 2026-08-15

### Added
- Remote control from any browser on your network. An optional control page and
  HTTP API Rusty serves itself on port 8765: switch what's on screen, bring Rusty
  forward, turn the screen on or off, set brightness and volume, and re-aim the
  Slideshow's filters. Off by default; read the security note in the README before
  enabling it.
- Services & status. One page showing every service and feature at a glance, each
  with its state, name and address. Open it from the info button on any screen.
- Update Rusty from Rusty. About & updates — and the control page — can check for a
  new release and install it. Android always asks for confirmation on the device
  screen.
- Playback takeover. Rusty can react when someone starts playing on this receiver:
  switch to the Spotify page, and wake the screen and come to the front. Two toggles
  in Settings → Spotify, both off by default, and only a genuine new play triggers
  them. Waking and coming forward needs the "Display over other apps" permission;
  until it's granted the switch shows amber.
- Screen control. Turn the display off or set its brightness remotely. Off is a
  black overlay that keeps the panel awake, so turning it back on is instant.
- Home Assistant chrome in Rusty's style: a section bar with its own back control,
  dashboard chips that stay in step as you navigate, and a floating title instead of
  a repainted header.

### Changed
- The Spotify-only session sheet is now the app-wide Services & status page.

### Fixed
- Home Assistant's sub pages — security, lights, climate, history — opened blank.
- The Home Assistant dark theme did not stick: it painted for a moment and then
  reverted to light on every load.
- Turning the DLNA Player feature off left its renderer service running.

## [2.3.0] - 2026-07-23

### Added
- Immich Slideshow screensaver. A new screensaver face that shows photos from your
  own Immich server. Enter the server address and an API key, then pick what it
  shows — your whole library, or a selection of albums, people or tags chosen from a
  searchable picker with thumbnails. Photos change on a timer (10 seconds to 5
  minutes) with a slow Ken Burns drift and a blurred fill behind them, plus an
  optional clock, optional photo info (date, place, people), and an optional split
  view that pairs two portrait photos side by side. Tap the photo for pause, next
  and previous; a remote's media keys work too. The API key needs read-only access;
  the exact Immich permissions to enable are listed in the project README.
- Sign in to Home Assistant from settings. Enter your Home Assistant address,
  username and password — and a two-factor code if you use one — right in Rusty's
  settings, instead of only through the dashboard's web page. Rusty stays signed in
  across restarts.
- Home Assistant theme. Choose which of your Home Assistant dashboard themes Rusty
  uses; it tints its own top and bottom bars to match so the screen looks like one
  piece.

### Changed
- The screensaver faces are now named Clock, OLED, Spotify Canvas and Immich
  Slideshow.
- Password and API-key fields have a show/hide button.

### Security
- API keys and your Home Assistant sign-in are kept in encrypted storage, and
  nothing Rusty stores leaves the device: cloud backup and device-to-device transfer
  are both switched off. Settings warns when a server address is plain http, since
  the credential then crosses your network unencrypted.

### Fixed
- Popup cards (settings, filter pickers) re-size when the device rotates instead of
  overflowing off the screen.
- Home Assistant player: the mix-mode and fade options no longer overflow a narrow
  settings card.

## [2.2.0] - 2026-07-17

### Added
- Home Assistant media renderer. Rusty now shows up as a media player in Home
  Assistant and other DLNA/UPnP apps, so you can send text-to-speech announcements and
  audio to it over the network. Announcements duck or pause Spotify and then hand
  playback back automatically, with an optional volume fade (off, 250, 500, or
  1000 ms). Radio streams play too.
- DLNA player screen. An optional full-screen now-playing view for media sent to
  Rusty — album art, title, artist, and a progress bar with play, pause, stop, and
  seek. Turn it on under the app's settings.

### Changed
- The default Spotify Connect device name is now Rusty Speaker.

## [2.1.0] - 2026-07-09

### Fixed
- Updates now install over an existing version. Releases are signed with a stable
  key; the jump from v2.0.0 needs one final uninstall, then future updates apply
  normally.
- Android TV: a remote key now dismisses the idle screensaver, restoring D-pad
  control on devices like the NVIDIA Shield.
- Album art now loads at full resolution.

## [2.0.0] - 2026-06-29

Rusty grows from a single-purpose Spotify Connect receiver into an ambient,
multi-feature appliance. The Spotify receiver works exactly as before — everything
new is additive, and the whole-screen features are off by default.

### Added
- **Screensaver.** After an idle timeout — or a tap on the clock — Rusty shows a
  full-screen idle face and gently wakes back to now-playing. Choose a clean Clock
  face, an OLED-burn-in-safe drifting face, or a Canvas face that plays the track's
  looping Spotify Canvas video.
- **Home Assistant as a second screen.** Sign in once and Rusty shows your Home
  Assistant dashboards full-screen in a kiosk-style view, with switcher chips to jump
  between them.
- **Spotify Canvas in now-playing.** Optionally fill the now-playing screen with the
  track's looping Canvas video instead of static album art.
- **Start on boot.** Optionally launch Rusty automatically when the device powers on,
  so it comes back as an always-on display.
- **Keep screen on.** An optional switch holds the display awake while Rusty is in
  front, honored on both now-playing and lyrics.

### Changed
- Settings are now organized into tabs, one page per feature.

## [1.3.1] - 2026-06-24

### Fixed
- Fixed playback failing with every track skipping and no sound, caused by Spotify
  handing out an unreachable audio server. The receiver now falls back to the other
  servers Spotify provides instead of giving up on the track.

## [1.3.0] - 2026-06-24

### Added
- Full Android TV support.

## [1.2.0] - 2026-06-11

### Added
- In-app version display with an update check.

## [1.1.0] - 2026-06-10

### Added
- Start/stop control for the receiver from Settings and the notification, so you can
  take it off the network without force-quitting the app.

## [1.0.0] - 2026-06-08

### Added
- Spotify Connect receiver with zero-config discovery and direct, high-bitrate
  playback through the native AAudio backend.
- Ambient now-playing screen with album-art color wash, synced lyrics, and an idle
  clock face.
- Transport controls, live receiver rename, selectable bitrate (96 / 160 / 320 kbps),
  a fullscreen mode, and a 12/24-hour clock.

[Unreleased]: https://github.com/SerafiniJose/rusty/compare/v2.4.0...HEAD
[2.4.0]: https://github.com/SerafiniJose/rusty/compare/v2.3.0...v2.4.0
[2.3.0]: https://github.com/SerafiniJose/rusty/compare/v2.2.0...v2.3.0
[2.2.0]: https://github.com/SerafiniJose/rusty/compare/v2.1.0...v2.2.0
[2.1.0]: https://github.com/SerafiniJose/rusty/compare/v2.0.0...v2.1.0
[2.0.0]: https://github.com/SerafiniJose/rusty/compare/v1.3.1...v2.0.0
[1.3.1]: https://github.com/SerafiniJose/rusty/compare/v1.3.0...v1.3.1
[1.3.0]: https://github.com/SerafiniJose/rusty/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/SerafiniJose/rusty/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/SerafiniJose/rusty/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/SerafiniJose/rusty/releases/tag/v1.0.0
