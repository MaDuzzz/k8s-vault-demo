# Phase 2: tích hợp Kubernetes với Vault qua VSO

Tài liệu này cố ý dùng từng lệnh trực tiếp, không gọi script Bash hoặc target
Makefile. Có hai kiểu cấu hình:

- **Imperative với Vault CLI:** enable secrets engine/auth method và ghi config
  vào Vault.
- **Declarative với Kubernetes:** apply YAML định nghĩa ServiceAccount, RBAC và
  các VSO custom resources.

Luồng dữ liệu cuối cùng:

```text
VSO --Kubernetes JWT--> Vault Kubernetes auth
VSO --read-----------> database/creds/todo-app
Vault ---------------> tạo PostgreSQL login có lease
VSO -----------------> cập nhật Secret/todo-database-credentials
Backend -------------> phát hiện file đổi và thay connection pool
```

## 0. Khai báo giá trị của môi trường lab

Thay các giá trị ví dụ bằng môi trường thực tế:

```bash
export VAULT_ADDR="http://192.168.180.192:8200"
export VAULT_DB_ADDRESS="192.168.180.193:5432"
export POSTGRES_ADMIN_PASSWORD="your-postgres-admin-password"
export KUBERNETES_HOST_FOR_VAULT="https://192.168.180.194:6443"

# Chỉ cần nếu VSO Pod dùng endpoint khác với VAULT_ADDR.
export VSO_VAULT_ADDR="${VAULT_ADDR}"
```

Với Vault TLS private CA:

```bash
export VAULT_CACERT="/absolute/path/to/vault-ca.pem"
```

Kiểm tra context và kết nối trước khi thay đổi:

```bash
kubectl config current-context
vault status
docker compose ps postgres
```

Đăng nhập bằng token có quyền cấu hình secrets engine, auth method và policy:

```bash
vault login
```

## 1. Cấu hình Vault Database Secrets Engine

Xem các secrets engine hiện tại:

```bash
vault secrets list
```

Nếu chưa có mount `database/`, enable nó:

```bash
vault secrets enable -path=database database
```

Khai báo kết nối từ Vault tới PostgreSQL Docker. `{{username}}` và
`{{password}}` là placeholder dành cho Vault database plugin, không phải shell
variable:

```bash
vault write database/config/todo-postgres \
  plugin_name=postgresql-database-plugin \
  allowed_roles=todo-app \
  connection_url="postgresql://{{username}}:{{password}}@${VAULT_DB_ADDRESS}/todo?sslmode=disable" \
  username=postgres \
  password="${POSTGRES_ADMIN_PASSWORD}" \
  verify_connection=true \
  max_open_connections=4 \
  max_connection_lifetime=0s
```

`verify_connection=true` buộc Vault kết nối DB ngay. Nếu lệnh này lỗi, kiểm tra
route/firewall từ **Vault node** tới `${VAULT_DB_ADDRESS}` trước khi làm tiếp.

Xem các SQL template mà Vault sẽ sử dụng:

```bash
cat infra/vault/bootstrap/sql/create-user.sql
cat infra/vault/bootstrap/sql/renew-user.sql
cat infra/vault/bootstrap/sql/revoke-user.sql
```

Tạo dynamic role với TTL ngắn để dễ quan sát demo:

```bash
vault write database/roles/todo-app \
  db_name=todo-postgres \
  default_ttl=30s \
  max_ttl=2m \
  creation_statements=@infra/vault/bootstrap/sql/create-user.sql \
  renew_statements=@infra/vault/bootstrap/sql/renew-user.sql \
  revocation_statements=@infra/vault/bootstrap/sql/revoke-user.sql
```

Policy `todo-app` cho phép VSO đọc credential và quản lý lease:

```bash
cat infra/vault/bootstrap/policies/todo-app.hcl
vault policy write todo-app infra/vault/bootstrap/policies/todo-app.hcl
```

### Kiểm tra dynamic credential bằng tay

```bash
export DB_CREDS_JSON="$(vault read -format=json database/creds/todo-app)"
export DB_LEASE_ID="$(printf '%s' "${DB_CREDS_JSON}" | jq -r '.lease_id')"
export DB_USERNAME="$(printf '%s' "${DB_CREDS_JSON}" | jq -r '.data.username')"
export DB_PASSWORD="$(printf '%s' "${DB_CREDS_JSON}" | jq -r '.data.password')"

printf 'Lease: %s\nUsername: %s\n' "${DB_LEASE_ID}" "${DB_USERNAME}"
```

