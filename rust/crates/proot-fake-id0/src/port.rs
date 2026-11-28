//! Pure Rust port of PRoot's `extension/fake_id0/` extension.
//!
//! The original callbacks run under ptrace and operate on a live `Tracee`;
//! their tracee-facing halves (register reads/writes, tracee memory accesses,
//! guestfs checks, chained syscalls, talloc wiring) stay in C.  This crate
//! ports the decisions those callbacks make: uid/gid emulation and the
//! capability model in `fake_id0.c`, the meta-file permission helpers in
//! `helper_functions.c`, and the pure parts of `access.c`, `open.c`,
//! `socket.c`, `stat.c` and `sendmsg.c`.
//!
//! A recording-only harness in the reference root called each of these with
//! plain arguments; the C ABI in `lib.rs` keeps the harness' signatures, so
//! `rust-anything diff` compares against the original code's behavior,
//! including its quirks (documented at each function below).

#![forbid(unsafe_code)]

use std::ffi::{OsStr, c_int, c_uint};
use std::fs::{File, metadata};
use std::io::{Read, Write};
use std::os::unix::ffi::OsStrExt;
use std::path::Path;

/// `PATH_MAX` from `<linux/limits.h>`.
const PATH_MAX: usize = 4096;
/// `META_TAG` from `helper_functions.c:15`.
const META_TAG: &[u8] = b".proot-meta-file.";

const EPERM: c_int = 1;
const EACCES: c_int = 13;
const ENAMETOOLONG: c_int = 36;
const EPROTONOSUPPORT: c_int = 93;

const PR_SET_KEEPCAPS: c_int = 8;
const STATX_UID: c_uint = 0x0000_0008;
const STATX_GID: c_uint = 0x0000_0010;

const F_OK: c_int = 0;
const R_OK: c_int = 4;
const W_OK: c_int = 2;
const X_OK: c_int = 1;

const O_RDONLY: c_int = 0;
const O_WRONLY: c_int = 1;
const O_RDWR: c_int = 2;
const O_ACCMODE: c_int = 3;

const S_IFMT: c_uint = 0o170_000;
const S_ISUID: c_uint = 0o4_000;
const S_ISGID: c_uint = 0o2_000;

const SOL_SOCKET: c_int = 1;
const SCM_CREDENTIALS: c_int = 2;
const MAX_CONTROLLEN: usize = 1024;

/// Mirror of the C `Config` from `extension/fake_id0/config.h`.  Every field
/// is stored in the form the harness passed it: the id/mask fields as `u32`,
/// the two flags as `bool`.
#[derive(Clone, Copy, Debug)]
struct Config {
    ruid: u32,
    euid: u32,
    suid: u32,
    fsuid: u32,
    rgid: u32,
    egid: u32,
    sgid: u32,
    fsgid: u32,
    umask: u32,
    caps_active: bool,
    keep_caps: bool,
}

impl Config {
    #[expect(
        clippy::too_many_arguments,
        reason = "one argument per C Config field, as in the harness wrapper"
    )]
    fn new(
        ruid: c_int,
        euid: c_int,
        suid: c_int,
        fsuid: c_int,
        rgid: c_int,
        egid: c_int,
        sgid: c_int,
        fsgid: c_int,
        umask: c_int,
        caps_active: c_int,
        keep_caps: c_int,
    ) -> Self {
        Self {
            ruid: ruid as u32,
            euid: euid as u32,
            suid: suid as u32,
            fsuid: fsuid as u32,
            rgid: rgid as u32,
            egid: egid as u32,
            sgid: sgid as u32,
            fsgid: fsgid as u32,
            umask: umask as u32,
            caps_active: caps_active != 0,
            keep_caps: keep_caps != 0,
        }
    }

    /// The harness encoded the resulting config, the macro's status and the
    /// value `poke_reg(SYSARG_RESULT, ...)` wrote as 13 little-endian u32s.
    fn pack(&self, status: c_int, result: u32, out: &mut [u8]) {
        let values = [
            self.ruid,
            self.euid,
            self.suid,
            self.fsuid,
            self.rgid,
            self.egid,
            self.sgid,
            self.fsgid,
            self.umask,
            u32::from(self.caps_active),
            u32::from(self.keep_caps),
            status as u32,
            result,
        ];
        for (i, value) in values.iter().enumerate() {
            if let Some(slot) = out.get_mut(i * 4..i * 4 + 4) {
                slot.copy_from_slice(&value.to_le_bytes());
            }
        }
    }

    /// `MAYBE_DROP_CAPS(prev_root)` from `fake_id0.c:92-97`.
    fn maybe_drop_caps(&mut self, prev_root: bool) {
        if prev_root && !self.keep_caps && self.ruid != 0 && self.euid != 0 && self.suid != 0 {
            self.caps_active = false;
        }
    }

    fn prev_root(&self) -> bool {
        self.ruid == 0 || self.euid == 0 || self.suid == 0
    }

    /// `UNSET_ID(id)` / `EQUALS_ANY_ID(var, type)` for the selected id family.
    fn unset(id: u32) -> bool {
        id == u32::MAX
    }

    fn equals_any(&self, id: u32, uid_family: bool) -> bool {
        if uid_family {
            id == self.ruid || id == self.euid || id == self.suid
        } else {
            id == self.rgid || id == self.egid || id == self.sgid
        }
    }
}

