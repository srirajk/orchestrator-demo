FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="Meridian Platform"
LABEL description="OpenWolf IAM Service — OIDC provider, user management, entitlements"

WORKDIR /app

# Copy the fat JAR produced by spring-boot-maven-plugin
COPY target/iam-service-*.jar app.jar

EXPOSE 8084

ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-XX:+ZGenerational", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", \
    "app.jar"]
