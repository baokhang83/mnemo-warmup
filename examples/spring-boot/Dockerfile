# syntax=docker/dockerfile:1
#
# The example application image. Build with the JDK you want the cache trained for:
#   docker build --build-arg JDK=21 -t mnemo-example:21 examples/spring-boot
#   docker build --build-arg JDK=25 -t mnemo-example:25 examples/spring-boot
#
# The Spring Boot JAR is EXTRACTED AT BUILD TIME. The trainer and every replica then run the same files, at the
# same path, with the same modification times, which is what an archive needs (docs/adr/0003-run-exec-model-and-layout.md).
ARG JDK=21

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src src
# the cache mount keeps downloaded dependencies between builds (and between the two JDK variants)
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests package \
 && java -Djarmode=tools -jar target/app.jar extract --destination /out

FROM eclipse-temurin:${JDK}-jre
COPY --from=build /out /app
EXPOSE 8080
CMD ["java", "-jar", "/app/app.jar"]
