use std::{
    borrow::Cow,
    collections::BTreeMap,
    net::{Ipv4Addr, Ipv6Addr, SocketAddr, TcpListener},
    sync::{Arc, Mutex},
};

use aes::cipher::{KeyIvInit, StreamCipher};
use base64::engine::Engine as _;
use base64::engine::general_purpose::STANDARD as BASE64;
use bytes::Bytes;
use hmac::{Hmac, Mac};
use http_body_util::{BodyExt, Full};
use hyper::header::{CONTENT_TYPE, HeaderValue};
use hyper::{Method, Request, Response, StatusCode, body::Incoming};

use hyper_util::{rt::TokioIo, server::graceful::GracefulShutdown};
use log::{debug, error, warn};
use serde_json::json;
use sha1::{Digest, Sha1};
use tokio::sync::{mpsc, oneshot};

use super::{DiscoveryError, DiscoveryEvent};

use crate::{
    core::config::DeviceType,
    core::{Error, authentication::Credentials, diffie_hellman::DhLocalKeys},
};

type Aes128Ctr = ctr::Ctr128BE<aes::Aes128>;

type Params<'a> = BTreeMap<Cow<'a, str>, Cow<'a, str>>;

pub struct Alias {
    pub name: Cow<'static, str>,
    pub id: u32,
    pub is_group: bool,
}

pub struct Config {
    pub name: Cow<'static, str>,
    pub device_type: DeviceType,
    pub device_id: String,
    pub is_group: bool,
    pub client_id: String,
    pub aliases: Vec<Alias>,
}

struct RequestHandler {
    config: Config,
    username: Mutex<Option<String>>,
    keys: DhLocalKeys,
    event_tx: mpsc::UnboundedSender<DiscoveryEvent>,
}

impl RequestHandler {
    fn new(config: Config, event_tx: mpsc::UnboundedSender<DiscoveryEvent>) -> Self {
        Self {
            config,
            username: Mutex::new(None),
            keys: DhLocalKeys::random(&mut rand::rng()),
            event_tx,
        }
    }

    /// Every handshake answer is JSON. Say so on the way out — a controller
    /// is entitled to check the content type before parsing the body.
    fn json(body: serde_json::Value) -> Response<Full<Bytes>> {
        let mut response = Response::new(Full::new(Bytes::from(body.to_string())));
        response
            .headers_mut()
            .insert(CONTENT_TYPE, HeaderValue::from_static("application/json"));
        response
    }

    fn active_user(&self) -> String {
        if let Ok(maybe_username) = self.username.lock() {
            maybe_username.clone().unwrap_or(String::new())
        } else {
            warn!("username lock corrupted; read failed");
            String::from("!")
        }
    }

    fn handle_get_info(&self) -> Response<Full<Bytes>> {
        let public_key = BASE64.encode(self.keys.public_key());
        let device_type: &str = self.config.device_type.into();
        let active_user = self.active_user();

        // options based on zeroconf guide, search for `groupStatus` on page
        let group_status = if self.config.is_group {
            "GROUP"
        } else {
            "NONE"
        };

        // See: https://developer.spotify.com/documentation/commercial-hardware/implementation/guides/zeroconf/
        Self::json(json!({
            "status": 101,
            "statusString": "OK",
            "spotifyError": 0,
            // departing from the Spotify documentation, Google Cast uses "5.0.0"
            "version": "2.9.0",
            "deviceID": (self.config.device_id),
            "deviceType": (device_type),
            "remoteName": (self.config.name),
            // valid value seen in the wild: "empty"
            "publicKey": (public_key),
            "brandDisplayName": "librespot",
            "modelDisplayName": "librespot",
            "libraryVersion": crate::core::version::SEMVER,
            "resolverVersion": "1",
            // valid values are "GROUP" and "NONE"
            "groupStatus": group_status,
            // valid value documented & seen in the wild: "accesstoken"
            // Using it will cause clients to fail to connect.
            "tokenType": "default",
            "clientID": (self.config.client_id),
            "productID": 0,
            // Other known scope: client-authorization-universal
            // Comma-separated.
            "scope": "streaming",
            "availability": "",
            "supported_drm_media_formats": [],
            // TODO: bitmask but what are the flags?
            "supported_capabilities": 1,
            // undocumented but should still work
            "accountReq": "PREMIUM",
            "activeUser": active_user,
            "aliases": self.config.aliases.iter().map(|alias| {
                json!({
                    "name": alias.name,
                    "id": alias.id.to_string(),
                    "isGroup": alias.is_group.to_string(),
                })
            }).collect::<Vec<_>>(),
            // others seen-in-the-wild:
            // - "deviceAPI_isGroup": False
        }))
    }

