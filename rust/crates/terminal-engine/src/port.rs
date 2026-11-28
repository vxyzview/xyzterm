//! Rust port of the Termux v0.118.3 terminal-emulator helpers that have a
//! stable, recordable API: terminal key encoding, style bit decoding and
//! perceived color brightness. The parser/grid follow in a later unit.

#![forbid(unsafe_code)]

const KEYMOD_ALT: i32 = i32::MIN; // 0x80000000
const KEYMOD_CTRL: i32 = 1 << 30;
const KEYMOD_SHIFT: i32 = 1 << 29;
const KEYMOD_NUM_LOCK: i32 = 1 << 28;

const TRUECOLOR_FOREGROUND: i64 = 1 << 9;
const TRUECOLOR_BACKGROUND: i64 = 1 << 10;

fn transform_for_modifiers(start: &str, keymod: i32, last: char) -> String {
    // `|` is an alternative in match patterns, so use an if-chain for the
    // bitwise combinations of modifier bits.
    let modifier = if keymod == KEYMOD_SHIFT {
        2
    } else if keymod == KEYMOD_ALT {
        3
    } else if keymod == KEYMOD_SHIFT | KEYMOD_ALT {
        4
    } else if keymod == KEYMOD_CTRL {
        5
    } else if keymod == KEYMOD_SHIFT | KEYMOD_CTRL {
        6
    } else if keymod == KEYMOD_ALT | KEYMOD_CTRL {
        7
    } else if keymod == KEYMOD_SHIFT | KEYMOD_ALT | KEYMOD_CTRL {
        8
    } else {
        return format!("{start}{last}");
    };
    format!("{start};{modifier}{last}")
}

fn cursor_key(key_mode: i32, cursor_app: bool, letter: char) -> String {
    if key_mode == 0 {
        if cursor_app {
            format!("\u{1b}O{letter}")
        } else {
            format!("\u{1b}[{letter}")
        }
    } else {
        transform_for_modifiers("\u{1b}[1", key_mode, letter)
    }
}

fn numpad_key(keypad_application: bool, key_mode: i32, app_letter: char, plain: &str) -> String {
    if keypad_application {
        transform_for_modifiers("\u{1b}O", key_mode, app_letter)
    } else {
        plain.to_string()
    }
}

