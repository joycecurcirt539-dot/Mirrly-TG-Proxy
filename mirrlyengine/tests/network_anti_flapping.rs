use mirrlyengine::network_profile::{apply_profile, current_generation, NetworkProfile};
use mirrlyengine::ws::compute_happy_eyeballs_delay;
use std::time::Duration;

#[test]
fn telemetry_and_transport_changes_do_not_retune_committed_policy_or_generation() {
    let mut profile = NetworkProfile {
        generation: current_generation() + 1,
        validated: true,
        suspended: false,
        transport: "CELLULAR".into(),
        cellular: true,
        happy_eyeballs_delay_ms: 350,
        ..NetworkProfile::default()
    };
    let generation = profile.generation;
    apply_profile(profile.clone());
    let stamp = mirrlyengine::generation_guard::snapshot();
    for second in 1..60 {
        profile.transport_sli.smoothed_rtt_ms = if second % 2 == 0 { 800 } else { 50 };
        profile.transport_sli.jitter_ms = second * 10;
        profile.transport_sli.measured_at_ms = second * 1000;
        profile.estimated_down_kbps = second as u32 * 1000;
        profile.congested = second % 2 == 0;
        apply_profile(profile.clone());
        assert_eq!(current_generation(), generation);
        assert_eq!(mirrlyengine::generation_guard::snapshot(), stamp);
        assert_eq!(compute_happy_eyeballs_delay(), Duration::from_millis(350));
    }
    profile.cellular = false;
    profile.wifi = true;
    apply_profile(profile.clone());
    assert_eq!(compute_happy_eyeballs_delay(), Duration::from_millis(350));
    profile.happy_eyeballs_delay_ms = 700;
    apply_profile(profile.clone());
    assert_eq!(compute_happy_eyeballs_delay(), Duration::from_millis(700));
    assert_eq!(current_generation(), generation);

    // Old snapshots cannot undo a committed profile after a real handover.
    profile.generation += 1;
    apply_profile(profile.clone());
    profile.generation -= 1;
    profile.happy_eyeballs_delay_ms = 200;
    apply_profile(profile);
    assert_eq!(current_generation(), generation + 1);
    assert_eq!(compute_happy_eyeballs_delay(), Duration::from_millis(700));
}
