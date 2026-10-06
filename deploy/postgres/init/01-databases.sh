#!/bin/sh
# Runs once, when the postgres container initialises an empty data volume
# (docker-entrypoint-initdb.d). Creates one database and one login role per
# service, so the engine cannot read the backend's tables and the other way
# round (docs/security.md: internal services share only what they must).
# Each role owns its database and Flyway creates the tables in it on the
# service's first start.
#
# Passwords come from the container environment (docker-compose.yml, fed by
# .env). They are passed to psql as variables and quoted by psql itself
# (:'name'), so a password cannot break out of the SQL string.
#
# Changing a password later: this script does not run again on an existing
# volume. Use ALTER ROLE ... PASSWORD in psql, or `docker compose down -v`
# (which deletes all data).
#
# deploy/postgres/init/01-databases.sh is a copy for the pull-only VM bundle;
# keep the two identical.
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  -v engine_pw="$ENGINE_DB_PASSWORD" \
  -v backend_pw="$BACKEND_DB_PASSWORD" <<'SQL'
CREATE ROLE cib7 LOGIN PASSWORD :'engine_pw';
CREATE DATABASE cib7 OWNER cib7;
REVOKE ALL ON DATABASE cib7 FROM PUBLIC;

CREATE ROLE backend LOGIN PASSWORD :'backend_pw';
CREATE DATABASE backend OWNER backend;
REVOKE ALL ON DATABASE backend FROM PUBLIC;
SQL
