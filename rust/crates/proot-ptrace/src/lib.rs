//! Minimal ptrace tracee lifecycle: spawn under PTRACE_TRACEME, wait for stop
//! events, continue, inspect memory and detach. Register access and syscall
//! interception build on this layer in later units.

use std::ffi::CString;
use std::io;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WaitEvent {
    Stopped(i32),
    Exited(i32),
    Signaled(i32),
}

#[derive(Debug)]
pub struct Tracee {
    pid: libc::pid_t,
}

impl Tracee {
    pub fn pid(&self) -> i32 {
        self.pid
    }

    /// Forks a child that requests tracing, stops itself for the parent to set
    /// options, then execs the requested program.
    pub fn spawn(program: &str, args: &[&str]) -> io::Result<Self> {
        let program = CString::new(program)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "NUL in program"))?;
        let mut argv = vec![program.clone()];
        for arg in args {
            argv.push(
                CString::new(*arg)
                    .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "NUL in argument"))?,
            );
        }
        argv.push(CString::default());
        let mut pointers: Vec<*const libc::c_char> = argv.iter().map(|s| s.as_ptr()).collect();
        pointers.push(std::ptr::null());

        // SAFETY: the child performs only async-signal-safe calls after fork.
        let pid = unsafe { libc::fork() };
        if pid < 0 {
            return Err(io::Error::last_os_error());
        }
        if pid == 0 {
            unsafe {
                if libc::ptrace(libc::PTRACE_TRACEME, 0, 0, 0) < 0 {
                    libc::_exit(127);
                }
                libc::raise(libc::SIGSTOP);
                libc::execvp(program.as_ptr(), pointers.as_ptr());
                libc::_exit(127);
            }
        }
        let tracee = Self { pid };
        match tracee.wait()? {
            WaitEvent::Stopped(libc::SIGSTOP) | WaitEvent::Stopped(libc::SIGTRAP) => Ok(tracee),
            _ => Err(io::Error::other("unexpected initial stop")),
        }
    }

    pub fn wait(&self) -> io::Result<WaitEvent> {
        let mut status = 0;
        let result = loop {
            // SAFETY: status is a valid out-parameter for our own child pid.
            let result = unsafe { libc::waitpid(self.pid, &mut status, 0) };
            if result < 0 && io::Error::last_os_error().kind() == io::ErrorKind::Interrupted {
                continue;
            }
            break result;
        };
        if result < 0 {
            return Err(io::Error::last_os_error());
        }
        if libc::WIFSTOPPED(status) {
            Ok(WaitEvent::Stopped(libc::WSTOPSIG(status)))
        } else if libc::WIFEXITED(status) {
            Ok(WaitEvent::Exited(libc::WEXITSTATUS(status)))
        } else if libc::WIFSIGNALED(status) {
            Ok(WaitEvent::Signaled(libc::WTERMSIG(status)))
        } else {
            Err(io::Error::other("unknown wait status"))
        }
    }

    pub fn set_options(&self, options: libc::c_long) -> io::Result<()> {
        // SAFETY: pid is our tracee; options is an opaque ptrace flag word.
        if unsafe {
            libc::ptrace(
                libc::PTRACE_SETOPTIONS,
                self.pid,
                std::ptr::null_mut::<libc::c_void>(),
                options as *mut libc::c_void,
            )
        } < 0
        {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }

    pub fn cont(&self, signal: Option<libc::c_int>) -> io::Result<()> {
        let sig = signal.unwrap_or(0) as *mut libc::c_void;
        // SAFETY: pid is our tracee; signal is either 0 or a valid signal.
        if unsafe {
            libc::ptrace(
                libc::PTRACE_CONT,
                self.pid,
                std::ptr::null_mut::<libc::c_void>(),
                sig,
            )
        } < 0
        {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }

    /// Reads the syscall number from the tracee's registers at a syscall stop.
    #[cfg(any(target_arch = "aarch64", target_arch = "x86_64"))]
    pub fn syscall_number(&self) -> io::Result<u64> {
        let mut registers: libc::user_regs_struct = unsafe { std::mem::zeroed() };
        let mut iov = libc::iovec {
            iov_base: (&mut registers as *mut libc::user_regs_struct).cast(),
            iov_len: std::mem::size_of::<libc::user_regs_struct>(),
        };
        // SAFETY: iovec points to a live register buffer; pid is our tracee.
        if unsafe {
            libc::ptrace(
                libc::PTRACE_GETREGSET,
                self.pid,
                libc::NT_PRSTATUS as *mut libc::c_void,
                (&mut iov as *mut libc::iovec).cast::<libc::c_void>(),
            )
        } < 0
        {
            return Err(io::Error::last_os_error());
        }
        #[cfg(target_arch = "aarch64")]
        {
            Ok(registers.regs[8])
        }
        #[cfg(target_arch = "x86_64")]
        {
            Ok(registers.orig_rax)
        }
    }

    /// Overwrites the syscall register at a syscall stop.
    #[cfg(any(target_arch = "aarch64", target_arch = "x86_64"))]
    pub fn set_syscall_number(&self, number: u64) -> io::Result<()> {
        let mut registers: libc::user_regs_struct = unsafe { std::mem::zeroed() };
        let mut get = libc::iovec {
            iov_base: (&mut registers as *mut libc::user_regs_struct).cast(),
            iov_len: std::mem::size_of::<libc::user_regs_struct>(),
        };
        // SAFETY: get.iov_base points to a live register buffer.
        if unsafe {
            libc::ptrace(
                libc::PTRACE_GETREGSET,
                self.pid,
                libc::NT_PRSTATUS as *mut libc::c_void,
                (&mut get as *mut libc::iovec).cast::<libc::c_void>(),
            )
        } < 0
        {
            return Err(io::Error::last_os_error());
        }
        #[cfg(target_arch = "aarch64")]
        {
            registers.regs[8] = number;
        }
        #[cfg(target_arch = "x86_64")]
        {
            registers.orig_rax = number;
        }
        let mut put = libc::iovec {
            iov_base: (&registers as *const libc::user_regs_struct)
                .cast_mut()
                .cast::<libc::c_void>(),
            iov_len: std::mem::size_of::<libc::user_regs_struct>(),
        };
        // SAFETY: put.iov_base points to a live register buffer with valid values.
        if unsafe {
            libc::ptrace(
                libc::PTRACE_SETREGSET,
                self.pid,
                libc::NT_PRSTATUS as *mut libc::c_void,
                (&mut put as *mut libc::iovec).cast::<libc::c_void>(),
            )
        } < 0
        {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }

    /// Resumes the tracee until the next syscall-entry or syscall-exit stop.
    pub fn syscall_step(&self, signal: Option<libc::c_int>) -> io::Result<()> {
        let sig = signal.unwrap_or(0) as *mut libc::c_void;
        // SAFETY: pid is our tracee; signal is 0 or a valid signal.
        if unsafe {
            libc::ptrace(
                libc::PTRACE_SYSCALL,
                self.pid,
                std::ptr::null_mut::<libc::c_void>(),
                sig,
            )
        } < 0
        {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }

    /// Steps to the next syscall stop, applies the guest/host decision and
    /// returns it. `None` means the tracee exited instead of stopping.
    #[cfg(any(target_arch = "aarch64", target_arch = "x86_64"))]
    pub fn intercept_syscall(
        &self,
        guest: proot_syscall::Abi,
        host: proot_syscall::Abi,
    ) -> io::Result<Option<proot_syscall::Decision>> {
        self.syscall_step(None)?;
        let event = self.wait()?;
        if !matches!(event, WaitEvent::Stopped(_)) {
            return Ok(None);
        }
        let number = self.syscall_number()? as u32;
        let decision = proot_syscall::decide(guest, host, number);
        if let proot_syscall::Decision::Rewrite(rewritten) = decision {
            self.set_syscall_number(u64::from(rewritten))?;
        }
        Ok(Some(decision))
    }

    /// Reads one machine word from the tracee's address space.
    pub fn peek_word(&self, address: usize) -> io::Result<u64> {
        // SAFETY: address is supplied by the caller; ptrace validates it.
        let value = unsafe {
            libc::ptrace(
                libc::PTRACE_PEEKDATA,
                self.pid,
                address as *mut libc::c_void,
                std::ptr::null_mut::<libc::c_void>(),
            )
        };
        if value == -1 {
            let error = io::Error::last_os_error();
            if error.raw_os_error().unwrap_or(0) != 0 {
                return Err(error);
            }
        }
        Ok(value as u64)
    }

    /// Writes one machine word into the tracee's address space.
    pub fn poke_word(&self, address: usize, value: u64) -> io::Result<()> {
        // SAFETY: address and value are plain machine words.
        if unsafe {
            libc::ptrace(
                libc::PTRACE_POKEDATA,
                self.pid,
                address as *mut libc::c_void,
                value as *mut libc::c_void,
            )
        } < 0
        {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }

    pub fn detach(&self) -> io::Result<()> {
        // SAFETY: pid is our tracee; detach uses no user memory.
        if unsafe {
            libc::ptrace(
                libc::PTRACE_DETACH,
                self.pid,
                std::ptr::null_mut::<libc::c_void>(),
                std::ptr::null_mut::<libc::c_void>(),
            )
        } < 0
        {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }

    pub fn kill(&self, signal: libc::c_int) -> io::Result<()> {
        // SAFETY: kill with our own pid and a signal number.
        if unsafe { libc::kill(self.pid, signal) } != 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }
}

impl Drop for Tracee {
    fn drop(&mut self) {
        let _ = self.detach();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn traces_exec_and_exit() {
        let tracee = Tracee::spawn("/bin/true", &[]).expect("spawn");
        tracee
            .set_options(libc::PTRACE_O_TRACEEXEC as libc::c_long)
            .expect("set_options");
        tracee.cont(None).expect("continue");
        let exec_stop = tracee.wait().expect("exec stop");
        assert_eq!(exec_stop, WaitEvent::Stopped(libc::SIGTRAP));
        tracee.cont(None).expect("continue");
        assert_eq!(tracee.wait().expect("exit"), WaitEvent::Exited(0));
    }

    #[test]
    fn invalid_memory_access_is_an_error() {
        let tracee = Tracee::spawn("/bin/sleep", &["30"]).expect("spawn");
        assert!(tracee.peek_word(0).is_err());
        tracee.kill(libc::SIGKILL).expect("kill");
        let _ = tracee.wait();
    }

    #[test]
    fn syscall_stepping_observes_entry_and_exit_stops() {
        let tracee = Tracee::spawn("/bin/true", &[]).expect("spawn");
        tracee
            .set_options(libc::PTRACE_O_TRACEEXEC as libc::c_long)
            .expect("set_options");
        tracee.cont(None).expect("continue to exec stop");
        assert_eq!(
            tracee.wait().expect("exec stop"),
            WaitEvent::Stopped(libc::SIGTRAP)
        );
        let mut stops = 0;
        let mut exited = false;
        for _ in 0..500 {
            tracee.syscall_step(None).expect("syscall step");
            match tracee.wait().expect("wait") {
                WaitEvent::Stopped(_) => {
                    let number = tracee.syscall_number().expect("syscall number");
                    assert!(number <= 1000, "implausible syscall number: {number}");
                    tracee
                        .set_syscall_number(number)
                        .expect("set syscall number");
                    assert_eq!(tracee.syscall_number().expect("read back"), number);
                    let abi = proot_syscall::Abi::host().expect("arm host");
                    assert!(!matches!(
                        proot_syscall::decide(abi, abi, number as u32),
                        proot_syscall::Decision::Rewrite(_)
                    ));
                    stops += 1;
                }
                WaitEvent::Exited(_) => {
                    exited = true;
                    break;
                }
                WaitEvent::Signaled(_) => break,
            }
        }
        assert!(exited, "child did not exit");
        assert!(stops >= 2, "expected syscall entry/exit stops, got {stops}");
    }

    #[test]
    #[cfg(any(target_arch = "aarch64", target_arch = "x86_64"))]
    fn live_interception_returns_decisions() {
        let abi = proot_syscall::Abi::host().expect("arm host");
        let tracee = Tracee::spawn("/bin/true", &[]).expect("spawn");
        tracee.cont(None).expect("continue");
        let _ = tracee.wait().expect("wait");
        let mut decisions = 0;
        for _ in 0..500 {
            match tracee.intercept_syscall(abi, abi).expect("intercept") {
                Some(_) => decisions += 1,
                None => break,
            }
        }
        assert!(decisions > 0, "no syscall decisions observed");
    }

    #[test]
    fn detach_releases_the_child() {
        let tracee = Tracee::spawn("/bin/true", &[]).expect("spawn");
        tracee.detach().expect("detach");
        tracee
            .cont(None)
            .expect_err("detached child cannot be continued");
    }
}
