# Todo backend

Spring Boot 3 / Java 21 API dùng PostgreSQL credential từ một Kubernetes Secret
projected thành file. Backend không gọi Vault API và không giữ Vault token.

Watcher đọc file mỗi hai giây. Khi username, password fingerprint hoặc marker
provider đổi, backend tạo và validate Hikari pool mới, atomically chuyển request
mới sang pool đó rồi drain pool cũ. Vì vậy cùng một Pod chạy được cả hai phase:

- `managed_by=bootstrap`: static credential, không hiển thị lease timing.
- `managed_by=vso`: dynamic credential, hiển thị thời gian renew/rotate ước tính.

## Build

```bash
mvn test
docker build -t todo-backend:demo .
```

## Configuration

| Environment variable | Default | Mục đích |
| --- | --- | --- |
| `DB_CREDENTIAL_USERNAME_FILE` | `/var/run/secrets/database-credentials/username` | File username |
| `DB_CREDENTIAL_PASSWORD_FILE` | `/var/run/secrets/database-credentials/password` | File password |
| `DB_CREDENTIAL_PROVIDER_FILE` | `/var/run/secrets/database-credentials/managed_by` | `bootstrap` hoặc `vso` |
| `DB_CREDENTIAL_SECRET_NAME` | `todo-database-credentials` | Nhãn nguồn an toàn cho status |
| `DB_CREDENTIAL_POLL_INTERVAL` | `2s` | Chu kỳ kiểm tra file |
| `DB_CREDENTIAL_STARTUP_TIMEOUT` | `30s` | Thời gian chờ credential đầu tiên |
| `DB_CREDENTIAL_LEASE_DURATION` | `30s` | TTL dùng để ước tính khi provider là VSO |
| `DB_CREDENTIAL_RENEWAL_PERCENT` | `67` | Đồng bộ với `VaultDynamicSecret` |
| `DB_CREDENTIAL_MAX_TTL` | `2m` | Thời điểm rotate ước tính |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5432` / `todo` | PostgreSQL target |
| `DB_URL` | sinh từ các field trên | JDBC URL override tùy chọn |

Pool có thể tune bằng `DB_POOL_MAX_SIZE`, `DB_POOL_MIN_IDLE`,
`DB_POOL_MAX_LIFETIME`, `DB_CONNECTION_TIMEOUT`, `DB_VALIDATION_TIMEOUT` và
`DB_POOL_DRAIN_GRACE`.

## HTTP API

- `GET/POST /api/todos`
- `PUT/DELETE /api/todos/{id}`
- `GET /api/vault/status`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`

Status API trả username, password fingerprint, provider, generation và timing
ước tính; không bao giờ trả password thật. `VaultDynamicSecret` events/status là
nguồn authoritative cho hoạt động VSO.

Todo requests được log ở mức `INFO` theo dạng `method URI -> status (duration)`.
Request body và credential không được ghi log; endpoint status polling cũng được
bỏ qua để tránh làm ngập log.
