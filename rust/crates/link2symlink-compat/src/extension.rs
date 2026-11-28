//! Pure logic of the `--link2symlink` PRoot extension (`EXT:37-486`).
//!
//! The C layout emulated here is
//!
//! ```text
//! <dir>/name               -> symlink to intermediate
//! <dir>/.l2s.name0001      -> symlink to final   (intermediate)
//! <dir>/.l2s.name0001.0002 -> real file content  (final)
//! ```
//!
//! The runtime supplies already-translated host paths and the results of
//! `lstat`/`readlink`/`access`; this module decides names, counters, flags and
//! errno values. Ptrace-only work is explicitly not implemented here (see the
//! crate docs).
#![forbid(unsafe_code)]

use crate::{AT_REMOVEDIR, AT_SYMLINK_FOLLOW, Failure, errno, truncate_path};
use proot_syscall::Abi;
use xyzterm_path::link_compat::{PATH_MAX, is_l2s_name};

pub const PREFIX: &[u8] = xyzterm_path::link_compat::PREFIX;
pub const DELETED_SUFFIX: &[u8] = b" (deleted)";
/// Last suffix the `do { ... } while (taken && suffix < 1000)` loop can format
/// (`EXT:132-136`): the increment happens before the condition, so `1000` is
/// never formatted even though research case E25 suggests otherwise.
pub const MAX_INTERMEDIATE_SUFFIX: u32 = 999;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Error {
    NameTooLong,
    Directory,
    BrokenLink,
}

impl Error {
    pub fn errno(self) -> i32 {
        match self {
            Self::NameTooLong => errno::ENAMETOOLONG,
            Self::Directory => errno::EPERM,
            Self::BrokenLink => errno::ENOENT,
        }
    }
}

/// Source as reported by the runtime's `lstat` (and `readlink` for symlinks).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Source<'a> {
    Regular,
    Directory,
    Symlink(&'a [u8]),
}

pub fn basename(path: &[u8]) -> &[u8] {
    match path.iter().rposition(|byte| *byte == b'/') {
        Some(index) => &path[index + 1..],
        None => path,
    }
}

/// The unsuffixed intermediate `<dir>/.l2s.<name>` (`EXT:102-128`).
///
/// When `PROOT_L2S_DIR` is set and non-empty it replaces the original's
/// directory, including the original's conservative length check.
pub fn l2s_base(original: &[u8], l2s_dir: Option<&[u8]>) -> Result<Vec<u8>, Error> {
    let name = basename(original);
    let dir_len = original.len() - name.len();
    let mut out = Vec::new();
    match l2s_dir {
        Some(dir) if !dir.is_empty() => {
            if PREFIX.len() + dir.len() + dir_len + 6 >= PATH_MAX {
                return Err(Error::NameTooLong);
            }
            out.extend_from_slice(dir);
            if dir.last() != Some(&b'/') {
                out.push(b'/');
            }
        }
        _ => {
            if PREFIX.len() + original.len() + 5 >= PATH_MAX {
                return Err(Error::NameTooLong);
            }
            out.extend_from_slice(&original[..dir_len]);
        }
    }
    out.extend_from_slice(PREFIX);
    out.extend_from_slice(name);
    if out.len() >= PATH_MAX {
        return Err(Error::NameTooLong);
    }
    Ok(out)
}

/// First unused intermediate suffix (`EXT:130-136`). The C loop checks
/// `access()` and then the incremented counter, so it stops at 999.
pub fn choose_intermediate_suffix(is_taken: impl Fn(u32) -> bool) -> u32 {
    let mut suffix = 1;
    loop {
        let candidate = suffix;
        suffix += 1;
        if !is_taken(candidate) || suffix >= 1000 {
            return candidate;
        }
    }
}

pub fn intermediate_with_suffix(base: &[u8], suffix: u32) -> Result<Vec<u8>, Error> {
    let mut out = base.to_vec();
    out.extend_from_slice(format!("{suffix:04}").as_bytes());
    if out.len() >= PATH_MAX {
        Err(Error::NameTooLong)
    } else {
        Ok(out)
    }
}

/// The initial final path: `<intermediate>.0002` (`EXT:138-139`).
pub fn first_final(intermediate: &[u8]) -> Result<Vec<u8>, Error> {
    let mut out = intermediate.to_vec();
    out.extend_from_slice(b".0002");
    if out.len() >= PATH_MAX {
        Err(Error::NameTooLong)
    } else {
        Ok(out)
    }
}

