#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -f "${ROOT_DIR}/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "${ROOT_DIR}/.env"
  set +a
fi

: "${VAULT_ADDR:?Set VAULT_ADDR to the existing Vault cluster endpoint}"
: "${VAULT_DB_ADDRESS:?Set VAULT_DB_ADDRESS to a PostgreSQL host:port reachable from Vault}"
: "${POSTGRES_ADMIN_PASSWORD:?Set POSTGRES_ADMIN_PASSWORD}"

for command_name in vault jq; do
  command -v "${command_name}" >/dev/null 2>&1 || {
    echo "Missing required command: ${command_name}" >&2
    exit 1
  }
done

vault status >/dev/null

database_mount="database"
database_name="todo"
database_sslmode="${POSTGRES_SSLMODE:-disable}"

if ! vault secrets list -format=json | jq -e --arg mount "${database_mount}/" 'has($mount)' >/dev/null; then
  vault secrets enable -path="${database_mount}" database >/dev/null
fi

connection_url="postgresql://{{username}}:{{password}}@${VAULT_DB_ADDRESS}/${database_name}?sslmode=${database_sslmode}"

jq -n \
  --arg connection_url "${connection_url}" \
  --arg password "${POSTGRES_ADMIN_PASSWORD}" \
  '{
    plugin_name: "postgresql-database-plugin",
    allowed_roles: ["todo-app"],
    connection_url: $connection_url,
    username: "postgres",
    password: $password,
    verify_connection: true,
    max_open_connections: 4,
    max_connection_lifetime: "0s"
  }' | vault write "${database_mount}/config/todo-postgres" - >/dev/null

vault write "${database_mount}/roles/todo-app" \
  db_name=todo-postgres \
  default_ttl="${VAULT_DB_DEFAULT_TTL:-30s}" \
  max_ttl="${VAULT_DB_MAX_TTL:-2m}" \
  creation_statements=@"${ROOT_DIR}/infra/vault/bootstrap/sql/create-user.sql" \
  renew_statements=@"${ROOT_DIR}/infra/vault/bootstrap/sql/renew-user.sql" \
  revocation_statements=@"${ROOT_DIR}/infra/vault/bootstrap/sql/revoke-user.sql" >/dev/null

vault policy write todo-app "${ROOT_DIR}/infra/vault/bootstrap/policies/todo-app.hcl" >/dev/null

echo "Configured Vault database role ${database_mount}/creds/todo-app."
echo "Lease: ${VAULT_DB_DEFAULT_TTL:-30s}; max TTL: ${VAULT_DB_MAX_TTL:-2m}."
