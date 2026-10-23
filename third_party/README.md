# third_party

`CLOUDFLARED_VERSION` 釘住要編譯嘅 [cloudflared](https://github.com/cloudflare/cloudflared) tag。cloudflared 係 Cloudflare, Inc. 嘅作品，Apache License 2.0。

`android_dns.go` 只係快速臨時開放伺服器 Android 建置用。`scripts/build-cloudflared-android.sh` 會 clone 上面嗰個 tag（目錄 `cloudflared-src/`，唔入 git），把呢個檔複製去 `cmd/cloudflared/android_dns.go`，再用 `GOOS=android GOARCH=arm64 -buildmode=pie` 編譯。

官方 `cloudflared-linux-arm64` 係 non-PIE 嘅 ET_EXEC，Android 拒絕執行，所以唔直接打包。

Quick Tunnel 只係測試同開發用。
