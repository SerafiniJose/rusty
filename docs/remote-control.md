# Remote control

Rusty can serve a small control page — and the HTTP API behind it — from the device itself, so
you can drive the screen from another room without walking over to it.

**It is off by default.** Turn it on in **Settings → General → Remote control**. The same row
then shows the address to open, something like `http://192.168.1.42:8765/`. Type that into any
browser on the same network — or, to skip the typing, open **Settings → Remote Control →
Control page** and press **Show QR**, then scan the code with a phone. The code carries the plain
URL and never the password; the page asks for that itself. Either way you get a single page with:

- **Source** — four lamps for the four things Rusty can be showing: Spotify, Home Assistant,
  DLNA and the lock screen. Tap one and the device switches to it. A lamp only lights once the
  device confirms the switch, so a command that didn't land never looks like it did; a feature
  you've switched off in Settings stays in place, struck through, rather than disappearing.
  Switching needs Rusty to be on screen — if it isn't, the row says so instead of pretending.
- **Cameras** — when the Cameras feature is on, the Source row gains a Camera lamp and, while it
  is lit, a strip of your cameras: tap one to show it full screen on the device, or **Grid** to
  go back to the wall. With the password on, the API also serves each camera's latest still at
  `/api/camera/<id>/snapshot`.
- **Camera share** — on a device that can share its own camera, a card to switch the share on or
  off, the lens and quality chips, and the `rtsp://` URL with a copy button for pasting into VLC
  or Home Assistant. Devices without a camera don't show the card at all. Starting the share
  needs Rusty to be on screen, and the card says so rather than failing quietly — as it does if
  Android has refused the camera permission.
- **On screen** — a switch that brings Rusty's window to the front, or sends it out of the way to
  whatever's behind it. Bringing it forward wakes the display first, so it works on a sleeping
  panel. Both directions need Rusty to hold Android's **"Display over other apps"** permission —
  and the switch is deliberately dead in *both* directions without it, because sending Rusty away
  when it can't come back would leave a touch-free screen with no way home.
- **Lock screen theme** — pick Clock, OLED, Canvas or Slideshow. This one works even when Rusty
  isn't in the foreground, because it's a saved preference: a lock screen that appears later
  uses it, and one that's already up swaps instantly.
- **Screen** — on/off and a brightness slab you can drag anywhere on. "Off" is a full-screen
  black overlay that keeps the panel awake, so turning it back on is instant; touching the device
  (or pressing any remote key) also wakes it.
- **Volume** — the media volume slab. Hidden on devices whose volume is fixed (some TVs and
  docks).
- **Announce** — type a message and the device says it out loud, in the voice picked from the
  same list as **Settings → Remote control** (downloadable voices included). Spotify pauses or
  fades while it speaks and resumes afterwards. Nothing else has to be running for this: no DLNA
  player, no Home Assistant — Rusty does the speaking itself.
- **Slideshow sources** — the same album / person / tag checklists as the in-app picker, so you
  can re-aim the photo frame from the sofa. Collapsed under **Service**, since it's a setup task
  rather than something you do daily.
- **Software** — see whether a newer Rusty release exists and start the download from your sofa,
  also under **Service**. The device fetches the APK itself and hands it to Android's installer;
  **Android always asks for confirmation on the device screen** (a sideloaded app can't update
  itself silently), so the last step is one OK on the device — by touch or D-pad. The very first
  time, Android also shows a one-time **"allow installs from this source"** screen for Rusty.

The port is fixed at **8765**. Nothing needs to be installed on the other device — it's one
self-contained page, no accounts, no cloud.

## The "Allow system brightness" row

Under the toggle you may see a row asking to allow system brightness. It opens Android's **Modify
system settings** screen for Rusty. Granting it lets the brightness slider move the **device's
real display brightness**; without it, Rusty can only dim its own window, which looks the same
from across the room but doesn't affect anything else on screen. The control page tells you which
mode is in effect. It is entirely optional, and Remote control works without it. While the grant is
missing the Remote control switch itself shows **amber** rather than green — the service is running
and everything else works; the amber only flags the unclaimed brightness permission.

