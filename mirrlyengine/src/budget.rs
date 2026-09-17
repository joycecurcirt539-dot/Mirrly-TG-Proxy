// mirrlyengine/src/budget.rs
//
// Mirrly TG Proxy - Unified Dial & Probe Budget Manager + DNS Singleflight
// Copyright (C) 2026 R1Xern (Mirrly Dev)
//
// Solves MOB-003:
// 1. One centralized DialBudgetManager keyed to network generation.
// 2. Separate quotas and priorities for UserFlow, Recovery, and Background.
// 3. UserFlow always has priority over Background (probes/prewarm never starve user connections).
// 4. Cellular limits: max 2 active establishments, max 1 background.
// 5. Wi-Fi limits: max 4 active establishments, max 2 background (zero Wi-Fi regression).
// 6. Generic Singleflight engine for per-host DNS deduplication to eliminate DoH storms.

use crate::config::MOBILE_NETWORK;
use crate::{ldebug, linfo};
use once_cell::sync::Lazy;
use serde::Serialize;
use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use tokio::sync::{watch, Mutex, Notify};
use tokio_util::sync::CancellationToken;

/// Category of dial / connection attempt.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize)]
pub enum FlowCategory {
    /// Foreground user client traffic (incoming SOCKS5 CONNECT, incoming MTProto client connection).
    /// Always has highest priority.
    UserFlow,
    /// Connection recovery / reconnect after socket drop or network handover.
    Recovery,
    /// Background balancer race, prewarm, health probe.
    Background,
}

impl std::fmt::Display for FlowCategory {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            FlowCategory::UserFlow => write!(f, "UserFlow"),
            FlowCategory::Recovery => write!(f, "Recovery"),
            FlowCategory::Background => write!(f, "Background"),
        }
    }
}

/// Errors returned by the DialBudgetManager.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BudgetError {
    Cancelled,
    GenerationMismatch,
    Closed,
}

impl std::fmt::Display for BudgetError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            BudgetError::Cancelled => write!(f, "dial budget acquisition cancelled"),
            BudgetError::GenerationMismatch => write!(f, "network generation mismatch during dial wait"),
            BudgetError::Closed => write!(f, "dial budget manager is closed"),
        }
    }
}

impl std::error::Error for BudgetError {}

/// Diagnostics snapshot of the budget manager state.
#[derive(Debug, Clone, Serialize)]
pub struct BudgetStats {
    pub generation: u64,
    pub is_mobile: bool,
    pub max_establishment: usize,
    pub max_background: usize,
    pub active_user: usize,
    pub active_recovery: usize,
    pub active_background: usize,
    pub active_total: usize,
    pub waiting_user: usize,
    pub waiting_recovery: usize,
    pub waiting_background: usize,
}

struct BudgetState {
    active_user: usize,
    active_recovery: usize,
    active_background: usize,
    waiting_user: usize,
    waiting_recovery: usize,
    waiting_background: usize,
}

impl BudgetState {
    fn new() -> Self {
        Self {
            active_user: 0,
            active_recovery: 0,
            active_background: 0,
            waiting_user: 0,
            waiting_recovery: 0,
            waiting_background: 0,
        }
    }

    fn active_total(&self) -> usize {
        self.active_user + self.active_recovery + self.active_background
    }
}

pub struct DialBudgetInner {
    state: Mutex<BudgetState>,
    notify: Notify,
    current_gen: AtomicU64,
}

/// RAII Permit representing an acquired slot in the global dial budget.
/// When dropped, releases the slot and notifies pending waiters.
pub struct DialPermit {
    pub category: FlowCategory,
    pub generation: u64,
    inner: Arc<DialBudgetInner>,
}

