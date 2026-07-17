# Stage 1 — build with Maven + JDK 25 (source build inside Docker; no prebuilt jar needed)
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /build

# Pre-fetch deps in a separate layer so source changes don't retrigger downloads
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# The consequence-diff authoring path evaluates staged bundles with the exact pinned Cerbos binary.
# This is copied into the runtime image; it does not talk to or swap the live PDP policy pointer.
FROM ghcr.io/cerbos/cerbos:0.53.0 AS cerbos-runtime

# Stage 2 — lean runtime (JRE + wget for healthcheck)
FROM eclipse-temurin:25-jre-alpine

LABEL maintainer="Meridian Platform"
LABEL description="OpenWolf IAM Service — OIDC provider, user management, entitlements"

RUN apk add --no-cache wget

COPY --from=cerbos-runtime /cerbos /usr/local/bin/cerbos

WORKDIR /app
COPY --from=build /build/target/iam-service-*.jar app.jar

EXPOSE 8084

# -XX:+ZGenerational was removed in JDK 24; passing it to a 25 JVM aborts startup.
# ZGC is generational by default from 23 onward, so -XX:+UseZGC alone is equivalent.
ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", \
    "app.jar"]
