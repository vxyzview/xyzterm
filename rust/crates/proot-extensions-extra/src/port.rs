//! Pure Rust port of the host-testable logic in PRoot's small extensions:
//! `extension/extension.c`, `ashmem_memfd`, `mountinfo`, `port_switch` and
//! `kompat`.
//!
//! Only logic that can be exercised without a live tracee is ported: the
//! extension event dispatch/init rules, the ashmem→memfd syscall and ioctl
//! translation, mountinfo path/line rewriting and binding formatting, the
//! bind/connect/sendto port mapping with localhost detection, and kompat's
//! kernel-release parsing and syscall compatibility decisions.  Register
//! peeks/pokes, guest memory and the talloc shim live in the recording harness;
//! the port sees their values as plain arguments and written bytes.
//!
//! Everything that needs a live tracee or chained syscalls to actually run
//! (the seccomp/SIGSYS restart, the ELF auxv rewrite, the real fd-flag fcntl
//! chain, talloc ownership, `/proc/<pid>/fd` reads, the real mountinfo file)
//! stays in C and is documented in README.md.  The chain *decisions*, which
//! are pure, are ported and exposed as recorded chained-syscall argument lists.

#![forbid(unsafe_code)]

use std::ffi::c_longlong;

/* ---- syscall numbers (aarch64 PR_* from syscall/sysnums-arm64.h) ---- */
const PR_MEMFD_CREATE: i64 = 179;
const PR_FTRUNCATE: i64 = 95;
const PR_FTRUNCATE64: i64 = 96;
const PR_FSTAT: i64 = 88;
const PR_FSTATAT64: i64 = 90;
const PR_IOCTL: i64 = 150;
const PR_OPENAT: i64 = 223;
const PR_BIND: i64 = 24;
const PR_CONNECT: i64 = 44;
const PR_SOCKETCALL: i64 = 357;
const PR_SENDTO: i64 = 307;

const SYS_BIND: i64 = 2;
const SYS_CONNECT: i64 = 3;
const SYS_SENDTO: i64 = 11;

/* ---- ashmem / memfd ---- */
const MFD_CLOEXEC: u64 = 1;
const O_RDWR: u64 = 2;
const O_CLOEXEC: u64 = 0o2000000; /* 524288 */
const O_NONBLOCK: u64 = 0o4000;
const AT_FDCWD: i64 = -100;
const IOC_WRITE: u64 = 1;
const IOC_NRBITS: u32 = 8;
const IOC_TYPEBITS: u32 = 8;
const IOC_SIZEBITS: u32 = 14;

fn ioctl_ioc(dir: u64, ty: u8, nr: u8, size: u32) -> u64 {
    (dir << (IOC_NRBITS + IOC_TYPEBITS + IOC_SIZEBITS))
        | ((size as u64) << (IOC_NRBITS + IOC_TYPEBITS))
        | ((ty as u64) << IOC_NRBITS)
        | nr as u64
}

fn ashmem_set_size() -> u64 {
    ioctl_ioc(IOC_WRITE, b'A', 3, size_of::<usize>() as u32)
}
fn ashmem_get_size() -> u64 {
    ioctl_ioc(0, b'A', 4, 0)
}
const OFF_STAT_ST_SIZE: u64 = 48; /* offsetof(struct stat, st_size) on this ABI */
const CS_STAT_ENTERED: i64 = 1;
const CS_STAT_CHAINED_IOCTL: i64 = 2;

/* ---- socket / port_switch ---- */
const AF_INET: u16 = 2;
const AF_INET6: u16 = 10;
const PORT_THRESHOLD: u16 = 1024;
const PORT_ADDITION: u16 = 2000;
const SYSARG_2_ADDR: u64 = 0x1000; /* fake tracee registers in the harness */
const SYSARG_5_ADDR: u64 = 0x3000; /* RA_GUEST_BASE + 0x2000 */

/* ---- kompat ---- */
const fn kver(major: u32, minor: u32, revision: u32) -> i32 {
    ((major << 16) | (minor << 8) | revision) as i32
}
const V216: i32 = kver(2, 6, 16);
const V219: i32 = kver(2, 6, 19);
const V222: i32 = kver(2, 6, 22);
const V223: i32 = kver(2, 6, 23);
const V224: i32 = kver(2, 6, 24);
const V227: i32 = kver(2, 6, 27);
const V228: i32 = kver(2, 6, 28);
const AT_SYMLINK_NOFOLLOW: u64 = 0x100;
const AT_NO_AUTOMOUNT: u64 = 0x800;
const AT_EMPTY_PATH: u64 = 0x1000;
const AT_SYMLINK_FOLLOW: u64 = 0x400;
const AT_REMOVEDIR: u64 = 0x200;
const FUTEX_PRIVATE_FLAG: u64 = 0x80;
const F_DUPFD: u64 = 0;
const F_DUPFD_CLOEXEC: u64 = 1030;
const F_SETFD: u64 = 2;
const F_SETFL: u64 = 4;
const FD_CLOEXEC: u64 = 1;
const SYSCALL_AVOIDER: u64 = u64::MAX; /* arch.h, aarch64 */
const UTS_FIELD_LEN: usize = 65;

/* ---- byte helpers ---- */

fn u64_at(bytes: &[u8], at: usize) -> u64 {
    let mut raw = [0u8; 8];
    let n = 8.min(bytes.len().saturating_sub(at));
    raw[..n].copy_from_slice(&bytes[at..at + n]);
    u64::from_le_bytes(raw)
}

fn i64_at(bytes: &[u8], at: usize) -> i64 {
    u64_at(bytes, at) as i64
}

fn u16_at(bytes: &[u8], at: usize) -> u16 {
    let mut raw = [0u8; 2];
    let n = 2.min(bytes.len().saturating_sub(at));
    raw[..n].copy_from_slice(&bytes[at..at + n]);
    u16::from_le_bytes(raw)
}

fn put_u64(out: &mut [u8], at: usize, value: u64) {
    if let Some(slot) = out.get_mut(at..at + 8) {
        slot.copy_from_slice(&value.to_le_bytes());
    }
}

fn put_i64(out: &mut [u8], at: usize, value: i64) {
    put_u64(out, at, value as u64);
}

fn copy_out(out: &mut [u8], at: usize, bytes: &[u8]) -> usize {
    let Some(slot) = out.get_mut(at..) else {
        return 0;
    };
    let n = bytes.len().min(slot.len());
    slot[..n].copy_from_slice(&bytes[..n]);
    n
}

fn c_string(bytes: &[u8]) -> &[u8] {
    match bytes.iter().position(|&b| b == 0) {
        Some(at) => &bytes[..at],
        None => bytes,
    }
}

fn load_regs(bytes: &[u8]) -> [u64; 16] {
    let mut regs = [0u64; 16];
    for (index, reg) in regs.iter_mut().enumerate() {
        if (index + 1) * 8 <= bytes.len() {
            *reg = u64_at(bytes, index * 8);
        }
    }
    regs
}

