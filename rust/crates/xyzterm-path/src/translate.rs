//! Pure guest-path canonicalization, ported from PRoot's `canonicalize()`
//! (`features/terminal/proot/src/main/cpp/path/canon.c`) and the two-path case
//! of `join_paths()`/`pop_component()`/`next_component()` (`path/path.c`,
//! `path/canon.c`). Behavior was recorded from the exact original C with a
//! zeroed `Tracee`, no bindings, and `lstat` reporting every component as a
//! directory: this is the lexical, symlink-free core used for guest↔host path
//! translation, not a full `realpath(3)` replacement.
//!
//! Not ported (all tracee/filesystem-dependent, see `canon.c`): binding
//! substitution, symlink dereferencing (including `/proc` emulation and the
//! `/linkerconfig` workaround), glue creation and extension notifications.
//! `deref_final` is accepted for ABI parity but is inert here: with no symlinks
//! there is nothing to dereference. Callers that need those semantics must keep
//! using the C `canonicalize()`.
//!
//! # Safety / quirks kept from the original
//!
//! - `user_path` is a C string: bytes after the first NUL are ignored. `None`
//!   returns `-EINVAL` instead of the C `assert` (no panic at an FFI boundary).
//! - `guest_path` must be NUL-terminated; an unterminated `PATH_MAX` buffer
//!   gives `-ENAMETOOLONG`.
//! - A relative path needs an absolute base in `guest_path`; otherwise
//!   `-EINVAL`. An empty `user_path` is "relative" and gets a trailing `/`
//!   (the empty component is joined with a separator).
//! - `..` clamps at `/`; a final `.` overrides a final slash (`/a/./` → `/a/.`).
//! - `join_paths()` leaves partially joined bytes in `guest_path` on
//!   `-ENAMETOOLONG`; only the outermost call (`recursion_level == 0`)
//!   restores a trailing `/` or `/.`.
//! - `MAXSYMLINKS` is platform-defined; 20 is the value from glibc's
//!   `<sys/param.h>` where the original was recorded.

#![forbid(unsafe_code)]

use std::ffi::{c_int, c_uint};

/// Linux `PATH_MAX`; the buffer passed to the C function is `char[PATH_MAX]`.
pub const PATH_MAX: usize = 4096;
/// Linux `NAME_MAX`; a component of this length is `ENAMETOOLONG`.
pub const NAME_MAX: usize = 255;
/// `MAXSYMLINKS` from `<sys/param.h>` on the recording host.
pub const MAXSYMLINKS: c_uint = 20;

const EINVAL: c_int = -22;
const ELOOP: c_int = -40;
const ENAMETOOLONG: c_int = -36;

#[derive(Clone, Copy, PartialEq, Eq)]
enum Finality {
    NotFinal,
    Normal,
    Slash,
    Dot,
}

impl Finality {
    fn is_final(self) -> bool {
        self != Self::NotFinal
    }
}

fn c_len(buf: &[u8]) -> usize {
    buf.iter().position(|&b| b == 0).unwrap_or(buf.len())
}

/// `join_paths(2, result, path1, path2)` (`path/path.c:51`): the two-path case
/// of the C variadic join, including its PATH_MAX cutoff and partial writes on
/// error.
fn join_paths2(result: &mut [u8], path1: &[u8], path2: &[u8]) -> c_int {
    let limit = PATH_MAX.min(result.len());
    result[0] = 0;
    let mut length = 0usize;

    for path in [path1, path2] {
        let path = &path[..c_len(path)];
        let path_length = path.len();

        // An empty string still takes C's first branch when the result has no
        // trailing '/': that is how FINAL_SLASH re-appends the final separator.
        // C reads path[0] == '\0' for an empty string: that is not a '/'.
        let starts_with_slash = path.first() == Some(&b'/');
        let (new_length, skip, separator) =
            if length > 0 && result[length - 1] != b'/' && !starts_with_slash {
                (length + path_length + 1, 0, true)
            } else if length > 0 && result[length - 1] == b'/' && starts_with_slash {
                (length + path_length - 1, 1, false)
            } else {
                (length + path_length, 0, false)
            };

        if new_length + 1 >= limit {
            return ENAMETOOLONG;
        }
        if separator {
            result[length] = b'/';
            length += 1;
        }
        result[length..length + path_length - skip].copy_from_slice(&path[skip..]);
        length = new_length;
        result[length] = 0;
    }

    0
}

/// `pop_component()` (`path/canon.c:44`); a bare "/" has no component to pop.
fn pop_component(path: &mut [u8]) {
    let length = c_len(path);
    if length == 0 {
        return;
    }
    let mut offset = length - 1;
    if offset == 0 {
        return;
    }
    while offset > 1 && path[offset] == b'/' {
        offset -= 1;
    }
    while offset > 1 && path[offset] != b'/' {
        offset -= 1;
    }
    path[offset] = 0;
}

