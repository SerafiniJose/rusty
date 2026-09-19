//! Android `AudioTrack` audio sink.
//!
//! ## Why this exists
//!
//! The previous sink drove `rodio` (and therefore cpal, and therefore **AAudio**).
//! That path has two Android-specific problems:
//!
//! 1. **Route changes.** An AAudio stream binds to one device at open time. When
//!    that device stops being the highest-priority output (headset plugged in, BT
//!    connected) Android *disconnects* the stream rather than migrating it, and a
//!    disconnected stream silently swallows writes. `audio_sink` worked around this
//!    by watching cpal's error callback and rebuilding the stream by hand.
//! 2. **No control over stream attributes.** cpal pins `ndk` to `api-level-26`, so
//!    `AAudioStreamBuilder_setUsage`/`setContentType`/`setPerformanceMode` are
//!    compiled out — the stream is tagged `CONTENT_TYPE_UNKNOWN` and cannot be fixed
//!    from outside cpal.
//!
//! `android.media.AudioTrack` has neither problem. AudioFlinger owns device
//! selection and migrates an existing track across a route change without telling
//! the app, which is why `MediaPlayer`-based players (and Spotify itself) need no
//! route handling at all. Attributes are set at construction.
//!
//! ## Shape
//!
//! Only **framework** classes are touched (`android.media.*`), never app classes, so
//! `FindClass` works from any thread without a cached ClassLoader. The librespot
//! player thread is attached to the JVM permanently on first use.
//!
//! `AudioTrack.write(..., WRITE_BLOCKING)` blocks until the track has room, which is
//! exactly the backpressure the rodio sink emulated with a `sleep(10ms)` poll loop.

use jni::objects::{GlobalRef, JShortArray, JValue};
use jni::{JNIEnv, JavaVM};
use librespot::playback::audio_backend::{Sink, SinkError, SinkResult};
use librespot::playback::convert::Converter;
use librespot::playback::decoder::AudioPacket;
use log::{info, warn};

/// librespot decodes and normalises to interleaved stereo at 44.1 kHz.
const SAMPLE_RATE: i32 = 44_100;
const NUM_CHANNELS: i32 = 2;

// android.media.AudioFormat
const CHANNEL_OUT_STEREO: i32 = 12;
const ENCODING_PCM_16BIT: i32 = 2;
// android.media.AudioAttributes
const USAGE_MEDIA: i32 = 1;
const CONTENT_TYPE_MUSIC: i32 = 2;
// android.media.AudioTrack
const MODE_STREAM: i32 = 1;
const WRITE_BLOCKING: i32 = 0;

/// Multiple of `getMinBufferSize` to request. The HAL runs a 1536-frame period on
/// this class of device (~32 ms); a few bursts of slack keeps a late decode from
/// being audible without adding meaningful latency to a Connect receiver.
const BUFFER_BURSTS: i32 = 4;

fn err<E: std::fmt::Display>(e: E) -> String {
    e.to_string()
}

/// Attach the calling thread to the JVM and hand back a `JNIEnv`.
///
/// librespot calls `write` from a single long-lived player thread, so the
/// attachment is made permanent: repeated calls are then just a TLS lookup.
fn jni_env() -> Result<JNIEnv<'static>, String> {
    // The `JavaVM` must outlive every `JNIEnv` handed out, so it is cached rather
    // than rebuilt per call.
    static VM: std::sync::OnceLock<JavaVM> = std::sync::OnceLock::new();
    if VM.get().is_none() {
        let ctx = ndk_context::android_context();
        let vm = unsafe { JavaVM::from_raw(ctx.vm().cast()) }.map_err(err)?;
        let _ = VM.set(vm);
    }
    VM.get()
        .ok_or_else(|| "JavaVM unavailable".to_string())?
        .attach_current_thread_permanently()
        .map_err(err)
}

pub struct AudioTrackSink {
    /// `android.media.AudioTrack`, `None` until the first successful build.
    track: Option<GlobalRef>,
    /// Reused `short[]` staging array handed to `AudioTrack.write`.
    staging: Option<GlobalRef>,
    staging_len: usize,
    playing: bool,
}

impl AudioTrackSink {
    pub fn new() -> Self {
        Self {
            track: None,
            staging: None,
            staging_len: 0,
            playing: false,
        }
    }

