#!/system/bin/sh
# Launch an X11 desktop or window manager inside the proot container.
# Requires the external Termux:X11 app for the display surface.
set -e

if [ -z "$TERMUX_X11_SOURCE_DIR" ]; then
    echo "Termux:X11 is not installed. Install com.termux.x11 first." >&2
    exit 1
fi

SESSION="${X11_SESSION:-auto}"
CUSTOM="${X11_CUSTOM:-}"

# key|launcher|pacman package — keep in sync with DesktopSessions.kt
CANDIDATES="\
xfce4|startxfce4|xfce4
kde|startplasma-x11|plasma
gnome|gnome-session-xsession|gnome
cinnamon|cinnamon-session|cinnamon
mate|mate-session|mate
lxqt|startlxqt|lxqt
lxde|startlxde|lxde
budgie|budgie-desktop|budgie-desktop
deepin|dde-session|deepin
i3|i3|i3
i3-gaps|i3|i3-gaps
openbox|openbox-session|openbox
fluxbox|fluxbox|fluxbox
awesome|awesome|awesome
dwm|dwm|dwm
bspwm|bspwm|bspwm
xmonad|xmonad|xmonad
icewm|icewm-session|icewm
jwm|jwm|jwm
mutter|mutter --x11|mutter
kwin|kwin_x11|kwin
herbstluftwm|herbstluftwm|herbstluftwm
ratpoison|ratpoison|ratpoison
spectrwm|spectrwm|spectrwm
qtile|qtile start|qtile"

find_command() {
    printf '%s\n' "$CANDIDATES" | while IFS='|' read -r key cmd pkg; do
        [ "$key" = "$1" ] && printf '%s' "$cmd"
    done
}

find_package() {
    printf '%s\n' "$CANDIDATES" | while IFS='|' read -r key cmd pkg; do
        [ "$key" = "$1" ] && printf '%s' "$pkg"
    done
}

if [ "$SESSION" = "custom" ]; then
    CMD="$CUSTOM"
elif [ "$SESSION" = "auto" ]; then
    CMD=$(printf '%s\n' "$CANDIDATES" | while IFS='|' read -r key cmd pkg; do
        first=${cmd%% *}
        if command -v "$first" >/dev/null 2>&1; then
            printf '%s' "$cmd"
            break
        fi
    done)
    if [ -z "$CMD" ]; then
        echo "No desktop or window manager found. Install one, e.g. pacman -S xfce4 openbox i3." >&2
        exit 1
    fi
else
    CMD=$(find_command "$SESSION")
    [ -z "$CMD" ] && CMD="$SESSION"
    first=${CMD%% *}
    if ! command -v "$first" >/dev/null 2>&1; then
        echo "Session not installed: $CMD. Try: pacman -S $(find_package "$SESSION")" >&2
        exit 1
    fi
fi

if [ -z "$CMD" ]; then
    echo "No session selected." >&2
    exit 1
fi

DISPLAY="${X11_DISPLAY:-:0}"
export DISPLAY
export XKB_CONFIG_ROOT="${XKB_CONFIG_ROOT:-/usr/share/X11/xkb}"

termux-x11 "$DISPLAY" >/dev/null 2>&1 &
sleep 2

exec sh -c "$CMD"
