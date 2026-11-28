//! Rust PTY engine: master/slave allocation, shell spawning, window size,
//! signals and child reaping. Kotlin owns no part of this logic.

use std::ffi::{CStr, CString};
use std::io;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};

fn last_error(context: &str) -> io::Error {
    io::Error::new(
        io::Error::last_os_error().kind(),
        format!("{context} failed"),
    )
}

fn to_cstring(value: &str) -> io::Result<CString> {
    CString::new(value).map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "NUL in argument"))
}

/// A pseudo-terminal master with an optional child process attached.
pub struct Pty {
    master: OwnedFd,
    slave_path: CString,
    child: Option<libc::pid_t>,
}

impl Pty {
    /// Allocates a PTY pair and sets its initial window size.
    pub fn open(columns: u16, rows: u16) -> io::Result<Self> {
        if columns == 0 || rows == 0 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "PTY size must be positive",
            ));
        }
        // SAFETY: posix_openpt returns a new file descriptor or -1.
        let master = unsafe { libc::posix_openpt(libc::O_RDWR | libc::O_NOCTTY) };
        if master < 0 {
            return Err(last_error("posix_openpt"));
        }
        let master = unsafe { OwnedFd::from_raw_fd(master) };
        // SAFETY: grantpt/unlockpt operate on the descriptor we just opened.
        unsafe {
            if libc::grantpt(master.as_raw_fd()) != 0 {
                return Err(last_error("grantpt"));
            }
            if libc::unlockpt(master.as_raw_fd()) != 0 {
                return Err(last_error("unlockpt"));
            }
        }
        // SAFETY: ptsname returns a pointer to static storage valid while the
        // master is open; we copy it immediately.
        let name = unsafe { libc::ptsname(master.as_raw_fd()) };
        if name.is_null() {
            return Err(last_error("ptsname"));
        }
        let slave_path = unsafe { CStr::from_ptr(name) }.to_owned();
        let master_fd = master.as_raw_fd();
        let pty = Self {
            master,
            slave_path,
            child: None,
        };
        pty.set_winsize(master_fd, columns, rows)?;
        Ok(pty)
    }

    fn set_winsize(&self, fd: RawFd, columns: u16, rows: u16) -> io::Result<()> {
        // SAFETY: winsize is a plain C struct passed by pointer to ioctl.
        let size = libc::winsize {
            ws_row: rows,
            ws_col: columns,
            ws_xpixel: 0,
            ws_ypixel: 0,
        };
        // SAFETY: fd is an open PTY descriptor and TIOCSWINSZ takes `winsize`.
        if unsafe { libc::ioctl(fd, libc::TIOCSWINSZ, &size) } != 0 {
            return Err(last_error("ioctl(TIOCSWINSZ)"));
        }
        Ok(())
    }

    pub fn resize(&self, columns: u16, rows: u16) -> io::Result<()> {
        if columns == 0 || rows == 0 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "PTY size must be positive",
            ));
        }
        self.set_winsize(self.master.as_raw_fd(), columns, rows)
    }

    pub fn winsize(&self) -> io::Result<(u16, u16)> {
        // SAFETY: zeroed plain-old-data struct, written by ioctl.
        let mut size: libc::winsize = unsafe { std::mem::zeroed() };
        // SAFETY: fd is an open PTY descriptor and TIOCGWINSZ takes `winsize`.
        if unsafe { libc::ioctl(self.master.as_raw_fd(), libc::TIOCGWINSZ, &mut size) } != 0 {
            return Err(last_error("ioctl(TIOCGWINSZ)"));
        }
        Ok((size.ws_col, size.ws_row))
    }

    /// Spawns a program with the slave as its controlling terminal.
    pub fn spawn(&mut self, program: &str, args: &[&str]) -> io::Result<libc::pid_t> {
        if self.child.is_some() {
            return Err(io::Error::new(
                io::ErrorKind::AlreadyExists,
                "PTY already has a child",
            ));
        }
        let program = to_cstring(program)?;
        let mut argv = Vec::with_capacity(args.len() + 1);
        argv.push(program.clone());
        for arg in args {
            argv.push(to_cstring(arg)?);
        }
        argv.push(CString::default());
        let mut pointers: Vec<*const libc::c_char> = argv.iter().map(|s| s.as_ptr()).collect();
        pointers.push(std::ptr::null());

        // SAFETY: fork duplicates the process; the child only calls async-signal-safe
        // operations (setsid, open, ioctl, dup2, close, chdir, execvp, _exit)
        // and touches no Rust allocator state after the fork.
        let pid = unsafe { libc::fork() };
        if pid < 0 {
            return Err(last_error("fork"));
        }
        if pid == 0 {
            unsafe { self.child_exec(program.as_ptr(), pointers.as_ptr()) };
        }
        self.child = Some(pid);
        Ok(pid)
    }

    /// Never returns. Only async-signal-safe calls below this point.
    unsafe fn child_exec(
        &self,
        program: *const libc::c_char,
        argv: *const *const libc::c_char,
    ) -> ! {
        unsafe {
            if libc::setsid() < 0 {
                libc::_exit(127);
            }
            // No O_NOCTTY: opening the slave makes it the controlling terminal.
            let slave = libc::open(self.slave_path.as_ptr(), libc::O_RDWR);
            if slave < 0 {
                libc::_exit(127);
            }
            if libc::ioctl(slave, libc::TIOCSCTTY, 0) != 0 {
                libc::_exit(127);
            }
            for target in [libc::STDIN_FILENO, libc::STDOUT_FILENO, libc::STDERR_FILENO] {
                if libc::dup2(slave, target) < 0 {
                    libc::_exit(127);
                }
            }
            if slave > libc::STDERR_FILENO {
                libc::close(slave);
            }
            libc::execvp(program, argv);
            libc::_exit(127);
        }
    }

    /// Switches the master between blocking and non-blocking reads.
    pub fn set_nonblocking(&self, nonblocking: bool) -> io::Result<()> {
        // SAFETY: fd is open; F_GETFL/F_SETFL take no user memory.
        let flags = unsafe { libc::fcntl(self.master.as_raw_fd(), libc::F_GETFL) };
        if flags < 0 {
            return Err(last_error("fcntl(F_GETFL)"));
        }
        let flags = if nonblocking {
            flags | libc::O_NONBLOCK
        } else {
            flags & !libc::O_NONBLOCK
        };
        // SAFETY: fd is open and flags is a valid argument.
        if unsafe { libc::fcntl(self.master.as_raw_fd(), libc::F_SETFL, flags) } != 0 {
            return Err(last_error("fcntl(F_SETFL)"));
        }
        Ok(())
    }

    /// Non-blocking read: `Ok(None)` means no output is available yet.
    pub fn try_read(&mut self, buffer: &mut [u8]) -> io::Result<Option<usize>> {
        // SAFETY: buffer length is passed explicitly; master stays open.
        let count = unsafe {
            libc::read(
                self.master.as_raw_fd(),
                buffer.as_mut_ptr().cast(),
                buffer.len(),
            )
        };
        if count >= 0 {
            return Ok(Some(count as usize));
        }
        let error = io::Error::last_os_error();
        if error.kind() == io::ErrorKind::WouldBlock || error.kind() == io::ErrorKind::Interrupted {
            return Ok(None);
        }
        Err(error)
    }

    pub fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        loop {
            // SAFETY: buffer length is passed explicitly; master stays open.
            let count = unsafe {
                libc::read(
                    self.master.as_raw_fd(),
                    buffer.as_mut_ptr().cast(),
                    buffer.len(),
                )
            };
            if count >= 0 {
                return Ok(count as usize);
            }
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(error);
        }
    }

    pub fn write_all(&mut self, mut bytes: &[u8]) -> io::Result<()> {
        while !bytes.is_empty() {
            // SAFETY: pointer and length come from the same live slice.
            let count =
                unsafe { libc::write(self.master.as_raw_fd(), bytes.as_ptr().cast(), bytes.len()) };
            if count < 0 {
                let error = io::Error::last_os_error();
                if error.kind() == io::ErrorKind::Interrupted {
                    continue;
                }
                return Err(error);
            }
            bytes = &bytes[count as usize..];
        }
        Ok(())
    }

    pub fn signal(&self, signal: libc::c_int) -> io::Result<()> {
        if let Some(pid) = self.child {
            // SAFETY: kill with a known pid and signal number.
            if unsafe { libc::kill(pid, signal) } != 0 {
                return Err(last_error("kill"));
            }
        }
        Ok(())
    }

    pub fn wait(&mut self) -> io::Result<i32> {
        let Some(pid) = self.child else {
            return Err(io::Error::new(io::ErrorKind::NotFound, "no child process"));
        };
        let mut status = 0;
        loop {
            // SAFETY: status is a valid out-parameter; pid was returned by fork.
            let result = unsafe { libc::waitpid(pid, &mut status, 0) };
            if result == pid {
                self.child = None;
                if libc::WIFEXITED(status) {
                    return Ok(libc::WEXITSTATUS(status));
                }
                return Ok(128 + libc::WTERMSIG(status));
            }
            let error = io::Error::last_os_error();
            if error.kind() != io::ErrorKind::Interrupted {
                return Err(error);
            }
        }
    }
}

