# VSO reads credentials; the backend reads the resulting Kubernetes Secret.
# Match the database mount and role configured in Vault and VaultDynamicSecret.
path "todo-db/creds/todo-db-roles" {
  capabilities = ["read"]
}

# Renew leases accessible to the VSO token (subject to Vault lease ownership).
path "sys/leases/renew" {
  capabilities = ["update"]
}

# Revoke leases when VSO releases credentials (revoke: true).
path "sys/leases/revoke" {
  capabilities = ["update"]
}
