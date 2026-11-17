use crate::{linfo, lwarn};
use once_cell::sync::Lazy;
use parking_lot::RwLock;
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU64, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio_util::sync::CancellationToken;

// Uplink modes
pub const UPLINK_WORKER: i32 = 0;
pub const UPLINK_AWG: i32 = 4;

pub const STAGE_NONE: i32 = 0;
pub const STAGE_AWG: i32 = 2;

// Compatibility aliases
pub const CASCADE_STAGE_NONE: i32 = STAGE_NONE;
pub const CASCADE_STAGE_AWG: i32 = STAGE_AWG;

/// Immutable association transport and configuration identity; never reselected per target.
#[derive(Debug, Clone)]
pub struct UdpRoutePin {
    pub route: RouteKind,
    generation: crate::generation_guard::GenerationStamp,
    mode: i32,
    intent_generation: u64,
}

pub enum SocksUdpUplink {
    Awg(crate::awg::AwgTunnel),
}

tokio::task_local! {
    static ACQUIRE_GENERATION: crate::generation_guard::GenerationStamp;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NodeOperator {
    None,
    CloudflareWarp,
}

impl NodeOperator {
    pub fn is_private(&self) -> bool {
        false
    }

    pub fn display_name(&self) -> &'static str {
        match self {
            NodeOperator::None => "None",
            NodeOperator::CloudflareWarp => "Cloudflare Anycast",
        }
    }

    pub fn id_str(&self) -> &'static str {
        match self {
            NodeOperator::None => "none",
            NodeOperator::CloudflareWarp => "cloudflare_warp",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RouteKind {
    None,
    Awg, // Cloudflare WARP AmneziaWG
}

impl RouteKind {
    pub fn stage_code(&self) -> i32 {
        match self {
            RouteKind::None => STAGE_NONE,
            RouteKind::Awg => STAGE_AWG,
        }
    }

    pub fn id_str(&self) -> &'static str {
        match self {
            RouteKind::None => "none",
            RouteKind::Awg => "warp_awg",
        }
    }

    pub fn display_name(&self) -> &'static str {
        match self {
            RouteKind::None => "None",
            RouteKind::Awg => "WARP AmneziaWG",
        }
    }

    pub fn operator(&self, _is_private: bool) -> NodeOperator {
        match self {
            RouteKind::None => NodeOperator::None,
            RouteKind::Awg => NodeOperator::CloudflareWarp,
        }
    }

    pub fn effective_display_name(&self, _is_private: bool) -> &'static str {
        match self {
            RouteKind::None => "Not connected",
            RouteKind::Awg => "WARP AmneziaWG (Anycast)",
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub struct TrustPolicy {
    pub is_private_node: bool,
    pub allow_public_relay_fallback: bool,
}

impl Default for TrustPolicy {
    fn default() -> Self {
        Self {
            is_private_node: false,
            allow_public_relay_fallback: false,
        }
    }
}

#[derive(Debug, Clone)]
pub struct TransitionRecord {
    pub generation: u64,
    pub timestamp_ms: u64,
    pub from_route: String,
    pub to_route: String,
    pub reason: String,
}

#[derive(Debug, Clone)]
pub struct RoutingIntent {
    pub mode: i32,
    pub generation: u64,
    pub fallback_enabled: bool,
}

pub struct RouteSupervisor {
    route_generation: AtomicU64,
    active_stage: AtomicI32,
    intent: RwLock<RoutingIntent>,
    active_route: RwLock<RouteKind>,
    trace: RwLock<VecDeque<TransitionRecord>>,
    trust_policy: RwLock<TrustPolicy>,
    awg_cooldown_until_ms: AtomicU64,
    transport_ready: AtomicBool,
    app_ready: AtomicBool,
    last_app_error: RwLock<String>,
    app_success_count: AtomicU64,
    app_failure_count: AtomicU64,
}

impl RouteSupervisor {
    pub fn new() -> Self {
        Self {
            route_generation: AtomicU64::new(1),
            active_stage: AtomicI32::new(CASCADE_STAGE_NONE),
            intent: RwLock::new(RoutingIntent {
                mode: UPLINK_WORKER,
                generation: 1,
                fallback_enabled: true,
            }),
            active_route: RwLock::new(RouteKind::None),
            trace: RwLock::new(VecDeque::with_capacity(32)),
            trust_policy: RwLock::new(TrustPolicy::default()),
            awg_cooldown_until_ms: AtomicU64::new(0),
            transport_ready: AtomicBool::new(false),
            app_ready: AtomicBool::new(false),
            last_app_error: RwLock::new(String::new()),
            app_success_count: AtomicU64::new(0),
            app_failure_count: AtomicU64::new(0),
        }
    }

    pub fn set_trust_policy(
        &self,
        is_private_node: bool,
        allow_public_relay_fallback: bool,
    ) {
        let mut p = self.trust_policy.write();
        p.is_private_node = is_private_node;
        p.allow_public_relay_fallback = allow_public_relay_fallback;
        linfo!(
            "RouteSupervisor: trust policy updated -> is_private={}, allow_public_fallback={}",
            is_private_node, allow_public_relay_fallback
        );
    }

    pub fn get_trust_policy(&self) -> TrustPolicy {
        *self.trust_policy.read()
    }

    pub fn get_generation(&self) -> u64 {
        self.route_generation.load(Ordering::SeqCst)
    }

    pub fn get_active_stage(&self) -> i32 {
        self.active_stage.load(Ordering::Relaxed)
    }

    pub fn get_active_route(&self) -> RouteKind {
        *self.active_route.read()
    }

    pub fn record_transport_readiness(&self, ready: bool) {
        self.transport_ready.store(ready, Ordering::Relaxed);
    }

    pub fn is_transport_ready(&self) -> bool {
        self.transport_ready.load(Ordering::Relaxed)
    }

    pub fn record_app_readiness(&self, ready: bool, error: Option<&str>) {
        self.app_ready.store(ready, Ordering::Relaxed);
        if ready {
            self.app_success_count.fetch_add(1, Ordering::Relaxed);
        } else {
            self.app_failure_count.fetch_add(1, Ordering::Relaxed);
            if let Some(err) = error {
                *self.last_app_error.write() = err.to_string();
            }
        }
    }

    pub fn is_app_ready(&self) -> bool {
        self.app_ready.load(Ordering::Relaxed)
    }

    pub fn get_last_app_error(&self) -> String {
        self.last_app_error.read().clone()
    }

    pub fn reset(&self) {
        self.awg_cooldown_until_ms.store(0, Ordering::Relaxed);
        self.transport_ready.store(false, Ordering::Relaxed);
        self.app_ready.store(false, Ordering::Relaxed);
        *self.last_app_error.write() = String::new();
        self.app_success_count.store(0, Ordering::Relaxed);
        self.app_failure_count.store(0, Ordering::Relaxed);
        self.transition_to(RouteKind::None, "supervisor_reset");
    }

    pub fn set_intent(&self, mode: i32, intent_gen: u64, fallback_enabled: bool) {
        let initial_route = match mode {
            UPLINK_AWG => RouteKind::Awg,
            _ => RouteKind::None,
        };

        {
            let mut intent_guard = self.intent.write();
            intent_guard.mode = mode;
            intent_guard.generation = intent_gen;
            intent_guard.fallback_enabled = fallback_enabled;
        }

        self.awg_cooldown_until_ms.store(0, Ordering::Relaxed);
        self.transport_ready.store(false, Ordering::Relaxed);
        self.app_ready.store(false, Ordering::Relaxed);
        *self.last_app_error.write() = String::new();

        self.transition_to(initial_route, "set_intent");
    }

    pub fn transition_to(&self, new_route: RouteKind, reason: &str) {
        if let Ok(expected) = ACQUIRE_GENERATION.try_with(|stamp| *stamp) {
            self.transition_to_if_current(expected, new_route, reason);
        } else {
            self.transition_to_unchecked(new_route, reason);
        }
    }

    pub fn transition_to_if_current(
        &self,
        expected: crate::generation_guard::GenerationStamp,
        new_route: RouteKind,
        reason: &str,
    ) -> bool {
        crate::generation_guard::apply_if_current(expected, || {
            self.transition_to_unchecked(new_route, reason);
        })
        .is_some()
    }

    pub(crate) fn transition_to_unchecked(&self, new_route: RouteKind, reason: &str) {
        let now_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;

        let mut route_guard = self.active_route.write();
        let old_route = *route_guard;
        if old_route == new_route
            && self.active_stage.load(Ordering::Relaxed) == new_route.stage_code()
        {
            return;
        }

        *route_guard = new_route;
        let new_gen = self.route_generation.fetch_add(1, Ordering::SeqCst) + 1;
        self.active_stage
            .store(new_route.stage_code(), Ordering::Relaxed);
        self.transport_ready.store(false, Ordering::Relaxed);
        self.app_ready.store(false, Ordering::Relaxed);

        let record = TransitionRecord {
            generation: new_gen,
            timestamp_ms: now_ms,
            from_route: old_route.id_str().to_string(),
            to_route: new_route.id_str().to_string(),
            reason: reason.to_string(),
        };

        let mut trace_guard = self.trace.write();
        if trace_guard.len() >= 32 {
            trace_guard.pop_front();
        }
        trace_guard.push_back(record);

        linfo!(
            "RouteSupervisor [gen={}]: transition [{}] -> [{}], reason: {}",
            new_gen,
            old_route.display_name(),
            new_route.display_name(),
            reason
        );
    }

    pub fn report_route_failure(&self, reason: &str) -> i32 {
        let now_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;

        let (_mode, fallback_enabled) = {
            let i = self.intent.read();
            (i.mode, i.fallback_enabled)
        };
        let active = *self.active_route.read();

        match active {
            RouteKind::Awg => {
                self.awg_cooldown_until_ms
                    .store(now_ms + 30_000, Ordering::Relaxed);
                if fallback_enabled {
                    self.transition_to(RouteKind::None, reason);
                }
            }
            RouteKind::None => {}
        }

        self.active_stage.load(Ordering::Relaxed)
    }

    /// Pin the effective UDP transport once, before SOCKS success. Only AWG
    /// supports negotiated UDP capability; non-AWG receives REP=command-not-supported.
    pub fn pin_udp_route(&self) -> Option<UdpRoutePin> {
        let generation = crate::generation_guard::snapshot();
        let intent = self.intent.read().clone();
        let route = match intent.mode {
            UPLINK_AWG => RouteKind::Awg,
            _ => return None,
        };
        Some(UdpRoutePin {
            route,
            generation,
            mode: intent.mode,
            intent_generation: intent.generation,
        })
    }

    pub fn supports_udp(&self) -> bool {
        self.pin_udp_route().is_some()
    }

    pub fn udp_pin_is_current(&self, pin: &UdpRoutePin) -> bool {
        let current = crate::generation_guard::is_current(pin.generation);
        let intent = self.intent.read();
        intent.mode == pin.mode && intent.generation == pin.intent_generation && current
    }

    pub async fn acquire_socks5_udp_uplink(
        &self,
        pin: &UdpRoutePin,
        target_addr: &str,
        cancel_token: &CancellationToken,
    ) -> Option<SocksUdpUplink> {
        if cancel_token.is_cancelled() || !self.udp_pin_is_current(pin) {
            return None;
        }
        // Every target in this association uses the pinned AWG transport.
        let result = tokio::select! {
            biased;
            _ = cancel_token.cancelled() => return None,
            result = tokio::time::timeout(Duration::from_secs(3), async {
                match pin.route {
                    RouteKind::Awg => {
                        crate::awg::awg_acquire_tunnel(target_addr, cancel_token).await.map(SocksUdpUplink::Awg)
                    }
                    RouteKind::None => None,
                }
            }) => result.ok().flatten(),
        };
        if cancel_token.is_cancelled() || !self.udp_pin_is_current(pin) {
            return None;
        }
        result
    }

    pub fn get_route_state_json(&self) -> String {
        let gen = self.route_generation.load(Ordering::SeqCst);
        let stage = self.active_stage.load(Ordering::Relaxed);
        let route = *self.active_route.read();
        let intent = self.intent.read().clone();
        let trace_list = self.trace.read().clone();
        let policy = *self.trust_policy.read();
        let is_private = policy.is_private_node;
        let transport_ready = self.transport_ready.load(Ordering::Relaxed);
        let app_ready = self.app_ready.load(Ordering::Relaxed);
        let last_app_err = self.last_app_error.read().clone();
        let app_success_count = self.app_success_count.load(Ordering::Relaxed);
        let app_failure_count = self.app_failure_count.load(Ordering::Relaxed);

        let effective_route = route.effective_display_name(is_private);
        let operator = route.operator(is_private).display_name();
        let trust_maintained = match route {
            RouteKind::None => true,
            RouteKind::Awg => {
                if is_private {
                    policy.allow_public_relay_fallback
                } else {
                    true
                }
            }
        };

        let mut trace_json_items = Vec::new();
        for t in trace_list {
            trace_json_items.push(format!(
                r#"{{"generation":{},"timestamp_ms":{},"from":"{}","to":"{}","reason":"{}"}}"#,
                t.generation,
                t.timestamp_ms,
                escape_json(&t.from_route),
                escape_json(&t.to_route),
                escape_json(&t.reason)
            ));
        }

        let candidates = match intent.mode {
            UPLINK_AWG => r#"["warp_awg"]"#,
            _ => r#"[]"#,
        };

        format!(
            r#"{{"generation":{},"intent_mode":{},"intent_generation":{},"stage_code":{},"stage_name":"{}","effective_route":"{}","operator":"{}","is_private":{},"trust_boundary_maintained":{},"allow_public_relay_fallback":{},"transport_ready":{},"app_ready":{},"supports_udp":{},"last_app_error":"{}","app_success_count":{},"app_failure_count":{},"route_id":"{}","candidates":{},"trace":[{}]}}"#,
            gen,
            intent.mode,
            intent.generation,
            stage,
            route.display_name(),
            effective_route,
            operator,
            is_private,
            trust_maintained,
            policy.allow_public_relay_fallback,
            transport_ready,
            app_ready,
            self.supports_udp(),
            escape_json(&last_app_err),
            app_success_count,
            app_failure_count,
            route.id_str(),
            candidates,
            trace_json_items.join(",")
        )
    }
}

fn escape_json(s: &str) -> String {
    s.replace('\\', "\\\\")
        .replace('"', "\\\"")
        .replace('\n', "\\n")
        .replace('\r', "\\r")
        .replace('\t', "\\t")
}

pub static ROUTE_SUPERVISOR: Lazy<RouteSupervisor> = Lazy::new(RouteSupervisor::new);

pub fn get_active_cascade_stage() -> i32 {
    ROUTE_SUPERVISOR.get_active_stage()
}

pub fn get_route_generation() -> u64 {
    ROUTE_SUPERVISOR.get_generation()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_route_supervisor_supports_udp() {
        let sup = RouteSupervisor::new();

        sup.set_intent(UPLINK_AWG, 1, false);
        assert!(sup.supports_udp());

        sup.set_intent(UPLINK_WORKER, 1, false);
        assert!(!sup.supports_udp());
    }

    #[test]
    fn test_route_state_json_contains_supports_udp() {
        let sup = RouteSupervisor::new();
        sup.set_intent(UPLINK_AWG, 1, false);
        let json = sup.get_route_state_json();
        assert!(json.contains("\"supports_udp\":true"));

        sup.set_intent(UPLINK_WORKER, 1, false);
        let json_worker = sup.get_route_state_json();
        assert!(json_worker.contains("\"supports_udp\":false"));
    }
}
