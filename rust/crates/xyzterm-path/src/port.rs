//! The Rust port of `path/`: safe Rust; `lib.rs` exports each function under its C name.
//!
//! C has no error channel: return what the C code returns, and never panic
//! (a panic would abort at the C boundary).

#![forbid(unsafe_code)]

use std::ffi::c_int;

/// `PATHS_ARE_EQUAL` = 0, `PATH1_IS_PREFIX` = 1, `PATH2_IS_PREFIX` = 2,
/// `PATHS_ARE_NOT_COMPARABLE` = 3 (`path/path.h:67`).
const EQUAL: c_int = 0;
const PATH1_IS_PREFIX: c_int = 1;
const PATH2_IS_PREFIX: c_int = 2;
const NOT_COMPARABLE: c_int = 3;

/// Port of `Comparison compare_paths2(const char *path1, size_t length1, const char *path2, size_t length2)` (`path/path.c:536`).
///
/// `path1`: the bytes of the C string, without its NUL; `None` for `NULL`.
///
/// `path2`: the bytes of the C string, without its NUL; `None` for `NULL`.
pub fn compare_paths2(
    path1: Option<&[u8]>,
    length1: usize,
    path2: Option<&[u8]>,
    length2: usize,
) -> c_int {
    let (Some(p1), Some(p2)) = (path1, path2) else {
        // The C code dereferences NULL; recorded cases for that are crashes.
        return NOT_COMPARABLE;
    };
    if length1 == 0 || length2 == 0 {
        return NOT_COMPARABLE;
    }

    // Trailing '/' is ignored for the comparison.
    let mut len1 = length1;
    let mut len2 = length2;
    if p1.get(len1 - 1) == Some(&b'/') {
        len1 -= 1;
    }
    if p2.get(len2 - 1) == Some(&b'/') {
        len2 -= 1;
    }

    let (len_min, sentinel) = if len1 < len2 {
        (len1, p2.get(len1).copied().unwrap_or(0))
    } else {
        (len2, p1.get(len2).copied().unwrap_or(0))
    };
    // The character just past the shorter path must be a separator or the NUL
    // terminator: "/a" is not a prefix of "/ab".
    if sentinel != b'/' && sentinel != 0 {
        return NOT_COMPARABLE;
    }

    // strncmp(path1, path2, len_min); it also stops at the first NUL.
    let (Some(a), Some(b)) = (p1.get(..len_min), p2.get(..len_min)) else {
        return NOT_COMPARABLE;
    };
    for i in 0..len_min {
        if a[i] != b[i] || a[i] == 0 {
            break;
        }
    }
    if a != b {
        // `a == b` is the exact strncmp result for non-NUL bytes; a leading
        // NUL in both strings also compares equal, as strncmp does.
        return NOT_COMPARABLE;
    }

    if len1 == len2 {
        EQUAL
    } else if len1 < len2 {
        PATH1_IS_PREFIX
    } else {
        PATH2_IS_PREFIX
    }
}

/// Port of `Comparison compare_paths(const char *path1, const char *path2)` (`path/path.c:589`).
///
/// `path1`: the bytes of the C string, without its NUL; `None` for `NULL`.
///
/// `path2`: the bytes of the C string, without its NUL; `None` for `NULL`.
pub fn compare_paths(path1: Option<&[u8]>, path2: Option<&[u8]>) -> c_int {
    match (path1, path2) {
        (Some(a), Some(b)) => compare_paths2(Some(a), a.len(), Some(b), b.len()),
        _ => NOT_COMPARABLE,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn equal_paths() {
        assert_eq!(compare_paths(Some(b"/tmp"), Some(b"/tmp")), EQUAL);
        assert_eq!(compare_paths(Some(b"/tmp/"), Some(b"/tmp")), EQUAL);
        assert_eq!(compare_paths(Some(b"/"), Some(b"/")), EQUAL);
    }

    #[test]
    fn prefixes() {
        assert_eq!(
            compare_paths(Some(b"/tmp"), Some(b"/tmp/x")),
            PATH1_IS_PREFIX
        );
        assert_eq!(
            compare_paths(Some(b"/tmp/x"), Some(b"/tmp")),
            PATH2_IS_PREFIX
        );
        assert_eq!(compare_paths(Some(b"/"), Some(b"/tmp")), PATH1_IS_PREFIX);
    }

    #[test]
    fn not_comparable() {
        assert_eq!(compare_paths(Some(b"/a"), Some(b"/ab")), NOT_COMPARABLE);
        assert_eq!(compare_paths(Some(b"/tmp"), Some(b"/usr")), NOT_COMPARABLE);
        assert_eq!(
            compare_paths2(Some(b"/tmp"), 0, Some(b"/tmp"), 4),
            NOT_COMPARABLE
        );
    }

    #[test]
    fn explicit_lengths_and_nul_stop() {
        assert_eq!(
            compare_paths2(Some(b"/tmp\0X"), 5, Some(b"/tmp"), 4),
            PATH2_IS_PREFIX
        );
        assert_eq!(
            compare_paths2(Some(b"/a\0b"), 3, Some(b"/a"), 2),
            PATH2_IS_PREFIX
        );
        assert_eq!(
            compare_paths2(Some(b"/tmpXYZ"), 4, Some(b"/tmp"), 4),
            NOT_COMPARABLE
        );
    }
}
