# shellcheck disable=SC2034
force_color_prompt=yes
shopt -s checkwinsize

export PATH=/bin:/sbin:/usr/bin:/usr/sbin:/usr/games:/usr/local/bin:/usr/local/sbin:$LOCAL/bin:$PATH
export SHELL="bash"
export PS1="\[\e[1;32m\]\u@\h\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\] \\$ "

# Ubuntu-only helpers (utils.sh refuses to run inside the Android shell).
export TERMINAL_MODE=ubuntu
source "$LOCAL/bin/utils"

if [ -f "$LOCAL/.sandbox_degraded" ]; then
    warn "Running in degraded mode. Some features may not work. Please reinstall the terminal"
fi

# Set timezone
CONTAINER_TIMEZONE="UTC"  # or any timezone like "Asia/Kolkata"

# Symlink /etc/localtime to the desired timezone
ln -snf "/usr/share/zoneinfo/$CONTAINER_TIMEZONE" /etc/localtime

# Write the timezone string to /etc/timezone
echo "$CONTAINER_TIMEZONE" > /etc/timezone

# Reconfigure tzdata to apply without prompts
DEBIAN_FRONTEND=noninteractive dpkg-reconfigure -f noninteractive tzdata >/dev/null 2>&1


if [[ -f ~/.bashrc ]]; then
    # shellcheck disable=SC1090
    source ~/.bashrc
fi


ensure_packages_once() {
    local marker_file="/.cache/.packages_ensured"
    local PACKAGES=("command-not-found" "sudo" "xkb-data" "libjemalloc-dev")

    # Exit early if already done
    [[ -f "$marker_file" ]] && return 0

    echo 'APT::Install-Recommends "false";' > /etc/apt/apt.conf.d/99norecommends
    echo 'APT::Install-Suggests "false";' >> /etc/apt/apt.conf.d/99norecommends

    # Create cache dir
    mkdir -p "/.cache"

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

    if export DEBIAN_FRONTEND=noninteractive && \
       apt update -y && \
       apt install -y "${MISSING[@]}"; then
       touch "$marker_file"
       clear
       info "Setup complete."
    else
        error "Failed to install packages."
        return 1
    fi

    # Update command-not-found database
    update-command-not-found 2>/dev/null || true
}


ensure_packages_once
unset -f ensure_packages_once

# Auto-install Node.js once — the one helper most tooling (and the LSP
# installers in $LOCAL/bin) need. Runs in the background so the first
# shell prompt is never blocked on a network download. Opt-out by touching
# /.skip_nodejs.
ensure_nodejs_once() {
  local marker="/.cache/.nodejs_done"
  [[ -f "$marker" ]] && return 0
  [[ -f "/.skip_nodejs" ]] && return 0
  [[ -e "/.nodejs_inflight" ]] && return 0

  touch "/.nodejs_inflight"

  local log="/.cache/.nodejs.log"
  info "Setting up Node.js in the background (one-time)..."
  (
    if install_nodejs; then
      touch "$marker"
      info "Node.js ready."
    else
      warn "Node.js setup failed. Log: $log — run 'install_nodejs' inside the shell to retry."
    fi
    rm -f "/.nodejs_inflight"
  ) >>"$log" 2>&1 &
}

ensure_nodejs_once
unset -f ensure_nodejs_once

if [ -x /usr/lib/command-not-found -o -x /usr/share/command-not-found/command-not-found ]; then
	function command_not_found_handle {
	        # check because c-n-f could've been removed in the meantime
                if [ -x /usr/lib/command-not-found ]; then
		   /usr/lib/command-not-found -- "$1"
                   return $?
                elif [ -x /usr/share/command-not-found/command-not-found ]; then
		   /usr/share/command-not-found/command-not-found -- "$1"
                   return $?
		else
		   printf "%s: command not found\n" "$1" >&2
		   return 127
		fi
	}
fi


alias ls='ls --color=auto'
alias grep='grep --color=auto'
alias egrep='egrep --color=auto'
alias fgrep='fgrep --color=auto'
alias pkg='apt'

if [[ -f /initrc ]]; then
    # shellcheck disable=SC1090
    source /initrc
fi

# shellcheck disable=SC2164
cd "$WKDIR" || cd $HOME