Đăng nhập PostgreSQL bằng credential vừa được Vault tạo:

```bash
docker compose exec -T postgres \
  env PGPASSWORD="${DB_PASSWORD}" \
  psql -h 127.0.0.1 -U "${DB_USERNAME}" -d todo \
  -c 'SELECT session_user, current_user, current_database();'
```

Thu hồi lease và xóa biến chứa secret khỏi shell:

```bash
vault lease revoke "${DB_LEASE_ID}"
unset DB_CREDS_JSON DB_LEASE_ID DB_USERNAME DB_PASSWORD
```

Sau revoke, login role tương ứng phải bị Vault drop khỏi PostgreSQL.

## 2. Cài Vault Secrets Operator

```bash
helm repo add hashicorp https://helm.releases.hashicorp.com --force-update
helm repo update hashicorp

helm upgrade --install vault-secrets-operator \
  hashicorp/vault-secrets-operator \
  --version 1.5.1 \
  --namespace vault-secrets-operator-system \
  --create-namespace \
  --wait
```

Kiểm tra operator và CRD:

```bash
kubectl -n vault-secrets-operator-system get pods
kubectl get crd | grep secrets.hashicorp.com
```

## 3. Tạo identity để Vault xác thực Kubernetes JWT

Xem manifest trước khi apply:

```bash
cat k8s/vso/serviceaccounts.yaml
cat k8s/vso/vault-token-reviewer-secret.yaml
cat k8s/vso/vault-auth-delegator-binding.yaml
```

Apply từng nhóm tài nguyên:

```bash
kubectl apply -f k8s/base/namespace.yaml
kubectl -n vault-demo apply -f k8s/vso/serviceaccounts.yaml
kubectl -n vault-demo apply -f k8s/vso/vault-token-reviewer-secret.yaml
kubectl apply -f k8s/vso/vault-auth-delegator-binding.yaml
```

Ý nghĩa:

- `todo-vso-auth`: VSO yêu cầu một JWT audience `vault` để login Vault.
- `vault-auth-reviewer`: Vault dùng token này để gọi Kubernetes TokenReview.
- `system:auth-delegator`: cho phép reviewer thực hiện TokenReview.

Đợi Kubernetes cấp reviewer token rồi lấy token và CA:

```bash
kubectl -n vault-demo get secret vault-auth-reviewer-token

export REVIEWER_JWT="$(kubectl -n vault-demo get secret vault-auth-reviewer-token \
  -o jsonpath='{.data.token}' | openssl base64 -d -A)"

export KUBERNETES_CA_CERT="$(kubectl -n vault-demo get secret vault-auth-reviewer-token \
  -o jsonpath='{.data.ca\.crt}' | openssl base64 -d -A)"
```

Nếu chưa đặt `KUBERNETES_HOST_FOR_VAULT`, xem endpoint trong kubeconfig:

```bash
kubectl config view --raw --minify \
  -o jsonpath='{.clusters[0].cluster.server}'; echo
```

Không dùng `127.0.0.1` hoặc `localhost`: endpoint phải được các Vault node truy
cập được.

## 4. Cấu hình Kubernetes Auth Method trên Vault

Xem auth methods hiện tại:

```bash
vault auth list
```

Nếu chưa có `kubernetes-vso/`, enable auth method:

```bash
vault auth enable -path=kubernetes-vso kubernetes
```

Cấu hình cách Vault gọi Kubernetes TokenReview:

```bash
vault write auth/kubernetes-vso/config \
  token_reviewer_jwt="${REVIEWER_JWT}" \
  kubernetes_host="${KUBERNETES_HOST_FOR_VAULT}" \
  kubernetes_ca_cert="${KUBERNETES_CA_CERT}"
```

Bind đúng namespace, ServiceAccount, audience và Vault policy:

```bash
vault write auth/kubernetes-vso/role/todo-app \
  bound_service_account_names=todo-vso-auth \
  bound_service_account_namespaces=vault-demo \
  audience=vault \
  policies=todo-app \
  token_ttl=10m \
  token_max_ttl=1h
```

Sau khi ghi config xong, bỏ reviewer token khỏi shell hiện tại:

```bash
unset REVIEWER_JWT KUBERNETES_CA_CERT
```

## 5. Khai báo VSO resources trên Kubernetes

Đọc [resources.yaml](../k8s/vso/resources.yaml) và chú ý ba resource:

