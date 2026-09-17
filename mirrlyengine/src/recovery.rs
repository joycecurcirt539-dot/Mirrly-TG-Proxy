use once_cell::sync::Lazy;
use parking_lot::Mutex;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

pub const DEFAULT_RECOVERY_COOLDOWN: Duration = Duration::from_secs(15);
pub const LOCAL_ESCALATIONS_BEFORE_GLOBAL: u8 = 3;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RecoveryCause {
    DnsFailure,
    FallbackIpFailed,
    TcpTimeout,
    TlsReset,
    HttpUpgradeRejected,
    RateLimited429,
    RelayAckFailure,
    EstablishedStall,
    Ipv4LiteralUnreachableIpv6Only,
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RecoveryAction {
    RotateResolverAddress,
    TryNextFamilyOrIp,
    TryNextIpDomainFingerprint,
    OpenWorkerCircuit,
    TryNextWorker,
    ReconnectSingleFlow,
    RequestGlobalReset,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RecoveryDecision {
    pub cause: RecoveryCause,
    pub action: Option<RecoveryAction>,
    pub scope: String,
    pub escalation_level: u8,
    pub suppressed_by_cooldown: bool,
}

#[derive(Debug, Clone, Copy)]
struct RecoveryState {
    last_escalation_ms: u64,
    local_escalations: u8,
}

pub struct RecoveryController {
    cooldown_ms: u64,
    local_escalations_before_global: u8,
    states: Mutex<HashMap<String, RecoveryState>>,
}

impl RecoveryController {
    pub fn new(cooldown: Duration, local_escalations_before_global: u8) -> Self {
        Self {
            cooldown_ms: cooldown.as_millis().max(1) as u64,
            local_escalations_before_global: local_escalations_before_global.max(1),
            states: Mutex::new(HashMap::new()),
        }
    }

    pub fn decide_at(&self, scope: &str, cause: RecoveryCause, now_ms: u64) -> RecoveryDecision {
        let scope = sanitize_scope(scope);
        let mut states = self.states.lock();
        let state = states.entry(scope.clone()).or_insert(RecoveryState {
            last_escalation_ms: 0,
            local_escalations: 0,
        });

        if state.last_escalation_ms != 0
            && now_ms.saturating_sub(state.last_escalation_ms) < self.cooldown_ms
        {
            return RecoveryDecision {
                cause,
                action: None,
                scope,
                escalation_level: state.local_escalations,
                suppressed_by_cooldown: true,
            };
        }

        state.last_escalation_ms = now_ms.max(1);
        let action = if cause == RecoveryCause::Unknown
            || state.local_escalations >= self.local_escalations_before_global
        {
            RecoveryAction::RequestGlobalReset
        } else {
            state.local_escalations = state.local_escalations.saturating_add(1);
            action_for_cause(cause)
        };

        RecoveryDecision {
            cause,
            action: Some(action),
            scope,
            escalation_level: state.local_escalations,
            suppressed_by_cooldown: false,
        }
    }

    pub fn mark_success(&self, scope: &str) {
        self.states.lock().remove(&sanitize_scope(scope));
    }

    pub fn reset(&self) {
        self.states.lock().clear();
    }
}

pub fn action_for_cause(cause: RecoveryCause) -> RecoveryAction {
    match cause {
        RecoveryCause::DnsFailure => RecoveryAction::RotateResolverAddress,
        RecoveryCause::FallbackIpFailed => RecoveryAction::TryNextWorker,
        RecoveryCause::TcpTimeout => RecoveryAction::TryNextFamilyOrIp,
        RecoveryCause::TlsReset => RecoveryAction::TryNextIpDomainFingerprint,
        RecoveryCause::HttpUpgradeRejected | RecoveryCause::RateLimited429 => {
            RecoveryAction::OpenWorkerCircuit
        }
        RecoveryCause::RelayAckFailure => RecoveryAction::TryNextWorker,
        RecoveryCause::EstablishedStall => RecoveryAction::ReconnectSingleFlow,
        RecoveryCause::Ipv4LiteralUnreachableIpv6Only => RecoveryAction::TryNextFamilyOrIp,
        RecoveryCause::Unknown => RecoveryAction::RequestGlobalReset,
    }
}

fn sanitize_scope(scope: &str) -> String {
    let trimmed = scope.trim();
    let without_query = trimmed.split(['?', '#']).next().unwrap_or("");
    let without_credentials = without_query.rsplit('@').next().unwrap_or("");
    if without_credentials.is_empty() {
        "connection".to_string()
    } else {
        without_credentials.to_ascii_lowercase()
    }
}

static START: Lazy<Instant> = Lazy::new(Instant::now);
static DNS_RECOVERY_EPOCH: AtomicU64 = AtomicU64::new(0);
static TCP_RECOVERY_EPOCH: AtomicU64 = AtomicU64::new(0);
static TLS_RECOVERY_EPOCH: AtomicU64 = AtomicU64::new(0);

pub static RECOVERY_CONTROLLER: Lazy<RecoveryController> = Lazy::new(|| {
    RecoveryController::new(DEFAULT_RECOVERY_COOLDOWN, LOCAL_ESCALATIONS_BEFORE_GLOBAL)
});

pub fn record(scope: &str, cause: RecoveryCause) -> RecoveryDecision {
    record_if_current(crate::generation_guard::snapshot(), scope, cause).unwrap_or_else(|| {
        RecoveryDecision {
            cause,
            action: None,
            scope: sanitize_scope(scope),
            escalation_level: 0,
            suppressed_by_cooldown: true,
        }
    })
}

pub fn record_if_current(
    expected: crate::generation_guard::GenerationStamp,
    scope: &str,
    cause: RecoveryCause,
) -> Option<RecoveryDecision> {
    let decision = crate::generation_guard::apply_if_current(expected, || {
        let now_ms = START.elapsed().as_millis() as u64 + 1;
        let decision = RECOVERY_CONTROLLER.decide_at(scope, cause, now_ms);
        if let Some(action) = decision.action {
            if action != RecoveryAction::RequestGlobalReset {
                apply_local_action(&decision, action, expected.network);
            }
        }
        decision
    })?;
    if decision.action == Some(RecoveryAction::RequestGlobalReset) {
        crate::reset_network_sockets_for_reason_if_current(
            expected,
            "reason-aware recovery exhausted",
        );
    }
    if let Some(action) = decision.action {
        crate::linfo!(
            "Recovery {:?} -> {:?} scope={} level={}",
            cause,
            action,
            decision.scope,
            decision.escalation_level
        );
    } else {
        crate::ldebug!(
            "Recovery {:?} suppressed by cooldown scope={}",
            cause,
            decision.scope
        );
    }
    Some(decision)
}

fn apply_local_action(decision: &RecoveryDecision, action: RecoveryAction, network_gen: u64) {
    match action {
        RecoveryAction::RotateResolverAddress => {
            crate::cfproxy::invalidate_doh_host(&decision.scope);
            DNS_RECOVERY_EPOCH.fetch_add(1, Ordering::Relaxed);
            TCP_RECOVERY_EPOCH.fetch_add(1, Ordering::Relaxed);
        }
        RecoveryAction::TryNextFamilyOrIp => {
            TCP_RECOVERY_EPOCH.fetch_add(1, Ordering::Relaxed);
        }
        RecoveryAction::TryNextIpDomainFingerprint => {
            TCP_RECOVERY_EPOCH.fetch_add(1, Ordering::Relaxed);
            TLS_RECOVERY_EPOCH.fetch_add(1, Ordering::Relaxed);
        }
        RecoveryAction::OpenWorkerCircuit => {
            crate::cfproxy::mark_cfproxy_recovery_circuit_at_stage(
                &decision.scope,
                Duration::from_secs(30),
                "HTTP upgrade rejected",
                EstablishmentStage::Wss,
                network_gen,
            );
        }
        RecoveryAction::TryNextWorker => {
            crate::cfproxy::mark_cfproxy_recovery_circuit_at_stage(
                &decision.scope,
                Duration::from_secs(20),
                "relay ACK failed",
                EstablishmentStage::Ready,
                network_gen,
            );
        }
        RecoveryAction::ReconnectSingleFlow => {}
        RecoveryAction::RequestGlobalReset => {}
    }
}

pub fn mark_success(scope: &str) {
    RECOVERY_CONTROLLER.mark_success(scope);
    crate::cfproxy::clear_cfproxy_recovery_cooldown(scope);
}

pub fn mark_success_if_current(
    expected: crate::generation_guard::GenerationStamp,
    scope: &str,
) -> bool {
    crate::generation_guard::apply_if_current(expected, || mark_success(scope)).is_some()
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EstablishmentStage {
    Dns,
    Tcp,
    Tls,
    Wss,
    Ready,
}

impl EstablishmentStage {
    pub fn is_ready(&self) -> bool {
        matches!(self, EstablishmentStage::Ready)
    }

    pub fn level(&self) -> u8 {
        match self {
            EstablishmentStage::Dns => 1,
            EstablishmentStage::Tcp => 2,
            EstablishmentStage::Tls => 3,
            EstablishmentStage::Wss => 4,
            EstablishmentStage::Ready => 5,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AddressFamily {
    Ipv4,
    Ipv6,
}

impl From<std::net::IpAddr> for AddressFamily {
    fn from(ip: std::net::IpAddr) -> Self {
        match ip {
            std::net::IpAddr::V4(_) => AddressFamily::Ipv4,
            std::net::IpAddr::V6(_) => AddressFamily::Ipv6,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct RouteScoreKey {
    pub network_generation: u64,
    pub hostname: String,
    pub ip: std::net::IpAddr,
    pub family: AddressFamily,
    pub stage: EstablishmentStage,
}

#[derive(Debug, Default)]
struct PathFamilyMetrics {
    ipv4_failures: u32,
    ipv6_failures: u32,
    ipv4_rtt_ms: Option<u64>,
    ipv6_rtt_ms: Option<u64>,
    ready_ips: HashMap<std::net::IpAddr, EstablishmentStage>,
}

static PATH_FAMILY_TRACKER: Lazy<Mutex<HashMap<(u64, String), PathFamilyMetrics>>> =
    Lazy::new(|| Mutex::new(HashMap::new()));

pub fn record_stage_success(
    network_gen: u64,
    hostname: &str,
    ip: std::net::IpAddr,
    stage: EstablishmentStage,
    rtt_ms: u64,
) {
    let clean_host = sanitize_scope(hostname);
    let mut tracker = PATH_FAMILY_TRACKER.lock();
    let entry = tracker.entry((network_gen, clean_host)).or_default();

    let cur_stage = entry.ready_ips.entry(ip).or_insert(stage);
    if stage.level() > cur_stage.level() {
        *cur_stage = stage;
    }

    // Critical: TCP success alone does NOT promote to Ready!
    if stage == EstablishmentStage::Ready {
        match ip {
            std::net::IpAddr::V4(_) => {
                entry.ipv4_failures = 0;
                entry.ipv4_rtt_ms = Some(rtt_ms);
            }
            std::net::IpAddr::V6(_) => {
                entry.ipv6_failures = 0;
                entry.ipv6_rtt_ms = Some(rtt_ms);
            }
        }
    }
}

pub fn record_stage_failure(
    network_gen: u64,
    hostname: &str,
    ip: std::net::IpAddr,
    _stage: EstablishmentStage,
) {
    let clean_host = sanitize_scope(hostname);
    let mut tracker = PATH_FAMILY_TRACKER.lock();
    let entry = tracker.entry((network_gen, clean_host)).or_default();
    match ip {
        std::net::IpAddr::V4(_) => {
            entry.ipv4_failures = entry.ipv4_failures.saturating_add(1);
        }
        std::net::IpAddr::V6(_) => {
            entry.ipv6_failures = entry.ipv6_failures.saturating_add(1);
        }
    }
    entry.ready_ips.remove(&ip);
}

pub fn is_route_ready(network_gen: u64, hostname: &str, ip: std::net::IpAddr) -> bool {
    let clean_host = sanitize_scope(hostname);
    let tracker = PATH_FAMILY_TRACKER.lock();
    tracker
        .get(&(network_gen, clean_host))
        .and_then(|m| m.ready_ips.get(&ip))
        .map(|s| s.is_ready())
        .unwrap_or(false)
}

pub fn get_path_family_failures(network_gen: u64, hostname: &str, family: AddressFamily) -> u32 {
    let clean_host = sanitize_scope(hostname);
    let tracker = PATH_FAMILY_TRACKER.lock();
    tracker
        .get(&(network_gen, clean_host))
        .map(|m| match family {
            AddressFamily::Ipv4 => m.ipv4_failures,
            AddressFamily::Ipv6 => m.ipv6_failures,
        })
        .unwrap_or(0)
}

pub fn reset() {
    RECOVERY_CONTROLLER.reset();
    DNS_RECOVERY_EPOCH.store(0, Ordering::Relaxed);
    TCP_RECOVERY_EPOCH.store(0, Ordering::Relaxed);
    TLS_RECOVERY_EPOCH.store(0, Ordering::Relaxed);
    PATH_FAMILY_TRACKER.lock().clear();
}

pub static IPV6_ONLY_NETWORK: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

pub fn is_ipv6_only_network() -> bool {
    IPV6_ONLY_NETWORK.load(Ordering::Relaxed)
}

pub fn set_ipv6_only_network(is_ipv6_only: bool) {
    IPV6_ONLY_NETWORK.store(is_ipv6_only, Ordering::Relaxed);
}

pub fn order_socket_addrs_for_host(
    network_gen: u64,
    hostname: &str,
    addrs: &[SocketAddr],
) -> Vec<SocketAddr> {
    let mut ordered = addrs.to_vec();
    if ordered.len() <= 1 {
        return ordered;
    }
    let clean_host = sanitize_scope(hostname);
    let tracker = PATH_FAMILY_TRACKER.lock();
    let metrics = tracker.get(&(network_gen, clean_host));

    let ipv6_failures = metrics.map(|m| m.ipv6_failures).unwrap_or(0);
    let ipv4_failures = metrics.map(|m| m.ipv4_failures).unwrap_or(0);
    let ipv6_only = is_ipv6_only_network();

    if ipv6_only || (ipv4_failures > 0 && ipv6_failures == 0) {
        // IPv6-only network or IPv4 broken -> place IPv6 first
        ordered.sort_by_key(|addr| addr.is_ipv4());
    } else if ipv6_failures > 0 && ipv4_failures == 0 {
        // Rapid IPv6 demotion: broken IPv6 is placed after IPv4
        ordered.sort_by_key(|addr| addr.is_ipv6());
    } else {
        // RFC 8305: By default in dual-stack networks, IPv6 is preferred.
        // Interleave / rotate with recovery epoch without permanently locking IPv4 first.
        let epoch = TCP_RECOVERY_EPOCH.load(Ordering::Relaxed) as usize;
        if epoch % 2 == 1 {
            ordered.sort_by_key(|addr| addr.is_ipv6());
        } else {
            ordered.sort_by_key(|addr| addr.is_ipv4());
        }
        let rotation = epoch % ordered.len();
        ordered.rotate_left(rotation);
    }
    ordered
}

pub fn order_socket_addrs(addrs: &[SocketAddr]) -> Vec<SocketAddr> {
    order_socket_addrs_for_host(crate::generation_guard::snapshot().network, "", addrs)
}

pub fn order_resolver_endpoints(mut endpoints: Vec<String>) -> Vec<String> {
    if endpoints.len() > 1 {
        let rotation = DNS_RECOVERY_EPOCH.load(Ordering::Relaxed) as usize % endpoints.len();
        endpoints.rotate_left(rotation);
    }
    endpoints
}

pub fn effective_tls_fingerprint() -> &'static str {
    match TLS_RECOVERY_EPOCH.load(Ordering::Relaxed) % 3 {
        1 => "firefox",
        2 => "safari",
        _ => "chrome",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

    #[test]
    fn test_tcp_success_does_not_promote_to_ready() {
        let gen = 1;
        let host = "worker1.pages.dev";
        let ip = IpAddr::V4(Ipv4Addr::new(104, 21, 1, 1));

        record_stage_success(gen, host, ip, EstablishmentStage::Tcp, 20);
        assert!(!is_route_ready(gen, host, ip));

        record_stage_success(gen, host, ip, EstablishmentStage::Ready, 50);
        assert!(is_route_ready(gen, host, ip));
    }

    #[test]
    fn test_broken_ipv6_rapid_demotion_only_for_specific_path() {
        let gen = 1;
        let host_a = "worker1.pages.dev";
        let host_b = "worker2.pages.dev";
        let ipv6 = SocketAddr::new(IpAddr::V6(Ipv6Addr::new(0x2606, 0x4700, 0, 0, 0, 0, 0, 1)), 443);
        let ipv4 = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(104, 21, 1, 1)), 443);

        let candidates = vec![ipv6, ipv4];

        // Fail IPv6 on host A
        record_stage_failure(gen, host_a, ipv6.ip(), EstablishmentStage::Tls);
        assert_eq!(get_path_family_failures(gen, host_a, AddressFamily::Ipv6), 1);
        assert_eq!(get_path_family_failures(gen, host_a, AddressFamily::Ipv4), 0);

        // Host A orders IPv4 first due to rapid demotion
        let ordered_a = order_socket_addrs_for_host(gen, host_a, &candidates);
        assert_eq!(ordered_a[0], ipv4);
        assert_eq!(ordered_a[1], ipv6);

        // Host B is NOT affected
        assert_eq!(get_path_family_failures(gen, host_b, AddressFamily::Ipv6), 0);
    }

    #[test]
    fn test_new_network_generation_does_not_inherit_permanent_ban() {
        let gen1 = 1;
        let gen2 = 2;
        let host = "worker1.pages.dev";
        let ipv6 = SocketAddr::new(IpAddr::V6(Ipv6Addr::new(0x2606, 0x4700, 0, 0, 0, 0, 0, 1)), 443);
        let ipv4 = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(104, 21, 1, 1)), 443);
        let candidates = vec![ipv6, ipv4];

        // Gen 1 has failure on IPv6
        record_stage_failure(gen1, host, ipv6.ip(), EstablishmentStage::Tcp);
        assert_eq!(get_path_family_failures(gen1, host, AddressFamily::Ipv6), 1);
        let ordered_gen1 = order_socket_addrs_for_host(gen1, host, &candidates);
        assert_eq!(ordered_gen1[0], ipv4);

        // Handover to Gen 2: clean slate, no failure inherited
        assert_eq!(get_path_family_failures(gen2, host, AddressFamily::Ipv6), 0);
    }

    #[test]
    fn test_ipv6_only_prioritizes_ipv6_first() {
        let gen = 10;
        let host = "dualstack.example.com";
        let ipv6 = SocketAddr::new(IpAddr::V6(Ipv6Addr::new(0x2606, 0x4700, 0, 0, 0, 0, 0, 1)), 443);
        let ipv4 = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(104, 21, 1, 1)), 443);
        let candidates = vec![ipv4, ipv6];

        set_ipv6_only_network(true);
        assert!(is_ipv6_only_network());

        let ordered = order_socket_addrs_for_host(gen, host, &candidates);
        assert_eq!(ordered[0], ipv6);
        assert_eq!(ordered[1], ipv4);

        set_ipv6_only_network(false);
    }

    #[test]
    fn test_ipv4_literal_unreachable_on_ipv6_only_action() {
        let action = action_for_cause(RecoveryCause::Ipv4LiteralUnreachableIpv6Only);
        assert_eq!(action, RecoveryAction::TryNextFamilyOrIp);
    }
}
