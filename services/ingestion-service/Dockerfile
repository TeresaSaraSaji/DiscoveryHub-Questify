# P1 Ingestion.
#
# Built from the repository root, not from this directory:
#
#   docker build -f services/ingestion-service/Dockerfile -t discoveryhub/ingestion-service .
#
# The build context has to be the root because this module depends on contracts/ and inherits the
# parent POM. docker-compose.yml sets that context; if you build by hand from here, Maven will not
# resolve its own parent.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# POMs first, so a source-only change reuses the cached dependency layer instead of re-downloading
# the world on every build.
#
# Every module's POM, not just this service's: Maven validates that all children listed in the
# parent exist before it applies -pl, so a missing sibling POM fails the build with "child module
# does not exist" even though that module is not being built. Add a module to the root POM and it
# has to be added here too.
COPY pom.xml .
COPY contracts/pom.xml contracts/
COPY services/ingestion-service/pom.xml services/ingestion-service/
COPY services/storage-service/pom.xml services/storage-service/
COPY tools/corpus-generator/pom.xml tools/corpus-generator/
RUN mvn -B -q dependency:go-offline -pl contracts,services/ingestion-service -am || true

COPY contracts/src contracts/src
COPY services/ingestion-service/src services/ingestion-service/src
RUN mvn -B -q -DskipTests package -pl services/ingestion-service -am

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Not root. A container that only needs to read a jar and open a port has no reason to be able to
# write to its own filesystem as a privileged user.
RUN useradd --system --uid 10001 --create-home appuser
USER appuser

COPY --from=build --chown=appuser /build/services/ingestion-service/target/ingestion-service-*.jar app.jar

EXPOSE 8081

# Container defaults. These are overridden by compose, and they differ from application.yml
# because inside the network the hostnames are service names, not localhost.
ENV KAFKA_BOOTSTRAP=kafka:19092 \
    REDIS_HOST=redis \
    SERVER_PORT=8081 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
  CMD ["sh", "-c", "curl -sf http://localhost:8081/actuator/health | grep -q '\"status\":\"UP\"'"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
