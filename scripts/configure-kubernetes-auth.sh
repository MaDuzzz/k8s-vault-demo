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

for command_name in vault jq kubectl openssl; do
  command -v "${command_name}" >/dev/null 2>&1 || {
    echo "Missing required command: ${command_name}" >&2
    exit 1
  }
done

vault status >/dev/null

kubectl apply -f "${ROOT_DIR}/k8s/base/namespace.yaml" >/dev/null
kubectl -n vault-demo apply -f "${ROOT_DIR}/k8s/vso/serviceaccounts.yaml" >/dev/null
kubectl -n vault-demo apply -f "${ROOT_DIR}/k8s/vso/vault-token-reviewer-secret.yaml" >/dev/null
kubectl apply -f "${ROOT_DIR}/k8s/vso/vault-auth-delegator-binding.yaml" >/dev/null

reviewer_token_b64=""
for ((attempt = 1; attempt <= 30; attempt++)); do
  reviewer_token_b64="$(kubectl -n vault-demo get secret vault-auth-reviewer-token \
    -o jsonpath='{.data.token}' 2>/dev/null || true)"
  [[ -n "${reviewer_token_b64}" ]] && break
  sleep 1
done
[[ -n "${reviewer_token_b64}" ]] || {
  echo "Kubernetes did not populate the reviewer token Secret." >&2
  exit 1
}

reviewer_jwt="$(printf '%s' "${reviewer_token_b64}" | openssl base64 -d -A)"
ca_cert_b64="$(kubectl -n vault-demo get secret vault-auth-reviewer-token -o jsonpath='{.data.ca\.crt}')"
ca_cert="$(printf '%s' "${ca_cert_b64}" | openssl base64 -d -A)"

kubernetes_host="${KUBERNETES_HOST_FOR_VAULT:-$(kubectl config view --raw --minify \
  -o jsonpath='{.clusters[0].cluster.server}')}"
case "${kubernetes_host}" in
  https://127.0.0.1:* | https://localhost:* )
    echo "Kubernetes API ${kubernetes_host} is local-only." >&2
    echo "Set KUBERNETES_HOST_FOR_VAULT to an endpoint reachable from the Vault nodes." >&2
    exit 1
    ;;
esac

auth_mount="kubernetes-vso"
if ! vault auth list -format=json | jq -e --arg mount "${auth_mount}/" 'has($mount)' >/dev/null; then
  vault auth enable -path="${auth_mount}" kubernetes >/dev/null
fi

export VAULT_REVIEWER_JWT="${reviewer_jwt}"
export VAULT_KUBERNETES_HOST="${kubernetes_host}"
export VAULT_KUBERNETES_CA="${ca_cert}"$'\n'
jq -n '{
  token_reviewer_jwt: env.VAULT_REVIEWER_JWT,
  kubernetes_host: env.VAULT_KUBERNETES_HOST,
  kubernetes_ca_cert: env.VAULT_KUBERNETES_CA
}' | vault write "auth/${auth_mount}/config" - >/dev/null
unset VAULT_REVIEWER_JWT VAULT_KUBERNETES_CA reviewer_jwt reviewer_token_b64 ca_cert ca_cert_b64

vault policy write todo-app "${ROOT_DIR}/infra/vault/bootstrap/policies/todo-app.hcl" >/dev/null
vault write "auth/${auth_mount}/role/todo-app" \
  bound_service_account_names=todo-vso-auth \
  bound_service_account_namespaces=vault-demo \
  audience=vault \
  policies=todo-app \
  token_ttl=10m \
  token_max_ttl=1h >/dev/null

echo "Configured Vault auth/${auth_mount} for vault-demo/todo-vso-auth."
echo "Kubernetes API as seen by Vault: ${kubernetes_host}"
