#!/usr/bin/env bash
# 一键部署 VoiceBook BFF 到 NAS（Linux/macOS/Git Bash）。
# 用法: BFF_NAS_HOST=admin@nas ./scripts/deploy-bff.sh [--port 22] [--sudo] [--skip-tests]
set -euo pipefail

NAS_HOST="${BFF_NAS_HOST:-}"
REMOTE_DIR="${BFF_REMOTE_DIR:-voicebook-bff}"
SSH_PORT=22
SUDO=""
SKIP_TESTS=0

while [ $# -gt 0 ]; do
  case "$1" in
    --host) NAS_HOST="$2"; shift 2 ;;
    --dir) REMOTE_DIR="$2"; shift 2 ;;
    --port) SSH_PORT="$2"; shift 2 ;;
    --sudo) SUDO="sudo"; shift ;;
    --skip-tests) SKIP_TESTS=1; shift ;;
    *) echo "未知参数 $1" >&2; exit 2 ;;
  esac
done
[ -n "$NAS_HOST" ] || { echo "请用 --host user@host 或 BFF_NAS_HOST 指定 NAS" >&2; exit 2; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/server/build"
BUNDLE="$BUILD/deploy-bundle"
ARCHIVE="$BUILD/voicebook-bff.tgz"

echo "==> 构建"
tasks=(":server:installDist" "--console=plain")
[ "$SKIP_TESTS" = 1 ] || tasks=(":server:test" "${tasks[@]}")
(cd "$ROOT" && ./gradlew "${tasks[@]}")

echo "==> 打包"
rm -rf "$BUNDLE" "$ARCHIVE"
mkdir -p "$BUNDLE"
cp -R "$BUILD/install/bff" "$BUNDLE/bff"
cp "$ROOT/server/deploy/"{Dockerfile,docker-compose.yml,.env.example,remote-up.sh} "$BUNDLE/"
tar -czf "$ARCHIVE" -C "$BUNDLE" .

echo "==> 上传到 $NAS_HOST:$REMOTE_DIR"
SSH_OPTS=(-p "$SSH_PORT")
[ -z "$SUDO" ] || SSH_OPTS+=(-t)
ssh "${SSH_OPTS[@]}" "$NAS_HOST" "mkdir -p '$REMOTE_DIR'"
scp -P "$SSH_PORT" "$ARCHIVE" "$NAS_HOST:$REMOTE_DIR/voicebook-bff.tgz"

echo "==> 远端启动"
set +e
ssh "${SSH_OPTS[@]}" "$NAS_HOST" \
  "cd '$REMOTE_DIR' && rm -rf bff && tar -xzf voicebook-bff.tgz && rm -f voicebook-bff.tgz && sh remote-up.sh $SUDO"
code=$?
set -e
case $code in
  0) echo "部署完成。验证: pwsh scripts/bff-smoke.ps1 -BaseUrl https://<域名>:8462 -Token <access_token>" ;;
  3) echo "请编辑 NAS 上 $REMOTE_DIR/.env 后重跑。"; exit 3 ;;
  *) echo "远端部署失败，退出码 $code" >&2; exit "$code" ;;
esac
