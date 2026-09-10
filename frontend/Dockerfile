# The Angular UI, built once and served as static files by Nginx.
#
# Built from this directory, unlike the Java services which need the repository root for their
# parent POM:
#
#   docker build -t discoveryhub/frontend frontend
#
# There is no Node in the final image. `ng serve` is a development server — it is slower, it
# rebuilds on the fly, and it is not something to put in front of anyone. What ships is the
# production bundle and a web server.
FROM node:24-alpine AS build
WORKDIR /app

# Lockfile first, so a source-only change reuses the cached install layer. `npm ci` rather than
# `npm install`: it installs exactly what the lockfile pins and fails if package.json disagrees,
# which is what a reproducible image needs.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# Angular's own analytics prompt blocks a non-interactive build and would otherwise write a UUID
# into angular.json inside the image.
ENV NG_CLI_ANALYTICS=false
RUN npm run build

FROM nginx:1.27-alpine AS runtime

# `@angular/build:application` emits the browser bundle into dist/<project>/browser — the parent
# dist/frontend also holds a prerender/server directory on some configurations, and copying that
# would put non-servable files under the web root.
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html

# Replaces the image's default site, which has no SPA fallback.
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Nginx's own port. The published port is 4200, mapped in docker-compose.yml — the container does
# not need to know what it is reached on.
EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -qO- http://127.0.0.1/healthz || exit 1

# nginx:alpine's own entrypoint handles config templating and starts in the foreground.
