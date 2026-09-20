-- Memberships and per-role settings are removed automatically. Because all
-- application objects are owned by todo_app, the ephemeral login has no object
-- dependencies and can be dropped directly. IF EXISTS makes retries harmless.
DROP ROLE IF EXISTS "{{name}}";
