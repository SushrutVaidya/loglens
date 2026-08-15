# Shared demo helpers.
#
# asciinema records output as it arrives, so a script that dumps everything at
# once produces a 2-frame "GIF". These helpers emit gradually - a character at a
# time for commands, a line at a time for output - which is what turns the
# recording into a smooth animation. The CONTENT is real loglens output; only the
# reveal is paced.

GREEN='\033[38;5;114m'
GREY='\033[38;5;245m'
RESET='\033[0m'

# Typewriter a command behind a prompt.
type_cmd() {
  printf "${GREEN}\$${RESET} "
  local s="$1" i
  for (( i=0; i<${#s}; i++ )); do
    printf '%s' "${s:i:1}"
    sleep 0.032
  done
  printf '\n'
  sleep 0.35
}

# Typewriter a comment line.
type_note() {
  printf "${GREY}# "
  local s="$1" i
  for (( i=0; i<${#s}; i++ )); do
    printf '%s' "${s:i:1}"
    sleep 0.022
  done
  printf "${RESET}\n"
  sleep 0.5
}

# Reveal piped output one line at a time.
reveal() {
  local delay="${1:-0.07}"
  while IFS= read -r line; do
    printf '%s\n' "$line"
    sleep "$delay"
  done
}