impl Drop for DialPermit {
    fn drop(&mut self) {
        let inner = self.inner.clone();
        let cat = self.category;
        // A permit can be dropped while an FFI-triggered shutdown is unwinding
        // ownership. Always use Mirrly's runtime instead of assuming the
        // current thread has entered a Tokio reactor.
        crate::runtime().spawn(async move {
            let mut state = inner.state.lock().await;
            match cat {
                FlowCategory::UserFlow => {
                    state.active_user = state.active_user.saturating_sub(1);
                }
                FlowCategory::Recovery => {
                    state.active_recovery = state.active_recovery.saturating_sub(1);
                }
                FlowCategory::Background => {
                    state.active_background = state.active_background.saturating_sub(1);
                }
            }
            inner.notify.notify_waiters();
        });
    }
}

/// Unified Dial and Probe Budget Manager.
#[derive(Clone)]
pub struct DialBudgetManager {
    inner: Arc<DialBudgetInner>,
}

impl DialBudgetManager {
    pub fn new() -> Self {
        Self {
            inner: Arc::new(DialBudgetInner {
                state: Mutex::new(BudgetState::new()),
                notify: Notify::new(),
                current_gen: AtomicU64::new(1),
            }),
        }
    }

    /// Returns current limits based on the network profile.
    pub fn current_limits(&self) -> (usize, usize) {
        if MOBILE_NETWORK.load(Ordering::Relaxed) {
            // Cellular profile: max 2 active establishments, max 1 background probe
            (2, 1)
        } else {
            // Wi-Fi profile: max 4 active establishments, max 2 background probes
            (4, 2)
        }
    }

    /// Notifies the budget manager of a network generation change (e.g. Wi-Fi <-> Cellular handover).
    pub fn notify_generation_change(&self, new_gen: u64) {
        if new_gen == 0 {
            return;
        }

        // SetNetworkGeneration is an FFI entry point and is normally invoked
        // from a Kotlin dispatcher, not from a Tokio worker. Keep this path
        // reactor-independent: the atomic is the single generation source and
        // Notify::notify_waiters is safe to call synchronously from any thread.
        self.inner.current_gen.store(new_gen, Ordering::SeqCst);
        self.inner.notify.notify_waiters();
        linfo!("DialBudget: generation changed to {}", new_gen);
    }

    /// Waits until [expected_gen] is no longer the active network generation.
    /// This lets an in-flight establishment race abort without touching bridges
    /// that were already fully established on the previous network.
    pub async fn wait_for_generation_change(&self, expected_gen: u64) {
        loop {
            // Register the waiter before checking the atomic so a concurrent
            // notify cannot be lost between the check and await.
            let notified = self.inner.notify.notified();
            if self.inner.current_gen.load(Ordering::SeqCst) != expected_gen {
                return;
            }
            notified.await;
        }
    }

    /// Checks whether an establishment or probe slot can be admitted immediately without waiting.
    pub async fn has_free_budget_for(&self, category: FlowCategory) -> bool {
        let (max_est, max_bg) = self.current_limits();
        let state = self.inner.state.lock().await;
        match category {
            FlowCategory::UserFlow => state.active_total() < max_est,
            FlowCategory::Recovery => {
                state.waiting_user == 0 && state.active_total() < max_est
            }
            FlowCategory::Background => {
                state.waiting_user == 0
                    && state.waiting_recovery == 0
                    && state.active_background < max_bg
                    && state.active_total() < max_est
            }
        }
    }