/// Port of `getCode` (`com/termux/terminal/KeyHandler.java:156`).
/// Returns `None` where the original returns Java `null`.
pub fn get_code(
    key_code: i64,
    key_mode: i64,
    cursor_app: bool,
    keypad_application: bool,
) -> Result<Option<String>, String> {
    let key_code = key_code as i32;
    let num_lock_on = (key_mode as i32) & KEYMOD_NUM_LOCK != 0;
    let key_mode = (key_mode as i32) & !KEYMOD_NUM_LOCK;

    let code = match key_code {
        23 => Some("\r".to_string()), // DPAD_CENTER
        19 => Some(cursor_key(key_mode, cursor_app, 'A')),
        20 => Some(cursor_key(key_mode, cursor_app, 'B')),
        22 => Some(cursor_key(key_mode, cursor_app, 'C')),
        21 => Some(cursor_key(key_mode, cursor_app, 'D')),
        122 => Some(cursor_key(key_mode, cursor_app, 'H')),
        123 => Some(cursor_key(key_mode, cursor_app, 'F')),
        131 => Some(if key_mode == 0 {
            "\u{1b}OP".to_string()
        } else {
            transform_for_modifiers("\u{1b}[1", key_mode, 'P')
        }),
        132 => Some(if key_mode == 0 {
            "\u{1b}OQ".to_string()
        } else {
            transform_for_modifiers("\u{1b}[1", key_mode, 'Q')
        }),
        133 => Some(if key_mode == 0 {
            "\u{1b}OR".to_string()
        } else {
            transform_for_modifiers("\u{1b}[1", key_mode, 'R')
        }),
        134 => Some(if key_mode == 0 {
            "\u{1b}OS".to_string()
        } else {
            transform_for_modifiers("\u{1b}[1", key_mode, 'S')
        }),
        135 => Some(transform_for_modifiers("\u{1b}[15", key_mode, '~')),
        136 => Some(transform_for_modifiers("\u{1b}[17", key_mode, '~')),
        137 => Some(transform_for_modifiers("\u{1b}[18", key_mode, '~')),
        138 => Some(transform_for_modifiers("\u{1b}[19", key_mode, '~')),
        139 => Some(transform_for_modifiers("\u{1b}[20", key_mode, '~')),
        140 => Some(transform_for_modifiers("\u{1b}[21", key_mode, '~')),
        141 => Some(transform_for_modifiers("\u{1b}[23", key_mode, '~')),
        142 => Some(transform_for_modifiers("\u{1b}[24", key_mode, '~')),
        120 => Some("\u{1b}[32~".to_string()),
        121 => Some("\u{1b}[34~".to_string()),
        111 | 4 => Some("\u{1b}".to_string()),
        124 => Some(transform_for_modifiers("\u{1b}[2", key_mode, '~')),
        112 => Some(transform_for_modifiers("\u{1b}[3", key_mode, '~')),
        92 => Some("\u{1b}[5~".to_string()),
        93 => Some("\u{1b}[6~".to_string()),
        67 => {
            let prefix = if key_mode & KEYMOD_ALT == 0 {
                ""
            } else {
                "\u{1b}"
            };
            let suffix = if key_mode & KEYMOD_CTRL == 0 {
                "\u{7f}"
            } else {
                "\u{8}"
            };
            Some(format!("{prefix}{suffix}"))
        }
        143 => {
            if keypad_application {
                Some("\u{1b}OP".to_string())
            } else {
                None
            }
        }
        62 => {
            if key_mode & KEYMOD_CTRL == 0 {
                None
            } else {
                Some("\0".to_string())
            }
        }
        61 => {
            if key_mode & KEYMOD_SHIFT == 0 {
                Some("\x09".to_string())
            } else {
                Some("\u{1b}[Z".to_string())
            }
        }
        66 => {
            if key_mode & KEYMOD_ALT == 0 {
                Some("\r".to_string())
            } else {
                Some("\u{1b}\r".to_string())
            }
        }
        160 => {
            if keypad_application {
                Some(transform_for_modifiers("\u{1b}O", key_mode, 'M'))
            } else {
                Some("\n".to_string())
            }
        }
        155 => Some(if keypad_application {
            transform_for_modifiers("\u{1b}O", key_mode, 'j')
        } else {
            "*".to_string()
        }),
        157 => Some(numpad_key(keypad_application, key_mode, 'k', "+")),
        159 => Some(",".to_string()),
        158 => Some(if num_lock_on {
            numpad_key(keypad_application, key_mode, 'n', ".")
        } else {
            transform_for_modifiers("\u{1b}[3", key_mode, '~')
        }),
        156 => Some(numpad_key(keypad_application, key_mode, 'm', "-")),
        154 => Some(numpad_key(keypad_application, key_mode, 'o', "/")),
        144 => Some(if num_lock_on {
            numpad_key(keypad_application, key_mode, 'p', "0")
        } else {
            transform_for_modifiers("\u{1b}[2", key_mode, '~')
        }),
        145 => Some(if num_lock_on {
            numpad_key(keypad_application, key_mode, 'q', "1")
        } else {
            cursor_key(key_mode, cursor_app, 'F')
        }),
        146 => Some(if num_lock_on {
            numpad_key(keypad_application, key_mode, 'r', "2")
        } else {
            cursor_key(key_mode, cursor_app, 'B')
        }),
        147 => Some(if num_lock_on {
            numpad_key(keypad_application, key_mode, 's', "3")
        } else {
            "\u{1b}[6~".to_string()
        }),
        148 => Some(if num_lock_on {
            numpad_key(keypad_application, key_mode, 't', "4")
        } else {
            cursor_key(key_mode, cursor_app, 'D')
        }),
        149 => Some(numpad_key(keypad_application, key_mode, 'u', "5")),
        150 => Some(if num_lock_on {
            numpad_key(keypad_application, key_mode, 'v', "6")
        } else {
            cursor_key(key_mode, cursor_app, 'C')
        }),
        151 => Some(if num_lock_on {
            numpad_key(keypad_application, key_mode, 'w', "7")
        } else {
            cursor_key(key_mode, cursor_app, 'H')
        }),
        152 => Some(if num_lock_on {
            numpad_key(keypad_application, key_mode, 'x', "8")
        } else {
            cursor_key(key_mode, cursor_app, 'A')
        }),
        153 => Some(if num_lock_on {
            numpad_key(keypad_application, key_mode, 'y', "9")
        } else {
            "\u{1b}[5~".to_string()
        }),
        161 => Some(if keypad_application {
            transform_for_modifiers("\u{1b}O", key_mode, 'X')
        } else {
            "=".to_string()
        }),
        _ => None,
    };
    Ok(code)
}

