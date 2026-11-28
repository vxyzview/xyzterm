//! First host-verifiable end-to-end guest ELF execution path.
//!
//! A host process is spawned under `PTRACE_TRACEME` and used as a tracee
//! shell: guest PT_LOAD segments and a guest stack are mapped into its address
//! space with injected `mmap`, filled with `PTRACE_POKEDATA`, and the tracee's
//! PC/SP are redirected to the guest entry point. Execution then continues.
//!
//! Scope and limits:
//! - Only static, same-architecture ELF64 guests are supported. A guest with
//!   `PT_INTERP` needs a dynamic linker/relocation pass and is rejected.
//! - `Tracee::inject_syscall` currently re-runs the original syscall once
//!   before the injected one and restores the original register set afterwards;
//!   injected syscalls are only used here for anonymous mappings whose
//!   duplication is harmless.
//! - On hosts that themselves run under an outer ptrace tool (such as the
//!   proot sandbox used for development), the outer tracer consumes the
//!   guest's `svc` exit syscall. In that environment the guest instructions
//!   execute but the process dies with SIGILL on the instruction following
//!   the syscall. `execute` reports this failure instead of claiming a guest
//!   exit code; the host tests assert exactly what is observable.

#[cfg(not(any(target_arch = "aarch64", target_arch = "x86_64")))]
compile_error!("proot-runtime supports aarch64 and x86_64 Linux hosts only");

use std::io;

use proot_ptrace::{Tracee, WaitEvent};

/// Guest image state after its segments and stack have been injected.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GuestState {
    /// Address the tracee's PC is set to.
    pub entry: u64,
    /// Address the tracee's SP is set to (start of the guest stack image).
    pub sp: u64,
}

// `Tracee::spawn` appends an empty argv entry, so `/bin/sleep` would reject
// its arguments; a shell with a compound command ignores it and stays alive.
const HOLDER: &str = "/bin/sh";
const HOLDER_ARGS: [&str; 2] = ["-c", "sleep 3600; :"];

/// Guest base for ET_DYN images: above mmap_min_addr and inside the address
/// range accepted by the host kernel (very high addresses fail with ENOMEM).
const PIE_BASE: u64 = 0x1000_0000;

const PF_X: u32 = 1;
const PF_W: u32 = 2;
const PF_R: u32 = 4;

const PROT_READ: u64 = libc::PROT_READ as u64;
const PROT_WRITE: u64 = libc::PROT_WRITE as u64;
const PROT_EXEC: u64 = libc::PROT_EXEC as u64;

/// Runs `program_path` as a guest ELF and returns its exit code.
pub struct ProotRuntime;

impl Default for ProotRuntime {
    fn default() -> Self {
        Self::new()
    }
}

impl ProotRuntime {
    pub fn new() -> Self {
        Self
    }

    /// Spawns a tracee, loads the guest image, runs it and waits for its exit.
    pub fn run(&self, program_path: &str) -> io::Result<i32> {
        let image = std::fs::read(program_path)?;
        let tracee = Tracee::spawn(HOLDER, &HOLDER_ARGS)?;
        self.reach_exec_stop(&tracee)?;
        let state = self.prepare(&tracee, program_path, &image)?;
        self.execute(&tracee, &state)
    }

    /// Advances a freshly spawned tracee to the post-exec stop of the host
    /// helper, where its address space is stable and syscalls can be injected.
    pub fn reach_exec_stop(&self, tracee: &Tracee) -> io::Result<()> {
        tracee.set_options(libc::PTRACE_O_TRACEEXEC as libc::c_long)?;
        tracee.cont(None)?;
        match tracee.wait()? {
            // TRACEEXEC reports its event as SIGTRAP; no other trap is
            // expected between the spawn SIGSTOP and exec of the helper.
            WaitEvent::Stopped(libc::SIGTRAP) => Ok(()),
            WaitEvent::Exited(code) => Err(io::Error::other(format!(
                "helper exited before exec with code {code}"
            ))),
            WaitEvent::Signaled(signal) => Err(io::Error::other(format!(
                "helper died before exec with signal {signal}"
            ))),
            WaitEvent::Stopped(signal) => Err(io::Error::other(format!(
                "unexpected stop {signal} before exec"
            ))),
        }
    }

