#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ -f "${ROOT_DIR}/.env" ]] && { set -a; source "${ROOT_DIR}/.env"; set +a; }

for command_name in vault jq kubectl; do
  command -v "${command_name}" >/dev/null 2>&1 || {
    echo "Missing required command: ${command_name}" >&2
    exit 1
  }
done

database_mount="database"
credential_json="$(vault read -format=json "${database_mount}/creds/todo-app")"
lease_id="$(jq -r '.lease_id' <<<"${credential_json}")"
db_username="$(jq -r '.data.username' <<<"${credential_json}")"
db_password="$(jq -r '.data.password' <<<"${credential_json}")"

cleanup() {
  [[ -z "${lease_id:-}" ]] || vault lease revoke "${lease_id}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

kubectl -n vault-demo exec postgres-0 -- env PGPASSWORD="${db_password}" \
  psql -v ON_ERROR_STOP=1 -h postgres -U "${db_username}" -d todo \
  -c "SELECT session_user, current_user, current_database();" \
  -c "CREATE TEMP TABLE vault_probe (id integer); INSERT INTO vault_probe VALUES (1);" >/dev/null

vault lease revoke "${lease_id}" >/dev/null
lease_id=""

if kubectl -n vault-demo exec postgres-0 -- env PGPASSWORD="${db_password}" \
  psql -v ON_ERROR_STOP=1 -h postgres -U "${db_username}" -d todo \
  -c "SELECT 1" >/dev/null 2>&1; then
  echo "Credential still worked after revocation." >&2
  exit 1
fi

echo "Vault issued, PostgreSQL accepted, and Vault revoked a dynamic credential."
