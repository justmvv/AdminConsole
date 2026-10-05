# syntax=docker/dockerfile:1
# =============================================================================
#  Builds and runs Admin Console. Only Docker is needed locally.
#    docker build -t admin-console .
#  Corporate Maven mirror:  --build-arg MVN_EXTRA_ARGS="-s /build/docker/maven/settings.xml"
#  The Vaadin frontend build downloads Node.js (nodejs.org); for isolated networks see README.
# =============================================================================

FROM maven:3.9-eclipse-temurin-21 AS build
ARG MVN_EXTRA_ARGS=""
WORKDIR /build

# pom.xml first — the dependency layer is cached between builds
COPY pom.xml .
COPY docker/maven ./docker/maven
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B ${MVN_EXTRA_ARGS} -Pproduction dependency:go-offline || true

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/root/.vaadin \
    mvn -B ${MVN_EXTRA_ARGS} -Pproduction -DskipTests package \
 && cp target/admin-console-*.jar /build/app.jar

# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre

RUN groupadd --system console && useradd --system --gid console --home /app console \
 && mkdir -p /app/logs && chown -R console:console /app
WORKDIR /app
COPY --from=build --chown=console:console /build/app.jar /app/app.jar

USER console
EXPOSE 8080
VOLUME ["/app/logs"]

# Time zone for timestamps in the UI and logs: override with -e TZ=Europe/Moscow etc.
ENV TZ=UTC \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    LOGGING_FILE_PATH=/app/logs

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
