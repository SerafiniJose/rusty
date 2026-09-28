//! AudioTrack-backed audio sink.
//!
//! ## Why this exists
//!
//! Until 2.7.0 the player fed rodio → cpal → AAudio. That path had no knob for buffer depth,
//! performance mode or underrun accounting; rodio silently inserted ~11.6 ms silence fillers
//! when its ~0.5 s queue ran dry (a literal micro-cut), and a hand-rolled reopen state machine
//! chased AAudio's disconnect-on-route-change behaviour.
//!
//! The AAudio path also had two Android-specific problems of its own (analysis by Pablo
//! Culebras, PR #13, who hit continuous micro-cuts on an Echo Show 5 / MT8163):
//!
//! 1. **Route changes.** An AAudio stream binds to one device at open time. When that device
//!    stops being the highest-priority output (headset plugged in, BT connected) Android
//!    *disconnects* the stream rather than migrating it, and a disconnected stream silently
//!    swallows writes — hence the hand-rolled reopen state machine above.
//! 2. **No control over stream attributes.** cpal 0.16 reaches AAudio through `ndk` 0.9 pinned
//!    to `api-level-26`, which compiles out `AAudioStreamBuilder_setUsage` / `setContentType` /
//!    `setPerformanceMode`, so the stream was opened as `CONTENT_TYPE_UNKNOWN` and could not be
//!    fixed from outside cpal.
//!
//! The exact failure inside cpal/AAudio was never isolated, so this removes a layer rather than
//! fixing a diagnosed bug in one. `AudioTrack` has neither problem: AudioFlinger owns device
//! selection and migrates a track across a route change without telling the app (which is why
//! `MediaPlayer`-based players, and Spotify itself, need no route handling), and the attributes
//! are set at construction. It has also existed since API 1, so it has been exercised by far
//! more vendor audio HALs than AAudio has.
//!
//! ## What this does
//!
//! `AudioTrackSink` implements librespot's [`Sink`] and pushes every packet into one
//! `dev.rusty.app.audio.PcmOutput` (a Kotlin wrapper around `android.media.AudioTrack`) over
//! JNI. `AudioTrack.write` in blocking mode IS the backpressure: the player thread simply
//! blocks until the ~1 s buffer has room, so there is no polling loop. A normal shared track is
//! re-routed by Android on Bluetooth connect/disconnect, so there is no reopen logic either.
//!
//! ## Threading
//!
//! librespot builds the sink inside the player thread (`Player::new` calls the sink builder
//! from the spawned thread) and calls `start`/`stop`/`write` from that same thread, so the
//! thread is attached to the JVM permanently on first use (a cheap `GetEnv` afterwards).
//! `FindClass` from a bare Rust thread would go through the system class loader and miss app
//! classes, so [`init`] resolves the class and method IDs once on a Java-attached thread
//! (`initAndroidContext`) and keeps them in a process-wide slot.
//!
//! Volume and ducking are NOT here: the player applies them in the sample domain before
//! `write` (see `duck.rs`), so this sink only ever sees the final samples.

use std::sync::OnceLock;
use std::time::{Duration, Instant};

use jni::objects::{GlobalRef, JFloatArray, JMethodID};
use jni::signature::{Primitive, ReturnType};
use jni::sys::{jint, jsize, jvalue};
use jni::{JNIEnv, JavaVM};

use librespot::playback::audio_backend::{Sink, SinkError, SinkResult};
use librespot::playback::convert::Converter;
use librespot::playback::decoder::AudioPacket;

use log::{info, warn};

const PCM_OUTPUT_CLASS: &str = "dev/rusty/app/audio/PcmOutput";

/// Capacity of the reused `float[]`, in samples. A librespot packet is one decoder frame — at
/// most 2048 stereo frames = 4096 samples for Vorbis — so a packet normally goes in one JNI
/// call; anything larger is chunked.
const ARRAY_CAPACITY: usize = 8192;

/// How often `AudioTrack.getUnderrunCount()` is read and logged from the write path.
pub const UNDERRUN_REPORT_PERIOD: Duration = Duration::from_secs(30);

/// JNI handles for `PcmOutput`, resolved once by [`init`]. Method IDs are process-stable and
/// `Send + Sync`; the class `GlobalRef` keeps them valid.
struct PcmOutputJni {
    vm: JavaVM,
    class: GlobalRef,
    ctor: JMethodID,
    write: JMethodID,
    play: JMethodID,
    pause: JMethodID,
    release: JMethodID,
    underrun_count: JMethodID,
}

static JNI: OnceLock<PcmOutputJni> = OnceLock::new();