    fn handle_add_user(&self, params: &Params<'_>) -> Result<Response<Full<Bytes>>, Error> {
        let username_key = "userName";
        let username = params
            .get(username_key)
            .ok_or(DiscoveryError::ParamsError(username_key))?
            .as_ref();

        let blob_key = "blob";
        let encrypted_blob = params
            .get(blob_key)
            .ok_or(DiscoveryError::ParamsError(blob_key))?;

        let clientkey_key = "clientKey";
        let client_key = params
            .get(clientkey_key)
            .ok_or(DiscoveryError::ParamsError(clientkey_key))?;

        let encrypted_blob = BASE64.decode(encrypted_blob.as_bytes())?;

        let client_key = BASE64.decode(client_key.as_bytes())?;
        let shared_key = self.keys.shared_secret(&client_key);

        let encrypted_blob_len = encrypted_blob.len();
        if encrypted_blob_len < 16 {
            return Err(DiscoveryError::HmacError(encrypted_blob.to_vec()).into());
        }

        let iv = &encrypted_blob[0..16];
        let encrypted = &encrypted_blob[16..encrypted_blob_len - 20];
        let cksum = &encrypted_blob[encrypted_blob_len - 20..encrypted_blob_len];

        let base_key = Sha1::digest(shared_key);
        let base_key = &base_key[..16];

        let checksum_key = {
            let mut h = Hmac::<Sha1>::new_from_slice(base_key)
                .map_err(|_| DiscoveryError::HmacError(base_key.to_vec()))?;
            h.update(b"checksum");
            h.finalize().into_bytes()
        };

        let encryption_key = {
            let mut h = Hmac::<Sha1>::new_from_slice(base_key)
                .map_err(|_| DiscoveryError::HmacError(base_key.to_vec()))?;
            h.update(b"encryption");
            h.finalize().into_bytes()
        };

        let mut h = Hmac::<Sha1>::new_from_slice(&checksum_key)
            .map_err(|_| DiscoveryError::HmacError(base_key.to_vec()))?;
        h.update(encrypted);
        if h.verify_slice(cksum).is_err() {
            warn!("Login error for user {username:?}: MAC mismatch");
            return Ok(Self::json(json!({
                "status": 102,
                "spotifyError": 1,
                "statusString": "ERROR-MAC"
            })));
        }

        let decrypted = {
            let mut data = encrypted.to_vec();
            let mut cipher = Aes128Ctr::new_from_slices(&encryption_key[0..16], iv)
                .map_err(DiscoveryError::AesError)?;
            cipher.apply_keystream(&mut data);
            data
        };

        let credentials = Credentials::with_blob(username, decrypted, &self.config.device_id)?;

        {
            let maybe_username = self.username.lock();
            self.event_tx
                .send(DiscoveryEvent::Credentials(credentials))?;
            if let Ok(mut username_field) = maybe_username {
                *username_field = Some(String::from(username));
            } else {
                warn!("username lock corrupted; write failed");
            }
        }

        Ok(Self::json(json!({
            "status": 101,
            "spotifyError": 0,
            "statusString": "OK",
        })))
    }

    fn not_found(&self) -> Response<Full<Bytes>> {
        let mut res = Response::default();
        *res.status_mut() = StatusCode::NOT_FOUND;
        res
    }

