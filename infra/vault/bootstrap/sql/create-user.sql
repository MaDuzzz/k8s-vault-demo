CREATE ROLE "{{name}}" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}';
GRANT todo_app TO "{{name}}";
ALTER ROLE "{{name}}" SET ROLE TO 'todo_app';
