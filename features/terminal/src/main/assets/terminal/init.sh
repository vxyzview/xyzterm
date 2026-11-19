# shellcheck disable=SC2034
force_color_prompt=yes
shopt -s checkwinsize

export PATH=/bin:/sbin:/usr/bin:/usr/sbin:/usr/games:/usr/local/bin:/usr/local/sbin:$LOCAL/bin:$PATH
export SHELL="bash"

source "$LOCAL/bin/utils"

if [ -f "$LOCAL/.sandbox_degraded" ]; then
    warn "Running in degraded mode. Some features may not work. Please reinstall the terminal"
fi

# Set timezone
# Follow the device timezone (the app exports TZ); fall back to UTC.
CONTAINER_TIMEZONE="${TZ:-UTC}"

# Symlink /etc/localtime to the desired timezone. Only reconfigure when the
# timezone actually changed — no systemd/timedatectl inside proot.
if [ "$(cat /etc/timezone 2>/dev/null)" != "$CONTAINER_TIMEZONE" ]; then
    ln -snf "/usr/share/zoneinfo/$CONTAINER_TIMEZONE" /etc/localtime
    echo "$CONTAINER_TIMEZONE" > /etc/timezone
fi

# systemd upgrades can replace resolv.conf with a symlink into /run, which
# does not exist in the container; DNS then breaks until it is a plain file.
fix_resolv_conf

if [[ -f ~/.bashrc ]]; then
    # shellcheck disable=SC1090
    source ~/.bashrc
fi

# Prompt set AFTER ~/.bashrc so a stock colored prompt cannot override it.
# Plain (non-bold) 32/34 map straight to the theme palette's green/blue slots,
# so the prompt always matches the selected color scheme. User@host is
# hardcoded per brand ("roo@xyz"); see TerminalScreen wiring.
export PS1="\[\e[32m\]roo@xyz\[\e[0m\]:\[\e[34m\]\w\[\e[0m\] \$ "
case "$PROMPT_COMMAND" in
  *roo@xyz*) ;;
  *) pc="${PROMPT_COMMAND%;}"; export PROMPT_COMMAND="${pc:+${pc}; }PS1='\[\e[32m\]roo@xyz\[\e[0m\]:\[\e[34m\]\w\[\e[0m\] \$ '" ;;
esac


# One-time base setup now runs inside setup.sh, which writes the marker files,
# so this is normally a no-op. It only blocks when an older install never
# finished (e.g. an app update that kept the sandbox) — after that first
# start the prompt appears instantly.
if [ ! -f "/.cache/.packages_ensured" ]; then
    ensure_packages_once && clear
fi

alias ls='ls --color=auto'
alias grep='grep --color=auto'
alias egrep='egrep --color=auto'
alias fgrep='fgrep --color=auto'

if [[ -f /initrc ]]; then
    # shellcheck disable=SC1090
    source /initrc
fi

# shellcheck disable=SC2164
cd "$WKDIR" || cd $HOME
