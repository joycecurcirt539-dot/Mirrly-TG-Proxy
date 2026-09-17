use crate::config::OPERA_VPN;
use crate::socks5::{socks5_acquire_cf_ws, SocksUplink};
use crate::{ldebug, linfo, lwarn};
use once_cell::sync::Lazy;
use parking_lot::RwLock;
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU64, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio_util::sync::CancellationToken;

pub const CASCADE_STAGE_NONE: i32 = 0;
pub const CASCADE_STAGE_MASQUE: i32 = 1;
pub const CASCADE_STAGE_AWG: i32 = 2;
pub const CASCADE_STAGE_WORKER: i32 = 3;
pub const CASCADE_STAGE_VLESS: i32 = 4;
pub const CASCADE_STAGE_OPERA: i32 = 5;
pub const CASCADE_STAGE_DIRECT: i32 = 6;
pub const CASCADE_STAGE_VLESS_OPERA_HOP: i32 = 7;

/// Immutable association transport and configuration identity; never reselected per target.
#[derive(Debug, Clone)]
pub struct UdpRoutePin {
    pub route: RouteKind,
    generation: crate::generation_guard::GenerationStamp,
    mode: i32,
    intent_generation: u64,
    vless_config: crate::vless::VlessConfig,
    opera_endpoint: String,
}
pub enum SocksUdpUplink {
    Vless(crate::vless::VlessUplink),
    Masque(crate::masque::MasqueTunnel),
    Awg(crate::awg::AwgTunnel),
}

