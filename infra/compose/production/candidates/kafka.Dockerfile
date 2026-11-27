FROM apache/kafka:3.9.2@sha256:05b4616e0702ef2729327705d54ad6b50ea70b271c4b730fabd2320789fb7b02
USER root
RUN apk upgrade --no-cache && \
    for command in kafka-broker-api-versions kafka-topics; do \
      printf '#!/bin/sh\nexec /opt/kafka/bin/%s.sh "$@"\n' "$command" > "/usr/local/bin/$command" && \
      chmod 755 "/usr/local/bin/$command" || exit 1; \
    done
ENV KAFKA_LOG_DIRS=/var/lib/kafka/data
USER appuser
