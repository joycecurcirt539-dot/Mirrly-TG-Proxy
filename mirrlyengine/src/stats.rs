use std::sync::atomic::{AtomicI32, AtomicU64, Ordering};
use std::sync::Arc;

#[derive(Default)]
pub struct EngineStats {
    pub rx_bytes: AtomicU64,
    pub tx_bytes: AtomicU64,
    pub active_conns: AtomicI32,
}

impl EngineStats {
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    #[inline]
    pub fn add_rx(&self, bytes: u64) {
        self.rx_bytes.fetch_add(bytes, Ordering::Relaxed);
    }

    #[inline]
    pub fn add_tx(&self, bytes: u64) {
        self.tx_bytes.fetch_add(bytes, Ordering::Relaxed);
    }

    #[inline]
    pub fn inc_conns(&self) {
        self.active_conns.fetch_add(1, Ordering::Relaxed);
    }

    #[inline]
    pub fn dec_conns(&self) {
        self.active_conns.fetch_sub(1, Ordering::Relaxed);
    }

    pub fn to_stats_string(&self) -> String {
        let conns = self.active_conns.load(Ordering::Relaxed).max(0);
        let rx = self.rx_bytes.load(Ordering::Relaxed);
        let tx = self.tx_bytes.load(Ordering::Relaxed);
        format!(
            "active_conns: {}, rx_bytes: {}, tx_bytes: {}",
            conns, rx, tx
        )
    }

    pub fn to_json(&self) -> String {
        let conns = self.active_conns.load(Ordering::Relaxed).max(0);
        let rx = self.rx_bytes.load(Ordering::Relaxed);
        let tx = self.tx_bytes.load(Ordering::Relaxed);
        format!(
            r#"{{"active_conns":{},"rx_bytes":{},"tx_bytes":{}}}"#,
            conns, rx, tx
        )
    }

    pub fn reset(&self) {
        self.rx_bytes.store(0, Ordering::Relaxed);
        self.tx_bytes.store(0, Ordering::Relaxed);
        self.active_conns.store(0, Ordering::Relaxed);
    }
}
