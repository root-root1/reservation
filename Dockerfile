# syntax=docker/dockerfile:1

# ---- build ----------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

# Dependencies resolve in their own layer, so a source-only change does not
# re-download the world on every rebuild.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/*.jar extract --layers --destination extracted

# ---- run ------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

RUN addgroup -S seats && adduser -S -G seats seats

# Layer order matters: the ones that change least are copied first, so a code
# change only invalidates the last layer of the image.
ARG LAYERS=/build/extracted
COPY --from=build ${LAYERS}/dependencies/ ./
COPY --from=build ${LAYERS}/snapshot-dependencies/ ./
COPY --from=build ${LAYERS}/application/*.jar ./app.jar

USER seats

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Djava.security.egd=file:/dev/urandom"
ENV SERVER_PORT=8080
EXPOSE 8080

# Readiness is checked by the platform, but a container-level probe means a
# dead JVM is restarted even where the platform does not probe.
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://127.0.0.1:${SERVER_PORT}/healthz/liveness || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
