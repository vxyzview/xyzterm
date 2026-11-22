#!/system/bin/sh
# Launch an X11 desktop inside the proot container. Requires the external
# Termux:X11 app for the display surface; this script only starts the session.
set -e

if [ -z "$TERMUX_X11_SOURCE_DIR" ]; then
    echo "Termux:X11 is not installed. Install com.termux.x11 first." >&2
    exit 1
fi

DISPLAY="${X11_DISPLAY:-:0}"
export DISPLAY
export XKB_CONFIG_ROOT="${XKB_CONFIG_ROOT:-/usr/share/X11/xkb}"

termux-x11 "$DISPLAY" >/dev/null 2>&1 &
sleep 2

case "${X11_SESSION:-xfce4}" in
    xfce4) exec startxfce4 ;;
    kde) exec startplasma-x11 ;;
    openbox) exec openbox-session ;;
    *) exec sh -c "$X11_SESSION" ;;
esac