/// Resolves the `PcmOutput` class and its method IDs. Must run on a Java-attached thread
/// whose class loader can see app classes (`initAndroidContext` on the service's main
/// thread). On `Err` a Java exception may be pending: the caller clears it.
pub fn init(env: &mut JNIEnv) -> Result<(), String> {
    let class = env
        .find_class(PCM_OUTPUT_CLASS)
        .map_err(|e| format!("find_class {PCM_OUTPUT_CLASS}: {e}"))?;
    let mut method = |name: &str, sig: &str| -> Result<JMethodID, String> {
        env.get_method_id(&class, name, sig)
            .map_err(|e| format!("{PCM_OUTPUT_CLASS}.{name}{sig}: {e}"))
    };
    let ctor = method("<init>", "()V")?;
    let write = method("write", "([FI)I")?;
    let play = method("play", "()V")?;
    let pause = method("pause", "()V")?;
    let release = method("release", "()V")?;
    let underrun_count = method("underrunCount", "()I")?;
    let class = env
        .new_global_ref(&class)
        .map_err(|e| format!("new_global_ref({PCM_OUTPUT_CLASS}): {e}"))?;
    let vm = env.get_java_vm().map_err(|e| format!("get_java_vm: {e}"))?;
    JNI.set(PcmOutputJni {
        vm,
        class,
        ctor,
        write,
        play,
        pause,
        release,
        underrun_count,
    })
    .map_err(|_| "PcmOutput JNI handles already initialised".to_string())
}

/// Decides when the underrun counter is read and what a reading means. Pure, so the schedule
/// and delta maths are unit-tested on the host; the sink only supplies `Instant::now()` and
/// the JNI reading.
pub struct UnderrunReporter {
    period: Duration,
    next_due: Option<Instant>,
    last_total: u32,
}

impl UnderrunReporter {
    pub fn new(period: Duration) -> Self {
        Self {
            period,
            next_due: None,
            last_total: 0,
        }
    }

    /// True when the counter should be read now (always true before the first reading).
    pub fn is_due(&self, now: Instant) -> bool {
        self.next_due.map_or(true, |due| now >= due)
    }

    /// Records a reading; returns `(new underruns since the previous reading, total)`.
    pub fn record(&mut self, now: Instant, total: u32) -> (u32, u32) {
        let delta = total.wrapping_sub(self.last_total);
        self.last_total = total;
        self.next_due = Some(now + self.period);
        (delta, total)
    }

    /// The reading failed: try again one period from now, baseline unchanged.
    pub fn defer(&mut self, now: Instant) {
        self.next_due = Some(now + self.period);
    }

    /// A freshly opened track counts from zero.
    pub fn reset(&mut self) {
        self.last_total = 0;
        self.next_due = None;
    }
}

/// Describes and clears any pending Java exception (so the next JNI call is legal) and
/// renders the failure for a `SinkError`.
fn describe_and_clear(env: &mut JNIEnv, what: &str, e: jni::errors::Error) -> String {
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
    }
    format!("PcmOutput.{what}: {e}")
}

pub struct AudioTrackSink {
    /// The `PcmOutput` instance; `None` until the first `start()` and after a write failure.
    track: Option<GlobalRef>,
    /// The reused `float[ARRAY_CAPACITY]` — a global ref, because local refs made on this
    /// permanently attached Rust thread would never be released.
    buffer: Option<GlobalRef>,
    underruns: UnderrunReporter,
}

impl AudioTrackSink {
    pub fn new() -> Self {
        Self {
            track: None,
            buffer: None,
            underruns: UnderrunReporter::new(UNDERRUN_REPORT_PERIOD),
        }
    }

