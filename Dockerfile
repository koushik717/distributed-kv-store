FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY build/libs/distributed-kv-store-1.0.0.jar app.jar
EXPOSE 8080 9090
ENTRYPOINT ["java", \
  "-XX:+UseZGC", \
  "-jar", "app.jar"]
