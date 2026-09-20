//! Bridge policy: graceful stream EOF drains the opposite direction. Explicit stop,
//! I/O error or deadline is an abort (an unwritable peer cannot be drained reliably).
//! Framed reads are retained across idle checks; cancellation always ends the stream.
use std::{future::Future, sync::Arc, time::Duration};
use tokio_util::sync::CancellationToken;

/// Cancel/timeout is terminal: callers must drop the transport, never restart a
/// partially completed framed write or read on the same connection.
pub async fn bounded_io<F: Future>(
    future: F,
    cancel: &CancellationToken,
    timeout: Duration,
) -> std::io::Result<F::Output> {
    tokio::select! {
        biased;
        _ = cancel.cancelled() => Err(std::io::ErrorKind::Interrupted.into()),
        result = tokio::time::timeout(timeout, future) => result.map_err(|_| std::io::ErrorKind::TimedOut.into()),
    }
}

#[derive(Clone)]
pub struct BridgeActivity {
    state: Arc<parking_lot::Mutex<BridgeActivityState>>,
}

struct BridgeActivityState {
    last_data: tokio::time::Instant,
    last_transport: tokio::time::Instant,
    half_closed: bool,
    transport_checker: Option<Arc<dyn Fn() -> (Duration, bool) + Send + Sync>>,
}

impl BridgeActivity {
    pub fn new() -> Self {
        let now = tokio::time::Instant::now();
        Self {
            state: Arc::new(parking_lot::Mutex::new(BridgeActivityState {
                last_data: now,
                last_transport: now,
                half_closed: false,
                transport_checker: None,
            })),
        }
    }

    pub fn with_transport_health<F>(checker: F) -> Self
    where
        F: Fn() -> (Duration, bool) + Send + Sync + 'static,
    {
        let now = tokio::time::Instant::now();
        Self {
            state: Arc::new(parking_lot::Mutex::new(BridgeActivityState {
                last_data: now,
                last_transport: now,
                half_closed: false,
                transport_checker: Some(Arc::new(checker)),
            })),
        }
    }

    pub fn with_ws(ws: Arc<crate::ws::RawWebSocket>) -> Self {
        Self::with_transport_health(move || {
            (ws.heartbeat_idle_for(), ws.is_closed())
        })
    }

    pub fn touch(&self) {
        let mut state = self.state.lock();
        let now = tokio::time::Instant::now();
        state.last_data = now;
        state.last_transport = now;
    }

    pub fn touch_transport(&self) {
        self.state.lock().last_transport = tokio::time::Instant::now();
    }

    /// WS cannot carry a TCP FIN. Drain responses until peer close or data-idle timeout.
    /// Pongs do not keep a half-closed application session alive indefinitely.
    pub fn upload_eof(&self) {
        let mut state = self.state.lock();
        state.last_data = tokio::time::Instant::now();
        state.half_closed = true;
    }

    pub fn is_half_closed(&self) -> bool {
        self.state.lock().half_closed
    }

    pub async fn wait<F: Future>(
        &self,
        future: F,
        cancel: &CancellationToken,
    ) -> std::io::Result<F::Output> {
        tokio::pin!(future);
        let mut tick = tokio::time::interval(Duration::from_secs(1));
        loop {
            tokio::select! {
                biased;
                _ = cancel.cancelled() => return Err(std::io::ErrorKind::Interrupted.into()),
                result = &mut future => return Ok(result),
                _ = tick.tick() => {
                    let outcome = {
                        let state = self.state.lock();
                        if state.half_closed {
                            if state.last_data.elapsed() >= Duration::from_secs(30) {
                                Some(std::io::ErrorKind::TimedOut)
                            } else {
                                None
                            }
                        } else {
                            if let Some(ref checker) = state.transport_checker {
                                let (_, closed) = checker();
                                if closed {
                                    Some(std::io::ErrorKind::ConnectionReset)
                                } else {
                                    // The transport owns its heartbeat deadline. A separate
                                    // 20s cutoff would kill healthy sockets before their
                                    // profile's first ping (20-60s) and bypass recovery.
                                    // Data idle != dead: keep flow open up to profile-aware absolute timeout.
                                    let abs_timeout = crate::config::profile_aware_absolute_idle_timeout();
                                    if state.last_data.elapsed() >= abs_timeout {
                                        Some(std::io::ErrorKind::TimedOut)
                                    } else {
                                        None
                                    }
                                }
                            } else {
                                let abs_timeout = crate::config::profile_aware_absolute_idle_timeout();
                                let idle = state.last_data.elapsed().min(state.last_transport.elapsed());
                                if idle >= abs_timeout {
                                    Some(std::io::ErrorKind::TimedOut)
                                } else {
                                    None
                                }
                            }
                        }
                    };

                    if let Some(err_kind) = outcome {
                        return Err(err_kind.into());
                    }
                }
            }
        }
    }
}