/// `next_component()` (`path/canon.c:87`): extract one component, advance
/// `cursor` past its separators, and report whether it ended the path.
fn next_component<'a>(path: &'a [u8], cursor: &mut usize) -> Result<(&'a [u8], Finality), c_int> {
    let end = path.len();
    while *cursor < end && path[*cursor] == b'/' {
        *cursor += 1;
    }
    let start = *cursor;
    while *cursor < end && path[*cursor] != b'/' {
        *cursor += 1;
    }
    let length = *cursor - start;
    if length >= NAME_MAX {
        return Err(ENAMETOOLONG);
    }
    let component = &path[start..*cursor];

    let want_dir = path.get(*cursor) == Some(&b'/');
    while *cursor < end && path[*cursor] == b'/' {
        *cursor += 1;
    }
    let finality = if *cursor == end {
        if want_dir {
            Finality::Slash
        } else {
            Finality::Normal
        }
    } else {
        Finality::NotFinal
    };
    Ok((component, finality))
}

/// Port of `int canonicalize(Tracee *, const char *, bool, char[PATH_MAX], unsigned)`
/// (`path/canon.c:199`) in its symlink-free/no-binding mode.
///
/// `guest_path` is the in/out buffer: on entry it holds the absolute base for a
/// relative `user_path` (anything for an absolute one); on success it holds the
/// canonical path. Error paths keep the C behavior of leaving partially joined
/// content in the buffer.
pub fn canonicalize(
    user_path: Option<&[u8]>,
    _deref_final: c_int,
    guest_path: &mut [u8],
    recursion_level: c_uint,
) -> c_int {
    // The original checks the recursion limit before any dereference.
    if recursion_level > MAXSYMLINKS {
        return ELOOP;
    }
    let Some(user_path) = user_path else {
        // The C version asserts; never panic when called from C.
        return EINVAL;
    };
    let user_path = &user_path[..c_len(user_path)];
    if guest_path.len() < 2 || c_len(guest_path) >= PATH_MAX {
        return ENAMETOOLONG;
    }

    if user_path.first() != Some(&b'/') {
        if guest_path.first() != Some(&b'/') {
            return EINVAL;
        }
    } else {
        guest_path[0] = b'/';
        guest_path[1] = 0;
    }

    let mut scratch = vec![0u8; guest_path.len()];
    let mut cursor = 0usize;
    let mut finality = Finality::NotFinal;
    while !finality.is_final() {
        let (component, next) = match next_component(user_path, &mut cursor) {
            Ok(parts) => parts,
            Err(status) => return status,
        };
        finality = next;

        if component == b"." {
            if finality.is_final() {
                finality = Finality::Dot;
            }
            continue;
        }
        if component == b".." {
            pop_component(guest_path);
            if finality.is_final() {
                finality = Finality::Slash;
            }
            continue;
        }

        let status = join_paths2(&mut scratch, &guest_path[..c_len(guest_path)], component);
        if status < 0 {
            return status;
        }
        // With no bindings and no symlinks, substitute_binding_stat() returns 0,
        // so the C code joins the component back into guest_path and continues.
        let end = c_len(&scratch);
        guest_path[..=end].copy_from_slice(&scratch[..=end]);
    }

    // Only the outermost call restores the trailing "/" or "/." finality.
    if recursion_level == 0 {
        let suffix: &[u8] = match finality {
            Finality::NotFinal | Finality::Normal => return 0,
            Finality::Slash => b"",
            Finality::Dot => b".",
        };
        let base = guest_path[..c_len(guest_path)].to_vec();
        let status = join_paths2(guest_path, &base, suffix);
        if status < 0 {
            return status;
        }
    }

    0
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(user: &[u8], base: &[u8], deref: c_int, recursion: c_uint) -> (c_int, Vec<u8>) {
        let mut guest = vec![0u8; PATH_MAX];
        guest[..base.len()].copy_from_slice(base);
        let status = canonicalize(Some(user), deref, &mut guest, recursion);
        let out = guest[..c_len(&guest)].to_vec();
        (status, out)
    }

    fn canonical(user: &[u8]) -> Vec<u8> {
        let (status, out) = run(user, b"", 0, 0);
        assert_eq!(
            status,
            0,
            "canonicalizing {:?}",
            String::from_utf8_lossy(user)
        );
        out
    }

    #[test]
    fn absolute_paths_and_trailing_slashes() {
        assert_eq!(canonical(b"/tmp/foo"), b"/tmp/foo");
        assert_eq!(canonical(b"/tmp/foo/"), b"/tmp/foo/");
        assert_eq!(canonical(b"/"), b"/");
        assert_eq!(canonical(b"///"), b"/");
        assert_eq!(canonical(b"//tmp///foo"), b"/tmp/foo");
        assert_eq!(canonical(b"/a//b///"), b"/a/b/");
        assert_eq!(canonical(b"/a b/c d"), b"/a b/c d");
        assert_eq!(canonical(b"/proc/self/cwd"), b"/proc/self/cwd");
    }

    #[test]
    fn dot_components_keep_the_original_finality() {
        assert_eq!(canonical(b"/tmp/./foo"), b"/tmp/foo");
        assert_eq!(canonical(b"/tmp/foo/."), b"/tmp/foo/.");
        assert_eq!(canonical(b"/."), b"/.");
        assert_eq!(canonical(b"/a/./"), b"/a/.");
        assert_eq!(canonical(b"/././."), b"/.");
        assert_eq!(canonical(b"/a/.//b"), b"/a/b");
        assert_eq!(canonical(b"/tmp/./../x/"), b"/x/");
    }

    #[test]
    fn parent_components_clamp_at_root() {
        assert_eq!(canonical(b"/tmp/foo/.."), b"/tmp/");
        assert_eq!(canonical(b"/.."), b"/");
        assert_eq!(canonical(b"/tmp/../.."), b"/");
        assert_eq!(canonical(b"/a/b/c/../../../../.."), b"/");
        assert_eq!(canonical(b"/a/b/../../c"), b"/c");
        assert_eq!(canonical(b"/a/../../b"), b"/b");
        assert_eq!(canonical(b"/a/../"), b"/");
        assert_eq!(canonical(b"/tmp/foo//../bar"), b"/tmp/bar");
    }

    #[test]
    fn relative_paths_need_an_absolute_base() {
        assert_eq!(
            run(b"foo/bar", b"/base", 0, 0),
            (0, b"/base/foo/bar".to_vec())
        );
        assert_eq!(run(b"../x", b"/base", 0, 0), (0, b"/x".to_vec()));
        assert_eq!(
            run(b"a/../b", b"/base/start", 0, 0),
            (0, b"/base/start/b".to_vec())
        );
        assert_eq!(run(b"x", b"/base/", 0, 0), (0, b"/base/x".to_vec()));
        assert_eq!(run(b"foo", b"base", 0, 0), (EINVAL, b"base".to_vec()));
        assert_eq!(run(b"../x", b"", 0, 0).0, EINVAL);
    }

    #[test]
    fn empty_user_path_is_a_relative_empty_component() {
        assert_eq!(run(b"", b"", 0, 0).0, EINVAL);
        assert_eq!(run(b"", b"/base", 0, 0), (0, b"/base/".to_vec()));
        assert_eq!(run(b"", b"/base/", 0, 0), (0, b"/base/".to_vec()));
        assert_eq!(canonicalize(None, 0, &mut vec![0u8; PATH_MAX], 0), EINVAL);
    }

    #[test]
    fn deref_final_is_inert_without_symlinks() {
        assert_eq!(run(b"/tmp/foo", b"", 0, 0), run(b"/tmp/foo", b"", 1, 0));
        assert_eq!(run(b"/tmp/foo/", b"", 0, 0), run(b"/tmp/foo/", b"", 1, 0));
    }

    #[test]
    fn component_and_total_length_limits() {
        let ok_component = format!("/{}", "a".repeat(NAME_MAX - 1));
        assert_eq!(canonical(ok_component.as_bytes()).len(), NAME_MAX);
        let too_long = format!("/{}", "a".repeat(NAME_MAX));
        assert_eq!(
            run(too_long.as_bytes(), b"", 0, 0),
            (ENAMETOOLONG, b"/".to_vec())
        );

        let long = format!("/{}", vec!["b".repeat(200); 21].join("/"));
        let (status, partial) = run(long.as_bytes(), b"", 0, 0);
        assert_eq!(status, ENAMETOOLONG);
        assert!(partial.len() > 4000 && partial.len() < PATH_MAX);

        let mut unterminated = vec![b'A'; PATH_MAX];
        assert_eq!(
            canonicalize(Some(b"/x"), 0, &mut unterminated, 0),
            ENAMETOOLONG
        );
        assert_eq!(unterminated[0], b'A');

        let mut nearly_full = vec![b'A'; PATH_MAX];
        nearly_full[4089] = 0;
        assert_eq!(canonicalize(Some(b"/x"), 0, &mut nearly_full, 0), 0);
        assert_eq!(&nearly_full[..3], b"/x\0");
    }

    #[test]
    fn recursion_limit_and_finality_suffix() {
        assert_eq!(run(b"/tmp/foo/", b"", 0, 0), (0, b"/tmp/foo/".to_vec()));
        // The suffix is only restored by the outermost call.
        assert_eq!(run(b"/tmp/foo/", b"", 0, 20), (0, b"/tmp/foo".to_vec()));
        assert_eq!(run(b"/tmp/foo", b"", 0, 21).0, ELOOP);
        assert_eq!(run(b"/tmp/foo", b"", 0, 40).0, ELOOP);
        assert_eq!(canonicalize(Some(b"/x"), 0, &mut vec![0u8; PATH_MAX], 0), 0);
    }
}
