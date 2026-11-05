FROM node:22-alpine AS build
WORKDIR /app
RUN apk add --no-cache python3
COPY package*.json ./
RUN npm ci
COPY scripts/setup-sdk.py ./scripts/setup-sdk.py
RUN npm run sdk
COPY index.html vite.config.js ./
COPY src ./src
RUN npm run build

FROM public.ecr.aws/nginx/nginx-unprivileged:stable-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