fn dump_regs(out: &mut [u8], at: usize, regs: &[u64; 16]) {
    for (index, value) in regs.iter().enumerate() {
        put_u64(out, at + index * 8, *value);
    }
}

/// `strtol(3)`/base 10 on bytes: whitespace, sign, digits; returns the value
/// and the byte index of the first unconsumed character, saturating on overflow.
fn strtol10(bytes: &[u8]) -> (i64, usize) {
    let mut at = 0;
    while at < bytes.len() && matches!(bytes[at], b' ' | b'\t' | b'\n' | 0x0b | 0x0c | b'\r') {
        at += 1;
    }
    let negative = if at < bytes.len() && (bytes[at] == b'-' || bytes[at] == b'+') {
        let minus = bytes[at] == b'-';
        at += 1;
        minus
    } else {
        false
    };
    let start = at;
    let mut value: u64 = 0;
    while at < bytes.len() && bytes[at].is_ascii_digit() {
        value = value
            .saturating_mul(10)
            .saturating_add((bytes[at] - b'0') as u64);
        at += 1;
    }
    if at == start {
        return (0, start);
    }
    let signed = if negative {
        ((value.min(i64::MAX as u64 + 1)) as i64).wrapping_neg()
    } else {
        (value.min(i64::MAX as u64)) as i64
    };
    (signed, at)
}

/// `strtoul(3)`/base 10 as `parse_kernel_release()` uses it.
fn strtoul10(bytes: &[u8]) -> (u64, usize) {
    let mut at = 0;
    while at < bytes.len() && matches!(bytes[at], b' ' | b'\t' | b'\n' | 0x0b | 0x0c | b'\r') {
        at += 1;
    }
    let negative = if at < bytes.len() && (bytes[at] == b'-' || bytes[at] == b'+') {
        let minus = bytes[at] == b'-';
        at += 1;
        minus
    } else {
        false
    };
    let start = at;
    let mut value: u64 = 0;
    while at < bytes.len() && bytes[at].is_ascii_digit() {
        value = value
            .saturating_mul(10)
            .saturating_add((bytes[at] - b'0') as u64);
        at += 1;
    }
    if at == start {
        return (0, start);
    }
    (
        if negative {
            (0u64).wrapping_sub(value)
        } else {
            value
        },
        at,
    )
}

/// `strtol(3)`/base 16 for the complex `parse_utsname()` hwcap field.
fn strtol16(bytes: &[u8]) -> Result<(i64, usize), ()> {
    let mut at = 0;
    while at < bytes.len() && matches!(bytes[at], b' ' | b'\t' | b'\n' | 0x0b | 0x0c | b'\r') {
        at += 1;
    }
    let negative = if at < bytes.len() && (bytes[at] == b'-' || bytes[at] == b'+') {
        let minus = bytes[at] == b'-';
        at += 1;
        minus
    } else {
        false
    };
    let start = at;
    let mut value: u128 = 0;
    while at < bytes.len() && bytes[at].is_ascii_hexdigit() {
        value = value
            .saturating_mul(16)
            .saturating_add((bytes[at] as char).to_digit(16).unwrap_or(0) as u128);
        at += 1;
    }
    if at == start {
        return Ok((0, start));
    }
    let limit = if negative {
        i64::MAX as u128 + 1
    } else {
        i64::MAX as u128
    };
    if value > limit {
        return Err(()); /* errno == ERANGE */
    }
    let magnitude = value as i64;
    Ok((if negative { -magnitude } else { magnitude }, at))
}

/* ---- extension.c ---- */

#[repr(C)]
struct ExtensionLayout {
    callback: usize,
    config: usize,
    filtered_sysnums: usize,
    link: [usize; 2],
}

/// Sizes/offsets of the extension event enum, CLONE_RECONF, the filter flag
/// and `struct extension` (as `ra_extension_layout()` exposes them).
pub fn ra_extension_layout(out: &mut [u8]) -> c_longlong {
    use std::mem::{offset_of, size_of};
    let values: [i64; 26] = [
        0,
        1,
        2,
        3,
        4,
        5,
        6,
        7,
        8,
        9,
        10,
        11,
        12,
        13,
        14,
        15,
        16,
        17,
        18,
        19,
        -1, /* CLONE_RECONF */
        1,  /* FILTER_SYSEXIT */
        size_of::<ExtensionLayout>() as i64,
        offset_of!(ExtensionLayout, callback) as i64,
        offset_of!(ExtensionLayout, config) as i64,
        offset_of!(ExtensionLayout, filtered_sysnums) as i64,
    ];
    for (index, value) in values.iter().enumerate() {
        put_i64(out, index * 8, *value);
    }
    values.len() as c_longlong
}

/// `notify_extensions()`: the first non-zero callback status wins.  The list
/// is built by inserting statuses 0..n at the head, so it is walked in reverse.
pub fn ra_extension_notify(statuses: &[u8], event: c_longlong, out: &mut [u8]) -> c_longlong {
    let _ = event; /* the harness callbacks return their status for every event */
    let count = (statuses.len() / 8).min(8);
    let mut status = 0i64;
    let mut index = -1i64;
    for i in (0..count).rev() {
        let value = (i64_at(statuses, i * 8) as i32) as i64;
        if value != 0 {
            status = value;
            index = i as i64;
            break;
        }
    }
    put_i64(out, 0, index);
    status
}

/// `initialize_extension()`: a negative INITIALIZATION status removes the new
/// extension through its destructor; otherwise it stays attached.
pub fn ra_extension_initialize(init_status: c_longlong, out: &mut [u8]) -> c_longlong {
    let status = (init_status as i32) as i64;
    put_i64(out, 0, if status < 0 { 0 } else { 1 });
    if status < 0 { status } else { 0 }
}

/* ---- ashmem_memfd.c ---- */

/// `ashmem_memfd_handle_memfd_create()`: rewrite memfd_create into
/// openat("/dev/ashmem", O_RDWR[, O_CLOEXEC]) when memfd is unsupported.
pub fn ra_ashmem_create(
    supported: c_longlong,
    flags: c_longlong,
    from_sigsys: c_longlong,
    out: &mut [u8],
) -> c_longlong {
    /* The harness always loads `flags` into SYSARG_2 before the original. */
    put_i64(out, 24, flags);
    if supported == 0 {
        put_i64(out, 8, PR_OPENAT);
        put_i64(out, 16, AT_FDCWD);
        put_i64(out, 24, SYSARG_2_ADDR as i64);
        let cloexec = if (flags as u64) & MFD_CLOEXEC != 0 {
            O_CLOEXEC
        } else {
            0
        };
        put_i64(out, 32, (O_RDWR | cloexec) as i64);
        put_i64(out, 40, 0);
        copy_out(out, 56, b"/dev/ash");
        if from_sigsys != 0 {
            /* The real handler calls restart_syscall_after_seccomp() here;
             * that ptrace-only path is not recorded. */
            return 2;
        }
    }
    0
}

