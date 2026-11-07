# 短链接平台运行镜像
# 只需要 JRE，不需要 JDK 和 Maven —— 编译在宿主机做，镜像里只放运行产物
FROM eclipse-temurin:8-jre-jammy

WORKDIR /app

# TZ：容器默认 UTC，不改的话日志时间会比北京时间差 8 小时，
#     排查问题时对不上 MySQL 里的 create_time
# JAVA_OPTS：堆内存上限，容器内存有限，不设的话 JVM 会按宿主机内存算
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms256m -Xmx512m -Dfile.encoding=UTF-8"

# 先拷 jar 再写 ENTRYPOINT：Docker 分层缓存，jar 不变时这层复用
COPY target/shortlink.jar app.jar

EXPOSE 8080

# 两个细节：
# 1) exec 让 java 成为 PID 1。不用 exec 的话 PID 1 是 sh，docker stop 发的
#    SIGTERM 到不了 JVM，Spring 的 @PreDestroy 不执行，只能等 10 秒超时被 SIGKILL
# 2) sh -c 是为了让 $JAVA_OPTS 展开；不用 exec 形式无法展开环境变量
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
