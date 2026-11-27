FROM apache/kafka:4.4.0@sha256:833b867a9585a141da455dc2b57431c65cbccb27f11c91f694444cca9f777e0f
USER root
RUN apk upgrade --no-cache && \
    for command in kafka-broker-api-versions kafka-topics; do \
      printf '#!/bin/sh\nexec /opt/kafka/bin/%s.sh "$@"\n' "$command" > "/usr/local/bin/$command" && \
      chmod 755 "/usr/local/bin/$command" || exit 1; \
    done
ENV KAFKA_LOG_DIRS=/var/lib/kafka/data
USER appuser
