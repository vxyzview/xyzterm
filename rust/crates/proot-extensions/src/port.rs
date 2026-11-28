//! Pure Rust port of PRoot's `fix_symlink_size` and `hidden_files` extensions.
//!
//! The original callbacks operate on a live ptrace `Tracee`; the pure decisions
//! they make are ported here.  The tracee-facing glue (register reads/writes,
//! tracee memory reads/writes, guestfs checks and chained syscalls) stays in C.

#![forbid(unsafe_code)]

use std::ffi::{c_int, c_longlong, c_uint};
use std::os::unix::ffi::OsStrExt;
use std::path::Path;

/// `PATH_MAX` as used by the original C sources.
const PATH_MAX: usize = 4096;

/// `HIDDEN_PREFIX` from `extension/hidden_files/hidden_files.c`.
pub const HIDDEN_PREFIX: &[u8] = b".proot";

/// Sysnums the two C callbacks register in `INITIALIZATION`.  Their numeric
/// values are architecture-dependent (`syscall/sysnums-*.h`), so the C names
/// are kept here rather than a host-specific number.
pub const FIX_SYMLINK_SIZE_SYSNUMS: &[&str] = &["PR_lstat", "PR_lstat64"];
pub const HIDDEN_FILES_SYSNUMS: &[&str] = &["PR_getdents", "PR_getdents64"];

/// Port of `hasprefix()` in `extension/hidden_files/hidden_files.c`.
///
/// C strings arrive without their NUL; a `None` means `NULL` and matches nothing
/// (the original would dereference `NULL`, which the harness never does).
fn hasprefix(prefix: Option<&[u8]>, str: Option<&[u8]>) -> bool {
    let prefix = prefix.unwrap_or(&[]);
    let str = str.unwrap_or(&[]);
    for (i, &byte) in prefix.iter().enumerate() {
        if i >= str.len() || str[i] != byte {
            return false;
        }
    }
    true
}

/// Port of `mybcopy()` in `extension/hidden_files/hidden_files.c`.
///
/// The C harness caps `num` at the destination size; `num` is the source length.
fn mybcopy(src: &[u8], dst: &mut [u8]) -> c_uint {
    let num = src.len().min(dst.len());
    dst[..num].copy_from_slice(&src[..num]);
    num as c_uint
}

/// Read a little-endian `unsigned short` like the harness' `memcpy` does;
/// bytes past the buffer read as zero (the C harness sees zero padding there).
fn reclen_at(orig: &[u8], at: usize) -> usize {
    let mut bytes = [0u8; 2];
    for (i, byte) in bytes.iter_mut().enumerate() {
        if let Some(source) = orig.get(at + i) {
            *byte = *source;
        }
    }
    u16::from_le_bytes(bytes) as usize
}

/// Port of the pure filtering loop of `handle_getdents()`.
///
/// The C `linux_dirent`/`linux_dirent64` casts are replaced by their offsets on
/// a 64-bit host (16 for `d_reclen`, 19/18 for `d_name`).  Harness safety caps
/// are kept: a zero `d_reclen` stops the loop, and writes stop at `copy.len()`.
pub fn ra_hf_filter(is64: c_int, orig: &[u8], copy: &mut [u8]) -> c_uint {
    let name_offset = if is64 != 0 { 19 } else { 18 };
    let mut ptr = 0usize;
    let mut nleft = 0usize;

    while ptr < orig.len() {
        let reclen = reclen_at(orig, ptr + 16);
        let name = ptr + name_offset;
        if reclen == 0 || name > orig.len() {
            break;
        }
        let available = orig.len() - name;
        let mut name_len = 0usize;
        while name_len < available && name_len < PATH_MAX - 1 && orig[name + name_len] != 0 {
            name_len += 1;
        }

        if !hasprefix(Some(HIDDEN_PREFIX), Some(&orig[name..name + name_len])) {
            let mut n = reclen;
            if n > available + 19 {
                n = available + 19;
            }
            if n > copy.len() - nleft {
                n = copy.len() - nleft;
            }
            let written = mybcopy(&orig[ptr..ptr + n], &mut copy[nleft..nleft + n]);
            nleft += written as usize;
        }
        ptr += reclen;
    }
    nleft as c_uint
}

/// Port of the pure part of `handle_sysexit_end()`: the corrected `st_size`
/// for a symlink.  Returns `-1` when the C code would return `-1`, `0` when it
/// leaves `st_size` alone, otherwise the byte count from `readlink(2)`.
pub fn ra_fs_corrected_size(path: Option<&[u8]>) -> c_longlong {
    let Some(path) = path else {
        return -1;
    };
    let path = Path::new(std::ffi::OsStr::from_bytes(path));
    let Ok(metadata) = std::fs::symlink_metadata(path) else {
        return -1;
    };
    if !metadata.file_type().is_symlink() {
        return 0;
    }
    let Ok(target) = std::fs::read_link(path) else {
        return -1;
    };
    target.as_os_str().as_bytes().len().min(PATH_MAX) as c_longlong
}

