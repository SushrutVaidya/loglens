source "$(dirname "$0")/_lib.sh"
type_note "follow one request through the whole incident"
type_cmd "loglens --trace 9f2c-a1 incident.log"
loglens --color --trace 9f2c-a1 incident.log | reveal 0.16
sleep 1.4
type_note "cache miss -> pool exhausted -> 500 -> 200 after 171 seconds"
sleep 2.2
