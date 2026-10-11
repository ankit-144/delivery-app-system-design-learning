# syntax=docker/dockerfile:1.7
# One build stage compiles every module; each service image copies only its own jar.
# docker compose builds the targets below; nothing is ever pushed to a registry.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY libs libs
COPY services services
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
# -XX:+UseG1GC: with 1 CPU the JVM would silently pick SerialGC (see docs/FAILURE_TRACKER.md).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM runtime AS core-service
COPY --from=build /workspace/services/core-service/target/core-service.jar /app/app.jar

FROM runtime AS vendor-mock
COPY --from=build /workspace/services/vendor-mock/target/vendor-mock.jar /app/app.jar

FROM runtime AS notification-service
COPY --from=build /workspace/services/notification-service/target/notification-service.jar /app/app.jar
