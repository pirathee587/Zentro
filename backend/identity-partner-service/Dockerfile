FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
ARG GIT_SHA=unknown
COPY backend/pom.xml backend/pom.xml
COPY backend/libs/zentro-security-starter/pom.xml backend/libs/zentro-security-starter/pom.xml
COPY backend/api-gateway/pom.xml backend/api-gateway/pom.xml
COPY backend/identity-partner-service/pom.xml backend/identity-partner-service/pom.xml
COPY backend/parcel-pricing-service/pom.xml backend/parcel-pricing-service/pom.xml
COPY backend/dispatch-courier-service/pom.xml backend/dispatch-courier-service/pom.xml
COPY backend/tracking-reporting-service/pom.xml backend/tracking-reporting-service/pom.xml
COPY backend/notification-integration-service/pom.xml backend/notification-integration-service/pom.xml
COPY backend/ backend/
COPY contracts/ contracts/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -Dbuild.revision="$GIT_SHA" -Dmaven.wagon.http.retryHandler.count=5 -f backend/pom.xml -pl identity-partner-service -am package -DskipTests && JAR_FILE="$(find backend/identity-partner-service/target -maxdepth 1 -type f -name '*.jar' ! -name '*-sources.jar' | head -n 1)" && test -n "$JAR_FILE" && cp "$JAR_FILE" /workspace/app.jar
FROM eclipse-temurin:21-jre-alpine
ARG GIT_SHA=unknown
ARG APP_VERSION=unknown
LABEL org.opencontainers.image.title="Zentro Identity Partner Service" org.opencontainers.image.source="https://github.com/pirathee587/Zentro" org.opencontainers.image.revision="${GIT_SHA}" org.opencontainers.image.version="${APP_VERSION}"
RUN addgroup -S -g 1001 zentro && adduser -S -D -H -u 1001 -G zentro zentro
WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar
USER 1001:1001
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8081 9081
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 CMD wget -q -O /dev/null http://localhost:8081/actuator/health/liveness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
