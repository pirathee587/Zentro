FROM confluentinc/cp-kafka:7.6.15-1-ubi8
USER root
RUN microdnf upgrade -y && microdnf clean all
USER appuser
