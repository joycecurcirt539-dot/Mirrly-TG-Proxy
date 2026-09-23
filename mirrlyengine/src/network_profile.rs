use crate::config::MOBILE_NETWORK;
use once_cell::sync::Lazy;
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use std::sync::atomic::Ordering;
use std::net::IpAddr;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct TransportSli {
    pub measured_at_ms: u64,
    pub smoothed_rtt_ms: i64,
    pub jitter_ms: u64,
    pub success_rate_percent: u8,
    pub downstream_bps: u64,
    pub upstream_bps: u64,
    pub bufferbloat_ms: u64,
}

impl Default for TransportSli {
    fn default() -> Self {
        Self {
            measured_at_ms: 0,
            smoothed_rtt_ms: -1,
            jitter_ms: 0,
            success_rate_percent: 100,
            downstream_bps: 0,
            upstream_bps: 0,
            bufferbloat_ms: 0,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct NetworkProfile {
    pub schema_version: u32,
    pub generation: u64,
    pub validated: bool,
    pub suspended: bool,
    pub metered: bool,
    pub roaming: bool,
    pub congested: bool,
    pub transport: String,
    pub cellular: bool,
    pub wifi: bool,
    pub estimated_down_kbps: u32,
    pub estimated_up_kbps: u32,
    pub screen_on: bool,
    pub power_save_mode: bool,
    pub power_mode: String,
    pub transport_sli: TransportSli,
    /// Committed by the Kotlin FSM together with socket options; 0 is legacy mode.
    pub happy_eyeballs_delay_ms: u64,
    /// DUAL_STACK, IPV4_ONLY, or IPV6_FIRST. Committed by the advanced UI.
    pub ip_family_preference: String,
    /// Explicit WebSocket idle interval, in seconds. Zero is legacy adaptive mode.
    pub websocket_keep_alive_seconds: u64,
}

impl Default for NetworkProfile {
    fn default() -> Self {
        Self {
            schema_version: 1,
            generation: 1,
            validated: false,
            suspended: true,
            metered: false,
            roaming: false,
            congested: false,
            transport: "NONE".to_string(),
            cellular: false,
            wifi: false,
            estimated_down_kbps: 0,
            estimated_up_kbps: 0,
            screen_on: true,
            power_save_mode: false,
            power_mode: "ACTIVE".to_string(),
            transport_sli: TransportSli::default(),
            happy_eyeballs_delay_ms: 0,
            ip_family_preference: "DUAL_STACK".to_string(),
            websocket_keep_alive_seconds: 30,
        }
    }
}

impl NetworkProfile {
    /// Measurements are telemetry, not a new routing policy. Invalidating in-flight
    /// work for every SLI/bandwidth sample starves probes and background scans.
    fn invalidates_background_results(&self, previous: &Self) -> bool {
        self.generation != previous.generation
            || self.validated != previous.validated
            || self.suspended != previous.suspended
            || self.transport != previous.transport
            || self.metered != previous.metered
            || self.roaming != previous.roaming
            || self.power_mode != previous.power_mode
            || self.happy_eyeballs_delay_ms != previous.happy_eyeballs_delay_ms
            || self.ip_family_preference != previous.ip_family_preference
            || self.websocket_keep_alive_seconds != previous.websocket_keep_alive_seconds
    }

    fn normalized(mut self) -> Self {
        self.schema_version = 1;
        self.generation = self.generation.max(1);
        self.transport = self.transport.trim().to_ascii_uppercase();
        if self.cellular {
            self.transport = "CELLULAR".to_string();
        } else if self.wifi {
            self.transport = "WIFI".to_string();
        }
        match self.transport.as_str() {
            "CELLULAR" => {
                self.cellular = true;
                self.wifi = false;
            }
            "WIFI" => {
                self.cellular = false;
                self.wifi = true;
            }
            "NONE" | "ETHERNET" | "VPN" | "OTHER" => {
                self.cellular = false;
                self.wifi = false;
            }
            _ => {
                self.transport = "OTHER".to_string();
                self.cellular = false;
                self.wifi = false;
            }
        }
        self.transport_sli.success_rate_percent = self.transport_sli.success_rate_percent.min(100);
        self.power_mode = if self.power_save_mode {
            "POWER_SAVE".to_string()
        } else if !self.screen_on {
            "SCREEN_OFF".to_string()
        } else {
            "ACTIVE".to_string()
        };
        self.ip_family_preference = match self.ip_family_preference.trim().to_ascii_uppercase().as_str() {
            "IPV4_ONLY" => "IPV4_ONLY".to_string(),
            "IPV6_FIRST" => "IPV6_FIRST".to_string(),
            _ => "DUAL_STACK".to_string(),
        };
        self.websocket_keep_alive_seconds = self.websocket_keep_alive_seconds.clamp(15, 60);
        self
    }
}

static EFFECTIVE_NETWORK_PROFILE: Lazy<RwLock<NetworkProfile>> =
    Lazy::new(|| RwLock::new(NetworkProfile::default()));

pub fn apply_profile(profile: NetworkProfile) -> NetworkProfile {
    let effective = profile.normalized();
    crate::generation_guard::change_profile(effective.generation, || {
        let (previous_generation, changed, policy_changed) = {
            let mut guard = EFFECTIVE_NETWORK_PROFILE.write();
            let previous_generation = guard.generation;
            let changed = *guard != effective;
            let policy_changed = effective.invalidates_background_results(&guard);
            if changed {
                *guard = effective.clone();
            }
            (previous_generation, changed, policy_changed)
        };

        if changed {
            MOBILE_NETWORK.store(effective.cellular, Ordering::Relaxed);
            if effective.generation != previous_generation {
                crate::timeline::set_network_generation(effective.generation);
                crate::budget::DIAL_BUDGET.notify_generation_change(effective.generation);
                crate::balancer::BALANCER.write().notify_generation_change(effective.generation);
            }
        }
        (effective, policy_changed)
    })
    .unwrap_or_else(get_profile)
}

pub fn set_profile_json(json: &str) -> Result<NetworkProfile, String> {
    let parsed: NetworkProfile =
        serde_json::from_str(json).map_err(|error| format!("invalid network profile: {error}"))?;
    Ok(apply_profile(parsed))
}

pub fn get_profile() -> NetworkProfile {
    EFFECTIVE_NETWORK_PROFILE.read().clone()
}

pub fn current_generation() -> u64 {
    EFFECTIVE_NETWORK_PROFILE.read().generation
}

/// Filters every outbound resolver result at the engine boundary. This applies
/// equally to MTProto, SOCKS5 Worker WSS, VLESS and VPN dial paths.
pub fn filter_ip_family(ips: Vec<IpAddr>) -> Vec<IpAddr> {
    match get_profile().ip_family_preference.as_str() {
        "IPV4_ONLY" => ips.into_iter().filter(|ip| ip.is_ipv4()).collect(),
        "IPV6_FIRST" => {
            let mut v6 = Vec::new();
            let mut v4 = Vec::new();
            for ip in ips {
                if ip.is_ipv6() { v6.push(ip) } else { v4.push(ip) }
            }
            v6.extend(v4);
            v6
        }
        _ => ips,
    }
}

pub fn get_profile_json() -> String {
    serde_json::to_string(&get_profile()).unwrap_or_else(|_| "{}".to_string())
}

pub fn update_generation(generation: u64) {
    if generation == 0 {
        return;
    }
    let mut profile = get_profile();
    profile.generation = generation;
    apply_profile(profile);
}

pub fn update_legacy_transport(is_mobile: bool) {
    let mut profile = get_profile();
    profile.cellular = is_mobile;
    profile.wifi = !is_mobile;
    profile.transport = if is_mobile { "CELLULAR" } else { "WIFI" }.to_string();
    apply_profile(profile);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn profile_json_round_trip_preserves_every_dimension() {
        let profile = NetworkProfile {
            generation: 7,
            validated: true,
            suspended: false,
            metered: true,
            roaming: true,
            congested: true,
            transport: "CELLULAR".to_string(),
            cellular: true,
            estimated_down_kbps: 50_000,
            estimated_up_kbps: 12_000,
            screen_on: false,
            power_save_mode: true,
            transport_sli: TransportSli {
                measured_at_ms: 123,
                smoothed_rtt_ms: 88,
                jitter_ms: 14,
                success_rate_percent: 93,
                downstream_bps: 2_000_000,
                upstream_bps: 300_000,
                bufferbloat_ms: 31,
            },
            ..NetworkProfile::default()
        }
        .normalized();

        let json = serde_json::to_string(&profile).unwrap();
        let decoded: NetworkProfile = serde_json::from_str(&json).unwrap();
        assert_eq!(decoded, profile);
    }

    #[test]
    fn changing_power_field_preserves_transport_and_sli() {
        let original = NetworkProfile {
            generation: 9,
            validated: true,
            transport: "WIFI".to_string(),
            wifi: true,
            estimated_down_kbps: 90_000,
            transport_sli: TransportSli {
                smoothed_rtt_ms: 42,
                jitter_ms: 6,
                ..TransportSli::default()
            },
            ..NetworkProfile::default()
        }
        .normalized();
        let updated = NetworkProfile {
            screen_on: false,
            ..original.clone()
        }
        .normalized();

        assert_eq!(updated.generation, original.generation);
        assert_eq!(updated.transport, original.transport);
        assert_eq!(updated.estimated_down_kbps, original.estimated_down_kbps);
        assert_eq!(updated.transport_sli, original.transport_sli);
    }
}