    /// Acquires an establishment or probe slot according to priority quotas and network limits.
    ///
    /// Rules:
    /// 1. UserFlow has highest priority. If `waiting_user > 0`, Background is NEVER admitted.
    /// 2. Recovery is admitted after UserFlow.
    /// 3. Background is only admitted when no UserFlow/Recovery are waiting and background quota is free.
    /// 4. Total concurrent establishment never exceeds `max_establishment` (2 on mobile, 4 on Wi-Fi).
    pub async fn acquire(
        &self,
        category: FlowCategory,
        cancel_token: Option<&CancellationToken>,
    ) -> Result<DialPermit, BudgetError> {
        let initial_gen = self.inner.current_gen.load(Ordering::Relaxed);

        loop {
            // Check cancellation first
            if let Some(token) = cancel_token {
                if token.is_cancelled() {
                    return Err(BudgetError::Cancelled);
                }
            }

            let (max_est, max_bg) = self.current_limits();

            // Try fast acquire under lock
            {
                let mut state = self.inner.state.lock().await;

                if initial_gen != 0
                    && self.inner.current_gen.load(Ordering::SeqCst) != initial_gen
                {
                    return Err(BudgetError::GenerationMismatch);
                }

                let can_admit = match category {
                    FlowCategory::UserFlow => state.active_total() < max_est,
                    FlowCategory::Recovery => {
                        state.waiting_user == 0 && state.active_total() < max_est
                    }
                    FlowCategory::Background => {
                        state.waiting_user == 0
                            && state.waiting_recovery == 0
                            && state.active_background < max_bg
                            && state.active_total() < max_est
                    }
                };

                if can_admit {
                    match category {
                        FlowCategory::UserFlow => state.active_user += 1,
                        FlowCategory::Recovery => state.active_recovery += 1,
                        FlowCategory::Background => state.active_background += 1,
                    }
                    return Ok(DialPermit {
                        category,
                        generation: initial_gen,
                        inner: self.inner.clone(),
                    });
                }

                // Increment waiting counter
                match category {
                    FlowCategory::UserFlow => state.waiting_user += 1,
                    FlowCategory::Recovery => state.waiting_recovery += 1,
                    FlowCategory::Background => state.waiting_background += 1,
                }
            }

            // Await notification, cancellation, or generation change
            let notified = self.inner.notify.notified();

            if let Some(token) = cancel_token {
                tokio::select! {
                    _ = token.cancelled() => {
                        let mut state = self.inner.state.lock().await;
                        match category {
                            FlowCategory::UserFlow => state.waiting_user = state.waiting_user.saturating_sub(1),
                            FlowCategory::Recovery => state.waiting_recovery = state.waiting_recovery.saturating_sub(1),
                            FlowCategory::Background => state.waiting_background = state.waiting_background.saturating_sub(1),
                        }
                        self.inner.notify.notify_waiters();
                        return Err(BudgetError::Cancelled);
                    }
                    _ = notified => {
                        // Woken up to recheck
                    }
                }
            } else {
                notified.await;
            }

            // Decrement waiting counter before retrying loop
            {
                let mut state = self.inner.state.lock().await;
                match category {
                    FlowCategory::UserFlow => state.waiting_user = state.waiting_user.saturating_sub(1),
                    FlowCategory::Recovery => state.waiting_recovery = state.waiting_recovery.saturating_sub(1),
                    FlowCategory::Background => state.waiting_background = state.waiting_background.saturating_sub(1),
                }

                // Check generation change
                let current_generation = self.inner.current_gen.load(Ordering::SeqCst);
                if current_generation != initial_gen && initial_gen != 0 {
                    ldebug!(
                        "DialBudget: generation advanced ({} -> {}) while waiting for {}",
                        initial_gen,
                        current_generation,
                        category
                    );
                    self.inner.notify.notify_waiters();
                    return Err(BudgetError::GenerationMismatch);
                }
            }
        }
    }

    /// Attempts non-blocking acquisition.
    pub async fn try_acquire(&self, category: FlowCategory) -> Option<DialPermit> {
        let (max_est, max_bg) = self.current_limits();
        let mut state = self.inner.state.lock().await;

        let can_admit = match category {
            FlowCategory::UserFlow => state.active_total() < max_est,
            FlowCategory::Recovery => state.waiting_user == 0 && state.active_total() < max_est,
            FlowCategory::Background => {
                state.waiting_user == 0
                    && state.waiting_recovery == 0
                    && state.active_background < max_bg
                    && state.active_total() < max_est
            }
        };

        if can_admit {
            match category {
                FlowCategory::UserFlow => state.active_user += 1,
                FlowCategory::Recovery => state.active_recovery += 1,
                FlowCategory::Background => state.active_background += 1,
            }
            Some(DialPermit {
                category,
                generation: self.inner.current_gen.load(Ordering::SeqCst),
                inner: self.inner.clone(),
            })
        } else {
            None
        }
    }

