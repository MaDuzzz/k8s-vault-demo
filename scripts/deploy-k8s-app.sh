#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -f "${ROOT_DIR}/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "${ROOT_DIR}/.env"
  set +a
fi

: "${APP_DB_HOST:?Set APP_DB_HOST to the Docker host/node IP reachable from Kubernetes pods}"

for command_name in kubectl jq; do
  command -v "${command_name}" >/dev/null 2>&1 || {
    echo "Missing required command: ${command_name}" >&2
    exit 1
  }
done

backend_image="${BACKEND_IMAGE:-todo-backend:demo}"
frontend_image="${FRONTEND_IMAGE:-todo-frontend:demo}"
database_port="${APP_DB_PORT:-${POSTGRES_PORT:-5432}}"

kubectl apply -k "${ROOT_DIR}/k8s/base"

config_patch="$(jq -n \
  --arg host "${APP_DB_HOST}" \
  --arg port "${database_port}" \
  '{data: {DB_HOST: $host, DB_PORT: $port}}')"
kubectl -n vault-demo patch configmap todo-app-config \
  --type=merge -p "${config_patch}" >/dev/null

kubectl -n vault-demo set image deployment/backend backend="${backend_image}"
kubectl -n vault-demo set image deployment/frontend frontend="${frontend_image}"
# envFrom values are read only at Pod startup.
kubectl -n vault-demo rollout restart deployment/backend >/dev/null

kubectl -n vault-demo rollout status deployment/backend --timeout=180s
kubectl -n vault-demo rollout status deployment/frontend --timeout=180s

echo "Backend PostgreSQL target: ${APP_DB_HOST}:${database_port}"
