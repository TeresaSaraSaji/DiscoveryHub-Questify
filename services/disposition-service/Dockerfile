# P2.2 Disposition — retention policy, the scheduled sweep, and the run ledger.
#
# Built from the repository root, not from this directory:
#
#   docker build -f services/disposition-service/Dockerfile -t discoveryhub/disposition-service .
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
COPY tools/corpus-generator/pom.xml tools/corpus-generator/
RUN mvn -B -q dependency:go-offline -pl contracts,services/disposition-service -am || true

COPY contracts/src contracts/src
COPY services/disposition-service/src services/disposition-service/src
RUN mvn -B -q -DskipTests package -pl services/disposition-service -am

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN useradd --system --uid 10001 --create-home appuser
USER appuser

COPY --from=build --chown=appuser /build/services/disposition-service/target/disposition-service-*.jar app.jar

EXPOSE 8086

ENV KAFKA_BOOTSTRAP=kafka:19092 \
    DB_URL=jdbc:postgresql://postgres-disposition:5432/disposition \
    ARCHIVE_DB_URL=jdbc:postgresql://postgres-archive:5432/archive \
    CASES_BASE_URL=http://cases-service:8084 \
    SERVER_PORT=8086 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD ["sh", "-c", "curl -sf http://localhost:8086/actuator/health | grep -q '\"status\":\"UP\"'"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
