#!/bin/sh
set -eu

log() {
    printf '%s %s\n' '[wtfib]' "$*"
}

# Zeabur 数据库模板会暴露 POSTGRES_*；本地/Compose 仍可继续使用 PG_*。
PG_HOST="${PG_HOST:-${POSTGRES_HOST:-}}"
PG_PORT="${PG_PORT:-${POSTGRES_PORT:-5432}}"
PG_DB="${PG_DB:-${POSTGRES_DATABASE:-${POSTGRES_DB:-postgres}}}"
PG_USER="${PG_USER:-${POSTGRES_USERNAME:-${POSTGRES_USER:-}}}"
PG_PASSWORD="${PG_PASSWORD:-${POSTGRES_PASSWORD:-}}"

REDIS_HOST="${REDIS_HOST:-}"
REDIS_PORT="${REDIS_PORT:-6379}"
REDIS_DB="${REDIS_DB:-0}"
REDIS_PASSWORD="${REDIS_PASSWORD:-}"

PORT="${PORT:-8080}"
SIM_INTERNAL_PORT="${SIM_INTERNAL_PORT:-18080}"
FEED_INTERNAL_PORT="${FEED_INTERNAL_PORT:-18081}"
QUANT_INTERNAL_PORT="${QUANT_INTERNAL_PORT:-18082}"
STARTUP_WAIT_SECONDS="${STARTUP_WAIT_SECONDS:-180}"
SHUTDOWN_GRACE_SECONDS="${SHUTDOWN_GRACE_SECONDS:-20}"
INTERNAL_API_TOKEN="${INTERNAL_API_TOKEN:-wtfib-single-container-internal}"

export PG_HOST PG_PORT PG_DB PG_USER PG_PASSWORD
export REDIS_HOST REDIS_PORT REDIS_DB REDIS_PASSWORD
export PORT SIM_INTERNAL_PORT FEED_INTERNAL_PORT QUANT_INTERNAL_PORT
export STARTUP_WAIT_SECONDS SHUTDOWN_GRACE_SECONDS INTERNAL_API_TOKEN

if [ -z "$REDIS_HOST" ]; then
    log 'REDIS_HOST is missing; add the Zeabur Redis service to the same project'
    exit 1
fi

/app/bin/migrate.sh

waited=0
until {
    if [ -n "$REDIS_PASSWORD" ]; then
        redis-cli --no-auth-warning \
            -h "$REDIS_HOST" -p "$REDIS_PORT" -n "$REDIS_DB" \
            -a "$REDIS_PASSWORD" ping 2>/dev/null
    else
        redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" -n "$REDIS_DB" ping 2>/dev/null
    fi
} | grep -q '^PONG$'; do
    if [ "$waited" -ge "$STARTUP_WAIT_SECONDS" ]; then
        log "Redis was not ready after ${STARTUP_WAIT_SECONDS}s"
        exit 1
    fi
    if [ $((waited % 10)) -eq 0 ]; then
        log "waiting for Redis at ${REDIS_HOST}:${REDIS_PORT}/${REDIS_DB}"
    fi
    sleep 2
    waited=$((waited + 2))
done

envsubst '${PORT} ${SIM_INTERNAL_PORT} ${QUANT_INTERNAL_PORT}' \
    < /etc/nginx/http.d/default.conf.template \
    > /etc/nginx/http.d/default.conf

PIDS=''

stop_all() {
    exit_code="${1:-0}"
    trap - INT TERM EXIT
    if [ -n "$PIDS" ]; then
        log 'stopping all WTFiB processes'
        kill -TERM $PIDS 2>/dev/null || true

        shutdown_waited=0
        while [ "$shutdown_waited" -lt "$SHUTDOWN_GRACE_SECONDS" ]; do
            processes_alive=false
            for process_pid in $PIDS; do
                if kill -0 "$process_pid" 2>/dev/null; then
                    processes_alive=true
                    break
                fi
            done
            if [ "$processes_alive" = false ]; then
                break
            fi
            sleep 1
            shutdown_waited=$((shutdown_waited + 1))
        done

        for process_pid in $PIDS; do
            if kill -0 "$process_pid" 2>/dev/null; then
                kill -KILL "$process_pid" 2>/dev/null || true
            fi
        done
        wait $PIDS 2>/dev/null || true
    fi
    exit "$exit_code"
}

trap 'stop_all 143' TERM
trap 'stop_all 130' INT

