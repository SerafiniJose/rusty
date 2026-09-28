# Screenshots

Referenced from the top-level [`README.md`](../README.md). Captured on an Amazon Echo Show 8
(1280×800) running the receiver, with **Fullscreen** enabled so the system bars are hidden —
except `services-status.png`, which is from a Lenovo Tab M10 (1920×1200) because the page is
taller than an 800 px screen. All
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
| `settings.png` | Tabbed settings, on the Voice tab |
| `services-status.png` | Services & status — every service and feature with its state, and the version chip |
| `cameras.png` | Camera wall with four illustrated test scenes |
| `control-page.png` | Remote control page in a desktop browser, Lock source selected |
| `control-page-phone.png` | The same page at phone width |

Capture tip: enable **Fullscreen** in Settings → General, then
`adb exec-out screencap -p > screenshots/<name>.png`.
