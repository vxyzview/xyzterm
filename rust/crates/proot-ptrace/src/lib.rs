//! Minimal ptrace tracee lifecycle: spawn under PTRACE_TRACEME, wait for stop
//! events, continue, inspect memory and detach. Register access and syscall
//! interception build on this layer in later units.

use std::cell::Cell;
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
    /// With `PTRACE_O_TRACESYSGOOD`, syscall stops alternate entry/exit.
    next_syscall_is_entry: Cell<bool>,
    last_syscall_was_entry: Cell<bool>,
}

impl Tracee {
    fn is_syscall_stop(signal: libc::c_int) -> bool {
        signal == libc::SIGTRAP | 0x80
    }

    /// Steps until a syscall stop, forwarding any intervening signals.
    fn next_syscall_stop(&self) -> io::Result<()> {
        let mut pending = None;
        for _ in 0..1000 {
            self.syscall_step(pending)?;
            match self.wait()? {
                WaitEvent::Stopped(signal) if Self::is_syscall_stop(signal) => return Ok(()),
                WaitEvent::Stopped(signal) => pending = Some(signal),
                _ => return Err(io::Error::other("tracee exited before syscall stop")),
            }
        }
        Err(io::Error::other("no syscall stop observed"))
    }

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
        let tracee = Self {
            pid,
            next_syscall_is_entry: Cell::new(true),
            last_syscall_was_entry: Cell::new(false),
        };
        match tracee.wait()? {
            WaitEvent::Stopped(libc::SIGSTOP) | WaitEvent::Stopped(libc::SIGTRAP) => {
                tracee.set_options(0)?;
                Ok(tracee)
            }
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
            let signal = libc::WSTOPSIG(status);
            if Self::is_syscall_stop(signal) {
                let entry = self.next_syscall_is_entry.get();
                self.next_syscall_is_entry.set(!entry);
                self.last_syscall_was_entry.set(entry);
            } else {
                self.last_syscall_was_entry.set(false);
                if status >> 16 == libc::PTRACE_EVENT_EXEC {
                    // execve's syscall-exit stop is still pending once
                    // PTRACE_SYSCALL is resumed after the exec event.
                    self.next_syscall_is_entry.set(false);
                }
            }
            Ok(WaitEvent::Stopped(signal))
        } else if libc::WIFEXITED(status) {
            Ok(WaitEvent::Exited(libc::WEXITSTATUS(status)))
        } else if libc::WIFSIGNALED(status) {
            Ok(WaitEvent::Signaled(libc::WTERMSIG(status)))
        } else {
            Err(io::Error::other("unknown wait status"))
        }
    }

    pub fn set_options(&self, options: libc::c_long) -> io::Result<()> {
        let options = options | libc::PTRACE_O_TRACESYSGOOD as libc::c_long;
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
        // PTRACE_CONT does not trace syscalls, so no exit stop is pending.
        self.next_syscall_is_entry.set(true);
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

    #[cfg(any(target_arch = "aarch64", target_arch = "x86_64"))]
    fn read_registers(&self) -> io::Result<libc::user_regs_struct> {
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
        Ok(registers)
    }

    #[cfg(any(target_arch = "aarch64", target_arch = "x86_64"))]
    fn write_registers(&self, registers: &libc::user_regs_struct) -> io::Result<()> {
        let mut iov = libc::iovec {
            iov_base: (registers as *const libc::user_regs_struct)
                .cast_mut()
                .cast::<libc::c_void>(),
            iov_len: std::mem::size_of::<libc::user_regs_struct>(),
        };
        // SAFETY: iovec points to a live, initialized register buffer.
        if unsafe {
            libc::ptrace(
                libc::PTRACE_SETREGSET,
                self.pid,
                libc::NT_PRSTATUS as *mut libc::c_void,
                (&mut iov as *mut libc::iovec).cast::<libc::c_void>(),
            )
        } < 0
        {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }

    /// Sets the six syscall argument registers.
    #[cfg(any(target_arch = "aarch64", target_arch = "x86_64"))]
    pub fn set_syscall_args(&self, args: &[u64; 6]) -> io::Result<()> {
        let mut registers = self.read_registers()?;
        #[cfg(target_arch = "aarch64")]
        {
            for (index, value) in args.iter().enumerate() {
                registers.regs[index] = *value;
            }
        }
        #[cfg(target_arch = "x86_64")]
        {
            registers.rdi = args[0];
            registers.rsi = args[1];
            registers.rdx = args[2];
            registers.r10 = args[3];
            registers.r8 = args[4];
            registers.r9 = args[5];
        }
        self.write_registers(&registers)
    }

    /// Reads the six syscall argument registers.
    #[cfg(any(target_arch = "aarch64", target_arch = "x86_64"))]
    pub fn syscall_args(&self) -> io::Result<[u64; 6]> {
        let registers = self.read_registers()?;
        #[cfg(target_arch = "aarch64")]
        {
            Ok([
                registers.regs[0],
                registers.regs[1],
                registers.regs[2],
                registers.regs[3],
                registers.regs[4],
                registers.regs[5],
            ])
        }
        #[cfg(target_arch = "x86_64")]
        {
            Ok([
                registers.rdi,
                registers.rsi,
                registers.rdx,
                registers.r10,
                registers.r8,
                registers.r9,
            ])
        }
    }

    /// Reads the syscall return register.
    #[cfg(any(target_arch = "aarch64", target_arch = "x86_64"))]
    pub fn return_value(&self) -> io::Result<u64> {
        let registers = self.read_registers()?;
        #[cfg(target_arch = "aarch64")]
        {
            Ok(registers.regs[0])
        }
        #[cfg(target_arch = "x86_64")]
        {
            Ok(registers.rax)
        }
    }

    /// Advances until the tracee is stopped at a syscall-entry stop.
    ///
    /// `PTRACE_O_TRACESYSGOOD` distinguishes syscall stops from other traps;
    /// entry/exit parity is tracked in `wait()` rather than guessed from the
    /// syscall number, which is not stable across the two stops.
    #[cfg(any(target_arch = "aarch64", target_arch = "x86_64"))]
    pub fn seek_syscall_entry(&self) -> io::Result<()> {
        if self.last_syscall_was_entry.get() {
            return Ok(());
        }
        for _ in 0..1000 {
            self.next_syscall_stop()?;
            if self.last_syscall_was_entry.get() {
                return Ok(());
            }
        }
        Err(io::Error::other("no syscall entry stop observed"))
    }

    /// Seeks a syscall-entry stop, injects a syscall and returns its result.
    #[cfg(any(target_arch = "aarch64", target_arch = "x86_64"))]
    pub fn inject_syscall(&self, number: u64, args: &[u64; 6]) -> io::Result<u64> {
        self.seek_syscall_entry()?;
        #[cfg(target_arch = "x86_64")]
        {
            self.set_syscall_args(args)?;
            self.set_syscall_number(number)?;
            self.next_syscall_stop()?;
            self.return_value()
        }
        #[cfg(target_arch = "aarch64")]
        {
            // The kernel captured the syscall number before the entry stop, so
            // writing x8 alone leaves the original syscall in flight. Rewind PC
            // by the 4-byte `svc` instruction to make the kernel capture our x8.
            let original = self.read_registers()?;
            let mut setup = original;
            setup.regs[8] = number;
            setup.pc -= 4;
            self.write_registers(&setup)?;

            self.next_syscall_stop()?; // original syscall's exit stop
            let original_result = self.return_value()?;
            self.next_syscall_stop()?; // injected syscall's entry stop
            self.set_syscall_args(args)?;
            self.set_syscall_number(number)?;
            self.next_syscall_stop()?; // injected syscall's exit stop
            let result = self.return_value()?;

            // Resume the tracee as if only its original syscall had run.
            let mut restore = original;
            restore.regs[0] = original_result;
            self.write_registers(&restore)?;
            Ok(result)
        }
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

    /// Reads the stack pointer and program counter at a tracee stop.
    #[cfg(target_arch = "aarch64")]
    pub fn instruction_pointer(&self) -> io::Result<(u64, u64)> {
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
        Ok((registers.sp, registers.pc))
    }

    /// Sets the stack pointer and program counter at a tracee stop.
    #[cfg(target_arch = "aarch64")]
    pub fn set_instruction_pointer(&self, sp: u64, pc: u64) -> io::Result<()> {
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
        registers.sp = sp;
        registers.pc = pc;
        let mut put = libc::iovec {
            iov_base: (&registers as *const libc::user_regs_struct)
                .cast_mut()
                .cast::<libc::c_void>(),
            iov_len: std::mem::size_of::<libc::user_regs_struct>(),
        };
        // SAFETY: put.iov_base points to a live register buffer.
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

    /// Reads an arbitrary byte range, handling unaligned boundaries.
    pub fn read_bytes(&self, address: usize, length: usize) -> io::Result<Vec<u8>> {
        let mut output = Vec::with_capacity(length);
        for index in 0..length {
            let current = address + index;
            let word_address = current & !7;
            let shift = (current - word_address) * 8;
            let word = self.peek_word(word_address)?;
            output.push((word >> shift) as u8);
        }
        Ok(output)
    }

    /// Writes an arbitrary byte range with read-modify-write at unaligned edges.
    pub fn write_bytes(&self, address: usize, bytes: &[u8]) -> io::Result<()> {
        for (index, byte) in bytes.iter().enumerate() {
            let current = address + index;
            let word_address = current & !7;
            let shift = (current - word_address) * 8;
            let mut word = self.peek_word(word_address)?;
            word = (word & !(0xffu64 << shift)) | (u64::from(*byte) << shift);
            self.poke_word(word_address, word)?;
        }
        Ok(())
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
    fn byte_memory_access_roundtrip() {
        let tracee = Tracee::spawn("/bin/sleep", &["30"]).expect("spawn");
        let maps = std::fs::read_to_string(format!("/proc/{}/maps", tracee.pid())).expect("maps");
        let line = maps
            .lines()
            .find(|line| line.contains("rw-p") && !line.contains("/dev/"))
            .expect("writable mapping");
        let range = line.split_whitespace().next().expect("address range");
        let start =
            usize::from_str_radix(range.split('-').next().expect("start"), 16).expect("hex");
        let payload = b"xyzterm-stack";
        tracee.write_bytes(start, payload).expect("write bytes");
        assert_eq!(
            tracee.read_bytes(start, payload.len()).expect("read bytes"),
            payload
        );
        tracee.kill(libc::SIGKILL).expect("kill");
        let _ = tracee.wait();
    }

    #[test]
    fn loader_stack_can_be_written_into_a_tracee() {
        let tracee = Tracee::spawn("/bin/sleep", &["30"]).expect("spawn");
        let maps = std::fs::read_to_string(format!("/proc/{}/maps", tracee.pid())).expect("maps");
        let line = maps
            .lines()
            .find(|line| line.contains("rw-p") && !line.contains("/dev/"))
            .expect("writable mapping");
        let range = line.split_whitespace().next().expect("range");
        let start =
            usize::from_str_radix(range.split('-').next().expect("start"), 16).expect("hex");
        let stack = proot_loader::build_stack(
            start as u64,
            &["sh", "-c", "echo hi"],
            &["HOME=/root"],
            &[(proot_loader::AT_PAGESZ, 4096)],
        );
        tracee.write_bytes(start, &stack).expect("write stack");
        assert_eq!(
            tracee.read_bytes(start, stack.len()).expect("read stack"),
            stack
        );
        tracee.kill(libc::SIGKILL).expect("kill");
        let _ = tracee.wait();
    }

    #[test]
    #[cfg(target_arch = "aarch64")]
    fn stack_pointer_and_pc_roundtrip() {
        let tracee = Tracee::spawn("/bin/sleep", &["30"]).expect("spawn");
        let (sp, pc) = tracee.instruction_pointer().expect("registers");
        assert!(sp != 0 && pc != 0);
        tracee
            .set_instruction_pointer(sp, pc)
            .expect("set registers");
        assert_eq!(tracee.instruction_pointer().expect("read back"), (sp, pc));
        tracee.kill(libc::SIGKILL).expect("kill");
        let _ = tracee.wait();
    }

    #[test]
    #[cfg(any(target_arch = "aarch64", target_arch = "x86_64"))]
    fn syscall_arguments_roundtrip_in_registers() {
        let tracee = Tracee::spawn("/bin/sleep", &["30"]).expect("spawn");
        tracee.cont(None).expect("continue");
        let _ = tracee.wait().expect("stop");
        let args = [11, 22, 33, 44, 55, 66];
        tracee.set_syscall_args(&args).expect("set args");
        assert_eq!(tracee.syscall_args().expect("read args"), args);
        tracee.set_syscall_number(39).expect("set number");
        assert_eq!(tracee.syscall_number().expect("read number"), 39);
        tracee.cont(Some(libc::SIGKILL)).expect("resume with kill");
        let _ = tracee.wait();
    }

    #[test]
    #[cfg(any(target_arch = "aarch64", target_arch = "x86_64"))]
    fn injected_getpid_returns_tracee_pid() {
        let tracee = Tracee::spawn("/bin/sleep", &["30"]).expect("spawn");
        tracee
            .set_options(libc::PTRACE_O_TRACEEXEC as libc::c_long)
            .expect("set_options");
        tracee.cont(None).expect("continue to exec stop");
        assert_eq!(
            tracee.wait().expect("exec stop"),
            WaitEvent::Stopped(libc::SIGTRAP)
        );

        let result = tracee
            .inject_syscall(libc::SYS_getpid as u64, &[0; 6])
            .expect("inject getpid");
        assert_eq!(result, tracee.pid() as u64);

        tracee.kill(libc::SIGKILL).expect("kill");
        let _ = tracee.wait();
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