wait_for_health() {
    service_name="$1"
    service_pid="$2"
    health_url="$3"
    waited=0

    until curl -fsS "$health_url" >/dev/null 2>&1; do
        if ! kill -0 "$service_pid" 2>/dev/null; then
            log "${service_name} exited during startup"
            wait "$service_pid" || true
            stop_all 1
        fi
        if [ "$waited" -ge "$STARTUP_WAIT_SECONDS" ]; then
            log "${service_name} did not become healthy after ${STARTUP_WAIT_SECONDS}s"
            stop_all 1
        fi
        sleep 2
        waited=$((waited + 2))
    done

    log "${service_name} is healthy"
}

log 'starting wiib-feed'
java \
    -Xms"${FEED_JAVA_XMS:-512m}" \
    -Xmx"${FEED_JAVA_XMX:-2g}" \
    -XX:MaxDirectMemorySize="${FEED_JAVA_MAX_DIRECT_MEM:-512m}" \
    -XX:ReservedCodeCacheSize="${FEED_JAVA_CODE_CACHE:-128m}" \
    -XX:MaxMetaspaceSize="${FEED_JAVA_METASPACE:-256m}" \
    -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError \
    -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dumps/feed \
    ${FEED_JAVA_OPTS:-} \
    -jar /app/jars/wiib-feed.jar \
    --server.address=127.0.0.1 \
    --server.port="$FEED_INTERNAL_PORT" &
FEED_PID=$!
PIDS="$PIDS $FEED_PID"
wait_for_health wiib-feed "$FEED_PID" "http://127.0.0.1:${FEED_INTERNAL_PORT}/actuator/health"

log 'starting wiib-sim'
FEED_INTERNAL_BASE_URL="http://127.0.0.1:${FEED_INTERNAL_PORT}" \
java \
    -Xms"${SIM_JAVA_XMS:-512m}" \
    -Xmx"${SIM_JAVA_XMX:-3g}" \
    -XX:MaxDirectMemorySize="${SIM_JAVA_MAX_DIRECT_MEM:-1g}" \
    -XX:ReservedCodeCacheSize="${SIM_JAVA_CODE_CACHE:-128m}" \
    -XX:MaxMetaspaceSize="${SIM_JAVA_METASPACE:-256m}" \
    -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError \
    -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dumps/sim \
    ${SIM_JAVA_OPTS:-} \
    -jar /app/jars/wiib-sim.jar \
    --server.address=127.0.0.1 \
    --server.port="$SIM_INTERNAL_PORT" &
SIM_PID=$!
PIDS="$PIDS $SIM_PID"
wait_for_health wiib-sim "$SIM_PID" "http://127.0.0.1:${SIM_INTERNAL_PORT}/actuator/health"

log 'starting wiib-quant'
SIM_INTERNAL_BASE_URL="http://127.0.0.1:${SIM_INTERNAL_PORT}" \
java \
    -Xms"${QUANT_JAVA_XMS:-512m}" \
    -Xmx"${QUANT_JAVA_XMX:-4g}" \
    -XX:MaxDirectMemorySize="${QUANT_JAVA_MAX_DIRECT_MEM:-512m}" \
    -XX:ReservedCodeCacheSize="${QUANT_JAVA_CODE_CACHE:-128m}" \
    -XX:MaxMetaspaceSize="${QUANT_JAVA_METASPACE:-384m}" \
    -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError \
    -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dumps/quant \
    ${QUANT_JAVA_OPTS:-} \
    -jar /app/jars/wiib-quant.jar \
    --server.address=127.0.0.1 \
    --server.port="$QUANT_INTERNAL_PORT" &
QUANT_PID=$!
PIDS="$PIDS $QUANT_PID"
wait_for_health wiib-quant "$QUANT_PID" "http://127.0.0.1:${QUANT_INTERNAL_PORT}/actuator/health"

log "starting public web gateway on port ${PORT}"
nginx -g 'daemon off;' &
NGINX_PID=$!
PIDS="$PIDS $NGINX_PID"

while :; do
    for process_pid in $PIDS; do
        if ! kill -0 "$process_pid" 2>/dev/null; then
            set +e
            wait "$process_pid"
            process_status=$?
            set -e
            if [ "$process_status" -eq 0 ]; then
                process_status=1
            fi
            log "a managed process exited with status ${process_status}"
            stop_all "$process_status"
        fi
    done
    sleep 2
done