/// `ashmem_memfd_handle_syscall()`: ftruncate → ioctl(ASHMEM_SET_SIZE), then
/// the original's intentional fall-through into the fstat handler; fstat and
/// fstatat64 arm the stat-size fixup when the fd is an ashmem fd.
pub fn ra_ashmem_syscall(
    sysnum: c_longlong,
    is_ashmem: c_longlong,
    word_at_arg2: c_longlong,
    regs_bytes: &[u8],
    out: &mut [u8],
) -> c_longlong {
    let mut regs = load_regs(regs_bytes);
    let mut number = (sysnum as u64).min(u64::from(u32::MAX));
    let mut chain_state = 0i64;
    let mut stat_addr = 0u64;
    let mut stat_fd = 0u64;
    let ashmem = is_ashmem != 0;

    match number as i64 {
        PR_MEMFD_CREATE => {} /* the harness config marks memfd supported */
        PR_FTRUNCATE | PR_FTRUNCATE64 => {
            if ashmem {
                number = PR_IOCTL as u64;
                regs[3] = regs[2]; /* old size becomes ioctl arg 3 */
                regs[2] = ashmem_set_size();
            }
            /* fall through to PR_FSTAT, as the original does */
            if ashmem {
                chain_state = CS_STAT_ENTERED;
                stat_fd = regs[1];
                stat_addr = regs[2].wrapping_add(OFF_STAT_ST_SIZE);
            }
        }
        PR_FSTAT => {
            if ashmem {
                chain_state = CS_STAT_ENTERED;
                stat_fd = regs[1];
                stat_addr = regs[2].wrapping_add(OFF_STAT_ST_SIZE);
            }
        }
        PR_FSTATAT64 => {
            /* peek_word(tracee, SYSARG_2): the harness preloads this word at
             * that guest address. */
            let word = if regs[2] < 0x8000 {
                word_at_arg2 as u64
            } else {
                0
            };
            if (regs[1] as i64) >= 0 && word & 0xFF == 0 && ashmem {
                chain_state = CS_STAT_ENTERED;
                stat_fd = regs[1];
                stat_addr = regs[3].wrapping_add(OFF_STAT_ST_SIZE);
            }
        }
        _ => {}
    }
    regs[0] = number; /* set_sysnum() updates SYSARG_NUM too */
    put_i64(out, 0, number as i64);
    dump_regs(out, 8, &regs);
    put_i64(out, 136, chain_state);
    put_i64(out, 144, stat_addr as i64);
    put_i64(out, 152, stat_fd as i64);
    0
}

/// `ashmem_memfd_handle_stat_exit()`: on a successful empty stat, chain an
/// `ioctl(fd, ASHMEM_GET_SIZE)` to fill `st_size`.
pub fn ra_ashmem_stat_exit(
    result: c_longlong,
    word_at_addr: c_longlong,
    chain_state: c_longlong,
    out: &mut [u8],
) -> c_longlong {
    if result != 0 || word_at_addr != 0 {
        put_i64(out, 0, 0);
        put_i64(out, 8, 0);
    } else {
        put_i64(out, 0, CS_STAT_CHAINED_IOCTL);
        put_i64(out, 8, 7);
        let chained = [
            PR_IOCTL,
            7, /* the harness' fixed fd */
            ashmem_get_size() as i64,
            0,
            0,
            0,
            0,
        ];
        for (index, value) in chained.iter().enumerate() {
            put_i64(out, 16 + index * 8, *value);
        }
    }
    let _ = chain_state; /* the harness sets CS_STAT_ENTERED before calling */
    0
}

/// `SYSCALL_CHAINED_EXIT` handler: copy the chained ioctl result into the
/// guest `st_size` word and clear SYSARG_RESULT.
pub fn ra_ashmem_chained_exit(
    chain_state: c_longlong,
    result: c_longlong,
    addr: c_longlong,
    out: &mut [u8],
) -> c_longlong {
    if chain_state == CS_STAT_CHAINED_IOCTL {
        put_i64(out, 0, (result as u32) as i64);
        put_i64(out, 8, 0);
        put_i64(out, 16, 0);
    } else {
        put_i64(out, 0, 0);
        put_i64(out, 8, result);
        put_i64(out, 16, chain_state);
    }
    let _ = addr; /* the harness writes the captured word back into guest memory */
    0
}

/* ---- mountinfo.c ---- */

fn parse_pairs(pairs: &[u8]) -> Vec<(&[u8], &[u8])> {
    let mut result = Vec::new();
    let mut at = 0;
    while at < pairs.len() {
        let Some(end) = pairs[at..].iter().position(|&b| b == 0) else {
            break;
        };
        let guest = &pairs[at..at + end];
        at += end + 1;
        let Some(end) = pairs[at..].iter().position(|&b| b == 0) else {
            break;
        };
        let host = &pairs[at..at + end];
        at += end + 1;
        result.push((guest, host));
    }
    result
}

/// `append_runtime_binding_lines()`: synthesize a mountinfo line for each
/// non-root runtime binding, with ids starting at 1000000.
pub fn ra_mountinfo_bindings(pairs: &[u8], out: &mut [u8]) -> c_longlong {
    let mut text = Vec::new();
    let mut next_id = 1_000_000i64;
    for (guest, host) in parse_pairs(pairs) {
        if guest == b"/" {
            continue;
        }
        use std::io::Write;
        let _ = writeln!(
            text,
            "{} 1 0:1 / {} rw,relatime - bind {} rw,relatime",
            next_id,
            String::from_utf8_lossy(guest),
            String::from_utf8_lossy(host)
        );
        next_id += 1;
    }
    copy_out(out, 0, &text) as c_longlong
}

/// Skip the first four space-separated columns of a mountinfo line, mirroring
/// the original's `for (i = 0; i < 4; ...) strchr(chunk, ' ')` loop.
fn skip_four_columns(line: &[u8]) -> Option<usize> {
    let mut chunk = 0;
    for _ in 0..4 {
        if chunk >= line.len() {
            return None;
        }
        chunk = chunk + 1 + line[chunk..].iter().position(|&b| b == b' ')?;
    }
    Some(chunk)
}

fn mountinfo_lines(text: &[u8]) -> Vec<&[u8]> {
    let mut lines = Vec::new();
    let mut start = 0;
    for (index, &byte) in text.iter().enumerate() {
        if byte == b'\n' {
            lines.push(&text[start..=index]);
            start = index + 1;
        }
    }
    if start < text.len() {
        lines.push(&text[start..]);
    }
    lines
}

