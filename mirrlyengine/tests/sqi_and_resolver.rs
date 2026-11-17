use mirrlyengine::balancer::{normalize_domain, Balancer};
use mirrlyengine::dns::{
    clear_dns_cache, extract_ipv4_from_nat64, interleave_ips, is_nat64_address,
    parse_host_and_port, resolve_target_endpoint, synthesize_nat64,
};
use mirrlyengine::config::STATS;
use mirrlyengine::prober::probe_transport_health;
use mirrlyengine::proxy::{DcSlot, WsPool};
use mirrlyengine::GetUsefulRxSliJson;
use std::ffi::CStr;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use std::sync::atomic::Ordering;
use std::sync::Arc;
use tokio_util::sync::CancellationToken;

#[tokio::test]
async fn test_minimal_system_resolver_and_nat64() {
    clear_dns_cache();
    assert_eq!(parse_host_and_port("1.2.3.4:80").unwrap(), ("1.2.3.4".to_string(), 80));
    assert_eq!(
        parse_host_and_port("[2001:db8::1]:443").unwrap(),
        ("2001:db8::1".to_string(), 443)
    );
    let (ip, port) = resolve_target_endpoint("149.154.167.50:443").await.unwrap();
    assert_eq!(ip, IpAddr::V4(Ipv4Addr::new(149, 154, 167, 50)));
    assert_eq!(port, 443);

    let v4 = Ipv4Addr::new(149, 154, 167, 51);
    let v6 = synthesize_nat64(v4);
    assert!(is_nat64_address(&v6));
    assert_eq!(extract_ipv4_from_nat64(&v6), Some(v4));

    let v6_1 = IpAddr::V6(Ipv6Addr::new(0x2606, 0x4700, 0, 0, 0, 0, 0, 1));
    let v4_1 = IpAddr::V4(Ipv4Addr::new(104, 21, 1, 1));
    let ordered = interleave_ips(vec![v6_1], vec![v4_1]);
    assert_eq!(ordered.first().copied(), Some(v4_1));
}

#[tokio::test]
async fn test_sqi_balancer_summary_and_ws_pool_status() {
    let mut balancer = Balancer::new();
    balancer.update_domains_list(&["pclead.co.uk".to_string(), "offshor.co.uk".to_string()]);
    balancer.update_ranked_domains_for_dc(
        2,
        false,
        vec![
            ("pclead.co.uk".to_string(), 84),
            ("offshor.co.uk".to_string(), 142),
        ],
    );
    let summary = balancer.get_health_summary();
    assert_eq!(summary.active_domain.as_deref(), Some("pclead.co.uk"));
    assert_eq!(summary.healthy_domain_count, 2);
    assert_eq!(summary.min_probe_rtt_ms, Some(84));
    assert_eq!(normalize_domain("kws2.pclead.co.uk"), "pclead.co.uk");

    let cancel = CancellationToken::new();
    let pool = Arc::new(WsPool::new(cancel));
    let wp = pool.warm_pool_status();
    assert_eq!(wp.ready_total, 0);
    assert!(wp.target_total >= 1);
    assert!(wp.dc2_chat_target >= 1);
    let _slot = DcSlot { dc: 2, is_media: 0 };
}

#[test]
fn test_get_useful_rx_sli_json_populates_real_metrics() {
    STATS.connections_active.store(3, Ordering::Relaxed);
    STATS.pool_hits.store(5, Ordering::Relaxed);
    STATS.pool_misses.store(1, Ordering::Relaxed);

    let ptr = GetUsefulRxSliJson();
    assert!(!ptr.is_null());
    let json_str = unsafe { CStr::from_ptr(ptr).to_string_lossy().into_owned() };
    unsafe { mirrlyengine::FreeString(ptr) };

    let v: serde_json::Value = serde_json::from_str(&json_str).expect("valid JSON");
    assert_ne!(json_str, "{}");
    assert_eq!(v["active_conns"].as_i64(), Some(3));
    assert_eq!(v["pool_hits"].as_i64(), Some(5));
    assert_eq!(v["pool_misses"].as_i64(), Some(1));
    assert!(v.get("balancer_healthy_domains").is_some());
    assert!(v.get("warm_ready").is_some());
    assert!(v.get("warm_target").is_some());
    STATS.reset();
}

#[tokio::test]
async fn test_probe_transport_health_empty_worker_classifies_cleanly() {
    let res = probe_transport_health(2, "", 500).await;
    assert!(!res.alive);
    assert_eq!(res.failure, "HOST_RESOLUTION_FAILED");
}
