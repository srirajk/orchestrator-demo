# Stage 1 — build with Maven + JDK 25 (bytecode target stays 21, see pom.xml)
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /build

# Pre-fetch deps in a separate layer so source changes don't retrigger downloads
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# Stage 2 — lean runtime (JRE + wget for healthcheck)
# Java 25: JEP-491 means `synchronized` no longer pins virtual-thread carriers.
FROM eclipse-temurin:25-jre-alpine

RUN apk add --no-cache wget

WORKDIR /app
COPY --from=build /build/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
