# Keybinds

Keyboard shortcuts in xyzterm.

## Global commands

Rebindable in **Settings → Keybindings**.

| Shortcut | Action |
|----------|--------|
| <kbd>CTRL</kbd>+<kbd>J</kbd> | Open the terminal |
| <kbd>CTRL</kbd>+<kbd>,</kbd> | Open settings |
| <kbd>F1</kbd> | Open documentation |

## Terminal

| Shortcut | Action |
|----------|--------|
| <kbd>CTRL</kbd>+<kbd>C</kbd> | Interrupt the running command (SIGINT) |
| <kbd>CTRL</kbd>+<kbd>D</kbd> | EOF — exit the shell or close the session |
| <kbd>CTRL</kbd>+<kbd>L</kbd> | Clear the terminal screen |
| <kbd>CTRL</kbd>+<kbd>Z</kbd> | Suspend the foreground process (SIGTSTP) |
| <kbd>CTRL</kbd>+<kbd>A</kbd> / <kbd>E</kbd> | Jump to start / end of the line |
| <kbd>CTRL</kbd>+<kbd>U</kbd> / <kbd>K</kbd> | Delete back to start / forward to end of line |
| <kbd>CTRL</kbd>+<kbd>W</kbd> | Delete the previous word |
| <kbd>CTRL</kbd>+<kbd>R</kbd> | Reverse-search shell history |
| <kbd>ESC</kbd> | Escape |
| <kbd>TAB</kbd> | Shell completion |

> CTRL, ALT, SHIFT, and FN are also available as on-screen keys in the extra-keys row; hold one and tap a key to combine them. The extra-keys matrix itself is configurable in **Terminal settings → Extra keys**.

## Pinch to zoom

Pinch on the terminal to scale the font (10–20 dp).

## Text selection

Long-press the terminal screen to select text, then copy it. To paste, press
<kbd>CTRL</kbd>+<kbd>SHIFT</kbd>+<kbd>V</kbd> (part of the *Clipboard
keybindings*, toggleable in **Terminal settings**). The default extra-keys
matrix has no dedicated paste key — it contains <kbd>ESC</kbd>, <kbd>TAB</kbd>,
<kbd>CTRL</kbd>, <kbd>ALT</kbd>, the arrow keys, <kbd>HOME</kbd>, <kbd>END</kbd>,
<kbd>PGUP</kbd>, and <kbd>PGDN</kbd>.

## URLs

Tap a URL (http, https, or www.) in the terminal and a confirmation dialog
appears with **Open** (launches the browser) and **Copy link** actions.

## Share into the terminal

Share any text from another app — pick xyzterm from the share sheet — confirm
the warning prompt, and the text is typed into the current session.
