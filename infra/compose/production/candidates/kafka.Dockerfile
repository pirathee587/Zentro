FROM maven:3.9-eclipse-temurin-21 AS codecs
RUN mvn -B -ntp dependency:copy -Dartifact=at.yawk.lz4:lz4-java:1.11.4 -DoutputDirectory=/codecs && \
    mvn -B -ntp dependency:copy -Dartifact=org.xerial.snappy:snappy-java:1.1.10.9 -DoutputDirectory=/codecs

FROM apache/kafka:4.4.0@sha256:833b867a9585a141da455dc2b57431c65cbccb27f11c91f694444cca9f777e0f
USER root
COPY --from=codecs /codecs/ /opt/kafka/libs/
RUN test -f /opt/kafka/libs/lz4-java-1.11.2.jar && test -f /opt/kafka/libs/snappy-java-1.1.10.7.jar && \
    rm /opt/kafka/libs/lz4-java-1.11.2.jar /opt/kafka/libs/snappy-java-1.1.10.7.jar && \
    apk upgrade --no-cache && \
    for command in kafka-broker-api-versions kafka-topics; do \
      printf '#!/bin/sh\nexec /opt/kafka/bin/%s.sh "$@"\n' "$command" > "/usr/local/bin/$command" && \
      chmod 755 "/usr/local/bin/$command" || exit 1; \
    done
ENV KAFKA_LOG_DIRS=/var/lib/kafka/data
USER appuser
