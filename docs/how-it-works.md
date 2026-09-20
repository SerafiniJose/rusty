# How it works

```mermaid
flowchart TD
    client["Spotify client<br/>phone · desktop · web — same Wi-Fi"]
    client -- "Connect / zeroconf" --> shell

    subgraph shell["Rusty · feature shell — Kotlin (HomeActivity)"]
        direction LR
        spotify["Spotify<br/>now playing · lyrics · idle"]
        dlna["DLNA player<br/>TTS / radio from Home Assistant"]
        screensaver["Screensaver<br/>Clock · OLED · Canvas · Immich Slideshow"]
        homeassistant["Home Assistant<br/>kiosk WebView → your instance"]
    end

    spotify -- "JNI (Spotify feature only)" --> core["Rust core — librespot 0.8<br/>session · player · audio backend"]
```

- The app is a small **feature shell** (`HomeActivity`) that hosts switchable, full-screen
  features — the **Spotify** receiver, the **screensaver**, and **Home Assistant** — under one
  shared chrome (clock, settings, on-screen launcher).
- **Kotlin** (`app/`) handles the UI, the foreground service, network advertising, and the
  now-playing / lyrics / settings / screensaver screens. Home Assistant is a kiosk **WebView**
  pointed at your own instance — no Rust involved.
- **Rust** (`rust/`) wraps [librespot](https://github.com/librespot-org/librespot) 0.8 and exposes
  a small JNI surface (`NativeBridge`) for session lifecycle, transport, token retrieval, and
  rename — used only by the Spotify feature.

---

[← Back to the README](../README.md)
