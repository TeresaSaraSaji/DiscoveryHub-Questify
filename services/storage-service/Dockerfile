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
COPY services/search-service/pom.xml services/search-service/
COPY services/case-service/pom.xml services/case-service/
COPY services/hold-service/pom.xml services/hold-service/
COPY tools/corpus-generator/pom.xml tools/corpus-generator/
RUN mvn -B -q dependency:go-offline -pl contracts,services/storage-service -am || true

COPY contracts/src contracts/src
COPY services/storage-service/src services/storage-service/src
RUN mvn -B -q -DskipTests package -pl services/storage-service -am

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN useradd --system --uid 10001 --create-home appuser
USER appuser

COPY --from=build --chown=appuser /build/services/storage-service/target/storage-service-*.jar app.jar

EXPOSE 8082

ENV KAFKA_BOOTSTRAP=kafka:19092 \
    DB_URL=jdbc:postgresql://postgres-archive:5432/archive \
    SERVER_PORT=8082 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD ["sh", "-c", "curl -sf http://localhost:8082/actuator/health | grep -q '\"status\":\"UP\"'"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
