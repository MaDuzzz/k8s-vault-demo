FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 10001 spring && adduser -S -D -H -u 10001 -G spring spring
WORKDIR /app
COPY --from=build --chown=spring:spring /workspace/target/vault-todo-backend-*.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
