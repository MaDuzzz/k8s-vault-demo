FROM node:22-alpine AS build

WORKDIR /app
COPY package*.json ./
# npm 10.x bundled with Node 22 can crash in Arborist peer resolution with
# "Cannot read properties of null (reading 'edgesOut')". All peer packages are
# declared explicitly, so legacy-peer-deps safely avoids that broken code path.
RUN if [ -f package-lock.json ]; then \
      npm ci --ignore-scripts --legacy-peer-deps; \
    else \
      npm install --ignore-scripts --legacy-peer-deps; \
    fi
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.29-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=2s --start-period=5s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
