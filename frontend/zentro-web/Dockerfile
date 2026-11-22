FROM node:22-alpine AS build
WORKDIR /app
ARG VITE_API_BASE_URL=http://localhost:8080
ARG VITE_KEYCLOAK_URL=http://localhost:8180
ARG VITE_KEYCLOAK_REALM=zentro
ARG VITE_KEYCLOAK_CLIENT_ID=zentro-web
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL \
    VITE_KEYCLOAK_URL=$VITE_KEYCLOAK_URL \
    VITE_KEYCLOAK_REALM=$VITE_KEYCLOAK_REALM \
    VITE_KEYCLOAK_CLIENT_ID=$VITE_KEYCLOAK_CLIENT_ID
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build
# Allow the configured Keycloak origin as the only cross-origin frame source (OIDC silent check).
RUN case "$VITE_KEYCLOAK_URL" in http://*|https://*) ;; *) echo "VITE_KEYCLOAK_URL must be an http(s) URL" >&2; exit 1;; esac \
 && sed "s#__KEYCLOAK_URL__#${VITE_KEYCLOAK_URL%/}#g" nginx.conf > /app/nginx.rendered.conf \
 && grep -q "frame-src 'self' http" /app/nginx.rendered.conf \
 && ! grep -q '__KEYCLOAK_URL__' /app/nginx.rendered.conf
FROM nginxinc/nginx-unprivileged:1.27-alpine
ARG GIT_SHA=unknown
ARG APP_VERSION=unknown
LABEL org.opencontainers.image.source="https://github.com/pirathee587/Zentro" org.opencontainers.image.revision="${GIT_SHA}" org.opencontainers.image.version="${APP_VERSION}"
COPY --from=build /app/nginx.rendered.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
