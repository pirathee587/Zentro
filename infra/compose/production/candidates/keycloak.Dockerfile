FROM quay.io/keycloak/keycloak:26.8.2 AS upstream
FROM maven:3.9-eclipse-temurin-21 AS dependencies
RUN mvn -B -ntp dependency:copy -Dartifact=com.google.guava:guava:33.8.0-jre -DoutputDirectory=/patched
FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555
RUN apk upgrade --no-cache && apk add --no-cache bash && addgroup -g 1000 keycloak && adduser -D -u 1000 -G keycloak keycloak
COPY --from=upstream --chown=1000:1000 /opt/keycloak /opt/keycloak
COPY --from=dependencies /patched/guava-33.8.0-jre.jar /tmp/guava.jar
RUN set -eu; set -- /opt/keycloak/lib/lib/main/com.google.guava.guava-*.jar; test "$#" -eq 1; test -f "$1"; cp /tmp/guava.jar "$1"; rm /tmp/guava.jar
USER 1000:1000
WORKDIR /opt/keycloak
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
