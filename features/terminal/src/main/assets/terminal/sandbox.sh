force_color_prompt=yes

# MkSession passes a sandboxed command as `sh -c <this script> xyzterm <quoted command line>`,
# so the command line arrives in $1 with a placeholder sentinel in $0. An interactive launch
# passes nothing at all.
#
# This must be captured BEFORE the `set --` below, which rebuilds the positional parameters
# as proot's own argv and would otherwise destroy it.
COMMAND_LINE=""
[ $# -gt 0 ] && COMMAND_LINE="$1"

# Bind specs are collected newline-separated, then expanded into positional parameters
# once at the end.
#
# The previous version accumulated `ARGS="$ARGS -b $1"` into one string and expanded it
# unquoted at exec time. Any path containing whitespace word-split in two: proot received
# `-b /storage/ABCD-1234/My` plus a stray `Drive`, its option loop broke, and the tracer
# exited before bash started -- so every session failed to launch. Validation in
# UserBindings.kt now rejects those paths, but `set --` means the shell no longer depends on
# that validation holding.
BIND_SPECS=""
FLAGS_SPECS=""

add_bind() {
  [ -n "$1" ] || return 0
  [ -e "${1%%:*}" ] || return 0
  # The newline-separated form cannot represent whitespace: `for spec in $BIND_SPECS`
  # re-splits on it. A bind containing a space would become `-b /storage/.../My` plus a
  # stray `Drive`, which is exactly how this script used to brick every session. Drop it
  # loudly instead of corrupting every argument after it.
  case "$1" in
    *[[:space:]]* ) echo "xyzterm: ignoring bind with whitespace: $1" >&2; return 0 ;;
  esac
  BIND_SPECS="$BIND_SPECS$1
"
}

add_flag() {
  FLAGS_SPECS="$FLAGS_SPECS$1
"
}

for system_mnt in /apex /odm /product /system /system_ext /vendor \
 /linkerconfig/ld.config.txt \
 /linkerconfig/com.android.art/ld.config.txt \
 /plat_property_contexts /property_contexts; do

 if [ -e "$system_mnt" ]; then
  add_bind "$(realpath "$system_mnt")"
 fi
done
unset system_mnt

add_bind /sdcard
add_bind /storage
add_bind /dev
# Respect sandbox mode: raw /data stays invisible to the guest, matching
# getDefaultBindings() in ubuntuProcess.kt. Failsafe mode keeps the binding.
[ "$SANDBOX" = "true" ] || add_bind /data
add_bind /dev/urandom:/dev/random

# User-defined binds from the Custom Binds setting (CUSTOM_BINDS env var,
# newline-separated entries of "outside" or "outside:inside"). Sources that
# do not exist are skipped, matching the behaviour above. Whitespace cannot survive
# the newline-delimited form, so such an entry is dropped rather than corrupting
# every argument after it.
while IFS= read -r user_bind || [ -n "$user_bind" ]; do
  [ -n "$user_bind" ] && add_bind "$user_bind"
done <<__CUSTOM_BINDS__
$CUSTOM_BINDS
__CUSTOM_BINDS__

add_bind /proc
[ -e "$EXT_HOME" ] && add_bind "$EXT_HOME:/home"
[ -e "$EXT_HOME" ] && add_bind "$EXT_HOME:/root"
add_bind "$PRIVATE_DIR"

[ -e "/proc/self/fd" ]   && add_bind /proc/self/fd:/dev/fd
[ -e "/proc/self/fd/0" ] && add_bind /proc/self/fd/0:/dev/stdin
[ -e "/proc/self/fd/1" ] && add_bind /proc/self/fd/1:/dev/stdout
[ -e "/proc/self/fd/2" ] && add_bind /proc/self/fd/2:/dev/stderr

add_bind /sys

if [ ! -d "$LOCAL/sandbox/tmp" ]; then
 mkdir -p "$LOCAL/sandbox/tmp"
 chmod 1777 "$LOCAL/sandbox/tmp"
fi

add_bind "$LOCAL/sandbox/tmp:/dev/shm"

add_flag --kill-on-exit
add_flag -w
add_flag /
add_flag -r
add_flag "$LOCAL/sandbox"
add_flag -0
add_flag --link2symlink
add_flag --sysvipc
add_flag -L

# One positional parameter per argument, so "$@" below expands each element as its
# own word with no re-splitting and no globbing.
set --
for spec in $FLAGS_SPECS; do
  set -- "$@" "$spec"
done
for spec in $BIND_SPECS; do
  set -- "$@" -b "$spec"
done

# Exec bits only need restoring once; scripts are written a single time by
# the app (setupAssetFile skips existing files), so they persist across spawns.
if [ ! -f "$LOCAL/.bin_chmod_done" ]; then
  chmod -R +x "$LOCAL/bin"
  touch "$LOCAL/.bin_chmod_done"
fi

# Captured at the top of the script, before `set --` reassigned the positional parameters.
if [ -n "$COMMAND_LINE" ]; then
    exec "$PROOT" "$@" /bin/bash --rcfile "$LOCAL/bin/init" -i -c "$COMMAND_LINE"
else
    exec "$PROOT" "$@" /bin/bash --rcfile "$LOCAL/bin/init" -i
fi