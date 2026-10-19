# syntax=docker/dockerfile:1.7

FROM node:22-alpine AS frontend-build
WORKDIR /frontend
COPY frontend/package*.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci --no-audit --no-fund
COPY frontend ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
COPY --from=frontend-build /frontend/dist ./src/main/resources/static
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre-alpine
LABEL org.opencontainers.image.title="OpsFlow" \
      org.opencontainers.image.description="Enterprise ticketing and knowledge collaboration platform" \
      org.opencontainers.image.source="https://github.com/laolei888/OpsFlow"
WORKDIR /app
RUN apk upgrade --no-cache \
    && apk add --no-cache --upgrade 'libexpat>=2.9.0-r0' \
    && addgroup -S -g 10001 opsflow \
    && adduser -S -D -H -u 10001 -h /app -s /sbin/nologin -G opsflow opsflow \
    && mkdir -p /app/uploads /app/logs \
    && chown -R opsflow:opsflow /app
COPY --from=build --chown=opsflow:opsflow /app/target/opsflow-0.0.1-SNAPSHOT.jar app.jar
USER opsflow
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