tokio::task_local! {
    static ACQUIRE_GENERATION: crate::generation_guard::GenerationStamp;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NodeOperator {
    PrivateVps,
    CloudflareWarp,
    CloudflareWorker,
    OperaRelay,
    Direct,
}

impl NodeOperator {
    pub fn is_private(&self) -> bool {
        matches!(self, NodeOperator::PrivateVps)
    }

    pub fn display_name(&self) -> &'static str {
        match self {
            NodeOperator::PrivateVps => "Private VPS",
            NodeOperator::CloudflareWarp => "Cloudflare Anycast",
            NodeOperator::CloudflareWorker => "Cloudflare Worker",
            NodeOperator::OperaRelay => "Opera Proxy",
            NodeOperator::Direct => "Direct TCP",
        }
    }

    pub fn id_str(&self) -> &'static str {
        match self {
            NodeOperator::PrivateVps => "private_vps",
            NodeOperator::CloudflareWarp => "cloudflare_warp",
            NodeOperator::CloudflareWorker => "cloudflare_worker",
            NodeOperator::OperaRelay => "opera_relay",
            NodeOperator::Direct => "direct",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RouteKind {
    None,
    Masque,        // Cloudflare WARP MASQUE HTTP/3
    Awg,           // Cloudflare WARP AmneziaWG
    Worker,        // Cloudflare Worker WSS Relay (Public)
    VlessDirect,   // VLESS direct to VPS / CDN
    VlessOperaHop, // VLESS with Opera as transport hop to VPS
    OperaDirect,   // Opera direct exit HTTP CONNECT to target_addr (Public)
    Direct,        // Direct TCP
}

impl RouteKind {
    pub fn stage_code(&self) -> i32 {
        match self {
            RouteKind::None => CASCADE_STAGE_NONE,
            RouteKind::Masque => CASCADE_STAGE_MASQUE,
            RouteKind::Awg => CASCADE_STAGE_AWG,
            RouteKind::Worker => CASCADE_STAGE_WORKER,
            RouteKind::VlessDirect => CASCADE_STAGE_VLESS,
            RouteKind::OperaDirect => CASCADE_STAGE_OPERA,
            RouteKind::Direct => CASCADE_STAGE_DIRECT,
            RouteKind::VlessOperaHop => CASCADE_STAGE_VLESS_OPERA_HOP,
        }
    }

    pub fn id_str(&self) -> &'static str {
        match self {
            RouteKind::None => "none",
            RouteKind::Masque => "warp_masque",
            RouteKind::Awg => "warp_awg",
            RouteKind::Worker => "cf_worker",
            RouteKind::VlessDirect => "vless_direct",
            RouteKind::VlessOperaHop => "vless_opera_hop",
            RouteKind::OperaDirect => "opera_direct",
            RouteKind::Direct => "direct",
        }
    }

    pub fn display_name(&self) -> &'static str {
        match self {
            RouteKind::None => "None",
            RouteKind::Masque => "WARP MASQUE",
            RouteKind::Awg => "WARP AmneziaWG",
            RouteKind::Worker => "Cloudflare Worker",
            RouteKind::VlessDirect => "VLESS",
            RouteKind::VlessOperaHop => "VLESS via Opera Hop",
            RouteKind::OperaDirect => "Opera VPN",
            RouteKind::Direct => "Direct TCP",
        }
    }

    pub fn operator(&self, is_private: bool) -> NodeOperator {
        match self {
            RouteKind::None => NodeOperator::Direct,
            RouteKind::Masque | RouteKind::Awg => NodeOperator::CloudflareWarp,
            RouteKind::Worker => NodeOperator::CloudflareWorker,
            RouteKind::VlessDirect | RouteKind::VlessOperaHop => {
                if is_private {
                    NodeOperator::PrivateVps
                } else {
                    NodeOperator::CloudflareWorker
                }
            }
            RouteKind::OperaDirect => NodeOperator::OperaRelay,
            RouteKind::Direct => NodeOperator::Direct,
        }
    }

    pub fn effective_display_name(&self, is_private: bool) -> &'static str {
        match self {
            RouteKind::None => "Not connected",
            RouteKind::Masque => "WARP MASQUE (Anycast)",
            RouteKind::Awg => "WARP AmneziaWG (Anycast)",
            RouteKind::Worker => "Cloudflare Worker WSS (Public Relay)",
            RouteKind::VlessDirect => {
                if is_private {
                    "VLESS (Private VPS)"
                } else {
                    "VLESS (Cloudflare CDN)"
                }
            }
            RouteKind::VlessOperaHop => {
                if is_private {
                    "VLESS via Opera Hop (Private VPS)"
                } else {
                    "VLESS via Opera Hop (Cloudflare CDN)"
                }
            }
            RouteKind::OperaDirect => "Opera VPN (Direct Exit)",
            RouteKind::Direct => "Direct TCP",
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub struct TrustPolicy {
    pub is_private_node: bool,
    pub allow_public_relay_fallback: bool,
    pub allow_opera_direct_exit: bool,
    pub allow_opera_transport_hop: bool,
}

impl Default for TrustPolicy {
    fn default() -> Self {
        Self {
            is_private_node: false,
            allow_public_relay_fallback: false,
            allow_opera_direct_exit: false,
            allow_opera_transport_hop: false,
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
    masque_cooldown_until_ms: AtomicU64,
    awg_cooldown_until_ms: AtomicU64,
    vless_cooldown_until_ms: AtomicU64,
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
                mode: crate::masque::UPLINK_WORKER,
                generation: 1,
                fallback_enabled: true,
            }),
            active_route: RwLock::new(RouteKind::None),
            trace: RwLock::new(VecDeque::with_capacity(32)),
            trust_policy: RwLock::new(TrustPolicy::default()),
            masque_cooldown_until_ms: AtomicU64::new(0),
            awg_cooldown_until_ms: AtomicU64::new(0),
            vless_cooldown_until_ms: AtomicU64::new(0),
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
        allow_opera_direct_exit: bool,
        allow_opera_transport_hop: bool,
    ) {
        let mut p = self.trust_policy.write();
        p.is_private_node = is_private_node;
        p.allow_public_relay_fallback = allow_public_relay_fallback;
        p.allow_opera_direct_exit = allow_opera_direct_exit;
        p.allow_opera_transport_hop = allow_opera_transport_hop;
        linfo!(
            "RouteSupervisor: trust policy updated -> is_private={}, allow_public_fallback={}, allow_opera_direct={}, allow_opera_hop={}",
            is_private_node, allow_public_relay_fallback, allow_opera_direct_exit, allow_opera_transport_hop
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
        self.masque_cooldown_until_ms.store(0, Ordering::Relaxed);
        self.awg_cooldown_until_ms.store(0, Ordering::Relaxed);
        self.vless_cooldown_until_ms.store(0, Ordering::Relaxed);
        self.transport_ready.store(false, Ordering::Relaxed);
        self.app_ready.store(false, Ordering::Relaxed);
        *self.last_app_error.write() = String::new();
        self.app_success_count.store(0, Ordering::Relaxed);
        self.app_failure_count.store(0, Ordering::Relaxed);
        self.transition_to(RouteKind::None, "supervisor_reset");
    }

    pub fn set_intent(&self, mode: i32, intent_gen: u64, fallback_enabled: bool) {
        let policy = *self.trust_policy.read();
        let initial_route = match mode {
            crate::masque::UPLINK_MASQUE
            | crate::masque::UPLINK_WARP_CASCADE
            | crate::masque::UPLINK_AWG => RouteKind::Awg,
            crate::masque::UPLINK_VLESS | crate::masque::UPLINK_HYBRID => {
                if policy.allow_opera_transport_hop {
                    RouteKind::VlessOperaHop
                } else {
                    RouteKind::VlessDirect
                }
            }
            _ => RouteKind::Worker,
        };

        {
            let mut intent_guard = self.intent.write();
            intent_guard.mode = mode;
            intent_guard.generation = intent_gen;
            intent_guard.fallback_enabled = fallback_enabled;
        }

        self.masque_cooldown_until_ms.store(0, Ordering::Relaxed);
        self.awg_cooldown_until_ms.store(0, Ordering::Relaxed);
        self.vless_cooldown_until_ms.store(0, Ordering::Relaxed);
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

        let (mode, fallback_enabled) = {
            let i = self.intent.read();
            (i.mode, i.fallback_enabled)
        };
        let policy = *self.trust_policy.read();
        let is_private =
            policy.is_private_node || crate::vless::VLESS_CONFIG.read().is_direct_vps();
        let active = *self.active_route.read();

        match active {
            RouteKind::Masque => {
                self.masque_cooldown_until_ms
                    .store(now_ms + 30_000, Ordering::Relaxed);
                self.transition_to(RouteKind::Awg, reason);
            }
            RouteKind::Awg => {
                self.awg_cooldown_until_ms
                    .store(now_ms + 30_000, Ordering::Relaxed);
                if mode == crate::masque::UPLINK_WARP_CASCADE && fallback_enabled {
                    self.transition_to(RouteKind::Worker, reason);
                } else {
                    self.transition_to(RouteKind::Masque, reason);
                }
            }
            RouteKind::VlessDirect | RouteKind::VlessOperaHop => {
                self.vless_cooldown_until_ms
                    .store(now_ms + 30_000, Ordering::Relaxed);
                if is_private && !policy.allow_public_relay_fallback {
                    lwarn!(
                        "RouteSupervisor: VLESS Private VPS failed. Fallback to public relay is prohibited by trust policy. Isolating route."
                    );
                    self.transition_to(RouteKind::None, "vless_private_isolated_by_trust_policy");
                } else if fallback_enabled {
                    self.transition_to(RouteKind::Worker, reason);
                }
            }
            _ => {}
        }

        self.active_stage.load(Ordering::Relaxed)
    }

    pub async fn acquire_socks5_uplink(
        &self,
        target_addr: &str,
        cancel_token: &CancellationToken,
    ) -> Option<SocksUplink> {
        let expected = crate::generation_guard::snapshot();
        let result = ACQUIRE_GENERATION
            .scope(
                expected,
                self.acquire_socks5_uplink_inner(target_addr, cancel_token),
            )
            .await;
        if !crate::generation_guard::is_current(expected) {
            if let Some(uplink) = result {
                match uplink {
                    SocksUplink::Ws(ws) | SocksUplink::VlessWs(ws) => {
                        let _ = ws.close().await;
                    }
                    other => drop(other),
                }
            }
            return None;
        }
        result
    }

    async fn acquire_socks5_uplink_inner(
        &self,
        target_addr: &str,
        cancel_token: &CancellationToken,
    ) -> Option<SocksUplink> {
        let (mode, fallback_enabled) = {
            let i = self.intent.read();
            (i.mode, i.fallback_enabled)
        };
        let policy = *self.trust_policy.read();
        let is_private =
            policy.is_private_node || crate::vless::VLESS_CONFIG.read().is_direct_vps();

        let now_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;

        match mode {
            crate::masque::UPLINK_VLESS => {
                // 1. Primary VLESS (either direct VPS, or via Opera Transport Hop to VPS, or CDN)
                let vless_cooldown = self.vless_cooldown_until_ms.load(Ordering::Relaxed);
                if now_ms >= vless_cooldown {
                    if let Some(uplink) =
                        crate::vless::vless_acquire_uplink(target_addr, cancel_token).await
                    {
                        let (use_opera_hop, _) = {
                            let op = OPERA_VPN.read();
                            (op.vless_enabled, op.endpoint.clone())
                        };
                        if use_opera_hop && policy.allow_opera_transport_hop {
                            self.transition_to(RouteKind::VlessOperaHop, "vless_opera_hop_success");
                        } else {
                            self.transition_to(RouteKind::VlessDirect, "vless_direct_success");
                        }
                        return Some(SocksUplink::from(uplink));
                    }
                }

                // 2. Opera Direct Exit (to destination) ONLY IF explicitly allowed by user trust policy on VLESS failure
                let (use_opera_direct, opera_ep) = {
                    let op = OPERA_VPN.read();
                    (
                        op.vless_enabled && policy.allow_opera_direct_exit,
                        op.endpoint.clone(),
                    )
                };
                if use_opera_direct && !opera_ep.is_empty() {
                    if let Ok((host, port)) = crate::vless::parse_target_addr(target_addr) {
                        ldebug!(
                            "SOCKS5 VLESS: connecting via Opera Direct Exit {} -> {}:{}",
                            opera_ep,
                            host,
                            port
                        );
                        if let Ok(stream) = crate::ws::connect_via_http_proxy(
                            &opera_ep,
                            &host,
                            port,
                            Duration::from_secs(5),
                        )
                        .await
                        {
                            self.transition_to(RouteKind::OperaDirect, "opera_direct_exit");
                            return Some(SocksUplink::Tcp(stream, Vec::new(), None));
                        }
                    }
                }

                // 3. Fallback:
                if is_private && !policy.allow_public_relay_fallback {
                    lwarn!(
                        "RouteSupervisor: VLESS Private VPS failed for {}. Fallback to public relay is prohibited by trust policy.",
                        target_addr
                    );
                    self.vless_cooldown_until_ms
                        .store(now_ms + 15_000, Ordering::Relaxed);
                    self.transition_to(RouteKind::None, "vless_private_isolated");
                    return None;
                }

                // Fallback to Worker WSS if allowed
                if fallback_enabled {
                    lwarn!(
                        "RouteSupervisor: VLESS failed for {}, falling back to Worker WSS",
                        target_addr
                    );
                    self.vless_cooldown_until_ms
                        .store(now_ms + 15_000, Ordering::Relaxed);
                    self.transition_to(RouteKind::Worker, "vless_failed_fallback_worker");
                    if let Some(ws) = socks5_acquire_cf_ws(target_addr, cancel_token).await {
                        return Some(SocksUplink::Ws(ws));
                    }
                }
                None
            }
            crate::masque::UPLINK_MASQUE => {
                // Cloudflare WARP Anycast (WireGuard / AmneziaWG)
                let awg_cooldown = self.awg_cooldown_until_ms.load(Ordering::Relaxed);
                if now_ms >= awg_cooldown {
                    if let Some(tunnel) =
                        crate::awg::awg_acquire_tunnel(target_addr, cancel_token).await
                    {
                        self.transition_to(RouteKind::Awg, "awg_tunnel_success");
                        return Some(SocksUplink::Awg(tunnel));
                    }
                    lwarn!(
                        "RouteSupervisor: WARP Anycast failed for {}",
                        target_addr
                    );
                    self.awg_cooldown_until_ms
                        .store(now_ms + 25_000, Ordering::Relaxed);
                }
                None
            }
            crate::masque::UPLINK_WARP_CASCADE => {
                // Primary: WARP Anycast (WireGuard / AmneziaWG)
                let awg_cooldown = self.awg_cooldown_until_ms.load(Ordering::Relaxed);
                if now_ms >= awg_cooldown {
                    if let Some(tunnel) =
                        crate::awg::awg_acquire_tunnel(target_addr, cancel_token).await
                    {
                        self.transition_to(RouteKind::Awg, "awg_tunnel_success");
                        return Some(SocksUplink::Awg(tunnel));
                    }
                    lwarn!(
                        "RouteSupervisor: WARP Anycast failed for {}, evaluating fallback",
                        target_addr
                    );
                    self.awg_cooldown_until_ms
                        .store(now_ms + 30_000, Ordering::Relaxed);
                }

                // Fallback: Cloudflare Worker WSS Relay if allowed
                if fallback_enabled {
                    lwarn!(
                        "RouteSupervisor: WARP Anycast failed, fallback to Worker WSS for {}",
                        target_addr
                    );
                    self.transition_to(RouteKind::Worker, "warp_failed_fallback_worker");
                    if let Some(ws) = socks5_acquire_cf_ws(target_addr, cancel_token).await {
                        return Some(SocksUplink::Ws(ws));
                    }
                }

                None
            }
            crate::masque::UPLINK_AWG => {
                // Primary & Only: WARP AmneziaWG. Isolated mode.
                let awg_cooldown = self.awg_cooldown_until_ms.load(Ordering::Relaxed);
                if now_ms >= awg_cooldown {
                    if let Some(tunnel) =
                        crate::awg::awg_acquire_tunnel(target_addr, cancel_token).await
                    {
                        self.transition_to(RouteKind::Awg, "awg_tunnel_success");
                        return Some(SocksUplink::Awg(tunnel));
                    }
                    lwarn!("RouteSupervisor: WARP AmneziaWG failed for {}", target_addr);
                    self.awg_cooldown_until_ms
                        .store(now_ms + 25_000, Ordering::Relaxed);
                }
                None
            }
            crate::masque::UPLINK_HYBRID => {
                // 1. Primary: VLESS
                let vless_cooldown = self.vless_cooldown_until_ms.load(Ordering::Relaxed);
                if now_ms >= vless_cooldown {
                    if let Some(uplink) =
                        crate::vless::vless_acquire_uplink(target_addr, cancel_token).await
                    {
                        let (use_opera_hop, _) = {
                            let op = OPERA_VPN.read();
                            (op.vless_enabled, op.endpoint.clone())
                        };
                        if use_opera_hop && policy.allow_opera_transport_hop {
                            self.transition_to(
                                RouteKind::VlessOperaHop,
                                "hybrid_vless_opera_hop_success",
                            );
                        } else {
                            self.transition_to(
                                RouteKind::VlessDirect,
                                "hybrid_vless_direct_success",
                            );
                        }
                        return Some(SocksUplink::from(uplink));
                    }
                    self.vless_cooldown_until_ms
                        .store(now_ms + 20_000, Ordering::Relaxed);
                }

                // Private VPS: check trust policy before falling back to WARP / Worker
                if is_private && !policy.allow_public_relay_fallback {
                    lwarn!(
                        "RouteSupervisor: Hybrid VLESS (Private VPS) failed for {}. Fallback to public relay is prohibited by trust policy.",
                        target_addr
                    );
                    self.transition_to(RouteKind::None, "hybrid_vless_private_isolated");
                    return None;
                }

                // 2. Fallback: WARP Anycast (WireGuard / AmneziaWG)
                let awg_cooldown = self.awg_cooldown_until_ms.load(Ordering::Relaxed);
                if now_ms >= awg_cooldown {
                    if let Some(tunnel) =
                        crate::awg::awg_acquire_tunnel(target_addr, cancel_token).await
                    {
                        self.transition_to(RouteKind::Awg, "hybrid_awg_success");
                        return Some(SocksUplink::Awg(tunnel));
                    }
                    self.awg_cooldown_until_ms
                        .store(now_ms + 25_000, Ordering::Relaxed);
                }

                // 3. Fallback: Cloudflare Worker WSS Relay
                self.transition_to(RouteKind::Worker, "hybrid_fallback_worker");
                socks5_acquire_cf_ws(target_addr, cancel_token)
                    .await
                    .map(SocksUplink::Ws)
            }
            _ => {
                // Worker WSS
                self.transition_to(RouteKind::Worker, "worker_selected");
                socks5_acquire_cf_ws(target_addr, cancel_token)
                    .await
                    .map(SocksUplink::Ws)
            }
        }
    }

    /// Pin the effective UDP transport once, before SOCKS success. Worker/Opera/direct
    /// have no negotiated UDP capability and must receive REP=command-not-supported.
    pub fn pin_udp_route(&self) -> Option<UdpRoutePin> {
        let generation = crate::generation_guard::snapshot();
        let intent = self.intent.read().clone();
        let route = match intent.mode {
            crate::masque::UPLINK_MASQUE => RouteKind::Masque,
            crate::masque::UPLINK_AWG => RouteKind::Awg,
            crate::masque::UPLINK_VLESS => match self.get_active_route() {
                RouteKind::VlessOperaHop => RouteKind::VlessOperaHop,
                _ => RouteKind::VlessDirect,
            },
            crate::masque::UPLINK_WARP_CASCADE => match self.get_active_route() {
                RouteKind::Masque => RouteKind::Masque,
                RouteKind::Awg => RouteKind::Awg,
                _ => return None,
            },
            crate::masque::UPLINK_HYBRID => match self.get_active_route() {
                route @ (RouteKind::Masque
                | RouteKind::Awg
                | RouteKind::VlessDirect
                | RouteKind::VlessOperaHop) => route,
                _ => return None,
            },
            _ => return None,
        };
        Some(UdpRoutePin {
            route,
            generation,
            mode: intent.mode,
            intent_generation: intent.generation,
            vless_config: crate::vless::VLESS_CONFIG.read().clone(),
            opera_endpoint: OPERA_VPN.read().endpoint.clone(),
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
        // No fallback: every target in this association uses the same transport.
        let result = tokio::select! {
            biased;
            _ = cancel_token.cancelled() => return None,
            result = tokio::time::timeout(Duration::from_secs(10), async {
                match pin.route {
                    RouteKind::VlessDirect => {
                        crate::vless::dial_single_vless_config(&pin.vless_config, target_addr,
                            crate::vless::VLESS_CMD_UDP, cancel_token).await.map(SocksUdpUplink::Vless)
                    }
                    RouteKind::VlessOperaHop => {
                        let cfg = &pin.vless_config;
                        let domain = if cfg.tls_sni.is_empty() { &cfg.domain } else { &cfg.tls_sni };
                        if pin.opera_endpoint.is_empty() || domain.is_empty() { return None; }
                        let header = crate::vless::build_vless_header_cmd(&cfg.uuid, target_addr,
                            &[], &cfg.effective_flow(), crate::vless::VLESS_CMD_UDP).ok()?;
                        let ws = crate::ws::ws_connect_via_opera_proxy(&pin.opera_endpoint, domain,
                            &cfg.path, Duration::from_secs(5)).await.ok()?;
                        ws.send(&header).await.ok()?;
                        Some(SocksUdpUplink::Vless(crate::vless::VlessUplink::Ws(ws)))
                    }
                    RouteKind::Masque | RouteKind::Awg => {
                        crate::awg::awg_acquire_tunnel(target_addr, cancel_token).await.map(SocksUdpUplink::Awg)
                    }
                    _ => None,
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
        let is_private =
            policy.is_private_node || crate::vless::VLESS_CONFIG.read().is_direct_vps();
        let transport_ready = self.transport_ready.load(Ordering::Relaxed);
        let app_ready = self.app_ready.load(Ordering::Relaxed);
        let last_app_err = self.last_app_error.read().clone();
        let app_success_count = self.app_success_count.load(Ordering::Relaxed);
        let app_failure_count = self.app_failure_count.load(Ordering::Relaxed);

        let effective_route = route.effective_display_name(is_private);
        let operator = route.operator(is_private).display_name();
        let trust_maintained = match route {
            RouteKind::None => true,
            RouteKind::VlessDirect | RouteKind::VlessOperaHop => true,
            RouteKind::Masque | RouteKind::Awg | RouteKind::Worker => {
                if is_private {
                    policy.allow_public_relay_fallback
                } else {
                    true
                }
            }
            RouteKind::OperaDirect => policy.allow_opera_direct_exit,
            RouteKind::Direct => false,
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
            crate::masque::UPLINK_VLESS => {
                if policy.allow_opera_transport_hop {
                    r#"["vless_direct","vless_opera_hop"]"#
                } else {
                    r#"["vless_direct"]"#
                }
            }
            crate::masque::UPLINK_WARP_CASCADE => r#"["warp_masque","warp_awg","cf_worker"]"#,
            crate::masque::UPLINK_MASQUE => r#"["warp_masque"]"#,
            crate::masque::UPLINK_AWG => r#"["warp_awg"]"#,
            crate::masque::UPLINK_HYBRID => {
                r#"["vless_direct","warp_masque","warp_awg","cf_worker"]"#
            }
            _ => r#"["cf_worker"]"#,
        };

        format!(
            r#"{{"generation":{},"intent_mode":{},"intent_generation":{},"stage_code":{},"stage_name":"{}","effective_route":"{}","operator":"{}","is_private":{},"trust_boundary_maintained":{},"allow_public_relay_fallback":{},"allow_opera_direct_exit":{},"allow_opera_transport_hop":{},"transport_ready":{},"app_ready":{},"supports_udp":{},"last_app_error":"{}","app_success_count":{},"app_failure_count":{},"route_id":"{}","candidates":{},"trace":[{}]}}"#,
            gen,
            intent.mode,
            intent.generation,
            stage,
            escape_json(route.display_name()),
            escape_json(effective_route),
            escape_json(operator),
            is_private,
            trust_maintained,
            policy.allow_public_relay_fallback,
            policy.allow_opera_direct_exit,
            policy.allow_opera_transport_hop,
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

        sup.set_intent(crate::masque::UPLINK_MASQUE, false);
        assert!(sup.supports_udp());

        sup.set_intent(crate::masque::UPLINK_AWG, false);
        assert!(sup.supports_udp());

        sup.set_intent(crate::masque::UPLINK_WARP_CASCADE, true);
        assert!(sup.supports_udp());

        sup.set_intent(crate::masque::UPLINK_VLESS, false);
        assert!(sup.supports_udp());

        sup.set_intent(crate::masque::UPLINK_HYBRID, true);
        assert!(sup.supports_udp());

        sup.set_intent(crate::masque::UPLINK_WORKER, false);
        assert!(!sup.supports_udp());

        sup.set_intent(crate::masque::UPLINK_DIRECT, false);
        assert!(!sup.supports_udp());
    }

    #[test]
    fn test_route_state_json_contains_supports_udp() {
        let sup = RouteSupervisor::new();
        sup.set_intent(crate::masque::UPLINK_MASQUE, false);
        let json = sup.get_route_state_json();
        assert!(json.contains("\"supports_udp\":true"));

        sup.set_intent(crate::masque::UPLINK_WORKER, false);
        let json_worker = sup.get_route_state_json();
        assert!(json_worker.contains("\"supports_udp\":false"));
    }
}
