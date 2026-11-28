//! The libc-level C++ shim (`CPP:32-151`).
//!
//! `link`/`linkat` verify the source with `fstatat`, turn the source into an
//! absolute path, and delegate to `symlink`/`symlinkat`. There is no counter,
//! no intermediate file, no `st_nlink` faking and no `S_ISDIR` check. This
//! module only decides flags and target strings; the actual `fstatat`,
//! `readlink`, `getcwd`, `realpath` and `symlink` calls stay in the runtime.
//!
//! This mirrors the *C++ shim*, not the PRoot extension, even though both are
//! named "link2symlink" in the original tree.
#![forbid(unsafe_code)]

use crate::{
    AT_EMPTY_PATH, AT_SYMLINK_FOLLOW, AT_SYMLINK_NOFOLLOW, errno, resolve_to_absolute,
    truncate_path,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Call {
    Link,
    Linkat,
}

/// Flags used for the source `fstatat` (`CPP:89-93`, `:116-127`).
///
/// `link` always uses `AT_SYMLINK_NOFOLLOW`. `linkat` follows the source only
/// when `AT_SYMLINK_FOLLOW` is set, and adds `AT_EMPTY_PATH` if requested.
/// Unknown bits are silently ignored (unlike a real `linkat`).
pub fn verification_flags(call: Call, flags: i32) -> i32 {
    match call {
        Call::Link => AT_SYMLINK_NOFOLLOW,
        Call::Linkat => {
            let mut fstat_flags = 0;
            if flags & AT_SYMLINK_FOLLOW == 0 {
                fstat_flags |= AT_SYMLINK_NOFOLLOW;
            }
            if flags & AT_EMPTY_PATH != 0 {
                fstat_flags |= AT_EMPTY_PATH;
            }
            fstat_flags
        }
    }
}

/// `realpath()` is attempted only for `linkat` with `AT_SYMLINK_FOLLOW`
/// (`CPP:135-141`); the C++ code ignores its failure.
pub fn realpath_requested(flags: i32) -> bool {
    flags & AT_SYMLINK_FOLLOW != 0
}

/// The target string handed to `symlink`/`symlinkat`.
///
/// `base` is the resolved dirfd (cwd or `/proc/self/fd/N` target) supplied by
/// the runtime. `canonical` is the result of `realpath(target)`: `None` means
/// `realpath` failed, in which case the unresolved target is kept, as in
/// `CPP:135-141`. The destination is never canonicalized here.
pub fn symlink_target(
    base: &[u8],
    oldpath: Option<&[u8]>,
    flags: i32,
    canonical: Option<&[u8]>,
) -> Result<Vec<u8>, i32> {
    let path = oldpath.ok_or(errno::EINVAL)?;
    let mut target = resolve_to_absolute(base, Some(path), flags)?;
    if realpath_requested(flags)
        && let Some(canonical) = canonical
    {
        target = truncate_path(canonical);
    }
    Ok(target)
}

/// A NULL `newpath` reaches libc `symlink`/`symlinkat` and fails with `EFAULT`
/// (`CPP:102`, `:144`); no other validation happens.
pub fn check_newpath(newpath: Option<&[u8]>) -> Result<(), i32> {
    if newpath.is_some() {
        Ok(())
    } else {
        Err(errno::EFAULT)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Stand-in for the runtime: `verify` is the `fstatat` result, `create` is
    /// the `symlink`/`symlinkat` result. It returns the target string on success.
    // Test-only stand-in for the runtime's fstatat/symlink calls.
    #[allow(clippy::too_many_arguments)]
    fn attempt(
        call: Call,
        base: &[u8],
        oldpath: Option<&[u8]>,
        newpath: Option<&[u8]>,
        flags: i32,
        canonical: Option<&[u8]>,
        verify: Result<(), i32>,
        create: Result<(), i32>,
    ) -> Result<Vec<u8>, i32> {
        let _ = verification_flags(call, flags);
        verify?;
        let target = symlink_target(base, oldpath, flags, canonical)?;
        check_newpath(newpath)?;
        create?;
        Ok(target)
    }

    fn ok() -> Result<(), i32> {
        Ok(())
    }

    #[test]
    fn c1_c3_c4_absolute_source_is_copied_verbatim() {
        assert_eq!(
            attempt(
                Call::Link,
                b"/home/u/work",
                Some(b"/home/u/work/a"),
                Some(b"/home/u/work/b"),
                0,
                None,
                ok(),
                ok()
            ),
            Ok(b"/home/u/work/a".to_vec())
        );
        // C3/C4: the destination is irrelevant to target construction.
        assert_eq!(
            symlink_target(b"/home/u/work", Some(b"/a/../b"), 0, None),
            Ok(b"/a/../b".to_vec())
        );
    }

    #[test]
    fn c2_c13_relative_source_uses_cwd_or_dirfd_base() {
        assert_eq!(
            attempt(
                Call::Link,
                b"/home/u/work",
                Some(b"a"),
                Some(b"b"),
                0,
                None,
                ok(),
                ok()
            ),
            Ok(b"/home/u/work/a".to_vec())
        );
        assert_eq!(
            attempt(
                Call::Linkat,
                b"/home/u/work",
                Some(b"a"),
                Some(b"b"),
                0,
                None,
                ok(),
                ok()
            ),
            Ok(b"/home/u/work/a".to_vec())
        );
    }

    #[test]
    fn c14_c15_c21_c22_dirfd_and_absolute_oldpath() {
        // C14: fd("/home/u/work") base; PRoot/libc resolves the dirfd for us.
        assert_eq!(
            symlink_target(b"/proc/self/fd/3", Some(b"a"), 0, None),
            Ok(b"/proc/self/fd/3/a".to_vec())
        );
        // C15: an absolute oldpath ignores dirfd entirely.
        assert_eq!(
            symlink_target(b"/proc/self/fd/3", Some(b"/home/u/work/a"), 0, None),
            Ok(b"/home/u/work/a".to_vec())
        );
        // C21/C22: verification failures are propagated unchanged.
        assert_eq!(
            attempt(
                Call::Linkat,
                b"/home/u/work",
                Some(b"missing"),
                Some(b"b"),
                0,
                None,
                Err(errno::ENOENT),
                ok()
            ),
            Err(errno::ENOENT)
        );
        assert_eq!(
            attempt(
                Call::Linkat,
                b"/home/u/work",
                Some(b"a"),
                Some(b"b"),
                0,
                None,
                Err(errno::EBADF),
                ok()
            ),
            Err(errno::EBADF)
        );
    }

    #[test]
    fn c16_c17_c18_at_empty_path_uses_the_fd_target_verbatim() {
        assert_eq!(
            verification_flags(Call::Linkat, AT_EMPTY_PATH),
            AT_SYMLINK_NOFOLLOW | AT_EMPTY_PATH
        );
        assert_eq!(
            symlink_target(b"/home/u/work/a", Some(b""), AT_EMPTY_PATH, None),
            Ok(b"/home/u/work/a".to_vec())
        );
        // C17: the kernel's " (deleted)" suffix is preserved as a dangling link.
        assert_eq!(
            symlink_target(b"/home/u/work/a (deleted)", Some(b""), AT_EMPTY_PATH, None),
            Ok(b"/home/u/work/a (deleted)".to_vec())
        );
        // C18: AT_FDCWD + empty path fails in fstatat before resolution.
        assert_eq!(
            attempt(
                Call::Linkat,
                b"/proc/self/fd/-100",
                Some(b""),
                Some(b"b"),
                AT_EMPTY_PATH,
                None,
                Err(errno::EBADF),
                ok()
            ),
            Err(errno::EBADF)
        );
    }

    #[test]
    fn c19_c20_symlink_follow_only_with_realpath_success() {
        assert_eq!(
            verification_flags(Call::Linkat, AT_SYMLINK_FOLLOW),
            0,
            "AT_SYMLINK_FOLLOW drops AT_SYMLINK_NOFOLLOW"
        );
        assert_eq!(
            attempt(
                Call::Linkat,
                b"/home/u/work",
                Some(b"sym"),
                Some(b"b"),
                AT_SYMLINK_FOLLOW,
                Some(b"/home/u/work/target"),
                ok(),
                ok()
            ),
            Ok(b"/home/u/work/target".to_vec())
        );
        // realpath failure is swallowed: the unresolved target is used.
        assert_eq!(
            symlink_target(b"/home/u/work", Some(b"sym"), AT_SYMLINK_FOLLOW, None),
            Ok(b"/home/u/work/sym".to_vec())
        );
        assert_eq!(
            symlink_target(b"/home/u/work", Some(b"sym"), 0, Some(b"/ignored")),
            Ok(b"/home/u/work/sym".to_vec())
        );
    }

    #[test]
    fn c5_c6_c7_c8_c9_c10_c11_c23_c24_error_paths() {
        // C5 / C11: missing or empty source fails verification.
        assert_eq!(
            attempt(
                Call::Link,
                b"/home/u/work",
                Some(b"missing"),
                Some(b"b"),
                0,
                None,
                Err(errno::ENOENT),
                ok()
            ),
            Err(errno::ENOENT)
        );
        assert_eq!(
            attempt(
                Call::Link,
                b"/home/u/work",
                Some(b""),
                Some(b"b"),
                0,
                None,
                Err(errno::ENOENT),
                ok()
            ),
            Err(errno::ENOENT)
        );
        // C6: a directory source is accepted: no S_ISDIR check exists in CPP.
        assert_eq!(
            attempt(
                Call::Link,
                b"/home/u/work",
                Some(b"/home/u/work"),
                Some(b"b"),
                0,
                None,
                ok(),
                ok()
            ),
            Ok(b"/home/u/work".to_vec())
        );
        // C7/C8: symlink failures (EEXIST / ENOENT) are returned as-is.
        assert_eq!(
            attempt(
                Call::Link,
                b"/home/u/work",
                Some(b"a"),
                Some(b"a"),
                0,
                None,
                ok(),
                Err(errno::EEXIST)
            ),
            Err(errno::EEXIST)
        );
        assert_eq!(
            attempt(
                Call::Link,
                b"/home/u/work",
                Some(b"a"),
                Some(b"/no/such/dir/b"),
                0,
                None,
                ok(),
                Err(errno::ENOENT)
            ),
            Err(errno::ENOENT)
        );
        // C9: NULL oldpath fails fstatat first. C10: NULL newpath fails symlink.
        assert_eq!(
            attempt(
                Call::Link,
                b"/home/u/work",
                None,
                Some(b"b"),
                0,
                None,
                Err(errno::EFAULT),
                ok()
            ),
            Err(errno::EFAULT)
        );
        assert_eq!(
            attempt(
                Call::Link,
                b"/home/u/work",
                Some(b"a"),
                None,
                0,
                None,
                ok(),
                Err(errno::EFAULT)
            ),
            Err(errno::EFAULT)
        );
        // C23: bad newdirfd. C24: empty newpath.
        assert_eq!(check_newpath(Some(b"b")), Ok(()));
        assert_eq!(
            attempt(
                Call::Linkat,
                b"/home/u/work",
                Some(b"a"),
                Some(b"b"),
                0,
                None,
                ok(),
                Err(errno::EBADF)
            ),
            Err(errno::EBADF)
        );
        assert_eq!(
            attempt(
                Call::Linkat,
                b"/home/u/work",
                Some(b"a"),
                Some(b""),
                0,
                None,
                ok(),
                Err(errno::ENOENT)
            ),
            Err(errno::ENOENT)
        );
    }

    #[test]
    fn c12_symlink_source_is_a_path_not_a_dereference() {
        assert_eq!(
            symlink_target(b"/home/u/work", Some(b"sym"), 0, None),
            Ok(b"/home/u/work/sym".to_vec())
        );
    }

    #[test]
    fn c25_unknown_flags_are_ignored() {
        assert_eq!(
            verification_flags(Call::Linkat, 0x8),
            verification_flags(Call::Linkat, 0)
        );
        assert_eq!(
            symlink_target(b"/home/u/work", Some(b"a"), 0x8, None),
            symlink_target(b"/home/u/work", Some(b"a"), 0, None)
        );
        assert!(!realpath_requested(0x8));
    }

    #[test]
    fn c26_oversized_source_fails_verification_but_resolution_truncates() {
        let long = vec![b'a'; 4100];
        assert_eq!(
            attempt(
                Call::Link,
                b"/home/u/work",
                Some(&long),
                Some(b"b"),
                0,
                None,
                Err(errno::ENAMETOOLONG),
                ok()
            ),
            Err(errno::ENAMETOOLONG)
        );
        // If verification succeeded, the join is silently truncated.
        let out = symlink_target(b"/home/u/work", Some(&long), 0, None).unwrap();
        assert_eq!(out.len(), 4095);
    }
}
