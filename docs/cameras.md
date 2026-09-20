# Cameras

Turn the feature on in **Settings → General → Cameras**, then open **Settings → Cameras** to add
them. **Scan this Wi-Fi network** finds ONVIF cameras and fills in their stream for you; a camera
on another subnet can be added **by address** (ONVIF on port 8000 or 80); anything else takes an
`rtsp://` URL **by hand**. **Test** probes each stream and names the codec, so you know the device
can decode it before you save. Usernames and passwords are kept in Rusty's encrypted store, never
in the stream URL.

The wall shows a still per camera, taken from the camera's snapshot URL when it has one and
otherwise grabbed from the stream. Pick **All in one view**, where tiles shrink to fit, or
**Pages of 4, 6 or 8** with bigger tiles, flipped with ◀ ▶ or on a timer. The refresh runs one
camera at a time and starts at 30 s: grabbing a frame from a stream costs a few seconds of
decoding, so a faster setting would keep the device busy without showing you more. Tap a tile or
press OK for the live view: sound if the camera has it, a **SUB | MAIN** switch when a
high-resolution stream is set, and a snapshot button that saves to Pictures/Rusty.

A live view with sound pauses Spotify while it is up and resumes it afterwards. Streams use RTSP
over TCP by default; turn **Force TCP** off per camera only if yours needs UDP.

## Share a Rusty device's camera

A Rusty device with a camera can stream it to the others. On that device, turn on **Remote
control** first (**Settings → General → Remote control**) if it isn't already, then turn on
**Share this camera** in **Settings → Cameras** — until Remote control is on, that switch stays
disabled with a reminder why. Android asks for camera permission the first time you turn it on.

On any other Rusty, **Add camera → Scan this Wi-Fi network** lists it under **Rusty devices**; tap
it, enter the sharing device's Remote control password if it has one, and save. Most won't: no
password is the default here as it is everywhere else in Remote control, and that leaves the
stream open to anyone on your local network. The stream itself is plain RTSP at
`rtsp://<device-ip>:8554/live` (user `rusty`), so Home Assistant or VLC can open it directly too.
The camera only switches on while someone is watching, and an amber camera glyph shows on the
sharing device's screen for as long as a viewer stays connected.

The share doesn't need the device's own screen once it's set up: the control page carries a
**Camera share** card that starts and stops it and hands you the `rtsp://` URL to copy.

---

[← Back to the README](../README.md)
