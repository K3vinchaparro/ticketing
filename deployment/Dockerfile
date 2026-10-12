# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle main.gradle gradle.properties lombok.config ./
COPY gradle gradle
COPY applications applications
COPY domain domain
COPY infrastructure infrastructure

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :app-service:bootJar --no-daemon --no-configuration-cache

FROM eclipse-temurin:25-jre
WORKDIR /app

RUN useradd --system --uid 10001 --no-create-home appuser
COPY --from=build /workspace/applications/app-service/build/libs/ticketing.jar app.jar
USER 10001

EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "app.jar"]