## Home Assistant

While Remote control is on, Rusty advertises itself as `_rusty._tcp` over mDNS so a Home
Assistant integration can discover it on the network and expose the screen, volume, playing
state and the active panel as entities. That integration is a separate project; Rusty itself needs no configuration
for it beyond the toggle.

## Security — please read before enabling

The API and control page are **open by default**: no password, PIN or token. That is a deliberate
choice for a device that lives on a home network, and you can change it. **Settings → Remote
Control → Require password** sets a password that every request must carry (the control page asks
for it once and remembers it in that browser; scripts send it as
`Authorization: Bearer <password>`). Stills of a camera you've added are only served over the
API while the password is on; the one exception is a camera this device shares itself, which
follows its own **Share this camera** switch instead (see
[Share a Rusty device's camera](cameras.md#share-a-rusty-devices-camera)). With the password off, this is
what "open" means:

- **Any client on your local network can control this device**: switch the screen on or off,
  change brightness and media volume, and change the Slideshow filters. It can also **read the
  names of your Immich albums, people and tags** (names only — no photos are served through this
  API, and your Immich API key never leaves the device). Camera names are visible too, though
  stills are refused without the password. It can also start an app update —
  the worst that does is pop the system's install prompt on the device screen, because the APK
  always comes from Rusty's own GitHub Releases (the URL is pinned in the app, not taken from
  the request) and nothing installs without the on-device confirmation.
- **Any app already installed on the device** that holds the `INTERNET` permission can do the
  same, because `localhost`/`127.0.0.1` are deliberately accepted as valid hosts (that is what
  makes `adb forward` debugging work). This isn't a new class of exposure — a local app could
  already reach any server on the LAN — but it is worth knowing.
- Browser-based attacks are guarded against: Rusty validates the `Host` header (so a page on the
  public internet can't use DNS rebinding to reach it), never emits CORS headers, requires
  `Content-Type: application/json` on writes, and serves nothing but the one embedded page and
  the fixed API routes.
- **Camera share listens on TCP 8554 while it's on**: like the control API, the stream is plain
  — no TLS — and LAN-only, so don't forward port 8554 through your router either.

So: leave it off unless you want it, set the password if anyone you don't fully trust shares the
network, and don't enable it at all on a network you don't trust — a guest Wi-Fi, a shared flat,
a café. If you need it reachable from outside your home, put it behind your
own VPN rather than forwarding port 8765.

## Playback takeover

**Settings → Spotify** has two toggles, both off by default: **Switch to Spotify on playback** and
**Wake and show Rusty on playback**. Both react only to a genuine new play started from another
device — renaming the receiver, changing bitrate, or a plain pause/resume won't trigger them.

**Wake and show Rusty** is one gesture rather than a screen switch and an app switch, because the
halves are not separately useful: an app launched while the display is off may never resume, so
waking and coming forward only make sense together. It needs Android's **Display over other apps**
permission (`SYSTEM_ALERT_WINDOW`), and it is all-or-nothing — without the grant it does nothing at
all, not even the wake it could technically perform.

Turning it on without the permission opens the system grant screen directly, and until the grant
lands the switch shows **amber** rather than green, with the reason under it. On devices that ship
no such screen — common on Android TV and Fire OS builds — the toggle disables itself with an
explanation instead, since there is nothing to send you to.

Holding that permission is a documented background-activity-launch exemption on Android 10–15, but
Android 14–16 have progressively hardened background launches, and some OEM builds ignore the
exemption regardless. A blocked launch is swallowed silently by the platform — there is no way for
Rusty to detect it — so on an affected device the toggle quietly degrades to a wake plus a page
switch: the screen still lights, and the Spotify page is ready and waiting the next time you open
the app, with the existing media notification as the manual way to bring it forward.

---

[← Back to the README](../README.md)
