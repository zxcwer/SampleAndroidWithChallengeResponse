# syntax=docker/dockerfile:1
# Docker Hub の取得制限(429)を避けたいときは BASE_REGISTRY=mirror.gcr.io/library/ を指定する
ARG BASE_REGISTRY=
FROM ${BASE_REGISTRY}eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew bootJar -x test --no-daemon --console=plain \
    && cp build/libs/sync-server-*.jar /app.jar

ARG BASE_REGISTRY
FROM ${BASE_REGISTRY}eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home /app app \
    && mkdir -p /app/data /app/signing-keys && chown -R app /app
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
USER app
# DB(./data) と JWT 署名鍵(./signing-keys) は volume に置く
VOLUME ["/app/data", "/app/signing-keys"]
EXPOSE 8443
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
