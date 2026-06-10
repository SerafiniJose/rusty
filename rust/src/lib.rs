use std::collections::HashMap;
use std::{env, thread};
use std::os::raw::c_void;
use std::sync::{Mutex, Once, OnceLock};
use std::time::Duration;
use std::backtrace::Backtrace;
use std::future::{self, Future};
use std::pin::Pin;

use jni::{JNIEnv, JavaVM};
use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};

//librespot imports (0.8 umbrella crate)
use librespot::core::{Session, SessionConfig, SpotifyUri, FileId};
use librespot::core::config::DeviceType;
use librespot::core::authentication::Credentials;
use librespot::connect::{ConnectConfig, Spirc};
use librespot::playback::config::{PlayerConfig, Bitrate};
use librespot::playback::player::{Player, PlayerEvent, PlayerEventChannel};
use librespot::playback::mixer::{self, MixerConfig};
use librespot::metadata::{Episode, Metadata, Track};

//helpers
use sha1::{Sha1, Digest};
use futures::stream::StreamExt; //required for discovery.next()
use tokio::runtime::Runtime;
use tokio::sync::watch;
use tokio::time::{sleep_until, Instant};
use log::{info, error, LevelFilter};
use android_logger::Config;

/// Custom audio sink that reopens the output stream when Android's active audio
/// route changes (e.g. Bluetooth connect/disconnect) — see the module docs.
mod audio_sink;

/// Thread-safe state for the single active native Spotify Connect receiver.
///
/// Holds the Tokio runtime that drives the discovery/session/player tasks plus a
/// `watch` channel used to broadcast a cooperative shutdown signal into the
/// discovery loop and any active Spirc session. Replaces the previous
/// `static mut RUNTIME_HANDLE`, which could never actually stop the receiver.
///
/// `rename_tx` carries a new receiver name into the running discovery loop so it
/// can re-advertise in place (drop + recreate the mDNS service under the same
/// device id) without tearing the runtime — and therefore the foreground service
/// and UI — down.
struct ReceiverState {
    runtime: Runtime,
    shutdown_tx: watch::Sender<bool>,
    rename_tx: watch::Sender<String>,
    /// Handle to the spawned discovery loop, awaited on shutdown so its libmdns
    /// `Discovery` is dropped cleanly (responder still alive) before the runtime is
    /// torn down — otherwise libmdns panics in `Service::drop`.
    discovery_handle: tokio::task::JoinHandle<()>,
    device_name: String,
    bitrate: Bitrate,
}

/// Everything the discovery loop must own while a controller is connected.
///
/// Held in an `Option<ActiveSession>` inside `start_discovery_loop`: `Some` while a
/// session is live, `None` when the receiver is idle and discoverable. This lets the
/// loop poll `discovery.next()` (to accept a *new* controller — possibly a different
/// account) and the running `spirc_task` *concurrently in the same select!*, instead
/// of blocking on one session at a time. A new connection therefore preempts the
/// current session rather than being stuck behind it (which previously required a
/// force-stop to recover). Mirrors librespot's own reference client loop.
///
/// `Spirc` is intentionally NOT held here — it is not `Clone`, and the handle already
/// lives in `spirc_slot()` (used by JNI for transport commands); teardown takes it
/// from that slot, exactly as the previous explicit-shutdown path did.
struct ActiveSession {
    /// The receiver's own Spotify session. Kept so teardown can `shutdown()` it
    /// (which is what actually invalidates it and ends `spirc_task`).
    session: Session,
    /// The Spirc run-loop future, boxed+pinned so it lives across loop iterations and
    /// can be polled by `&mut` in a select arm. Completes only when the session goes
    /// invalid or Spirc is shut down — NOT merely because the controller's phone app
    /// was closed (the receiver stays the active device until something ends it).
    spirc_task: Pin<Box<dyn Future<Output = ()> + Send>>,
    /// The spawned `consume_player_events` task; aborted on teardown so the player/
    /// mixer (moved into `Spirc::new`) drop and the event channel closes.
    player_events_handle: tokio::task::JoinHandle<()>,
}

/// Grace period granted to async tasks (mDNS de-registration, Spirc goodbye,
/// session shutdown) before the runtime is forcibly torn down.
const SHUTDOWN_GRACE: Duration = Duration::from_millis(1500);

/// How long a session may stay paused/idle (no active playback) before the receiver
/// releases it on its own, returning to the discoverable idle state. A session that
/// is actively *playing* is never released by this timeout — only paused/stopped
/// ones — so closing the controller mid-playback keeps the music going. Tunable.
const IDLE_SESSION_TIMEOUT: Duration = Duration::from_secs(10 * 60);

/// Spotify Web Player client id, used for keymaster access-token requests. Public/well-known.
const TOKEN_CLIENT_ID: &str = "65b708073fc0480ea92a077233ca87bd";
/// Scopes requested for the access token (lyrics + profile + playback read).
const TOKEN_SCOPES: &str =
    "streaming,user-read-playback-state,user-read-currently-playing,user-read-private,user-read-email";

static RECEIVER: OnceLock<Mutex<Option<ReceiverState>>> = OnceLock::new();
static INIT_ANDROID_CONTEXT: Once = Once::new();
static INIT_PANIC_HOOK: Once = Once::new();
static mut ANDROID_CONTEXT_REF: Option<GlobalRef> = None;
static JAVA_VM: OnceLock<JavaVM> = OnceLock::new();
static SPOTIFY_SERVICE_CLASS: OnceLock<GlobalRef> = OnceLock::new();

/// Process-wide handle to the active session's `Spirc`, used to dispatch transport
/// commands (play/pause/next/prev) from JNI. `Spirc` is just a clonable command
/// sender (`tokio::sync::mpsc::UnboundedSender`), so it is `Send` and its methods
/// are non-blocking — safe to call from the Android UI thread. Set when a session
/// connects, cleared when it ends, so commands outside a session are safe no-ops.
static SPIRC_CONTROL: OnceLock<Mutex<Option<Spirc>>> = OnceLock::new();

