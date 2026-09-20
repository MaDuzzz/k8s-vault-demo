#!/usr/bin/env bash
set -Eeuo pipefail

command -v kubectl >/dev/null 2>&1 || { echo "Missing required command: kubectl" >&2; exit 1; }
command -v openssl >/dev/null 2>&1 || { echo "Missing required command: openssl" >&2; exit 1; }

username_b64="$(kubectl -n vault-demo get secret todo-database-credentials \
  -o jsonpath='{.data.username}')"
username="$(printf '%s' "${username_b64}" | openssl base64 -d -A)"

if [[ "${username}" == "todo_bootstrap" ]]; then
  echo "Refusing to disable todo_bootstrap before VSO has supplied a dynamic user." >&2
  exit 1
fi

kubectl -n vault-demo exec postgres-0 -- \
  psql -v ON_ERROR_STOP=1 -U postgres -d todo \
  -c "ALTER ROLE todo_bootstrap NOLOGIN;" >/dev/null

echo "Disabled LOGIN for todo_bootstrap; the application now depends on VSO credentials."
