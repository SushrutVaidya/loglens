source "$(dirname "$0")/_lib.sh"
type_note "structured logs: perfect for machines, unreadable for humans"
type_cmd "head -3 incident.log"
head -3 incident.log | reveal 0.5
sleep 1.6
printf '\n'
type_note "the same lines, through loglens"
type_cmd "loglens incident.log"
loglens --color incident.log | reveal 0.09
sleep 2.5
