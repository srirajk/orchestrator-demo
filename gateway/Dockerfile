# Stage 1 — build with Maven + JDK 21
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Pre-fetch deps in a separate layer so source changes don't retrigger downloads
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# Stage 2 — lean runtime (JRE + wget for healthcheck)
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache wget

WORKDIR /app
COPY --from=build /build/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