fn is_standard_mount(chunk: &[u8]) -> bool {
    (chunk.len() == 4 && chunk == b"/dev")
        || (chunk.len() >= 5 && chunk.starts_with(b"/dev/"))
        || (chunk.len() == 5 && chunk == b"/proc")
        || (chunk.len() == 4 && chunk == b"/sys")
        || (chunk.len() >= 5 && chunk.starts_with(b"/sys/"))
        || (chunk.len() == 4 && chunk == b"/tmp")
}

/// `mountinfo_check_open_path()`: validate `/proc/<PID>/mountinfo`, then
/// rewrite the real mountinfo file when the guest root is under /data.
pub fn ra_mountinfo_path(
    path_bytes: &[u8],
    real_text: &[u8],
    compare_result: c_longlong,
    get_tracee_ok: c_longlong,
    out: &mut [u8],
) -> c_longlong {
    put_i64(out, 0, 0); /* redirected */
    put_i64(out, 8, 0); /* fopen(real) calls */
    put_i64(out, 16, 0); /* generated text length */

    let path = c_string(path_bytes);
    let len = path.len();
    if len <= 16 || !path.starts_with(b"/proc/") || &path[len - 10..] != b"/mountinfo" {
        return 0;
    }
    let (pid, end) = strtol10(&path[6..]);
    if 6 + end != len - 10 || pid <= 0 || pid > i32::MAX as i64 {
        return 0;
    }
    if get_tracee_ok == 0 {
        return 0;
    }
    /* PATHS_ARE_EQUAL (0) and PATH2_IS_PREFIX (2) mean the root is under /data. */
    let is_android_data = compare_result == 0 || compare_result == 2;
    if !is_android_data {
        return 0;
    }
    put_i64(out, 8, 1);

    let lines = mountinfo_lines(real_text);
    let mut text: Vec<u8> = Vec::new();
    let mut found_line = false;
    if is_android_data {
        for line in &lines {
            let Some(chunk) = skip_four_columns(line) else {
                break;
            };
            let Some(relative_end) = line[chunk..].iter().position(|&b| b == b' ') else {
                continue;
            };
            let chunk_end = chunk + relative_end;
            if chunk_end - chunk == 5 && &line[chunk..chunk_end] == b"/data" {
                text.extend_from_slice(&line[..chunk + 1]); /* keep only "/" of root */
                text.extend_from_slice(&line[chunk_end..]);
                found_line = true;
                break;
            }
        }
        if found_line {
            for line in &lines {
                let Some(chunk) = skip_four_columns(line) else {
                    break;
                };
                let Some(relative_end) = line[chunk..].iter().position(|&b| b == b' ') else {
                    continue;
                };
                if is_standard_mount(&line[chunk..chunk + relative_end]) {
                    text.extend_from_slice(line);
                }
            }
        }
    } else {
        for line in &lines {
            text.extend_from_slice(line);
        }
        found_line = true;
    }
    if found_line {
        put_i64(out, 0, 1);
        put_i64(out, 16, text.len() as i64);
        copy_out(out, 24, &text);
    }
    0
}

/* ---- port_switch.c ---- */

fn parse_kernel_release(text: &[u8]) -> i32 {
    let (major, mut at) = strtoul10(text);
    let mut minor = 0;
    let mut revision = 0;
    if at < text.len() && text[at] == b'.' {
        at += 1;
        let relative;
        (minor, relative) = strtoul10(&text[at..]);
        at += relative; /* keep the cursor absolute across the sliced parses */
    }
    if at < text.len() && text[at] == b'.' {
        at += 1;
        (revision, _) = strtoul10(&text[at..]);
    }
    major
        .wrapping_shl(16)
        .wrapping_add(minor.wrapping_shl(8))
        .wrapping_add(revision) as i32
}

fn is_loopback_v4(addr: &[u8]) -> bool {
    let mut bytes = [0u8; 4];
    let n = addr.len().min(4);
    bytes[..n].copy_from_slice(&addr[..n]);
    bytes == [127, 0, 0, 1]
}

fn is_loopback_v6(addr: &[u8]) -> bool {
    let mut bytes = [0u8; 16];
    let n = addr.len().min(16);
    bytes[..n].copy_from_slice(&addr[..n]);
    bytes[15] == 1 && bytes[..15].iter().all(|&b| b == 0)
}

struct PortWrites {
    items: Vec<(u64, u64)>,
}

impl PortWrites {
    fn new() -> Self {
        Self { items: Vec::new() }
    }
    fn push(&mut self, dest: u64, size: u64) {
        self.items.push((dest, size));
    }
    fn dump(&self, out: &mut [u8], at: usize) {
        for (index, (dest, size)) in self.items.iter().take(4).enumerate() {
            put_u64(out, at + index * 16, *dest);
            put_u64(out, at + 8 + index * 16, *size);
        }
    }
}

/// Apply the port +2000 mapping to one sockaddr and record where the original
/// would write the modified structure (and, for socketcall, its argument array).
fn map_port(
    sockaddr: &mut [u8],
    is_socketcall: bool,
    is_udp: bool,
    arg2: &[u64; 6],
    reg2_addr: u64,
    reg5_addr: u64,
    writes: &mut PortWrites,
) -> i64 {
    let family = u16_at(sockaddr, 0);
    /* The original passes `in`/`in6` (pointers) to sizeof, so every write is
     * one word long — a quirk the recordings capture. */
    let size = 8;
    let port = u16::from_be_bytes([sockaddr[2], sockaddr[3]]);
    if family != AF_INET && family != AF_INET6 {
        return 0; /* the original's switch has no other case */
    }
    if port == 0 || port >= PORT_THRESHOLD {
        return port as i64;
    }
    let new_port = port + PORT_ADDITION;
    let bytes = new_port.to_be_bytes();
    sockaddr[2] = bytes[0];
    sockaddr[3] = bytes[1];
    if is_socketcall && is_udp {
        writes.push(arg2[4], size);
        writes.push(reg2_addr, 8);
    } else if !is_socketcall && is_udp && family == AF_INET {
        writes.push(reg5_addr, size);
        /* The original's AF_INET6 branch repeats `is_socketcall && is_udp`,
         * so a non-socketcall UDP write is silently skipped there. */
    } else if !is_socketcall && is_udp {
        /* AF_INET6: no write (quirk above). */
    } else if is_socketcall {
        writes.push(arg2[1], size);
        writes.push(reg2_addr, 8);
    } else {
        writes.push(reg2_addr, size);
    }
    new_port as i64
}

fn sockaddr_is_localhost(sockaddr: &[u8]) -> bool {
    let family = u16_at(sockaddr, 0);
    if family == AF_INET {
        is_loopback_v4(&sockaddr[4..8.min(sockaddr.len())])
    } else if family == AF_INET6 {
        is_loopback_v6(&sockaddr[8..24.min(sockaddr.len())])
    } else {
        false
    }
}

