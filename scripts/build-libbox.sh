#!/usr/bin/env bash
# Builds a slim libbox.aar (sing-box core) for the app.
#
# Only the features the app actually uses are compiled in:
#   with_quic   -> Hysteria2
#   with_utls   -> VLESS + REALITY (uTLS fingerprints)
#   with_gvisor -> optional gVisor/mixed TUN stack
# Everything else (Tailscale, WireGuard, OpenVPN, naive, clash API...) is left out,
# which makes the native library smaller, lowers RAM usage and the number of
# background goroutines -> less CPU wake-ups -> less battery drain.
#
# Requirements: Go (toolchain is auto-downloaded), Android SDK + NDK (ANDROID_HOME / ANDROID_NDK_HOME), JDK 17+.
#
# Usage: scripts/build-libbox.sh [android|android/arm64,android/arm]
set -euo pipefail

SING_BOX_VERSION="${SING_BOX_VERSION:-v1.14.4}"
GOMOBILE_VERSION="${GOMOBILE_VERSION:-v0.1.13}"
TARGET="${1:-android/arm64,android/arm,android/amd64}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK_DIR:-$ROOT/build/libbox-src}"
OUT="$ROOT/app/libs"

: "${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)"
fi
: "${ANDROID_NDK_HOME:?Android NDK not found (install ndk;28.0.13004108)}"
export ANDROID_HOME ANDROID_NDK_HOME
export GOTOOLCHAIN="${GOTOOLCHAIN:-auto}"

if [[ ! -d "$WORK/.git" ]]; then
  rm -rf "$WORK"
  git clone --depth 1 --branch "$SING_BOX_VERSION" https://github.com/SagerNet/sing-box "$WORK"
fi

cd "$WORK"
GOBIN="$(go env GOPATH)/bin"
go install "github.com/sagernet/gomobile/cmd/gomobile@$GOMOBILE_VERSION"
go install "github.com/sagernet/gomobile/cmd/gobind@$GOMOBILE_VERSION"
export PATH="$GOBIN:$PATH"

TAGS="with_gvisor,with_quic,with_utls,badlinkname,tfogo_checklinkname0"
LDFLAGS="-X github.com/sagernet/sing-box/constant.Version=${SING_BOX_VERSION#v} -X runtime.godebugDefault=multipathtcp=0 -checklinkname=0 -s -w -buildid="

mkdir -p "$OUT"
gomobile bind -v \
  -o "$OUT/libbox.aar" \
  -target "$TARGET" \
  -androidapi 26 \
  -javapkg=io.nekohasekai \
  -libname=box \
  -trimpath -buildvcs=false \
  -ldflags "$LDFLAGS" \
  -tags "$TAGS" \
  ./experimental/libbox

rm -f "$OUT/libbox-sources.jar"
echo "libbox.aar -> $OUT/libbox.aar"
