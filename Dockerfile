FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY gradle gradle
COPY gradlew .
COPY build.gradle.kts settings.gradle.kts ./
COPY proto proto
COPY src src
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/distributed-kv-store-1.0.0.jar app.jar
EXPOSE 8080 9090
ENTRYPOINT ["java", \
  "-XX:+UseZGC", \
  "-jar", "app.jar"]
