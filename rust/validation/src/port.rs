//! The Rust port of `src/main/kotlin/com/xyzterm/logic/`.

#![forbid(unsafe_code)]

/// Port of `isValidDeepLinkCommand` (`src/main/kotlin/com/xyzterm/logic/DeepLinkCommand.kt:6`).
pub fn is_valid_deep_link_command(command: String) -> Result<bool, String> {
    // Kotlin `isNotBlank` and Rust `is_whitespace` agree here: the only extra Kotlin
    // whitespace code points (U+001C..U+001F) are ISO controls, rejected below anyway.
    let blank = command.chars().all(char::is_whitespace);
    // Kotlin `String.length` counts UTF-16 code units; one astral character counts two.
    let utf16_len = command.encode_utf16().count();
    Ok(!blank && utf16_len <= 4096 && !command.chars().any(char::is_control))
}

/// Port of `isValidSessionName` (`src/main/kotlin/com/xyzterm/logic/SessionNames.kt:4`).
pub fn is_valid_session_name(name: String) -> Result<bool, String> {
    Ok(!name.is_empty()
        && name != "."
        && name != ".."
        && !name.contains('/')
        && !name.contains('\\')
        && !name.chars().any(char::is_control))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn session_name_edges() {
        assert!(!is_valid_session_name(String::new()).unwrap());
        assert!(!is_valid_session_name("..".into()).unwrap());
        assert!(!is_valid_session_name("a/b".into()).unwrap());
        assert!(is_valid_session_name("会话".into()).unwrap());
    }

    #[test]
    fn deep_link_blank_and_controls() {
        assert!(!is_valid_deep_link_command("   ".into()).unwrap());
        assert!(!is_valid_deep_link_command("echo hi\nrm -rf /".into()).unwrap());
        assert!(!is_valid_deep_link_command("\u{1c}".into()).unwrap());
        assert!(is_valid_deep_link_command("ls -la /home".into()).unwrap());
    }

    #[test]
    fn deep_link_length_is_utf16_units() {
        assert!(is_valid_deep_link_command("a".repeat(4096)).unwrap());
        assert!(!is_valid_deep_link_command("a".repeat(4097)).unwrap());
        assert!(!is_valid_deep_link_command("a".repeat(4095) + "😀").unwrap());
    }
}