/// Port of `ra_hf_hasprefix()` (the C harness wrapper around the original static helper).
pub fn ra_hf_hasprefix(prefix: Option<&[u8]>, str: Option<&[u8]>) -> c_uint {
    hasprefix(prefix, str) as c_uint
}

/// Port of `ra_hf_mybcopy()` (the C harness wrapper around the original static helper).
pub fn ra_hf_mybcopy(src: &[u8], dst: &mut [u8]) -> c_uint {
    mybcopy(src, dst)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prefix_matches_only_a_leading_prefix() {
        assert_eq!(ra_hf_hasprefix(Some(b".proot"), Some(b".proot-foo")), 1);
        assert_eq!(ra_hf_hasprefix(Some(b".proot"), Some(b".proo")), 0);
        assert_eq!(ra_hf_hasprefix(Some(b".proot"), Some(b"x.proot")), 0);
        assert_eq!(ra_hf_hasprefix(Some(b".proot"), Some(b"")), 0);
        assert_eq!(ra_hf_hasprefix(Some(b""), Some(b"anything")), 1);
        assert_eq!(ra_hf_hasprefix(Some(b".proot"), None), 0);
        assert_eq!(ra_hf_hasprefix(None, Some(b".proot")), 1);
    }

    #[test]
    fn mybcopy_copies_up_to_the_destination_size() {
        let mut dst = [0u8; 4];
        assert_eq!(ra_hf_mybcopy(&[1, 2, 3], &mut dst), 3);
        assert_eq!(dst, [1, 2, 3, 0]);
        let mut small = [0u8; 2];
        assert_eq!(ra_hf_mybcopy(&[1, 2, 3], &mut small), 2);
        assert_eq!(small, [1, 2]);
    }

    fn entry(is64: bool, name: &[u8]) -> Vec<u8> {
        let name_offset = if is64 { 19 } else { 18 };
        let mut raw = name.to_vec();
        raw.push(0);
        let reclen = (name_offset + raw.len()).div_ceil(8) * 8;
        let mut record = vec![0u8; reclen];
        record[0] = 1; // d_ino, low byte
        record[16] = reclen as u8;
        record[17] = (reclen >> 8) as u8;
        record[name_offset..name_offset + raw.len()].copy_from_slice(&raw);
        record
    }

    #[test]
    fn filter_keeps_visible_entries_and_drops_hidden_ones() {
        for is64 in [0, 1] {
            let mut orig = entry(is64 != 0, b"visible.txt");
            orig.extend(entry(is64 != 0, b".proot-thing"));
            orig.extend(entry(is64 != 0, b"z"));
            let mut copy = [0u8; 128];
            let nleft = ra_hf_filter(is64, &orig, &mut copy);
            assert_eq!(nleft, 56);
            assert_eq!(&copy[..32], &orig[..32]); // whole record, header included
            assert_eq!(copy[32..56], orig[64..88]); // hidden record skipped
            assert_eq!(copy[56..], [0u8; 72]);
        }
    }

    #[test]
    fn filter_returns_zero_when_every_entry_is_hidden() {
        for is64 in [0, 1] {
            let mut orig = entry(is64 != 0, b".proot1");
            orig.extend(entry(is64 != 0, b".proot2"));
            let mut copy = [0u8; 128];
            assert_eq!(ra_hf_filter(is64, &orig, &mut copy), 0);
            assert!(copy.iter().all(|byte| *byte == 0));
        }
    }

    #[test]
    fn filter_keeps_names_that_only_start_like_the_prefix() {
        for is64 in [0, 1] {
            let first = entry(is64 != 0, b".proo-not-hidden");
            let second = entry(is64 != 0, b"plain");
            let expected = (first.len() + second.len()) as c_uint;
            let mut orig = first;
            orig.extend(second);
            let mut copy = [0u8; 128];
            assert_eq!(ra_hf_filter(is64, &orig, &mut copy), expected);
        }
    }

    #[test]
    fn filter_stops_on_zero_reclen_and_short_records() {
        let mut copy = [0u8; 64];
        assert_eq!(ra_hf_filter(1, &[0xab; 8], &mut copy), 0);
        let mut orig = vec![0u8; 24];
        orig[16] = 0;
        assert_eq!(ra_hf_filter(1, &orig, &mut copy), 0);
    }

    #[test]
    fn corrected_size_handles_symlinks_and_regular_files() {
        let root = std::env::temp_dir().join("proot-extensions-tests");
        let _ = std::fs::create_dir_all(&root);
        let link = root.join("a-link");
        let regular = root.join("a-file");
        let missing = root.join("does-not-exist");
        let _ = std::fs::remove_file(&link);
        let _ = std::fs::remove_file(&regular);
        assert!(std::os::unix::fs::symlink("abc", &link).is_ok());
        assert!(std::fs::write(&regular, b"data").is_ok());

        assert_eq!(ra_fs_corrected_size(Some(link.as_os_str().as_bytes())), 3);
        assert_eq!(
            ra_fs_corrected_size(Some(regular.as_os_str().as_bytes())),
            0
        );
        assert_eq!(
            ra_fs_corrected_size(Some(missing.as_os_str().as_bytes())),
            -1
        );
        assert_eq!(ra_fs_corrected_size(None), -1);
    }
}
