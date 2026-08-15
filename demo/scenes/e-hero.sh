source "$(dirname "$0")/_lib.sh"
type_cmd "cat incident.log | loglens --stats --quiet"
cat incident.log | loglens --color --stats --quiet | reveal 0.1
sleep 2.8
