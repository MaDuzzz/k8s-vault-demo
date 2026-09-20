#!/usr/bin/env bash
set -Eeuo pipefail

psql -v ON_ERROR_STOP=1 \
  --username "${POSTGRES_USER}" \
  --dbname "${POSTGRES_DB}" \
  --set=bootstrap_password="${TODO_BOOTSTRAP_PASSWORD}" <<'EOSQL'
DO
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'todo_app') THEN
        CREATE ROLE todo_app NOLOGIN;
    END IF;
END
$$;

GRANT CONNECT, TEMPORARY ON DATABASE todo TO todo_app;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO todo_app;
GRANT USAGE, CREATE ON SCHEMA public TO todo_app;

SELECT format('CREATE ROLE todo_bootstrap WITH LOGIN PASSWORD %L', :'bootstrap_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'todo_bootstrap') \gexec
SELECT format('ALTER ROLE todo_bootstrap WITH LOGIN PASSWORD %L', :'bootstrap_password') \gexec
GRANT todo_app TO todo_bootstrap;
ALTER ROLE todo_bootstrap SET ROLE TO 'todo_app';
EOSQL
