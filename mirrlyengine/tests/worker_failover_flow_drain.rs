use mirrlyengine::config::{CFPROXY, CFPROXY_ENABLED, LAST_SOCKS5_WORKER};
use mirrlyengine::generation_guard::snapshot;
use std::ffi::CString;
use std::sync::atomic::Ordering;

#[test]
fn worker_switch_updates_config_without_invalidating_network_profile() {
    // 1. Initial snapshot
    let before_snap = snapshot();
    let initial_profile_gen = before_snap.profile;
    let initial_config_gen = before_snap.config;

    // 2. Simulate worker failover via SetCfProxyConfig (e.g. from primary to backup)
    let new_worker = "backup-worker.workers.dev";
    let c_worker = CString::new(new_worker).expect("valid cstring");

    // Pre-populate LAST_SOCKS5_WORKER to verify it gets cleared for new flows
    *LAST_SOCKS5_WORKER.write() = "old-worker.workers.dev".to_string();

    unsafe {
        mirrlyengine::SetCfProxyConfig(1, c_worker.as_ptr());
    }

    // 3. Verify config changes
    assert!(CFPROXY_ENABLED.load(Ordering::Relaxed));
    {
        let cfg = CFPROXY.read();
        assert_eq!(cfg.user_domain, new_worker);
        assert_eq!(cfg.active, new_worker);
    }
    // LAST_SOCKS5_WORKER must be cleared so the new proven worker immediately takes effect
    assert_eq!(*LAST_SOCKS5_WORKER.read(), "");

    // 4. Verify generation invariants:
    // Config generation MUST advance, but profile generation MUST NOT advance!
    // Advancing profile generation would invalidate active network bridges.
    let after_snap = snapshot();
    assert!(
        after_snap.config > initial_config_gen,
        "config generation must advance on worker switch"
    );
    assert_eq!(
        after_snap.profile, initial_profile_gen,
        "profile generation MUST NOT advance on worker switch (existing flows must drain)"
    );

    // 5. Emergency kill MUST explicitly advance profile generation and reset sockets
    mirrlyengine::EmergencyKillAllSockets();
    let emergency_snap = snapshot();
    assert!(
        emergency_snap.profile > initial_profile_gen,
        "emergency kill must advance profile generation and reset active flows"
    );
}
