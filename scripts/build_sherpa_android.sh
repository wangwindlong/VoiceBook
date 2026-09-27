#!/usr/bin/env bash
# ============================================================================
# VoiceBook sherpa-onnx Android 静态库构建脚本（arm64-v8a）
#
# 为什么需要这个脚本
# ------------------
# 官方发布的 Android 预编译包是「动态链接 + 多 so」形态，直接进 jniLibs 会有 5 个文件：
#     libsherpa-onnx-jni.so      4.54 MiB
#     libsherpa-onnx-c-api.so    4.25 MiB
#     libsherpa-onnx-cxx-api.so  0.42 MiB
#     libonnxruntime.so         20.68 MiB
#     libomp.so                  1.17 MiB
#   ≈ 31.06 MiB / ABI
# 其中有两处浪费：
#   ① c-api / cxx-api 两个 so 各自重复携带一份 sherpa 核心代码。JNI 层并不依赖它们
#      （readelf -d 确认 libsherpa-onnx-jni.so 的 NEEDED 里没有任何 sherpa so），
#      本 App 只走 JNI，故这两个 so 是纯冗余。
#   ② libomp.so 没有任何 so 依赖它（三者 NEEDED 均未出现），同样是冗余。
#   ③ onnxruntime 以独立动态库存在，未经链接期 GC。
#
# 改为「静态链接 onnxruntime 的单一 so」后，实测 23.11 MiB（-7.95 MiB / -25.6%），
# 且**功能零裁剪**：TTS / 离线+流式 STT / VAD / KWS / 说话人分离 / 声源分离(人声剥离)
# / 语音降噪 / 标点恢复 / 音频标签 / 语种识别 全部保留 —— 本脚本不传任何
# SHERPA_ONNX_ENABLE_*=OFF（官方也只有 TTS 与说话人分离两个开关，其余模块无开关）。
#
# 与 voice/libs/sherpa-classes.jar 的兼容性（已实测）
# -------------------------------------------
# jar 是 sherpa-onnx 1.13.6 的 Kotlin API，本脚本默认编 v1.13.8。两者导出的 JNI 符号
# 均为 133 个、差异为 0（llvm-nm -D 对比），可直接替换。若日后升级 jar，请重跑本脚本
# 并核对符号数：
#     llvm-nm -D --defined-only <so> | awk '{print $3}' | grep ^Java | sort -u | wc -l
#
# 设计原则（对齐 scripts/build_native.sh）
# --------------------------------------
#   - sherpa-onnx 源码**不 vendored** 进项目，克隆到 $SRC_ROOT（独立目录，可复用）
#   - 只把「strip 后的单 so」同步进 androidApp/src/main/jniLibs/<abi>/
#   - 同步时清理旧的 5 so，避免动态版与静态版混装（混装会导致 APK 里白白多 8 MiB）
#   - onnxruntime 预下载到源码根目录，官方 cmake 会自动识别并跳过联网下载
#
# 用法
# ----
#   bash scripts/build_sherpa_android.sh           # 全流程：源码 → 编译 → strip → 同步 → 体检
#   bash scripts/build_sherpa_android.sh deps      # 只准备源码 + onnxruntime 包
#   bash scripts/build_sherpa_android.sh build     # 只编译 + strip + 同步
#   bash scripts/build_sherpa_android.sh check     # 只体检当前 jniLibs（体积/依赖/符号）
#
# 环境变量（可选）
# ----------------
#   SRC_ROOT         外部源码根目录    默认 ~/work/tools/ai（与 build_native.sh 一致）
#   SHERPA_VERSION   sherpa-onnx tag   默认 v1.13.8（v1.13.6 无静态 onnxruntime 的 cmake 支持）
#   ANDROID_NDK      NDK 路径          默认 ~/work/tools/sdk/ndk/27.2.12479018
#   ANDROID_ABI      ABI               默认 arm64-v8a
#   USE_MIRROR       1=经 gh-proxy 拉取 默认 1（国内直连 GitHub 约 400 KB/s，镜像约 13 MB/s）
#
# 维护提示：改动本脚本后请同步 docs/VOICE.md 的 sherpa 小节。
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_ROOT="${SRC_ROOT:-$HOME/work/tools/ai}"
SHERPA_SRC="$SRC_ROOT/sherpa-onnx"
SHERPA_VERSION="${SHERPA_VERSION:-v1.13.8}"
ANDROID_NDK="${ANDROID_NDK:-$HOME/work/tools/sdk/ndk/27.2.12479018}"
ANDROID_ABI="${ANDROID_ABI:-arm64-v8a}"
USE_MIRROR="${USE_MIRROR:-1}"
JNI_LIBS="$ROOT/androidApp/src/main/jniLibs/$ANDROID_ABI"
BUILD_DIR="$SHERPA_SRC/build-android-$ANDROID_ABI"

