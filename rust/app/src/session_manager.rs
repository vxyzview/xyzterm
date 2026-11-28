//! Rust-owned terminal sessions: one PTY + one grid per session. Kotlin holds
//! only opaque handles and drives output reads, so no JNI call blocks the UI.

use std::collections::HashMap;
use std::io;
use std::sync::{Mutex, OnceLock};

use terminal_engine::grid::TerminalGrid;
use terminal_pty::Pty;

pub struct Session {
    pty: Pty,
    grid: TerminalGrid,
    state: &'static str,
}

impl Session {
    pub fn new(shell: &str, columns: usize, rows: usize) -> io::Result<Self> {
        let mut pty = Pty::open(columns as u16, rows as u16)?;
        pty.set_nonblocking(true)?;
        pty.spawn(shell, &[])?;
        Ok(Self {
            pty,
            grid: TerminalGrid::new(columns, rows),
            state: "running",
        })
    }

    pub fn write_input(&mut self, bytes: &[u8]) -> io::Result<()> {
        self.pty.write_all(bytes)
    }

    /// Drains everything currently available, feeding it into the terminal grid.
    pub fn pump(&mut self) -> io::Result<Vec<u8>> {
        let mut output = Vec::new();
        let mut buffer = [0u8; 8192];
        loop {
            match self.pty.try_read(&mut buffer)? {
                Some(0) => {
                    self.state = "exited";
                    break;
                }
                Some(count) => {
                    output.extend_from_slice(&buffer[..count]);
                    self.grid.append(&buffer[..count]);
                }
                None => break,
            }
        }
        Ok(output)
    }

    pub fn resize(&mut self, columns: usize, rows: usize) -> bool {
        if columns == 0 || rows == 0 {
            return false;
        }
        if self.pty.resize(columns as u16, rows as u16).is_err() {
            return false;
        }
        self.grid.resize(columns, rows);
        true
    }

    pub fn state(&self) -> &'static str {
        self.state
    }

    pub fn snapshot(&self) -> terminal_engine::grid::Snapshot {
        self.grid.snapshot()
    }
}

#[derive(Default)]
pub struct SessionRegistry {
    next_handle: u64,
    sessions: HashMap<u64, Session>,
}

impl SessionRegistry {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn create(&mut self, shell: &str, columns: usize, rows: usize) -> io::Result<u64> {
        let session = Session::new(shell, columns, rows)?;
        self.next_handle = self
            .next_handle
            .checked_add(1)
            .ok_or_else(|| io::Error::other("session handle space exhausted"))?;
        let handle = self.next_handle;
        self.sessions.insert(handle, session);
        Ok(handle)
    }

    pub fn get_mut(&mut self, handle: u64) -> Option<&mut Session> {
        self.sessions.get_mut(&handle)
    }

    pub fn get(&self, handle: u64) -> Option<&Session> {
        self.sessions.get(&handle)
    }

    pub fn close(&mut self, handle: u64) -> bool {
        self.sessions.remove(&handle).is_some()
    }
}

static SESSIONS: OnceLock<Mutex<SessionRegistry>> = OnceLock::new();

pub fn sessions() -> &'static Mutex<SessionRegistry> {
    SESSIONS.get_or_init(|| Mutex::new(SessionRegistry::new()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{Duration, Instant};

    fn pump_until(handle: u64, needle: &str) -> Vec<u8> {
        let mut registry = sessions().lock().expect("lock");
        let session = registry.get_mut(handle).expect("session");
        let deadline = Instant::now() + Duration::from_secs(5);
        let mut output = Vec::new();
        while Instant::now() < deadline {
            output.extend(session.pump().expect("pump"));
            if String::from_utf8_lossy(&output).contains(needle) {
                return output;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        output
    }

    #[test]
    fn shell_output_and_input_flow_through_a_session() {
        let handle = sessions()
            .lock()
            .expect("lock")
            .create("/bin/sh", 80, 24)
            .expect("create");
        {
            let mut registry = sessions().lock().expect("lock");
            registry
                .get_mut(handle)
                .expect("session")
                .write_input(b"printf hi-xyzterm\n")
                .expect("write");
        }
        let output = pump_until(handle, "hi-xyzterm");
        assert!(
            String::from_utf8_lossy(&output).contains("hi-xyzterm"),
            "output: {output:?}"
        );
        let mut registry = sessions().lock().expect("lock");
        let session = registry.get_mut(handle).expect("session");
        assert!(session.snapshot().screen.contains("hi-xyzterm"));
        assert!(session.resize(132, 43));
        assert!(session.snapshot().cursor_row < 43);
        assert!(session.snapshot().screen.contains("hi-xyzterm"));
        assert!(registry.close(handle));
    }

    #[test]
    fn missing_handle_is_reported() {
        let mut registry = SessionRegistry::new();
        assert!(registry.get_mut(999).is_none());
        assert!(!registry.close(999));
    }
}
