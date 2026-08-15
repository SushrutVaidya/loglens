source "$(dirname "$0")/_lib.sh"
type_note "8 errors in this log. how many actual problems?"
type_cmd "loglens --stats --quiet incident.log"
loglens --color --stats --quiet incident.log | reveal 0.11
sleep 3