/// Process-wide handle to the active `Session`, used by JNI to mint access tokens over the
/// existing Mercury connection. Set when a session connects, cleared when it ends, so a token
/// request outside a session is a safe no-op. `Session` is an `Arc` handle, so cloning is cheap.
static SESSION_HANDLE: OnceLock<Mutex<Option<Session>>> = OnceLock::new();

/// Process-wide Tokio runtime handle, set on each `startDevice`. Lets a JNI call made from
/// outside the runtime (e.g. requestAccessToken) spawn an async task onto it.
static RUNTIME_HANDLE: OnceLock<Mutex<Option<tokio::runtime::Handle>>> = OnceLock::new();

/// Returns the process-wide receiver slot, initialising it on first use.
fn receiver_slot() -> &'static Mutex<Option<ReceiverState>> {
    RECEIVER.get_or_init(|| Mutex::new(None))
}

/// Returns the process-wide Spirc control slot, initialising it on first use.
fn spirc_slot() -> &'static Mutex<Option<Spirc>> {
    SPIRC_CONTROL.get_or_init(|| Mutex::new(None))
}

/// Returns the process-wide session slot, initialising it on first use.
fn session_slot() -> &'static Mutex<Option<Session>> {
    SESSION_HANDLE.get_or_init(|| Mutex::new(None))
}

/// Returns the process-wide runtime-handle slot, initialising it on first use.
fn runtime_handle_slot() -> &'static Mutex<Option<tokio::runtime::Handle>> {
    RUNTIME_HANDLE.get_or_init(|| Mutex::new(None))
}

/// Synchronously shuts an existing receiver down and waits for its runtime — and
/// therefore its mDNS responder — to fully stop before returning.
///
/// Used when we are about to start a *replacement* receiver in the same process
/// (e.g. a bitrate change). Starting a second `libmdns` responder while the old one
/// is still alive makes the old one's `Service::drop` panic ("responder died:
/// SendError") and abort the process. Blocking here guarantees the old responder is
/// gone before the new one is created. Called from the service's background start
/// thread, so the block is harmless.
fn shutdown_blocking(state: ReceiverState) {
    let ReceiverState { runtime, shutdown_tx, discovery_handle, device_name, .. } = state;
    // Drop the runtime handle so token requests between stop and the next start are inert.
    *runtime_handle_slot().lock().unwrap_or_else(|poison| poison.into_inner()) = None;
    let _ = shutdown_tx.send(true);
    // Drive the discovery loop to a clean stop FIRST: it drops its libmdns Discovery
    // while the responder task is still alive (a clean mDNS de-register). Forcing the
    // runtime down without this drops the Service after its responder is gone, which
    // makes libmdns panic ("responder died").
    let _ = runtime.block_on(async {
        tokio::time::timeout(SHUTDOWN_GRACE, discovery_handle).await
    });
    runtime.shutdown_timeout(Duration::from_millis(200));
    info!("Native receiver '{}' fully stopped before restart", device_name);
}

/// Signals an existing receiver to shut down and tears its runtime down on a
/// detached thread, so callers (the Android service/main thread that invokes
/// stopDevice()/onDestroy()) never block on the grace period.
fn spawn_shutdown(state: ReceiverState) {
    let ReceiverState { runtime, shutdown_tx, discovery_handle, device_name, .. } = state;
    // Drop the runtime handle so token requests between stop and the next start are inert.
    *runtime_handle_slot().lock().unwrap_or_else(|poison| poison.into_inner()) = None;
    // Ask the discovery loop + active session to stop cooperatively. This makes
    // the discovery loop drop its Discovery handle (removing the mDNS
    // advertisement and HTTP server) and triggers spirc.shutdown()/
    // session.shutdown() for any connected session.
    let _ = shutdown_tx.send(true);
    thread::spawn(move || {
        // Let the discovery loop drop its libmdns Discovery cleanly (responder still
        // alive) before tearing the runtime down, otherwise libmdns panics on drop.
        // Then allow a short grace period for any remaining tasks, then force-abort.
        let _ = runtime.block_on(async {
            tokio::time::timeout(SHUTDOWN_GRACE, discovery_handle).await
        });
        runtime.shutdown_timeout(Duration::from_millis(200));
        info!("Native receiver '{}' fully stopped", device_name);
    });
}

/// Derives a stable 40-char SHA-1 hex Spotify Connect device id from a persisted
/// per-install seed (passed from Android SharedPreferences). Falls back to the
/// historical constant if the seed is empty so behaviour never regresses.
fn derive_device_id(seed: &str) -> String {
    let seed = seed.trim();
    let seed = if seed.is_empty() { "android_device_id" } else { seed };
    let mut hasher = Sha1::new();
    hasher.update(seed.as_bytes());
    hex::encode(hasher.finalize())
}

fn bitrate_from_kbps(bitrate_kbps: i32) -> Bitrate {
    match bitrate_kbps {
        96 => Bitrate::Bitrate96,
        320 => Bitrate::Bitrate320,
        _ => Bitrate::Bitrate160,
    }
}

