# P5 Evidence Export & Audit.
#
# Built from the repository root, not from this directory:
#
#   docker build -f services/export-service/Dockerfile -t discoveryhub/export-service .
#
# The build context has to be the root because this module depends on contracts/ and inherits the
# parent POM. docker-compose.yml sets that context; if you build by hand from here, Maven will not
# resolve its own parent.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Every module's POM, not just this service's: Maven validates that all children listed in the
# parent exist before it applies -pl, so a missing sibling POM fails the build with "child module
# does not exist" even though that module is not being built. Add a module to the root POM and it
# has to be added here too.
COPY pom.xml .
COPY contracts/pom.xml contracts/
COPY services/ingestion-service/pom.xml services/ingestion-service/
COPY services/storage-service/pom.xml services/storage-service/
COPY services/search-service/pom.xml services/search-service/
COPY services/case-service/pom.xml services/case-service/
COPY services/hold-service/pom.xml services/hold-service/
COPY services/disposition-service/pom.xml services/disposition-service/
COPY services/export-service/pom.xml services/export-service/
COPY tools/corpus-generator/pom.xml tools/corpus-generator/
RUN mvn -B -q dependency:go-offline -pl contracts,services/export-service -am || true

COPY contracts/src contracts/src
COPY services/export-service/src services/export-service/src
RUN mvn -B -q -DskipTests package -pl services/export-service -am

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN useradd --system --uid 10001 --create-home appuser
USER appuser

COPY --from=build --chown=appuser /build/services/export-service/target/export-service-*.jar app.jar

EXPOSE 8085

ENV KAFKA_BOOTSTRAP=kafka:19092 \
    DB_URL=jdbc:postgresql://postgres-audit:5432/audit \
    ARCHIVE_BASE_URL=http://storage-service:8082 \
    MINIO_ENDPOINT=http://minio:9000 \
    MINIO_ACCESS_KEY=minioadmin \
    MINIO_SECRET_KEY=minioadmin \
    SERVER_PORT=8085 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
  CMD ["sh", "-c", "curl -sf http://localhost:8085/actuator/health | grep -q '\"status\":\"UP\"'"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
