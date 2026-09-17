use mirrlyengine::balancer::{Balancer, BALANCER};

#[test]
fn test_dc2_speed_does_not_leak_to_dc4_or_dc1() {
    let mut b = Balancer::new();
    b.update_domains_list(&[
        "worker1.dev".to_string(),
        "worker2.dev".to_string(),
        "worker3.dev".to_string(),
    ]);

    // Probe only DC2 chat
    b.update_ranked_domains_for_dc(
        2,
        false,
        vec![
            ("worker1.dev".to_string(), 25),
            ("worker2.dev".to_string(), 110),
        ],
    );

    // DC2 chat winner is worker1.dev
    assert_eq!(
        b.get_fastest_domain_for_dc(2, false).as_deref(),
        Some("worker1.dev")
    );

    // MOB-026: DC4 chat and DC1 chat are unprobed and MUST NOT inherit worker1.dev from DC2!
    assert_eq!(b.get_fastest_domain_for_dc(4, false), None);
    assert_eq!(b.get_fastest_domain_for_dc(1, false), None);
    assert_eq!(b.get_fastest_domain_for_dc(5, false), None);
}

#[test]
fn test_media_class_isolation() {
    let mut b = Balancer::new();
    b.update_domains_list(&[
        "worker1.dev".to_string(),
        "worker2.dev".to_string(),
        "worker3.dev".to_string(),
    ]);

    // DC2 chat uses worker1
    b.update_ranked_domains_for_dc(
        2,
        false,
        vec![
            ("worker1.dev".to_string(), 35),
            ("worker2.dev".to_string(), 90),
        ],
    );

    // DC2 media uses worker2
    b.update_ranked_domains_for_dc(
        2,
        true,
        vec![
            ("worker2.dev".to_string(), 20),
            ("worker1.dev".to_string(), 140),
        ],
    );

    // DC4 media uses worker3
    b.update_ranked_domains_for_dc(
        4,
        true,
        vec![
            ("worker3.dev".to_string(), 18),
            ("worker2.dev".to_string(), 75),
        ],
    );

    assert_eq!(
        b.get_fastest_domain_for_dc(2, false).as_deref(),
        Some("worker1.dev")
    );
    assert_eq!(
        b.get_fastest_domain_for_dc(2, true).as_deref(),
        Some("worker2.dev")
    );
    assert_eq!(
        b.get_fastest_domain_for_dc(4, true).as_deref(),
        Some("worker3.dev")
    );
    assert_eq!(b.get_fastest_domain_for_dc(4, false), None);
}

#[test]
fn test_useful_success_vs_probe_rtt() {
    let mut b = Balancer::new();
    let gen = 5;
    b.set_network_generation(gen);

    // workerA has 70ms probe RTT
    b.record_probe_rtt(gen, 2, false, "workerA.dev", 70);

    // workerB has 85ms probe RTT
    b.record_probe_rtt(gen, 2, false, "workerB.dev", 85);

    // Initially workerA is active
    assert_eq!(
        b.get_fastest_domain_for_dc(2, false).as_deref(),
        Some("workerA.dev")
    );

    // workerB earns multiple useful successes: 4 * 15ms = 60ms max bonus
    // Score becomes 85 - 60 = 25ms
    for _ in 0..4 {
        b.record_useful_success(gen, 2, false, "workerB.dev");
    }

    // workerA has a failure: score jumps from 70 to 70 + 100 + 20 = 190
    b.record_failure(gen, 2, false, "workerA.dev");

    // workerB with useful success and low score becomes the winner
    assert_eq!(
        b.get_fastest_domain_for_dc(2, false).as_deref(),
        Some("workerB.dev")
    );
}

#[test]
fn test_operator_change_does_not_inherit_bad_winner() {
    let mut b = Balancer::new();
    b.set_network_generation(10);

    b.update_domain_for_dc(2, false, "worker_failing.dev");
    assert_eq!(
        b.get_active_domain_for_dc(2, false).as_deref(),
        Some("worker_failing.dev")
    );

    // Operator switch (network generation increments 10 -> 11)
    b.set_network_generation(11);

    // Bad winner is NOT inherited on new operator/generation
    assert_eq!(b.get_active_domain_for_dc(2, false), None);
    assert_eq!(b.get_fastest_domain_for_dc(2, false), None);
}
