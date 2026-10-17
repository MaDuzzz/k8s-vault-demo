# Phase 2: tích hợp Kubernetes với Vault qua VSO

Vault tạo credential PostgreSQL có lease → VSO ghi vào Kubernetes Secret →
backend đọc Secret và đổi connection pool. Backend không gọi Vault trực tiếp.

**Điều kiện:** Phase 1 đã chạy được (PostgreSQL, backend và Secret bootstrap).
Chạy các lệnh từ thư mục gốc repo. Hoàn tất phần A và kiểm tra đăng nhập DB
trước khi chuyển sang phần B.

## Namespace: tạo tài nguyên ở đâu?

Trong lab này, **app ở `vault-demo`, VSO operator ở `vso-sys`**.
Thư mục `k8s/vso` chứa cấu hình VSO cho app nên được apply vào `vault-demo`.

| Namespace / phạm vi              | Thành phần và ràng buộc                                                                                                                                                                                                                                                 |
| -------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| App: `vault-demo`                | Backend và Secret `todo-database-credentials` **phải cùng namespace** để Pod mount Secret. `VaultDynamicSecret` phải cùng namespace với Secret đích.                                                                                                                    |
| App: `vault-demo`                | `VaultAuth`, ServiceAccount `todo-vso-auth`, `VaultConnection` nằm cùng namespace với `VaultDynamicSecret` trong cấu hình này, vì các tham chiếu đang dùng tên không kèm namespace. ServiceAccount dùng để login phải cùng namespace với `VaultAuth`.                   |
| App: `vault-demo`                | Secret CA `external-vault-ca` (nếu dùng TLS) phải cùng namespace với `VaultConnection`.                                                                                                                                                                                 |
| Reviewer: `vault-demo` trong lab | ServiceAccount `vault-auth-reviewer` và Secret `vault-auth-reviewer-token` **phải cùng namespace**. Reviewer có thể ở namespace riêng nếu sửa các lệnh lấy token và `ClusterRoleBinding.subjects[].namespace` cho khớp; không bắt buộc cùng namespace với app/operator. |
| Operator: `vso-sys`              | Helm release, Deployment/Pod và ServiceAccount của **VSO controller**. ServiceAccount này khác `todo-vso-auth` của app.                                                                                                                                                 |
| Toàn cluster                     | CRD, `ClusterRole` và `ClusterRoleBinding` không thuộc namespace. Subject ServiceAccount trong binding vẫn phải ghi đúng namespace của reviewer.                                                                                                                        |
| Trong Vault                      | DB engine, policy và Kubernetes auth role không thuộc namespace Kubernetes. `bound_service_account_namespaces=vault-demo` phải là namespace của SA `todo-vso-auth`, không phải namespace operator.                                                                      |

> Khi đổi namespace app, cập nhật manifest Phase 1, `k8s/vso/kustomization.yaml`,
> các lệnh `kubectl -n` cho app và cấu hình auth role trong Vault. Nếu reviewer
> chuyển theo app, cập nhật cả RBAC subject. Khi đổi namespace operator, cập nhật
> `helm --namespace` và các lệnh xem Pod/log controller.

## Các giá trị phải khớp khi đổi tên

Các tên dưới đây là ví dụ đang dùng trong repo. Khi đổi, sửa cả nơi khai báo
và nơi tham chiếu; đổi biến shell không tự cập nhật manifest hoặc policy.