fn put_u32(out: &mut [u8], at: usize, value: u32) {
    if let Some(slot) = out.get_mut(at..at + 4) {
        slot.copy_from_slice(&value.to_le_bytes());
    }
}

fn copy_out(src: &[u8], out: &mut [u8]) {
    let n = src.len().min(out.len());
    out[..n].copy_from_slice(&src[..n]);
}

/// The recording harness truncated input strings to `PATH_MAX - 1` before
/// handing them to the original's unbounded `strcpy`.
fn harness_path(path: Option<&[u8]>) -> Vec<u8> {
    path.unwrap_or(&[])
        .iter()
        .copied()
        .take(PATH_MAX - 1)
        .collect()
}

fn as_path(bytes: &[u8]) -> &Path {
    Path::new(OsStr::from_bytes(bytes))
}

/// `dtoo()` in `helper_functions.c:24-35`.  Uses wrapping arithmetic: the C
/// original relies on gcc's two's-complement overflow for `i *= 10`.
fn dtoo(n: c_int) -> c_int {
    let mut n = n;
    let mut i = 1i32;
    let mut octal = 0i32;
    while n != 0 {
        let rem = n % 8;
        n /= 8;
        octal = octal.wrapping_add(rem.wrapping_mul(i));
        i = i.wrapping_mul(10);
    }
    octal
}

/// `otod()` in `helper_functions.c:40-55`.
fn otod(n: c_int) -> c_int {
    let mut n = n;
    let mut decimal = 0i32;
    let mut i = 0i32;
    while n != 0 {
        let mut pow = 1i32;
        let mut j = 0;
        while j < i {
            pow = pow.wrapping_mul(8);
            j += 1;
        }
        let rem = n % 10;
        n /= 10;
        decimal = decimal.wrapping_add(rem.wrapping_mul(pow));
        i += 1;
    }
    decimal
}

/// `get_name()` in `helper_functions.c:151-162`: offset of the final path
/// component, or 0 when the path has no `/`.
fn name_offset(path: &[u8]) -> usize {
    path.iter()
        .rposition(|&byte| byte == b'/')
        .map_or(0, |at| at + 1)
}

/// `get_dir_path()` in `helper_functions.c:259-278`: path without its final
/// component.  Returns the bytes the C code would leave in `dir_path`.
fn dir_path(path: &[u8]) -> Vec<u8> {
    let mut dir = path.to_vec();
    let offset = dir.len() as isize - 1; // C: strlen(dir_path) - 1
    if offset > 0 {
        let mut at = offset as usize;
        while at > 1 && dir[at] == b'/' {
            at -= 1;
        }
        while at > 1 && dir[at] != b'/' {
            at -= 1;
        }
        dir.truncate(at);
    }
    dir
}

/// `get_meta_path()` in `helper_functions.c:283-302`.
fn meta_path(path: &[u8]) -> Result<Vec<u8>, c_int> {
    let mut meta = dir_path(path);
    let filename = &path[name_offset(path)..];
    if meta.as_slice() != b"/" {
        meta.push(b'/');
    }
    if meta.len() + filename.len() + META_TAG.len() >= PATH_MAX {
        return Err(-ENAMETOOLONG);
    }
    meta.extend_from_slice(META_TAG);
    meta.extend_from_slice(filename);
    Ok(meta)
}

/// `read_meta_file()` in `helper_functions.c:309-327`.  A missing file falls
/// back to mode `otod(755)` and the config's euid/egid, as in the original.
fn read_meta(path: &[u8], config: &Config) -> Result<(u32, u32, u32), c_int> {
    let Ok(mut file) = File::open(as_path(path)) else {
        return Ok((otod(755) as u32, config.euid, config.egid));
    };
    let mut content = String::new();
    if file.read_to_string(&mut content).is_err() {
        return Ok((otod(755) as u32, config.euid, config.egid));
    }
    let mut fields = content.split_whitespace();
    let parse = |s: Option<&str>| s.and_then(|v| v.parse::<i32>().ok()).unwrap_or(0);
    let mode = parse(fields.next());
    let owner = parse(fields.next());
    let group = parse(fields.next());
    Ok((otod(mode) as u32, owner as u32, group as u32))
}

/// `get_permissions()` in `helper_functions.c:167-210`.
///
/// Faithful quirk: the C `else` has no braces, so `emulated_gid` is always
/// `egid`, even when `uses_real` is set (which selects `ruid` only).
fn permissions(meta: &[u8], config: &Config, uses_real: bool) -> c_int {
    let Ok((mode, owner, group)) = read_meta(meta, config) else {
        return -EACCES;
    };
    let emulated_uid = if uses_real { config.ruid } else { config.euid };
    let emulated_gid = config.egid;

    let mut omode = dtoo(mode as i32);
    if emulated_uid == owner || emulated_uid == 0 {
        omode = omode.wrapping_div(10).wrapping_div(10).wrapping_rem(10);
    } else if emulated_gid == group {
        omode = omode.wrapping_div(10).wrapping_rem(10);
    } else {
        omode %= 10;
    }
    if emulated_uid == 0 {
        omode |= 6;
    }
    omode
}

