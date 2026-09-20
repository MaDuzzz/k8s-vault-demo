# Hạ tầng của bài lab

Repo không còn dựng Vault bằng Docker Compose. Cụm Vault HA và cụm Kubernetes
được xem là tài nguyên có sẵn; PostgreSQL, backend và frontend chạy trong
namespace `vault-demo`.

## Hai giai đoạn

Giai đoạn 1 dùng `todo_bootstrap` từ Secret `todo-database-credentials` để xác
nhận app kết nối PostgreSQL bình thường. PostgreSQL có role ổn định
`todo_app` (NOLOGIN), sở hữu schema/object; bootstrap user và các dynamic user
chỉ là login role được grant `todo_app`. Vì object không thuộc dynamic role,
Vault có thể drop role cũ khi lease hết hạn.

Giai đoạn 2 dùng:

- `VaultConnection/external-vault`: endpoint của cụm Vault có sẵn.
- `VaultAuth/todo-vso-auth`: Kubernetes auth mount `kubernetes-vso`.
- `VaultDynamicSecret/todo-database`: đọc `database/creds/todo-app` và cập nhật
  Secret đã có.
- `vault-auth-reviewer`: service account chỉ dùng cho Vault TokenReview.

Backend service account không auto-mount Kubernetes token. Backend chỉ đọc
projected Secret files và thay connection pool khi nội dung file đổi.

## Ranh giới mạng

```text
VSO Pod  --------------------> Vault API :8200
Vault node ------------------> Kubernetes API (TokenReview)
Vault node ------------------> PostgreSQL endpoint :5432
Backend ---------------------> postgres.vault-demo.svc:5432
```

`k8s/addons/postgres-nodeport.yaml` mở port 30432 trên Kubernetes node để tiện
lab. Với môi trường thật, dùng private LoadBalancer/routing, TLS PostgreSQL,
NetworkPolicy và firewall chỉ cho phép Vault node truy cập.

## Secret strategy

- PostgreSQL admin password và bootstrap password đến từ `.env`, không commit.
- VSO ghi dynamic username/password vào Kubernetes Secret; do đó cần bật mã hóa
  etcd at rest và giới hạn RBAC đọc Secret.
- UI chỉ nhận password fingerprint, không nhận password thô.
- Private CA được tạo thành `Secret/external-vault-ca` khi có `VAULT_CACERT`.
- Reviewer token hiện là long-lived service-account token để bài lab dễ quan
  sát; production cần quy trình rotation và audit thích hợp.

Nếu VSO được dùng lâu dài với dynamic leases, cân nhắc persistent encrypted
client cache để operator restart không làm mất trạng thái lease.
