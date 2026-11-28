//! Host-testable semantics of XYZTerm's two link2symlink implementations.
//!
//! There are two different behaviors and this crate keeps them separate:
//!
//! * [`shim`] ports `link`/`linkat` from
//!   `features/terminal/link2symlink/src/main/cpp/link2symlink.cpp`: verify the
//!   source, make its path absolute, then create a symlink.
//! * [`extension`] ports the `--link2symlink` PRoot extension
//!   `features/terminal/proot/src/main/cpp/extension/link2symlink/link2symlink.c`:
//!   rename the backing file into a `.l2s.` chain, bump a 4-digit counter and
//!   fake `st_nlink`.
//!
//! Characterization cases live in `rust/research/link2symlink-semantics.md`
//! and ADR-006 requires preserving them, including the bare `-1` errno bugs.
//!
//! Deliberately **not** ported here (ptrace/runtime-only, never faked):
//! reading/writing tracee registers and memory, `read_string`/`read_path`,
//! `poke_reg`, chained syscalls inside PRoot, `notify_extensions`, the
//! `/proc/<pid>/fd` stat-path recovery, the copy loop for deleted
//! `/proc` fd sources, and writing the returned `struct stat`/`statx` buffer.
//! This crate computes the *decisions*; the runtime performs those operations.
#![forbid(unsafe_code)]

pub mod extension;
pub mod shim;

use xyzterm_path::link_compat::PATH_MAX;

pub const AT_FDCWD: i32 = -100;
pub const AT_SYMLINK_NOFOLLOW: i32 = 0x100;
/// `CPP:23-25` fallback and Linux value. `xyzterm-path`'s link_compat constant
/// is 0x2000; it is not used here because this bit is observable in flag logic.
pub const AT_SYMLINK_FOLLOW: i32 = 0x400;
pub const AT_EMPTY_PATH: i32 = 0x1000;
pub const AT_REMOVEDIR: i32 = 0x200;

/// Raw Linux/Android errno values, used by both ports.
pub mod errno {
    pub const EPERM: i32 = 1;
    pub const ENOENT: i32 = 2;
    pub const EBADF: i32 = 9;
    pub const EFAULT: i32 = 14;
    pub const EEXIST: i32 = 17;
    pub const EINVAL: i32 = 22;
    pub const ENAMETOOLONG: i32 = 36;
}

/// How an error is returned to the guest by the PRoot extension.
///
/// Most helpers return `-errno`, but the rename/symlink/notify failures in
/// `move_and_symlink_path` and `decrement_link_count` return a bare `-1`
/// (`EXT:140-155`, `:168-181`, `:188-189`, `:265-291`); the guest then sees
/// `EPERM` instead of the real errno.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Failure {
    Errno(i32),
    BareMinusOne,
}

impl Failure {
    /// `-errno` propagation; a non-positive errno becomes `ENOENT` (`EXT:83`).
    pub fn raw(errno: i32) -> Self {
        Self::Errno(if errno > 0 { errno } else { errno::ENOENT })
    }

    pub fn bare_minus_one() -> Self {
        Self::BareMinusOne
    }

    pub fn return_value(self) -> i32 {
        match self {
            Self::Errno(errno) => -errno,
            Self::BareMinusOne => -1,
        }
    }

    pub fn guest_errno(self) -> i32 {
        match self {
            Self::Errno(errno) => errno,
            Self::BareMinusOne => errno::EPERM,
        }
    }
}

/// `snprintf(out, PATH_MAX, ...)` / `strncpy(out, PATH_MAX - 1, ...)` truncation.
pub fn truncate_path(path: &[u8]) -> Vec<u8> {
    path[..path.len().min(PATH_MAX - 1)].to_vec()
}

/// C++ shim `resolve_to_absolute` (`CPP:32-81`), with a NULL path mapped to
/// `EINVAL` as in `CPP:33-36`. `base` is supplied by the runtime: the cwd for
/// `AT_FDCWD`, or the target of `/proc/self/fd/<dirfd>` otherwise.
///
/// Only `AT_EMPTY_PATH` is consulted (and then only for an empty path);
/// absolute paths are copied verbatim and relative paths are simply joined.
pub fn resolve_to_absolute(base: &[u8], path: Option<&[u8]>, flags: i32) -> Result<Vec<u8>, i32> {
    let path = path.ok_or(errno::EINVAL)?;
    Ok(xyzterm_path::link_compat::resolve_to_absolute(
        base, path, flags,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn failure_return_values_and_errno_mapping() {
        assert_eq!(Failure::raw(errno::ENOENT).return_value(), -2);
        assert_eq!(Failure::raw(errno::ENOENT).guest_errno(), errno::ENOENT);
        assert_eq!(Failure::raw(0), Failure::Errno(errno::ENOENT));
        assert_eq!(Failure::bare_minus_one().return_value(), -1);
        // E24: EXDEV from the internal rename is masked as EPERM by bare -1.
        assert_eq!(Failure::bare_minus_one().guest_errno(), errno::EPERM);
    }

    #[test]
    fn null_path_is_einval_and_truncation_is_silent() {
        assert_eq!(resolve_to_absolute(b"/tmp", None, 0), Err(errno::EINVAL));
        let long = vec![b'a'; PATH_MAX + 10];
        let out = resolve_to_absolute(b"/", Some(&long), 0).unwrap();
        assert_eq!(out.len(), PATH_MAX - 1);
        assert_eq!(truncate_path(&long).len(), PATH_MAX - 1);
    }
}