/// Port of `ra_fid0_dtoo()`.
pub fn ra_fid0_dtoo(n: c_int) -> c_int {
    dtoo(n)
}

/// Port of `ra_fid0_otod()`.
pub fn ra_fid0_otod(n: c_int) -> c_int {
    otod(n)
}

/// Port of `ra_fid0_get_name()`: the offset the original returned.
pub fn ra_fid0_get_name(path: Option<&[u8]>) -> c_int {
    name_offset(&harness_path(path)) as c_int
}

/// Port of `ra_fid0_get_dir_path()`.
pub fn ra_fid0_get_dir_path(path: Option<&[u8]>, out: &mut [u8]) -> c_int {
    copy_out(&dir_path(&harness_path(path)), out);
    0
}

/// Port of `ra_fid0_get_meta_path()`.
pub fn ra_fid0_get_meta_path(path: Option<&[u8]>, out: &mut [u8]) -> c_int {
    match meta_path(&harness_path(path)) {
        Ok(meta) => {
            copy_out(&meta, out);
            0
        }
        Err(status) => status,
    }
}

/// Port of `ra_fid0_path_exists()`: `access(path, F_OK)` as 0 or -1.
pub fn ra_fid0_path_exists(path: Option<&[u8]>) -> c_int {
    let path = harness_path(path);
    if metadata(as_path(&path)).is_ok() {
        0
    } else {
        -1
    }
}

/// Port of `ra_fid0_get_permissions()`.
#[expect(
    clippy::too_many_arguments,
    reason = "the C ABI and the harness wrapper have the same plain arguments"
)]
pub fn ra_fid0_get_permissions(
    meta_path: Option<&[u8]>,
    ruid: c_int,
    euid: c_int,
    suid: c_int,
    fsuid: c_int,
    rgid: c_int,
    egid: c_int,
    sgid: c_int,
    fsgid: c_int,
    umask: c_int,
    caps_active: c_int,
    keep_caps: c_int,
    uses_real: c_int,
) -> c_int {
    let config = Config::new(
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
    );
    permissions(&harness_path(meta_path), &config, uses_real != 0)
}

/// Port of `ra_fid0_read_meta_file()`: mode, owner and group as three u32s.
#[expect(
    clippy::too_many_arguments,
    reason = "the C ABI and the harness wrapper have the same plain arguments"
)]
pub fn ra_fid0_read_meta_file(
    meta_path: Option<&[u8]>,
    ruid: c_int,
    euid: c_int,
    suid: c_int,
    fsuid: c_int,
    rgid: c_int,
    egid: c_int,
    sgid: c_int,
    fsgid: c_int,
    umask: c_int,
    caps_active: c_int,
    keep_caps: c_int,
    out: &mut [u8],
) -> c_int {
    let config = Config::new(
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
    );
    let Ok((mode, owner, group)) = read_meta(&harness_path(meta_path), &config) else {
        return -1;
    };
    put_u32(out, 0, mode);
    put_u32(out, 4, owner);
    put_u32(out, 8, group);
    0
}

/// Port of `ra_fid0_write_meta_file()` (`helper_functions.c:334-353`).
///
/// The harness read the file back into `out` so recording could observe the
/// bytes; that readback is mirrored here.  `%d` prints the u32 id fields as
/// signed ints, so values above `INT_MAX` appear negative.
#[expect(
    clippy::too_many_arguments,
    reason = "the C ABI and the harness wrapper have the same plain arguments"
)]
pub fn ra_fid0_write_meta_file(
    meta_path: Option<&[u8]>,
    mode: c_uint,
    owner: c_uint,
    group: c_uint,
    is_creat: c_int,
    ruid: c_int,
    euid: c_int,
    suid: c_int,
    fsuid: c_int,
    rgid: c_int,
    egid: c_int,
    sgid: c_int,
    fsgid: c_int,
    umask: c_int,
    caps_active: c_int,
    keep_caps: c_int,
    out: &mut [u8],
) -> c_int {
    let config = Config::new(
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
    );
    let path = harness_path(meta_path);
    let mut effective = mode;
    if is_creat != 0 {
        effective &= !config.umask & 0o777;
    }
    let content = format!(
        "{}\n{}\n{}\n",
        dtoo(effective as i32),
        owner as i32,
        group as i32
    );
    let Ok(mut file) = File::create(as_path(&path)) else {
        return -1;
    };
    if file.write_all(content.as_bytes()).is_err() {
        return -1;
    }
    drop(file);

    if let Ok(mut file) = File::open(as_path(&path)) {
        let mut readback = Vec::new();
        if file.read_to_end(&mut readback).is_ok() {
            let n = readback.len().min(out.len());
            out[..n].copy_from_slice(&readback[..n]);
            if n < out.len() {
                out[n] = 0;
            }
        }
    }
    0
}

