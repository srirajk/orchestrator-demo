# Conduit Chat BFF — multi-stage build (Java/Spring Boot).
#
# Stage 1 builds the React SPA (apps/chat/web) into static assets.
# Stage 2 bundles those assets into the Spring Boot BFF (apps/chat/bff) and builds the jar.
# Stage 3 is a slim JRE runtime that runs the jar and serves both the API and the SPA.
#
# Build context is ./apps/chat so both `web/` and `bff/` are available.
# The legacy Node BFF at apps/chat/server is retained as the reference spec but is no
# longer built here.

# ── Stage 1: Build web (Vite/React → dist/) ─────────────────────────────────
FROM node:20-alpine AS web-builder
WORKDIR /build/web
COPY web/package*.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# ── Stage 2: Build the Spring Boot jar (with the SPA bundled as static) ──────
FROM maven:3.9-eclipse-temurin-21 AS jar-builder
WORKDIR /build/bff
# Warm the dependency cache first.
COPY bff/pom.xml ./
RUN mvn -q -B -e dependency:go-offline
# App sources.
COPY bff/src ./src
# Bundle the built SPA so the BFF serves it from classpath:/static/.
COPY --from=web-builder /build/web/dist ./src/main/resources/static
RUN mvn -q -B -DskipTests package

# ── Stage 3: Runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
# wget is used by the compose healthcheck.
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*
COPY --from=jar-builder /build/bff/target/conduit-chat-bff.jar ./app.jar

ENV PORT=8095
EXPOSE 8095

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
