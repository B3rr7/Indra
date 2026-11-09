//! Per-connection child book-keeping.
//!
//! The daemon used to keep child ids in a process-global map that was **never inserted
//! into** (declared, removed and looked up, never written), and it allocated them from an
//! `AtomicU8` while the wire protocol carries a `u16`. Both are fixed here:
//!
//! * ids are allocated from the table itself, so a live id is never handed out again
//!   (an `AtomicU8` wrapped at 256 and reused ids of children that were still running);
//! * the table is owned by one connection, because a child id is only meaningful to the
//!   client that started it — and on disconnect the connection kills what it owns, which is
//!   what the old C daemon did.
//!
//! Deliberately free of tokio/Android types so `cargo test` covers it on the host.

use std::collections::HashMap;

/// Id 0 is reserved for the control channel (`CONTROL_ID`).
const FIRST_CHILD_ID: u16 = 1;

#[derive(Debug, Default)]
pub struct ChildTable {
    next: u16,
    map: HashMap<u16, u32>,
}

impl ChildTable {
    pub fn new() -> Self {
        Self {
            next: FIRST_CHILD_ID,
            map: HashMap::new(),
        }
    }

    /// Next id that is not in use, or `None` when every id is taken.
    ///
    /// The caller registers the pid before the next allocation, so two starts cannot share
    /// an id: the whole spawn path is synchronous (no `await` between allocate and register).
    pub fn allocate(&mut self) -> Option<u16> {
        for _ in 0..u16::MAX {
            let id = self.next;
            self.next = if self.next == u16::MAX {
                FIRST_CHILD_ID
            } else {
                self.next + 1
            };
            if id != 0 && !self.map.contains_key(&id) {
                return Some(id);
            }
        }
        None
    }

    pub fn register(&mut self, id: u16, pid: u32) {
        self.map.insert(id, pid);
    }

    pub fn pid(&self, id: u16) -> Option<u32> {
        self.map.get(&id).copied()
    }

    pub fn remove(&mut self, id: u16) -> Option<u32> {
        self.map.remove(&id)
    }

    /// Every pid still registered, for connection teardown.
    pub fn drain(&mut self) -> Vec<u32> {
        let pids = self.map.values().copied().collect();
        self.map.clear();
        pids
    }

    pub fn len(&self) -> usize {
        self.map.len()
    }

    pub fn is_empty(&self) -> bool {
        self.map.is_empty()
    }

    #[cfg(test)]
    pub fn set_next(&mut self, next: u16) {
        self.next = next;
    }
}

/// `CMD_SIGNAL` body: `[code: u8] [padding: u8] [child_id: u16] [signal: u32]`.
///
/// Returns `None` for a short body. The id is **16 bits**: `DaemonProtocol.cmdSignal` writes
/// it with `putLe16` at offset 2, and reading one byte truncated every id above 255.
pub fn parse_signal(body: &[u8]) -> Option<(u16, i32)> {
    if body.len() < 8 {
        return None;
    }
    let id = u16::from_le_bytes([body[2], body[3]]);
    let signal = u32::from_le_bytes([body[4], body[5], body[6], body[7]]) as i32;
    Some((id, signal))
}

/// Shell script that kills `pid`'s whole subtree, deepest first, then the pid itself.
///
/// `kill(2)` on the direct child alone leaves a tool's own children running (`sh -c 'tool &'`,
/// nmap's helper processes). The walk has to be a script because the daemon cannot enumerate
/// `/proc` for another process through a syscall, and `ps | awk` is what the C daemon used.
///
/// The signal is clamped to a real signal number: an out-of-range value would make
/// `kill -<n>` parse `-0`/`-123` as a process group instead of a signal.
pub fn kill_script(pid: u32, signal: i32) -> String {
    let sig = if (1..=64).contains(&signal) { signal } else { 9 };
    format!(
        "kill_desc() {{ for c in $(ps -A -o PID,PPID | awk -v p=$1 '$2==p{{print $1}}'); \
         do kill_desc $c; done; kill -{} $1 2>/dev/null; }}; kill_desc {}",
        sig, pid
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn allocate_starts_at_one_and_skips_zero() {
        let mut table = ChildTable::new();
        assert_eq!(table.allocate(), Some(1));
        table.register(1, 100);
        assert_eq!(table.allocate(), Some(2));
    }

    #[test]
    fn allocate_never_reuses_a_live_id() {
        let mut table = ChildTable::new();
        let first = table.allocate().unwrap();
        table.register(first, 4242);
        // Walk the counter all the way round; the live id must be stepped over, not returned.
        table.set_next(u16::MAX);
        assert_eq!(table.allocate(), Some(u16::MAX));
        table.register(u16::MAX, 4243);
        assert_eq!(table.allocate(), Some(2));
    }

    #[test]
    fn remove_frees_the_id_for_reuse() {
        let mut table = ChildTable::new();
        let id = table.allocate().unwrap();
        table.register(id, 7);
        assert_eq!(table.pid(id), Some(7));
        assert_eq!(table.remove(id), Some(7));
        assert_eq!(table.pid(id), None);
        assert!(table.is_empty());
    }

    #[test]
    fn drain_returns_every_pid_and_empties_the_table() {
        let mut table = ChildTable::new();
        table.register(1, 11);
        table.register(2, 22);
        let mut pids = table.drain();
        pids.sort();
        assert_eq!(pids, vec![11, 22]);
        assert!(table.is_empty());
    }

    #[test]
    fn parse_signal_reads_u16_id_and_le_signal() {
        // 300 is > u8::MAX: the old `body[2]` read answered 44 for it.
        let mut body = vec![6u8, 0, 0x2C, 0x01, 9, 0, 0, 0];
        assert_eq!(parse_signal(&body), Some((300, 9)));
        body[4] = 15;
        assert_eq!(parse_signal(&body), Some((300, 15)));
    }

    #[test]
    fn parse_signal_rejects_a_short_body() {
        assert_eq!(parse_signal(&[6, 0, 1, 0, 9, 0, 0]), None);
        assert_eq!(parse_signal(&[]), None);
    }

    #[test]
    fn kill_script_walks_descendants_and_uses_the_signal() {
        let script = kill_script(1234, 9);
        assert!(script.contains("kill_desc 1234"), "{script}");
        assert!(script.contains("kill -9 $1"), "{script}");
        // Out-of-range signals fall back to SIGKILL instead of becoming a process group.
        assert!(kill_script(1, 0).contains("kill -9 $1"));
        assert!(kill_script(1, 999).contains("kill -9 $1"));
    }
}
