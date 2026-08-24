# shellcheck disable=SC2034
force_color_prompt=yes

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
# Respect sandbox mode: raw /data stays invisible to the guest, matching
# getDefaultBindings() in ubuntuProcess.kt. Failsafe mode keeps the binding.
[ "$SANDBOX" = "true" ] || bind_if_exists /data
bind_if_exists /dev/urandom:/dev/random

# User-defined binds from the Custom Binds setting (CUSTOM_BINDS env var,
# newline-separated entries of "outside" or "outside:inside"). Sources that
# do not exist are skipped, matching bind_if_exists behavior above.
while IFS= read -r user_bind || [ -n "$user_bind" ]; do
  [ -n "$user_bind" ] && bind_if_exists "$user_bind"
done <<__CUSTOM_BINDS__
$CUSTOM_BINDS
__CUSTOM_BINDS__

bind_if_exists /proc
[ -e "$EXT_HOME" ] && ARGS="$ARGS -b $EXT_HOME:/home"
[ -e "$EXT_HOME" ] && ARGS="$ARGS -b $EXT_HOME:/root"
bind_if_exists "$PRIVATE_DIR"

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


bind_if_exists /sys

if [ ! -d "$LOCAL/sandbox/tmp" ]; then
 mkdir -p "$LOCAL/sandbox/tmp"
 chmod 1777 "$LOCAL/sandbox/tmp"
fi

ARGS="$ARGS -b $LOCAL/sandbox/tmp:/dev/shm"

ARGS="$ARGS -r $LOCAL/sandbox"
ARGS="$ARGS -0"
ARGS="$ARGS --link2symlink"
ARGS="$ARGS --sysvipc"
ARGS="$ARGS -L"

# Exec bits only need restoring once; scripts are written a single time by
# the app (setupAssetFile skips existing files), so they persist across spawns.
if [ ! -f "$LOCAL/.bin_chmod_done" ]; then
  chmod -R +x "$LOCAL/bin"
  touch "$LOCAL/.bin_chmod_done"
fi

if [ $# -gt 0 ]; then
    # shellcheck disable=SC2086
    $PROOT $ARGS /bin/bash --rcfile "$LOCAL/bin/init" -i -c "$*"
else
    $PROOT $ARGS /bin/bash --rcfile "$LOCAL/bin/init" -i
fi