/// C `atoi` on the last four bytes (`EXT:162`, `:257`, `:433`, `:485`).
/// There is no digit validation in the original: a non-digit stops parsing and
/// yields 0 (or a partial number). Overflow saturates instead of being UB.
pub fn c_atoi(bytes: &[u8]) -> i32 {
    let mut index = 0;
    while bytes.get(index).is_some_and(u8::is_ascii_whitespace) {
        index += 1;
    }
    let negative = match bytes.get(index) {
        Some(b'-') => {
            index += 1;
            true
        }
        Some(b'+') => {
            index += 1;
            false
        }
        _ => false,
    };
    let mut value = 0i32;
    while let Some(digit) = bytes.get(index) {
        if !digit.is_ascii_digit() {
            break;
        }
        value = value
            .saturating_mul(10)
            .saturating_add(i32::from(digit - b'0'));
        index += 1;
    }
    if negative { -value } else { value }
}

pub fn counter_at_end(final_path: &[u8]) -> i32 {
    if final_path.len() < 4 {
        return 0;
    }
    c_atoi(&final_path[final_path.len() - 4..])
}

fn replace_counter(final_path: &[u8], counter: i32) -> Vec<u8> {
    let mut out = final_path[..final_path.len() - 4].to_vec();
    out.extend_from_slice(format!("{counter:04}").as_bytes());
    out
}

/// Bump the counter at the end of a final path (`EXT:162-166`). `%04d` is
/// preserved: 9999 becomes `10000`, a 5-digit suffix, exactly as in C.
pub fn increment_final(final_path: &[u8]) -> Result<Vec<u8>, Error> {
    if final_path.len() < 4 {
        return Err(Error::BrokenLink);
    }
    let next = replace_counter(final_path, counter_at_end(final_path) + 1);
    if next.len() >= PATH_MAX {
        Err(Error::NameTooLong)
    } else {
        Ok(next)
    }
}

/// Result of `decrement_link_count` on the backing file (`EXT:257-293`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Decrement {
    Rename { counter: i32, new_final: Vec<u8> },
    Delete,
}

pub fn decrement_final(final_path: &[u8]) -> Decrement {
    let counter = counter_at_end(final_path) - 1;
    if counter > 0 {
        Decrement::Rename {
            counter,
            new_final: replace_counter(final_path, counter),
        }
    } else {
        Decrement::Delete
    }
}

/// The paths involved in one emulated hard link (`EXT:130-188`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LinkPlan {
    pub first_link: bool,
    pub intermediate: Vec<u8>,
    /// Final path after the rename: `.0002` on the first link, incremented on
    /// later links.
    pub final_path: Vec<u8>,
    /// Source of the rename: the original on the first link, the old final
    /// path on later links.
    pub renamed_from: Vec<u8>,
}

/// Decide the `rename`/`symlink` chain for `link`/`linkat`.
///
/// `is_taken` answers `access(candidate, F_OK) != -1`; `existing_final` is the
/// result of `readlink(intermediate)` for an existing l2s symlink. A symlink
/// source with a non-`.l2s.` target keeps that target verbatim as the
/// intermediate base (`EXT:87-101`) — a quirk, not a path join.
pub fn plan_link(
    original: &[u8],
    source: Source<'_>,
    l2s_dir: Option<&[u8]>,
    is_taken: impl Fn(u32) -> bool,
    existing_final: Option<&[u8]>,
) -> Result<LinkPlan, Error> {
    let (base, first_link) = match source {
        Source::Directory => return Err(Error::Directory),
        Source::Symlink(target) => (truncate_path(target), !is_l2s_name(target)),
        Source::Regular => (l2s_base(original, l2s_dir)?, true),
    };
    if first_link {
        let intermediate = intermediate_with_suffix(&base, choose_intermediate_suffix(is_taken))?;
        let final_path = first_final(&intermediate)?;
        Ok(LinkPlan {
            first_link: true,
            intermediate,
            final_path,
            renamed_from: original.to_vec(),
        })
    } else {
        let old_final = existing_final.ok_or(Error::BrokenLink)?;
        let new_final = increment_final(old_final)?;
        Ok(LinkPlan {
            first_link: false,
            intermediate: base,
            final_path: new_final,
            renamed_from: old_final.to_vec(),
        })
    }
}

/// `st_nlink` reported by the stat family (`EXT:433`): `atoi` of the last four
/// bytes of the final path.
pub fn nlink_from_final(final_path: &[u8]) -> i32 {
    counter_at_end(final_path)
}

