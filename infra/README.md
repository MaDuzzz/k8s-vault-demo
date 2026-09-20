# Hạ tầng bài lab

PostgreSQL chạy bằng Docker Compose trên một host/node và publish cổng `5432`.
Frontend, backend và VSO chạy trên Kubernetes. Vault HA là cụm có sẵn bên ngoài
repo.

```text
Backend Pod --------------------> Docker host/node :5432
Vault nodes --------------------> Docker host/node :5432
VSO Pod ------------------------> Vault API :8200
Vault nodes --------------------> Kubernetes API (TokenReview)
```

`APP_DB_HOST` là địa chỉ PostgreSQL nhìn từ Kubernetes Pods.
`VAULT_DB_ADDRESS` là địa chỉ PostgreSQL nhìn từ Vault nodes. Hai giá trị có thể
khác nhau nếu hệ thống dùng NAT hoặc các network segment khác nhau.

PostgreSQL init tạo NOLOGIN role `todo_app` làm owner ổn định. Bootstrap user và
dynamic users chỉ là LOGIN role được grant `todo_app`; vì vậy Vault có thể drop
dynamic user khi lease hết hạn mà không vướng ownership của Flyway objects.

Dynamic username/password được VSO ghi vào Kubernetes Secret và projected vào
backend. Cần giới hạn RBAC đọc Secret, bật mã hóa etcd at rest, dùng TLS và chỉ
mở firewall PostgreSQL cho Kubernetes/Vault nodes. Bind `0.0.0.0:5432` trong
repo nhằm phục vụ lab; không nên expose ra Internet.
