# ThingsBoard Community Edition 4.3.1.8 vendor image, adapted for a java -jar launch.
FROM thingsboard/tb-node@sha256:5e2c180f3c48a9f4efd3e19d2f03c41b510ff1a935f25083657e0cf046a94872 AS source
FROM source AS normalized
USER root
RUN apt-get update && apt-get install -y --no-install-recommends python3 && rm -rf /var/lib/apt/lists/*
COPY normalize.py /tmp/normalize.py
# The vendor prefixes the JAR with a shell launcher. Rebuild the ZIP without it;
# keep every entry and its compression method, then let Spring Boot extract it.
RUN python3 /tmp/normalize.py
RUN java -Djarmode=tools -jar /tmp/thingsboard-plain.jar extract --destination /out
FROM source
USER root
COPY --from=normalized /out /opt/thingsboard-app
RUN chown -R thingsboard:thingsboard /opt/thingsboard-app
USER thingsboard
WORKDIR /usr/share/thingsboard/bin
ENV SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:/usr/share/thingsboard/conf/ \
    SQL_DATA_FOLDER=/usr/share/thingsboard/data/sql
CMD ["java", "-Dplatform=docker", "-Dlogging.config=/usr/share/thingsboard/conf/logback.xml", "-jar", "/opt/thingsboard-app/thingsboard-plain.jar"]
