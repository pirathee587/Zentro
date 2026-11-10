FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
ARG GIT_SHA=unknown
COPY pom.xml pom.xml
COPY api-gateway api-gateway
RUN mvn -B -f api-gateway/pom.xml -DskipTests -Dbuild.revision="$GIT_SHA" package

FROM eclipse-temurin:21-jre
ARG GIT_SHA=unknown
ARG APP_VERSION=unknown
LABEL org.opencontainers.image.source="https://github.com/pirathee587/Zentro" org.opencontainers.image.revision="${GIT_SHA}" org.opencontainers.image.version="${APP_VERSION}"
WORKDIR /app
RUN useradd --system --uid 10001 --create-home gateway
COPY --from=build /workspace/api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
