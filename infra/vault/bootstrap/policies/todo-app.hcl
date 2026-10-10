# The application can obtain and renew only its database lease.
path "b_lab/creds/todo-app" {
  capabilities = ["read"]
}

path "sys/leases/renew" {
  capabilities = ["update"]
}

path "sys/leases/revoke" {
  capabilities = ["update"]
}
