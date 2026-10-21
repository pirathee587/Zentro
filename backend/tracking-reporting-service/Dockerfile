FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY libs/zentro-security-starter/pom.xml libs/zentro-security-starter/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY identity-partner-service/pom.xml identity-partner-service/pom.xml
COPY parcel-pricing-service/pom.xml parcel-pricing-service/pom.xml
COPY dispatch-courier-service/pom.xml dispatch-courier-service/pom.xml
COPY tracking-reporting-service/pom.xml tracking-reporting-service/pom.xml
COPY notification-integration-service/pom.xml notification-integration-service/pom.xml
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -Dmaven.wagon.http.retryHandler.count=5 -pl tracking-reporting-service -am package -DskipTests && JAR_FILE="$(find tracking-reporting-service/target -maxdepth 1 -type f -name '*.jar' ! -name '*-sources.jar' | head -n 1)" && test -n "$JAR_FILE" && cp "$JAR_FILE" /workspace/app.jar
FROM eclipse-temurin:21-jre-alpine
ARG GIT_SHA=dev
LABEL org.opencontainers.image.title="Zentro Tracking Reporting Service" org.opencontainers.image.source="https://github.com/zentro/zentro" org.opencontainers.image.revision="${GIT_SHA}"
RUN addgroup -S -g 1001 zentro && adduser -S -D -H -u 1001 -G zentro zentro
WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar
USER 1001:1001
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8084
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 CMD wget -q -O /dev/null http://localhost:8084/actuator/health/liveness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
