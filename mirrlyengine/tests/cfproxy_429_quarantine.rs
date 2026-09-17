use mirrlyengine::cfproxy::{
    canonical_cfproxy_cooldown_key, cfproxy_429_cooldown_remaining, clear_cfproxy_429_cooldown,
    clear_cfproxy_429_cooldowns, mark_cfproxy_429_cooldown,
};
use mirrlyengine::config::{Cfproxy429State, CFPROXY_429};
use mirrlyengine::ws::{WsError, WsHandshakeError};
use std::collections::HashMap;
use std::time::{Duration, Instant};

fn rate_limited(retry_after_seconds: u64) -> WsError {
    let mut headers = HashMap::new();
    headers.insert("retry-after".to_string(), retry_after_seconds.to_string());
    WsError::Handshake(WsHandshakeError {
        status_code: 429,
        status_line: "HTTP/1.1 429 Too Many Requests".to_string(),
        headers,
        location: String::new(),
    })
}

#[test]
fn quarantine_survives_generation_reset_and_uses_one_flowseal_key() {
    const BASE: &str = "mob019-worker.example";
    const FULL: &str = "kws2.mob019-worker.example";

    clear_cfproxy_429_cooldowns();

    assert_eq!(canonical_cfproxy_cooldown_key(BASE), BASE);
    assert_eq!(canonical_cfproxy_cooldown_key(FULL), BASE);
    assert_eq!(
        canonical_cfproxy_cooldown_key(" KWS5.MOB019-WORKER.EXAMPLE. "),
        BASE
    );

    let error = rate_limited(120);
    mark_cfproxy_429_cooldown(BASE, &error);
    mark_cfproxy_429_cooldown(FULL, &error);

    {
        let state = CFPROXY_429.read();
        assert_eq!(
            state.len(),
            1,
            "base and kws hostname split quarantine state"
        );
        assert_eq!(state.get(BASE).map(|entry| entry.strikes), Some(2));
    }
    let before_reset = cfproxy_429_cooldown_remaining(FULL);
    assert!(before_reset > Duration::from_secs(115));
    assert!(before_reset <= Duration::from_secs(120));

    // A shorter subsequent Retry-After must not release the same endpoint
    // before its existing server deadline.
    mark_cfproxy_429_cooldown(FULL, &rate_limited(1));
    assert!(cfproxy_429_cooldown_remaining(BASE) > Duration::from_secs(115));

    // A handover resets adaptive recovery state, but server Retry-After belongs
    // to the endpoint and must remain independent of the network generation.
    mirrlyengine::recovery::reset();
    let after_reset = cfproxy_429_cooldown_remaining(BASE);
    assert!(after_reset > Duration::from_secs(115));
    assert_eq!(
        CFPROXY_429.read().get(BASE).map(|entry| entry.strikes),
        Some(3)
    );

    // The exported last-level reset must not erase the quarantine either.
    mirrlyengine::ResetNetworkSockets();
    assert!(cfproxy_429_cooldown_remaining(FULL) > Duration::from_secs(115));

    // A successful connection through any kws{dc} form clears the shared key.
    clear_cfproxy_429_cooldown("kws5.mob019-worker.example");
    assert_eq!(cfproxy_429_cooldown_remaining(BASE), Duration::ZERO);

    // Expired state is removed lazily and its strike history does not leak into
    // the next independent rate-limit episode.
    CFPROXY_429.write().insert(
        BASE.to_string(),
        Cfproxy429State {
            until: Some(Instant::now() - Duration::from_secs(1)),
            strikes: 9,
        },
    );
    assert_eq!(cfproxy_429_cooldown_remaining(FULL), Duration::ZERO);
    assert!(!CFPROXY_429.read().contains_key(BASE));

    mark_cfproxy_429_cooldown(FULL, &error);
    assert_eq!(
        CFPROXY_429.read().get(BASE).map(|entry| entry.strikes),
        Some(1)
    );

    // Keep bulk clearing out of lifecycle/reset paths. It exists only as a
    // deterministic test helper; production expiry/success is per endpoint.
    assert!(!include_str!("../src/lib.rs").contains("clear_cfproxy_429_cooldowns();"));
    clear_cfproxy_429_cooldowns();
}
