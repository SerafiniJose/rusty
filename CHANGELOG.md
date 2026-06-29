# Changelog

All notable changes to Rusty are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The section matching each release tag is published as that release's notes and is
what the app shows under **About & updates → What's new**, so keep entries short and
user-facing.

## [Unreleased]

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

[Unreleased]: https://github.com/SerafiniJose/rusty/compare/v2.0.0...HEAD
[2.0.0]: https://github.com/SerafiniJose/rusty/compare/v1.3.1...v2.0.0
[1.3.1]: https://github.com/SerafiniJose/rusty/compare/v1.3.0...v1.3.1
[1.3.0]: https://github.com/SerafiniJose/rusty/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/SerafiniJose/rusty/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/SerafiniJose/rusty/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/SerafiniJose/rusty/releases/tag/v1.0.0
