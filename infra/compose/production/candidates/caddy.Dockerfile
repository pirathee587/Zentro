FROM caddy:2.11.9-builder AS build
RUN xcaddy build v2.11.9 --with golang.org/x/net@v0.62.0
FROM caddy:2.11.9@sha256:4851e54a46b206517c574821bb5cc67f170d2156041e9fbc4c9193b45e84a75b
COPY --from=build /usr/bin/caddy /usr/bin/caddy
