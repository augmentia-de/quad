# Build context must be the repository root (..), so the relative
# quad-ui/… paths below resolve. Adjust the compose build.context
# accordingly and build with:  docker build -f quad-ui/docker/frontend.Dockerfile .
FROM node:20-alpine AS builder

WORKDIR /app
COPY quad-ui/package*.json ./
RUN npm ci

COPY quad-ui/ ./
RUN npm run build

FROM nginx:alpine

COPY --from=builder /app/dist /usr/share/nginx/html
COPY quad-ui/docker/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]