    /// Reply to a request that could not be processed at all — a missing
    /// parameter, a blob that is not valid base64, a blob too short to hold an
    /// IV and a checksum. Uses the same error shape as the checksum mismatch
    /// in [`Self::handle_add_user`] so a controller always gets a parseable
    /// answer instead of a dropped connection.
    fn error() -> Response<Full<Bytes>> {
        Self::json(json!({
            "status": 102,
            "spotifyError": 1,
            "statusString": "ERROR-INVALID-ARGUMENTS"
        }))
    }

    async fn handle(
        self: Arc<Self>,
        request: Request<Incoming>,
    ) -> Result<hyper::Result<Response<Full<Bytes>>>, Error> {
        let mut params = Params::new();

        let (parts, body) = request.into_parts();

        if let Some(query) = parts.uri.query() {
            let query_params = form_urlencoded::parse(query.as_bytes());
            params.extend(query_params);
        }

        if parts.method != Method::GET {
            debug!("{:?} {:?} {:?}", parts.method, parts.uri.path(), params);
        }

        let body = body.collect().await?.to_bytes();

        params.extend(form_urlencoded::parse(&body));

        let action = params.get("action").map(Cow::as_ref);

        // Dispatch on the action alone. The verb a controller picks is not
        // part of what identifies the request, and refusing an action because
        // it arrived over the other verb strands the controller on a 404.
        Ok(Ok(match action {
            Some("getInfo") => self.handle_get_info(),
            Some("addUser") => self.handle_add_user(&params).inspect_err(|e| {
                // Name the parameters the controller actually sent (never their
                // values — the blob and client key are secrets). Which one is
                // missing, or that all three arrived and the blob still would
                // not open, is the whole diagnosis for a refused handshake.
                error!(
                    "Spotify Connect handshake rejected: {e} — request carried {:?}",
                    params.keys().collect::<Vec<_>>()
                )
            })?,
            _ => self.not_found(),
        }))
    }
}

pub(crate) enum DiscoveryServerCmd {
    Shutdown,
}

pub struct DiscoveryServer {
    close_tx: oneshot::Sender<DiscoveryServerCmd>,
    task_handle: tokio::task::JoinHandle<()>,
}

impl DiscoveryServer {
    pub fn new(
        config: Config,
        port: &mut u16,
        event_tx: mpsc::UnboundedSender<DiscoveryEvent>,
    ) -> Result<Self, Error> {
        let discovery = RequestHandler::new(config, event_tx);
        let address = if cfg!(windows) {
            SocketAddr::new(Ipv4Addr::UNSPECIFIED.into(), *port)
        } else {
            // this creates a dual stack socket on non-windows systems
            SocketAddr::new(Ipv6Addr::UNSPECIFIED.into(), *port)
        };

        let (close_tx, close_rx) = oneshot::channel();

        let listener = match TcpListener::bind(address) {
            Ok(listener) => listener,
            Err(e) => {
                warn!("Discovery server failed to start: {e}");
                return Err(e.into());
            }
        };

        listener.set_nonblocking(true)?;
        let listener = tokio::net::TcpListener::from_std(listener)?;

        match listener.local_addr() {
            Ok(addr) => {
                *port = addr.port();
                debug!("Zeroconf server listening on 0.0.0.0:{}", *port);
            }
            Err(e) => {
                warn!("Discovery server failed to start: {e}");
                return Err(e.into());
            }
        }

        let task_handle = tokio::spawn(async move {
            let discovery = Arc::new(discovery);

            let server = hyper::server::conn::http1::Builder::new();
            let graceful = GracefulShutdown::new();
            let mut close_rx = std::pin::pin!(close_rx);
            loop {
                tokio::select! {
                    Ok((stream, _)) = listener.accept() => {
                        let io = TokioIo::new(stream);
                        let discovery = discovery.clone();

                        let svc = hyper::service::service_fn(move |request| {
                            let discovery = discovery.clone();
                            async move {
                                match discovery.handle(request).await {
                                    Ok(response) => response,
                                    Err(e) => {
                                        error!("could not handle discovery request: {e}");
                                        // A controller that gets no response at all sits
                                        // on "Connecting..." until it times out, so a
                                        // request we cannot process still has to be
                                        // answered.
                                        Ok(RequestHandler::error())
                                    }
                                }
                            }
                        });

                        let conn = server.serve_connection(io, svc);
                        let fut = graceful.watch(conn);
                        tokio::spawn(async move {
                            // Errors are logged in the service_fn
                            let _ = fut.await;
                        });
                    }
                    _ = &mut close_rx => {
                        break;
                    }
                }
            }

            graceful.shutdown().await;
        });

        Ok(Self {
            close_tx,
            task_handle,
        })
    }

