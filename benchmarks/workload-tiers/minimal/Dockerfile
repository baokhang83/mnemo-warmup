# syntax=docker/dockerfile:1
# Build with Spring Boot's JDK 25 tools; run on the same Debian OpenJDK
# 25.0.4.1 image and digest as the ThingsBoard heavy tier.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
COPY src src
RUN mvn -B -ntp -DskipTests package \
 && java -Djarmode=tools -jar target/app.jar extract --destination /out

FROM thingsboard/tb-node@sha256:5e2c180f3c48a9f4efd3e19d2f03c41b510ff1a935f25083657e0cf046a94872
USER root
COPY --from=build /out /app
USER thingsboard
EXPOSE 8080
CMD ["java", "-jar", "/app/app.jar"]
