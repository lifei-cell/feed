#!/usr/bin/env bash
set -Eeuo pipefail

api_base="${E2E_BASE_URL:-http://127.0.0.1:8080}"
topic="${FANOUT_TOPIC:-feed.post-published.v1}"
marker="not-json-release-smoke-$(date +%s)"

mysql_query() {
  docker compose exec -T mysql mysql --batch --skip-column-names \
    -ufeed -pfeed feed -e "$1" 2>/dev/null
}

json_access_token() {
  node -e "let value='';process.stdin.on('data',chunk=>value+=chunk);process.stdin.on('end',()=>process.stdout.write(JSON.parse(value).accessToken||''))"
}

login_json="$(curl --fail --silent --show-error -X POST "$api_base/api/auth/login" \
  -H 'Content-Type: application/json' \
  --data '{"username":"demo_alice","password":"demo12345"}')"
access_token="$(printf '%s' "$login_json" | json_access_token)"
if [[ -z "$access_token" ]]; then
  echo "DLT smoke could not obtain the admin access token" >&2
  exit 1
fi

printf '%s\n' "$marker" | docker compose exec -T kafka \
  /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka:19092 --topic "$topic" >/dev/null

captured_id=""
for _ in $(seq 1 30); do
  captured_id="$(mysql_query "SELECT id FROM kafka_dead_letters WHERE payload='${marker}' ORDER BY id DESC LIMIT 1;")"
  [[ -n "$captured_id" ]] && break
  sleep 1
done
if [[ -z "$captured_id" ]]; then
  echo "Malformed Kafka message was not captured in the governed DLT" >&2
  exit 1
fi

curl --fail --silent --show-error -X POST \
  "$api_base/api/admin/kafka-dead-letters/$captured_id/discard" \
  -H "Authorization: Bearer $access_token" \
  -H 'Content-Type: application/json' \
  --data '{"note":"release smoke malformed payload"}' >/dev/null
[[ "$(mysql_query "SELECT status FROM kafka_dead_letters WHERE id=${captured_id};")" == "DISCARDED" ]]

synthetic_offset="-$(( $(date +%s) * 1000 + RANDOM ))"
payload='{"eventId":922337203685477000,"postId":"00000000-0000-0000-0000-000000000000","attempt":0,"createdAt":"2026-01-01T00:00:00Z"}'
replay_id="$(mysql_query "INSERT INTO kafka_dead_letters(original_topic, original_partition, original_offset, message_key, payload, exception_class, exception_message) VALUES ('${topic}',0,${synthetic_offset},'release-smoke','${payload}','ReleaseSmoke','synthetic replayable record'); SELECT LAST_INSERT_ID();" | tail -n 1)"

curl --fail --silent --show-error \
  "$api_base/api/admin/kafka-dead-letters/$replay_id" \
  -H "Authorization: Bearer $access_token" | grep -q '"status":"PENDING"'
status_code="$(curl --silent --show-error -o /dev/null -w '%{http_code}' -X POST \
  "$api_base/api/admin/kafka-dead-letters/$replay_id/replay" \
  -H "Authorization: Bearer $access_token")"
[[ "$status_code" == "202" ]]
[[ "$(mysql_query "SELECT CONCAT(status, ':', replay_count) FROM kafka_dead_letters WHERE id=${replay_id};")" == "REPLAYED:1" ]]

echo "Kafka DLT capture, discard and replay smoke passed"
