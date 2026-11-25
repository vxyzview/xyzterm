set -e

source "$LOCAL/bin/utils"

info "Extracting the archm (Arch Linux) container…"

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

COMMAND="(cd $LOCAL/sandbox && tar -xf $TMP_DIR/sandbox.tar.gz)"

# Extract directly first: running tar under proot costs a ptrace stop on every
# syscall and is the slowest part of first launch. The archm tarball has no
# hardlinks, device nodes or xattrs, so Android's tar can unpack it as-is.
# proot remains a fallback for devices that reject the direct path.
set +e
LD_PRELOAD="$(realpath "$NATIVE_LIB_DIR/liblink2symlink.so")"
export LD_PRELOAD
/system/bin/sh -c "$COMMAND"
ret=$?
unset LD_PRELOAD
set -e

if [ "$ret" -ne 0 ]; then
    warn "Direct extraction failed (exit code $ret), retrying under proot..."

    set +e
    $PROOT $ARGS /system/bin/sh -c "$COMMAND"
    ret=$?
    set -e

    if [ "$ret" -ne 0 ]; then
        error "Extraction failed (exit code $ret)"
        exit "$ret"
    fi
fi


SANDBOX_DIR="$LOCAL/sandbox"

info "Setting up the archm container…"

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

# write resolv.conf from the device's active DNS, falling back to public resolvers
: > "$SANDBOX_DIR/etc/resolv.conf"
for dns in ${DNS_SERVERS:-8.8.8.8 8.8.4.4}; do
    printf 'nameserver %s\n' "$dns" >> "$SANDBOX_DIR/etc/resolv.conf"
done

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



if [ $# -gt 0 ]; then
    sh "$@"
else
    clear
    sh "$LOCAL/bin/sandbox"
fi
