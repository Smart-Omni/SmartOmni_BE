#!/bin/sh
# Infrastructure bootstrap only. Flyway creates all business tables on service startup.
set -eu
: "${DB_APP_PASSWORD:?DB_APP_PASSWORD is required}"
: "${DB_MIGRATION_PASSWORD:?DB_MIGRATION_PASSWORD is required}"

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 \
  --set app_password="$DB_APP_PASSWORD" --set migration_password="$DB_MIGRATION_PASSWORD" <<'SQL'
SELECT 'CREATE ROLE smartomni_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'smartomni_migrator') \gexec
SELECT 'CREATE ROLE smartomni_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'smartomni_app') \gexec
ALTER ROLE smartomni_migrator PASSWORD :'migration_password';
ALTER ROLE smartomni_app PASSWORD :'app_password';
ALTER SCHEMA public OWNER TO smartomni_migrator;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO smartomni_app;
SQL
