#!/system/bin/sh
# Launch an X11 desktop inside the proot container. Requires the external
# Termux:X11 app for the display surface; this script only starts the session.
set -e

if [ -z "$TERMUX_X11_SOURCE_DIR" ]; then
    echo "Termux:X11 is not installed. Install com.termux.x11 first." >&2
    exit 1
fi

SESSION="${X11_SESSION:-xfce4}"
case "$SESSION" in
    xfce4) DESKTOP_CMD=startxfce4; PKG=xfce4 ;;
    kde) DESKTOP_CMD=startplasma-x11; PKG=kde ;;
    openbox) DESKTOP_CMD=openbox-session; PKG=openbox ;;
    *) DESKTOP_CMD=""; PKG="$SESSION" ;;
esac

if [ -n "$DESKTOP_CMD" ] && ! command -v "$DESKTOP_CMD" >/dev/null 2>&1; then
    echo "Desktop not installed. Run: pacman -S $PKG" >&2
    exit 1
fi

DISPLAY="${X11_DISPLAY:-:0}"
export DISPLAY
export XKB_CONFIG_ROOT="${XKB_CONFIG_ROOT:-/usr/share/X11/xkb}"

termux-x11 "$DISPLAY" >/dev/null 2>&1 &
sleep 2

if [ -n "$DESKTOP_CMD" ]; then
    exec "$DESKTOP_CMD"
else
    exec sh -c "$SESSION"
fi
