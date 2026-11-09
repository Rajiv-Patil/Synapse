FROM gradle:8.14-jdk21-alpine AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S synapse && adduser -S synapse -G synapse
COPY --from=build /workspace/build/libs/*.jar /app/app.jar
USER synapse
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
