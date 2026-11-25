#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to an installed Android NDK (r28c or newer)}"
export ANDROID_HOME ANDROID_NDK_HOME
export GOARM=7

export TMPDIR="${TMPDIR:-$ROOT/build/core-tmp}"
mkdir -p "$TMPDIR"

TOOLS="$ROOT/build/core-tools"
mkdir -p "$TOOLS" "$ROOT/composeApp/libs"
export PATH="$TOOLS:$PATH"
GOBIN="$TOOLS" go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.13
GOBIN="$TOOLS" go install github.com/sagernet/gomobile/cmd/gobind@v0.1.13

cd "$ROOT/CoreBridge"
go mod download
gomobile init

gomobile bind \
  -target="${VEIL_CORE_TARGETS:-android/arm,android/arm64,android/amd64}" \
  -androidapi 24 \
  -o "$ROOT/composeApp/libs/veil-core.aar" \
  -tags=http2legacy,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,with_conntrack,with_tailscale \
  -ldflags="-s -w -checklinkname=0 -X github.com/sagernet/sing-box/constant.Version=1.12.9" \
  -trimpath \
  github.com/faustyu1/veil/ios/xraybridge \
  github.com/sagernet/sing-box/experimental/libbox
