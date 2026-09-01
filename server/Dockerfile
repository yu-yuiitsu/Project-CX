# Multi-stage Dockerfile for Hosted WebRTC Signaling Server
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/Project-CX-1.0-SNAPSHOT.jar app.jar

# Signaling Server Port
EXPOSE 8888

ENTRYPOINT ["java", "-cp", "app.jar", "org.yu.projectcx.network.signaling.HostedSignalingServer", "8888"]
