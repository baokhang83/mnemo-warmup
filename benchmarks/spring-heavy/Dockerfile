# syntax=docker/dockerfile:1
# The benchmark application image: extracted at build time like examples/spring-boot/Dockerfile (docs/adr/0003).
#   docker build --build-arg JDK=21 -t bench-spring-heavy:21 benchmarks/spring-heavy
ARG JDK=21

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests package \
 && java -Djarmode=tools -jar target/app.jar extract --destination /out

FROM eclipse-temurin:${JDK}-jre
COPY --from=build /out /app
EXPOSE 8080
CMD ["java", "-jar", "/app/app.jar"]
