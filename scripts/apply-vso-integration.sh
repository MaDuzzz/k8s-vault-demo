#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -f "${ROOT_DIR}/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "${ROOT_DIR}/.env"
  set +a
fi

: "${VAULT_ADDR:?Set VAULT_ADDR for the Vault CLI}"
vso_vault_addr="${VSO_VAULT_ADDR:-${VAULT_ADDR}}"

for command_name in kubectl jq openssl; do
  command -v "${command_name}" >/dev/null 2>&1 || {
    echo "Missing required command: ${command_name}" >&2
    exit 1
  }
done

kubectl apply -k "${ROOT_DIR}/k8s/vso"

connection_patch="$(jq -n --arg address "${vso_vault_addr}" '{spec: {address: $address}}')"
kubectl -n vault-demo patch vaultconnection external-vault \
  --type=merge -p "${connection_patch}" >/dev/null

if [[ -n "${VAULT_CACERT:-}" ]]; then
  kubectl -n vault-demo create secret generic external-vault-ca \
    --from-file=ca.crt="${VAULT_CACERT}" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  kubectl -n vault-demo patch vaultconnection external-vault \
    --type=merge -p '{"spec":{"caCertSecretRef":"external-vault-ca","skipTLSVerify":false}}' >/dev/null
elif [[ "${VAULT_SKIP_VERIFY:-false}" == "true" ]]; then
  kubectl -n vault-demo patch vaultconnection external-vault \
    --type=merge -p '{"spec":{"skipTLSVerify":true}}' >/dev/null
fi

username=""
provider=""
for ((attempt = 1; attempt <= 90; attempt++)); do
  username_b64="$(kubectl -n vault-demo get secret todo-database-credentials \
    -o jsonpath='{.data.username}' 2>/dev/null || true)"
  provider_b64="$(kubectl -n vault-demo get secret todo-database-credentials \
    -o jsonpath='{.data.managed_by}' 2>/dev/null || true)"
  username="$(printf '%s' "${username_b64}" | openssl base64 -d -A 2>/dev/null || true)"
  provider="$(printf '%s' "${provider_b64}" | openssl base64 -d -A 2>/dev/null || true)"
  [[ "${provider}" == "vso" && -n "${username}" && "${username}" != "todo_bootstrap" ]] && break
  sleep 2
done

if [[ "${provider}" != "vso" || -z "${username}" || "${username}" == "todo_bootstrap" ]]; then
  echo "Timed out waiting for VSO to replace the bootstrap credential." >&2
  kubectl -n vault-demo describe vaultdynamicsecret todo-database >&2 || true
  exit 1
fi

echo "VSO now manages Secret/todo-database-credentials as dynamic user ${username}."
echo "The password was intentionally not printed."
