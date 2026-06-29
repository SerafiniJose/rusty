# Screenshots

Referenced from the top-level [`README.md`](../README.md). Captured on an Amazon Echo Show 8
(1280×800) running the receiver, with **Fullscreen** enabled so the system bars are hidden. All
content is **synthetic** — the cover is a generated gradient and the track, artist, listener and
lyrics are placeholders (no copyrighted material); the Home Assistant shot uses the public Home
Assistant demo.

| File | Shows |
| --- | --- |
| `now-playing.png` | Now-playing screen — album-art wash, accent color, transport |
| `lyrics.png` | Synced lyrics, active line highlighted |
| `screensaver-clock.png` | Screensaver Clock face (idle) |
| `screensaver-oled.png` | Screensaver OLED-burn-in-safe minimal face |
| `home-assistant.png` | Home Assistant dashboard in the WebView (public demo) |
| `launcher.png` | Expanded on-screen launcher (Spotify / Home Assistant pills) |
| `settings.png` | Tabbed settings (General / Screensaver / Spotify / Home Assistant) |
| `session.png` | Session & receiver health sheet |

Capture tip: enable **Fullscreen** in Settings → General, then
`adb exec-out screencap -p > screenshots/<name>.png`.
