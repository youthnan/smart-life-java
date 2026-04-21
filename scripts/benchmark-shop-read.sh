#!/usr/bin/env bash
# 店铺详情读压测：GET /shop/{id}（无需登录）
set -euo pipefail
BASE_URL="${BASE_URL:-http://127.0.0.1:8088}"
SHOP_ID="${SHOP_ID:-1}"
URL="${BASE_URL}/shop/${SHOP_ID}"

if command -v wrk >/dev/null 2>&1; then
  echo "wrk GET ${URL}"
  wrk -t4 -c50 -d30s "${URL}"
else
  echo "wrk 未安装，使用 10 秒 curl 冒烟（不代表 QPS）："
  for _ in $(seq 1 100); do
    curl -sS -o /dev/null -w "%{http_code}\n" "${URL}" || true
  done
fi