| Giá trị                                     | Các nơi phải khớp                                                                                                                                      |
| ------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| DB mount `d_lab`                            | `vault secrets enable -path`, các lệnh `config/roles/creds`, path trong `infra/vault/bootstrap/policies/todo-app.hcl`, `VaultDynamicSecret.spec.mount` |
| DB config `todo-postgres`                   | Tên sau `config/` và `db_name` khi tạo DB role                                                                                                         |
| DB role `todo-roles`                        | `allowed_roles`, tên sau `roles/` và `creds/`, path trong policy, `VaultDynamicSecret.spec.path=creds/todo-roles`                                      |
| Policy `todo-app`                           | Tên trong `vault policy write` và `policies` của Kubernetes auth role                                                                                  |
| Auth mount `kubernetes-vso`                 | `vault auth enable -path`, đường dẫn `auth/...`, `VaultAuth.spec.mount`                                                                                |
| Auth role `todo-roles`                      | Tên sau `auth/kubernetes-vso/role/` và `VaultAuth.spec.kubernetes.role`                                                                                |
| Namespace `vault-demo`                      | Namespace từ Phase 1, các lệnh `kubectl -n`, VSO kustomization, RBAC subject và `bound_service_account_namespaces`                                     |
| ServiceAccount `todo-vso-auth`              | `serviceaccounts.yaml`, `bound_service_account_names`, `VaultAuth.spec.kubernetes.serviceAccount`                                                      |
| Reviewer SA `vault-auth-reviewer`           | `serviceaccounts.yaml`, annotation của reviewer Secret và RBAC subject                                                                                 |
| Reviewer Secret `vault-auth-reviewer-token` | `metadata.name` trong manifest và các lệnh lấy token/CA                                                                                                |
| Audience `vault`                            | `audience` của auth role và `VaultAuth.spec.kubernetes.audiences`                                                                                      |
| VaultConnection `external-vault`            | `metadata.name`, `VaultAuth.spec.vaultConnectionRef`, các lệnh patch/describe                                                                          |
| VaultAuth `todo-vso-auth`                   | `metadata.name`, `VaultDynamicSecret.spec.vaultAuthRef`, lệnh describe                                                                                 |
| VaultDynamicSecret `todo-database`          | `metadata.name` và các lệnh describe                                                                                                                   |
| Secret `todo-database-credentials`          | Secret từ Phase 1, `VaultDynamicSecret.spec.destination.name` và `backend.spec.template.spec.volumes[].secret.secretName`                              |
| Database `todo`, PostgreSQL role `todo_app` | DB từ Phase 1, database trong connection URL/lệnh `psql`; role `todo_app` trong SQL creation template phải tồn tại từ Phase 1                          |

> DB role và auth role dùng tên `todo-roles`; policy vẫn là `todo-app`.
> Đây là ba đối tượng riêng; từng tham chiếu ở trên phải khớp đúng đối tượng.
> ServiceAccount và VaultAuth cũng là hai đối tượng riêng dù cùng tên.

Sơ đồ tham khảo: [tổng quan](diagrams/vso-demo-overview.mmd) ·
[runtime](diagrams/vso-runtime-sequence.mmd).

## A. Vault tạo credential PostgreSQL

### A0. Khai báo giá trị của môi trường lab

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

### A1. Cấu hình Vault Database Secrets Engine

Xem các secrets engine hiện tại:

```bash
vault secrets list
```

Nếu chưa có mount `todo-db/`, enable nó:

```bash
vault secrets enable -path=todo-db database
```

Khai báo kết nối từ Vault tới PostgreSQL Docker. `{{username}}` và
`{{password}}` là placeholder dành cho Vault database plugin, không phải shell
variable:

```bash
vault write todo-db/config/todo-postgres \
  plugin_name=postgresql-database-plugin \
  allowed_roles=todo-db-roles \
  connection_url="postgresql://{{username}}:{{password}}@${VAULT_DB_ADDRESS}/todo?sslmode=disable" \
  username=postgres \
  password="${POSTGRES_ADMIN_PASSWORD}" \
  verify_connection=true \
  max_open_connections=4 \
  max_connection_lifetime=0s

sample: vault write todo-db/config/todo-postgres plugin_name=postgresql-database-plugin allowed_roles=todo-db-roles connection_url="postgresql://{{username}}:{{password}}@192.168.180.1:5432/todo?sslmode=disable" username=postgres password=adminpassword verify_connection=true max_open_connections=4 max_connection_lifetime=0s
check_result: vault read todo-db/config/todo-postgres
```

Tạo dynamic role với TTL ngắn để dễ quan sát demo:

```bash
vault write todo-db/roles/todo-db-roles \
  db_name=todo-postgres \
  default_ttl=30s \
  max_ttl=2m \
  creation_statements=@infra/vault/bootstrap/sql/create-user.sql \
  renew_statements=@infra/vault/bootstrap/sql/renew-user.sql \
  revocation_statements=@infra/vault/bootstrap/sql/revoke-user.sql

sample: vault write todo-db/roles/todo-db-roles db_name=todo-postgres default_ttl=30s max_ttl=2m creation_statements=@infra/vault/bootstrap/sql/create-user.sql renew_statements=@infra/vault/bootstrap/sql/renew-user.sql revocation_statements=@infra/vault/bootstrap/sql/revoke-user.sql
check_result: vault read todo-db/roles/todo-db-roles
```

`verify_connection=true` buộc Vault kết nối DB ngay. Nếu lệnh này lỗi, kiểm tra
route/firewall từ **Vault node** tới `${VAULT_DB_ADDRESS}` trước khi làm tiếp.

