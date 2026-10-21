# 默认真名镜像；国内拉不动时可：
#   docker compose build --build-arg JAVA_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:21-jre
ARG JAVA_IMAGE=eclipse-temurin:21-jre
FROM ${JAVA_IMAGE}
WORKDIR /app
ENV TZ=Asia/Shanghai
COPY docker-entrypoint.sh /docker-entrypoint.sh
COPY target/sidehustle-daily-title.jar app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "/docker-entrypoint.sh"]