fn bitrate_label(bitrate: Bitrate) -> &'static str {
    match bitrate {
        Bitrate::Bitrate96 => "96",
        Bitrate::Bitrate160 => "160",
        Bitrate::Bitrate320 => "320",
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_rusty_app_NativeBridge_initAndroidContext(
    mut env: JNIEnv,
    _class: JClass,
    context: JObject,
    cache_dir_java: JString,
) {
    let cache_dir: String = env
        .get_string(&cache_dir_java)
        .expect("Couldn't get cache dir java string!")
        .into();
    env::set_var("TMPDIR", &cache_dir);
    env::set_var("TEMP", &cache_dir);
    env::set_var("TMP", &cache_dir);

    INIT_ANDROID_CONTEXT.call_once(|| {
        let java_vm = env.get_java_vm().expect("Failed to get JavaVM");
        let context_ref = env.new_global_ref(context).expect("Failed to create global Android context ref");
        let context_ptr = context_ref.as_obj().as_raw() as *mut c_void;
        let vm_ptr = java_vm.get_java_vm_pointer() as *mut c_void;

        // Establish the audio/JNI context first so it is set up even if the class
        // lookup below fails.
        unsafe {
            ndk_context::initialize_android_context(vm_ptr, context_ptr);
            ANDROID_CONTEXT_REF = Some(context_ref);
        }
        let _ = JAVA_VM.set(java_vm);

        // Cache the SpotifyService class used by the JNI callbacks (playback/status/
        // token). This path MUST match the app package. It was previously the stale
        // pre-rename name ("com/example/spotifyreceiver/SpotifyService"), which made
        // find_class fail; the `.expect()` that followed then panicked, and unwinding
        // that panic burned ~8s on the main thread (initAndroidContext runs inside
        // SpotifyService.onCreate), which tripped the foreground-service "did not call
        // startForeground in time" ANR and killed the service on launch. Handle a miss
        // gracefully now: clear the pending JNI exception and carry on with callbacks
        // disabled, rather than taking down the service.
        match env
            .find_class("dev/rusty/app/SpotifyService")
            .and_then(|class| env.new_global_ref(class))
        {
            Ok(service_class) => {
                let _ = SPOTIFY_SERVICE_CLASS.set(service_class);
            }
            Err(e) => {
                if env.exception_check().unwrap_or(false) {
                    let _ = env.exception_clear();
                }
                error!("Failed to cache SpotifyService class; JNI callbacks disabled: {:?}", e);
            }
        }

        info!("Android context initialized for cpal/AAudio audio backend; TMPDIR={}", cache_dir);
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_rusty_app_NativeBridge_initLogger(
    _env: JNIEnv,
    _class: JClass,
) {
    android_logger::init_once(
        Config::default().with_max_level(LevelFilter::Info).with_tag("RustSpotify"),
    );
    INIT_PANIC_HOOK.call_once(|| {
        std::panic::set_hook(Box::new(|panic_info| {
            error!("Rust panic: {}\nBacktrace:\n{:?}", panic_info, Backtrace::force_capture());
        }));
    });
    info!("Rust Logger Initialized");
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_rusty_app_NativeBridge_startDevice(
    mut env: JNIEnv,
    _class: JClass,
    device_name_java: JString,
    device_id_java: JString,
    bitrate_kbps: i32,
) {
    let device_name: String = env
        .get_string(&device_name_java)
        .expect("Couldn't get java string!")
        .into();
    // Persisted per-install id from Android SharedPreferences. Defaults to empty
    // (handled by derive_device_id) if the bridge ever passes a null string.
    let device_id_seed: String = env
        .get_string(&device_id_java)
        .map(Into::into)
        .unwrap_or_default();

    let slot = receiver_slot();
    let mut guard = slot.lock().unwrap_or_else(|poison| poison.into_inner());

    let bitrate = bitrate_from_kbps(bitrate_kbps);

    // Idempotent start: if a receiver with the same name/bitrate is already
    // running, do nothing. This absorbs repeated onStartCommand()/START_STICKY
    // deliveries.
    if let Some(existing) = guard.as_ref() {
        if existing.device_name == device_name && existing.bitrate == bitrate {
            info!(
                "Native receiver already running as '{}' at {}kbps; ignoring duplicate start",
                device_name,
                bitrate_label(bitrate)
            );
            return;
        }
        // A different name or playback quality was requested: stop the old
        // receiver first so we never leak native discovery/session/player tasks.
        info!(
            "Native receiver running as '{}' at {}kbps; stopping it before starting '{}' at {}kbps",
            existing.device_name,
            bitrate_label(existing.bitrate),
            device_name,
            bitrate_label(bitrate)
        );
        if let Some(previous) = guard.take() {
            // Block until the old receiver (and its mDNS responder) is fully gone
            // before creating the replacement — two live libmdns responders make the
            // old one panic on drop. This thread is the service's background start
            // thread, so blocking briefly here is fine.
            shutdown_blocking(previous);
        }
    }

    let device_id = derive_device_id(&device_id_seed);
    info!(
        "Starting Spotify Receiver: name='{}' device_id={} bitrate={}kbps",
        device_name,
        device_id,
        bitrate_label(bitrate)
    );

    let runtime = match Runtime::new() {
        Ok(runtime) => runtime,
        Err(e) => {
            error!("Failed to create Tokio runtime: {:?}", e);
            return;
        }
    };

    // Record the runtime handle so JNI calls (e.g. requestAccessToken) can spawn onto it.
    *runtime_handle_slot().lock().unwrap_or_else(|poison| poison.into_inner()) =
        Some(runtime.handle().clone());

    let (shutdown_tx, shutdown_rx) = watch::channel(false);
    // Seeded with the current name; `changed()` only fires on a later send(), so
    // the initial value never triggers a spurious re-advertise.
    let (rename_tx, rename_rx) = watch::channel(device_name.clone());
    let loop_name = device_name.clone();
    let loop_device_id = device_id.clone();
    let discovery_handle = runtime.spawn(async move {
        start_discovery_loop(loop_name, loop_device_id, bitrate, shutdown_rx, rename_rx).await;
    });

    *guard = Some(ReceiverState {
        runtime,
        shutdown_tx,
        rename_tx,
        discovery_handle,
        device_name,
        bitrate,
    });
}

async fn start_discovery_loop(
    device_name: String,
    device_id: String,
    bitrate: Bitrate,
    mut shutdown_rx: watch::Receiver<bool>,
    mut rename_rx: watch::Receiver<String>,
) {
    // 0.8: ConnectConfig moved to librespot::connect and changed shape.
    // autoplay moved to SessionConfig; has_volume_ctrl -> disable_volume (inverted);
    // initial_volume Option<u16> -> plain u16 (0..=u16::MAX). 32768 ~= 50%.
    let mut connect_config = ConnectConfig {
        name: device_name,
        device_type: DeviceType::Speaker,
        is_group: false,
        initial_volume: 32768,
        disable_volume: false,
        volume_steps: 64,
    };

    // 0.8: discovery requires a client_id. On Android, SessionConfig::default() supplies
    // the Android client_id; reuse it so discovery and the session agree.
    let client_id = SessionConfig::default().client_id;

    // 0.8: librespot::discovery::Discovery::builder(device_id, client_id) -> Builder -> launch()
    let mut discovery = match librespot::discovery::Discovery::builder(
        device_id.clone(),
        client_id.clone(),
    )
    .name(connect_config.name.clone())
    .device_type(DeviceType::Speaker)
    .is_group(false)
    .port(0)
    .launch()
    {
        Ok(discovery) => discovery,
        Err(e) => {
            error!("Failed to start Zeroconf discovery: {:?}", e);
            return;
        }
    };

    info!("Discovery started. Waiting for connection...");

    // `Some` while a controller is connected; `None` when idle and discoverable.
    // Polled concurrently with `discovery.next()` below so a NEW connection (even a
    // different account) preempts the current session instead of being stuck behind
    // it — the bug this whole restructure fixes.
    let mut active: Option<ActiveSession> = None;

    loop {
        tokio::select! {
            biased;
            _ = shutdown_rx.changed() => {
                info!("Shutdown requested; leaving discovery loop");
                if let Some(mut current) = active.take() {
                    teardown_active_session(&mut current);
                    // Best-effort drain of the Spirc goodbye; bounded by the caller's
                    // SHUTDOWN_GRACE timeout around the whole discovery handle.
                    let _ = current.spirc_task.await;
                }
                break;
            }
            _ = rename_rx.changed() => {
                let new_name = rename_rx.borrow().clone();
                info!("Rename requested; re-advertising in place as '{}'", new_name);
                connect_config.name = new_name.clone();
                drop(discovery);
                discovery = match librespot::discovery::Discovery::builder(
                    device_id.clone(),
                    client_id.clone(),
                )
                .name(new_name.clone())
                .device_type(DeviceType::Speaker)
                .is_group(false)
                .port(0)
                .launch()
                {
                    Ok(discovery) => discovery,
                    Err(e) => {
                        error!("Failed to re-advertise discovery after rename: {:?}", e);
                        return;
                    }
                };
            }
            maybe_credentials = discovery.next() => {
                match maybe_credentials {
                    Some(credentials) => {
                        info!("Connection request received!");
                        // Preempt any active session (e.g. a different account taking
                        // over). Tear it down, then drain its goodbye off to the side
                        // so it doesn't block the new connection. No disconnect callback
                        // here — the connected callback inside build_active_session
                        // overwrites the UI directly, avoiding an idle flicker.
                        if let Some(mut old) = active.take() {
                            info!("Preempting active session for the new controller");
                            teardown_active_session(&mut old);
                            tokio::spawn(old.spirc_task);
                        }
                        match build_active_session(
                            connect_config.clone(),
                            device_id.clone(),
                            bitrate,
                            credentials,
                        ).await {
                            Ok(session) => {
                                info!("Session established");
                                active = Some(session);
                            }
                            Err(e) => {
                                error!("Session error: {:?}", e);
                                // Make sure we land in a clean idle state if setup
                                // failed partway (slots may have been set).
                                *spirc_slot().lock().unwrap_or_else(|poison| poison.into_inner()) = None;
                                *session_slot().lock().unwrap_or_else(|poison| poison.into_inner()) = None;
                                active = None;
                            }
                        }
                    }
                    None => {
                        error!("Discovery stream ended unexpectedly");
                        break;
                    }
                }
            }
            // The active session ended on its own: the receiver's session went invalid
            // (network loss / remote logout) or the idle timeout shut it down. Return to
            // idle and notify Android. Only polled while a session is live; when idle the
            // arm parks on `future::pending()` so it never fires. The `&mut active` borrow
            // lives only inside this future, which select! drops before running any arm
            // body — so the other arms remain free to reassign `active`.
            _ = async {
                match active.as_mut() {
                    Some(current) => (&mut current.spirc_task).await,
                    None => future::pending::<()>().await,
                }
            }, if active.is_some() => {
                info!("Spirc control loop ended on its own; returning receiver to idle");
                if let Some(mut current) = active.take() {
                    teardown_active_session(&mut current);
                }
                send_native_receiver_disconnected();
            }
        }
    }

    drop(discovery);
    info!("Discovery loop exited; mDNS advertisement stopped for this receiver");
}

/// Sets up one controller connection: builds the session/player/mixer, spawns the
/// player-event forwarder, connects via `Spirc::new`, wires the JNI/session/spirc
/// slots, and fires the connected callback. Returns the live handles for the
/// discovery loop to own. Unlike the old `connect_and_play`, it does NOT block on
/// the spirc task — the loop polls that itself so it can simultaneously watch
/// `discovery.next()` for a preempting (possibly different-account) connection.
async fn build_active_session(
    connect_config: ConnectConfig,
    device_id: String,
    bitrate: Bitrate,
    credentials: Credentials,
) -> Result<ActiveSession, Box<dyn std::error::Error>> {
    let session_config = SessionConfig {
        device_id,
        // 0.8: autoplay moved here from ConnectConfig. Preserve prior autoplay: true.
        autoplay: Some(true),
        ..SessionConfig::default()
    };
    // 0.8: build the Session but DO NOT connect here. Spirc::new connects internally
    // (it calls session.connect(credentials, true)); connecting twice errors.
    let session = Session::new(session_config, None);

    let player_config = PlayerConfig {
        bitrate,
        ..PlayerConfig::default()
    };
    info!("Player configured for bitrate {}kbps", bitrate_label(bitrate));

    // 0.8: mixer::find -> Option<MixerFn>; MixerFn -> Result<Arc<dyn Mixer>, Error>.
    let mixer_fn = mixer::find(None).expect("No mixer found!");
    let mixer = mixer_fn(MixerConfig::default()).expect("Failed to open mixer");

    // 0.8: Player::new returns Arc<Player>; the sink closure now takes NO args.
    // We supply our own route-following sink instead of librespot's stock rodio
    // backend so playback survives Android audio-route changes (Bluetooth
    // connect/disconnect, headset plug/unplug) — see audio_sink for the rationale.
    let player = Player::new(
        player_config,
        session.clone(),
        mixer.get_soft_volume(),
        move || -> Box<dyn librespot::playback::audio_backend::Sink> {
            Box::new(audio_sink::RouteFollowingSink::new())
        },
    );

    // 0.8: the event channel is pulled from the player (no longer returned by Player::new).
    let event_channel = player.get_player_event_channel();
    let player_events_handle = tokio::spawn(consume_player_events(session.clone(), event_channel));

    info!("Player initialized. Starting Spotify Connect control loop.");

    // 0.8: Spirc::new is async, takes credentials, connects internally, returns Result.
    // player (Arc<Player>) and mixer (Arc<dyn Mixer>) are moved in; not needed after.
    let (spirc, spirc_task) =
        Spirc::new(connect_config, session.clone(), credentials, player, mixer).await?;

    // The session is connected now (Spirc::new did it) — publish the connected event.
    let username = session.username();
    info!("Connected to Spotify! User: {}", username);

    // Expose the session so JNI can mint access tokens over its connection. MUST be set before
    // send_native_receiver_connected: that callback is a synchronous JNI call into Android, whose
    // onNativeReceiverConnected synchronously calls requestAccessToken(), which reads this slot.
    // Set here — after Spirc::new connected the session — so the connect-time mint sees a live
    // session rather than an empty slot.
    *session_slot().lock().unwrap_or_else(|poison| poison.into_inner()) = Some(session.clone());
    send_native_receiver_connected(&username);

    *spirc_slot().lock().unwrap_or_else(|poison| poison.into_inner()) = Some(spirc);

    Ok(ActiveSession {
        session,
        spirc_task: Box::pin(spirc_task),
        player_events_handle,
    })
}

/// Tears down a live session: shuts down Spirc (taken from `spirc_slot()`), invalidates
/// the Session (so any still-running `spirc_task` completes), aborts the player-event
/// task, and clears the global slots so JNI transport/token calls become safe no-ops.
/// Used both on takeover (before building the replacement) and on shutdown. Does NOT
/// await the spirc_task — the caller decides whether to drain (spawn) or drop it.
fn teardown_active_session(active: &mut ActiveSession) {
    if let Some(spirc) = spirc_slot().lock().unwrap_or_else(|poison| poison.into_inner()).take() {
        let _ = spirc.shutdown(); // 0.8: returns Result<(), Error>
    }
    if !active.session.is_invalid() {
        active.session.shutdown();
    }
    // Stop the player-event forwarding task; the player/mixer are dropped with it.
    active.player_events_handle.abort();
    // Token requests after the session ends become safe no-ops.
    *session_slot().lock().unwrap_or_else(|poison| poison.into_inner()) = None;
}

/// Cached track metadata `(title, artist, duration_ms, cover_url)`.
type TrackMetadata = (String, String, u32, Option<String>);
/// Fingerprint of the last published playback event, used to drop exact dupes.
type EventFingerprint = (String, SpotifyUri, i64, i64);

async fn consume_player_events(session: Session, mut event_channel: PlayerEventChannel) {
    let mut metadata_cache: HashMap<SpotifyUri, TrackMetadata> = HashMap::new();
    let mut last_published: Option<EventFingerprint> = None;
    // When set, playback is paused/stopped and the session is released if it stays idle
    // past this instant (IDLE_SESSION_TIMEOUT). Cleared on (re)start so an actively
    // playing session is never auto-released. `Instant` is absolute, so recreating the
    // timer each loop iteration does not drift. Copied into a fresh local per iteration
    // (and moved into the timer future) so the timer never borrows `idle_deadline`,
    // leaving the event arm free to mutate it.
    let mut idle_deadline: Option<Instant> = None;

    loop {
        let deadline = idle_deadline;
        tokio::select! {
            biased;
            maybe_event = event_channel.recv() => {
                let Some(event) = maybe_event else { break; };
                match event {
                    // 0.8: `Started` removed; `Loading` carries play_request_id+track_id+position_ms.
                    PlayerEvent::Loading { track_id, position_ms, .. } => {
                        idle_deadline = None;
                        publish_track_event(&session, "LOADING", track_id, position_ms, 0, &mut metadata_cache, &mut last_published).await;
                    }
                    // 0.8: Playing/Paused no longer carry duration_ms; pass 0 and let metadata fill it.
                    PlayerEvent::Playing { track_id, position_ms, .. } => {
                        idle_deadline = None;
                        publish_track_event(&session, "PLAYING", track_id, position_ms, 0, &mut metadata_cache, &mut last_published).await;
                    }
                    PlayerEvent::Paused { track_id, position_ms, .. } => {
                        idle_deadline = Some(Instant::now() + IDLE_SESSION_TIMEOUT);
                        publish_track_event(&session, "PAUSED", track_id, position_ms, 0, &mut metadata_cache, &mut last_published).await;
                    }
                    PlayerEvent::Stopped { track_id, .. } => {
                        idle_deadline = Some(Instant::now() + IDLE_SESSION_TIMEOUT);
                        publish_track_event(&session, "STOPPED", track_id, 0, 0, &mut metadata_cache, &mut last_published).await;
                    }
                    PlayerEvent::Unavailable { track_id, .. } => {
                        idle_deadline = Some(Instant::now() + IDLE_SESSION_TIMEOUT);
                        publish_track_event(&session, "UNAVAILABLE", track_id, 0, 0, &mut metadata_cache, &mut last_published).await;
                    }
                    PlayerEvent::Preloading { track_id } => {
                        if let Some((title, artist, _duration, _cover)) = resolve_cached_metadata(&session, track_id, &mut metadata_cache).await {
                            info!("Preloading possible next track: {} — {}", title, artist);
                        }
                    }
                    _ => {}
                }
            }
            _ = async move {
                match deadline {
                    Some(d) => sleep_until(d).await,
                    None => future::pending::<()>().await,
                }
            }, if deadline.is_some() => {
                info!(
                    "Session idle for {}s; releasing it and returning the receiver to idle",
                    IDLE_SESSION_TIMEOUT.as_secs()
                );
                // Invalidate the session so spirc_task ends; the discovery loop's
                // session-ended arm then tears down and notifies Android (-> idle).
                session.shutdown();
                break;
            }
        }
    }
}

async fn publish_track_event(
    session: &Session,
    playback_state: &str,
    track_id: SpotifyUri,
    position_ms: u32,
    duration_ms: u32,
    metadata_cache: &mut HashMap<SpotifyUri, TrackMetadata>,
    last_published: &mut Option<EventFingerprint>,
) {
    let metadata = if matches!(playback_state, "STOPPED") {
        None
    } else {
        resolve_cached_metadata(session, track_id.clone(), metadata_cache).await
    };

    let (title, artist, metadata_duration_ms, cover_url) = metadata.unwrap_or_else(|| {
        if matches!(playback_state, "UNAVAILABLE") {
            ("Track unavailable".to_string(), "Unknown artist".to_string(), 0, None)
        } else {
            ("Unknown track".to_string(), "Unknown artist".to_string(), 0, None)
        }
    });
    let final_duration_ms = if duration_ms > 0 { duration_ms } else { metadata_duration_ms };

    // base62 (22-char) track id for the Android UI. Compute from a borrow BEFORE track_id is
    // moved into the fingerprint below — 0.8's SpotifyUri is not Copy (Local/Unknown hold Strings).
    // 0.8 deprecated to_base62() in favour of SpotifyUri::to_id() (same value; to_base62 just
    // forwards to it). Returns Err for local/unknown URIs, which .ok() maps to None.
    let track_id_base62 = track_id.to_id().ok();

    // Coalesce exact-duplicate consecutive events.
    let fingerprint: EventFingerprint = (
        playback_state.to_string(),
        track_id,
        position_ms as i64,
        final_duration_ms as i64,
    );
    if last_published.as_ref() == Some(&fingerprint) {
        return;
    }
    *last_published = Some(fingerprint);

    send_native_playback_event(
        playback_state,
        Some(&title),
        Some(&artist),
        position_ms as i64,
        final_duration_ms as i64,
        None,
        None,
        true,
        cover_url.as_deref(),
        track_id_base62.as_deref(),
    );
}

/// Resolves track metadata, consulting (and populating) the per-session cache so
/// the same track id is only fetched from Spotify once per session.
async fn resolve_cached_metadata(
    session: &Session,
    track_id: SpotifyUri,
    metadata_cache: &mut HashMap<SpotifyUri, TrackMetadata>,
) -> Option<TrackMetadata> {
    if let Some(cached) = metadata_cache.get(&track_id) {
        return Some(cached.clone());
    }
    let resolved = resolve_spotify_metadata(session, &track_id).await;
    if let Some(metadata) = &resolved {
        metadata_cache.insert(track_id, metadata.clone());
    }
    resolved
}

async fn resolve_spotify_metadata(session: &Session, track_id: &SpotifyUri) -> Option<TrackMetadata> {
    // 0.8: Metadata::get takes &SpotifyUri; Track::get validates the URI is a Track variant
    // (an Episode URI falls through to Episode::get below).
    if let Ok(track) = Track::get(session, track_id).await {
        // 0.8: track.artists is Vec<Artist> with names inline — no per-artist fetch.
        let mut artist_names = Vec::new();
        for artist in track.artists.iter().take(4) {
            if !artist.name.trim().is_empty() {
                artist_names.push(artist.name.clone());
            }
        }
        let artists = if artist_names.is_empty() {
            "Unknown artist".to_string()
        } else {
            artist_names.join(", ")
        };
        // 0.8: track.album is the embedded Album; covers is Vec<Image>, each Image has id: FileId.
        let cover_url = track.album.covers.first().and_then(|img| cover_url_from_file(&img.id));
        return Some((track.name, artists, track.duration.max(0) as u32, cover_url));
    }

    if let Ok(episode) = Episode::get(session, track_id).await {
        // 0.8: episode.show (id) is gone; show_name is inline. No Show::get needed.
        let show_name = if episode.show_name.trim().is_empty() {
            "Podcast".to_string()
        } else {
            episode.show_name.clone()
        };
        let cover_url = episode.covers.first().and_then(|img| cover_url_from_file(&img.id));
        return Some((episode.name, show_name, episode.duration.max(0) as u32, cover_url));
    }

    // SpotifyUri may not impl Debug; log the URI string form.
    error!("Failed to resolve metadata for player event track id: {:?}", track_id.to_uri());
    None
}

/// Builds the public CDN URL for a Spotify image `FileId` (40-char base16 hex).
fn cover_url_from_file(file: &FileId) -> Option<String> {
    file.to_base16().ok().map(|hex| format!("https://i.scdn.co/image/{}", hex))
}

fn send_native_receiver_connected(username: &str) {
    let Some(java_vm) = JAVA_VM.get() else {
        error!("Cannot publish connected event before JavaVM is initialized");
        return;
    };
    let Some(service_class) = SPOTIFY_SERVICE_CLASS.get() else {
        error!("Cannot publish connected event before SpotifyService class is initialized");
        return;
    };
    let Ok(mut env) = java_vm.attach_current_thread() else {
        error!("Failed to attach Rust connect thread to JVM");
        return;
    };

    let username_obj = match env.new_string(username) {
        Ok(value) => JObject::from(value),
        Err(e) => {
            error!("Failed to create session username Java string: {:?}", e);
            return;
        }
    };

    if let Err(e) = env.call_static_method(
        service_class,
        "onNativeReceiverConnected",
        "(Ljava/lang/String;)V",
        &[JValue::Object(&username_obj)],
    ) {
        error!("Failed to publish native connected event to Android: {:?}", e);
    }

    if let Ok(true) = env.exception_check() {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
        error!("Android threw while handling native connected event");
    }
}

/// Notifies Android that the active session ended on its own (network loss, remote
/// logout, or the idle timeout) and the receiver has returned to idle/discoverable.
/// Mirrors `send_native_receiver_connected` but carries no payload. NOT fired on a
/// takeover — there the following connected callback updates the UI directly.
fn send_native_receiver_disconnected() {
    let Some(java_vm) = JAVA_VM.get() else {
        error!("Cannot publish disconnected event before JavaVM is initialized");
        return;
    };
    let Some(service_class) = SPOTIFY_SERVICE_CLASS.get() else {
        error!("Cannot publish disconnected event before SpotifyService class is initialized");
        return;
    };
    let Ok(mut env) = java_vm.attach_current_thread() else {
        error!("Failed to attach Rust disconnect thread to JVM");
        return;
    };

    if let Err(e) = env.call_static_method(
        service_class,
        "onNativeReceiverDisconnected",
        "()V",
        &[],
    ) {
        error!("Failed to publish native disconnected event to Android: {:?}", e);
    }

    if let Ok(true) = env.exception_check() {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
        error!("Android threw while handling native disconnected event");
    }
}

fn send_native_access_token(token: Option<&str>, expires_in: i32) {
    let Some(java_vm) = JAVA_VM.get() else {
        error!("Cannot publish access token before JavaVM is initialized");
        return;
    };
    let Some(service_class) = SPOTIFY_SERVICE_CLASS.get() else {
        error!("Cannot publish access token before SpotifyService class is initialized");
        return;
    };
    let Ok(mut env) = java_vm.attach_current_thread() else {
        error!("Failed to attach Rust token thread to JVM");
        return;
    };

    let token_obj = match token {
        Some(value) => match env.new_string(value) {
            Ok(jstr) => JObject::from(jstr),
            Err(e) => {
                error!("Failed to create access token Java string: {:?}", e);
                return;
            }
        },
        None => JObject::null(),
    };

    if let Err(e) = env.call_static_method(
        service_class,
        "onNativeAccessToken",
        "(Ljava/lang/String;I)V",
        &[JValue::Object(&token_obj), JValue::Int(expires_in)],
    ) {
        error!("Failed to publish access token to Android: {:?}", e);
    }

    if let Ok(true) = env.exception_check() {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
        error!("Android threw while handling native access token");
    }
}

fn send_native_playback_event(
    playback_state: &str,
    title: Option<&str>,
    artist: Option<&str>,
    elapsed_ms: i64,
    duration_ms: i64,
    queue_title: Option<&str>,
    queue_artist: Option<&str>,
    queue_unavailable: bool,
    cover_url: Option<&str>,
    track_id: Option<&str>,
) {
    let Some(java_vm) = JAVA_VM.get() else {
        error!("Cannot publish playback event before JavaVM is initialized");
        return;
    };
    let Some(service_class) = SPOTIFY_SERVICE_CLASS.get() else {
        error!("Cannot publish playback event before SpotifyService class is initialized");
        return;
    };
    let Ok(mut env) = java_vm.attach_current_thread() else {
        error!("Failed to attach Rust player event thread to JVM");
        return;
    };

    let state_string = match env.new_string(playback_state) {
        Ok(value) => value,
        Err(e) => {
            error!("Failed to create playback state Java string: {:?}", e);
            return;
        }
    };
    let title_string = title.and_then(|value| env.new_string(value).ok());
    let artist_string = artist.and_then(|value| env.new_string(value).ok());
    let queue_title_string = queue_title.and_then(|value| env.new_string(value).ok());
    let queue_artist_string = queue_artist.and_then(|value| env.new_string(value).ok());
    let cover_string = cover_url.and_then(|value| env.new_string(value).ok());
    let track_id_string = track_id.and_then(|value| env.new_string(value).ok());

    let state_obj = JObject::from(state_string);
    let title_obj = title_string.map(JObject::from).unwrap_or_else(JObject::null);
    let artist_obj = artist_string.map(JObject::from).unwrap_or_else(JObject::null);
    let queue_title_obj = queue_title_string.map(JObject::from).unwrap_or_else(JObject::null);
    let queue_artist_obj = queue_artist_string.map(JObject::from).unwrap_or_else(JObject::null);
    let cover_obj = cover_string.map(JObject::from).unwrap_or_else(JObject::null);
    let track_id_obj = track_id_string.map(JObject::from).unwrap_or_else(JObject::null);
    let queue_flag: u8 = if queue_unavailable { 1 } else { 0 };

    if let Err(e) = env.call_static_method(
        service_class,
        "onNativePlaybackEvent",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJLjava/lang/String;Ljava/lang/String;ZLjava/lang/String;Ljava/lang/String;)V",
        &[
            JValue::Object(&state_obj),
            JValue::Object(&title_obj),
            JValue::Object(&artist_obj),
            JValue::Long(elapsed_ms),
            JValue::Long(duration_ms),
            JValue::Object(&queue_title_obj),
            JValue::Object(&queue_artist_obj),
            JValue::Bool(queue_flag),
            JValue::Object(&cover_obj),
            JValue::Object(&track_id_obj),
        ],
    ) {
        error!("Failed to publish native playback event to Android: {:?}", e);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_rusty_app_NativeBridge_stopDevice(
    _env: JNIEnv,
    _class: JClass,
) {
    info!("stopDevice called");
    let previous = receiver_slot()
        .lock()
        .unwrap_or_else(|poison| poison.into_inner())
        .take();
    match previous {
        Some(state) => spawn_shutdown(state),
        None => info!("stopDevice: no active native receiver to stop"),
    }
}

/// Dispatches a transport command to the active session's `Spirc`, if any.
///
/// `Spirc` methods are non-blocking sends over an mpsc channel, so this is safe to
/// call directly from the Android UI thread. When no session is connected the slot
/// is empty and the command is a logged no-op, so UI taps before/after a session
/// are harmless.
fn dispatch_spirc(name: &str, action: impl FnOnce(&Spirc) -> Result<(), librespot::core::Error>) {
    let guard = spirc_slot().lock().unwrap_or_else(|poison| poison.into_inner());
    match guard.as_ref() {
        Some(spirc) => match action(spirc) {
            Ok(_) => info!("Spirc transport command '{}' dispatched", name),
            Err(e) => error!("Spirc transport command '{}' failed: {:?}", name, e),
        },
        None => info!("Spirc transport command '{}' ignored: no active session", name),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_rusty_app_NativeBridge_play(
    _env: JNIEnv,
    _class: JClass,
) {
    dispatch_spirc("play", |spirc| spirc.play());
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_rusty_app_NativeBridge_pause(
    _env: JNIEnv,
    _class: JClass,
) {
    dispatch_spirc("pause", |spirc| spirc.pause());
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_rusty_app_NativeBridge_nextTrack(
    _env: JNIEnv,
    _class: JClass,
) {
    dispatch_spirc("next", |spirc| spirc.next());
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_rusty_app_NativeBridge_previousTrack(
    _env: JNIEnv,
    _class: JClass,
) {
    dispatch_spirc("previous", |spirc| spirc.prev());
}

/// Renames the running receiver in place: signals the discovery loop to re-advertise
/// the mDNS service under `device_name_java` (keeping the same device id) without
/// restarting the runtime, foreground service, or UI. If a session is actively
/// playing the new name is applied when that session next goes idle; renames while
/// idle take effect immediately.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_rusty_app_NativeBridge_renameDevice(
    mut env: JNIEnv,
    _class: JClass,
    device_name_java: JString,
) {
    let new_name: String = match env.get_string(&device_name_java) {
        Ok(value) => Into::<String>::into(value).trim().to_string(),
        Err(e) => {
            error!("renameDevice: failed to read new device name: {:?}", e);
            return;
        }
    };
    if new_name.is_empty() {
        info!("renameDevice: ignoring empty name");
        return;
    }

    let mut guard = receiver_slot()
        .lock()
        .unwrap_or_else(|poison| poison.into_inner());
    match guard.as_mut() {
        Some(state) => {
            // Keep device_name in sync so a later startDevice() with this name is
            // treated as already-running (idempotent) instead of a full restart.
            state.device_name = new_name.clone();
            if state.rename_tx.send(new_name.clone()).is_err() {
                error!("renameDevice: discovery loop has ended; rename not applied");
            } else {
                info!("renameDevice: signalled in-place re-advertise as '{}'", new_name);
            }
        }
        None => info!("renameDevice: no active native receiver to rename"),
    }
}

/// Asynchronously mints a Spotify access token over the active session's Mercury connection and
/// delivers it to Android via `SpotifyService.onNativeAccessToken`. Non-blocking: returns
/// immediately and spawns the request on the runtime. A no-op (logged) when no session is active.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_rusty_app_NativeBridge_requestAccessToken(
    _env: JNIEnv,
    _class: JClass,
) {
    let session = match session_slot().lock().unwrap_or_else(|p| p.into_inner()).as_ref().cloned() {
        Some(session) => session,
        None => {
            info!("requestAccessToken: no active session; ignoring");
            return;
        }
    };
    let handle = match runtime_handle_slot().lock().unwrap_or_else(|p| p.into_inner()).as_ref().cloned() {
        Some(handle) => handle,
        None => {
            error!("requestAccessToken: no runtime handle available");
            return;
        }
    };

    handle.spawn(async move {
        // 0.8: the `keymaster` module was removed. Access tokens now come from the session's
        // TokenProvider, which hits the same hm://keymaster/token/authenticated endpoint over
        // Mercury. Pass the web-player client id explicitly (as the old code did) so the requested
        // scopes are permitted — plain get_token() would use the session's Android client id,
        // which may be rejected for these scopes. `expires_in` is a Duration in 0.8.
        match session
            .token_provider()
            .get_token_with_client_id(TOKEN_SCOPES, TOKEN_CLIENT_ID)
            .await
        {
            Ok(token) => {
                let expires_in = token.expires_in.as_secs() as i32;
                info!("Access token acquired (expires in {}s)", expires_in);
                send_native_access_token(Some(&token.access_token), expires_in);
            }
            Err(e) => {
                error!("Failed to acquire access token: {:?}", e);
                send_native_access_token(None, 0);
            }
        }
    });
}