/// `mod_port()` with plain arguments: family/port in, modified port and the
/// captured `write_data()` calls out.
pub fn ra_port_mod(
    family: c_longlong,
    port: c_longlong,
    is_socketcall: c_longlong,
    is_bind: c_longlong,
    is_udp: c_longlong,
    arg2_bytes: &[u8],
    out: &mut [u8],
) -> c_longlong {
    let _ = is_bind; /* only the bind printf() differs in the original */
    let family = family as u16;
    let mut sockaddr = [0u8; 128];
    sockaddr[..2].copy_from_slice(&family.to_le_bytes());
    let port = port as u16;
    sockaddr[2..4].copy_from_slice(&port.to_be_bytes());
    let mut arg2 = [0u64; 6];
    for (index, value) in arg2.iter_mut().enumerate() {
        if (index + 1) * 8 <= arg2_bytes.len() {
            *value = u64_at(arg2_bytes, index * 8);
        }
    }
    let mut writes = PortWrites::new();
    let new_port = map_port(
        &mut sockaddr,
        is_socketcall != 0,
        is_udp != 0,
        &arg2,
        SYSARG_2_ADDR,
        SYSARG_5_ADDR,
        &mut writes,
    );
    put_i64(out, 0, new_port);
    put_i64(out, 8, family as i64);
    put_i64(out, 16, writes.items.len() as i64);
    writes.dump(out, 24);
    new_port
}

/// `is_localhost()` with a raw sockaddr address.
pub fn ra_port_localhost(family: c_longlong, addr_bytes: &[u8]) -> c_longlong {
    let family = family as u16;
    let result = if family == AF_INET {
        is_loopback_v4(addr_bytes)
    } else if family == AF_INET6 {
        is_loopback_v6(addr_bytes)
    } else {
        false
    };
    result as i64
}

fn guest_at(guest: &[u8], address: u64, size: usize) -> Vec<u8> {
    let mut result = vec![0u8; size];
    if let Ok(at) = usize::try_from(address)
        && let Some(src) = guest.get(at..at.saturating_add(size))
    {
        result.copy_from_slice(src);
    }
    result
}

fn store_guest(guest: &mut [u8], address: u64, bytes: &[u8]) {
    if let Ok(at) = usize::try_from(address)
        && let Some(slot) = guest.get_mut(at..at.saturating_add(bytes.len()))
    {
        slot.copy_from_slice(bytes);
    }
}

/// `port_switch_callback(SYSCALL_ENTER_END)`: the bind/connect/sendto and
/// socketcall dispatch table, including the localhost-only rule.
pub fn ra_port_dispatch(
    sysnum: c_longlong,
    call: c_longlong,
    regs_bytes: &[u8],
    guest_bytes: &[u8],
    out: &mut [u8],
) -> c_longlong {
    let regs = load_regs(regs_bytes);
    let mut guest = vec![0u8; 0x8000];
    let n = guest_bytes.len().min(guest.len());
    guest[..n].copy_from_slice(&guest_bytes[..n]);
    let mut writes = PortWrites::new();

    match sysnum {
        PR_BIND => {
            let mut sockaddr = guest_at(&guest, regs[2], 128);
            map_port(
                &mut sockaddr,
                false,
                false,
                &[0; 6],
                regs[2],
                regs[5],
                &mut writes,
            );
            store_guest(&mut guest, regs[2], &sockaddr);
        }
        PR_CONNECT => {
            let mut sockaddr = guest_at(&guest, regs[2], 128);
            if sockaddr_is_localhost(&sockaddr) {
                map_port(
                    &mut sockaddr,
                    false,
                    false,
                    &[0; 6],
                    regs[2],
                    regs[5],
                    &mut writes,
                );
                store_guest(&mut guest, regs[2], &sockaddr);
            }
        }
        PR_SENDTO => {
            if regs[5] != 0 {
                let mut sockaddr = guest_at(&guest, regs[5], 128);
                if sockaddr_is_localhost(&sockaddr) {
                    map_port(
                        &mut sockaddr,
                        false,
                        true,
                        &[0; 6],
                        regs[2],
                        regs[5],
                        &mut writes,
                    );
                    store_guest(&mut guest, regs[5], &sockaddr);
                }
            }
        }
        PR_SOCKETCALL => {
            let raw = guest_at(&guest, regs[2], 48);
            let mut arg2 = [0u64; 6];
            for (index, value) in arg2.iter_mut().enumerate() {
                *value = u64_at(&raw, index * 8);
            }
            match call {
                SYS_BIND => {
                    let mut sockaddr = guest_at(&guest, arg2[1], 128);
                    map_port(
                        &mut sockaddr,
                        true,
                        false,
                        &arg2,
                        regs[2],
                        regs[5],
                        &mut writes,
                    );
                    store_guest(&mut guest, arg2[1], &sockaddr);
                }
                SYS_CONNECT => {
                    let mut sockaddr = guest_at(&guest, arg2[1], 128);
                    if sockaddr_is_localhost(&sockaddr) {
                        map_port(
                            &mut sockaddr,
                            true,
                            false,
                            &arg2,
                            regs[2],
                            regs[5],
                            &mut writes,
                        );
                        store_guest(&mut guest, arg2[1], &sockaddr);
                    }
                }
                SYS_SENDTO if arg2[4] != 0 => {
                    let mut sockaddr = guest_at(&guest, arg2[4], 128);
                    if sockaddr_is_localhost(&sockaddr) {
                        map_port(
                            &mut sockaddr,
                            true,
                            true,
                            &arg2,
                            regs[2],
                            regs[5],
                            &mut writes,
                        );
                        store_guest(&mut guest, arg2[4], &sockaddr);
                    }
                }
                _ => {}
            }
        }
        _ => {}
    }
    copy_out(out, 0, &guest[0x1000..0x1080]);
    copy_out(out, 128, &guest[0x2000..0x2080]);
    copy_out(out, 256, &guest[0x3000..0x3080]);
    put_i64(out, 384, writes.items.len() as i64);
    writes.dump(out, 392);
    0
}

/* ---- kompat.c ---- */

fn needs_kompat(actual: i32, virtual_release: i32, expected: i32) -> bool {
    expected > actual && expected <= virtual_release
}

/// `needs_kompat()` with explicit config values.
pub fn ra_kompat_needs(
    actual: c_longlong,
    virtual_release: c_longlong,
    expected: c_longlong,
) -> c_longlong {
    needs_kompat(actual as i32, virtual_release as i32, expected as i32) as i64
}

/// `parse_kernel_release()` with the `major.minor.revision` string.
pub fn ra_kompat_parse_release(text: &[u8]) -> c_longlong {
    parse_kernel_release(c_string(text)) as c_longlong
}

fn fixed_utsname() -> [[u8; UTS_FIELD_LEN]; 6] {
    let mut fields = [[0u8; UTS_FIELD_LEN]; 6];
    for (field, value) in fields.iter_mut().zip([
        &b"Linux"[..],
        &b"fakehost"[..],
        &b"3.10.0-fake"[..],
        &b"#1 SMP FAKE"[..],
        &b"x86_64"[..],
        &b"fakedomain"[..],
    ]) {
        let n = value.len().min(field.len());
        field[..n].copy_from_slice(&value[..n]);
    }
    fields
}

