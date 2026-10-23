#!/usr/bin/env bash
# First boot of a NEW PostGIS volume only. No repair/reset of an existing database.
set -eu

: "${POSTGRES_USER:?Missing migration administrator}"
: "${POSTGRES_PASSWORD:?Missing migration administrator password}"
: "${PG_DB:?Missing application database name}"
: "${PG_APP_USER:?Missing application role}"
: "${PG_APP_PASSWORD:?Missing application password}"

for init_identifier in "$PG_DB" "$PG_APP_USER" "$POSTGRES_USER"; do
  if [[ ! "$init_identifier" =~ ^[a-z_][a-z0-9_]{0,62}$ ]]; then
    echo "Database and role names must be lowercase identifiers of 1 to 63 characters" >&2
    exit 1
  fi
done

case "$PG_DB" in
  postgres|template0|template1) echo "Choose a separate application database" >&2; exit 1 ;;
esac
if [ "$PG_APP_USER" = "$POSTGRES_USER" ]; then
  echo "The application and migration roles must be different" >&2
  exit 1
fi

# psql identifier/literal variables safely quote values without echoing secrets.
psql --username "$POSTGRES_USER" --dbname postgres --set=ON_ERROR_STOP=1 <<'SQL'
\getenv app_user PG_APP_USER
\getenv app_password PG_APP_PASSWORD
SET log_statement = 'none';
SET log_min_error_statement = 'panic';
CREATE ROLE :"app_user" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD :'app_password';
SQL

# The image initializes PostGIS in postgres; the application starts from template0.
createdb --username "$POSTGRES_USER" --owner "$POSTGRES_USER" \
  --template=template0 -- "$PG_DB"

psql --username "$POSTGRES_USER" --dbname "$PG_DB" --set=ON_ERROR_STOP=1 \
  --set=app_db="$PG_DB" --set=app_user="$PG_APP_USER" <<'SQL'
REVOKE ALL ON DATABASE :"app_db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"app_db" TO :"app_user";
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO :"app_user";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app_user";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO :"app_user";
SQL