    /// Builds the `AudioTrack` if it is not up yet. Failures are logged and retried
    /// on the next write rather than killing the player thread.
    fn ensure_track(&mut self) {
        if self.track.is_some() {
            return;
        }
        match Self::build_track() {
            Ok(track) => {
                info!("AudioTrack sink opened ({SAMPLE_RATE} Hz, {NUM_CHANNELS} ch, PCM16)");
                self.track = Some(track);
                self.playing = false;
            }
            Err(e) => warn!("AudioTrack build failed ({e}); retrying on next write"),
        }
    }

    fn build_track() -> Result<GlobalRef, String> {
        let mut env = jni_env()?;

        // AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        let track_cls = env.find_class("android/media/AudioTrack").map_err(err)?;
        let min = env
            .call_static_method(
                &track_cls,
                "getMinBufferSize",
                "(III)I",
                &[
                    JValue::Int(SAMPLE_RATE),
                    JValue::Int(CHANNEL_OUT_STEREO),
                    JValue::Int(ENCODING_PCM_16BIT),
                ],
            )
            .and_then(|v| v.i())
            .map_err(err)?;
        if min <= 0 {
            return Err(format!("getMinBufferSize returned {min}"));
        }
        let buffer_bytes = min * BUFFER_BURSTS;

        // new AudioAttributes.Builder().setUsage(MEDIA).setContentType(MUSIC).build()
        let attrs = {
            let cls = env
                .find_class("android/media/AudioAttributes$Builder")
                .map_err(err)?;
            let b = env.new_object(&cls, "()V", &[]).map_err(err)?;
            let b = env
                .call_method(
                    &b,
                    "setUsage",
                    "(I)Landroid/media/AudioAttributes$Builder;",
                    &[JValue::Int(USAGE_MEDIA)],
                )
                .and_then(|v| v.l())
                .map_err(err)?;
            let b = env
                .call_method(
                    &b,
                    "setContentType",
                    "(I)Landroid/media/AudioAttributes$Builder;",
                    &[JValue::Int(CONTENT_TYPE_MUSIC)],
                )
                .and_then(|v| v.l())
                .map_err(err)?;
            env.call_method(&b, "build", "()Landroid/media/AudioAttributes;", &[])
                .and_then(|v| v.l())
                .map_err(err)?
        };

        // new AudioFormat.Builder().setEncoding(..).setSampleRate(..).setChannelMask(..).build()
        let format = {
            let cls = env
                .find_class("android/media/AudioFormat$Builder")
                .map_err(err)?;
            let b = env.new_object(&cls, "()V", &[]).map_err(err)?;
            let b = env
                .call_method(
                    &b,
                    "setEncoding",
                    "(I)Landroid/media/AudioFormat$Builder;",
                    &[JValue::Int(ENCODING_PCM_16BIT)],
                )
                .and_then(|v| v.l())
                .map_err(err)?;
            let b = env
                .call_method(
                    &b,
                    "setSampleRate",
                    "(I)Landroid/media/AudioFormat$Builder;",
                    &[JValue::Int(SAMPLE_RATE)],
                )
                .and_then(|v| v.l())
                .map_err(err)?;
            let b = env
                .call_method(
                    &b,
                    "setChannelMask",
                    "(I)Landroid/media/AudioFormat$Builder;",
                    &[JValue::Int(CHANNEL_OUT_STEREO)],
                )
                .and_then(|v| v.l())
                .map_err(err)?;
            env.call_method(&b, "build", "()Landroid/media/AudioFormat;", &[])
                .and_then(|v| v.l())
                .map_err(err)?
        };

        // new AudioTrack.Builder()... .build()
        let cls = env
            .find_class("android/media/AudioTrack$Builder")
            .map_err(err)?;
        let b = env.new_object(&cls, "()V", &[]).map_err(err)?;
        let b = env
            .call_method(
                &b,
                "setAudioAttributes",
                "(Landroid/media/AudioAttributes;)Landroid/media/AudioTrack$Builder;",
                &[JValue::Object(&attrs)],
            )
            .and_then(|v| v.l())
            .map_err(err)?;
        let b = env
            .call_method(
                &b,
                "setAudioFormat",
                "(Landroid/media/AudioFormat;)Landroid/media/AudioTrack$Builder;",
                &[JValue::Object(&format)],
            )
            .and_then(|v| v.l())
            .map_err(err)?;
        let b = env
            .call_method(
                &b,
                "setBufferSizeInBytes",
                "(I)Landroid/media/AudioTrack$Builder;",
                &[JValue::Int(buffer_bytes)],
            )
            .and_then(|v| v.l())
            .map_err(err)?;
        let b = env
            .call_method(
                &b,
                "setTransferMode",
                "(I)Landroid/media/AudioTrack$Builder;",
                &[JValue::Int(MODE_STREAM)],
            )
            .and_then(|v| v.l())
            .map_err(err)?;
        let track = env
            .call_method(&b, "build", "()Landroid/media/AudioTrack;", &[])
            .and_then(|v| v.l())
            .map_err(err)?;

        info!("AudioTrack buffer {buffer_bytes} bytes (min {min} x {BUFFER_BURSTS})");
        env.new_global_ref(track).map_err(err)
    }

