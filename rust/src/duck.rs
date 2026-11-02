//! Duck-aware wrapper around librespot's SoftMixer, with the Connect volume handed off to Android.
//!
//! The audible level on this receiver is the device's media stream (STREAM_MUSIC) — the same
//! level the hardware volume keys and the remote-control page move. Spotify's Connect volume is
//! mirrored into it, so the mixer reports the volume but does NOT attenuate by it; otherwise the
//! signal would be cut twice (once here, once by the system stream) and the two sliders would
//! disagree about loudness.
//!
//! Spirc is handed THIS mixer, so every volume path (remote Connect volume changes included)
//! goes through the same lock as ducking:
//!   set_volume(v)              -> logical = v (reported to Spirc and to Android);
//!                                 inner volume = duck-only attenuation (base always FULL)
//!   volume()                   -> logical (the Connect slider NEVER sees the duck)
//!   set_attenuation(t, fade)   -> ramps `current` from wherever it is to `t`, re-applying
//!                                 that duck (and nothing else) at every step
//! A restore therefore cannot clobber a volume the user changed mid-fade — the change already
//! updated `logical`, and the ramp just keeps re-applying the in-flight attenuation.
//!
//! Fades are computed in the MAPPED domain (the attenuation factor the player actually
//! multiplies the samples by), NOT on the raw u16 slider position. librespot's default volume
//! control is `VolumeCtrl::Log(60 dB)`: `SoftMixer::set_volume` maps the position through that
//! curve, so scaling the POSITION directly (what this used to do) attenuates by
//! 1000^(-0.8 * x), a duck whose depth depends on where the user's Connect slider happens to be.
//! Mapping to the audible scale, attenuating there and mapping back gives the SAME dB duck at
//! every Connect volume. Going through the mixer's own `VolumeCtrl` (rather than hard-coding the
//! log curve) keeps this correct if librespot's default mapping ever changes.

use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use librespot::core::Error;
use librespot::playback::config::VolumeCtrl;
use librespot::playback::mixer::mappings::MappedCtrl;
use librespot::playback::mixer::softmixer::SoftMixer;
use librespot::playback::mixer::{Mixer, MixerConfig, VolumeGetter};

/// Ramp step interval. librespot's player re-reads the soft volume per audio packet,
/// so 25 ms steps are audibly smooth — a per-sample fade would buy nothing.
const RAMP_STEP: Duration = Duration::from_millis(25);

struct DuckState {
    logical: u16,
    /// Attenuation factor applied right now (mapped/amplitude domain); 1.0 = none.
    current: f64,
    /// Attenuation factor we are heading to.
    target: f64,
    /// Bumped by every set_attenuation call; a ramp step whose generation is stale must
    /// not write (it would clobber a newer instant restore). Also what makes ramps
    /// supersede instead of queue.
    ramp_gen: u64,
}

pub struct DuckingMixer {
    inner: SoftMixer,
    /// The mixer's own position -> attenuation curve; fades are computed in ITS output domain.
    volume_ctrl: VolumeCtrl,
    state: Mutex<DuckState>,
    /// Called with the logical Connect volume whenever it changes, so Android can move its media
    /// stream to match. Behind its own lock so the reporter never runs while `state` is held: it
    /// crosses into the JVM and can block for the attach.
    volume_reporter: Mutex<Option<Arc<dyn Fn(u16) + Send + Sync>>>,
}

impl DuckingMixer {
    /// The u16 position for a duck of `atten` applied to full-scale audio. The base volume is
    /// never applied to the samples (see the module doc); only the duck attenuates them.
    fn audible(&self, atten: f64) -> u16 {
        if atten >= 1.0 {
            return u16::MAX;
        }
        let mapped = self.volume_ctrl.to_mapped(u16::MAX) * atten;
        self.volume_ctrl.as_unmapped(mapped)
    }

    /// Installs the sink for logical Connect volume changes. Only the last reporter is kept, and
    /// the current value is not replayed — callers seed Android from the startup volume.
    pub fn set_volume_reporter(&self, reporter: impl Fn(u16) + Send + Sync + 'static) {
        *self.volume_reporter.lock().unwrap_or_else(|p| p.into_inner()) = Some(Arc::new(reporter));
    }

    fn report(&self, volume: u16) {
        let reporter = self.volume_reporter.lock().unwrap_or_else(|p| p.into_inner()).clone();
        if let Some(reporter) = reporter {
            reporter(volume);
        }
    }

    /// Fade the audible volume to `target` over `fade_ms`. `fade_ms == 0` applies instantly.
    /// A new call supersedes any in-flight ramp (latest target wins; ramps never queue) and
    /// starts from the CURRENT attenuation, so mid-fade reversal is glitch-free. The final
    /// step is computed from the deadline, not the step count, so wake-up jitter cannot leave
    /// the ramp short of target. No-op when already idle at `target`.
    pub fn set_attenuation(self: &Arc<Self>, target: f64, fade_ms: u32) {
        let target = if target.is_nan() { 1.0 } else { target.clamp(0.0, 1.0) };
        let (gen, start) = {
            let mut s = self.state.lock().unwrap_or_else(|p| p.into_inner());
            if s.current == target && s.target == target {
                return;
            }
            s.ramp_gen += 1;
            s.target = target;
            if fade_ms == 0 {
                s.current = target;
                let v = self.audible(target);
                self.inner.set_volume(v);
                return;
            }
            (s.ramp_gen, s.current)
        };
        let mixer = Arc::clone(self);
        thread::spawn(move || {
            let t0 = Instant::now();
            let total = Duration::from_millis(u64::from(fade_ms));
            loop {
                thread::sleep(RAMP_STEP);
                let frac = (t0.elapsed().as_secs_f64() / total.as_secs_f64()).min(1.0);
                let mut s = mixer.state.lock().unwrap_or_else(|p| p.into_inner());
                if s.ramp_gen != gen {
                    return; // superseded — a newer call owns the mixer now
                }
                s.current = start + (s.target - start) * frac;
                let v = mixer.audible(s.current);
                mixer.inner.set_volume(v);
                if frac >= 1.0 {
                    return;
                }
            }
        });
    }
}

