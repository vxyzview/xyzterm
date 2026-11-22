//! Rust port of xyzterm's platform-independent logic.
//!
//! Source bodies: `rust/portable/src/main/java/com/xyzterm/terminal/PortableLogic.kt`
//! (verbatim extracts of the original Kotlin files). Test seams return strings/enums
//! because the JNI bridge carries only primitives and strings.

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

/// Kotlin `Char.isWhitespace()` on the JVM (includes NBSP, U+2007 and U+202F here).
fn kotlin_is_whitespace(c: char) -> bool {
    matches!(
        c as u32,
        9..=13
            | 0x1c..=0x20
            | 0xa0
            | 0x1680
            | 0x2000..=0x200a
            | 0x2028
            | 0x2029
            | 0x202f
            | 0x205f
            | 0x3000
    )
}

/// UTF-16 index of the first occurrence of `needle`, as Kotlin `indexOf` reports it.
fn utf16_index(s: &str, needle: u16) -> Option<usize> {
    s.encode_utf16().position(|u| u == needle)
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

/// Port of `hasBinaryChars` (UTF-16 code units, matching Kotlin `take`/`length`).
pub fn has_binary_chars(text: String) -> Result<bool, String> {
    let units: Vec<u16> = text.encode_utf16().take(1024).collect();
    if units.is_empty() {
        return Ok(false);
    }
    if units.contains(&0) {
        return Ok(true);
    }
    let unusual = units
        .iter()
        .filter(|u| {
            let v = **u;
            (v <= 0x1f || (0x7f..=0x9f).contains(&v)) && !matches!(v, 9 | 10 | 12 | 13)
        })
        .count();
    Ok(unusual as f64 / units.len() as f64 > 0.3)
}

/// Port of `parseExtensions`.
pub fn parse_extensions(input: String) -> Result<Vec<String>, String> {
    let out: Vec<String> = input
        .split([',', ' '])
        .map(|part| {
            part.trim_matches(kotlin_is_whitespace)
                .trim_start_matches('.')
                .to_string()
        })
        .filter(|part| !part.is_empty())
        .collect();
    Ok(out)
}

/// Port of the string-only seam `parseExtensionsJoined`.
pub fn parse_extensions_joined(input: String) -> Result<String, String> {
    Ok(parse_extensions(input)?.join("\n"))
}

/// Port of the `childSafe` path predicate.
pub fn is_safe_file_name(file_name: String) -> Result<bool, String> {
    Ok(!file_name.is_empty()
        && file_name != "."
        && file_name != ".."
        && !file_name.contains('/')
        && !file_name.contains('\\'))
}

/// Port of `matchesLogLevel` (level value replaces the Android-backed enum).
pub fn matches_log_level(line: String, level_value: i64) -> Result<bool, String> {
    if line.is_empty() {
        return Ok(true);
    }
    if utf16_index(&line, b'/' as u16) != Some(1) {
        return Ok(true);
    }
    let priority: i64 = match line.encode_utf16().next() {
        Some(u) if u == b'V' as u16 || u == b'D' as u16 => 5,
        Some(u) if u == b'I' as u16 => 3,
        Some(u) if u == b'W' as u16 => 2,
        Some(u) if u == b'E' as u16 || u == b'F' as u16 => 1,
        _ => 5,
    };
    Ok(priority <= level_value)
}

/// ASCII-only case-insensitive substring search (all noisy keywords are ASCII).
fn contains_ignore_case(haystack: &str, needle: &str) -> bool {
    let h = haystack.as_bytes();
    let n = needle.as_bytes();
    if n.len() > h.len() {
        return false;
    }
    h.windows(n.len())
        .any(|w| w.iter().zip(n).all(|(a, b)| a.eq_ignore_ascii_case(b)))
}

/// Port of `shouldCaptureLog`.
pub fn should_capture_log(line: String) -> Result<bool, String> {
    if line.is_empty() {
        return Ok(false);
    }
    const NOISY: &[&str] = &[
        "ViewRootImpl",
        "Choreographer",
        "OpenGLRenderer",
        "InputMethodManager",
        "RenderThread",
        "libEGL",
        "EGL_emulation",
        "CompatChangeReporter",
        "vndksupport",
        "ConfigStore",
        "Mono",
        "me.weishu.reflection",
        "chatty",
        "HostConnection",
        "gralloc",
        "GestureDetector",
        "WindowManager",
        "ImeFocusController",
        "Insets",
    ];
    if NOISY.iter().any(|k| contains_ignore_case(&line, k)) {
        return Ok(false);
    }
    let slash = utf16_index(&line, b'/' as u16);
    let paren = utf16_index(&line, b'(' as u16);
    if slash.is_none() || paren.is_none() {
        return Ok(true);
    }
    // shortcut: the original's priority/tag branches after this guard all return true;
    // upgrade only if one of them starts doing real filtering.
    if slash != Some(1) || paren <= slash {
        return Ok(true);
    }
    Ok(true)
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
            detect_backup_layout_name("etc\nusr\nhome".into()).unwrap(),
            "V1_ROOT"
        );
    }

    #[test]
    fn binary_detection() {
        assert!(!has_binary_chars("abc".into()).unwrap());
        assert!(has_binary_chars("\0".into()).unwrap());
        assert!(!has_binary_chars("\t\n\u{c}\r".into()).unwrap());
        assert!(has_binary_chars("a\u{b}".into()).unwrap());
        assert!(!has_binary_chars("aaa\u{b}".into()).unwrap());
    }

    #[test]
    fn extension_parsing() {
        assert_eq!(parse_extensions("png jpg".into()).unwrap(), ["png", "jpg"]);
        assert_eq!(
            parse_extensions(".png,..JPG".into()).unwrap(),
            ["png", "JPG"]
        );
        assert_eq!(parse_extensions("\u{a0}png".into()).unwrap(), ["png"]);
    }

    #[test]
    fn path_and_log_filters() {
        assert!(!is_safe_file_name("a/b".into()).unwrap());
        assert!(is_safe_file_name("a..".into()).unwrap());
        assert!(!should_capture_log("I/ViewRootImpl: x".into()).unwrap());
        assert!(should_capture_log("V/MyApp(1): x".into()).unwrap());
        assert!(!matches_log_level("W/Tag".into(), 1).unwrap());
        assert!(matches_log_level("E/Tag".into(), 1).unwrap());
    }

    #[test]
    fn name_and_version() {
        assert_eq!(
            name_safe("hello world".into()).unwrap(),
            "hello_world_to5x38"
        );
        assert!(is_newer("v1.2.3".into(), "v1.2.2".into()).unwrap());
        assert!(!is_newer("garbage".into(), "v1.2.3".into()).unwrap());
    }
}