impl Drop for Pty {
    fn drop(&mut self) {
        if let Some(pid) = self.child {
            // SAFETY: pid came from fork; WNOHANG reaps only if already exited.
            unsafe {
                libc::kill(pid, libc::SIGKILL);
                let mut status = 0;
                libc::waitpid(pid, &mut status, libc::WNOHANG);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn read_until_eof(pty: &mut Pty) -> String {
        let mut output = Vec::new();
        let mut buffer = [0u8; 1024];
        loop {
            match pty.read(&mut buffer) {
                Ok(0) | Err(_) => break,
                Ok(count) => output.extend_from_slice(&buffer[..count]),
            }
        }
        String::from_utf8_lossy(&output).into_owned()
    }

    #[test]
    fn runs_a_shell_and_reaps_it() {
        let mut pty = Pty::open(80, 24).expect("open pty");
        pty.spawn("/bin/sh", &["-c", "printf hello-xyzterm"])
            .expect("spawn");
        let output = read_until_eof(&mut pty);
        assert!(output.contains("hello-xyzterm"), "output: {output:?}");
        assert_eq!(pty.wait().expect("wait"), 0);
    }

    #[test]
    fn input_reaches_the_shell() {
        let mut pty = Pty::open(80, 24).expect("open pty");
        pty.spawn("/bin/sh", &["-c", "read value; printf got:$value"])
            .expect("spawn");
        pty.write_all(b"abc\n").expect("write input");
        let output = read_until_eof(&mut pty);
        assert!(output.contains("got:abc"), "output: {output:?}");
        assert_eq!(pty.wait().expect("wait"), 0);
    }

    #[test]
    fn window_size_is_applied() {
        let pty = Pty::open(132, 43).expect("open pty");
        assert_eq!(pty.winsize().expect("winsize"), (132, 43));
        pty.resize(80, 24).expect("resize");
        assert_eq!(pty.winsize().expect("winsize"), (80, 24));
    }

    #[test]
    fn signals_terminate_the_child() {
        let mut pty = Pty::open(80, 24).expect("open pty");
        pty.spawn("/bin/sh", &["-c", "sleep 30"]).expect("spawn");
        pty.signal(libc::SIGTERM).expect("signal");
        let code = pty.wait().expect("wait");
        assert!(code >= 128, "exit code: {code}");
    }
}