/// Port of `ra_fid0_check_dir_perms()` (`helper_functions.c:218-255`), with
/// the harness' guestfs predicate (always true).
#[expect(
    clippy::too_many_arguments,
    reason = "the C ABI and the harness wrapper have the same plain arguments"
)]
pub fn ra_fid0_check_dir_perms(
    r#type: c_int,
    path: Option<&[u8]>,
    rel_path: Option<&[u8]>,
    ruid: c_int,
    euid: c_int,
    suid: c_int,
    fsuid: c_int,
    rgid: c_int,
    egid: c_int,
    sgid: c_int,
    fsgid: c_int,
    umask: c_int,
    caps_active: c_int,
    keep_caps: c_int,
) -> c_int {
    let config = Config::new(
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
    );
    let rel = harness_path(rel_path);
    let mut shorten = dir_path(&harness_path(path));

    let meta = match meta_path(&shorten) {
        Ok(meta) => meta,
        Err(status) => return status,
    };
    let perms = permissions(&meta, &config, false);
    if r#type as u8 == b'w' && perms & 2 != 2 {
        return -EACCES;
    }
    if r#type as u8 == b'r' && perms & 1 != 1 {
        return -EACCES;
    }

    while shorten.as_slice() != rel.as_slice() && rel.len() < shorten.len() {
        let parent = dir_path(&shorten);
        // shortcut: the C loop never terminates once get_dir_path stops
        // shrinking the path; the harness times those cases out, so they are
        // not recorded.  Stop here instead of hanging in Rust.
        if parent == shorten {
            break;
        }
        shorten = parent;
        let meta = match meta_path(&shorten) {
            Ok(meta) => meta,
            Err(status) => return status,
        };
        if permissions(&meta, &config, false) & 1 != 1 {
            return -EACCES;
        }
    }
    0
}

/// Port of `SETXID(uid|gid, ORIGINAL)` (`fake_id0.c:102-137`).
#[expect(
    clippy::too_many_arguments,
    reason = "the C ABI and the harness wrapper have the same plain arguments"
)]
pub fn ra_fid0_setxid(
    kind: c_int,
    ruid: c_int,
    euid: c_int,
    suid: c_int,
    fsuid: c_int,
    rgid: c_int,
    egid: c_int,
    sgid: c_int,
    fsgid: c_int,
    umask: c_int,
    caps_active: c_int,
    keep_caps: c_int,
    new_id: c_uint,
    out: &mut [u8],
) -> c_int {
    let mut config = Config::new(
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
    );
    let uid_family = kind == 0;
    let id = new_id;
    let prev_root = config.prev_root();
    let allowed = config.euid == 0
        || config.caps_active
        || (uid_family && (id == config.ruid || id == config.euid || id == config.suid))
        || (!uid_family && (id == config.rgid || id == config.egid || id == config.sgid));
    if !allowed {
        config.pack(-EPERM, 0, out);
        return -EPERM;
    }
    if config.euid == 0 || config.caps_active {
        if uid_family {
            config.ruid = id;
            config.suid = id;
        } else {
            config.rgid = id;
            config.sgid = id;
        }
    }
    if uid_family {
        config.euid = id;
        config.fsuid = id;
    } else {
        config.egid = id;
        config.fsgid = id;
    }
    config.maybe_drop_caps(prev_root);
    config.pack(0, 0, out);
    0
}

/// `UNCHANGED_ID(id)` from `fake_id0.c:147`.
fn unchanged(id: u32, current: u32) -> bool {
    Config::unset(id) || id == current
}

/// Port of `SETREXID(uid|gid, ORIGINAL)` (`fake_id0.c:152-211`).
#[expect(
    clippy::too_many_arguments,
    reason = "the C ABI and the harness wrapper have the same plain arguments"
)]
pub fn ra_fid0_setrexid(
    kind: c_int,
    ruid: c_int,
    euid: c_int,
    suid: c_int,
    fsuid: c_int,
    rgid: c_int,
    egid: c_int,
    sgid: c_int,
    fsgid: c_int,
    umask: c_int,
    caps_active: c_int,
    keep_caps: c_int,
    new_r: c_uint,
    new_e: c_uint,
    out: &mut [u8],
) -> c_int {
    let mut config = Config::new(
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
    );
    let uid_family = kind == 0;
    let (cur_r, cur_e, cur_s) = if uid_family {
        (config.ruid, config.euid, config.suid)
    } else {
        (config.rgid, config.egid, config.sgid)
    };
    let (r, e) = (new_r, new_e);
    let prev_root = config.prev_root();
    let allowed = config.euid == 0
        || config.caps_active
        || (unchanged(e, cur_e) && unchanged(r, cur_r))
        || (r == cur_e && (e == cur_r || unchanged(e, cur_e)))
        || (e == cur_r && (r == cur_e || unchanged(r, cur_r)))
        || (e == cur_s && unchanged(r, cur_r));
    if !allowed {
        config.pack(-EPERM, 0, out);
        return -EPERM;
    }
    if !Config::unset(e) {
        if e != cur_r {
            if uid_family {
                config.suid = e;
            } else {
                config.sgid = e;
            }
        }
        if uid_family {
            config.euid = e;
            config.fsuid = e;
        } else {
            config.egid = e;
            config.fsgid = e;
        }
    }
    if !Config::unset(r) {
        if !Config::unset(e) {
            if uid_family {
                config.suid = e;
            } else {
                config.sgid = e;
            }
        }
        if uid_family {
            config.ruid = r;
        } else {
            config.rgid = r;
        }
    }
    config.maybe_drop_caps(prev_root);
    config.pack(0, 0, out);
    0
}

