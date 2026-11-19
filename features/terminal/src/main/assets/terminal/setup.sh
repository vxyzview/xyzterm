set -e

source "$LOCAL/bin/utils"

info "Extracting the Arch Linux container…"

ARGS="--kill-on-exit"
ARGS="$ARGS -w /"

for system_mnt in /apex /odm /product /system /system_ext /vendor \
 /linkerconfig/ld.config.txt \
 /linkerconfig/com.android.art/ld.config.txt \
 /plat_property_contexts /property_contexts; do

 if [ -e "$system_mnt" ]; then
  system_mnt=$(realpath "$system_mnt")
  ARGS="$ARGS -b ${system_mnt}"
 fi
done
unset system_mnt

# Only bind paths that actually exist — proot errors on a missing bind source,
# and /sdcard (and friends) is absent on some tablets / secondary profiles.
bind_if_exists() {
  local src="${1%%:*}"
  [ -n "$src" ] && [ -e "$src" ] && ARGS="$ARGS -b $1"
}

bind_if_exists /sdcard
bind_if_exists /storage
bind_if_exists /dev
bind_if_exists /data
bind_if_exists /dev/urandom:/dev/random
bind_if_exists /proc

if [ -e "/proc/self/fd" ]; then
  ARGS="$ARGS -b /proc/self/fd:/dev/fd"
fi

if [ -e "/proc/self/fd/0" ]; then
  ARGS="$ARGS -b /proc/self/fd/0:/dev/stdin"
fi

if [ -e "/proc/self/fd/1" ]; then
  ARGS="$ARGS -b /proc/self/fd/1:/dev/stdout"
fi

if [ -e "/proc/self/fd/2" ]; then
  ARGS="$ARGS -b /proc/self/fd/2:/dev/stderr"
fi

bind_if_exists "$PRIVATE_DIR"
[ -e "$EXT_HOME" ] && ARGS="$ARGS -b $EXT_HOME:/home"
bind_if_exists /sys

ARGS="$ARGS -r /"
ARGS="$ARGS -0"
ARGS="$ARGS --link2symlink"
ARGS="$ARGS --sysvipc"
ARGS="$ARGS -L"

SANDBOX_DIR="$LOCAL/sandbox"
mkdir -p "$SANDBOX_DIR"

# Replace whatever distro was installed before (the app used to ship Ubuntu).
# User data lives outside the rootfs ($LOCAL/home), so wiping the extracted
# rootfs only costs packages.
for entry in "$SANDBOX_DIR"/* "$SANDBOX_DIR"/.[!.]* "$SANDBOX_DIR"/..?*; do
  [ -e "$entry" ] && rm -rf "$entry"
done

COMMAND="(cd $LOCAL/sandbox && tar -xf $TMP_DIR/sandbox.tar.gz)"

set +e
# Samsung devices doesn't like running system binaries under proot
$PROOT $ARGS /system/bin/sh -c "$COMMAND"
ret=$?
set -e

if [ "$ret" -ne 0 ]; then
    warn "PRoot extraction failed (exit code $ret), falling back to direct extraction..."

    set +e
    LD_PRELOAD="$(realpath "$NATIVE_LIB_DIR/liblink2symlink.so")"
    export LD_PRELOAD
    /system/bin/sh -c "$COMMAND"
    unset LD_PRELOAD
    ret=$?
    set -e

    if [ "$ret" -ne 0 ]; then
        error "Extraction failed (exit code $ret)"
        exit "$ret"
    fi
fi


info "Setting up the Arch Linux container…"

# values you want written
nameserver="nameserver 8.8.8.8
nameserver 8.8.4.4"

hosts="127.0.0.1   localhost.localdomain localhost xyz

# IPv6.
::1         localhost.localdomain localhost ip6-localhost ip6-loopback
fe00::0     ip6-localnet
ff00::0     ip6-mcastprefix
ff02::1     ip6-allnodes
ff02::2     ip6-allrouters
ff02::3     ip6-allhosts"

# ensure etc directory exists
mkdir -p "$SANDBOX_DIR/etc"

# write hostname
printf '%s\n' "xyz" > "$SANDBOX_DIR/etc/hostname"

# replace the stock resolv.conf: Arch Linux ARM ships it as a symlink into
# /run/systemd, which does not exist in the container, so writing through it
# would fail with ENOENT.
rm -f "$SANDBOX_DIR/etc/resolv.conf"
printf '%s\n' "$nameserver" > "$SANDBOX_DIR/etc/resolv.conf"

# write hosts
printf '%s\n' "$hosts" > "$SANDBOX_DIR/etc/hosts"

groupFile="$SANDBOX_DIR/etc/group"
aid="$(id -g)"

linesToAdd="
inet:x:3003
everybody:x:9997
android_app:x:20455
android_debug:x:50455
android_cache:x:$((10000 + aid))
android_storage:x:$((40000 + aid))
android_media:x:$((50000 + aid))
android_external_storage:x:1077
"

# create the file if it doesn't exist
[ -f "$groupFile" ] || : > "$groupFile"

existing="$(cat "$groupFile")"

# iterate through lines
echo "$linesToAdd" | while IFS= read -r line; do
    [ -z "$line" ] && continue
    gid="${line##*:}"  # get part after last colon
    case "$existing" in
        *:"$gid"*) : ;;   # already exists → skip
        *) printf '%s\n' "$line" >> "$groupFile" ;;
    esac
done


rm "$TMP_DIR"/sandbox.tar.gz
# DO NOT REMOVE THIS FILE JUST DON'T, TRUST ME
touch $LOCAL/.terminal_setup_ok_DO_NOT_REMOVE


# One-time base package install. Done here, during setup, so the first shell
# opens instantly instead of blocking for minutes. If it fails, init.sh
# retries it on the first shell start.
info "Installing base packages (one-time, takes a few minutes)…"

mkdir -p "$SANDBOX_DIR/tmp"
chmod 1777 "$SANDBOX_DIR/tmp"

SANDBOX_ARGS="${ARGS/-r \//-r $SANDBOX_DIR}"
SANDBOX_ARGS="$SANDBOX_ARGS -b $SANDBOX_DIR/tmp:/dev/shm"
[ -e "$EXT_HOME" ] && SANDBOX_ARGS="$SANDBOX_ARGS -b $EXT_HOME:/root"

set +e
# The proot child inherits the Android-side PATH, which lacks the
# container's /usr/bin etc.; set the same container PATH init.sh uses
# or pacman is not found.
$PROOT $SANDBOX_ARGS /bin/bash -c 'export PATH=/bin:/sbin:/usr/bin:/usr/sbin:/usr/local/bin:/usr/local/sbin:$LOCAL/bin:$PATH && source "$LOCAL/bin/utils" && ensure_packages_once'
ret=$?
set -e

if [ "$ret" -ne 0 ]; then
    warn "Package install failed (exit code $ret) — it will retry on first shell start."
else
    info "Base packages installed."
fi



if [ $# -gt 0 ]; then
    sh "$@"
else
    clear
    sh "$LOCAL/bin/sandbox"
fi
