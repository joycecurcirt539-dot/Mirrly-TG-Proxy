use mirrlyengine::balancer::BALANCER;
use mirrlyengine::cfproxy::{
    apply_ranked_domains_if_current, cache_resolved_ips_if_current, cached_resolved_ips,
};
use mirrlyengine::config::CFPROXY;
use mirrlyengine::generation_guard::{change_config, snapshot};
use mirrlyengine::network_profile::{apply_profile, get_profile};
use mirrlyengine::recovery::{record_if_current, RecoveryCause};
use mirrlyengine::supervisor::{RouteKind, RouteSupervisor};
use std::net::IpAddr;
use std::sync::Arc;
use tokio::sync::Notify;

#[tokio::test]
async fn slow_result_a_cannot_rewrite_or_reset_generation_b() {
    const DC: i32 = 203;
    const B: &str = "mob020-worker-b.example";
    const DNS_HOST: &str = "mob020-dns.example";

    let a = snapshot();
    let route = Arc::new(RouteSupervisor::new());
    let release_a = Arc::new(Notify::new());
    let waiting = release_a.clone();
    let old_route = route.clone();
    let late_a = tokio::spawn(async move {
        waiting.notified().await;
        let old_ip: IpAddr = "192.0.2.10".parse().unwrap();
        let rank_applied = apply_ranked_domains_if_current(
            a,
            DC,
            vec![("mob020-worker-a.example".to_string(), 1)],
        );
        let dns_applied = cache_resolved_ips_if_current(a, DNS_HOST, vec![old_ip]);
        let recovery_applied =
            record_if_current(a, "mob020-worker-a.example", RecoveryCause::Unknown);
        let route_applied =
            old_route.transition_to_if_current(a, RouteKind::Worker, "late-a-failover");
        (
            rank_applied,
            dns_applied,
            recovery_applied.is_some(),
            route_applied,
        )
    });

    let mut profile_b = get_profile();
    profile_b.generation += 1;
    profile_b.validated = true;
    profile_b.suspended = false;
    apply_profile(profile_b);
    change_config(|| {
        CFPROXY.write().user_domain = B.to_string();
        BALANCER.write().update_domain_for_dc(DC, false, B);
        route.set_intent(mirrlyengine::masque::UPLINK_AWG, 2, true);
    });
    let b = snapshot();
    let route_b_generation = route.get_generation();
    assert_eq!(route.get_active_route(), RouteKind::Awg);
    let b_ip: IpAddr = "192.0.2.20".parse().unwrap();
    assert!(cache_resolved_ips_if_current(b, DNS_HOST, vec![b_ip]));

    release_a.notify_one();
    let (rank_applied, dns_applied, recovery_applied, route_applied) = late_a.await.unwrap();
    assert!(!rank_applied, "A overwrote B's active race winner");
    assert!(!dns_applied, "A overwrote B's DNS cache");
    assert!(!recovery_applied, "A initiated recovery/reset in B");
    assert!(!route_applied, "A overwrote B's failover route");
    assert_eq!(route.get_active_route(), RouteKind::Awg);
    assert_eq!(route.get_generation(), route_b_generation);
    assert_eq!(
        BALANCER.read().get_active_domain_for_dc(DC, false).as_deref(),
        Some(B)
    );
    assert_eq!(cached_resolved_ips(DNS_HOST), Some(vec![b_ip]));
    assert_eq!(snapshot(), b, "stale A changed B's generation");

    let mut same_network_new_profile = get_profile();
    same_network_new_profile.screen_on = !same_network_new_profile.screen_on;
    apply_profile(same_network_new_profile);
    assert_eq!(snapshot().network, b.network);
    assert_ne!(snapshot().profile, b.profile);
    assert!(!apply_ranked_domains_if_current(
        b,
        DC,
        vec![("mob020-old-profile.example".to_string(), 1)],
    ));
    assert_eq!(
        BALANCER.read().get_active_domain_for_dc(DC, false).as_deref(),
        Some(B)
    );
}
