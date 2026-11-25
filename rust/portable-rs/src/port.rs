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

/// Port of `UserBindings.isWellFormedHost`: Kotlin `length` counts UTF-16 units,
/// and `Char.isISOControl()` is the same range as Rust's `char::is_control()`.
pub fn is_well_formed_host(path: String) -> Result<bool, String> {
    Ok(path.starts_with('/')
        && path.encode_utf16().count() > 1
        && !path.contains(':')
        && !path.contains('\\')
        && !path.chars().any(|c| c.is_control()))
}

/// Port of `UserBindings.isValidGuestPath`: same shape check as the host path.
pub fn is_valid_guest_path(path: String) -> Result<bool, String> {
    is_well_formed_host(path)
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
    // shortcut: the original's remaining branches after this filter all return true;
    // upgrade only if one of them starts doing real filtering.
    Ok(!NOISY.iter().any(|k| contains_ignore_case(&line, k)))
}

/// Parse the leading `v?(\d+)\.(\d+)\.(\d+)` version shape, ignoring a trailing suffix.
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
    Some(parts)
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

// ---- wave 2: six new exports ---------------------------------------------------------------

/// Java `String.format("%.1f %s", …)`: HALF_UP on positive values.
fn format_size_unit(value: f64, unit: &str) -> String {
    let scaled = (value * 10.0).round() as i64;
    format!("{}.{} {}", scaled / 10, scaled.rem_euclid(10), unit)
}

/// Port of `formatFileSize`.
pub fn format_file_size(bytes: i64) -> Result<String, String> {
    if bytes < 1024 {
        return Ok(format!("{} B", bytes));
    }
    let kb = bytes as f64 / 1024.0;
    if kb < 1024.0 {
        return Ok(format_size_unit(kb, "KB"));
    }
    let mb = kb / 1024.0;
    if mb < 1024.0 {
        return Ok(format_size_unit(mb, "MB"));
    }
    Ok(format_size_unit(mb / 1024.0, "GB"))
}

/// Port of `formatNumberCompact`.
pub fn format_number_compact(number: i64) -> Result<String, String> {
    if number < 1000 {
        return Ok(number.to_string());
    }
    let suffixes = ["k", "m", "b"];
    let mut value = number as f64;
    let mut index: i32 = -1;
    while value >= 1000.0 && index < suffixes.len() as i32 - 1 {
        value /= 1000.0;
        index += 1;
    }
    let suffix = suffixes[index as usize];
    let scaled = (value * 10.0).round() as i64;
    let whole = scaled / 10;
    let frac = scaled.rem_euclid(10);
    Ok(if frac == 0 {
        format!("{}{}", whole, suffix)
    } else {
        format!("{}.{}{}", whole, frac, suffix)
    })
}

/// Port of `roundedSliderMin`.
pub fn rounded_slider_min(min: i64) -> Result<i64, String> {
    if min <= 0 {
        return Ok(0);
    }
    let mut magnitude: i64 = 1;
    while magnitude <= min / 10 {
        magnitude *= 10;
    }
    Ok((min / magnitude) * magnitude)
}

fn parse_hex_radix(s: &str) -> Result<i64, String> {
    i64::from_str_radix(s, 16).map_err(|_| {
        format!(
            "NumberFormatException: For input string: \"{}\" under radix 16",
            s
        )
    })
}