- `VaultConnection`: endpoint Vault mà VSO Pod truy cập.
- `VaultAuth`: mount `kubernetes-vso`, role `todo-app`, SA `todo-vso-auth`.
- `VaultDynamicSecret`: đọc `database/creds/todo-app` và cập nhật Secret phase 1.

Apply tài nguyên:

```bash
kubectl apply -k k8s/vso
```

Manifest để một địa chỉ placeholder nhằm tránh commit endpoint môi trường. Patch
địa chỉ Vault mà **VSO Pod** truy cập được:

```bash
kubectl -n vault-demo patch vaultconnection external-vault \
  --type=merge \
  -p "{\"spec\":{\"address\":\"${VSO_VAULT_ADDR}\"}}"
```

Nếu Vault dùng private CA:

```bash
kubectl -n vault-demo create secret generic external-vault-ca \
  --from-file=ca.crt="${VAULT_CACERT}" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl -n vault-demo patch vaultconnection external-vault \
  --type=merge \
  -p '{"spec":{"caCertSecretRef":"external-vault-ca","skipTLSVerify":false}}'
```

Với HTTP như lab nội bộ thì không cần CA. Không nên dùng `skipTLSVerify` ngoài
môi trường thử nghiệm tạm thời.

## 6. Quan sát VSO thay bootstrap credential

```bash
kubectl -n vault-demo get vaultconnection,vaultauth,vaultdynamicsecret
kubectl -n vault-demo describe vaultdynamicsecret todo-database
kubectl -n vault-demo get events --sort-by=.lastTimestamp
kubectl -n vault-secrets-operator-system logs deployment/vault-secrets-operator-controller-manager -f
```

Đọc username và marker trong Secret:

```bash
kubectl -n vault-demo get secret todo-database-credentials \
  -o jsonpath='{.data.username}' | openssl base64 -d -A; echo

kubectl -n vault-demo get secret todo-database-credentials \
  -o jsonpath='{.data.managed_by}' | openssl base64 -d -A; echo
```

Kết quả mong đợi:

- Username không còn là `todo_bootstrap`.
- `managed_by` bằng `vso`.
- Backend log một lần rotate connection pool.
- Todo CRUD vẫn hoạt động tại `http://<k8s-node-ip>:30085`.

Xem backend nhận credential mới:

```bash
kubectl -n vault-demo logs deployment/backend -f
curl -s http://<k8s-node-ip>:30085/api/vault/status | jq
```

## 7. Tắt bootstrap login sau khi VSO thành công

Kiểm tra username lần cuối. Chỉ tiếp tục nếu nó không phải `todo_bootstrap`:

```bash
export CURRENT_DB_USERNAME="$(kubectl -n vault-demo get secret todo-database-credentials \
  -o jsonpath='{.data.username}' | openssl base64 -d -A)"
printf '%s\n' "${CURRENT_DB_USERNAME}"
```

Tắt quyền LOGIN của bootstrap user:

```bash
docker compose exec -T postgres \
  psql -v ON_ERROR_STOP=1 -U postgres -d todo \
  -c 'ALTER ROLE todo_bootstrap NOLOGIN;'

unset CURRENT_DB_USERNAME
```

Từ thời điểm này app phụ thuộc hoàn toàn vào dynamic credential do VSO quản lý.

## Troubleshooting theo từng ranh giới

```bash
# VSO resources và events
kubectl -n vault-demo describe vaultconnection external-vault
kubectl -n vault-demo describe vaultauth todo-vso-auth
kubectl -n vault-demo describe vaultdynamicsecret todo-database

# VSO controller logs
kubectl -n vault-secrets-operator-system logs \
  deployment/vault-secrets-operator-controller-manager --tail=200

# Vault có tạo credential DB được không?
vault read database/creds/todo-app

# Vault Kubernetes auth role/config đã tồn tại chưa?
vault read auth/kubernetes-vso/config
vault read auth/kubernetes-vso/role/todo-app
```

Phân loại lỗi theo hướng kết nối:

- `connection refused/timeout` ở `VaultConnection`: VSO Pod không tới Vault.
- `permission denied` khi login: role, ServiceAccount, namespace, audience hoặc
  policy không khớp.
- `TokenReview` lỗi: Vault node không tới Kubernetes API, reviewer JWT/RBAC/CA
  sai.
- Database plugin lỗi: Vault node không tới PostgreSQL hoặc admin credential sai.
