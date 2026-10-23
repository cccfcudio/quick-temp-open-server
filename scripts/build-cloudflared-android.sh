#!/usr/bin/env bash
# Rebuild Cloudflare's cloudflared (Apache-2.0) as an Android arm64 PIE binary.
# The official linux-arm64 release is a non-PIE ET_EXEC, which Android refuses
# to run. This script produces app/src/main/jniLibs/arm64-v8a/libcloudflared.so.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="$(tr -d '[:space:]' < "$ROOT/third_party/CLOUDFLARED_VERSION")"
SRC="${CLOUDFLARED_SRC:-$ROOT/third_party/cloudflared-src}"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a/libcloudflared.so"
PATCH="$ROOT/third_party/android_dns.go"

if [[ -x "$HOME/.local/go/bin/go" ]]; then
  GO="$HOME/.local/go/bin/go"
elif command -v go >/dev/null 2>&1; then
  GO="$(command -v go)"
else
  echo "需要 Go 1.27 或更新。可從 https://go.dev/dl/ 安裝。" >&2
  exit 1
fi

GO_VERSION="$("$GO" env GOVERSION || true)"
echo "使用 $GO ($GO_VERSION)，目標 cloudflared $VERSION"

if [[ ! -d "$SRC/.git" ]]; then
  git clone --depth 1 --branch "$VERSION" https://github.com/cloudflare/cloudflared.git "$SRC"
fi

cp "$PATCH" "$SRC/cmd/cloudflared/android_dns.go"
mkdir -p "$(dirname "$OUT")"

export CGO_ENABLED=0
export GOOS=android
export GOARCH=arm64
export GOTOOLCHAIN=local
export GOMAXPROCS="${GOMAXPROCS:-2}"
export GOMEMLIMIT="${GOMEMLIMIT:-2800MiB}"

(
  cd "$SRC"
  "$GO" build -trimpath -buildmode=pie \
    -ldflags "-s -w -X main.Version=${VERSION} -X main.BuildTime=quick-temp-open-server" \
    -o "$OUT" ./cmd/cloudflared
)

if ! readelf -h "$OUT" | grep -q "Type:[[:space:]]*DYN"; then
  echo "編譯結果唔係 PIE（ET_DYN）。Android 無法執行非 PIE 二進位。" >&2
  exit 1
fi

SHA="$(sha256sum "$OUT" | awk '{print $1}')"
mkdir -p "$ROOT/app/src/main/assets"
printf '%s\n%s\n' "$VERSION" "$SHA" > "$ROOT/app/src/main/assets/cloudflared-build.txt"
echo "已輸出 $OUT"
echo "sha256 $SHA"
file "$OUT" || true
