#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -f "${ROOT_DIR}/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "${ROOT_DIR}/.env"
  set +a
fi

: "${TODO_BOOTSTRAP_PASSWORD:?Set TODO_BOOTSTRAP_PASSWORD in .env or the environment}"

command -v kubectl >/dev/null 2>&1 || { echo "Missing required command: kubectl" >&2; exit 1; }

kubectl apply -f "${ROOT_DIR}/k8s/base/namespace.yaml" >/dev/null

kubectl -n vault-demo create secret generic todo-database-credentials \
  --from-literal=username=todo_bootstrap \
  --from-literal=password="${TODO_BOOTSTRAP_PASSWORD}" \
  --from-literal=managed_by=bootstrap \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

echo "Created/updated the application bootstrap Secret in namespace vault-demo."
