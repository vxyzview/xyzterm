//! Terminal character grid and VT parser for XYZTerm.
//!
//! The state model is owned by Rust; Kotlin only renders snapshots and sends
//! input bytes. `append` is incremental: a split UTF-8 sequence is buffered
//! until the rest arrives. Behavior is characterized against Termux
//! terminal-emulator v0.118.3 (see the `snapshots` test below).

#![forbid(unsafe_code)]

const TAB_WIDTH: usize = 8;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Snapshot {
    pub cursor_row: usize,
    pub cursor_col: usize,
    pub title: Option<String>,
    /// Visible rows joined with '\n'; trailing spaces are not rendered.
    pub screen: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum State {
    Ground,
    Esc,
    Csi,
    Osc,
    /// Device control / other string sequences: discarded until ST or BEL.
    Dcs,
}

#[derive(Debug, Clone)]
struct SavedScreen {
    cells: Vec<Vec<char>>,
    cursor_row: usize,
    cursor_col: usize,
}

/// A VT100/VT220/xterm-compatible screen state machine.
pub struct TerminalGrid {
    cols: usize,
    rows: usize,
    cells: Vec<Vec<char>>,
    cursor_row: usize,
    cursor_col: usize,
    title: Option<String>,
    scrollback: Vec<Vec<char>>,
    alternate: Option<SavedScreen>,
    insert_mode: bool,
    saved_cursor: Option<(usize, usize)>,
    state: State,
    params: Vec<u8>,
    private_marker: bool,
    pending: Vec<u8>,
}

impl TerminalGrid {
    pub fn new(cols: usize, rows: usize) -> Self {
        assert!(cols > 0 && rows > 0, "terminal size must be positive");
        let cells = vec![vec![' '; cols]; rows];
        Self {
            cols,
            rows,
            cells,
            cursor_row: 0,
            cursor_col: 0,
            title: None,
            scrollback: Vec::new(),
            alternate: None,
            insert_mode: false,
            saved_cursor: None,
            state: State::Ground,
            params: Vec::new(),
            private_marker: false,
            pending: Vec::new(),
        }
    }

    pub fn resize(&mut self, cols: usize, rows: usize) {
        if cols == 0 || rows == 0 {
            return;
        }
        self.cols = cols;
        self.rows = rows;
        for row in &mut self.cells {
            row.resize(cols, ' ');
        }
        self.cells.resize(rows, vec![' '; cols]);
        self.cursor_row = self.cursor_row.min(rows - 1);
        self.cursor_col = self.cursor_col.min(cols - 1);
    }

    pub fn snapshot(&self) -> Snapshot {
        let lines: Vec<String> = self
            .cells
            .iter()
            .map(|row| {
                row.iter()
                    .filter(|c| **c != '\0')
                    .collect::<String>()
                    .trim_end()
                    .to_string()
            })
            .collect();
        Snapshot {
            cursor_row: self.cursor_row,
            cursor_col: self.cursor_col,
            title: self.title.clone(),
            screen: lines.join("\n"),
        }
    }

    pub fn scrollback_lines(&self) -> &[Vec<char>] {
        &self.scrollback
    }

    pub fn is_alternate_active(&self) -> bool {
        self.alternate.is_some()
    }

    /// Feeds output from the child process. Bytes are decoded incrementally.
    pub fn append(&mut self, bytes: &[u8]) {
        self.pending.extend_from_slice(bytes);
        let valid = match std::str::from_utf8(&self.pending) {
            Ok(_) => self.pending.len(),
            Err(error) => error.valid_up_to(),
        };
        let text = std::str::from_utf8(&self.pending[..valid]).unwrap_or("");
        let chars: Vec<char> = text.chars().collect();
        for ch in chars {
            self.push_char(ch);
        }
        self.pending.drain(..valid);
    }

    fn push_char(&mut self, ch: char) {
        match self.state {
            State::Ground => self.ground(ch),
            State::Esc => self.escape(ch),
            State::Csi => self.csi(ch),
            State::Osc => self.osc(ch),
            State::Dcs => self.dcs(ch),
        }
    }

    fn ground(&mut self, ch: char) {
        match ch {
            '\u{1b}' => {
                self.state = State::Esc;
                self.params.clear();
                self.private_marker = false;
            }
            '\r' => self.cursor_col = 0,
            '\n' => self.line_feed(),
            '\u{8}' => self.cursor_col = self.cursor_col.saturating_sub(1),
            '\t' => {
                let next = ((self.cursor_col / TAB_WIDTH) + 1) * TAB_WIDTH;
                self.cursor_col = next.min(self.cols - 1);
            }
            '\u{7}' => {}
            c if c.is_control() => {}
            c => self.put(c),
        }
    }

    fn put(&mut self, ch: char) {
        if self.cursor_col >= self.cols {
            self.cursor_col = 0;
            self.line_feed();
        }
        if self.insert_mode {
            let row = &mut self.cells[self.cursor_row];
            if self.cursor_col < row.len() {
                row.insert(self.cursor_col, ch);
                row.pop();
            }
        } else {
            self.cells[self.cursor_row][self.cursor_col] = ch;
        }
        let width = unicode_width::UnicodeWidthChar::width(ch)
            .unwrap_or(1)
            .max(1);
        if width == 2 && self.cursor_col + 1 < self.cols {
            // The second cell is a continuation placeholder, not a character.
            self.cells[self.cursor_row][self.cursor_col + 1] = '\0';
            self.cursor_col += 2;
        } else {
            self.cursor_col += 1;
        }
    }

    fn line_feed(&mut self) {
        self.cursor_row += 1;
        if self.cursor_row >= self.rows {
            self.cursor_row = self.rows - 1;
            let removed = self.cells.remove(0);
            if self.alternate.is_none() {
                self.scrollback.push(removed);
            }
            self.cells.push(vec![' '; self.cols]);
        }
    }

    fn escape(&mut self, ch: char) {
        match ch {
            '[' => self.state = State::Csi,
            ']' => {
                self.state = State::Osc;
                self.params.clear();
            }
            'P' | 'X' | '^' | '_' => self.state = State::Dcs,
            '7' => self.saved_cursor = Some((self.cursor_row, self.cursor_col)),
            '8' => {
                if let Some((row, col)) = self.saved_cursor {
                    self.cursor_row = row.min(self.rows - 1);
                    self.cursor_col = col.min(self.cols - 1);
                }
            }
            'M' => {
                if self.cursor_row > 0 {
                    self.cursor_row -= 1;
                }
            }
            'D' => self.line_feed(),
            'c' => self.reset(),
            '(' | ')' | '*' | '+' => {
                // Charset designation: consume the next byte in Esc state.
            }
            _ => {}
        }
        if self.state == State::Esc {
            self.state = State::Ground;
        }
    }

    fn dcs(&mut self, ch: char) {
        if ch == '\u{7}' {
            self.state = State::Ground;
        } else if ch == '\u{1b}' {
            // The following '\\' completes ST; tolerate it in Ground.
            self.state = State::Esc;
        }
    }

    fn osc(&mut self, ch: char) {
        if ch == '\u{7}' {
            let value = String::from_utf8_lossy(&self.params).into_owned();
            let title = value
                .split_once(';')
                .map_or(value.as_str(), |(_, title)| title);
            self.title = Some(title.to_string());
            self.state = State::Ground;
        } else if ch == '\u{1b}' {
            self.state = State::Dcs;
        } else {
            self.params.push(ch as u8);
        }
    }

    fn param(&self, index: usize, default: usize) -> usize {
        let text = self
            .params
            .split(|b| *b == b';' || *b == b':')
            .nth(index)
            .unwrap_or(&[]);
        if text.is_empty() {
            default
        } else {
            std::str::from_utf8(text)
                .ok()
                .and_then(|s| s.parse::<usize>().ok())
                .unwrap_or(default)
        }
    }

    fn csi(&mut self, ch: char) {
        match ch {
            '0'..='9' | ';' | ':' => {
                self.params.push(ch as u8);
                return;
            }
            '?' => {
                self.private_marker = true;
                return;
            }
            _ => {}
        }
        // Cursor-movement commands default to 1; erase commands default to 0.
        let first = self.param(0, 1).max(1);
        let erase_mode = self.param(0, 0);
        if self.private_marker {
            match ch {
                'h' if first == 1049 => self.enter_alternate(),
                'l' if first == 1049 => self.leave_alternate(),
                _ => {}
            }
        } else {
            match ch {
                'A' => self.cursor_row = self.cursor_row.saturating_sub(first),
                'B' => self.cursor_row = (self.cursor_row + first).min(self.rows - 1),
                'C' => self.cursor_col = (self.cursor_col + first).min(self.cols - 1),
                'D' => self.cursor_col = self.cursor_col.saturating_sub(first),
                'H' | 'f' => {
                    self.cursor_row = self.param(0, 1).saturating_sub(1).min(self.rows - 1);
                    self.cursor_col = self.param(1, 1).saturating_sub(1).min(self.cols - 1);
                }
                'J' => self.erase_screen(erase_mode),
                'K' => self.erase_line(erase_mode),
                'h' if first == 4 => self.insert_mode = true,
                'l' if first == 4 => self.insert_mode = false,
                'm' | 'n' => {}
                _ => {}
            }
        }
        self.state = State::Ground;
        self.params.clear();
        self.private_marker = false;
    }

    fn erase_screen(&mut self, mode: usize) {
        match mode {
            0 => {
                let row = self.cursor_row;
                for col in self.cursor_col..self.cols {
                    self.cells[row][col] = ' ';
                }
                for row in (row + 1)..self.rows {
                    self.cells[row].fill(' ');
                }
            }
            1 => {
                let row = self.cursor_row;
                for col in 0..=self.cursor_col.min(self.cols - 1) {
                    self.cells[row][col] = ' ';
                }
                for cells in self.cells.iter_mut().take(row) {
                    cells.fill(' ');
                }
            }
            _ => {
                for row in &mut self.cells {
                    row.fill(' ');
                }
            }
        }
    }

    fn erase_line(&mut self, mode: usize) {
        let row = self.cursor_row;
        match mode {
            0 => {
                for col in self.cursor_col..self.cols {
                    self.cells[row][col] = ' ';
                }
            }
            1 => {
                for col in 0..=self.cursor_col.min(self.cols - 1) {
                    self.cells[row][col] = ' ';
                }
            }
            _ => self.cells[row].fill(' '),
        }
    }

    fn enter_alternate(&mut self) {
        if self.alternate.is_some() {
            return;
        }
        let saved = SavedScreen {
            cells: self.cells.clone(),
            cursor_row: self.cursor_row,
            cursor_col: self.cursor_col,
        };
        self.cells = vec![vec![' '; self.cols]; self.rows];
        self.cursor_row = 0;
        self.cursor_col = 0;
        self.alternate = Some(saved);
    }

    fn leave_alternate(&mut self) {
        if let Some(saved) = self.alternate.take() {
            self.cells = saved.cells;
            self.cursor_row = saved.cursor_row.min(self.rows - 1);
            self.cursor_col = saved.cursor_col.min(self.cols - 1);
        }
    }

    fn reset(&mut self) {
        self.cells = vec![vec![' '; self.cols]; self.rows];
        self.cursor_row = 0;
        self.cursor_col = 0;
        self.title = None;
        self.scrollback.clear();
        self.alternate = None;
        self.insert_mode = false;
        self.saved_cursor = None;
        self.state = State::Ground;
        self.params.clear();
    }
}

/// JNI-friendly batched snapshot: one call reads the whole screen state.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SnapshotHeader {
    pub cursor_row: usize,
    pub cursor_col: usize,
}

