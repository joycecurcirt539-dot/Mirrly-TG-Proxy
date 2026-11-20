//! Serializes generation changes with background-result application.
//! A result owns the snapshot taken before I/O; only the current snapshot may
//! mutate DNS cache, endpoint ranking, health scores or active route state.

use once_cell::sync::Lazy;
use parking_lot::RwLock;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GenerationStamp {
    pub network: u64,
    pub profile: u64,
    pub config: u64,
}

static CURRENT: Lazy<RwLock<GenerationStamp>> = Lazy::new(|| {
    RwLock::new(GenerationStamp {
        network: 1,
        profile: 1,
        config: 1,
    })
});

pub fn snapshot() -> GenerationStamp {
    *CURRENT.read()
}

pub fn current_network() -> u64 {
    snapshot().network
}

pub fn advance_network() -> u64 {
    let mut current = CURRENT.write();
    current.network = current.network.saturating_add(1);
    current.network
}

pub fn is_current(expected: GenerationStamp) -> bool {
    snapshot() == expected
}

pub fn apply_if_current<T>(expected: GenerationStamp, apply: impl FnOnce() -> T) -> Option<T> {
    let current = CURRENT.read();
    if *current != expected {
        return None;
    }
    Some(apply())
}

pub fn change_profile<T>(network: u64, change: impl FnOnce() -> (T, bool)) -> Option<T> {
    let mut current = CURRENT.write();
    if network < current.network {
        return None;
    }
    let (result, changed) = change();
    if changed {
        current.network = network;
        current.profile = current.profile.saturating_add(1);
    }
    Some(result)
}

pub fn invalidate_profile() {
    let mut current = CURRENT.write();
    current.profile = current.profile.saturating_add(1);
}

pub fn invalidate_profile_if_current<T>(
    expected: GenerationStamp,
    invalidate: impl FnOnce() -> T,
) -> Option<T> {
    let mut current = CURRENT.write();
    if *current != expected {
        return None;
    }
    current.profile = current.profile.saturating_add(1);
    Some(invalidate())
}

pub fn change_config<T>(change: impl FnOnce() -> T) -> T {
    let mut current = CURRENT.write();
    current.config = current.config.saturating_add(1);
    change()
}

pub fn change_config_if_current<T>(
    expected: GenerationStamp,
    change: impl FnOnce() -> T,
) -> Option<T> {
    let mut current = CURRENT.write();
    if *current != expected {
        return None;
    }
    current.config = current.config.saturating_add(1);
    Some(change())
}