> **Khớp tên:** `db_name` phải là config `todo-postgres` vừa tạo; tên role
> `todo-roles` phải có trong `allowed_roles`. SQL dùng PostgreSQL role
> `todo_app` đã tạo ở Phase 1.


#### Ba file SQL làm gì?

Vault dùng các template này để tạo, gia hạn và thu hồi user PostgreSQL.
Cú pháp `@infra/...sql` trong lệnh trên yêu cầu Vault CLI đọc nội dung file
local và gửi lên Vault; không cần chép file sang Vault node.

`{{name}}`, `{{password}}`, `{{expiration}}` được Vault thay bằng username,
password và thời điểm hết hạn thực tế. Giữ nguyên các placeholder này.

**[create-user.sql](../infra/vault/bootstrap/sql/create-user.sql)** — chạy khi
Vault cấp credential mới:

```sql
CREATE ROLE "{{name}}" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}';
GRANT todo_app TO "{{name}}";
ALTER ROLE "{{name}}" SET ROLE TO 'todo_app';
```

- `CREATE ROLE ... LOGIN`: tạo user đăng nhập bằng password, có thời hạn.
- `GRANT todo_app`: cho user trở thành thành viên role quyền ứng dụng đã tạo
  ở Phase 1.
- `SET ROLE`: cấu hình để phiên đăng nhập mới tự dùng role `todo_app`.
  Object do app tạo sẽ thuộc `todo_app`, tránh phụ thuộc vào user tạm thời
  khi Vault thu hồi user đó.

**[renew-user.sql](../infra/vault/bootstrap/sql/renew-user.sql)** — chạy khi
lease được gia hạn:

```sql
ALTER ROLE "{{name}}" VALID UNTIL '{{expiration}}';
```

Cập nhật hạn dùng password của user hiện tại; không đổi username/password.
Lease chỉ được renew trong giới hạn `max_ttl`; sau đó cần credential mới.

**[revoke-user.sql](../infra/vault/bootstrap/sql/revoke-user.sql)** — chạy khi
Vault thu hồi lease, kể cả khi lease hết hạn:

```sql
DROP ROLE IF EXISTS "{{name}}";
```

Xóa user tạm thời; `IF EXISTS` tránh lỗi nếu user đã bị xóa. Membership và
cấu hình riêng của user cũng được xóa. Lệnh này không chủ động ngắt các
session DB đang mở; `VALID UNTIL` cũng chỉ kiểm soát đăng nhập bằng password.

> **Khớp tên:** PostgreSQL role `todo_app` trong SQL phải tồn tại từ Phase 1.
> Đây là role quyền DB (`NOLOGIN`), khác Vault DB role `todo-roles`.
> Nếu đổi tên `todo_app`, sửa cả bootstrap PostgreSQL và hai dòng
> `GRANT` / `SET ROLE` trong `create-user.sql`.

Policy `todo-app` cho phép VSO đọc credential và quản lý lease:

```bash
vault policy write todo-policy infra/vault/bootstrap/policies/todo-app.hcl
```

### A2. Kiểm tra dynamic credential bằng tay

```bash
vault read todo-db/creds/todo-db-roles
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

## B. VSO đồng bộ credential vào Kubernetes

### B1. Cài Vault Secrets Operator

> Cài controller vào `vso-sys`; các resource cấu hình cho app ở B2/B4 vẫn
> được tạo trong `vault-demo`.

```bash
helm repo add hashicorp https://helm.releases.hashicorp.com --force-update
helm repo update hashicorp

helm upgrade --install vault-secrets-operator \
  hashicorp/vault-secrets-operator \
  --version 1.6.0 \
  --namespace vso-sys \
  --create-namespace \
  --set controller.manager.clientCache.numLocks=100 \
  --wait
```

`clientCache.numLocks=100` đặt rõ giá trị mặc định của VSO và tránh lỗi render
`incompatible types for comparison` của template chart khi dùng các phiên bản
Helm mới.

Kiểm tra operator và CRD:

```bash
kubectl -n vso-sys get pods
kubectl get crd | grep secrets.hashicorp.com
```

### B2. Tạo identity để Vault xác thực Kubernetes JWT

> Hai ServiceAccount và reviewer Secret ở `vault-demo` theo cấu hình lab.
> ClusterRoleBinding ở phạm vi cluster, nhưng subject phải trỏ tới reviewer
> ServiceAccount trong `vault-demo`.

Apply từng nhóm tài nguyên:

```bash
kubectl -n vault-demo apply -f k8s/vso/serviceaccounts.yaml
kubectl -n vault-demo apply -f k8s/vso/vault-token-reviewer-secret.yaml
kubectl apply -f k8s/vso/vault-auth-delegator-binding.yaml
```

`vault-auth-reviewer` dùng TokenReview với quyền `system:auth-delegator`;
`todo-vso-auth` là identity để VSO login Vault.

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

### B3. Cấu hình Kubernetes Auth Method trên Vault

Xem auth methods hiện tại:

```bash
vault auth list
```

Nếu chưa có `k-vso/`, enable auth method:

```bash
vault auth enable -path=k-vso kubernetes
```

Cấu hình cách Vault gọi Kubernetes TokenReview:

```bash
vault write auth/k-vso/config \
  token_reviewer_jwt="${REVIEWER_JWT}" \
  kubernetes_host="${KUBERNETES_HOST_FOR_VAULT}" \
  kubernetes_ca_cert="${KUBERNETES_CA_CERT}"