    /// Calls a no-arg void method on the track, logging rather than propagating.
    fn track_call(&mut self, name: &'static str) {
        let Some(track) = self.track.as_ref() else {
            return;
        };
        let Ok(mut env) = jni_env() else { return };
        if let Err(e) = env.call_method(track.as_obj(), name, "()V", &[]) {
            warn!("AudioTrack.{name}() failed: {e}");
            let _ = env.exception_clear();
        }
    }

    /// Grows the reused `short[]` when a packet needs more room.
    fn ensure_staging(&mut self, env: &mut JNIEnv, len: usize) -> Result<(), String> {
        if self.staging.is_some() && self.staging_len >= len {
            return Ok(());
        }
        let arr = env.new_short_array(len as i32).map_err(err)?;
        self.staging = Some(env.new_global_ref(&arr).map_err(err)?);
        self.staging_len = len;
        Ok(())
    }
}

impl Sink for AudioTrackSink {
    fn start(&mut self) -> SinkResult<()> {
        self.ensure_track();
        if !self.playing {
            self.track_call("play");
            self.playing = true;
        }
        Ok(())
    }

    fn stop(&mut self) -> SinkResult<()> {
        if self.playing {
            self.track_call("pause");
            self.track_call("flush");
            self.playing = false;
        }
        Ok(())
    }

    fn write(&mut self, packet: AudioPacket, converter: &mut Converter) -> SinkResult<()> {
        self.ensure_track();
        if self.track.is_none() {
            // No output right now; drop the packet rather than erroring so the
            // player keeps running and we retry on the next write.
            return Ok(());
        }

        let samples = packet
            .samples()
            .map_err(|e| SinkError::OnWrite(e.to_string()))?;
        // Straight to the HAL's native format, with librespot's TPDF dither, instead
        // of f32 -> AudioFlinger's undithered s16 conversion.
        let pcm: Vec<i16> = converter.f64_to_s16(samples);
        if pcm.is_empty() {
            return Ok(());
        }

        let mut env = jni_env().map_err(SinkError::OnWrite)?;
        self.ensure_staging(&mut env, pcm.len())
            .map_err(SinkError::OnWrite)?;

        let staging = self.staging.as_ref().expect("staging just ensured").clone();
        let arr: &JShortArray = staging.as_obj().into();
        env.set_short_array_region(arr, 0, &pcm)
            .map_err(|e| SinkError::OnWrite(e.to_string()))?;

        let track = self.track.as_ref().expect("track checked above").clone();
        let written = env
            .call_method(
                track.as_obj(),
                "write",
                "([SIII)I",
                &[
                    JValue::Object(staging.as_obj()),
                    JValue::Int(0),
                    JValue::Int(pcm.len() as i32),
                    JValue::Int(WRITE_BLOCKING),
                ],
            )
            .and_then(|v| v.i())
            .map_err(|e| SinkError::OnWrite(e.to_string()))?;

        if written < 0 {
            // Negative return values are AudioTrack error codes (ERROR_INVALID_OPERATION,
            // ERROR_DEAD_OBJECT, …). Drop the track so the next write rebuilds it.
            warn!("AudioTrack.write returned {written}; rebuilding track");
            self.track = None;
            self.playing = false;
        }
        Ok(())
    }
}

impl Drop for AudioTrackSink {
    fn drop(&mut self) {
        self.track_call("stop");
        self.track_call("release");
    }
}