fn parse_complex_utsname(text: &[u8], fields: &mut [[u8; UTS_FIELD_LEN]; 6]) -> Option<u64> {
    let mut end = 0usize;
    for field in fields.iter_mut() {
        let start = end + 1;
        let relative = text.get(start..)?.iter().position(|&b| b == b'\\')?;
        end = start + relative;
        let length = (end - start).min(field.len() - 1);
        field.fill(0);
        field[..length].copy_from_slice(&text[start..start + length]);
    }
    let (hwcap, end2) = strtol16(&text[end + 1..]).ok()?;
    if text.get(end + 1 + end2) != Some(&b'\\') {
        return None;
    }
    Some(hwcap as u64)
}

/// `parse_utsname()`: both the simple release string and the backslash-delimited
/// complex format, with a fixed `uname()` answer from the harness.
pub fn ra_kompat_parse_utsname(text: &[u8], force: c_longlong, out: &mut [u8]) -> c_longlong {
    let text = c_string(text);
    let mut fields = fixed_utsname();
    let actual = if force != 0 {
        0
    } else {
        parse_kernel_release(&fields[2])
    };
    let hwcap: u64 = if text.first() == Some(&b'\\') {
        for field in fields.iter_mut() {
            field.fill(0); /* the original calloc()s its config */
        }
        let Some(value) = parse_complex_utsname(text, &mut fields) else {
            put_i64(out, 0, actual as i64);
            put_i64(out, 8, 0);
            put_u64(out, 16, 0);
            for (index, field) in fields.iter().enumerate() {
                copy_out(out, 24 + index * UTS_FIELD_LEN, field);
            }
            return -1;
        };
        value
    } else {
        let length = text.len().min(fields[2].len() - 1);
        fields[2][..length].copy_from_slice(&text[..length]);
        fields[2][length] = 0;
        u64::MAX
    };
    let virtual_release = parse_kernel_release(&fields[2]);
    put_i64(out, 0, actual as i64);
    put_i64(out, 8, virtual_release as i64);
    put_u64(out, 16, hwcap);
    for (index, field) in fields.iter().enumerate() {
        copy_out(out, 24 + index * UTS_FIELD_LEN, field);
    }
    0
}

fn modify_syscall(
    regs: &mut [u64; 16],
    actual: i32,
    virtual_release: i32,
    expected: i32,
    new_sysnum: i64,
    shifts: &[(usize, usize, i64)],
    det: u64,
) -> bool {
    if !needs_kompat(actual, virtual_release, expected) || det == SYSCALL_AVOIDER {
        return false;
    }
    regs[0] = new_sysnum as u64;
    for &(sysarg, nb_args, offset) in shifts {
        for j in 0..nb_args {
            let value = regs[sysarg + j];
            let target = (sysarg as i64 + j as i64 + offset) as usize;
            regs[target] = value;
        }
    }
    true
}

fn discard_fd_flags(
    regs: &mut [u64; 16],
    actual: i32,
    virtual_release: i32,
    flags: u64,
    sysarg: usize,
) {
    if needs_kompat(actual, virtual_release, V223) {
        regs[sysarg] &= !flags;
    }
}

/// `handle_sysenter_end()` on the recording host's aarch64 branch: apply the
/// *-at → older-syscall compatibility table and its argument shifts.
pub fn ra_kompat_sysenter(
    sysnum: c_longlong,
    actual: c_longlong,
    virtual_release: c_longlong,
    abi_cs: c_longlong,
    det_result: c_longlong,
    regs_bytes: &[u8],
    out: &mut [u8],
) -> c_longlong {
    let mut regs = load_regs(regs_bytes);
    regs[0] = sysnum as u64;
    let actual = actual as i32;
    let virtual_release = virtual_release as i32;
    let det = det_result as u64;
    let _ = abi_cs; /* aarch64 get_abi() reads is_aarch32, already set by the harness */
    let mut status = 0i64;
    match sysnum {
        12 => {
            modify_syscall(&mut regs, actual, virtual_release, V228, 11, &[], det);
        }
        51 => {
            if regs[1] == regs[2] {
                status = -22; /* EINVAL when oldfd == newfd */
            } else {
                modify_syscall(&mut regs, actual, virtual_release, V227, 50, &[], det);
            }
        }
        53 => {
            if modify_syscall(&mut regs, actual, virtual_release, V227, 52, &[], det) {
                regs[1] = 1; /* epoll_create size must be > 0 */
            }
        }
        56 => {
            modify_syscall(&mut regs, actual, virtual_release, V219, 57, &[], det);
        }
        60 => {
            if modify_syscall(&mut regs, actual, virtual_release, V227, 59, &[], det)
                && regs[2] & 1 != 0
            {
                status = -22; /* EFD_SEMAPHORE cannot be emulated */
            }
        }
        65 => {
            modify_syscall(
                &mut regs,
                actual,
                virtual_release,
                V216,
                13,
                &[(2, 2, -1)],
                det,
            );
        }
        74 => {
            modify_syscall(
                &mut regs,
                actual,
                virtual_release,
                V216,
                32,
                &[(2, 2, -1)],
                det,
            );
        }
        77 => {
            let new = if regs[5] & AT_SYMLINK_NOFOLLOW != 0 {
                161
            } else {
                33
            };
            modify_syscall(
                &mut regs,
                actual,
                virtual_release,
                V216,
                new,
                &[(2, 3, -1)],
                det,
            );
        }
        78 => {
            if needs_kompat(actual, virtual_release, V224) && regs[2] == F_DUPFD_CLOEXEC {
                regs[2] = F_DUPFD;
            }
        }
        213 | 90 => {
            let flags = regs[4];
            if flags & !(AT_SYMLINK_NOFOLLOW | AT_NO_AUTOMOUNT | AT_EMPTY_PATH | 0x6000) != 0 {
                status = -22;
            } else {
                let new = if flags & AT_SYMLINK_NOFOLLOW != 0 {
                    175
                } else {
                    362
                };
                modify_syscall(
                    &mut regs,
                    actual,
                    virtual_release,
                    V216,
                    new,
                    &[(2, 2, -1)],
                    det,
                );
            }
        }
        97 => {
            if needs_kompat(actual, virtual_release, V222) && actual != 0 {
                let operation = regs[2];
                if operation & FUTEX_PRIVATE_FLAG != 0 {
                    regs[2] = operation & !FUTEX_PRIVATE_FLAG;
                }
            }
        }
        98 => {
            modify_syscall(
                &mut regs,
                actual,
                virtual_release,
                V216,
                409,
                &[(2, 2, -1)],
                det,
            );
        }
        143 => {
            modify_syscall(&mut regs, actual, virtual_release, V227, 142, &[], det);
        }
        165 => {
            if regs[5] & !AT_SYMLINK_FOLLOW != 0 {
                status = -22;
            } else {
                modify_syscall(
                    &mut regs,
                    actual,
                    virtual_release,
                    V216,
                    164,
                    &[(2, 1, -1), (4, 1, -2)],
                    det,
                );
            }
        }
        183 => {
            modify_syscall(
                &mut regs,
                actual,
                virtual_release,
                V216,
                182,
                &[(2, 2, -1)],
                det,
            );
        }
        185 => {
            modify_syscall(
                &mut regs,
                actual,
                virtual_release,
                V216,
                184,
                &[(2, 3, -1)],
                det,
            );
        }
        223 => {
            let modified = modify_syscall(
                &mut regs,
                actual,
                virtual_release,
                V216,
                221,
                &[(2, 3, -1)],
                det,
            );
            let sysarg = if modified { 2 } else { 3 };
            discard_fd_flags(&mut regs, actual, virtual_release, O_CLOEXEC, sysarg);
        }
        221 => discard_fd_flags(&mut regs, actual, virtual_release, O_CLOEXEC, 2),
        232 => {
            modify_syscall(&mut regs, actual, virtual_release, V227, 231, &[], det);
        }
        248 => {
            modify_syscall(&mut regs, actual, virtual_release, V216, 9, &[], det);
        }
        260 => {
            modify_syscall(
                &mut regs,
                actual,
                virtual_release,
                V216,
                259,
                &[(2, 3, -1)],
                det,
            );
        }
        270 => {
            modify_syscall(
                &mut regs,
                actual,
                virtual_release,
                V216,
                269,
                &[(2, 1, -1), (4, 1, -2)],
                det,
            );
        }
        351 => {
            if modify_syscall(&mut regs, actual, virtual_release, V227, 350, &[], det) {
                regs[4] = 0; /* signalfd flags must be zero */
            }
        }
        356 | 358 | 387 => {
            discard_fd_flags(
                &mut regs,
                actual,
                virtual_release,
                O_CLOEXEC | O_NONBLOCK,
                2,
            );
        }
        371 => {
            modify_syscall(
                &mut regs,
                actual,
                virtual_release,
                V216,
                370,
                &[(3, 1, -1)],
                det,
            );
        }
        402 => {
            let new = if regs[3] & AT_REMOVEDIR != 0 {
                274
            } else {
                401
            };
            modify_syscall(
                &mut regs,
                actual,
                virtual_release,
                V216,
                new,
                &[(2, 1, -1)],
                det,
            );
        }
        _ => {}
    }
    put_i64(out, 0, status);
    put_i64(out, 8, regs[0] as i64);
    dump_regs(out, 16, &regs);
    status
}