impl From<&Snapshot> for SnapshotHeader {
    fn from(value: &Snapshot) -> Self {
        Self {
            cursor_row: value.cursor_row,
            cursor_col: value.cursor_col,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn render(cols: usize, rows: usize, input: &[u8]) -> String {
        let mut grid = TerminalGrid::new(cols, rows);
        grid.append(input);
        let snap = grid.snapshot();
        format!(
            "{}|{}|{}|{}",
            snap.cursor_row,
            snap.cursor_col,
            snap.title.as_deref().unwrap_or("null"),
            snap.screen.replace('\n', "\\n")
        )
    }

    /// Snapshots recorded from Termux terminal-emulator v0.118.3 by feeding
    /// the same bytes into a headless TerminalEmulator.
    #[test]
    fn snapshots_match_termux_v0_118_3() {
        let cases: &[(&[u8], &str)] = &[
            (b"hello world", "0|11|null|hello world\\n\\n\\n\\n"),
            (b"first\r\nsecond", "1|6|null|first\\nsecond\\n\\n\\n"),
            (
                b"ABCDEFGHIJKLMNOPQRSTUVWXYZ",
                "1|6|null|ABCDEFGHIJKLMNOPQRST\\nUVWXYZ\\n\\n\\n",
            ),
            (b"abc\x1b[2DZ", "0|2|null|aZc\\n\\n\\n\\n"),
            (b"abc\x1b[2Adef", "0|6|null|abcdef\\n\\n\\n\\n"),
            (b"abc\x1b[1Bdef", "1|6|null|abc\\n   def\\n\\n\\n"),
            (b"\x1b[3;5HX", "2|5|null|\\n\\n    X\\n\\n"),
            (b"abcdef\x1b[2D\x1b[K", "0|4|null|abcd\\n\\n\\n\\n"),
            (b"aaa\x1b[2Jbbb", "0|6|null|   bbb\\n\\n\\n\\n"),
            (
                b"\x1b]0;My Title\x07hello",
                "0|5|My Title|hello\\n\\n\\n\\n",
            ),
            (
                b"\x1b[?1049hALT\x1b[?1049lMAIN",
                "0|4|null|MAIN\\n\\n\\n\\n",
            ),
            (
                b"line1\r\nline2\r\nline3\r\nline4\r\nline5\r\nline6",
                "4|5|null|line2\\nline3\\nline4\\nline5\\nline6",
            ),
            (b"ab\tcd\x08ef", "0|11|null|ab      cef\\n\\n\\n\\n"),
            (b"\x1b[4h12345", "0|5|null|12345\\n\\n\\n\\n"),
            (
                "caf\u{e9} \u{1f600} wide".as_bytes(),
                "0|12|null|café 😀 wide\\n\\n\\n\\n",
            ),
            (
                b"\x1b[31mRED\x1b[0mNORMAL",
                "0|9|null|REDNORMAL\\n\\n\\n\\n",
            ),
            (
                b"\x1b[2J\x1b[Htop\x1b[3;1Hbottom",
                "2|6|null|top\\n\\nbottom\\n\\n",
            ),
        ];
        for (input, expected) in cases {
            assert_eq!(render(20, 5, input), *expected, "input: {input:?}");
        }
    }

    #[test]
    fn split_utf8_is_buffered() {
        let mut grid = TerminalGrid::new(10, 2);
        let bytes = "😀".as_bytes();
        grid.append(&bytes[..2]);
        assert_eq!(grid.snapshot().screen, "\n");
        grid.append(&bytes[2..]);
        assert_eq!(grid.snapshot().screen, "😀\n");
    }

    #[test]
    fn scrollback_retains_scrolled_lines() {
        let mut grid = TerminalGrid::new(10, 2);
        grid.append(b"one\r\ntwo\r\nthree");
        assert_eq!(grid.scrollback_lines().len(), 1);
        assert_eq!(grid.snapshot().screen, "two\nthree");
    }

    #[test]
    fn alternate_buffer_has_no_scrollback() {
        let mut grid = TerminalGrid::new(10, 2);
        grid.append(b"\x1b[?1049h");
        assert!(grid.is_alternate_active());
        grid.append(b"a\r\nb\r\nc");
        assert!(grid.scrollback_lines().is_empty());
        grid.append(b"\x1b[?1049l");
        assert!(!grid.is_alternate_active());
    }
}
