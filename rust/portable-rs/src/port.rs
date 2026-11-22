//! Rust port of xyzterm's platform-independent logic.
//!
//! The source bodies live in `rust/portable/src/main/java/com/xyzterm/terminal/PortableLogic.kt`
//! (faithful extractions of the original Kotlin files). `detectBackupLayoutName` is a test seam:
//! newline-separated entries in, enum name out.

#![forbid(unsafe_code)]

/// Java `String.hashCode()` is over UTF-16 code units with i32 wrapping.
fn java_string_hash(s: &str) -> i32 {
    let mut h: i32 = 0;
    for unit in s.encode_utf16() {
        h = h.wrapping_mul(31).wrapping_add(unit as i32);
    }
    h
}

/// Kotlin `Int.toUInt().toString(36)`.
fn base36_unsigned(value: u32) -> String {
    if value == 0 {
        return "0".to_string();
    }
    const DIGITS: &[u8; 36] = b"0123456789abcdefghijklmnopqrstuvwxyz";
    let mut buf = [0u8; 32];
    let mut i = buf.len();
    let mut n = value;
    while n > 0 {
        i -= 1;
        buf[i] = DIGITS[(n % 36) as usize];
        n /= 36;
    }
    String::from_utf8_lossy(&buf[i..]).into_owned()
}

/// Port of `detectBackupLayoutName`.
pub fn detect_backup_layout_name(entries_joined: String) -> Result<String, String> {
    let v2 = if entries_joined.is_empty() {
        false
    } else {
        entries_joined
            .split('\n')
            .any(|entry| entry == "sandbox" || entry.starts_with("sandbox/"))
    };
    Ok(if v2 {
        "V2_SANDBOX_HOME".to_string()
    } else {
        "V1_ROOT".to_string()
    })
}

/// Port of `isWellFormedHost` / `isValidGuestPath` (identical bodies in Kotlin).
pub fn is_well_formed_host(path: String) -> Result<bool, String> {
    Ok(path.starts_with('/') && path.encode_utf16().count() > 1)
}

/// Port of `isValidGuestPath`.
pub fn is_valid_guest_path(path: String) -> Result<bool, String> {
    Ok(path.starts_with('/') && path.encode_utf16().count() > 1)
}

/// Port of `nameSafe` from MkSession.
pub fn name_safe(id: String) -> Result<String, String> {
    let clean: String = id
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '_' || c == '.' || c == '-' {
                c
            } else {
                '_'
            }
        })
        .collect();
    let base = if clean.is_empty() || clean == "." || clean == ".." {
        "session"
    } else {
        &clean
    };
    Ok(format!(
        "{}_{}",
        base,
        base36_unsigned(java_string_hash(&id) as u32)
    ))
}

/// Parse the `v?(\d+)\.(\d+)\.(\d+)` version shape with `matchEntire` semantics.
fn parse_version(v: &str) -> Option<[i32; 3]> {
    let s = v.trim();
    let s = s.strip_prefix('v').unwrap_or(s);
    let mut parts = [0i32; 3];
    let mut rest = s;
    for (i, part) in parts.iter_mut().enumerate() {
        let n = rest.bytes().take_while(|b| b.is_ascii_digit()).count();
        if n == 0 {
            return None;
        }
        *part = rest[..n].parse::<i32>().ok()?;
        rest = &rest[n..];
        if i < 2 {
            rest = rest.strip_prefix('.')?;
        }
    }
    if rest.is_empty() { Some(parts) } else { None }
}

/// Port of `isNewer` from UpdateManager.
pub fn is_newer(latest: String, installed: String) -> Result<bool, String> {
    let a = match parse_version(&latest) {
        Some(a) => a,
        None => return Ok(false),
    };
    let b = match parse_version(&installed) {
        Some(b) => b,
        None => return Ok(true),
    };
    for (x, y) in a.iter().zip(b.iter()) {
        if x != y {
            return Ok(x > y);
        }
    }
    Ok(false)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn backup_layout_junit_cases() {
        assert_eq!(
            detect_backup_layout_name("sandbox\nhome".into()).unwrap(),
            "V2_SANDBOX_HOME"
        );
        assert_eq!(
            detect_backup_layout_name("sandbox/etc\nhome".into()).unwrap(),
            "V2_SANDBOX_HOME"
        );
        assert_eq!(
            detect_backup_layout_name("etc\nusr\nhome".into()).unwrap(),
            "V1_ROOT"
        );
        assert_eq!(detect_backup_layout_name("".into()).unwrap(), "V1_ROOT");
    }

    #[test]
    fn path_validators() {
        assert!(!is_well_formed_host("/".into()).unwrap());
        assert!(is_well_formed_host("/a".into()).unwrap());
        assert!(!is_well_formed_host("a/b".into()).unwrap());
        assert!(is_valid_guest_path("/home".into()).unwrap());
    }

    #[test]
    fn name_safe_matches_java_hash() {
        // Recorded from the original Kotlin on the JVM.
        assert_eq!(
            name_safe("hello world".into()).unwrap(),
            "hello_world_to5x38"
        );
        assert_eq!(name_safe("日本語".into()).unwrap(), "____fflhz");
        assert_eq!(name_safe("a/b".into()).unwrap(), "a_b_214k");
    }

    #[test]
    fn version_compare() {
        assert!(is_newer("v1.2.3".into(), "v1.2.2".into()).unwrap());
        assert!(!is_newer("v1.2.2".into(), "v1.2.3".into()).unwrap());
        assert!(is_newer("v1.2.3".into(), "garbage".into()).unwrap());
        assert!(!is_newer("garbage".into(), "v1.2.3".into()).unwrap());
    }
}