/// `link2symlink_handle_statx` (`EXT:461-486`). `has_nlink_mask` is
/// `stx_mask & STATX_NLINK`. Note the original never sets `updated_stats`, so
/// writing the result into the guest buffer remains runtime work and the
/// rewrite may be discarded.
pub fn statx_nlink(path: &[u8], has_nlink_mask: bool) -> Option<i32> {
    if !has_nlink_mask {
        return None;
    }
    let slash = path.iter().rposition(|byte| *byte == b'/')?;
    let component = &path[slash..];
    let len = component.len();
    if len < PREFIX.len() + 6 || !component.get(1..)?.starts_with(PREFIX) {
        return None;
    }
    if component[len - 5] != b'.' || !component[len - 4..].iter().all(u8::is_ascii_digit) {
        return None;
    }
    Some(c_atoi(&component[len - 4..]))
}

/// fstat path recovery strips the kernel's deleted marker (`EXT:367-369`).
pub fn strip_deleted_suffix(path: &[u8]) -> &[u8] {
    if path.len() > DELETED_SUFFIX.len() && path.ends_with(DELETED_SUFFIX) {
        &path[..path.len() - DELETED_SUFFIX.len()]
    } else {
        path
    }
}

/// `translated_path` skip list (`EXT:500-509`), keyed by guest syscall number.
/// `link`, `unlink` and `rename` families see the symlink itself; every other
/// syscall is redirected to the backing file.
pub fn skips_translated_path(abi: Abi, sysnum: u32) -> bool {
    matches!(
        proot_syscall::name(abi, sysnum),
        Some("unlink" | "unlinkat" | "link" | "linkat" | "rename" | "renameat" | "renameat2")
    )
}

/// Exactly two levels of `readlink` for a fake hard link (`EXT:514-540`).
/// `None` means readlink failed (including an over-long target) or the first
/// target is not an `.l2s.` name. The third level is deliberately absent:
/// the `#if 0` sanity check in the C source stays disabled.
pub fn translated_target(first: Option<&[u8]>, second: Option<&[u8]>) -> Option<Vec<u8>> {
    let first = first?;
    if first.len() >= PATH_MAX || !basename(first).starts_with(PREFIX) {
        return None;
    }
    let second = second?;
    if second.len() >= PATH_MAX {
        return None;
    }
    Some(second.to_vec())
}

/// Preconditions of `handle_linkat_from_proc_fd` (`EXT:555-589`).
///
/// `source` is the already-translated path, `link_target` its `readlink`
/// result, and `is_regular` the `stat` result. A missing destination or a
/// failed source `open` also falls through to normal link2symlink processing
/// in C; those are runtime inputs, not decisions.
pub fn proc_fd_copy_applies(
    source: &[u8],
    link_target: Option<&[u8]>,
    is_regular: bool,
    flags: i32,
) -> bool {
    // compare_paths() strips one trailing '/' and requires a strict prefix.
    let proc_path = source.strip_suffix(b"/").unwrap_or(source);
    flags & AT_SYMLINK_FOLLOW != 0
        && proc_path.len() > b"/proc".len()
        && proc_path.starts_with(b"/proc/")
        && link_target.is_some_and(|target| {
            target.len() >= DELETED_SUFFIX.len() && target.ends_with(DELETED_SUFFIX)
        })
        && is_regular
}

/// Past the point of no return the copy loop turns a non-positive errno into
/// `EPERM` (`EXT:607-636`).
pub fn proc_fd_failure(errno: i32) -> Failure {
    Failure::Errno(if errno > 0 { errno } else { errno::EPERM })
}

