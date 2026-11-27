FROM apache/kafka:3.9.2@sha256:05b4616e0702ef2729327705d54ad6b50ea70b271c4b730fabd2320789fb7b02
USER root
RUN ln -s /opt/kafka/bin/kafka-broker-api-versions.sh /usr/local/bin/kafka-broker-api-versions && \
    ln -s /opt/kafka/bin/kafka-topics.sh /usr/local/bin/kafka-topics
ENV KAFKA_LOG_DIRS=/var/lib/kafka/data
USER appuser