/// Port of `SETRESXID(u|g, ORIGINAL)` (`fake_id0.c:223-266`).
#[expect(
    clippy::too_many_arguments,
    reason = "the C ABI and the harness wrapper have the same plain arguments"
)]
pub fn ra_fid0_setresxid(
    kind: c_int,
    ruid: c_int,
    euid: c_int,
    suid: c_int,
    fsuid: c_int,
    rgid: c_int,
    egid: c_int,
    sgid: c_int,
    fsgid: c_int,
    umask: c_int,
    caps_active: c_int,
    keep_caps: c_int,
    new_r: c_uint,
    new_e: c_uint,
    new_s: c_uint,
    out: &mut [u8],
) -> c_int {
    let mut config = Config::new(
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
    );
    let uid_family = kind == 0;
    let (r, e, s) = (new_r, new_e, new_s);
    let prev_root = config.prev_root();
    let allowed = config.euid == 0
        || config.caps_active
        || ((Config::unset(r) || config.equals_any(r, uid_family))
            && (Config::unset(e) || config.equals_any(e, uid_family))
            && (Config::unset(s) || config.equals_any(s, uid_family)));
    if !allowed {
        config.pack(-EPERM, 0, out);
        return -EPERM;
    }
    if !Config::unset(r) {
        if uid_family {
            config.ruid = r;
        } else {
            config.rgid = r;
        }
    }
    if !Config::unset(e) {
        if uid_family {
            config.euid = e;
            config.fsuid = e;
        } else {
            config.egid = e;
            config.fsgid = e;
        }
    }
    if !Config::unset(s) {
        if uid_family {
            config.suid = s;
        } else {
            config.sgid = s;
        }
    }
    config.maybe_drop_caps(prev_root);
    config.pack(0, 0, out);
    0
}

/// Port of `SETFSXID(u|g)` (`fake_id0.c:271-291`).  The old fsuid/fsgid is
/// the value the original wrote to `SYSARG_RESULT`.
#[expect(
    clippy::too_many_arguments,
    reason = "the C ABI and the harness wrapper have the same plain arguments"
)]
pub fn ra_fid0_setfsxid(
    kind: c_int,
    ruid: c_int,
    euid: c_int,
    suid: c_int,
    fsuid: c_int,
    rgid: c_int,
    egid: c_int,
    sgid: c_int,
    fsgid: c_int,
    umask: c_int,
    caps_active: c_int,
    keep_caps: c_int,
    new_fs: c_uint,
    out: &mut [u8],
) -> c_int {
    let mut config = Config::new(
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
    );
    let uid_family = kind == 0;
    let old_fs = if uid_family {
        config.fsuid
    } else {
        config.fsgid
    };
    let allowed = config.euid == 0
        || config.caps_active
        || new_fs == old_fs
        || config.equals_any(new_fs, uid_family);
    if allowed {
        if uid_family {
            config.fsuid = new_fs;
        } else {
            config.fsgid = new_fs;
        }
    }
    config.pack(0, old_fs, out);
    0
}

/// Port of `fake_id0_callback` INITIALIZATION uid:gid parsing
/// (`fake_id0.c:1207-1245`).  `strtol` with base 10 and the `errno` fallbacks
/// are reproduced; a `NULL` string is treated as empty by the harness.
pub fn ra_fid0_init_ids(
    uid_string: Option<&[u8]>,
    fallback_uid: c_int,
    fallback_gid: c_int,
    out: &mut [u8],
) -> c_int {
    let bytes = uid_string.unwrap_or(&[]);
    let text = String::from_utf8_lossy(bytes);
    let colon = text.find(':');
    // C strtol(base 10): skip ASCII space, take one sign, then decimal digits.
    // No digits returns 0 with errno untouched (so no fallback); only ERANGE
    // triggers the caller's getuid()/getgid() fallback.  A long is then cast
    // to int, which truncates values above INT_MAX.
    let parse = |s: &str| -> Result<i32, ()> {
        let b = s.as_bytes();
        let mut at = 0;
        while at < b.len() && matches!(b[at], b' ' | b'\t' | b'\n' | b'\r' | 0x0b | 0x0c) {
            at += 1;
        }
        let negative = if at < b.len() && (b[at] == b'+' || b[at] == b'-') {
            let negative = b[at] == b'-';
            at += 1;
            negative
        } else {
            false
        };
        let mut value: i64 = 0;
        let mut any = false;
        while at < b.len() && b[at].is_ascii_digit() {
            any = true;
            value = value
                .checked_mul(10)
                .and_then(|v| v.checked_add((b[at] - b'0') as i64))
                .ok_or(())?;
            at += 1;
        }
        let _ = any; // no digits yields 0, exactly like strtol with errno == 0
        if negative {
            value = value.checked_neg().ok_or(())?;
        }
        Ok(value as i32)
    };
    let uid_value = parse(&text).unwrap_or(fallback_uid);
    let gid_value = match colon {
        Some(at) => parse(&text[at + 1..]).unwrap_or(fallback_gid),
        None => fallback_gid,
    };

    let config = Config {
        ruid: uid_value as u32,
        euid: uid_value as u32,
        suid: uid_value as u32,
        fsuid: uid_value as u32,
        rgid: gid_value as u32,
        egid: gid_value as u32,
        sgid: gid_value as u32,
        fsgid: gid_value as u32,
        umask: 0o22,
        caps_active: uid_value == 0,
        keep_caps: false,
    };
    config.pack(0, 0, out);
    0
}

