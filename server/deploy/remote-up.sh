#!/bin/sh
# Runs on the NAS inside the deploy directory, after the bundle has been extracted.
# Usage: sh remote-up.sh [sudo]
set -eu

SUDO="${1:-}"
cd "$(dirname "$0")"

if [ ! -f .env ]; then
  cp .env.example .env
  chmod 600 .env
  echo "首次部署：已生成 $(pwd)/.env，请填写后重新执行部署。" >&2
  exit 3
fi

if $SUDO docker compose version >/dev/null 2>&1; then
  DC="$SUDO docker compose"
else
  DC="$SUDO docker-compose"
fi

# Keep the running image as the rollback point (see docs/BFF.md, 回滚).
if $SUDO docker image inspect voicebook-bff:latest >/dev/null 2>&1; then
  $SUDO docker tag voicebook-bff:latest voicebook-bff:previous
fi

$DC up -d --build --remove-orphans
$SUDO docker image prune -f >/dev/null 2>&1 || true

echo "等待健康检查..."
i=0
while [ $i -lt 40 ]; do
  status="$($SUDO docker inspect -f '{{.State.Health.Status}}' voicebook-bff 2>/dev/null || echo missing)"
  if [ "$status" = "healthy" ]; then
    echo "voicebook-bff 已就绪"
    exit 0
  fi
  i=$((i + 1))
  sleep 3
done

echo "voicebook-bff 未通过健康检查（状态: $status），最近日志：" >&2
$SUDO docker logs --tail 80 voicebook-bff >&2 || true
exit 1