    pub async fn shutdown(self) {
        let Self {
            close_tx,
            task_handle,
            ..
        } = self;
        log::debug!("Shutting down discovery server");
        if close_tx.send(DiscoveryServerCmd::Shutdown).is_err() {
            log::warn!("Discovery server unexpectedly disappeared");
        } else {
            let _ = task_handle.await;
            log::debug!("Discovery server stopped");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpStream;
    use std::time::Duration;

    fn test_config() -> Config {
        Config {
            name: "test receiver".into(),
            device_type: DeviceType::Speaker,
            device_id: "0123456789abcdef0123456789abcdef01234567".to_string(),
            is_group: false,
            client_id: "test-client-id".to_string(),
            aliases: vec![],
        }
    }

    /// Speaks raw HTTP/1.1 to the discovery server the way a Spotify controller
    /// does, and returns every byte the server sent back (empty if it hung up
    /// without answering).
    fn raw_request(port: u16, request: &str) -> String {
        let addr = SocketAddr::new(Ipv4Addr::LOCALHOST.into(), port);
        let mut stream = TcpStream::connect(addr).expect("connect to discovery server");
        stream
            .set_read_timeout(Some(Duration::from_secs(5)))
            .expect("set read timeout");
        stream
            .write_all(request.as_bytes())
            .expect("write request");
        let mut response = String::new();
        let _ = stream.read_to_string(&mut response);
        response
    }

    fn post_add_user(port: u16, body: &str) -> String {
        let request = format!(
            "POST /?action=addUser HTTP/1.1\r\nHost: localhost\r\n\
             Content-Type: application/x-www-form-urlencoded\r\n\
             Content-Length: {}\r\nConnection: close\r\n\r\n{body}",
            body.len()
        );
        raw_request(port, &request)
    }

    async fn start_server() -> (DiscoveryServer, u16, mpsc::UnboundedReceiver<DiscoveryEvent>) {
        let (event_tx, event_rx) = mpsc::unbounded_channel();
        let mut port = 0;
        let server = DiscoveryServer::new(test_config(), &mut port, event_tx)
            .expect("discovery server starts");
        (server, port, event_rx)
    }

    fn get(port: u16, target: &str) -> String {
        raw_request(
            port,
            &format!("GET {target} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"),
        )
    }

    fn post(port: u16, target: &str) -> String {
        raw_request(
            port,
            &format!(
                "POST {target} HTTP/1.1\r\nHost: localhost\r\n\
                 Content-Length: 0\r\nConnection: close\r\n\r\n"
            ),
        )
    }

    /// Controllers are not consistent about which verb carries `getInfo`, and
    /// the action is what identifies the request. go-librespot dispatches on
    /// the action alone; answering only `GET` leaves such a controller with a
    /// 404 and no way to start a session.
    #[tokio::test]
    async fn get_info_is_answered_whichever_verb_carries_it() {
        let (server, port, _event_rx) = start_server().await;

        let over_get = tokio::task::spawn_blocking(move || get(port, "/?action=getInfo"))
            .await
            .expect("request task");
        let over_post = tokio::task::spawn_blocking(move || post(port, "/?action=getInfo"))
            .await
            .expect("request task");

        server.shutdown().await;

        assert!(over_get.contains("\"remoteName\":\"test receiver\""), "{over_get:?}");
        assert!(over_post.contains("\"remoteName\":\"test receiver\""), "{over_post:?}");
    }

    /// A parameter the server needs but did not get is a different failure from
    /// a blob it cannot open, and it used to reach the same panic.
    #[tokio::test]
    async fn add_user_missing_a_parameter_still_answers_the_controller() {
        let (server, port, _event_rx) = start_server().await;

        let response = tokio::task::spawn_blocking(move || {
            post_add_user(port, "userName=someone&blob=AAAA")
        })
        .await
        .expect("request task");

        server.shutdown().await;

        assert!(
            response.contains("\"statusString\":\"ERROR-INVALID-ARGUMENTS\""),
            "{response:?}"
        );
    }

    /// A blob that is well-formed but does not carry a matching checksum is a
    /// normal, expected outcome and has its own documented answer. Guards the
    /// path against the response-building rework around it.
    #[tokio::test]
    async fn add_user_with_a_bad_checksum_reports_a_mac_error() {
        let (server, port, mut event_rx) = start_server().await;

        let info = tokio::task::spawn_blocking(move || get(port, "/?action=getInfo"))
            .await
            .expect("request task");
        let public_key = info
            .split("\"publicKey\":\"")
            .nth(1)
            .and_then(|rest| rest.split('"').next())
            .expect("getInfo carries a public key")
            .to_string();

        // A real controller answers with its own DH key; the blob below is the
        // right shape (16 byte IV, 20 byte checksum) but the checksum is junk.
        // Both values are base64, so they go through the form encoder — a raw
        // "+" in a body would arrive as a space.
        let client_keys = DhLocalKeys::random(&mut rand::rng());
        assert_eq!(
            BASE64.decode(&public_key).expect("base64 key").len(),
            client_keys.public_key().len(),
            "the advertised key should be a DH public key of our own key's size"
        );
        let body = form_urlencoded::Serializer::new(String::new())
            .append_pair("userName", "someone")
            .append_pair("blob", &BASE64.encode([0u8; 36]))
            .append_pair("clientKey", &BASE64.encode(client_keys.public_key()))
            .finish();

        let response = tokio::task::spawn_blocking(move || post_add_user(port, &body))
            .await
            .expect("request task");

        server.shutdown().await;

        assert!(
            response.contains("\"statusString\":\"ERROR-MAC\""),
            "{response:?}"
        );
        assert!(
            event_rx.try_recv().is_err(),
            "a blob that failed its checksum must not produce credentials"
        );
    }

    /// The handshake answers are JSON, and a controller is entitled to insist
    /// on being told so before it parses them.
    #[tokio::test]
    async fn handshake_answers_are_labelled_as_json() {
        let (server, port, _event_rx) = start_server().await;

        let response = tokio::task::spawn_blocking(move || get(port, "/?action=getInfo"))
            .await
            .expect("request task");

        server.shutdown().await;

        assert!(
            response.to_lowercase().contains("content-type: application/json"),
            "{response:?}"
        );
    }

    /// Regression test for the "stuck on Connecting..." bug: an `addUser` the
    /// server cannot process must still produce an HTTP response. Answering
    /// with nothing at all leaves the controller waiting forever.
    #[tokio::test]
    async fn add_user_with_an_unusable_blob_still_answers_the_controller() {
        let (server, port, _event_rx) = start_server().await;

        // "AAAA" decodes to three bytes — far too short to be an encrypted blob.
        let response = tokio::task::spawn_blocking(move || {
            post_add_user(port, "userName=someone&blob=AAAA&clientKey=AAAA")
        })
        .await
        .expect("request task");

        server.shutdown().await;

        assert!(
            response.starts_with("HTTP/1.1 "),
            "server hung up without answering; controller would spin forever. Got: {response:?}"
        );
    }
}
