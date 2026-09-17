use mirrlyengine::budget::DIAL_BUDGET;
use mirrlyengine::config::{
    get_transport_pool_status, MOBILE_NETWORK, MTPROTO_STANDBY_PER_ACTIVE_SLOT_REQUESTED, STATS,
};
use mirrlyengine::SetMtprotoStandbyPerActiveSlot;
use std::sync::atomic::Ordering;

#[test]
fn requested_effective_budget_and_socks_flows_share_one_contract() {
    struct Restore {
        requested: i32,
        mobile: bool,
        active: i64,
    }
    impl Drop for Restore {
        fn drop(&mut self) {
            MTPROTO_STANDBY_PER_ACTIVE_SLOT_REQUESTED.store(self.requested, Ordering::Relaxed);
            MOBILE_NETWORK.store(self.mobile, Ordering::Relaxed);
            STATS
                .connections_active
                .store(self.active, Ordering::Relaxed);
        }
    }
    let _restore = Restore {
        requested: MTPROTO_STANDBY_PER_ACTIVE_SLOT_REQUESTED.load(Ordering::Relaxed),
        mobile: MOBILE_NETWORK.load(Ordering::Relaxed),
        active: STATS.connections_active.load(Ordering::Relaxed),
    };

    MOBILE_NETWORK.store(false, Ordering::Relaxed);
    assert_eq!(SetMtprotoStandbyPerActiveSlot(99), 4);
    STATS.connections_active.store(5, Ordering::Relaxed);
    let mtproto = get_transport_pool_status("mtproto");
    assert_eq!(mtproto.mtproto_standby_per_active_slot_requested, 4);
    assert_eq!(mtproto.mtproto_standby_per_active_slot_effective, 4);
    assert_eq!(
        mtproto.global_establishment_budget,
        DIAL_BUDGET.current_limits().0
    );
    assert_eq!(mtproto.socks_concurrent_flows, 0);

    let socks = get_transport_pool_status("socks5");
    assert_eq!(socks.socks_concurrent_flows, 5);
    assert_eq!(socks.global_establishment_budget, 4);

    MOBILE_NETWORK.store(true, Ordering::Relaxed);
    assert_eq!(SetMtprotoStandbyPerActiveSlot(3), 1);
    let mobile = get_transport_pool_status("mtproto");
    assert_eq!(mobile.mtproto_standby_per_active_slot_requested, 3);
    assert_eq!(mobile.mtproto_standby_per_active_slot_effective, 1);
    assert_eq!(
        mobile.global_establishment_budget,
        DIAL_BUDGET.current_limits().0
    );
    assert_eq!(mobile.global_establishment_budget, 2);
}
