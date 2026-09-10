# P4 Case Management — case lifecycle, custodians, evidence items.
#
# Built from the repository root, not from this directory, because this module depends on
# contracts/ and inherits the parent POM:
#
#   docker build -f services/case-service/Dockerfile -t discoveryhub/case-service .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# POMs first so a source-only change reuses the cached dependency layer. Every module's POM, not
# just this service's: Maven validates that all children listed in the parent exist before -pl.
COPY pom.xml .
COPY contracts/pom.xml contracts/
COPY services/ingestion-service/pom.xml services/ingestion-service/
COPY services/storage-service/pom.xml services/storage-service/
COPY services/disposition-service/pom.xml services/disposition-service/
COPY services/search-service/pom.xml services/search-service/
COPY services/case-service/pom.xml services/case-service/
COPY services/hold-service/pom.xml services/hold-service/
COPY services/export-service/pom.xml services/export-service/
COPY tools/corpus-generator/pom.xml tools/corpus-generator/
RUN mvn -B -q dependency:go-offline -pl contracts,services/case-service -am || true

COPY contracts/src contracts/src
COPY services/case-service/src services/case-service/src
RUN mvn -B -q -DskipTests package -pl services/case-service -am

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN useradd --system --uid 10001 --create-home appuser
USER appuser

COPY --from=build --chown=appuser /build/services/case-service/target/case-service-*.jar app.jar

EXPOSE 8084

ENV KAFKA_BOOTSTRAP=kafka:19092 \
    DB_URL=jdbc:postgresql://postgres-cases:5432/cases \
    SERVER_PORT=8084 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD ["sh", "-c", "curl -sf http://localhost:8084/actuator/health | grep -q '\"status\":\"UP\"'"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