/// Port of `parseHexArgb`.
pub fn parse_hex_argb(input: String) -> Result<String, String> {
    let trimmed = input.trim_matches(kotlin_is_whitespace);
    let hex = trimmed.strip_prefix('#').unwrap_or(trimmed);
    if hex.is_empty() {
        return Ok("null".to_string());
    }
    let argb: i32 = match hex.encode_utf16().count() {
        3 => {
            let mut units = hex.encode_utf16();
            let repeated = |_u: u16| -> Result<i32, String> {
                let unit = char::from_u32(_u as u32).unwrap_or('\u{fffd}');
                Ok(parse_hex_radix(&format!("{}{}", unit, unit))? as i32)
            };
            let r = repeated(units.next().unwrap_or(0))?;
            let g = repeated(units.next().unwrap_or(0))?;
            let b = repeated(units.next().unwrap_or(0))?;
            -16777216i32 | (r << 16) | (g << 8) | b
        }
        6 => -16777216i32 | (parse_hex_radix(hex)? as i32),
        8 => parse_hex_radix(hex)? as i32,
        _ => return Ok("null".to_string()),
    };
    Ok(argb.to_string())
}

/// Kotlin `Float.roundToInt()` is `floor(x + 0.5)` with saturation; NaN throws.
fn kotlin_round_to_int(x: f32) -> Result<i32, String> {
    if x.is_nan() {
        return Err("IllegalArgumentException: Cannot round NaN value.".to_string());
    }
    Ok((x + 0.5).floor() as i32)
}

/// Port of `hslLabel`.
pub fn hsl_label(rf: f64, gf: f64, bf: f64) -> Result<String, String> {
    let (r, g, b) = (rf as f32, gf as f32, bf as f32);
    // Kotlin's maxOf/minOf on Float use Math.max/min, which propagate NaN.
    let nan_max = |a: f32, c: f32| {
        if a.is_nan() || c.is_nan() {
            f32::NAN
        } else {
            a.max(c)
        }
    };
    let nan_min = |a: f32, c: f32| {
        if a.is_nan() || c.is_nan() {
            f32::NAN
        } else {
            a.min(c)
        }
    };
    let max = nan_max(nan_max(r, g), b);
    let min = nan_min(nan_min(r, g), b);
    let delta = max - min;

    let mut h = 0f32;
    let l = (max + min) / 2f32;
    let s = if delta == 0f32 {
        0f32
    } else {
        delta / (1f32 - (2f32 * l - 1f32).abs())
    };

    if delta != 0f32 {
        h = if max == r {
            ((g - b) / delta) % 6f32
        } else if max == g {
            ((b - r) / delta) + 2f32
        } else {
            ((r - g) / delta) + 4f32
        };
        h /= 6f32;
        if h < 0f32 {
            h += 1f32;
        }
    }

    Ok(format!(
        "{}|{}|{}",
        kotlin_round_to_int(h * 360f32)?,
        kotlin_round_to_int(s * 100f32)?,
        kotlin_round_to_int(l * 100f32)?
    ))
}

/// Port of `fromUrlName`.
pub fn from_url_name(url: String) -> Result<String, String> {
    let colon = url.find(':');
    let scheme = match colon {
        Some(i)
            if i > 0
                && url[..i]
                    .chars()
                    .next()
                    .is_some_and(|c| c.is_ascii_alphabetic())
                && url[..i]
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || matches!(c, '+' | '-' | '.')) =>
        {
            &url[..i]
        }
        _ => {
            return Err(format!("RuntimeException: no protocol: {}", url));
        }
    };
    let lower = scheme.to_ascii_lowercase();
    if !matches!(lower.as_str(), "http" | "https" | "ftp" | "file") {
        return Err(format!("RuntimeException: unknown protocol: {}", scheme));
    }
    let rest = &url[colon.unwrap_or(0) + 1..];
    let host = match rest.strip_prefix("//") {
        Some(authority) => {
            let authority = authority.split(['/', '?', '#']).next().unwrap_or("");
            let after_user = authority.rsplit('@').next().unwrap_or("");
            after_user.split(':').next().unwrap_or("")
        }
        None => "",
    };
    Ok(match host {
        "github.com" => "GitHub",
        "gitlab.com" => "GitLab",
        "bitbucket.org" => "BitBucket",
        _ => "Other",
    }
    .to_string())
}

#[cfg(test)]
mod wave2_tests {
    use super::*;