    /// Maps and fills the guest segments and stack, then sets PC/SP. The
    /// tracee must be stopped at a syscall stop (see `reach_exec_stop`).
    pub fn prepare(
        &self,
        tracee: &Tracee,
        program_path: &str,
        image: &[u8],
    ) -> io::Result<GuestState> {
        let info = proot_elf::parse(image)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
        if info.machine != host_machine() || info.class != 2 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "guest ELF architecture does not match the host",
            ));
        }
        if info.interp_range().is_some() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "dynamic ELF interpreter is not supported by the first-stage loader",
            ));
        }

        let page = page_size()?;
        let base = if info.is_pie() { PIE_BASE } else { 0 };
        let entry = base
            .checked_add(info.entry)
            .ok_or_else(|| io::Error::other("guest entry overflow"))?;
        if entry == 0 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "guest has no entry point",
            ));
        }

        // proot_loader flattens PT_LOAD into (guest address, file bytes).
        let segments = proot_loader::load_segments(image, base);
        if segments.is_empty() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "guest ELF has no loadable file-backed segments",
            ));
        }

        // One anonymous mapping per page group, filled with POKEDATA and then
        // restricted to the ELF's final segment permissions.
        let groups = proot_elf::load_groups(&info, page);
        let mut group_flags: std::collections::BTreeMap<u64, u32> = Default::default();
        for segment in info.load_segments() {
            group_flags
                .entry(segment.vaddr.saturating_add(base) / page * page)
                .and_modify(|flags| *flags |= segment.flags)
                .or_insert(segment.flags);
        }
        for (start, end) in &groups {
            map_region(tracee, *start, end - start, PROT_READ | PROT_WRITE)?;
        }
        for (address, bytes) in &segments {
            let address = usize::try_from(*address)
                .map_err(|_| io::Error::other("guest address does not fit usize"))?;
            tracee.write_bytes(address, bytes)?;
        }
        for (start, end) in groups {
            let prot = group_flags
                .get(&start)
                .map(|flags| segment_prot(*flags))
                .unwrap_or(PROT_READ);
            protect_region(tracee, start, end - start, prot)?;
        }

        // The stack image starts at `stack_base`; SP points at its first word
        // (argc), matching the Linux initial-stack layout.
        let stack_base = align_up(base.saturating_add(info.max_vaddr()), page)
            .and_then(|value| value.checked_add(page))
            .ok_or_else(|| io::Error::other("guest stack address overflow"))?;
        let stack = proot_loader::build_stack(
            stack_base,
            &[program_path],
            &[],
            &[
                (proot_loader::AT_PAGESZ, page),
                (proot_loader::AT_ENTRY, entry),
            ],
        );
        let stack_len = align_up(stack.len() as u64, page)
            .ok_or_else(|| io::Error::other("guest stack size overflow"))?;
        map_region(tracee, stack_base, stack_len, PROT_READ | PROT_WRITE)?;
        tracee.write_bytes(stack_base as usize, &stack)?;

        set_sp_pc(tracee, stack_base, entry)?;
        Ok(GuestState {
            entry,
            sp: stack_base,
        })
    }

    /// Continues the prepared tracee and reports its exit code. A signal
    /// death is an error: it is not a guest exit code.
    pub fn execute(&self, tracee: &Tracee, _state: &GuestState) -> io::Result<i32> {
        tracee.cont(None)?;
        for _ in 0..1000 {
            match tracee.wait()? {
                WaitEvent::Exited(code) => return Ok(code),
                WaitEvent::Signaled(signal) => {
                    return Err(io::Error::other(format!(
                        "guest terminated by signal {signal}"
                    )));
                }
                // Deliver ordinary signals; traps not caused by our injected
                // syscalls are resumed harmlessly.
                WaitEvent::Stopped(signal) => tracee.cont(Some(signal))?,
            }
        }
        Err(io::Error::other("guest did not exit"))
    }
}

fn host_machine() -> u16 {
    match std::env::consts::ARCH {
        "aarch64" => 183, // EM_AARCH64
        "x86_64" => 62,   // EM_X86_64
        _ => 0,
    }
}

fn page_size() -> io::Result<u64> {
    // SAFETY: sysconf has no preconditions; it returns -1 on failure.
    let value = unsafe { libc::sysconf(libc::_SC_PAGESIZE) };
    if value <= 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(value as u64)
}

fn align_up(value: u64, alignment: u64) -> Option<u64> {
    value
        .checked_add(alignment - 1)
        .map(|v| v / alignment * alignment)
}

fn segment_prot(flags: u32) -> u64 {
    let mut prot = 0;
    if flags & PF_R != 0 {
        prot |= PROT_READ;
    }
    if flags & PF_W != 0 {
        prot |= PROT_WRITE;
    }
    if flags & PF_X != 0 {
        prot |= PROT_EXEC;
    }
    prot
}

