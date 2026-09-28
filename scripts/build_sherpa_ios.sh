#!/usr/bin/env bash
# ============================================================================
# VoiceBook sherpa-onnx iOS 构建脚本（C API 静态库 → xcframework）
#
# 为什么 iOS 需要单独一条构建路径
# ------------------------------
# Kotlin/Native 加载不了 JVM 的 JNI 动态库，Android 那套 libsherpa-onnx-jni.so
# 在 iOS 完全不可用。官方为 iOS 提供的正是 C API（SHERPA_ONNX_ENABLE_JNI=OFF +
# SHERPA_ONNX_ENABLE_C_API=ON 的静态库），voice 模块用 cinterop 绑 c-api.h 接入。
#
# 产物（落到 voice/ios/sherpa-onnx-<ver>-ios/）
# --------------------------------------------
#   c-api.h                 cinterop 绑定用的头文件（与库同版本，结构体布局必须一致）
#   sherpa-onnx.xcframework device + simulator 双切片的 SherpaOnnxC.framework，
#                           内部是 libtool 合并 12 个中间静态库的单体 .a
#
# 配方对齐 sherpa-onnx 官方 build-ios.sh：
#   ① 逐平台 cmake 编静态库（PLATFORM=OS64|SIMULATORARM64，toolchains/ios.toolchain.cmake）
#   ② libtool -static 合并中间静态库
#   ③ 包成 SherpaOnnxC.framework（Headers + module.modulemap + Info.plist）
#   ④ xcodebuild -create-xcframework 合成双切片
#
# 仅 macOS + Xcode 可执行 build；deps（克隆源码 + 下 onnxruntime xcframework）
# 任意平台都能跑，可先在手边机器备好再拷到 $SRC_ROOT 供 macOS 使用。
# 没产出 xcframework 时 voice 模块自动跳过 cinterop，iOS 无本地推理（云端兜底）。
#
# 用法
# ----
#   bash scripts/build_sherpa_ios.sh deps   # 只准备源码 + onnxruntime 包（任意平台）
#   bash scripts/build_sherpa_ios.sh build  # 只编译（macOS + Xcode）
#   bash scripts/build_sherpa_ios.sh check  # 只体检产物
#   bash scripts/build_sherpa_ios.sh        # 全流程
#
# 环境变量（可选）
# ----------------
#   SRC_ROOT                       外部源码根目录  默认 ~/work/tools/ai
#   SHERPA_VERSION                 sherpa-onnx tag 默认 v1.13.8（与 build_sherpa_android.sh 一致）
#   SHERPA_ONNX_ONNXRUNTIME_VERSION onnxruntime    默认 1.28.2
#   SHERPA_IOS_PLATFORMS           默认 "OS64 SIMULATORARM64"
#   USE_MIRROR                     1=经 gh-proxy  默认 1
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_ROOT="${SRC_ROOT:-$HOME/work/tools/ai}"
SHERPA_SRC="$SRC_ROOT/sherpa-onnx"
SHERPA_VERSION="${SHERPA_VERSION:-v1.13.8}"
SHERPA_VER_NUM="${SHERPA_VERSION#v}"
USE_MIRROR="${USE_MIRROR:-1}"

