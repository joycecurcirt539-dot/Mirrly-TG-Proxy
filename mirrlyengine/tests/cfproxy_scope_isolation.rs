use mirrlyengine::cfproxy::{
    cfproxy_429_cooldown_remaining_scoped, cfproxy_429_hits, cfproxy_429_strikes_scoped,
    clear_cfproxy_429_cooldown, clear_cfproxy_429_cooldown_scoped, clear_cfproxy_429_cooldowns,
    mark_cfproxy_429_cooldown_scoped, reset_cfproxy_429_hits, scoped_cfproxy_cooldown_key,
    ProtoScope,
};
use mirrlyengine::ws::{WsError, WsHandshakeError};
use std::collections::HashMap;
use std::time::Duration;

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
fn test_namespace_isolation_between_mtproto_and_socks5() {
    const DOMAIN: &str = "my-custom-worker.workers.dev";
    clear_cfproxy_429_cooldowns();
    reset_cfproxy_429_hits(ProtoScope::Mtproto);
    reset_cfproxy_429_hits(ProtoScope::Socks5);

    assert_eq!(
        scoped_cfproxy_cooldown_key(DOMAIN, ProtoScope::Mtproto),
        "mtproto:my-custom-worker.workers.dev"
    );
    assert_eq!(
        scoped_cfproxy_cooldown_key(DOMAIN, ProtoScope::Socks5),
        "socks5:my-custom-worker.workers.dev"
    );

    // 1. Initial state: neither protocol is in cooldown
    assert_eq!(
        cfproxy_429_cooldown_remaining_scoped(DOMAIN, ProtoScope::Mtproto),
        Duration::ZERO
    );
    assert_eq!(
        cfproxy_429_cooldown_remaining_scoped(DOMAIN, ProtoScope::Socks5),
        Duration::ZERO
    );
    assert_eq!(cfproxy_429_hits(ProtoScope::Mtproto), 0);
    assert_eq!(cfproxy_429_hits(ProtoScope::Socks5), 0);

    // 2. MTProto encounters HTTP 429
    let err_mt = rate_limited(60);
    mark_cfproxy_429_cooldown_scoped(DOMAIN, ProtoScope::Mtproto, &err_mt);

    // MTProto must now be in cooldown
    assert!(cfproxy_429_cooldown_remaining_scoped(DOMAIN, ProtoScope::Mtproto) > Duration::ZERO);
    assert_eq!(cfproxy_429_strikes_scoped(DOMAIN, ProtoScope::Mtproto), 1);
    assert_eq!(cfproxy_429_hits(ProtoScope::Mtproto), 1);

    // SOCKS5 on the SAME domain must remain 100% active and unblocked!
    assert_eq!(
        cfproxy_429_cooldown_remaining_scoped(DOMAIN, ProtoScope::Socks5),
        Duration::ZERO,
        "429 in MTProto MUST leave SOCKS5 active on the same domain"
    );
    assert_eq!(cfproxy_429_strikes_scoped(DOMAIN, ProtoScope::Socks5), 0);
    assert_eq!(cfproxy_429_hits(ProtoScope::Socks5), 0);

    // 3. Second 429 strike in MTProto increments strikes only for MTProto
    mark_cfproxy_429_cooldown_scoped(DOMAIN, ProtoScope::Mtproto, &err_mt);
    assert_eq!(cfproxy_429_strikes_scoped(DOMAIN, ProtoScope::Mtproto), 2);
    assert_eq!(cfproxy_429_hits(ProtoScope::Mtproto), 2);
    assert_eq!(cfproxy_429_strikes_scoped(DOMAIN, ProtoScope::Socks5), 0);
    assert_eq!(cfproxy_429_hits(ProtoScope::Socks5), 0);

    // 4. Now SOCKS5 encounters its own 429
    let err_s5 = rate_limited(30);
    mark_cfproxy_429_cooldown_scoped(DOMAIN, ProtoScope::Socks5, &err_s5);

    // Both are now in cooldown, but with isolated strike counts & metrics
    assert!(cfproxy_429_cooldown_remaining_scoped(DOMAIN, ProtoScope::Mtproto) > Duration::ZERO);
    assert!(cfproxy_429_cooldown_remaining_scoped(DOMAIN, ProtoScope::Socks5) > Duration::ZERO);
    assert_eq!(cfproxy_429_strikes_scoped(DOMAIN, ProtoScope::Mtproto), 2);
    assert_eq!(cfproxy_429_strikes_scoped(DOMAIN, ProtoScope::Socks5), 1);
    assert_eq!(cfproxy_429_hits(ProtoScope::Mtproto), 2);
    assert_eq!(cfproxy_429_hits(ProtoScope::Socks5), 1);

    // 5. Clearing MTProto cooldown leaves SOCKS5 cooldown intact
    clear_cfproxy_429_cooldown_scoped(DOMAIN, ProtoScope::Mtproto);
    assert_eq!(
        cfproxy_429_cooldown_remaining_scoped(DOMAIN, ProtoScope::Mtproto),
        Duration::ZERO
    );
    assert!(cfproxy_429_cooldown_remaining_scoped(DOMAIN, ProtoScope::Socks5) > Duration::ZERO);

    // 6. Clearing SOCKS5 cooldown leaves it clean
    clear_cfproxy_429_cooldown_scoped(DOMAIN, ProtoScope::Socks5);
    assert_eq!(
        cfproxy_429_cooldown_remaining_scoped(DOMAIN, ProtoScope::Socks5),
        Duration::ZERO
    );

    // 7. Test backward-compatible clear_cfproxy_429_cooldown clears all scopes for the domain
    mark_cfproxy_429_cooldown_scoped(DOMAIN, ProtoScope::Mtproto, &err_mt);
    mark_cfproxy_429_cooldown_scoped(DOMAIN, ProtoScope::Socks5, &err_s5);
    assert!(cfproxy_429_cooldown_remaining_scoped(DOMAIN, ProtoScope::Mtproto) > Duration::ZERO);
    assert!(cfproxy_429_cooldown_remaining_scoped(DOMAIN, ProtoScope::Socks5) > Duration::ZERO);
    clear_cfproxy_429_cooldown(DOMAIN);
    assert_eq!(
        cfproxy_429_cooldown_remaining_scoped(DOMAIN, ProtoScope::Mtproto),
        Duration::ZERO
    );
    assert_eq!(
        cfproxy_429_cooldown_remaining_scoped(DOMAIN, ProtoScope::Socks5),
        Duration::ZERO
    );

    clear_cfproxy_429_cooldowns();
}
