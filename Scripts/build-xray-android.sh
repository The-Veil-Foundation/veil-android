#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/composeApp/libs/xray.aar"
# Мост живёт в репозитории, чтобы релиз собирался на GitHub без соседних папок.
BRIDGE="${XRAY_BRIDGE:-$ROOT/XrayBridge}"

export ANDROID_HOME="$HOME/Android"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$HOME/Android/ndk/29.0.14206865}"
export GOPATH="$HOME/.local/gopath"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdk}"
export PATH="$JAVA_HOME/bin:$HOME/.local/go126/bin:$GOPATH/bin:$PATH"

if [ ! -x "$HOME/.local/go126/bin/go" ]; then
  echo "==> go 1.26"
  curl -fsSL -o /tmp/opencode/go126.tar.gz https://go.dev/dl/go1.26.10.linux-amd64.tar.gz
  rm -rf "$HOME/.local/go126"; mkdir -p "$HOME/.local/go126"
  tar -xf /tmp/opencode/go126.tar.gz -C "$HOME/.local/go126" --strip-components=1
fi
go version

echo "==> gomobile"
[ -x "$GOPATH/bin/gomobile" ] || go install golang.org/x/mobile/cmd/gomobile@latest
[ -x "$GOPATH/bin/gobind" ] || go install golang.org/x/mobile/cmd/gobind@latest
gomobile init

echo "==> building xray.aar (this is the long part)"
cd "$BRIDGE"
go mod download
gomobile bind \
  -target=android/arm64,android/amd64 \
  -androidapi 24 \
  -o "$OUT" \
  -ldflags="-s -w" \
  -trimpath \
  .
ls -lh "$OUT"
echo "XRAY_AAR_OK"
