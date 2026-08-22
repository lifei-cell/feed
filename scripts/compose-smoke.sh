#!/usr/bin/env bash
set -Eeuo pipefail

export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-friend-feed-smoke}"
compose_wait_timeout="${COMPOSE_WAIT_TIMEOUT:-240}"

cleanup() {
  exit_code=$?
  if [[ $exit_code -ne 0 ]]; then
    docker compose logs --no-color > compose-smoke.log 2>&1 || true
  fi
  docker compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  exit "$exit_code"
}
trap cleanup EXIT

docker compose config -q
docker compose up --build --detach --wait --wait-timeout "$compose_wait_timeout"
curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health >/dev/null

if [[ "${RUN_E2E:-false}" == "true" ]]; then
  npm --prefix frontend run test:e2e
fi
