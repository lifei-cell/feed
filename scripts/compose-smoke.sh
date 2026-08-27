#!/usr/bin/env bash
set -Eeuo pipefail

export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-friend-feed-smoke}"
compose_wait_timeout="${COMPOSE_WAIT_TIMEOUT:-240}"
compose=(docker compose)
if [[ "${RUN_OBSERVABILITY_E2E:-false}" == "true" ]]; then
  compose+=(--profile observability)
fi

cleanup() {
  exit_code=$?
  if [[ $exit_code -ne 0 ]]; then
    docker compose logs --no-color > compose-smoke.log 2>&1 || true
  fi
  docker compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  exit "$exit_code"
}
trap cleanup EXIT

"${compose[@]}" config -q
"${compose[@]}" up --build --detach --wait --wait-timeout "$compose_wait_timeout"
curl --fail --silent --show-error http://127.0.0.1:8081/actuator/health >/dev/null

if [[ "${RUN_E2E:-false}" == "true" ]]; then
  npm --prefix frontend run test:e2e
fi

if [[ "${RUN_OBSERVABILITY_E2E:-false}" == "true" ]]; then
  curl --fail --silent --show-error http://127.0.0.1:9090/-/ready >/dev/null
  curl --fail --silent --show-error http://127.0.0.1:9093/-/ready >/dev/null
  curl --fail --silent --show-error http://127.0.0.1:16686/ >/dev/null
  curl --fail --silent --show-error http://127.0.0.1:8090/health >/dev/null

  curl --fail --silent --show-error -X POST http://127.0.0.1:9093/api/v2/alerts \
    -H 'Content-Type: application/json' \
    --data '[{"labels":{"alertname":"FriendFeedReleaseSmoke","severity":"info"},"annotations":{"summary":"release acceptance alert"}}]' >/dev/null
  delivered=false
  for _ in $(seq 1 20); do
    if curl --fail --silent http://127.0.0.1:8090/status | grep -Eq '"received": [1-9]'; then
      delivered=true
      break
    fi
    sleep 1
  done
  [[ "$delivered" == "true" ]]
fi

if [[ "${RUN_DLT_E2E:-false}" == "true" ]]; then
  bash scripts/dlt-replay-smoke.sh
fi
