# VoiceBook BFF 运行时镜像。
#
# 采用「本地构建 + 镜像只装 JAR」的策略：Gradle 在多阶段镜像里跑一遍要拉全量依赖，
# 而本机（或 CI）已经有 JDK 与依赖缓存，出 fat jar 只需几秒。
#
# 构建步骤（在仓库根目录）：
#   export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
#   ./gradlew :server2:buildFatJar
#   # 产物：server2/build/libs/server-all.jar
FROM eclipse-temurin:21-jre-alpine

# 时区（日志时间与 NAS 一致）
RUN apk add --no-cache tzdata && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone

WORKDIR /app

# Ktor 插件 buildFatJar 的产物名固定为 <project>-all.jar
COPY server2/build/libs/server-all.jar /app/server-all.jar

# 非 root 运行
RUN addgroup -g 1000 -S vbff && adduser -u 1000 -S vbff -G vbff && chown -R vbff:vbff /app
USER vbff

EXPOSE 8080

# 容器内存有限，给 JVM 一个保守的上限（NAS 上总量 8G 且已跑多个服务）
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Dfile.encoding=UTF-8"

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/server-all.jar"]
