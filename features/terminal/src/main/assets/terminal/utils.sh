# Shell mode guard: these helpers are for the proot archm shell, not Android's.
# Set TERMINAL_MODE=android in an Android shell so they stay inactive there.
: "${TERMINAL_MODE:=archm}"

is_android_shell() {
  [ "${TERMINAL_MODE:-archm}" != "archm" ]
}

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

warn() {
  printf "\n${YELLOW_BG}  WARN  ${RESET} ${BOLD_YELLOW}%s${RESET}\n" "$1"
}

error() {
  printf "\n${RED_BG} ERROR ${RESET} ${BOLD_RED}%s${RESET}\n" "$1"
}

ask() {
  local prompt="$1"
  local response

  while true; do
    printf "\n${BLUE_BG}  ?  ${RESET} ${BOLD_BLUE}%s${RESET}\n" "$prompt"
    read -rp "[y/N]: " response
    case "$response" in
      [Yy]|[Yy][Ee][Ss])
        return 0
        ;;
      [Nn]|[Nn][Oo]|"")
        return 1
        ;;
      *)
        warn "Please answer yes or no."
        ;;
    esac
  done
}

command_exists() {
  command -v "$1" >/dev/null 2>&1
}