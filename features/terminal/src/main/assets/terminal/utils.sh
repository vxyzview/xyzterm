# Shell mode guard. utils.sh is Ubuntu-only: the LSP installers / setup helpers
# are intended for the proot Ubuntu shell, not the Android shell. When a future
# android shell lands, set TERMINAL_MODE=android there so these stay inactive.
: "${TERMINAL_MODE:=ubuntu}"

is_android_shell() {
  [ "${TERMINAL_MODE:-ubuntu}" != "ubuntu" ]
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

add_nodesource_repo() {
  local arch
  arch="$(dpkg --print-architecture)"
  if [ "$arch" != "amd64" ] && [ "$arch" != "arm64" ]; then
    error "Unsupported architecture for Node.js: $arch"
    return 1
  fi

  install -d /usr/share/keyrings
  curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /usr/share/keyrings/nodesource.gpg || return 1
  chmod 644 /usr/share/keyrings/nodesource.gpg

  cat > /etc/apt/sources.list.d/nodesource.sources << EOF
Types: deb
URIs: https://deb.nodesource.com/node_24.x
Suites: nodistro
Components: main
Architectures: $arch
Signed-By: /usr/share/keyrings/nodesource.gpg
EOF

  echo "Package: nodejs" > /etc/apt/preferences.d/nodejs
  echo "Pin: origin deb.nodesource.com" >> /etc/apt/preferences.d/nodejs
  echo "Pin-Priority: 600" >> /etc/apt/preferences.d/nodejs
}

install_nodejs() {
  if is_android_shell; then
    error "Node.js is installed inside the Ubuntu shell only. Run this inside Ubuntu."
    return 1
  fi

  if dpkg -s nodejs >/dev/null 2>&1 && command_exists node; then
    info "Node.js already installed: $(node -v)"
    return 0
  fi

  info "Installing Node.js LTS..."
  # Recover an interrupted dpkg state (e.g. a killed install) before apt will run
  dpkg --configure -a || warn "dpkg --configure -a reported errors; continuing anyway"
  apt install -y curl ca-certificates gnupg || return 1
  add_nodesource_repo || return 1
  apt update -y || return 1
  apt install -y nodejs || return 1
  if ! command_exists node; then
    error "Node.js install failed: node was not found after install."
    return 1
  fi
  info "Node.js installed: $(node -v)"
}

ensure_packages_once() {
    local marker_file="/.cache/.packages_ensured"
    local PACKAGES=(
        "command-not-found" "sudo" "xkb-data" "libjemalloc-dev"
        "python-is-python3" "python3-pip" "python3-pillow" "python3-pil"
        "wget" "curl" "nano" "git" "ripgrep" "grep" "jq" "openssh-client"
    )

    # Exit early if already done
    [[ -f "$marker_file" ]] && return 0

    mkdir -p "/.cache"

    # Written before any update/install so the one-time setup and later
    # manual apt runs both benefit.
    {
        echo 'APT::Install-Recommends "false";'
        echo 'APT::Install-Suggests "false";'
        echo 'Acquire::Languages "none";'
        echo 'Acquire::Retries "3";'
    } > /etc/apt/apt.conf.d/99norecommends

    # Check for missing packages
    local MISSING=()
    for pkg in "${PACKAGES[@]}"; do
        if ! dpkg -s "$pkg" >/dev/null 2>&1; then
            MISSING+=("$pkg")
        fi
    done

    # If nothing missing, just mark as done
    if [ ${#MISSING[@]} -eq 0 ]; then
        touch "$marker_file"
        return 0
    fi

    info "Installing missing packages: ${MISSING[*]}"

    # Recover an interrupted dpkg state (e.g. a killed install) before apt will run
    dpkg --configure -a || warn "dpkg --configure -a reported errors; continuing anyway"

    if export DEBIAN_FRONTEND=noninteractive && \
       apt update -y && \
       apt install -y "${MISSING[@]}"; then
       touch "$marker_file"
       info "Setup complete."
    else
        error "Failed to install packages."
        return 1
    fi

    # Update command-not-found database
    update-command-not-found 2>/dev/null || true
}

ensure_nodejs_once() {
  local marker="/.cache/.nodejs_done"
  [[ -f "$marker" ]] && return 0
  [[ -f "/.skip_nodejs" ]] && return 0

  info "Setting up Node.js (one-time)..."
  if install_nodejs; then
    touch "$marker"
    info "Node.js ready."
  else
    warn "Node.js setup failed — run 'install_nodejs' inside the shell to retry."
    return 1
  fi
}

uninstall_nodejs() {
  if is_android_shell; then
    error "Node.js is installed inside the Ubuntu shell only. Run this inside Ubuntu."
    return 1
  fi

  if ask "Do you want to uninstall Node.js LTS? It was installed as a dependency of this language server. This will also remove all globally installed npm packages."; then
    info "Uninstalling Node.js LTS..."
    apt remove -y nodejs
    apt autoremove -y
    info "Node.js LTS uninstalled successfully."
  fi
}

command_exists() {
  command -v "$1" >/dev/null 2>&1
}