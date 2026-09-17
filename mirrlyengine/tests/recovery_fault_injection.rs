use mirrlyengine::recovery::{RecoveryAction, RecoveryCause, RecoveryController};
use std::time::Duration;

#[test]
fn every_fault_stage_selects_only_its_scoped_recovery() {
    let controller = RecoveryController::new(Duration::from_secs(15), 3);
    let cases = [
        (
            RecoveryCause::DnsFailure,
            RecoveryAction::RotateResolverAddress,
        ),
        (RecoveryCause::TcpTimeout, RecoveryAction::TryNextFamilyOrIp),
        (
            RecoveryCause::TlsReset,
            RecoveryAction::TryNextIpDomainFingerprint,
        ),
        (
            RecoveryCause::HttpUpgradeRejected,
            RecoveryAction::OpenWorkerCircuit,
        ),
        (
            RecoveryCause::RateLimited429,
            RecoveryAction::OpenWorkerCircuit,
        ),
        (
            RecoveryCause::RelayAckFailure,
            RecoveryAction::TryNextWorker,
        ),
        (
            RecoveryCause::EstablishedStall,
            RecoveryAction::ReconnectSingleFlow,
        ),
    ];

    for (index, (cause, expected)) in cases.into_iter().enumerate() {
        let decision = controller.decide_at(&format!("fault-{index}"), cause, 1);
        assert_eq!(
            decision.action,
            Some(expected),
            "wrong action for {cause:?}"
        );
        assert!(!decision.suppressed_by_cooldown);
    }
}

#[test]
fn cooldown_allows_at_most_one_escalation_per_scope() {
    let controller = RecoveryController::new(Duration::from_millis(1_000), 3);
    let first = controller.decide_at("worker.example", RecoveryCause::TcpTimeout, 1);
    let duplicate = controller.decide_at("worker.example", RecoveryCause::TlsReset, 500);
    let after_cooldown = controller.decide_at("worker.example", RecoveryCause::TlsReset, 1_001);

    assert_eq!(first.action, Some(RecoveryAction::TryNextFamilyOrIp));
    assert_eq!(duplicate.action, None);
    assert!(duplicate.suppressed_by_cooldown);
    assert_eq!(
        after_cooldown.action,
        Some(RecoveryAction::TryNextIpDomainFingerprint)
    );
}

#[test]
fn global_reset_is_last_level_and_success_resets_escalation() {
    let controller = RecoveryController::new(Duration::from_millis(100), 3);
    let scope = "kws2.worker.example";

    for now in [1, 101, 201] {
        let decision = controller.decide_at(scope, RecoveryCause::RelayAckFailure, now);
        assert_eq!(decision.action, Some(RecoveryAction::TryNextWorker));
    }
    let exhausted = controller.decide_at(scope, RecoveryCause::RelayAckFailure, 301);
    assert_eq!(exhausted.action, Some(RecoveryAction::RequestGlobalReset));

    controller.mark_success(scope);
    let recovered = controller.decide_at(scope, RecoveryCause::RelayAckFailure, 302);
    assert_eq!(recovered.action, Some(RecoveryAction::TryNextWorker));
    assert_eq!(recovered.escalation_level, 1);
}

#[test]
fn worker_circuit_is_visible_to_base_domain_selector() {
    mirrlyengine::cfproxy::mark_cfproxy_recovery_cooldown(
        "kws2.worker.example",
        Duration::from_secs(20),
        "fault injection",
    );
    assert!(
        mirrlyengine::cfproxy::cfproxy_429_cooldown_remaining("worker.example") > Duration::ZERO
    );
    mirrlyengine::cfproxy::clear_cfproxy_recovery_cooldown("kws2.worker.example");
    assert_eq!(
        mirrlyengine::cfproxy::cfproxy_429_cooldown_remaining("worker.example"),
        Duration::ZERO
    );
}
