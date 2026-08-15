source "$(dirname "$0")/_lib.sh"
type_note "one stream: Spark, Hadoop, Kafka, Airflow, klog, logfmt, JSON"
type_cmd "cat mixed.log"
cat mixed.log | reveal 0.22
sleep 1.5
printf '\n'
type_cmd "loglens mixed.log"
loglens --color mixed.log | reveal 0.2
sleep 2.5
