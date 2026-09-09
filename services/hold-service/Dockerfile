# P4 Legal Hold — holds, scope resolution, hold check, release.
#
# Built from the repository root (depends on contracts/ and the parent POM):
#   docker build -f services/hold-service/Dockerfile -t discoveryhub/hold-service .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
COPY contracts/pom.xml contracts/
COPY services/ingestion-service/pom.xml services/ingestion-service/
COPY services/storage-service/pom.xml services/storage-service/
COPY services/search-service/pom.xml services/search-service/
COPY services/case-service/pom.xml services/case-service/
COPY services/hold-service/pom.xml services/hold-service/
COPY services/export-service/pom.xml services/export-service/
COPY tools/corpus-generator/pom.xml tools/corpus-generator/
RUN mvn -B -q dependency:go-offline -pl contracts,services/hold-service -am || true

COPY contracts/src contracts/src
COPY services/hold-service/src services/hold-service/src
RUN mvn -B -q -DskipTests package -pl services/hold-service -am

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN useradd --system --uid 10001 --create-home appuser
USER appuser

COPY --from=build --chown=appuser /build/services/hold-service/target/hold-service-*.jar app.jar

EXPOSE 8086

ENV KAFKA_BOOTSTRAP=kafka:19092 \
    DB_URL=jdbc:postgresql://postgres-holds:5432/holds \
    SERVER_PORT=8086 \
    CASES_BASE_URL=http://case-service:8084 \
    ARCHIVE_BASE_URL=http://storage-service:8082 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD ["sh", "-c", "curl -sf http://localhost:8086/actuator/health | grep -q '\"status\":\"UP\"'"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
