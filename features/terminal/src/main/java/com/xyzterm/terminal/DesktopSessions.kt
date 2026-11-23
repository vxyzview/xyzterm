package com.xyzterm.terminal

/** A known X11 session: the settings key, display label, launcher and pacman package. */
data class DesktopSession(
    val key: String,
    val label: String,
    val command: String,
    val packageName: String,
)

object DesktopSessions {
    const val AUTO = "auto"
    const val CUSTOM = "custom"

    /** Ordered by preference: auto-detect picks the first installed launcher. */
    val all =
        listOf(
            DesktopSession("xfce4", "XFCE", "startxfce4", "xfce4"),
            DesktopSession("kde", "KDE Plasma", "startplasma-x11", "plasma"),
            DesktopSession("gnome", "GNOME", "gnome-session-xsession", "gnome"),
            DesktopSession("cinnamon", "Cinnamon", "cinnamon-session", "cinnamon"),
            DesktopSession("mate", "MATE", "mate-session", "mate"),
            DesktopSession("lxqt", "LXQt", "startlxqt", "lxqt"),
            DesktopSession("lxde", "LXDE", "startlxde", "lxde"),
            DesktopSession("budgie", "Budgie", "budgie-desktop", "budgie-desktop"),
            DesktopSession("deepin", "Deepin", "dde-session", "deepin"),
            DesktopSession("i3", "i3", "i3", "i3"),
            DesktopSession("i3-gaps", "i3-gaps", "i3", "i3-gaps"),
            DesktopSession("openbox", "Openbox", "openbox-session", "openbox"),
            DesktopSession("fluxbox", "Fluxbox", "fluxbox", "fluxbox"),
            DesktopSession("awesome", "Awesome", "awesome", "awesome"),
            DesktopSession("dwm", "dwm", "dwm", "dwm"),
            DesktopSession("bspwm", "bspwm", "bspwm", "bspwm"),
            DesktopSession("xmonad", "xmonad", "xmonad", "xmonad"),
            DesktopSession("icewm", "IceWM", "icewm-session", "icewm"),
            DesktopSession("jwm", "JWM", "jwm", "jwm"),
            DesktopSession("mutter", "Mutter", "mutter --x11", "mutter"),
            DesktopSession("kwin", "KWin", "kwin_x11", "kwin"),
            DesktopSession("herbstluftwm", "herbstluftwm", "herbstluftwm", "herbstluftwm"),
            DesktopSession("ratpoison", "ratpoison", "ratpoison", "ratpoison"),
            DesktopSession("spectrwm", "spectrwm", "spectrwm", "spectrwm"),
            DesktopSession("qtile", "Qtile", "qtile start", "qtile"),
        )

    fun find(key: String): DesktopSession? = all.firstOrNull { it.key == key }
}
