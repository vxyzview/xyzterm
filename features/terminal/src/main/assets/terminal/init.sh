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
# Follow the device timezone (the app exports TZ); fall back to UTC.
CONTAINER_TIMEZONE="${TZ:-UTC}"

# Symlink /etc/localtime to the desired timezone
# Only reconfigure when the timezone actually changed — dpkg-reconfigure on
# every shell start wastes a second each time.
if [ "$(cat /etc/timezone 2>/dev/null)" != "$CONTAINER_TIMEZONE" ]; then
    ln -snf "/usr/share/zoneinfo/$CONTAINER_TIMEZONE" /etc/localtime

    # Write the timezone string to /etc/timezone
    echo "$CONTAINER_TIMEZONE" > /etc/timezone

    # Reconfigure tzdata to apply without prompts
    DEBIAN_FRONTEND=noninteractive dpkg-reconfigure -f noninteractive tzdata >/dev/null 2>&1
fi


if [[ -f ~/.bashrc ]]; then
    # shellcheck disable=SC1090
    source ~/.bashrc
fi


# One-time base setup now runs inside setup.sh, which writes the marker files,
# so this is normally a no-op. It only blocks when an older install never
# finished (e.g. an app update that kept the sandbox) — after that first
# start the prompt appears instantly.
if [ ! -f "/.cache/.packages_ensured" ]; then
    if ensure_packages_once && ensure_nodejs_once; then
        clear
    fi
fi

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
