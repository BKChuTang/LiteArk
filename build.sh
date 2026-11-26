#!/usr/bin/env bash
# LiteArk 一键构建：前端 → 静态资源 → fat jar
# 用法： ./build.sh               完整构建
#        ./build.sh --skip-front  前端未改动，只重新打 jar
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

SKIP_FRONT=0
if [ "${1:-}" = "--skip-front" ]; then SKIP_FRONT=1; fi

echo "==> [1/4] 环境检查"
if [ "$SKIP_FRONT" -eq 0 ]; then
  command -v node >/dev/null 2>&1 || { echo "缺少 Node.js（需 18+）"; exit 1; }
  NODE_MAJOR="$(node -p "process.versions.node.split('.')[0]")"
  if [ "$NODE_MAJOR" -lt 18 ]; then echo "Node.js 需 18+，当前 $NODE_MAJOR"; exit 1; fi
fi
command -v mvn >/dev/null 2>&1 || { echo "缺少 Maven；请设置 JAVA_HOME 并将 mvn 加入 PATH"; exit 1; }
if ! java -version 2>&1 | head -1 | grep -q '1\.8'; then
  echo "必须使用 JDK 8（当前：$(java -version 2>&1 | head -1)）"; exit 1
fi
echo "    JDK: $(java -version 2>&1 | head -1)"
[ "$SKIP_FRONT" -eq 1 ] || echo "    Node: $(node --version)"

if [ "$SKIP_FRONT" -eq 0 ]; then
  echo "==> [2/4] 构建前端"
  (cd web && npm ci && npm run build)
else
  echo "==> [2/4] 跳过前端构建（使用现有 static 产物）"
fi

echo "==> [3/4] 注入静态资源并打包"
if [ "$SKIP_FRONT" -eq 0 ]; then
  rm -rf src/main/resources/static/*
  cp -R web/dist/. src/main/resources/static/
fi
if [ -n "${MVN_SETTINGS:-}" ]; then
  mvn -s "$MVN_SETTINGS" clean package -DskipTests
else
  mvn clean package -DskipTests
fi

echo "==> [4/4] 校验产物"
[ -f target/liteark.jar ] || { echo "未生成 target/liteark.jar"; exit 1; }
echo "    产物: target/liteark.jar ($(du -h target/liteark.jar | cut -f1))"
echo "    启动: java -Dfile.encoding=UTF-8 -jar target/liteark.jar"