    /// Captures a diagnostic snapshot of current budget state.
    pub async fn stats(&self) -> BudgetStats {
        let (max_est, max_bg) = self.current_limits();
        let state = self.inner.state.lock().await;
        BudgetStats {
            generation: self.inner.current_gen.load(Ordering::SeqCst),
            is_mobile: MOBILE_NETWORK.load(Ordering::Relaxed),
            max_establishment: max_est,
            max_background: max_bg,
            active_user: state.active_user,
            active_recovery: state.active_recovery,
            active_background: state.active_background,
            active_total: state.active_total(),
            waiting_user: state.waiting_user,
            waiting_recovery: state.waiting_recovery,
            waiting_background: state.waiting_background,
        }
    }

    /// Resets all budget state (for tests or engine reset).
    pub async fn reset(&self) {
        let mut state = self.inner.state.lock().await;
        state.active_user = 0;
        state.active_recovery = 0;
        state.active_background = 0;
        state.waiting_user = 0;
        state.waiting_recovery = 0;
        state.waiting_background = 0;
        self.inner.notify.notify_waiters();
    }
}

pub static DIAL_BUDGET: Lazy<DialBudgetManager> = Lazy::new(DialBudgetManager::new);

// ---------------------------------------------------------------------------
// Singleflight Engine for Per-Host DNS & Tasks
// ---------------------------------------------------------------------------

struct SingleflightGuard<K: Eq + std::hash::Hash + Clone + Send + 'static, V: Clone + Send + 'static> {
    in_flight: Arc<parking_lot::Mutex<HashMap<K, watch::Receiver<Option<V>>>>>,
    key: K,
}

impl<K: Eq + std::hash::Hash + Clone + Send + 'static, V: Clone + Send + 'static> Drop
    for SingleflightGuard<K, V>
{
    fn drop(&mut self) {
        self.in_flight.lock().remove(&self.key);
    }
}

/// Generic Singleflight group ensuring only one execution for identical key occurs concurrently.
/// Concurrent callers join the in-flight operation and receive the same outcome.
#[derive(Clone)]
pub struct Singleflight<K: Eq + std::hash::Hash + Clone + Send + 'static, V: Clone + Send + 'static> {
    in_flight: Arc<parking_lot::Mutex<HashMap<K, watch::Receiver<Option<V>>>>>,
}

impl<K: Eq + std::hash::Hash + Clone + Send + 'static, V: Clone + Send + 'static> Singleflight<K, V> {
    pub fn new() -> Self {
        Self {
            in_flight: Arc::new(parking_lot::Mutex::new(HashMap::new())),
        }
    }

    pub async fn execute<F, Fut>(&self, key: K, f: F) -> V
    where
        F: FnOnce() -> Fut,
        Fut: std::future::Future<Output = V>,
    {
        // 1. Check if another task is already executing for this key
        let mut maybe_rx = {
            let guard = self.in_flight.lock();
            guard.get(&key).cloned()
        };

        // If in-flight, await leader result
        while let Some(mut rx) = maybe_rx.take() {
            loop {
                if let Some(val) = rx.borrow().as_ref() {
                    return val.clone();
                }
                if rx.changed().await.is_err() {
                    // Leader dropped without value (e.g. cancelled/panicked)
                    break;
                }
            }
            // Recheck if a new leader registered
            let guard = self.in_flight.lock();
            if let Some(existing) = guard.get(&key) {
                maybe_rx = Some(existing.clone());
            } else {
                break;
            }
        }

        // 2. Register ourselves as leader
        let (tx, rx) = watch::channel(None);
        {
            let mut guard = self.in_flight.lock();
            if let Some(existing) = guard.get(&key) {
                maybe_rx = Some(existing.clone());
            } else {
                guard.insert(key.clone(), rx);
            }
        }

        // Rare race: another task slipped in as leader
        if let Some(mut rx) = maybe_rx {
            loop {
                if let Some(val) = rx.borrow().as_ref() {
                    return val.clone();
                }
                if rx.changed().await.is_err() {
                    break;
                }
            }
        }

        // We are leader: wrap in RAII guard so map is cleaned up even on cancel/panic
        let _guard = SingleflightGuard {
            in_flight: self.in_flight.clone(),
            key: key.clone(),
        };

        let result = f().await;
        let _ = tx.send(Some(result.clone()));
        result
    }
}