MIRROR=""
if [[ "$USE_MIRROR" == "1" ]]; then MIRROR="https://gh-proxy.com/"; fi

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[!]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[x]\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- 前置检查
preflight() {
  for c in cmake ninja git curl; do
    command -v "$c" >/dev/null || die "缺少命令: $c"
  done
  [[ -d "$ANDROID_NDK" ]] || die "NDK 不存在: $ANDROID_NDK（可用 ANDROID_NDK=... 覆盖）"
  local strip="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
  [[ -x "$strip" ]] || die "找不到 llvm-strip: $strip"
}

strip_bin() { echo "$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"; }

# ---------------------------------------------------------------- deps
prepare_deps() {
  mkdir -p "$SRC_ROOT"
  if [[ ! -d "$SHERPA_SRC/.git" ]]; then
    log "克隆 sherpa-onnx（$SHERPA_VERSION）→ $SHERPA_SRC"
    # 浅克隆 default 分支再取目标 tag，比 --branch 更省流量
    git clone --depth 1 "${MIRROR}https://github.com/k2-fsa/sherpa-onnx.git" "$SHERPA_SRC"
  fi
  cd "$SHERPA_SRC"
  if ! git rev-parse --verify -q "refs/tags/$SHERPA_VERSION" >/dev/null; then
    log "拉取 tag $SHERPA_VERSION"
    git fetch --depth 1 origin "refs/tags/$SHERPA_VERSION:refs/tags/$SHERPA_VERSION"
  fi
  git checkout -q "refs/tags/$SHERPA_VERSION"

  # 官方 cmake 支持把 onnxruntime 压缩包放在源码根目录（possible_file_locations），
  # 命中则不联网。不同版本的 cmake 文件布局不同，这里统一用 grep 全目录解析下载地址。
  local ort_url ort_zip
  ort_url="$(grep -rhoE 'https://github\.com/[^"[:space:]]*onnxruntime-android[^"[:space:]]*\.zip' cmake/ 2>/dev/null | head -1 || true)"
  if [[ -z "$ort_url" ]]; then
    warn "未能从 cmake/ 解析出 onnxruntime 的 Android 包地址，将交由 cmake 自行联网下载"
    return 0
  fi
  ort_zip="$(basename "$ort_url")"
  if [[ -f "$SHERPA_SRC/$ort_zip" ]]; then
    log "onnxruntime 包已就位：$ort_zip（$(du -h "$SHERPA_SRC/$ort_zip" | cut -f1)）"
    return 0
  fi
  log "下载 onnxruntime：$ort_zip"
  curl -fL --retry 3 -o "$SHERPA_SRC/$ort_zip" "${MIRROR}${ort_url}"
  ls -lh "$SHERPA_SRC/$ort_zip"
}

# ---------------------------------------------------------------- build
# C API 合并（关键）
# ----------------
# jni.so 默认用 version-script 只导出 `Java_com_k2fsa_sherpa_onnx*`，导致**只有 C API 暴露
# 的能力在 App 侧无法调用** —— 典型例子是声源分离（人声剥离）：官方至今（含 master）都
# 没有提供它的 Kotlin API，只有 `SherpaOnnxCreateOfflineSourceSeparation`。
# 把 c-api.cc 一并编进 jni.so 并放开两组符号后，JNI(133) 与 C API(173) 同时可用，
# 实测仅 +0.21 MiB（23.08 → 23.29 MiB）。本函数幂等，可重复运行。
apply_merged_capi_patch() {
  local src="$1"
  cat > "$src/sherpa-onnx/jni/sherpa-onnx-symbols.lds" <<'LDS'
{
  global:
    Java_com_k2fsa_sherpa_onnx*;
    SherpaOnnx*;
    SherpaOffline*;
  local:
    *;
};
LDS
  local jni_cmake="$src/sherpa-onnx/jni/CMakeLists.txt"
  if ! grep -q "c-api/c-api.cc" "$jni_cmake"; then
    sed -i 's|^set(sources|set(sources\n  ${PROJECT_SOURCE_DIR}/sherpa-onnx/c-api/c-api.cc|' "$jni_cmake"
    log "已把 c-api.cc 并入 jni 目标（放行 SherpaOnnx* 符号，恢复人声剥离等纯 C API 能力）"
  fi
}

