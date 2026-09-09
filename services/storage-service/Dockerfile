# P2 Storage / Archive — the system of record.
#
# Built from the repository root, not from this directory:
#
#   docker build -f services/storage-service/Dockerfile -t discoveryhub/storage-service .
#
# The build context has to be the root because this module depends on contracts/ and inherits the
# parent POM. docker-compose.yml sets that context; if you build by hand from here, Maven will not
# resolve its own parent.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# POMs first, so a source-only change reuses the cached dependency layer.
#
# Every module's POM, not just this service's: Maven validates that all children listed in the
# parent exist before it applies -pl, so a missing sibling POM fails the build with "child module
# does not exist" even though that module is not being built. Add a module to the root POM and it
# has to be added here too.
COPY pom.xml .
COPY contracts/pom.xml contracts/
COPY services/ingestion-service/pom.xml services/ingestion-service/
COPY services/storage-service/pom.xml services/storage-service/
COPY services/disposition-service/pom.xml services/disposition-service/
COPY services/search-service/pom.xml services/search-service/
COPY services/case-service/pom.xml services/case-service/
COPY services/hold-service/pom.xml services/hold-service/
COPY services/export-service/pom.xml services/export-service/
COPY services/disposition-service/pom.xml services/disposition-service/
COPY tools/corpus-generator/pom.xml tools/corpus-generator/
RUN mvn -B -q dependency:go-offline -pl contracts,services/storage-service -am || true

COPY contracts/src contracts/src
COPY services/storage-service/src services/storage-service/src
RUN mvn -B -q -DskipTests package -pl services/storage-service -am

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN useradd --system --uid 10001 --create-home appuser

# Attachment bytes are written here, not into PostgreSQL (V4__attachment_storage.sql). Created and
# chowned before the USER switch because appuser cannot mkdir under a root-owned WORKDIR.
#
# This MUST be backed by a volume in whatever runs the image — without one the bytes live in the
# container's writable layer and are destroyed with the container, which loses the durability
# guarantee the metadata rows still claim. docker-compose.yml has to mount it.
RUN mkdir -p /app/data/attachments && chown -R appuser /app/data
VOLUME ["/app/data/attachments"]

USER appuser

COPY --from=build --chown=appuser /build/services/storage-service/target/storage-service-*.jar app.jar

EXPOSE 8082

# CASES_BASE_URL is P4, for the hold guard on every delete command this service receives. Left
# unset the property falls back to localhost:8084, which inside this container is this container:
# every hold check gets a connection refused, fails closed, and P2 refuses every disposition. The
# refusals look exactly like a hold doing its job, which is what makes the wrong address hard to
# spot — it was, until an end-to-end run showed five skipped messages and no deletions.
ENV KAFKA_BOOTSTRAP=kafka:19092 \
    DB_URL=jdbc:postgresql://postgres-archive:5432/archive \
    CASES_BASE_URL=http://cases-service:8084 \
    SERVER_PORT=8082 \
    ATTACHMENT_DIR=/app/data/attachments \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD ["sh", "-c", "curl -sf http://localhost:8082/actuator/health | grep -q '\"status\":\"UP\"'"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