    fn jni() -> SinkResult<&'static PcmOutputJni> {
        JNI.get().ok_or_else(|| {
            SinkError::NotConnected(
                "PcmOutput JNI handles not initialised (did initAndroidContext fail?)".to_string(),
            )
        })
    }

    /// The player thread's `JNIEnv`. The first call attaches the thread for its lifetime
    /// (it detaches itself on exit); later calls are a cheap `GetEnv`.
    fn env(jni: &'static PcmOutputJni) -> SinkResult<JNIEnv<'static>> {
        jni.vm
            .attach_current_thread_permanently()
            .map_err(|e| SinkError::NotConnected(format!("attach player thread to JVM: {e}")))
    }

    /// Creates the reusable array first, then the `PcmOutput` (whose constructor opens the
    /// AudioTrack), so a failure never leaves an orphaned track behind.
    fn open(&mut self, jni: &'static PcmOutputJni, env: &mut JNIEnv) -> SinkResult<()> {
        let array = env
            .new_float_array(ARRAY_CAPACITY as jsize)
            .map_err(|e| SinkError::ConnectionRefused(describe_and_clear(env, "new_float_array", e)))?;
        let buffer = env
            .new_global_ref(&array)
            .map_err(|e| SinkError::ConnectionRefused(describe_and_clear(env, "new_global_ref(array)", e)))?;
        let _ = env.delete_local_ref(array);

        // SAFETY: `ctor` was resolved from this exact class with signature "()V" in `init`.
        let object = unsafe { env.new_object_unchecked(&jni.class, jni.ctor, &[]) }
            .map_err(|e| SinkError::ConnectionRefused(describe_and_clear(env, "<init>", e)))?;
        let track = env
            .new_global_ref(&object)
            .map_err(|e| SinkError::ConnectionRefused(describe_and_clear(env, "new_global_ref(track)", e)))?;
        let _ = env.delete_local_ref(object);

        self.buffer = Some(buffer);
        self.track = Some(track);
        self.underruns.reset();
        info!("AudioTrack output opened");
        Ok(())
    }

    /// Releases the AudioTrack (if any). Never fails: a broken release is logged and forgotten.
    fn close(&mut self, jni: &'static PcmOutputJni, env: &mut JNIEnv) {
        if let Some(track) = self.track.take() {
            // SAFETY: method ID resolved from the object's class in `init`; no args, void return.
            if let Err(e) = unsafe {
                env.call_method_unchecked(&track, jni.release, ReturnType::Primitive(Primitive::Void), &[])
            } {
                warn!("{}", describe_and_clear(env, "release", e));
            }
            info!("AudioTrack output released");
        }
        self.buffer = None;
    }

    fn call_void(env: &mut JNIEnv, track: &GlobalRef, id: JMethodID, what: &str) -> Result<(), String> {
        // SAFETY: `id` is one of the "()V" methods resolved from the object's class in `init`.
        unsafe { env.call_method_unchecked(track, id, ReturnType::Primitive(Primitive::Void), &[]) }
            .map(|_| ())
            .map_err(|e| describe_and_clear(env, what, e))
    }

    /// Reads and logs the underrun counter when a report is due (every
    /// [`UNDERRUN_REPORT_PERIOD`]). Cheap on the hot path: one `Instant::now()` per packet.
    fn report_underruns(&mut self, jni: &'static PcmOutputJni, env: &mut JNIEnv, track: &GlobalRef) {
        let now = Instant::now();
        if !self.underruns.is_due(now) {
            return;
        }
        // SAFETY: "()I" method resolved from the object's class in `init`.
        let reading = unsafe {
            env.call_method_unchecked(track, jni.underrun_count, ReturnType::Primitive(Primitive::Int), &[])
        }
        .and_then(|v| v.i());
        match reading {
            Ok(total) if total >= 0 => {
                let (new, total) = self.underruns.record(now, total as u32);
                let secs = UNDERRUN_REPORT_PERIOD.as_secs();
                if new > 0 {
                    warn!("AudioTrack underruns: +{new} in the last {secs} s (total {total})");
                } else {
                    info!("AudioTrack underruns: none in the last {secs} s (total {total})");
                }
            }
            Ok(_) => self.underruns.defer(now), // -1: the Kotlin side says the track is gone
            Err(e) => {
                warn!("{}", describe_and_clear(env, "underrunCount", e));
                self.underruns.defer(now);
            }
        }
    }
}

impl Default for AudioTrackSink {
    fn default() -> Self {
        Self::new()
    }
}

impl Sink for AudioTrackSink {
    fn start(&mut self) -> SinkResult<()> {
        let jni = Self::jni()?;
        let mut env = Self::env(jni)?;
        if self.track.is_none() {
            self.open(jni, &mut env)?;
        }
        let Some(track) = self.track.clone() else {
            return Err(SinkError::NotConnected("AudioTrack output not open".to_string()));
        };
        Self::call_void(&mut env, &track, jni.play, "play").map_err(SinkError::StateChange)
    }

    /// Pause + flush on the Kotlin side (instant silence; the unplayed tail is replayed by the
    /// next `start`). Deliberately never returns `Err`: librespot's `ensure_sink_stopped`
    /// answers a stop error with `exit(1)`, which would kill the whole app process.
    fn stop(&mut self) -> SinkResult<()> {
        let Some(track) = self.track.clone() else {
            return Ok(());
        };
        let jni = match Self::jni() {
            Ok(jni) => jni,
            Err(e) => {
                warn!("stop: {e}");
                return Ok(());
            }
        };
        let mut env = match Self::env(jni) {
            Ok(env) => env,
            Err(e) => {
                warn!("stop: {e}");
                return Ok(());
            }
        };
        if let Err(e) = Self::call_void(&mut env, &track, jni.pause, "pause") {
            warn!("stop: {e}");
        }
        Ok(())
    }