sample: vault write auth/k-vso/config token_reviewer_jwt="" kubernetes_host="https://192.168.180.100:6443" kubernetes_ca_cert=""
```

> **Khớp giá trị:** ServiceAccount và namespace lấy từ B2; `policies` là
> policy đã ghi ở A1. Tên auth role và audience phải khớp `VaultAuth` ở B4.

Bind namespace, ServiceAccount, audience và Vault policy:

```bash
vault write auth/k-vso/role/todo-app-roles \
  bound_service_account_names=todo-vso-auth \
  bound_service_account_namespaces=todo-app \
  audience=vault \
  policies=todo-policy \
  token_ttl=10m \
  token_max_ttl=1h
```

Sau khi ghi config xong, bỏ reviewer token khỏi shell hiện tại:

```bash
unset REVIEWER_JWT KUBERNETES_CA_CERT
```

### B4. Khai báo VSO resources trên Kubernetes

Đọc [resources.yaml](../k8s/vso/resources.yaml) và chú ý ba resource:

- `VaultConnection`: endpoint Vault mà VSO Pod truy cập.
- `VaultAuth`: mount `kubernetes-vso`, role `todo-roles`, SA `todo-vso-auth`.
- `VaultDynamicSecret`: đọc `d_lab/creds/todo-roles` và cập nhật Secret phase 1.

> **Kiểm tra trước khi apply:** `destination.create=false` yêu cầu Secret
> Phase 1 đã tồn tại và `destination.name=todo-database-credentials`. Nếu
> thiếu tên này trong `resources.yaml`, bổ sung trước khi chạy; tên phải khớp
> Secret backend đang mount. Kiểm tra các tham chiếu trong bảng đầu tài liệu.

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

### B5. Quan sát VSO thay bootstrap credential

```bash
kubectl -n vault-demo get vaultconnection,vaultauth,vaultdynamicsecret
kubectl -n vault-demo describe vaultdynamicsecret todo-database
kubectl -n vault-demo get events --sort-by=.lastTimestamp
kubectl -n vso-sys logs deployment/vault-secrets-operator-controller-manager -f
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

### B6. Tắt bootstrap login sau khi VSO thành công

Chỉ tắt bootstrap login khi username đã đổi, `managed_by=vso` và Todo CRUD
đã hoạt động. Nếu đổi tên bootstrap user ở Phase 1, dùng đúng tên đó trong
lệnh `ALTER ROLE` dưới đây.

Kiểm tra username lần cuối:

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
kubectl -n vso-sys logs \
  deployment/vault-secrets-operator-controller-manager --tail=200

# Vault có tạo credential DB được không?
vault read d_lab/creds/todo-roles

# Vault Kubernetes auth role/config đã tồn tại chưa?
vault read auth/kubernetes-vso/config
vault read auth/kubernetes-vso/role/todo-roles

kubectl exec etcd-master01 -n kube-system -- etcdctl \
  --endpoints=https://127.0.0.1:2379 \
  --cacert=/etc/kubernetes/pki/etcd/ca.crt \
  --cert=/etc/kubernetes/pki/etcd/server.crt \
  --key=/etc/kubernetes/pki/etcd/server.key \
  get /registry/secrets/todo-app/todo-database-credentials | strings | grep -o '{"password".*}' | jq .
```

Phân loại lỗi theo hướng kết nối:

- `connection refused/timeout` ở `VaultConnection`: VSO Pod không tới Vault.
- `permission denied` khi login: role, ServiceAccount, namespace, audience hoặc
  policy không khớp.
- `TokenReview` lỗi: Vault node không tới Kubernetes API, reviewer JWT/RBAC/CA
  sai.
- Database plugin lỗi: Vault node không tới PostgreSQL hoặc admin credential sai.
