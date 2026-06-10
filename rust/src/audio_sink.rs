//! Route-following audio sink.
//!
//! ## Why this exists
//!
//! On Android, cpal 0.16 (pulled in via rodio) uses a native **AAudio** backend.
//! `default_output_device()` returns the *unspecified* device, so a stream opens on
//! AAudio's default output and correctly targets whatever route is active **at open
//! time** (built-in speaker, Bluetooth A2DP, wired headset…).
//!
//! The catch is what AAudio does on a *route change*. Per the AAudio contract a
//! stream is **disconnected** the moment its device "is no longer the highest
//! priority audio device" — i.e. exactly when a Bluetooth sink connects mid-playback
//! (BT outranks the speaker), or a headset is (un)plugged. A disconnected output
//! stream silently swallows writes: no audio, no error shown to the user.
//!
//! librespot builds its sink **once per session** (`Player::new` takes the sink
//! builder as an `FnOnce`) and its stock `RodioSink` never reopens the stream, while
//! this app does no Android-side route handling. The result was the reported bug:
//! playing on the tablet speaker, connecting Bluetooth killed the audio, and only a
//! force-close + relaunch (which rebuilds the player) recovered it.
//!
//! ## What this does
//!
//! `RouteFollowingSink` reuses the same rodio output path (so rodio still handles
//! buffering, mixing and resampling) but registers a cpal **stream error callback**.
//! AAudio surfaces the disconnect through that callback (`AAUDIO_ERROR_DISCONNECTED`
//! → `cpal::StreamError::DeviceNotAvailable`), which sets an atomic flag. The next
//! `write`/`start` on the player thread tears the dead stream down and rebuilds it
//! via `default_output_device()`, which re-binds to the *now-active* route. The
//! Spirc session, player and playback position all stay intact across the switch.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

use librespot::playback::audio_backend::{Sink, SinkError, SinkResult};
use librespot::playback::convert::Converter;
use librespot::playback::decoder::AudioPacket;

use log::{error, info, warn};

use rodio::cpal;
use rodio::cpal::traits::{DeviceTrait, HostTrait};
use rodio::OutputStream;

/// librespot decodes and normalises to interleaved stereo `f32` at 44.1 kHz (its
/// `NUM_CHANNELS` / `SAMPLE_RATE`). We feed rodio at the same rate and let AAudio
/// resample to the device's native rate.
const NUM_CHANNELS: u16 = 2;
const SAMPLE_RATE: u32 = 44_100;

/// Backpressure threshold, mirroring librespot's `RodioSink`: appended chunks
/// average ~1628 samples, so ~27 queued chunks ≈ 44_100 frames ≈ 0.5 s buffered.
const MAX_BUFFERED_CHUNKS: usize = 26;

pub struct RouteFollowingSink {
    /// `None` until the first (re)build succeeds. The rodio `Sink` and its backing
    /// `OutputStream` are kept together so they are dropped as a unit on rebuild and
    /// teardown.
    active: Option<(rodio::Sink, OutputStream)>,
    /// Set by the cpal error callback (AAudio disconnect on a route change) and
    /// observed on the player thread to trigger an in-place stream rebuild.
    reopen: Arc<AtomicBool>,
}

impl RouteFollowingSink {
    pub fn new() -> Self {
        let mut sink = Self {
            active: None,
            reopen: Arc::new(AtomicBool::new(false)),
        };
        // Best-effort initial open. If the output device is momentarily unavailable
        // we retry on the first write rather than panicking the player thread.
        sink.ensure_stream();
        sink
    }

    /// (Re)builds the rodio output stream if we have none yet, or a route change has
    /// flagged the current one as dead. Opening picks the active default route.
    fn ensure_stream(&mut self) {
        if self.active.is_some() && !self.reopen.load(Ordering::SeqCst) {
            return;
        }

        // Clear the flag *before* (re)building: the old stream is dropped first (so its
        // error callback can't fire again), and the new stream's callback is only wired
        // once `build_stream` returns — so a disconnect arriving during the rebuild is
        // never lost, it just re-flags and we rebuild again on the next write.
        self.reopen.store(false, Ordering::SeqCst);

        // Drop the old (dead) stream first so AAudio releases it before we open the
        // replacement on the new route.
        self.active = None;

        match build_stream(self.reopen.clone()) {
            Ok((sink, stream)) => {
                // A freshly built sink must be playing: on a mid-session rebuild
                // librespot does not call start() again.
                sink.play();
                info!("Audio output (re)opened on the active route");
                self.active = Some((sink, stream));
            }
            Err(e) => {
                // Leave the flag set so the next write retries the open.
                self.reopen.store(true, Ordering::SeqCst);
                error!("Failed to open audio output stream: {e}");
            }
        }
    }
}