/// Port of the `PR_prctl` branch of `handle_sysexit_end`
/// (`fake_id0.c:962-975`).  Linux clears `keep_caps` on execve; the harness
/// supplies the st_mode bits directly instead of calling `stat()`.
pub fn ra_fid0_prctl_keepcaps(op: c_int, result: c_int, arg: c_uint, keep_caps: c_int) -> c_int {
    if result == 0 && op == PR_SET_KEEPCAPS {
        i32::from(arg != 0)
    } else {
        keep_caps
    }
}

/// Port of the setuid/setgid binary part of `handle_sysexit_start`
/// (`fake_id0.c:1177-1194`).
#[expect(
    clippy::too_many_arguments,
    reason = "the C ABI and the harness wrapper have the same plain arguments"
)]
pub fn ra_fid0_exec_setid(
    st_mode: c_uint,
    ruid: c_int,
    euid: c_int,
    suid: c_int,
    fsuid: c_int,
    rgid: c_int,
    egid: c_int,
    sgid: c_int,
    fsgid: c_int,
    umask: c_int,
    caps_active: c_int,
    keep_caps: c_int,
    out: &mut [u8],
) -> c_int {
    let mut config = Config::new(
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
    );
    config.keep_caps = false;
    if st_mode & S_ISUID != 0 {
        config.euid = 0;
        config.suid = 0;
        config.caps_active = true;
    }
    if st_mode & S_ISGID != 0 {
        config.egid = 0;
        config.sgid = 0;
    }
    config.pack(0, 0, out);
    0
}

/// Port of `fake_id0_handle_statx_syscall()` (`stat.c:160-176`).  The original
/// compares the gid against `getuid()` rather than `getgid()`; that bug is
/// kept, with `real_gid` standing in for the value `getuid()` returned.
#[expect(
    clippy::too_many_arguments,
    reason = "the C ABI and the harness wrapper have the same plain arguments"
)]
pub fn ra_fid0_statx(
    mask: c_uint,
    st_uid: c_uint,
    st_gid: c_uint,
    real_uid: c_uint,
    real_gid: c_uint,
    suid: c_uint,
    sgid: c_uint,
    out: &mut [u8],
) -> c_int {
    let mut uid = st_uid;
    let mut gid = st_gid;
    let mut updated = false;
    if mask & STATX_UID != 0 && uid == real_uid {
        uid = suid;
        updated = true;
    }
    if mask & STATX_GID != 0 && gid == real_gid {
        gid = sgid;
        updated = true;
    }
    put_u32(out, 0, uid);
    put_u32(out, 4, gid);
    if let Some(slot) = out.get_mut(8..12) {
        slot.copy_from_slice(&[u32::from(updated) as u8, 0, 0, 0]);
    }
    i32::from(updated)
}

/// Port of the mode merge in `stat.c:125`.
pub fn ra_fid0_stat_mode(meta_mode: c_uint, st_mode: c_uint) -> c_uint {
    meta_mode | ((st_mode & S_IFMT) | (st_mode & 0o7_000))
}

/// Port of the mask and check in `access.c:34-52`.
///
/// Faithful quirk: `F_OK` is 0, so `mode & F_OK` never short-circuits and the
/// R_OK/W_OK/X_OK mask is always computed.
#[expect(
    clippy::bad_bit_mask,
    reason = "faithful to the C code: F_OK is 0, so this test never fires"
)]
pub fn ra_fid0_access_check(mode: c_int, perms: c_int) -> c_int {
    let mut mask = 0;
    if mode & F_OK != 0 {
        return 0;
    }
    if mode & R_OK == R_OK {
        mask += 4;
    }
    if mode & W_OK == W_OK {
        mask += 2;
    }
    if mode & X_OK == X_OK {
        mask += 1;
    }
    if perms & mask != mask { -EACCES } else { 0 }
}

/// Port of the access-mode check in `open.c:85-92`.
pub fn ra_fid0_open_check(flags: c_int, perms: c_int) -> c_int {
    let access_mode = flags & O_ACCMODE;
    if (access_mode == O_WRONLY && perms & 2 != 2)
        || (access_mode == O_RDONLY && perms & 4 != 4)
        || (access_mode == O_RDWR && perms & 6 != 6)
    {
        -EACCES
    } else {
        0
    }
}