fn emulate_fd_flags(
    regs: &[u64; 16],
    sysarg: usize,
    emulated_flags: u64,
    fd: u64,
    chained: &mut Vec<u64>,
) {
    let flags = regs[sysarg];
    if flags == 0 {
        return;
    }
    if emulated_flags & flags & O_CLOEXEC != 0 {
        chained.extend_from_slice(&[78, fd, F_SETFD, FD_CLOEXEC, 0, 0, 0]);
    }
    if emulated_flags & flags & O_NONBLOCK != 0 {
        chained.extend_from_slice(&[78, fd, F_SETFL, O_NONBLOCK, 0, 0, 0]);
    }
}

fn read_guest_i32(guest: &[u8], address: u64) -> i32 {
    let bytes = guest_at(guest, address, 4);
    i32::from_le_bytes(bytes.try_into().unwrap_or([0; 4]))
}

/// `handle_sysexit_end()`: the result/fd-flag emulation decisions.  Chained
/// `fcntl` calls are recorded as flat `[sysnum, a1..a6]` lists.
pub fn ra_kompat_sysexit(
    sysnum: c_longlong,
    modified_sysnum: c_longlong,
    actual: c_longlong,
    virtual_release: c_longlong,
    regs_bytes: &[u8],
    guest_bytes: &[u8],
    out: &mut [u8],
) -> c_longlong {
    let mut regs = load_regs(regs_bytes);
    regs[0] = sysnum as u64; /* SYSARG_NUM in the harness setup */
    let mut guest = vec![0u8; 0x8000];
    let n = guest_bytes.len().min(guest.len());
    guest[..n].copy_from_slice(&guest_bytes[..n]);
    let mut chained: Vec<u64> = Vec::new();
    let mut writes: Vec<(u64, u64)> = Vec::new();
    let result = regs[7] as i64;
    let actual = actual as i32;
    let virtual_release = virtual_release as i32;
    if result >= 0 {
        match sysnum {
            400 => {
                let address = regs[1];
                let mut utsname = Vec::new();
                for field in fixed_utsname() {
                    utsname.extend_from_slice(&field);
                }
                writes.push((address, utsname.len() as u64));
            }
            312 | 321 => {
                let length = regs[2];
                if length > 64 {
                    put_i64(out, 0, -22);
                    put_i64(out, 8, 0);
                    dump_regs(out, 16, &regs);
                    put_i64(out, 136, 0);
                    put_i64(out, 192, 0);
                    return -22;
                }
            }
            12 => {
                if modified_sysnum == 11 {
                    emulate_fd_flags(
                        &regs,
                        4,
                        O_CLOEXEC | O_NONBLOCK,
                        result as u64,
                        &mut chained,
                    );
                }
            }
            51 => {
                if modified_sysnum == 50 {
                    emulate_fd_flags(&regs, 3, O_CLOEXEC | O_NONBLOCK, regs[2], &mut chained);
                }
            }
            53 => {
                if modified_sysnum == 52 {
                    emulate_fd_flags(
                        &regs,
                        1,
                        O_CLOEXEC | O_NONBLOCK,
                        result as u64,
                        &mut chained,
                    );
                }
            }
            60 => {
                if modified_sysnum == 59 {
                    emulate_fd_flags(
                        &regs,
                        2,
                        O_CLOEXEC | O_NONBLOCK,
                        result as u64,
                        &mut chained,
                    );
                }
            }
            78 => {
                if needs_kompat(actual, virtual_release, V224) && regs[2] == F_DUPFD_CLOEXEC {
                    chained.extend_from_slice(&[78, result as u64, F_SETFD, FD_CLOEXEC, 0, 0, 0]);
                }
            }
            143 => {
                if modified_sysnum == 142 {
                    emulate_fd_flags(
                        &regs,
                        1,
                        O_CLOEXEC | O_NONBLOCK,
                        result as u64,
                        &mut chained,
                    );
                }
            }
            221 => {
                if needs_kompat(actual, virtual_release, V223) {
                    emulate_fd_flags(&regs, 2, O_CLOEXEC, result as u64, &mut chained);
                }
            }
            223 => {
                if needs_kompat(actual, virtual_release, V223) {
                    emulate_fd_flags(&regs, 3, O_CLOEXEC, result as u64, &mut chained);
                }
            }
            232 => {
                if modified_sysnum == 231 {
                    let fds = [
                        read_guest_i32(&guest, regs[1]),
                        read_guest_i32(&guest, regs[1].wrapping_add(4)),
                    ];
                    for fd in fds {
                        emulate_fd_flags(&regs, 2, O_CLOEXEC | O_NONBLOCK, fd as u64, &mut chained);
                    }
                }
            }
            351 => {
                if modified_sysnum == 350 {
                    emulate_fd_flags(
                        &regs,
                        4,
                        O_CLOEXEC | O_NONBLOCK,
                        result as u64,
                        &mut chained,
                    );
                }
            }
            356 | 387 => {
                if needs_kompat(actual, virtual_release, V227) {
                    emulate_fd_flags(
                        &regs,
                        2,
                        O_CLOEXEC | O_NONBLOCK,
                        result as u64,
                        &mut chained,
                    );
                }
            }
            358 if needs_kompat(actual, virtual_release, V227) => {
                let fds = [
                    read_guest_i32(&guest, regs[4]),
                    read_guest_i32(&guest, regs[4].wrapping_add(4)),
                ];
                for fd in fds {
                    emulate_fd_flags(&regs, 2, O_CLOEXEC | O_NONBLOCK, fd as u64, &mut chained);
                }
            }
            _ => {}
        }
    }
    put_i64(out, 0, 0);
    dump_regs(out, 8, &regs);
    put_i64(out, 136, chained.len() as i64);
    for (index, value) in chained.iter().take(7).enumerate() {
        put_u64(out, 144 + index * 8, *value);
    }
    put_i64(out, 192, writes.len() as i64);
    for (index, (dest, size)) in writes.iter().take(4).enumerate() {
        put_u64(out, 200 + index * 16, *dest);
        put_u64(out, 208 + index * 16, *size);
    }
    0
}

