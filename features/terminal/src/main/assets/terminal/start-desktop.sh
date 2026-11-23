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
# Termux:X11 puts its socket at $TMPDIR/.X11-unix/X<n>. Inside proot that must
# be the guest /tmp, otherwise clients see a stale lock but cannot connect.
export TMPDIR=/tmp
export XKB_CONFIG_ROOT="${XKB_CONFIG_ROOT:-/usr/share/X11/xkb}"
mkdir -p /tmp/.X11-unix
chmod 1777 /tmp/.X11-unix

num=${DISPLAY##*:}
num=${num%%.*}
SOCKET="/tmp/.X11-unix/X$num"

if [ ! -S "$SOCKET" ]; then
    # Only a lock with no socket is stale; never remove a live server's lock.
    rm -f "/tmp/.X$num-lock"
    XLOG=/tmp/termux-x11.log
    termux-x11 "$DISPLAY" >"$XLOG" 2>&1 &
    tries=0
    while [ ! -S "$SOCKET" ] && [ "$tries" -lt 20 ]; do
        sleep 1
        tries=$((tries + 1))
    done
    if [ ! -S "$SOCKET" ]; then
        echo "Termux:X11 did not start on $DISPLAY:" >&2
        cat "$XLOG" >&2 2>/dev/null || true
        exit 1
    fi
fi

# Desktop sessions need a D-Bus session bus and an XDG runtime dir.
uid=$(id -u 2>/dev/null || echo 0)
export XDG_RUNTIME_DIR="/tmp/runtime-$uid"
mkdir -p "$XDG_RUNTIME_DIR"
chmod 700 "$XDG_RUNTIME_DIR"

if command -v dbus-launch >/dev/null 2>&1; then
    exec dbus-launch --exit-with-session sh -c "$CMD"
fi
exec sh -c "$CMD"
