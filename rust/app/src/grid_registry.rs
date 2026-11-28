//! Thread-safe registry of Rust-owned terminal grids. Kotlin holds only an
//! opaque handle; all terminal state stays in Rust.

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

use terminal_engine::grid::{Snapshot, TerminalGrid};

#[derive(Default)]
pub struct GridRegistry {
    next_handle: i64,
    grids: HashMap<i64, TerminalGrid>,
}

impl GridRegistry {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn create(&mut self, cols: usize, rows: usize) -> Option<i64> {
        if cols == 0 || rows == 0 {
            return None;
        }
        self.next_handle = self.next_handle.checked_add(1)?;
        let handle = self.next_handle;
        self.grids.insert(handle, TerminalGrid::new(cols, rows));
        Some(handle)
    }

    pub fn append(&mut self, handle: i64, bytes: &[u8]) -> bool {
        match self.grids.get_mut(&handle) {
            Some(grid) => {
                grid.append(bytes);
                true
            }
            None => false,
        }
    }

    pub fn snapshot(&self, handle: i64) -> Option<Snapshot> {
        self.grids.get(&handle).map(TerminalGrid::snapshot)
    }

    pub fn close(&mut self, handle: i64) -> bool {
        self.grids.remove(&handle).is_some()
    }

    pub fn is_empty(&self) -> bool {
        self.grids.is_empty()
    }
}

static REGISTRY: OnceLock<Mutex<GridRegistry>> = OnceLock::new();

pub fn registry() -> &'static Mutex<GridRegistry> {
    REGISTRY.get_or_init(|| Mutex::new(GridRegistry::new()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn create_append_snapshot_close() {
        let mut registry = GridRegistry::new();
        let handle = registry.create(20, 5).expect("create");
        assert!(registry.append(handle, b"hello"));
        let snap = registry.snapshot(handle).expect("snapshot");
        assert_eq!(snap.screen, "hello\n\n\n\n");
        assert_eq!(snap.cursor_col, 5);
        assert_eq!(registry.grids.len(), 1);
        assert!(registry.close(handle));
        assert!(registry.snapshot(handle).is_none());
        assert!(!registry.append(handle, b"x"));
    }

    #[test]
    fn invalid_size_and_zero_handle() {
        let mut registry = GridRegistry::new();
        assert_eq!(registry.create(0, 5), None);
        assert_eq!(registry.create(5, 0), None);
        assert!(registry.snapshot(0).is_none());
        assert!(!registry.close(0));
    }
}
