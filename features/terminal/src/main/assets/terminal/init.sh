# shellcheck disable=SC2034
force_color_prompt=yes
shopt -s checkwinsize

export PATH=/bin:/sbin:/usr/bin:/usr/sbin:/usr/games:/usr/local/bin:/usr/local/sbin:$LOCAL/bin:$PATH
export SHELL="bash"

# archm helpers (utils.sh refuses to run inside the Android shell).
export TERMINAL_MODE=archm
source "$LOCAL/bin/utils"

if [ -f "$LOCAL/.sandbox_degraded" ]; then
    warn "Running in degraded mode. Some features may not work. Please reinstall the terminal"
fi

# Follow the device timezone when the app exports one (archm defaults to UTC).
if [ -n "${TZ:-}" ] && [ -e "/usr/share/zoneinfo/$TZ" ]; then
    ln -snf "/usr/share/zoneinfo/$TZ" /etc/localtime
fi


if [[ -f ~/.bashrc ]]; then
    # shellcheck disable=SC1090
    source ~/.bashrc
fi

# Prompt set AFTER ~/.bashrc so a stock prompt cannot override it. Plain
# (non-bold) 32/34 map straight to the theme palette's green/blue slots, so
# the prompt always matches the selected color scheme. User@host is hardcoded
# per brand ("roo@xyz"); see TerminalScreen wiring. Guard PS1 so a stale
# dotfile cannot re-inject another host name via PROMPT_COMMAND.
export PS1="\[\e[32m\]roo@xyz\[\e[0m\]:\[\e[34m\]\w\[\e[0m\] \$ "
case "$PROMPT_COMMAND" in
  *roo@xyz*) ;;
  *) pc="${PROMPT_COMMAND%;}"; export PROMPT_COMMAND="${pc:+${pc}; }PS1='\[\e[32m\]roo@xyz\[\e[0m\]:\[\e[34m\]\w\[\e[0m\] \$ '" ;;
esac


alias ls='ls --color=auto'
alias grep='grep --color=auto'
alias egrep='egrep --color=auto'
alias fgrep='fgrep --color=auto'
alias pkg='pacman'

# Ring the bell when a command ends so the app can notify the session.
note() {
    "$@"
    local status=$?
    printf '\a' >&2
    return $status
}

if [[ -f /initrc ]]; then
    # shellcheck disable=SC1090
    source /initrc
fi

# shellcheck disable=SC2164
cd "$WKDIR" || cd $HOME
