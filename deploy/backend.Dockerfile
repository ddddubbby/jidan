# 后端镜像：在容器里用仓库自带的 mvnw 打包，部署的机器上只需要 Docker。
# 构建上下文是 backend/（见 compose.yml）。

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# 先只拷 pom，依赖这一层在源码变化时能复用
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
# 测试要用 Testcontainers 起 MySQL，镜像构建里没有 Docker；测试由开发时的 make verify 负责
RUN ./mvnw -B -q -DskipTests package && cp target/egg-backend-*.jar /app.jar

FROM eclipse-temurin:21-jre
# 中文姓名等环境变量要在 UTF-8 区域设置下读（见 .env.example）
ENV LANG=C.UTF-8 TZ=Asia/Shanghai
COPY --from=build /app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