/// Global Singleflight group for Hostname DNS deduplication.
pub static DNS_SINGLEFLIGHT: Lazy<Singleflight<String, Vec<IpAddr>>> =
    Lazy::new(Singleflight::new);

/// Global Singleflight group for Dual-Scope DNS resolution.
pub static DNS_SCOPE_SINGLEFLIGHT: Lazy<Singleflight<String, Result<Vec<IpAddr>, crate::dns::DnsError>>> =
    Lazy::new(Singleflight::new);

// ---------------------------------------------------------------------------
// DNS Concurrency Budget Manager (MOB-021)
// ---------------------------------------------------------------------------

pub struct DnsPermit {
    inner: Arc<DnsBudgetInner>,
}

impl Drop for DnsPermit {
    fn drop(&mut self) {
        self.inner.release();
    }
}

struct DnsBudgetInner {
    active: parking_lot::Mutex<usize>,
    notify: Notify,
}

impl DnsBudgetInner {
    fn release(&self) {
        {
            let mut active = self.active.lock();
            *active = active.saturating_sub(1);
        }
        self.notify.notify_waiters();
    }
}

/// Global DNS Concurrency Budget Manager (MOB-021).
/// Limits the maximum number of concurrent in-flight DNS resolutions across unique domains.
/// Prevents radio congestion and bufferbloat on mobile networks (cellular: 4, Wi-Fi: 8).
#[derive(Clone)]
pub struct DnsBudgetManager {
    inner: Arc<DnsBudgetInner>,
}

impl DnsBudgetManager {
    pub fn new() -> Self {
        Self {
            inner: Arc::new(DnsBudgetInner {
                active: parking_lot::Mutex::new(0),
                notify: Notify::new(),
            }),
        }
    }

    pub fn max_concurrent(&self) -> usize {
        if MOBILE_NETWORK.load(Ordering::Relaxed) {
            4 // Cellular limit: at most 4 concurrent DNS resolutions
        } else {
            8 // Wi-Fi limit: at most 8 concurrent DNS resolutions
        }
    }

    pub async fn acquire(&self, cancel_token: Option<&CancellationToken>) -> Result<DnsPermit, BudgetError> {
        loop {
            if let Some(token) = cancel_token {
                if token.is_cancelled() {
                    return Err(BudgetError::Cancelled);
                }
            }

            let max = self.max_concurrent();
            {
                let mut active = self.inner.active.lock();
                if *active < max {
                    *active += 1;
                    return Ok(DnsPermit {
                        inner: self.inner.clone(),
                    });
                }
            }

            let notified = self.inner.notify.notified();
            if let Some(token) = cancel_token {
                tokio::select! {
                    _ = token.cancelled() => return Err(BudgetError::Cancelled),
                    _ = notified => {}
                }
            } else {
                notified.await;
            }
        }
    }

    pub fn active_count(&self) -> usize {
        *self.inner.active.lock()
    }

    pub fn reset(&self) {
        *self.inner.active.lock() = 0;
        self.inner.notify.notify_waiters();
    }
}