/// Port of `handle_socket_exit_end()` (`socket.c:10-22`).
pub fn ra_fid0_socket_check(result: c_int, family: c_int, protocol: c_int, euid: c_int) -> c_int {
    if result != -EPERM && result != -EACCES {
        return 0;
    }
    if family == 16 && protocol == 9 && euid == 0 {
        -EPROTONOSUPPORT
    } else {
        0
    }
}

fn read_u32_le(bytes: &[u8], at: usize) -> u32 {
    let mut buf = [0u8; 4];
    buf.copy_from_slice(&bytes[at..at + 4]);
    u32::from_le_bytes(buf)
}

fn read_u64_le(bytes: &[u8], at: usize) -> u64 {
    let mut buf = [0u8; 8];
    buf.copy_from_slice(&bytes[at..at + 8]);
    u64::from_le_bytes(buf)
}

fn write_u32_le(bytes: &mut [u8], at: usize, value: u32) {
    bytes[at..at + 4].copy_from_slice(&value.to_le_bytes());
}

/// Port of the `SCM_CREDENTIALS` scan in `sendmsg.c:115-205`.  `is32` selects
/// the 32-on-64 layout (12-byte cmsg header) used on x86_64/ARM64; otherwise
/// the native `struct cmsghdr` layout (16 bytes here) is used.
pub fn ra_fid0_sendmsg_scan(
    buf: &[u8],
    is32: c_int,
    real_uid: c_uint,
    real_gid: c_uint,
    out: &mut [u8],
) -> c_int {
    let header = if is32 != 0 { 12usize } else { 16usize };
    let align_mask = if is32 != 0 {
        3usize
    } else {
        std::mem::size_of::<usize>() - 1
    };
    let len = buf.len().min(out.len()).min(MAX_CONTROLLEN);
    out[..len].copy_from_slice(&buf[..len]);
    let mut position = 0usize;
    let mut did_modify = false;

    while position < len {
        if len - position < header {
            return 0;
        }
        let (cmsg_len, level, kind) = if is32 != 0 {
            (
                read_u32_le(out, position) as u64,
                read_u32_le(out, position + 4) as i32,
                read_u32_le(out, position + 8) as i32,
            )
        } else {
            (
                read_u64_le(out, position),
                read_u32_le(out, position + 8) as i32,
                read_u32_le(out, position + 12) as i32,
            )
        };
        if !(cmsg_len >= header as u64 && cmsg_len <= (len - position) as u64) {
            return 0;
        }
        if level == SOL_SOCKET && kind == SCM_CREDENTIALS {
            if cmsg_len != (header + 12) as u64 {
                return 0;
            }
            let at = position + header;
            write_u32_le(out, at + 4, real_uid);
            write_u32_le(out, at + 8, real_gid);
            did_modify = true;
        }
        position += (cmsg_len as usize + align_mask) & !align_mask;
    }
    c_int::from(did_modify)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cfg() -> Config {
        Config::new(1000, 1000, 1000, 1000, 100, 100, 100, 100, 0o22, 0, 0)
    }

    #[test]
    fn octal_conversions_round_trip_the_common_modes() {
        assert_eq!(otod(755), 493);
        assert_eq!(dtoo(493), 755);
        assert_eq!(otod(644), 420);
        assert_eq!(dtoo(420), 644);
        assert_eq!(dtoo(0), 0);
        assert_eq!(otod(0), 0);
    }

    #[test]
    fn name_and_dir_path_handle_leading_and_trailing_slashes() {
        assert_eq!(ra_fid0_get_name(Some(b"/a/b/c")), 5);
        assert_eq!(ra_fid0_get_name(Some(b"foo")), 0);
        assert_eq!(ra_fid0_get_name(Some(b"/")), 1);
        let mut out = [0u8; 16];
        ra_fid0_get_dir_path(Some(b"/a/b/c"), &mut out);
        assert_eq!(&out[..4], b"/a/b");
        ra_fid0_get_dir_path(Some(b"/a"), &mut out);
        assert_eq!(&out[..1], b"/");
    }

    #[test]
    fn meta_path_inserts_the_tag_before_the_final_component() {
        let mut out = [0u8; 64];
        ra_fid0_get_meta_path(Some(b"/a/b/c"), &mut out);
        assert_eq!(&out[..23], b"/a/b/.proot-meta-file.c");
        assert_eq!(
            ra_fid0_get_meta_path(Some(&[b'a'; PATH_MAX]), &mut out),
            -ENAMETOOLONG
        );
    }

    #[test]
    fn root_gets_owner_permissions_and_full_access() {
        let config = Config::new(0, 0, 0, 0, 0, 0, 0, 0, 0o22, 1, 0);
        assert_eq!(permissions(b"missing-meta", &config, false), 7);
        let config = cfg();
        assert_eq!(permissions(b"missing-meta", &config, false), 7);
    }

    #[test]
    fn setuid_requires_privilege_and_drops_caps_on_a_permanent_drop() {
        let mut out = [0u8; 52];
        let root = Config::new(0, 0, 0, 0, 0, 0, 0, 0, 0o22, 1, 0);
        assert_eq!(
            ra_fid0_setxid(
                0,
                root.ruid as i32,
                root.euid as i32,
                root.suid as i32,
                root.fsuid as i32,
                root.rgid as i32,
                root.egid as i32,
                root.sgid as i32,
                root.fsgid as i32,
                root.umask as i32,
                i32::from(root.caps_active),
                0,
                1000,
                &mut out,
            ),
            0
        );
        assert_eq!(u32::from_le_bytes(out[0..4].try_into().unwrap()), 1000);
        assert_eq!(u32::from_le_bytes(out[36..40].try_into().unwrap()), 0); // caps_active
        let unprivileged = cfg();
        assert_eq!(
            ra_fid0_setxid(
                0,
                unprivileged.ruid as i32,
                unprivileged.euid as i32,
                unprivileged.suid as i32,
                unprivileged.fsuid as i32,
                unprivileged.rgid as i32,
                unprivileged.egid as i32,
                unprivileged.sgid as i32,
                unprivileged.fsgid as i32,
                unprivileged.umask as i32,
                0,
                0,
                42,
                &mut out,
            ),
            -EPERM
        );
    }

    #[test]
    fn setfsuid_returns_the_old_value() {
        let mut out = [0u8; 52];
        ra_fid0_setfsxid(
            0, 1000, 1000, 1000, 1000, 100, 100, 100, 100, 0o22, 0, 0, 1000, &mut out,
        );
        assert_eq!(u32::from_le_bytes(out[48..52].try_into().unwrap()), 1000);
    }

    #[test]
    fn keep_caps_survives_a_uid_drop_only_when_set() {
        assert_eq!(ra_fid0_prctl_keepcaps(PR_SET_KEEPCAPS, 0, 1, 0), 1);
        assert_eq!(ra_fid0_prctl_keepcaps(PR_SET_KEEPCAPS, -1, 1, 0), 0);
        assert_eq!(ra_fid0_prctl_keepcaps(4, 0, 1, 0), 0);
    }

    #[test]
    fn statx_replaces_only_requested_matching_ids() {
        let mut out = [0u8; 12];
        assert_eq!(
            ra_fid0_statx(STATX_UID, 0, 0, 0, 0, 1000, 1000, &mut out),
            1
        );
        assert_eq!(u32::from_le_bytes(out[0..4].try_into().unwrap()), 1000);
        assert_eq!(
            ra_fid0_statx(STATX_UID, 7, 0, 0, 0, 1000, 1000, &mut out),
            0
        );
    }

    #[test]
    fn access_and_open_masks_match_the_c_bit_tests() {
        assert_eq!(ra_fid0_access_check(R_OK | X_OK, 5), 0);
        assert_eq!(ra_fid0_access_check(R_OK | W_OK, 5), -EACCES);
        assert_eq!(ra_fid0_access_check(0, 0), 0); // F_OK is 0 in the C original
        assert_eq!(ra_fid0_open_check(O_WRONLY, 6), 0);
        assert_eq!(ra_fid0_open_check(O_WRONLY, 4), -EACCES);
        assert_eq!(ra_fid0_open_check(O_RDWR, 6), 0);
    }

    #[test]
    fn socket_check_only_overrides_audit_permissions_errors() {
        assert_eq!(ra_fid0_socket_check(-1, 16, 9, 0), -EPROTONOSUPPORT);
        assert_eq!(ra_fid0_socket_check(0, 16, 9, 0), 0);
        assert_eq!(ra_fid0_socket_check(-1, 16, 9, 1000), 0);
    }

    #[test]
    fn sendmsg_scan_rewrites_credentials_in_both_layouts() {
        let mut cred = [0u8; 12];
        write_u32_le(&mut cred, 4, 1000);
        write_u32_le(&mut cred, 8, 1000);
        for (is32, header) in [(0, 16usize), (1, 12usize)] {
            let mut buf = vec![0u8; header + 12];
            if is32 == 0 {
                buf[..8].copy_from_slice(&((header + 12) as u64).to_le_bytes());
                write_u32_le(&mut buf, 8, 1);
                write_u32_le(&mut buf, 12, 2);
            } else {
                write_u32_le(&mut buf, 0, (header + 12) as u32);
                write_u32_le(&mut buf, 4, 1);
                write_u32_le(&mut buf, 8, 2);
            }
            buf[header..].copy_from_slice(&cred);
            let mut out = vec![0u8; 64];
            assert_eq!(ra_fid0_sendmsg_scan(&buf, is32, 42, 43, &mut out), 1);
            assert_eq!(
                u32::from_le_bytes(out[header + 4..header + 8].try_into().unwrap()),
                42
            );
            assert_eq!(
                u32::from_le_bytes(out[header + 8..header + 12].try_into().unwrap()),
                43
            );
        }
    }

    #[test]
    fn sendmsg_scan_rejects_malformed_headers() {
        let mut buf = [0u8; 16];
        buf[..8].copy_from_slice(&8u64.to_le_bytes());
        let mut out = [0u8; 32];
        assert_eq!(ra_fid0_sendmsg_scan(&buf, 0, 42, 43, &mut out), 0);
    }

    #[test]
    fn stat_mode_keeps_file_type_and_setid_bits() {
        assert_eq!(
            ra_fid0_stat_mode(0o644, 0o104755),
            0o644 | 0o100000 | 0o4000
        );
    }
}