MIRROR=""
[[ "$USE_MIRROR" == "1" ]] && MIRROR="https://gh-proxy.com/"

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[!]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[x]\033[0m %s\n' "$*" >&2; exit 1; }

IOS_ORT_VER="${SHERPA_ONNX_ONNXRUNTIME_VERSION:-1.28.2}"
IOS_ORT_ROOT="$SHERPA_SRC/ios-onnxruntime"
IOS_ORT_XCFW="$IOS_ORT_ROOT/onnxruntime.xcframework"
OUT_DIR="$ROOT/voice/ios/sherpa-onnx-${SHERPA_VER_NUM}-ios"
BUILD_DIR="$SHERPA_SRC/build-ios"

# ---------------------------------------------------------------- 前置检查
preflight() {
  for c in cmake ninja git curl; do
    command -v "$c" >/dev/null || die "缺少命令: $c"
  done
  # deps 只做「克隆源码 + 下 onnxruntime 包」，任何平台都能做——便于先在手边
  # 机器备好再拷到 macOS；只有真正 build 才需要 Xcode。
  if [[ "${1:-}" == "deps" ]]; then
    log "iOS deps 可在任意平台准备（仅 build 需要 macOS + Xcode）"
  elif ! command -v xcrun >/dev/null || [[ "$(uname -s)" != "Darwin" ]]; then
    die "iOS 目标必须在 macOS + Xcode 上构建（本机 $(uname -s) 无 iOS SDK）。
    可先在本机执行 'bash scripts/build_sherpa_ios.sh deps' 准备源码与 onnxruntime 包，
    再把 \$SRC_ROOT/sherpa-onnx 拷到 macOS 上继续 build。"
  fi
}

# ---------------------------------------------------------------- deps
# onnxruntime 官方只提供 iOS xcframework（含 device + simulator 两套 framework），
# 不能用其它平台的静态 zip。下载后展开成 ios-onnxruntime/onnxruntime.xcframework，
# 供 do_build_ios 逐个平台指路。
prepare_ios_ort() {
  local ver="$IOS_ORT_VER"
  local zip="onnxruntime-ios-static-xcframework-${ver}.xcframework.zip"
  local url="https://github.com/csukuangfj/onnxruntime-libs/releases/download/v${ver}/${zip}"

  if [[ -d "$IOS_ORT_XCFW/ios-arm64" ]]; then
    log "onnxruntime iOS xcframework 已就位（$ver）"
    return 0
  fi

  mkdir -p "$IOS_ORT_ROOT"
  if [[ ! -f "$IOS_ORT_ROOT/$zip" ]]; then
    log "下载 onnxruntime iOS xcframework：$zip（较大，走镜像加速）"
    curl -fL --retry 3 -o "$IOS_ORT_ROOT/$zip" "${MIRROR}${url}" \
      || die "下载失败：$url"
    ls -lh "$IOS_ORT_ROOT/$zip"
  fi

  log "展开 $zip"
  ( cd "$IOS_ORT_ROOT" && unzip -q -o "$zip" )
  # 包内层级为 <ver>/onnxruntime.xcframework，官方脚本再建一层同名软链
  if [[ ! -d "$IOS_ORT_XCFW" && -d "$IOS_ORT_ROOT/$ver/onnxruntime.xcframework" ]]; then
    ln -sfn "$IOS_ORT_ROOT/$ver/onnxruntime.xcframework" "$IOS_ORT_XCFW"
  fi
  [[ -d "$IOS_ORT_XCFW/ios-arm64" ]] || die "xcframework 结构异常：$IOS_ORT_XCFW"
  log "onnxruntime 已就位：$IOS_ORT_XCFW"
  ls "$IOS_ORT_XCFW" | sed 's/^/    /'
}

prepare_deps() {
  mkdir -p "$SRC_ROOT"
  if [[ ! -d "$SHERPA_SRC/.git" ]]; then
    log "克隆 sherpa-onnx（$SHERPA_VERSION）→ $SHERPA_SRC"
    git clone --depth 1 "${MIRROR}https://github.com/k2-fsa/sherpa-onnx.git" "$SHERPA_SRC"
  fi
  cd "$SHERPA_SRC"
  if ! git rev-parse --verify -q "refs/tags/$SHERPA_VERSION" >/dev/null; then
    log "拉取 tag $SHERPA_VERSION"
    git fetch --depth 1 origin "refs/tags/$SHERPA_VERSION:refs/tags/$SHERPA_VERSION"
  fi
  git checkout -q "refs/tags/$SHERPA_VERSION"

  prepare_ios_ort
}

# ---------------------------------------------------------------- build
# 各平台在 xcframework 里的切片目录名（与 onnxruntime xcframework 的命名保持一致）
IOS_PLATFORMS="${SHERPA_IOS_PLATFORMS:-OS64 SIMULATORARM64}"

ios_slice_for() {
  case "$1" in
    OS64)           echo "ios-arm64" ;;
    SIMULATORARM64) echo "ios-arm64_x86_64-simulator" ;;
    SIMULATOR64)    echo "ios-arm64_x86_64-simulator" ;;
    *)              echo "ios-unknown" ;;
  esac
}

