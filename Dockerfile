FROM maven:3.9.11-eclipse-temurin-21-alpine AS backend-build

WORKDIR /build
COPY . .
RUN mvn -B -ntp -DskipTests clean package \
    -pl wiib-feed,wiib-sim,wiib-quant -am

FROM node:22-alpine AS web-build

WORKDIR /build/wiib-web
COPY wiib-web/package.json wiib-web/package-lock.json ./
RUN npm ci
COPY wiib-web/ ./
RUN npm run build

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache \
        curl \
        gettext \
        nginx \
        postgresql-client \
        redis \
        tini \
        tzdata \
    && mkdir -p \
        /app/bin \
        /app/jars \
        /app/sql/migrations \
        /app/web \
        /dumps/feed \
        /dumps/sim \
        /dumps/quant \
        /run/nginx

WORKDIR /app

COPY --from=backend-build /build/wiib-feed/target/wiib-feed-*.jar /app/jars/wiib-feed.jar
COPY --from=backend-build /build/wiib-sim/target/wiib-sim-*.jar /app/jars/wiib-sim.jar
COPY --from=backend-build /build/wiib-quant/target/wiib-quant-*.jar /app/jars/wiib-quant.jar
COPY --from=web-build /build/wiib-web/dist/ /app/web/

COPY sql/init.sql sql/bstock.sql /app/sql/
COPY sql/migrations/ /app/sql/migrations/
COPY deploy/nginx.conf.template /etc/nginx/http.d/default.conf.template
COPY deploy/entrypoint.sh deploy/migrate.sh /app/bin/

RUN chmod +x /app/bin/entrypoint.sh /app/bin/migrate.sh \
    && rm -f /etc/nginx/http.d/default.conf

ENV PORT=8080 \
    TZ=Asia/Shanghai \
    STARTUP_WAIT_SECONDS=180 \
    SHUTDOWN_GRACE_SECONDS=20 \
    FEED_JAVA_XMS=512m \
    FEED_JAVA_XMX=2g \
    SIM_JAVA_XMS=512m \
    SIM_JAVA_XMX=3g \
    QUANT_JAVA_XMS=512m \
    QUANT_JAVA_XMX=4g

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=180s --retries=4 \
    CMD curl -fsS "http://127.0.0.1:${PORT}/healthz" >/dev/null || exit 1

ENTRYPOINT ["/sbin/tini", "--", "/app/bin/entrypoint.sh"]
