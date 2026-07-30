#!/bin/sh
set -eu

log() {
    printf '%s %s\n' '[wtfib:migrate]' "$*"
}

require_value() {
    name="$1"
    eval "value=\${$name:-}"
    if [ -z "$value" ]; then
        log "missing required database setting: $name"
        exit 1
    fi
}

require_value PG_HOST
require_value PG_PORT
require_value PG_DB
require_value PG_USER
require_value PG_PASSWORD

export PGPASSWORD="$PG_PASSWORD"

waited=0
until pg_isready \
    --host "$PG_HOST" \
    --port "$PG_PORT" \
    --dbname "$PG_DB" \
    --username "$PG_USER" >/dev/null 2>&1; do
    if [ "$waited" -ge "$STARTUP_WAIT_SECONDS" ]; then
        log "PostgreSQL was not ready after ${STARTUP_WAIT_SECONDS}s"
        exit 1
    fi
    if [ $((waited % 10)) -eq 0 ]; then
        log "waiting for PostgreSQL at ${PG_HOST}:${PG_PORT}/${PG_DB}"
    fi
    sleep 2
    waited=$((waited + 2))
done

psql_base() {
    psql \
        --no-password \
        --host "$PG_HOST" \
        --port "$PG_PORT" \
        --dbname "$PG_DB" \
        --username "$PG_USER" \
        --set ON_ERROR_STOP=on \
        "$@"
}

bootstrap_baseline() {
    {
        printf '%s\n' '\set ON_ERROR_STOP on'
        printf '%s\n' "SELECT pg_advisory_lock(823746291);"
        printf '%s\n' "CREATE TABLE IF NOT EXISTS wiib_schema_migration (version VARCHAR(255) PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP);"
        printf '%s\n' "SELECT EXISTS (SELECT 1 FROM wiib_schema_migration WHERE version = '00000000_baseline') AS migration_applied \\gset"
        printf '%s\n' '\if :migration_applied'
        printf '%s\n' '\echo baseline already applied'
        printf '%s\n' '\else'
        printf '%s\n' "SELECT to_regclass('public.\"user\"') IS NOT NULL AS schema_exists \\gset"
        printf '%s\n' '\if :schema_exists'
        printf '%s\n' '\echo existing WTFiB schema detected; recording baseline without replaying init.sql'
        printf '%s\n' "INSERT INTO wiib_schema_migration(version) VALUES ('00000000_baseline');"
        printf '%s\n' '\else'
        printf '%s\n' '\echo creating WTFiB database schema'
        printf '%s\n' 'BEGIN;'
        printf '%s\n' "\\i '/app/sql/init.sql'"
        printf '%s\n' "INSERT INTO wiib_schema_migration(version) VALUES ('00000000_baseline');"
        printf '%s\n' 'COMMIT;'
        printf '%s\n' '\endif'
        printf '%s\n' '\endif'
        printf '%s\n' "SELECT pg_advisory_unlock(823746291);"
    } | psql_base --quiet
}

bootstrap_bstock() {
    {
        printf '%s\n' '\set ON_ERROR_STOP on'
        printf '%s\n' "SELECT pg_advisory_lock(823746291);"
        printf '%s\n' "SELECT EXISTS (SELECT 1 FROM wiib_schema_migration WHERE version = '00000001_bstock') AS migration_applied \\gset"
        printf '%s\n' '\if :migration_applied'
        printf '%s\n' '\echo bstock seed already applied'
        printf '%s\n' '\else'
        printf '%s\n' '\echo applying bstock schema and seed data'
        printf '%s\n' 'BEGIN;'
        printf '%s\n' "\\i '/app/sql/bstock.sql'"
        printf '%s\n' "INSERT INTO wiib_schema_migration(version) VALUES ('00000001_bstock');"
        printf '%s\n' 'COMMIT;'
        printf '%s\n' '\endif'
        printf '%s\n' "SELECT pg_advisory_unlock(823746291);"
    } | psql_base --quiet
}

apply_migration() {
    migration_file="$1"
    migration_name="$(basename "$migration_file" .sql)"

    case "$migration_name" in
        *[!A-Za-z0-9._-]*)
            log "refusing unsafe migration filename: $migration_name"
            exit 1
            ;;
    esac

    {
        printf '%s\n' '\set ON_ERROR_STOP on'
        printf '%s\n' "SELECT pg_advisory_lock(823746291);"
        printf '%s\n' "SELECT EXISTS (SELECT 1 FROM wiib_schema_migration WHERE version = '${migration_name}') AS migration_applied \\gset"
        printf '%s\n' '\if :migration_applied'
        printf '%s\n' "\\echo migration ${migration_name} already applied"
        printf '%s\n' '\else'
        printf '%s\n' "\\echo applying migration ${migration_name}"
        printf '%s\n' "\\i '${migration_file}'"
        printf '%s\n' "INSERT INTO wiib_schema_migration(version) VALUES ('${migration_name}');"
        printf '%s\n' '\endif'
        printf '%s\n' "SELECT pg_advisory_unlock(823746291);"
    } | psql_base --quiet
}

bootstrap_baseline
bootstrap_bstock

for migration_file in $(find /app/sql/migrations -maxdepth 1 -type f -name '*.sql' | sort); do
    apply_migration "$migration_file"
done

log "database schema is ready"