fn syscall_result(value: u64, operation: &str) -> io::Result<u64> {
    if (value as i64) < 0 && (value as i64) >= -4095 {
        return Err(io::Error::from_raw_os_error((!value + 1) as i32));
    }
    if value == u64::MAX {
        return Err(io::Error::other(format!("{operation} failed")));
    }
    Ok(value)
}

/// Injects `mmap(MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE)` in the
/// tracee. `MAP_FIXED_NOREPLACE` keeps unrelated inherited mappings intact.
fn map_region(tracee: &Tracee, address: u64, length: u64, prot: u64) -> io::Result<()> {
    let flags =
        libc::MAP_PRIVATE as u64 | libc::MAP_ANONYMOUS as u64 | libc::MAP_FIXED_NOREPLACE as u64;
    let result = tracee.inject_syscall(
        libc::SYS_mmap as u64,
        &[address, length, prot, flags, u64::MAX, 0],
    )?;
    let mapped = syscall_result(result, "mmap")?;
    if mapped != address {
        return Err(io::Error::other(format!(
            "mmap returned {mapped:#x}, expected {address:#x}"
        )));
    }
    Ok(())
}

fn protect_region(tracee: &Tracee, address: u64, length: u64, prot: u64) -> io::Result<()> {
    let result =
        tracee.inject_syscall(libc::SYS_mprotect as u64, &[address, length, prot, 0, 0, 0])?;
    syscall_result(result, "mprotect")?;
    Ok(())
}

#[cfg(target_arch = "aarch64")]
fn set_sp_pc(tracee: &Tracee, sp: u64, pc: u64) -> io::Result<()> {
    // SAFETY invariant: the tracee is stopped under our ptrace ownership, so
    // `set_instruction_pointer` may write its NT_PRSTATUS register set.
    tracee.set_instruction_pointer(sp, pc)
}