#[cfg(test)]
mod tests {
    use super::*;

    const V229: i32 = kver(2, 6, 29);

    fn words(values: &[i64]) -> Vec<u8> {
        let mut bytes = Vec::new();
        for value in values {
            bytes.extend_from_slice(&value.to_le_bytes());
        }
        bytes
    }

    #[test]
    fn extension_dispatch_uses_the_first_nonzero_status() {
        let mut out = [0u8; 8];
        assert_eq!(ra_extension_notify(&[], 0, &mut out), 0);
        assert_eq!(i64_at(&out, 0), -1);
        let statuses = words(&[0, -11, 7]);
        assert_eq!(ra_extension_notify(&statuses, 0, &mut out), 7);
        assert_eq!(i64_at(&out, 0), 2);
        assert_eq!(ra_extension_initialize(-1, &mut out), -1);
        assert_eq!(i64_at(&out, 0), 0);
        assert_eq!(ra_extension_initialize(0, &mut out), 0);
        assert_eq!(i64_at(&out, 0), 1);
    }

    #[test]
    fn ashmem_rewrites_memfd_and_size_ioctl() {
        let mut out = [0u8; 64];
        assert_eq!(ra_ashmem_create(1, 0, 0, &mut out), 0);
        assert_eq!(i64_at(&out, 8), 0);
        assert_eq!(ra_ashmem_create(0, 1, 0, &mut out), 0);
        assert_eq!(i64_at(&out, 8), PR_OPENAT);
        assert_eq!(i64_at(&out, 32), (O_RDWR | O_CLOEXEC) as i64);
        assert_eq!(&out[56..64], b"/dev/ash");

        let mut regs = [0i64; 16];
        regs[1] = 7;
        regs[2] = 0x2000;
        let mut out = [0u8; 160];
        ra_ashmem_syscall(PR_FTRUNCATE, 1, 0, &words(&regs), &mut out);
        assert_eq!(i64_at(&out, 0), PR_IOCTL);
        assert_eq!(i64_at(&out, 24), ashmem_set_size() as i64);
        assert_eq!(i64_at(&out, 32), 0x2000);
        assert_eq!(i64_at(&out, 136), CS_STAT_ENTERED);
    }

    #[test]
    fn mountinfo_lines_and_pid_validation() {
        let mut out = [0u8; 256];
        let pairs = b"/\0/\0/tmp\0/host/tmp\0";
        let n = ra_mountinfo_bindings(pairs, &mut out);
        assert_eq!(
            &out[..n as usize],
            b"1000000 1 0:1 / /tmp rw,relatime - bind /host/tmp rw,relatime\n"
        );
        let real = b"36 25 0:29 /data / rw\n37 25 0:30 /data /data rw\n";
        let mut out = [0u8; 512];
        ra_mountinfo_path(b"/proc/7/mountinfo", real, 2, 1, &mut out);
        assert_eq!(i64_at(&out, 0), 1);
        assert_eq!(i64_at(&out, 8), 1);
        assert!(out[24..].starts_with(b"37 25 0:30 /data / rw\n"));
        ra_mountinfo_path(b"/proc/abc/mountinfo", real, 2, 1, &mut out);
        assert_eq!(i64_at(&out, 8), 0);
    }

    #[test]
    fn port_mapping_and_localhost() {
        let mut out = [0u8; 88];
        assert_eq!(ra_port_mod(2, 80, 0, 0, 0, &[], &mut out), 2080);
        assert_eq!(i64_at(&out, 16), 1);
        assert_eq!(i64_at(&out, 24), SYSARG_2_ADDR as i64);
        assert_eq!(i64_at(&out, 32), 8);
        assert_eq!(ra_port_mod(2, 1024, 0, 0, 0, &[], &mut out), 1024);
        assert_eq!(i64_at(&out, 16), 0);
        assert_eq!(ra_port_localhost(2, &[127, 0, 0, 1]), 1);
        assert_eq!(ra_port_localhost(2, &[8, 8, 8, 8]), 0);
        assert_eq!(
            ra_port_localhost(10, &[0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1]),
            1
        );
    }

    #[test]
    fn kompat_release_and_compat_table() {
        assert_eq!(ra_kompat_parse_release(b"5.10.2"), kver(5, 10, 2) as i64);
        assert_eq!(ra_kompat_needs(V216.into(), V229.into(), V227.into()), 1);
        assert_eq!(ra_kompat_needs(V227.into(), V229.into(), V227.into()), 0);
        let mut out = [0u8; 144];
        let mut regs = [0i64; 16];
        regs[1] = 7;
        regs[2] = 9;
        assert_eq!(
            ra_kompat_sysenter(51, V216.into(), V229.into(), 0, 0, &words(&regs), &mut out),
            0
        );
        assert_eq!(i64_at(&out, 8), 50);
        assert_eq!(i64_at(&out, 16), 50);
    }
}