/// `unlinkat` with `AT_REMOVEDIR` skips `decrement_link_count` (`EXT:722-728`).
pub fn unlinkat_decrements(flags: i32) -> bool {
    flags & AT_REMOVEDIR == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    const ORIGINAL: &[u8] = b"/rootfs/tmp/a";
    const INTERMEDIATE: &[u8] = b"/rootfs/tmp/.l2s.a0001";
    const FINAL2: &[u8] = b"/rootfs/tmp/.l2s.a0001.0002";
    const FINAL3: &[u8] = b"/rootfs/tmp/.l2s.a0001.0003";

    #[test]
    fn e1_first_link_builds_the_three_path_chain() {
        let plan = plan_link(ORIGINAL, Source::Regular, None, |_| false, None).unwrap();
        assert_eq!(
            plan,
            LinkPlan {
                first_link: true,
                intermediate: INTERMEDIATE.to_vec(),
                final_path: FINAL2.to_vec(),
                renamed_from: ORIGINAL.to_vec(),
            }
        );
        assert_eq!(nlink_from_final(&plan.final_path), 2);
    }

    #[test]
    fn e2_second_link_increments_the_final_counter() {
        let plan = plan_link(
            ORIGINAL,
            Source::Symlink(INTERMEDIATE),
            None,
            |_| false,
            Some(FINAL2),
        )
        .unwrap();
        assert_eq!(
            plan,
            LinkPlan {
                first_link: false,
                intermediate: INTERMEDIATE.to_vec(),
                final_path: FINAL3.to_vec(),
                renamed_from: FINAL2.to_vec(),
            }
        );
        assert_eq!(nlink_from_final(&plan.final_path), 3);
    }

    #[test]
    fn e3_directory_source_is_eperm() {
        assert_eq!(
            plan_link(b"/rootfs/tmp/dir", Source::Directory, None, |_| false, None).unwrap_err(),
            Error::Directory
        );
        assert_eq!(Error::Directory.errno(), errno::EPERM);
    }

    #[test]
    fn e4_missing_source_errno_is_propagated() {
        assert_eq!(Failure::raw(errno::ENOENT).guest_errno(), errno::ENOENT);
    }

    #[test]
    fn e5_e6_failed_destination_keeps_errno_and_rollback_renames_down() {
        for err in [errno::EEXIST, errno::ENOENT] {
            let failure = Failure::Errno(err);
            assert_eq!(failure.return_value(), -err);
            assert_eq!(failure.guest_errno(), err);
        }
        // Rollback calls decrement_link_count on the original. The first
        // final is .0002, so the counter drops to 1: C renames the backing
        // file to .0001 and repoints the intermediate. The original is never
        // unlinked; contrary to research E5/E6 it stays live, not dangling.
        assert_eq!(
            decrement_final(FINAL2),
            Decrement::Rename {
                counter: 1,
                new_final: b"/rootfs/tmp/.l2s.a0001.0001".to_vec(),
            }
        );
    }

    #[test]
    fn e7_e8_decrement_renames_down_and_deletes_at_one() {
        assert_eq!(
            decrement_final(FINAL3),
            Decrement::Rename {
                counter: 2,
                new_final: FINAL2.to_vec(),
            }
        );
        // Research E8 says a count of 2 deletes the backing file, but
        // EXT:257-261 decrements first: only a count of 1 reaches Delete.
        assert_eq!(
            decrement_final(FINAL2),
            Decrement::Rename {
                counter: 1,
                new_final: b"/rootfs/tmp/.l2s.a0001.0001".to_vec(),
            }
        );
        assert_eq!(
            decrement_final(b"/rootfs/tmp/.l2s.a0001.0001"),
            Decrement::Delete
        );
    }

    #[test]
    fn e9_e10_unlinkat_removedir_is_skipped() {
        assert!(unlinkat_decrements(0));
        assert!(!unlinkat_decrements(AT_REMOVEDIR));
    }

    #[test]
    fn e11_rename_family_is_skipped_by_translated_path() {
        let abi = Abi::Arm;
        for name in [
            "unlink",
            "unlinkat",
            "link",
            "linkat",
            "rename",
            "renameat",
            "renameat2",
        ] {
            let sysnum = proot_syscall::number(abi, name).unwrap();
            assert!(skips_translated_path(abi, sysnum), "{name}");
        }
        assert!(!skips_translated_path(
            abi,
            proot_syscall::number(abi, "open").unwrap()
        ));
    }

    #[test]
    fn e12_e13_stat_nlink_comes_from_the_final_path() {
        assert_eq!(nlink_from_final(FINAL2), 2);
        assert_eq!(
            nlink_from_final(
                translated_target(Some(INTERMEDIATE), Some(FINAL2))
                    .unwrap()
                    .as_slice()
            ),
            2
        );
    }

    #[test]
    fn e14_e15_e16_other_syscalls_see_the_backing_file() {
        let abi = Abi::Arm;
        for name in ["readlink", "readlinkat", "open", "stat"] {
            let sysnum = proot_syscall::number(abi, name).unwrap();
            assert!(!skips_translated_path(abi, sysnum), "{name}");
        }
        assert_eq!(
            translated_target(Some(INTERMEDIATE), Some(FINAL2)),
            Some(FINAL2.to_vec())
        );
        // Non-l2s targets and missing second-level links are left untouched.
        assert_eq!(
            translated_target(Some(b"/rootfs/tmp/plain"), Some(FINAL2)),
            None
        );
        assert_eq!(translated_target(Some(INTERMEDIATE), None), None);
    }

    #[test]
    fn e17_statx_only_rewrites_final_paths() {
        assert_eq!(statx_nlink(INTERMEDIATE, true), None); // ends in "a0001"
        assert_eq!(statx_nlink(FINAL2, true), Some(2));
        assert_eq!(statx_nlink(FINAL2, false), None);
        assert_eq!(statx_nlink(b"no-slash.l2s.a.0002", true), None);
    }

    #[test]
    fn e18_e19_e20_proc_fd_copy_preconditions() {
        assert!(proc_fd_copy_applies(
            b"/proc/42/fd/3",
            Some(b"/rootfs/tmp/a (deleted)"),
            true,
            AT_SYMLINK_FOLLOW
        ));
        assert!(!proc_fd_copy_applies(
            b"/rootfs/tmp/a",
            Some(b"/rootfs/tmp/a (deleted)"),
            true,
            AT_SYMLINK_FOLLOW
        ));
        assert!(!proc_fd_copy_applies(
            b"/proc/",
            Some(b"/rootfs/tmp/a (deleted)"),
            true,
            AT_SYMLINK_FOLLOW
        ));
        assert!(!proc_fd_copy_applies(
            b"/proc/42/fd/3",
            Some(b"/rootfs/tmp/a"),
            true,
            AT_SYMLINK_FOLLOW
        ));
        assert!(!proc_fd_copy_applies(
            b"/proc/42/fd/3",
            Some(b"/rootfs/tmp/a (deleted)"),
            false,
            AT_SYMLINK_FOLLOW
        ));
        assert!(!proc_fd_copy_applies(
            b"/proc/42/fd/3",
            Some(b"/rootfs/tmp/a (deleted)"),
            true,
            0
        ));
        assert_eq!(proc_fd_failure(18).guest_errno(), 18);
        assert_eq!(proc_fd_failure(0).guest_errno(), errno::EPERM);
    }

    #[test]
    fn e21_dirfd_paths_are_already_canonicalized_by_the_runtime() {
        assert_eq!(
            l2s_base(b"/rootfs/tmp/a", None).unwrap(),
            b"/rootfs/tmp/.l2s.a"
        );
        assert_eq!(l2s_base(b"./a", None).unwrap(), b"./.l2s.a".as_slice());
    }

    #[test]
    fn e22_canonical_symlink_source_uses_the_existing_final() {
        // PRoot resolves "sym" -> target before the extension; the canonical
        // target is an l2s intermediate, so this is an additional link.
        assert!(
            !plan_link(
                ORIGINAL,
                Source::Symlink(INTERMEDIATE),
                None,
                |_| false,
                Some(FINAL2)
            )
            .unwrap()
            .first_link
        );
    }

    #[test]
    fn e23_non_prefix_symlink_target_is_used_verbatim() {
        let plan = plan_link(
            ORIGINAL,
            Source::Symlink(b"data.txt"),
            None,
            |_| false,
            None,
        )
        .unwrap();
        assert!(plan.first_link);
        assert_eq!(plan.intermediate, b"data.txt0001");
        assert_eq!(plan.final_path, b"data.txt0001.0002");
    }

    #[test]
    fn e24_rename_failure_is_masked_as_eperm() {
        let failure = Failure::bare_minus_one();
        assert_eq!(failure.return_value(), -1);
        assert_eq!(failure.guest_errno(), errno::EPERM);
    }

    #[test]
    fn e25_exhaustion_reuses_0999_not_1000() {
        assert_eq!(
            choose_intermediate_suffix(|_| true),
            MAX_INTERMEDIATE_SUFFIX
        );
        assert_eq!(choose_intermediate_suffix(|n| n == 1), 2);
        assert_eq!(choose_intermediate_suffix(|_| false), 1);
    }

    #[test]
    fn e26_overlong_paths_are_enametoolong() {
        let long = vec![b'a'; PATH_MAX];
        assert_eq!(l2s_base(&long, None), Err(Error::NameTooLong));
        assert_eq!(l2s_base(ORIGINAL, Some(&long)), Err(Error::NameTooLong));
        assert_eq!(translated_target(Some(&long), Some(FINAL2)), None);
        assert_eq!(translated_target(Some(INTERMEDIATE), Some(&long)), None);
    }

    #[test]
    fn counter_helpers_keep_the_c_atoi_quirks() {
        assert_eq!(counter_at_end(b"/x/.l2s.a0001.00x2"), 0);
        assert_eq!(
            increment_final(b"/x/.l2s.a0001.9999").unwrap(),
            b"/x/.l2s.a0001.10000"
        );
        assert_eq!(
            increment_final(b"/x/.l2s.a0001.0002").unwrap(),
            b"/x/.l2s.a0001.0003".to_vec()
        );
        assert_eq!(strip_deleted_suffix(b"/tmp/a (deleted)"), b"/tmp/a");
        assert_eq!(strip_deleted_suffix(b"/tmp/a"), b"/tmp/a");
        assert_eq!(c_atoi(b"-001"), -1);
    }
}
