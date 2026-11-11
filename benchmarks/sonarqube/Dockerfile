# SonarQube Community Build, the official image, pinned by digest (sonarqube:community, 26.11.0.134175, JDK 25, built 2026-11-05).
# Unchanged: the benchmark runs the image as its publisher ships it. It starts `java -jar lib/sonarqube.jar`, a small launcher JVM
# that starts three more JVMs (web server, compute engine, Elasticsearch). That is why it is in the benchmark: mnemo-warmup
# wraps the JVM the container starts, so only the launcher can use an archive. It needs PostgreSQL (bench-cluster.sh starts one).
FROM sonarqube@sha256:1ce197471f5f2bac347694a336862fd2c23e9f57c6edb46acebbfbe70f2c7d9c