impl Mixer for DuckingMixer {
    fn open(config: MixerConfig) -> Result<Self, Error> {
        let volume_ctrl = config.volume_ctrl;
        let inner = <SoftMixer as Mixer>::open(config)?;
        // Base at unity: the audible level is the Android stream volume (see module doc).
        inner.set_volume(u16::MAX);
        let logical = inner.volume();
        Ok(DuckingMixer {
            inner,
            volume_ctrl,
            state: Mutex::new(DuckState {
                logical,
                current: 1.0,
                target: 1.0,
                ramp_gen: 0,
            }),
            volume_reporter: Mutex::new(None),
        })
    }

    fn volume(&self) -> u16 {
        self.state.lock().unwrap_or_else(|p| p.into_inner()).logical
    }

    fn set_volume(&self, volume: u16) {
        let changed = {
            let mut s = self.state.lock().unwrap_or_else(|p| p.into_inner());
            let changed = s.logical != volume;
            s.logical = volume;
            let v = self.audible(s.current);
            self.inner.set_volume(v);
            changed
        };
        // Outside the state lock: the reporter is a JNI call into Android.
        if changed {
            self.report(volume);
        }
    }

    fn get_soft_volume(&self) -> Box<dyn VolumeGetter + Send> {
        // The Player attenuates through the INNER mixer's getter, which tracks the
        // effective (possibly ducked/fading) volume we push into it above.
        self.inner.get_soft_volume()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use librespot::playback::mixer::{Mixer, MixerConfig};
    use std::sync::Arc;
    use std::thread::sleep;
    use std::time::Duration;

    const FULL: u16 = u16::MAX;

    fn mixer() -> Arc<DuckingMixer> {
        let m = Arc::new(<DuckingMixer as Mixer>::open(MixerConfig::default()).unwrap());
        m.set_volume(FULL);
        m
    }

    #[test]
    fn instant_duck_and_restore() {
        let m = mixer();
        m.set_attenuation(0.2, 0);
        assert_eq!(m.inner.volume(), m.audible(0.2));
        assert_eq!(m.volume(), FULL); // the Connect slider never sees the duck
        m.set_attenuation(1.0, 0);
        assert_eq!(m.inner.volume(), FULL);
    }

    #[test]
    fn connect_volume_is_reported_but_not_applied_to_samples() {
        let m = mixer();
        let seen = Arc::new(Mutex::new(Vec::new()));
        let sink = Arc::clone(&seen);
        m.set_volume_reporter(move |v| sink.lock().unwrap().push(v));

        m.set_volume(1234);
        // Reported to Spirc and Android...
        assert_eq!(m.volume(), 1234);
        assert_eq!(*seen.lock().unwrap(), vec![1234]);
        // ...but not applied to the samples: the system stream owns the level.
        assert_eq!(m.inner.volume(), FULL);

        m.set_volume(1234); // unchanged: no second report
        assert_eq!(*seen.lock().unwrap(), vec![1234]);
    }

    #[test]
    fn ramp_reaches_exact_target() {
        let m = mixer();
        m.set_attenuation(0.2, 100);
        sleep(Duration::from_millis(300));
        assert_eq!(m.inner.volume(), m.audible(0.2));
        m.set_attenuation(1.0, 100);
        sleep(Duration::from_millis(300));
        assert_eq!(m.inner.volume(), FULL);
    }

    #[test]
    fn new_target_supersedes_inflight_ramp() {
        let m = mixer();
        m.set_attenuation(0.0, 500);
        sleep(Duration::from_millis(50));
        m.set_attenuation(1.0, 0); // instant restore must kill the ramp
        sleep(Duration::from_millis(300)); // a stale step would land in this window
        assert_eq!(m.inner.volume(), FULL);
    }

    #[test]
    fn connect_volume_change_mid_fade_wins() {
        let m = mixer();
        m.set_attenuation(0.2, 150);
        sleep(Duration::from_millis(50));
        let half = FULL / 2;
        m.set_volume(half); // user moves the Connect slider mid-fade
        sleep(Duration::from_millis(300));
        assert_eq!(m.volume(), half);
        assert_eq!(m.inner.volume(), m.audible(0.2));
    }

    #[test]
    fn bogus_inputs_are_sanitised() {
        let m = mixer();
        m.set_attenuation(f64::NAN, 0); // NaN → 1.0
        assert_eq!(m.inner.volume(), FULL);
        m.set_attenuation(1.5, 0); // clamped to 1.0
        assert_eq!(m.inner.volume(), FULL);
        m.set_attenuation(-0.3, 0); // clamped to 0.0 (silence)
        assert_eq!(m.inner.volume(), m.audible(0.0));
    }

    #[test]
    fn retarget_at_current_value_is_noop() {
        let m = mixer();
        m.set_attenuation(1.0, 200); // already at 1.0: nothing to do
        sleep(Duration::from_millis(30));
        assert_eq!(m.inner.volume(), FULL);
    }
}
