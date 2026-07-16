//! Duck-aware wrapper around librespot's SoftMixer.
//!
//! Spirc is handed THIS mixer, so every volume path (remote Connect volume changes included)
//! goes through the same lock as ducking:
//!   set_volume(v)              -> logical = v; inner volume = effective(v, current)
//!   volume()                   -> logical (the Connect slider NEVER sees the duck)
//!   set_attenuation(t, fade)   -> ramps `current` from wherever it is to `t`, re-applying
//!                                 effective(logical, current) at every step
//! A restore therefore cannot clobber a volume the user changed mid-fade — the change already
//! updated `logical`, and the ramp just keeps re-applying it at the in-flight attenuation.
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
}

impl DuckingMixer {
    /// The u16 position whose audible volume is `atten` x the audible volume of `logical`.
    fn effective(&self, logical: u16, atten: f64) -> u16 {
        if atten >= 1.0 {
            return logical;
        }
        let mapped = self.volume_ctrl.to_mapped(logical) * atten;
        self.volume_ctrl.as_unmapped(mapped)
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
                let v = self.effective(s.logical, target);
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
                let v = mixer.effective(s.logical, s.current);
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
        })
    }

    fn volume(&self) -> u16 {
        self.state.lock().unwrap_or_else(|p| p.into_inner()).logical
    }

    fn set_volume(&self, volume: u16) {
        let mut s = self.state.lock().unwrap_or_else(|p| p.into_inner());
        s.logical = volume;
        let v = self.effective(volume, s.current);
        self.inner.set_volume(v);
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
        assert_eq!(m.inner.volume(), m.effective(FULL, 0.2));
        assert_eq!(m.volume(), FULL); // the Connect slider never sees the duck
        m.set_attenuation(1.0, 0);
        assert_eq!(m.inner.volume(), FULL);
    }

    #[test]
    fn ramp_reaches_exact_target() {
        let m = mixer();
        m.set_attenuation(0.2, 100);
        sleep(Duration::from_millis(300));
        assert_eq!(m.inner.volume(), m.effective(FULL, 0.2));
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
        assert_eq!(m.inner.volume(), m.effective(half, 0.2));
    }

    #[test]
    fn bogus_inputs_are_sanitised() {
        let m = mixer();
        m.set_attenuation(f64::NAN, 0); // NaN → 1.0
        assert_eq!(m.inner.volume(), FULL);
        m.set_attenuation(1.5, 0); // clamped to 1.0
        assert_eq!(m.inner.volume(), FULL);
        m.set_attenuation(-0.3, 0); // clamped to 0.0 (silence)
        assert_eq!(m.inner.volume(), m.effective(FULL, 0.0));
    }

    #[test]
    fn retarget_at_current_value_is_noop() {
        let m = mixer();
        m.set_attenuation(1.0, 200); // already at 1.0: nothing to do
        sleep(Duration::from_millis(30));
        assert_eq!(m.inner.volume(), FULL);
    }
}