#[cfg(target_arch = "x86_64")]
fn set_sp_pc(tracee: &Tracee, sp: u64, pc: u64) -> io::Result<()> {
    let mut registers: libc::user_regs_struct = unsafe { std::mem::zeroed() };
    let mut iov = libc::iovec {
        iov_base: (&mut registers as *mut libc::user_regs_struct).cast(),
        iov_len: std::mem::size_of::<libc::user_regs_struct>(),
    };
    // SAFETY: iovec points to a live register buffer; pid is our own tracee,
    // stopped under ptrace, so GETREGSET/SETREGSET are valid.
    if unsafe {
        libc::ptrace(
            libc::PTRACE_GETREGSET,
            tracee.pid(),
            libc::NT_PRSTATUS as *mut libc::c_void,
            (&mut iov as *mut libc::iovec).cast::<libc::c_void>(),
        )
    } < 0
    {
        return Err(io::Error::last_os_error());
    }
    registers.rsp = sp;
    registers.rip = pc;
    let mut put = libc::iovec {
        iov_base: (&registers as *const libc::user_regs_struct)
            .cast_mut()
            .cast::<libc::c_void>(),
        iov_len: std::mem::size_of::<libc::user_regs_struct>(),
    };
    // SAFETY: iovec points to a fully initialized register buffer and the
    // tracee is stopped and owned by this tracer.
    if unsafe {
        libc::ptrace(
            libc::PTRACE_SETREGSET,
            tracee.pid(),
            libc::NT_PRSTATUS as *mut libc::c_void,
            (&mut put as *mut libc::iovec).cast::<libc::c_void>(),
        )
    } < 0
    {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

#[cfg(all(test, target_arch = "aarch64"))]
fn sp_pc(tracee: &Tracee) -> io::Result<(u64, u64)> {
    // SAFETY invariant: stopped tracee owned by this tracer; see above.
    tracee.instruction_pointer()
}

#[cfg(all(test, target_arch = "x86_64"))]
fn sp_pc(tracee: &Tracee) -> io::Result<(u64, u64)> {
    let mut registers: libc::user_regs_struct = unsafe { std::mem::zeroed() };
    let mut iov = libc::iovec {
        iov_base: (&mut registers as *mut libc::user_regs_struct).cast(),
        iov_len: std::mem::size_of::<libc::user_regs_struct>(),
    };
    // SAFETY: iovec points to a live register buffer; pid is our stopped tracee.
    if unsafe {
        libc::ptrace(
            libc::PTRACE_GETREGSET,
            tracee.pid(),
            libc::NT_PRSTATUS as *mut libc::c_void,
            (&mut iov as *mut libc::iovec).cast::<libc::c_void>(),
        )
    } < 0
    {
        return Err(io::Error::last_os_error());
    }
    Ok((registers.rsp, registers.rip))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Builds a static ELF64 guest whose only PT_LOAD code executes the
    /// architecture's `exit(0)` trampoline supplied by proot-loader.
    fn static_exit_elf() -> Vec<u8> {
        let page = page_size().expect("page size") as usize;
        let base = 0x1000_0000u64;
        let code_offset = 0x78usize;
        let image_len = page as u64;
        let mut image = vec![0u8; page];
        image[..4].copy_from_slice(&[0x7f, b'E', b'L', b'F']);
        image[4] = 2; // ELFCLASS64
        image[5] = 1; // ELFDATA2LSB
        image[6] = 1; // EV_CURRENT
        image[16..18].copy_from_slice(&2u16.to_le_bytes()); // ET_EXEC
        image[18..20].copy_from_slice(&host_machine().to_le_bytes());
        image[20..24].copy_from_slice(&1u32.to_le_bytes()); // EV_CURRENT
        image[24..32].copy_from_slice(&(base + code_offset as u64).to_le_bytes());
        image[32..40].copy_from_slice(&64u64.to_le_bytes()); // e_phoff
        image[52..54].copy_from_slice(&64u16.to_le_bytes()); // e_ehsize
        image[54..56].copy_from_slice(&56u16.to_le_bytes()); // e_phentsize
        image[56..58].copy_from_slice(&1u16.to_le_bytes()); // e_phnum
        let header = &mut image[64..120];
        header[..4].copy_from_slice(&1u32.to_le_bytes()); // PT_LOAD
        header[4..8].copy_from_slice(&5u32.to_le_bytes()); // PF_R | PF_X
        header[8..16].copy_from_slice(&0u64.to_le_bytes()); // p_offset
        header[16..24].copy_from_slice(&base.to_le_bytes());
        header[24..32].copy_from_slice(&base.to_le_bytes());
        header[32..40].copy_from_slice(&image_len.to_le_bytes());
        header[40..48].copy_from_slice(&image_len.to_le_bytes());
        header[48..56].copy_from_slice(&(page as u64).to_le_bytes());
        image[code_offset..code_offset + proot_loader::AARCH64_EXIT_TRAMPOLINE.len()]
            .copy_from_slice(proot_loader::AARCH64_EXIT_TRAMPOLINE);
        image
    }

    fn temp_dir() -> std::path::PathBuf {
        let preferred = "/data/user/0/com.xyzterm.app/tmp/opencode";
        let path = std::path::Path::new(preferred);
        if path.is_dir() {
            path.to_path_buf()
        } else {
            std::env::temp_dir()
        }
    }

    fn write_guest(name: &str, image: &[u8]) -> std::path::PathBuf {
        let path = temp_dir().join(format!("proot-runtime-{}-{name}", std::process::id()));
        std::fs::write(&path, image).expect("write guest");
        path
    }

    #[cfg(target_arch = "aarch64")]
    fn prepare_static_guest(name: &str) -> (Tracee, Vec<u8>, std::path::PathBuf, GuestState) {
        let image = static_exit_elf();
        let path = write_guest(name, &image);
        let tracee = Tracee::spawn(HOLDER, &HOLDER_ARGS).expect("spawn holder");
        let runtime = ProotRuntime::new();
        runtime.reach_exec_stop(&tracee).expect("exec stop");
        let state = runtime
            .prepare(&tracee, path.to_str().expect("utf8 path"), &image)
            .expect("prepare");
        (tracee, image, path, state)
    }

    /// Proves (a) mappings, (b) guest bytes, (c) SP/PC and (d) child reaping.
    #[test]
    #[cfg(target_arch = "aarch64")]
    fn prepared_guest_has_mappings_bytes_and_registers() {
        use proot_loader::{AT_ENTRY, AT_PAGESZ};

        let (tracee, image, path, state) = prepare_static_guest("prepared");
        let info = proot_elf::parse(&image).expect("parse guest");
        let page = page_size().expect("page size");

        // (a) Injected mappings exist at the guest addresses.
        let maps = std::fs::read_to_string(format!("/proc/{}/maps", tracee.pid())).expect("maps");
        for (start, end) in proot_elf::load_groups(&info, page) {
            assert!(
                maps.lines().any(|line| {
                    let range = line.split_whitespace().next().unwrap_or("");
                    let mut parts = range.split('-');
                    let lo = u64::from_str_radix(parts.next().unwrap_or(""), 16).unwrap_or(0);
                    let hi = u64::from_str_radix(parts.next().unwrap_or(""), 16).unwrap_or(0);
                    lo <= start && hi >= end
                }),
                "missing mapping for {start:#x}..{end:#x}"
            );
        }

        // (b) Segment bytes and the built stack are present in tracee memory.
        for (address, bytes) in proot_loader::load_segments(&image, 0) {
            assert_eq!(
                tracee
                    .read_bytes(address as usize, bytes.len())
                    .expect("read segment"),
                bytes
            );
        }
        assert_eq!(tracee.peek_word(state.sp as usize).expect("argc"), 1);
        // argc, argv[0], argv NULL, envp NULL, then the auxv pairs.
        let auxv = state.sp + 4 * 8;
        assert_eq!(
            tracee.peek_word(auxv as usize).expect("AT_PAGESZ"),
            AT_PAGESZ
        );
        assert_eq!(
            tracee.peek_word(auxv as usize + 16).expect("AT_ENTRY"),
            AT_ENTRY
        );

        // (c) PC/SP point at the guest entry and stack.
        assert_eq!(sp_pc(&tracee).expect("registers"), (state.sp, state.entry));

        // (d) The tracee is reaped after being continued. Under this host's
        // outer proot tracer the guest exits via SIGILL; a clean guest exit
        // is not observable here (see the module documentation).
        let runtime = ProotRuntime::new();
        let result = runtime.execute(&tracee, &state);
        assert!(result.is_err(), "unexpected clean guest exit: {result:?}");
        assert!(tracee.kill(0).is_err(), "tracee was not reaped");
        let _ = std::fs::remove_file(path);
    }

    /// The guest's first three instructions actually execute: the SIGILL stop
    /// is reported at `entry + 12`, after the trampoline's `svc`. The outer
    /// tracer cancels the exit syscall, so no guest exit code is claimed.
    #[test]
    #[cfg(target_arch = "aarch64")]
    fn guest_instructions_run_up_to_its_exit_syscall() {
        let (tracee, _image, path, state) = prepare_static_guest("entry");
        tracee.cont(None).expect("continue");
        let event = tracee.wait().expect("guest stop");
        let expected = state.entry + proot_loader::AARCH64_EXIT_TRAMPOLINE.len() as u64;
        match event {
            WaitEvent::Stopped(libc::SIGILL) => {
                assert_eq!(sp_pc(&tracee).expect("registers").1, expected);
            }
            WaitEvent::Exited(code) => {
                // On an untraced host the guest would exit cleanly here.
                assert_eq!(code, 0);
            }
            other => panic!("unexpected guest event: {other:?}"),
        }
        let _ = tracee.kill(libc::SIGKILL);
        let _ = tracee.wait();
        let _ = std::fs::remove_file(path);
    }

    /// `run()` is callable end to end. On a host without an outer tracer the
    /// static guest exits 0; under the development sandbox its exit syscall is
    /// cancelled, so the error must name the signal rather than invent a code.
    #[test]
    #[cfg(target_arch = "aarch64")]
    fn run_reports_a_clean_exit_or_a_signal() {
        let path = write_guest("run", &static_exit_elf());
        let result = ProotRuntime::new().run(path.to_str().expect("utf8 path"));
        match result {
            Ok(0) => {}
            Err(error) if error.to_string().contains("signal") => {}
            other => panic!("unexpected run result: {other:?}"),
        }
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn run_rejects_non_elf_program() {
        let path = write_guest("invalid", b"not an elf");
        assert!(
            ProotRuntime::new()
                .run(path.to_str().expect("utf8 path"))
                .is_err()
        );
        let _ = std::fs::remove_file(path);
    }

    /// Documents why `/bin/true` cannot be used as the first guest: it is a
    /// dynamically linked PIE and needs a dynamic linker/relocation pass.
    #[test]
    fn dynamic_bin_true_is_rejected_before_execution() {
        if !std::path::Path::new("/bin/true").exists() {
            return;
        }
        let error = ProotRuntime::new()
            .run("/bin/true")
            .expect_err("dynamic guest");
        assert!(
            error
                .to_string()
                .contains("dynamic ELF interpreter is not supported"),
            "unexpected error: {error}"
        );
    }
}
