//! Manual Rust port of the link2symlink compatibility layer.
//!
//! The C++ shim (`features/terminal/link2symlink/.../link2symlink.cpp`) redirects
//! `link`/`linkat` into `symlink`/`symlinkat` using an absolute source path. The
//! Proot extension adds the `.l2s.` intermediate/final file chain. Behavior is
//! characterized in `rust/research/link2symlink-semantics.md`; no C/C++ code is
//! retained. This module holds the pure path/name logic; the syscall plumbing
//! belongs to the Proot runtime (unit U14).

#![forbid(unsafe_code)]

/// PATH_MAX as used by the Android libc calls in the original shim.
pub const PATH_MAX: usize = 4096;
pub const AT_EMPTY_PATH: i32 = 0x1000;
pub const AT_SYMLINK_FOLLOW: i32 = 0x400;
pub const PREFIX: &[u8] = b".l2s.";

/// The highest counter the original tries before giving up (`EXT:132-136`).
pub const MAX_COUNTER: u32 = 999;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LinkError {
    /// The original holds the counter in four decimal digits.
    CounterExhausted,
    /// A name or path did not fit after truncation-free checks.
    NameTooLong,
}

fn truncated(value: &[u8]) -> Vec<u8> {
    // snprintf(out, PATH_MAX, ...) writes at most PATH_MAX-1 bytes plus NUL.
    // snprintf truncates bytes, not characters.
    value[..value.len().min(PATH_MAX - 1)].to_vec()
}

/// The C++ shim's `resolve_to_absolute` (`CPP:32-81`).
///
/// `base` is the current working directory, or the target of
/// `/proc/self/fd/<dirfd>` when the caller resolved a directory descriptor.
/// Absolute paths are copied verbatim: no `..`, symlink or guest/host
/// resolution happens here.
pub fn resolve_to_absolute(base: &[u8], path: &[u8], flags: i32) -> Vec<u8> {
    if path.is_empty() && flags & AT_EMPTY_PATH != 0 {
        return truncated(base);
    }
    if path.first() == Some(&b'/') {
        return truncated(path);
    }
    let mut joined = Vec::with_capacity(base.len() + 1 + path.len());
    joined.extend_from_slice(base);
    if base.last() != Some(&b'/') {
        joined.push(b'/');
    }
    joined.extend_from_slice(path);
    truncated(&joined)
}

/// `<dir>/.l2s.<name>` — the intermediate symlink for the first hardlink.
pub fn intermediate_name(dir: &[u8], name: &[u8]) -> Result<Vec<u8>, LinkError> {
    let mut out = Vec::with_capacity(dir.len() + PREFIX.len() + name.len() + 1);
    out.extend_from_slice(dir);
    if dir.last() != Some(&b'/') {
        out.push(b'/');
    }
    out.extend_from_slice(PREFIX);
    out.extend_from_slice(name);
    if out.len() >= PATH_MAX {
        return Err(LinkError::NameTooLong);
    }
    Ok(out)
}

/// `<intermediate>.<counter:04>` — the real file holding the content.
pub fn final_name(intermediate: &[u8], counter: u32) -> Result<Vec<u8>, LinkError> {
    if counter == 0 || counter > MAX_COUNTER {
        return Err(LinkError::CounterExhausted);
    }
    let mut out = intermediate.to_vec();
    out.push(b'.');
    out.extend_from_slice(format!("{counter:04}").as_bytes());
    if out.len() >= PATH_MAX {
        return Err(LinkError::NameTooLong);
    }
    Ok(out)
}

/// Counter encoded by the last four bytes of a final path (`EXT:433`).
pub fn counter_from_final(final_path: &[u8]) -> Option<u32> {
    let start = final_path.len().checked_sub(4)?;
    let digits = final_path.get(start..)?;
    if !digits.iter().all(u8::is_ascii_digit) {
        return None;
    }
    let mut value = 0u32;
    for d in digits {
        value = value * 10 + u32::from(d - b'0');
    }
    (1..=MAX_COUNTER).contains(&value).then_some(value)
}

/// True when the basename starts with `.l2s.` (`EXT:523`).
pub fn is_l2s_name(path: &[u8]) -> bool {
    let basename = path.rsplit(|b| *b == b'/').next().unwrap_or(path);
    basename.starts_with(PREFIX)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn absolute_paths_are_verbatim() {
        assert_eq!(resolve_to_absolute(b"/tmp", b"/a/../b", 0), b"/a/../b");
        assert_eq!(resolve_to_absolute(b"/tmp", b"/", 0), b"/");
        assert_eq!(resolve_to_absolute(b"/tmp", b"/tmp/", 0), b"/tmp/");
    }

    #[test]
    fn relative_paths_join_the_base() {
        assert_eq!(resolve_to_absolute(b"/tmp", b"a", 0), b"/tmp/a");
        assert_eq!(resolve_to_absolute(b"/tmp/", b"a", 0), b"/tmp/a");
        assert_eq!(resolve_to_absolute(b"/tmp", b"a/b", 0), b"/tmp/a/b");
        assert_eq!(
            resolve_to_absolute(b"/proc/self/fd/3", b"a", 0),
            b"/proc/self/fd/3/a"
        );
    }

    #[test]
    fn empty_path_uses_fd_target_only_with_at_empty_path() {
        assert_eq!(
            resolve_to_absolute(b"/tmp/f (deleted)", b"", AT_EMPTY_PATH),
            b"/tmp/f (deleted)"
        );
        assert_eq!(resolve_to_absolute(b"/tmp", b"", 0), b"/tmp/");
        assert_ne!(
            resolve_to_absolute(b"/tmp/f", b"", 0),
            resolve_to_absolute(b"/tmp/f", b"", AT_EMPTY_PATH)
        );
    }

    #[test]
    fn output_is_truncated_to_path_max() {
        let long = vec![b'a'; PATH_MAX + 10];
        let out = resolve_to_absolute(b"/", &long, 0);
        assert_eq!(out.len(), PATH_MAX - 1);
    }

    #[test]
    fn l2s_names_and_counters() {
        let intermediate = intermediate_name(b"/tmp", b"a").unwrap();
        assert_eq!(intermediate, b"/tmp/.l2s.a");
        let first = final_name(&intermediate, 1).unwrap();
        assert_eq!(first, b"/tmp/.l2s.a.0001");
        let second = final_name(&intermediate, 2).unwrap();
        assert_eq!(second, b"/tmp/.l2s.a.0002");
        assert_eq!(counter_from_final(&second), Some(2));
        assert_eq!(counter_from_final(b"/tmp/.l2s.a.1000"), None);
        assert_eq!(
            final_name(&intermediate, 1000),
            Err(LinkError::CounterExhausted)
        );
    }

    #[test]
    fn linux_at_flag_values() {
        // AT_EMPTY_PATH 0x1000, AT_SYMLINK_FOLLOW 0x400, AT_REMOVEDIR 0x2000.
        assert_eq!(AT_EMPTY_PATH, 0x1000);
        assert_eq!(AT_SYMLINK_FOLLOW, 0x400);
    }

    #[test]
    fn detects_l2s_basenames() {
        assert!(is_l2s_name(b"/tmp/.l2s.a0001"));
        assert!(is_l2s_name(b".l2s.a0001.0002"));
        assert!(!is_l2s_name(b"/tmp/a"));
        assert!(!is_l2s_name(b"/tmp/x.l2s.a0001"));
    }
}