    #[test]
    fn file_size_rounding_and_units() {
        assert_eq!(format_file_size(0).unwrap(), "0 B");
        assert_eq!(format_file_size(1023).unwrap(), "1023 B");
        assert_eq!(format_file_size(1024).unwrap(), "1.0 KB");
        assert_eq!(format_file_size(1280).unwrap(), "1.3 KB");
        assert_eq!(format_file_size(1536).unwrap(), "1.5 KB");
        assert_eq!(format_file_size(1048575).unwrap(), "1024.0 KB");
        assert_eq!(format_file_size(1048576).unwrap(), "1.0 MB");
        assert_eq!(format_file_size(1073741824).unwrap(), "1.0 GB");
        assert_eq!(format_file_size(i64::MAX).unwrap(), "8589934592.0 GB");
        assert_eq!(format_file_size(-1).unwrap(), "-1 B");
    }

    #[test]
    fn compact_number_rounding_and_suffixes() {
        assert_eq!(format_number_compact(999).unwrap(), "999");
        assert_eq!(format_number_compact(1000).unwrap(), "1k");
        assert_eq!(format_number_compact(1250).unwrap(), "1.3k");
        assert_eq!(format_number_compact(999999).unwrap(), "1000k");
        assert_eq!(format_number_compact(1000000).unwrap(), "1m");
        assert_eq!(format_number_compact(1000000000).unwrap(), "1b");
        assert_eq!(format_number_compact(i32::MAX as i64).unwrap(), "2.1b");
    }

    #[test]
    fn slider_rounding() {
        assert_eq!(rounded_slider_min(-1).unwrap(), 0);
        assert_eq!(rounded_slider_min(11).unwrap(), 10);
        assert_eq!(rounded_slider_min(99).unwrap(), 90);
        assert_eq!(rounded_slider_min(101).unwrap(), 100);
    }

    #[test]
    fn hex_argb_lengths_and_errors() {
        assert_eq!(parse_hex_argb("".into()).unwrap(), "null");
        assert_eq!(parse_hex_argb("abc".into()).unwrap(), "-5588020");
        assert_eq!(parse_hex_argb("#ABC".into()).unwrap(), "-5588020");
        assert_eq!(parse_hex_argb("112233".into()).unwrap(), "-15654349");
        assert_eq!(parse_hex_argb("11223344".into()).unwrap(), "287454020");
        assert!(parse_hex_argb("zzz".into()).is_err());
    }

    #[test]
    fn hsl_labels() {
        assert_eq!(hsl_label(0.0, 0.0, 0.0).unwrap(), "0|0|0");
        assert_eq!(hsl_label(1.0, 0.0, 0.0).unwrap(), "0|100|50");
        assert_eq!(hsl_label(0.0, 1.0, 0.0).unwrap(), "120|100|50");
        assert_eq!(hsl_label(0.0, 0.0, 1.0).unwrap(), "240|100|50");
        assert_eq!(hsl_label(1.0, 1.0, 1.0).unwrap(), "0|0|100");
        assert!(hsl_label(f64::NAN, 0.0, 0.5).is_err());
        assert!(hsl_label(f64::NAN, 2.0, 2.0).is_err());
    }

    #[test]
    fn url_hosts_and_errors() {
        assert_eq!(
            from_url_name("https://github.com/x".into()).unwrap(),
            "GitHub"
        );
        assert_eq!(from_url_name("http://gitlab.com".into()).unwrap(), "GitLab");
        assert_eq!(
            from_url_name("https://example.com".into()).unwrap(),
            "Other"
        );
        assert_eq!(from_url_name("HTTP://github.com".into()).unwrap(), "GitHub");
        assert_eq!(from_url_name("https://GitHub.com".into()).unwrap(), "Other");
        assert!(
            from_url_name("github.com".into())
                .unwrap_err()
                .starts_with("RuntimeException:")
        );
    }
}