do_build() {
  cd "$SHERPA_SRC"
  apply_merged_capi_patch "$SHERPA_SRC"
  # 进程级 git 重写：只影响本次构建拉依赖（kaldi-native-fbank / espeak-ng / hifigan 等），
  # 不污染用户的全局 git 配置。
  if [[ -n "$MIRROR" ]]; then
    export GIT_CONFIG_COUNT=1
    export GIT_CONFIG_KEY_0="url.${MIRROR}https://github.com/.insteadOf"
    export GIT_CONFIG_VALUE_0="https://github.com/"
  fi

  log "配置（全功能：TTS/STT/VAD/KWS/说话人分离/声源分离… 一个都不关）"
  cmake -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ANDROID_ABI" \
    -DANDROID_PLATFORM=android-24 \
    -DSHERPA_ONNX_ENABLE_JNI=ON \
    -DSHERPA_ONNX_ENABLE_BINARY=OFF \
    -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF \
    -DSHERPA_ONNX_ENABLE_TESTS=OFF \
    -DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF \
    -DCMAKE_BUILD_TYPE=Release \
    > /dev/null

  log "编译 sherpa-onnx-jni（$(nproc) 并发）"
  cmake --build "$BUILD_DIR" --target sherpa-onnx-jni -j"$(nproc)" 2>&1 | tail -3

  local raw="$BUILD_DIR/lib/libsherpa-onnx-jni.so"
  [[ -f "$raw" ]] || die "编译产物缺失: $raw"

  log "strip（未 strip 约 756 MiB，strip 后约 23 MiB）"
  mkdir -p "$JNI_LIBS"
  "$(strip_bin)" --strip-unneeded -o "$JNI_LIBS/libsherpa-onnx-jni.so" "$raw"

  sync_jnilibs
  report
}

# 清理动态版的冗余 so，避免与静态版混装
sync_jnilibs() {
  local removed=0
  for f in libonnxruntime.so libomp.so libsherpa-onnx-c-api.so libsherpa-onnx-cxx-api.so; do
    if [[ -f "$JNI_LIBS/$f" ]]; then
      log "移除冗余 so：$f（$(du -h "$JNI_LIBS/$f" | cut -f1)）"
      rm -f "$JNI_LIBS/$f"; removed=1
    fi
  done
  [[ "$removed" == "1" ]] && log "已清理动态版冗余 so（静态版已把 onnxruntime 链进主 so）"
  return 0
}

# ---------------------------------------------------------------- check / report
report() {
  log "jniLibs/$ANDROID_ABI 现状"
  local total=0
  while read -r sz name; do
    printf "    %10d B  %s\n" "$sz" "$name"
    total=$((total + sz))
  done < <(find "$JNI_LIBS" -maxdepth 1 -name '*.so' -printf '%s %f\n' | sort -rn)
  printf "    合计 %.2f MiB\n" "$(echo "scale=4; $total/1048576" | bc)"

  log "外部依赖（应为 0 个 sherpa/onnxruntime 动态库）"
  local so="$JNI_LIBS/libsherpa-onnx-jni.so"
  if [[ -f "$so" ]]; then
    readelf -d "$so" | grep NEEDED | sed 's/^/    /'
    local n; n=$(readelf -d "$so" | grep NEEDED | grep -c "sherpa-onnx\|onnxruntime" || true)
    [[ "$n" == "0" ]] && log "已确认自包含（无 sherpa/onnxruntime 动态依赖）" \
                      || warn "仍存在动态依赖，静态链接可能未生效"

    # 符号核对：JNI 与 C API 两组都要在。缺 C API 会导致人声剥离等纯 C API 能力无法调用。
    local nm_bin="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm"
    if [[ -x "$nm_bin" ]]; then
      local jni_cnt capi_cnt
      jni_cnt=$("$nm_bin" -D --defined-only "$so" | grep -c 'Java_com_k2fsa' || true)
      capi_cnt=$("$nm_bin" -D --defined-only "$so" | grep -c 'SherpaOnnx' || true)
      log "导出符号：JNI ${jni_cnt} 个 / C API ${capi_cnt} 个"
      if [[ "$jni_cnt" -ge 133 && "$capi_cnt" -ge 173 ]]; then
        log "符号完整（C API 含 SherpaOnnxCreateOfflineSourceSeparation —— 人声剥离可用）"
      else
        warn "符号数低于预期（应为 JNI 133 / C API 173），请检查合并 patch 是否生效"
      fi
    fi
  fi
}

do_check() {
  [[ -d "$JNI_LIBS" ]] || die "目录不存在: $JNI_LIBS"
  report
}

case "${1:-all}" in
  deps)  preflight; prepare_deps ;;
  build) preflight; do_build ;;
  check) do_check ;;
  all)   preflight; prepare_deps; do_build ;;
  *)     die "未知子命令: $1（可用: all | deps | build | check）" ;;
esac
