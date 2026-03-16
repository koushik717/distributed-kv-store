FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY gradle gradle
COPY gradlew .
COPY build.gradle.kts settings.gradle.kts ./

COPY src src
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/build/libs/distributed-kv-store-1.0.0.jar app.jar
EXPOSE 8080 9090
ENTRYPOINT ["java", \
  "-XX:+UseZGC", \
  "-jar", "app.jar"]