do_build_ios() {
  cd "$SHERPA_SRC"

  local header_src="$SHERPA_SRC/sherpa-onnx/c-api/c-api.h"
  [[ -f "$header_src" ]] || die "找不到 c-api.h：$header_src"

  local -a merged_libs=()   # 收集各平台合并后的 framework
  local plat

  for plat in $IOS_PLATFORMS; do
    local slice; slice="$(ios_slice_for "$plat")"
    local bdir="$BUILD_DIR/$plat"
    local fw_dir="$IOS_ORT_XCFW/$slice/onnxruntime.framework"

    # 模拟器的切片目录名可能不带 x86_64（取决于 ort 包版本），兜底扫一次
    if [[ ! -d "$fw_dir" ]]; then
      fw_dir="$(find "$IOS_ORT_XCFW" -maxdepth 3 -type d -name "onnxruntime.framework" \
                -path "*simulator*" 2>/dev/null | head -1)"
    fi
    [[ -d "$fw_dir" ]] || die "找不到 $slice 对应的 onnxruntime.framework（查 $IOS_ORT_XCFW）"

    export SHERPA_ONNXRUNTIME_LIB_DIR="$(dirname "$fw_dir")"
    export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$fw_dir/Headers"

    log "配置 [iOS/$plat]（JNI=OFF / C_API=ON，全功能保留）"
    cmake -B "$bdir" -S "$SHERPA_SRC" \
      -DCMAKE_TOOLCHAIN_FILE="$SHERPA_SRC/toolchains/ios.toolchain.cmake" \
      -DPLATFORM="$plat" \
      -DENABLE_BITCODE=0 \
      -DENABLE_ARC=1 \
      -DENABLE_VISIBILITY=0 \
      -DDEPLOYMENT_TARGET=13.0 \
      -DCMAKE_BUILD_TYPE=Release \
      -DBUILD_SHARED_LIBS=OFF \
      -DBUILD_PIPER_PHONMIZE_EXE=OFF \
      -DBUILD_PIPER_PHONMIZE_TESTS=OFF \
      -DBUILD_ESPEAK_NG_EXE=OFF \
      -DBUILD_ESPEAK_NG_TESTS=OFF \
      -DSHERPA_ONNX_ENABLE_PYTHON=OFF \
      -DSHERPA_ONNX_ENABLE_BINARY=OFF \
      -DSHERPA_ONNX_ENABLE_TESTS=OFF \
      -DSHERPA_ONNX_ENABLE_CHECK=OFF \
      -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF \
      -DSHERPA_ONNX_ENABLE_JNI=OFF \
      -DSHERPA_ONNX_ENABLE_C_API=ON \
      -DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF \
      > /dev/null

    log "编译 [iOS/$plat]（$(sysctl -n hw.ncpu 2>/dev/null || echo 4) 并发）"
    cmake --build "$bdir" -j"$(sysctl -n hw.ncpu 2>/dev/null || echo 4)" 2>&1 | tail -3

    # 头文件安装（framework 需要 install/include/.../c-api.h）
    cmake --install "$bdir" > /dev/null 2>&1 || true

    # 合并全部中间静态库 —— 清单取自官方 build-ios.sh，缺一不可
    local -a parts=()
    local name
    for name in libkaldi-native-fbank-core libkissfft-float libsherpa-onnx-c-api \
                libsherpa-onnx-core libsherpa-onnx-fstfar libsherpa-onnx-fst \
                libsherpa-onnx-kaldifst-core libkaldi-decoder-core libucd \
                libpiper_phonemize libespeak-ng libssentencepiece_core; do
      [[ -f "$bdir/lib/$name.a" ]] && parts+=("$bdir/lib/$name.a")
    done
    [[ "${#parts[@]}" -ge 10 ]] || die "[iOS/$plat] 中间静态库不足（${#parts[@]} 个），构建可能未完成"

    log "[iOS/$plat] libtool 合并 ${#parts[@]} 个静态库"
    libtool -static -o "$bdir/libsherpa-onnx-c-api.a" "${parts[@]}"

    # 包成 framework（cinterop 要能解析到模块）
    local out_fw="$bdir/SherpaOnnxC.framework"
    rm -rf "$out_fw"
    mkdir -p "$out_fw/Headers/sherpa-onnx/c-api" "$out_fw/Modules"
    cp "$bdir/libsherpa-onnx-c-api.a" "$out_fw/SherpaOnnxC"
    cp "$header_src" "$out_fw/Headers/sherpa-onnx/c-api/"
    cat > "$out_fw/Modules/module.modulemap" <<'MEOF'
framework module SherpaOnnxC {
  header "sherpa-onnx/c-api/c-api.h"
  export *
}
MEOF
    cat > "$out_fw/Info.plist" <<'PEOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleIdentifier</key><string>com.k2-fsa.sherpa-onnx</string>
  <key>CFBundleName</key><string>SherpaOnnxC</string>
  <key>CFBundlePackageType</key><string>FMWK</string>
  <key>CFBundleExecutable</key><string>SherpaOnnxC</string>
  <key>MinimumOSVersion</key><string>13.0</string>
  <key>CFBundleSupportedPlatforms</key><array><string>iPhoneOS</string></array>
</dict>
</plist>
PEOF
    merged_libs+=("$out_fw")
  done

  # 合成 xcframework
  [[ "${#merged_libs[@]}" -ge 1 ]] || die "没有可用的 framework 切片"
  log "xcodebuild -create-xcframework（${#merged_libs[@]} 个切片）"
  local xcfw="$BUILD_DIR/sherpa-onnx.xcframework"
  rm -rf "$xcfw"
  local -a args=()
  for fw in "${merged_libs[@]}"; do args+=( -framework "$fw" ); done
  xcodebuild -create-xcframework "${args[@]}" -output "$xcfw"

  # 落到项目内 voice/ios/
  mkdir -p "$OUT_DIR"
  rm -rf "$OUT_DIR/sherpa-onnx.xcframework"
  cp -R "$xcfw" "$OUT_DIR/"
  cp "$header_src" "$OUT_DIR/c-api.h"

  report_ios
}