/// Port of `getPerceivedBrightnessOfColor` (`TerminalColors.java:87`).
pub fn get_perceived_brightness_of_color(color: i64) -> Result<i64, String> {
    let color = color as u32;
    let red = f64::from((color >> 16) & 0xff);
    let green = f64::from((color >> 8) & 0xff);
    let blue = f64::from(color & 0xff);
    let brightness = (red * red * 0.241 + green * green * 0.691 + blue * blue * 0.068).sqrt();
    Ok(brightness.floor() as i64)
}

/// Port of `decodeForeColor` (`TextStyle.java:69`).
pub fn decode_fore_color(style: i64) -> Result<i64, String> {
    if style & TRUECOLOR_FOREGROUND == 0 {
        Ok(((style as u64 >> 40) & 0b111111111) as i64)
    } else {
        Ok((0xff000000u32 | ((style as u64 >> 40) & 0x00ffffff) as u32) as i32 as i64)
    }
}

/// Port of `decodeBackColor` (`TextStyle.java:78`).
pub fn decode_back_color(style: i64) -> Result<i64, String> {
    if style & TRUECOLOR_BACKGROUND == 0 {
        Ok(((style as u64 >> 16) & 0b111111111) as i64)
    } else {
        Ok((0xff000000u32 | ((style as u64 >> 16) & 0x00ffffff) as u32) as i32 as i64)
    }
}

/// Port of `decodeEffect` (`TextStyle.java:86`).
pub fn decode_effect(style: i64) -> Result<i64, String> {
    Ok((style & 0b11111111111) as i32 as i64)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn arrow_keys() {
        assert_eq!(
            get_code(19, 0, false, false).unwrap().as_deref(),
            Some("\u{1b}[A")
        );
        assert_eq!(
            get_code(19, 0, true, false).unwrap().as_deref(),
            Some("\u{1b}OA")
        );
        assert_eq!(
            get_code(19, (KEYMOD_SHIFT) as i64, true, false)
                .unwrap()
                .as_deref(),
            Some("\u{1b}[1;2A")
        );
        assert_eq!(
            get_code(19, (KEYMOD_CTRL | KEYMOD_ALT) as i64, false, false)
                .unwrap()
                .as_deref(),
            Some("\u{1b}[1;7A")
        );
    }

    #[test]
    fn keys_that_return_null_in_java() {
        assert_eq!(get_code(1, 0, false, false).unwrap(), None);
        assert_eq!(get_code(143, 0, false, false).unwrap(), None);
        assert_eq!(get_code(62, 0, false, false).unwrap(), None);
        assert_eq!(
            get_code(62, (KEYMOD_CTRL) as i64, false, false)
                .unwrap()
                .as_deref(),
            Some("\0")
        );
    }

    #[test]
    fn numpad_respects_num_lock() {
        assert_eq!(
            get_code(144, 0, false, false).unwrap().as_deref(),
            Some("\u{1b}[2~")
        );
        assert_eq!(
            get_code(144, (KEYMOD_NUM_LOCK) as i64, false, false)
                .unwrap()
                .as_deref(),
            Some("0")
        );
        assert_eq!(
            get_code(144, (KEYMOD_NUM_LOCK) as i64, false, true)
                .unwrap()
                .as_deref(),
            Some("\u{1b}Op")
        );
    }

    #[test]
    fn style_bits() {
        assert_eq!(decode_fore_color(256i64 << 40).unwrap(), 256);
        assert_eq!(decode_effect(1 | (1 << 10)).unwrap(), 1 | (1 << 10));
        assert_eq!(
            decode_fore_color(TRUECOLOR_FOREGROUND | (0x00aabbcc << 40)).unwrap(),
            0xffaabbccu32 as i32 as i64
        );
    }

    #[test]
    fn brightness_is_in_range() {
        assert_eq!(get_perceived_brightness_of_color(0).unwrap(), 0);
        assert_eq!(get_perceived_brightness_of_color(0xffffff).unwrap(), 255);
    }
}
