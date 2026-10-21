#!/bin/sh
# 容器里的 127.0.0.1 不是本机。OrbStack 用 host.docker.internal 访问宿主机代理。
rewrite() {
  printf '%s' "$1" | sed \
    -e 's#://127\.0\.0\.1:#://host.docker.internal:#' \
    -e 's#://localhost:#://host.docker.internal:#'
}
if [ -n "$HTTPS_PROXY" ]; then
  export HTTPS_PROXY="$(rewrite "$HTTPS_PROXY")"
fi
if [ -n "$https_proxy" ]; then
  export https_proxy="$(rewrite "$https_proxy")"
fi
exec java -jar app.jar