pub static DNS_BUDGET: Lazy<DnsBudgetManager> = Lazy::new(DnsBudgetManager::new);

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicUsize;

    #[test]
    fn test_generation_change_without_entered_tokio_runtime() {
        let mgr = DialBudgetManager::new();

        // Mirrors SetNetworkGeneration invoked by JNA on an Android/Kotlin
        // dispatcher thread. This must never require a Tokio reactor.
        mgr.notify_generation_change(42);

        assert_eq!(mgr.inner.current_gen.load(Ordering::SeqCst), 42);
    }

    #[tokio::test]
    async fn test_waiting_dial_is_rejected_when_network_generation_changes() {
        MOBILE_NETWORK.store(true, Ordering::Relaxed);
        let mgr = DialBudgetManager::new();
        let _p1 = mgr.acquire(FlowCategory::UserFlow, None).await.unwrap();
        let _p2 = mgr.acquire(FlowCategory::UserFlow, None).await.unwrap();

        let waiting_mgr = mgr.clone();
        let waiting = tokio::spawn(async move {
            waiting_mgr.acquire(FlowCategory::UserFlow, None).await
        });

        tokio::time::sleep(Duration::from_millis(20)).await;
        assert_eq!(mgr.stats().await.waiting_user, 1);

        mgr.notify_generation_change(2);
        let result = tokio::time::timeout(Duration::from_millis(250), waiting)
            .await
            .expect("stale dial must wake immediately")
            .expect("dial task must not panic");

        assert_eq!(result.err(), Some(BudgetError::GenerationMismatch));
        assert_eq!(mgr.stats().await.waiting_user, 0);
        MOBILE_NETWORK.store(false, Ordering::Relaxed);
    }

    #[tokio::test]
    async fn test_dial_budget_mobile_limits() {
        MOBILE_NETWORK.store(true, Ordering::Relaxed);
        let mgr = DialBudgetManager::new();

        // Mobile allows 2 total active establishments
        let p1 = mgr.acquire(FlowCategory::UserFlow, None).await.unwrap();
        let p2 = mgr.acquire(FlowCategory::UserFlow, None).await.unwrap();

        let stats = mgr.stats().await;
        assert_eq!(stats.active_user, 2);
        assert_eq!(stats.active_total, 2);

        // Third should not acquire immediately
        assert!(mgr.try_acquire(FlowCategory::UserFlow).await.is_none());

        // Background should not acquire immediately
        assert!(mgr.try_acquire(FlowCategory::Background).await.is_none());

        drop(p1);
        tokio::time::sleep(Duration::from_millis(20)).await;

        // Now one slot is available
        let p3 = mgr.try_acquire(FlowCategory::UserFlow).await;
        assert!(p3.is_some());

        MOBILE_NETWORK.store(false, Ordering::Relaxed);
    }

    #[tokio::test]
    async fn test_dial_budget_user_priority_over_background() {
        MOBILE_NETWORK.store(true, Ordering::Relaxed);
        let mgr = DialBudgetManager::new();

        // Consume all 2 slots
        let p1 = mgr.acquire(FlowCategory::UserFlow, None).await.unwrap();
        let p2 = mgr.acquire(FlowCategory::UserFlow, None).await.unwrap();

        let mgr_bg = mgr.clone();
        let mgr_user = mgr.clone();

        let bg_order = Arc::new(AtomicUsize::new(0));
        let user_order = Arc::new(AtomicUsize::new(0));
        let counter = Arc::new(AtomicUsize::new(1));

        let bg_order_c = bg_order.clone();
        let counter_bg = counter.clone();
        let bg_task = tokio::spawn(async move {
            let _p = mgr_bg.acquire(FlowCategory::Background, None).await.unwrap();
            bg_order_c.store(counter_bg.fetch_add(1, Ordering::SeqCst), Ordering::SeqCst);
        });

        // Let background queue up
        tokio::time::sleep(Duration::from_millis(30)).await;

        let user_order_c = user_order.clone();
        let counter_user = counter.clone();
        let user_task = tokio::spawn(async move {
            let _p = mgr_user.acquire(FlowCategory::UserFlow, None).await.unwrap();
            user_order_c.store(counter_user.fetch_add(1, Ordering::SeqCst), Ordering::SeqCst);
        });

        // Let user queue up
        tokio::time::sleep(Duration::from_millis(30)).await;

        // Release one slot
        drop(p1);

        // UserFlow must be admitted before Background despite arriving later!
        tokio::time::sleep(Duration::from_millis(50)).await;
        assert_eq!(user_order.load(Ordering::SeqCst), 1);
        assert_eq!(bg_order.load(Ordering::SeqCst), 0);

        // Release second slot, allowing Background
        drop(p2);
        let _ = bg_task.await;
        let _ = user_task.await;
        assert_eq!(bg_order.load(Ordering::SeqCst), 2);

        MOBILE_NETWORK.store(false, Ordering::Relaxed);
    }

    #[tokio::test]
    async fn test_dial_budget_cancellation() {
        MOBILE_NETWORK.store(true, Ordering::Relaxed);
        let mgr = DialBudgetManager::new();

        let _p1 = mgr.acquire(FlowCategory::UserFlow, None).await.unwrap();
        let _p2 = mgr.acquire(FlowCategory::UserFlow, None).await.unwrap();

        let cancel = CancellationToken::new();
        let cancel_c = cancel.clone();
        let mgr_c = mgr.clone();

        let handle = tokio::spawn(async move {
            mgr_c.acquire(FlowCategory::UserFlow, Some(&cancel_c)).await
        });

        tokio::time::sleep(Duration::from_millis(30)).await;
        assert_eq!(mgr.stats().await.waiting_user, 1);

        cancel.cancel();
        let res = handle.await.unwrap();
        assert_eq!(res.err(), Some(BudgetError::Cancelled));

        tokio::time::sleep(Duration::from_millis(20)).await;
        assert_eq!(mgr.stats().await.waiting_user, 0);

        MOBILE_NETWORK.store(false, Ordering::Relaxed);
    }

    #[tokio::test]
    async fn test_singleflight_deduplication() {
        let sf: Arc<Singleflight<String, u32>> = Arc::new(Singleflight::new());
        let execution_count = Arc::new(AtomicUsize::new(0));

        let mut handles = Vec::new();
        for _ in 0..10 {
            let sf_c = sf.clone();
            let count_c = execution_count.clone();
            handles.push(tokio::spawn(async move {
                sf_c.execute("same_host.workers.dev".to_string(), || async move {
                    count_c.fetch_add(1, Ordering::SeqCst);
                    tokio::time::sleep(Duration::from_millis(100)).await;
                    42
                })
                .await
            }));
        }

        for h in handles {
            let val = h.await.unwrap();
            assert_eq!(val, 42);
        }

        // All 10 callers executed the closure EXACTLY ONCE!
        assert_eq!(execution_count.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn test_dns_budget_limits_and_drop() {
        MOBILE_NETWORK.store(true, Ordering::Relaxed);
        let mgr = DnsBudgetManager::new();
        assert_eq!(mgr.max_concurrent(), 4);

        let p1 = mgr.acquire(None).await.unwrap();
        let p2 = mgr.acquire(None).await.unwrap();
        let p3 = mgr.acquire(None).await.unwrap();
        let p4 = mgr.acquire(None).await.unwrap();
        assert_eq!(mgr.active_count(), 4);

        // 5th acquire should wait until a slot is freed
        let mgr_c = mgr.clone();
        let cancel = CancellationToken::new();
        let cancel_c = cancel.clone();
        let handle = tokio::spawn(async move {
            mgr_c.acquire(Some(&cancel_c)).await
        });

        tokio::time::sleep(Duration::from_millis(30)).await;
        assert_eq!(mgr.active_count(), 4);

        // Drop p1: should unblock 5th acquire
        drop(p1);
        let p5 = handle.await.unwrap().unwrap();
        assert_eq!(mgr.active_count(), 4);

        drop(p2);
        drop(p3);
        drop(p4);
        drop(p5);
        assert_eq!(mgr.active_count(), 0);

        MOBILE_NETWORK.store(false, Ordering::Relaxed);
        assert_eq!(mgr.max_concurrent(), 8);
    }
}