impl Sink for RouteFollowingSink {
    fn start(&mut self) -> SinkResult<()> {
        self.ensure_stream();
        if let Some((sink, _)) = &self.active {
            sink.play();
        }
        Ok(())
    }

    fn stop(&mut self) -> SinkResult<()> {
        if let Some((sink, _)) = &self.active {
            // Only drain when the stream is healthy. A route change that killed the
            // stream stops the AAudio callback from pulling, so `sleep_until_end`
            // would block forever — skip the drain and just pause in that case.
            if !self.reopen.load(Ordering::SeqCst) {
                sink.sleep_until_end();
            }
            sink.pause();
        }
        Ok(())
    }

    fn write(&mut self, packet: AudioPacket, converter: &mut Converter) -> SinkResult<()> {
        self.ensure_stream();
        let Some((sink, _)) = &self.active else {
            // No usable output right now (device temporarily gone). Drop this packet
            // instead of erroring so the player keeps running; we retry next write.
            return Ok(());
        };

        let samples = packet
            .samples()
            .map_err(|e| SinkError::OnWrite(e.to_string()))?;
        let samples_f32: &[f32] = &converter.f64_to_f32(samples);
        let source = rodio::buffer::SamplesBuffer::new(NUM_CHANNELS, SAMPLE_RATE, samples_f32);
        sink.append(source);

        // Backpressure: wait for rodio to drain a bit, but bail the moment a route
        // change flags a rebuild so we never spin on a dead stream's queue (which
        // never drains). The next write then rebuilds onto the new route.
        while sink.len() > MAX_BUFFERED_CHUNKS && !self.reopen.load(Ordering::SeqCst) {
            thread::sleep(Duration::from_millis(10));
        }
        Ok(())
    }
}

/// Records a stream error (most importantly the AAudio disconnect that arrives as
/// `DeviceNotAvailable` on a route change) by flagging the stream for rebuild.
fn on_stream_error(e: cpal::StreamError, reopen: &AtomicBool) {
    warn!("Audio stream error ({e}); will reopen on the active route");
    reopen.store(true, Ordering::SeqCst);
}

/// Builds a rodio output stream on the current default route, wiring a cpal error
/// callback that flags the stream for rebuild when AAudio disconnects it.
fn build_stream(reopen: Arc<AtomicBool>) -> Result<(rodio::Sink, OutputStream), String> {
    let host = cpal::default_host();
    let device = host
        .default_output_device()
        .ok_or_else(|| "no default output device".to_string())?;

    // Prefer native stereo 44.1 kHz (matches our feed, no rodio resampling); fall
    // back to the device default rate (rodio/AAudio resample), mirroring librespot.
    let default_config = device.default_output_config().map_err(|e| e.to_string())?;
    let config = device
        .supported_output_configs()
        .map_err(|e| e.to_string())?
        .find(|c| c.channels() == NUM_CHANNELS)
        .and_then(|c| {
            c.try_with_sample_rate(cpal::SampleRate(SAMPLE_RATE))
                .or_else(|| c.try_with_sample_rate(default_config.sample_rate()))
        })
        .unwrap_or(default_config);

    let stream = {
        let flag = reopen.clone();
        rodio::OutputStreamBuilder::default()
            .with_device(device.clone())
            .with_config(&config.config())
            .with_sample_format(cpal::SampleFormat::F32)
            .with_error_callback(move |e: cpal::StreamError| on_stream_error(e, &flag))
            .open_stream()
    };

    let mut stream = match stream {
        Ok(stream) => stream,
        Err(e) => {
            warn!("exact audio config failed ({e}); falling back to device default");
            let flag = reopen.clone();
            rodio::OutputStreamBuilder::from_device(device)
                .map_err(|e| e.to_string())?
                .with_error_callback(move |e: cpal::StreamError| on_stream_error(e, &flag))
                .open_stream_or_fallback()
                .map_err(|e| e.to_string())?
        }
    };

    // Teardown of a dead stream is routine here (every route change), so silence
    // rodio's drop-time logging.
    stream.log_on_drop(false);
    let sink = rodio::Sink::connect_new(stream.mixer());
    Ok((sink, stream))
}
