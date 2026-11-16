# 站点镜像：构建 Web 管理端，再放进 nginx。构建上下文是 web/（见 compose.yml）。
# nginx 的配置不在镜像里，由 compose 挂载 deploy/nginx.conf。

FROM node:22-slim AS build
WORKDIR /src
COPY package.json package-lock.json .npmrc ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /src/dist /usr/share/nginx/html
