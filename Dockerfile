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
    mvn -q -B -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd --system opsflow \
    && useradd --system --gid opsflow --home-dir /app --shell /usr/sbin/nologin opsflow \
    && mkdir -p /app/uploads /app/logs \
    && chown -R opsflow:opsflow /app
COPY --from=build --chown=opsflow:opsflow /app/target/opsflow-0.0.1-SNAPSHOT.jar app.jar
USER opsflow
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
