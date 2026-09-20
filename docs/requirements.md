# Requirements

- **Spotify Premium** — Spotify Connect requires a Premium account.
- **Android 8.0 (API 26) or newer.**
- A **64-bit (arm64-v8a)** or **32-bit ARM (armeabi-v7a)** device. (No x86 builds are shipped.)
- The receiver and the controlling Spotify client must be on the **same local network**.
- **Home Assistant mode (optional)** needs a Home Assistant instance reachable on the same local network.
- **Immich Slideshow (optional)** needs a self-hosted [Immich](https://immich.app) server reachable on the same local network, plus an API key (see below).
- **Remote control (optional)** is off by default and needs nothing but a browser on the same local network — read the [security note](remote-control.md#security--please-read-before-enabling) before enabling it.

> Tested on an Amazon Echo Show 8 running LineageOS 18.1 (Android 11) and on a Lenovo Tab M10 (TB-X606FA).

## Immich API key permissions

Create the key in Immich under **Account settings → API keys**, and grant it these
read permissions:

```
album.read
album.statistics
asset.view
asset.read
asset.statistics
face.read
memory.read
person.read
person.statistics
tag.read
user.read
```

---

[← Back to the README](../README.md)
