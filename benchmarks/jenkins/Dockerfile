# syntax=docker/dockerfile:1
# A third-party benchmark application that is not ours and not Spring Boot: the Jenkins LTS executable WAR, started with
# `java -jar jenkins.war` (Winstone/Jetty inside). The WAR is pinned by version and SHA-256 (get.jenkins.io publishes the
# same checksum next to the file). No plugins beyond those bundled in the WAR, setup wizard off (see bench-cluster.sh).
#   docker build --build-arg JDK=21 -t bench-jenkins:21 benchmarks/jenkins
ARG JDK=21
FROM eclipse-temurin:${JDK}-jre
ARG JENKINS_VERSION=2.492.3
ADD --checksum=sha256:90ccf556133c36fdf7653ad710f00d248bf2895f9fbc26ccee0e2d3ba681b01f \
    https://get.jenkins.io/war-stable/${JENKINS_VERSION}/jenkins.war /app/jenkins.war
ENV JENKINS_HOME=/var/jenkins_home
RUN mkdir -p "$JENKINS_HOME"
EXPOSE 8080
CMD ["java", "-jar", "/app/jenkins.war"]
