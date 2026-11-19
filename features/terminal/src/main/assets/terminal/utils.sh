RESET='\033[0m'

BOLD_BLUE='\033[1;34m'
BOLD_YELLOW='\033[1;33m'
BOLD_RED='\033[1;31m'

BLUE_BG='\033[1;44m'
YELLOW_BG='\033[1;43m'
RED_BG='\033[1;41m'

info() {
  printf "\n${BLUE_BG}  INFO  ${RESET} ${BOLD_BLUE}%s${RESET}\n" "$1"
}

fix_resolv_conf() {
  # Arch Linux ARM ships resolv.conf as a symlink into /run/systemd, and the
  # systemd-resolvconf package restores that symlink on upgrade. /run is not
  # populated in the container, so DNS only works with a plain file.
  if [ -L /etc/resolv.conf ] || [ ! -s /etc/resolv.conf ]; then
    rm -f /etc/resolv.conf
    printf 'nameserver 8.8.8.8\nnameserver 8.8.4.4\n' > /etc/resolv.conf
  fi
}

warn() {
  printf "\n${YELLOW_BG}  WARN  ${RESET} ${BOLD_YELLOW}%s${RESET}\n" "$1"
}

error() {
  printf "\n${RED_BG} ERROR ${RESET} ${BOLD_RED}%s${RESET}\n" "$1"
}

ensure_packages_once() {
    local marker_file="/.cache/.packages_ensured"
    local PACKAGES="sudo xkeyboard-config python python-pip python-pillow wget curl nano git ripgrep jq openssh which"

    # Exit early if already done
    [[ -f "$marker_file" ]] && return 0

    mkdir -p "/.cache"

    # DNS has to work before the sync below.
    fix_resolv_conf

    # A killed transaction leaves db.lck behind, and every retry (including the
    # one in init.sh) would fail until it is gone. No other pacman can be
    # running during the one-time setup.
    rm -f /var/lib/pacman/db.lck

    # pacman 7 sandboxes downloads with Landlock and a dedicated user. Android
    # kernels rarely expose Landlock, and a proot container cannot switch
    # users, so downloads must run unsandboxed or every sync fails.
    sed -i '/^#\?DisableSandbox/d' /etc/pacman.conf
    sed -i '/^\[options\]/a DisableSandboxFilesystem' /etc/pacman.conf
    sed -i '/^\[options\]/a DisableSandboxSyscalls' /etc/pacman.conf

    # proot traces every syscall, so the forked download workers of pacman
    # cost more than they save and can leave the transaction waiting for a
    # child that never runs.
    sed -i '/^ParallelDownloads/d' /etc/pacman.conf
    sed -i '/^\[options\]/a ParallelDownloads = 1' /etc/pacman.conf

    # Userspace container: kernel modules, firmware, docs and man pages are
    # dead weight, and without NoExtract every upgrade puts them back. Keep
    # usr/lib/systemd out of this list: the image ships without it, and
    # systemd-sysusers/tmpfiles link libsystemd-shared from there, so the
    # upgrade must be allowed to restore it.
    #
    # The libalpm hooks are dropped as well: systemd never runs here, so
    # every hook that talks to systemctl fails, and the mkinitcpio one
    # spends minutes rebuilding initramfs images nobody can boot.
    sed -i '/^NoExtract/d' /etc/pacman.conf
    sed -i '/^\[options\]/a NoExtract = usr/share/doc/* usr/share/man/* usr/share/info/* usr/share/locale/* usr/lib/firmware/* usr/lib/modules/* boot/* usr/share/libalpm/hooks/*systemd* usr/share/libalpm/hooks/*openssh* usr/share/libalpm/hooks/*mkinitcpio* usr/share/libalpm/hooks/*depmod* usr/share/libalpm/hooks/dbus-reload.hook' /etc/pacman.conf
    rm -f /usr/share/libalpm/hooks/*systemd* \
        /usr/share/libalpm/hooks/*openssh* \
        /usr/share/libalpm/hooks/*mkinitcpio* \
        /usr/share/libalpm/hooks/*depmod* \
        /usr/share/libalpm/hooks/dbus-reload.hook

    # The tarball ships an uninitialized keyring; pacman cannot verify
    # anything until it is populated.
    if [ ! -s /etc/pacman.d/gnupg/pubring.gpg ]; then
        info "Initializing pacman keyring…"
        pacman-key --init || return 1
        pacman-key --populate archlinuxarm 2>/dev/null || pacman-key --populate archlinux || return 1
    fi

    info "Syncing Arch Linux packages (one-time)…"

    # Android provides the kernel; every kernel/firmware package is dead weight
    # and would otherwise be downloaded again on each upgrade only to be
    # skipped by NoExtract. -Rdd removes just the package, never a dependency
    # another package needs.
    #
    # The kernel presets go first: with them in place the mkinitcpio hook
    # rebuilds initramfs images, which takes minutes and is useless in a
    # container that never boots.
    rm -f /etc/mkinitcpio.d/*.preset

    local drop
    drop=$(pacman -Qq 2>/dev/null | grep -E '^linux-(aarch64|armv7|firmware)')
    if [ -n "$drop" ]; then
        pacman -Rdd --noconfirm $drop || warn "Could not remove kernel/firmware packages; continuing."
    fi

    pacman -Syu --noconfirm || return 1

    # The upgrade rewrites /etc/resolv.conf (systemd-resolvconf), which would
    # leave every download below without DNS.
    fix_resolv_conf

    pacman -S --needed --noconfirm $PACKAGES || return 1

    touch "$marker_file"
    # Do not keep the downloaded package cache: it is hundreds of MB and
    # re-downloadable.
    pacman -Scc --noconfirm >/dev/null 2>&1 || true
    info "Setup complete."
}