    fn write(&mut self, packet: AudioPacket, converter: &mut Converter) -> SinkResult<()> {
        let samples = packet
            .samples()
            .map_err(|e| SinkError::OnWrite(e.to_string()))?;
        let samples: Vec<f32> = converter.f64_to_f32(samples);

        let jni = Self::jni()?;
        let mut env = Self::env(jni)?;
        let (track, buffer) = match (&self.track, &self.buffer) {
            (Some(track), Some(buffer)) => (track.clone(), buffer.clone()),
            _ => return Err(SinkError::NotConnected("AudioTrack output not open".to_string())),
        };
        let array: &JFloatArray = <&JFloatArray>::from(buffer.as_obj());

        for chunk in samples.chunks(ARRAY_CAPACITY) {
            env.set_float_array_region(array, 0, chunk)
                .map_err(|e| SinkError::OnWrite(describe_and_clear(&mut env, "SetFloatArrayRegion", e)))?;
            let args = [jvalue { l: array.as_raw() }, jvalue { i: chunk.len() as jint }];
            // SAFETY: "([FI)I" resolved from the object's class in `init`; args match the descriptor.
            let written = unsafe {
                env.call_method_unchecked(&track, jni.write, ReturnType::Primitive(Primitive::Int), &args)
            }
            .and_then(|v| v.i())
            .map_err(|e| SinkError::OnWrite(describe_and_clear(&mut env, "write", e)))?;
            if written < 0 {
                // ERROR_DEAD_OBJECT (audioserver restart) or ERROR_INVALID_OPERATION (track
                // released under us). Drop the track; the player pauses on this error and the
                // next start() opens a fresh one.
                self.close(jni, &mut env);
                return Err(SinkError::OnWrite(format!(
                    "AudioTrack.write returned {written}; output closed, reopens on the next start"
                )));
            }
        }

        self.report_underruns(jni, &mut env, &track);
        Ok(())
    }
}

impl Drop for AudioTrackSink {
    /// Runs on the player thread when `PlayerInternal` is dropped (session teardown, account
    /// takeover, bitrate change). Releases the AudioTrack so no track outlives its session.
    fn drop(&mut self) {
        if self.track.is_none() {
            return;
        }
        if let Ok(jni) = Self::jni() {
            if let Ok(mut env) = Self::env(jni) {
                self.close(jni, &mut env);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::UnderrunReporter;
    use std::time::{Duration, Instant};

    #[test]
    fn first_reading_is_due_and_reports_the_baseline() {
        let mut r = UnderrunReporter::new(Duration::from_secs(30));
        let t0 = Instant::now();
        assert!(r.is_due(t0));
        assert_eq!(r.record(t0, 3), (3, 3));
    }

    #[test]
    fn not_due_again_until_the_period_elapsed() {
        let mut r = UnderrunReporter::new(Duration::from_secs(30));
        let t0 = Instant::now();
        r.record(t0, 0);
        assert!(!r.is_due(t0 + Duration::from_secs(29)));
        assert!(r.is_due(t0 + Duration::from_secs(30)));
    }

    #[test]
    fn reports_the_delta_since_the_previous_reading() {
        let mut r = UnderrunReporter::new(Duration::from_secs(30));
        let t0 = Instant::now();
        r.record(t0, 5);
        assert_eq!(r.record(t0 + Duration::from_secs(30), 5), (0, 5));
        assert_eq!(r.record(t0 + Duration::from_secs(60), 9), (4, 9));
    }

    #[test]
    fn defer_pushes_the_next_reading_out_without_a_value() {
        let mut r = UnderrunReporter::new(Duration::from_secs(30));
        let t0 = Instant::now();
        r.record(t0, 2);
        r.defer(t0 + Duration::from_secs(30));
        assert!(!r.is_due(t0 + Duration::from_secs(59)));
        // The counter baseline is untouched by a deferral.
        assert_eq!(r.record(t0 + Duration::from_secs(60), 2), (0, 2));
    }

    #[test]
    fn reset_starts_a_fresh_track_at_zero() {
        let mut r = UnderrunReporter::new(Duration::from_secs(30));
        let t0 = Instant::now();
        r.record(t0, 7);
        r.reset();
        assert!(r.is_due(t0));
        // A new AudioTrack counts from zero again: no phantom negative delta.
        assert_eq!(r.record(t0, 1), (1, 1));
    }
}