report_ios() {
  log "[ios-arm64] 产物目录：$OUT_DIR"
  local xcfw="$OUT_DIR/sherpa-onnx.xcframework"
  [[ -d "$xcfw" ]] || { warn "xcframework 不存在，跳过体检"; return 0; }

  find "$xcfw" -maxdepth 2 -type d | sed 's|'"$xcfw"'|  .|' | head -12

  local dev_lib="$xcfw/ios-arm64/SherpaOnnxC.framework/SherpaOnnxC"
  if [[ -f "$dev_lib" ]]; then
    printf "    device 静态库：%d B\n" "$(stat -f%z "$dev_lib" 2>/dev/null || stat -c%s "$dev_lib")"
    local capi_cnt
    capi_cnt=$(nm -gU "$dev_lib" 2>/dev/null | grep -c 'SherpaOnnx' || true)
    log "C API 符号：${capi_cnt} 个"
    if nm -gU "$dev_lib" 2>/dev/null | grep -q 'SherpaOnnxCreateOfflineSourceSeparation'; then
      log "含 SherpaOnnxCreateOfflineSourceSeparation —— 功能未裁剪"
    else
      warn "缺少声源分离符号，请检查 c-api.h 是否与库同版本"
    fi
  fi
  log "头文件：$OUT_DIR/c-api.h（cinterop 用）"
}

# ---------------------------------------------------------------- dispatch
case "${1:-all}" in
  deps)  preflight "$1"; prepare_deps ;;
  build) preflight "$1"; do_build_ios ;;
  check) report_ios ;;
  all)   preflight "$1"; prepare_deps; do_build_ios ;;
  *)     die "未知子命令: $1（可用: all | deps | build | check）" ;;
esac
