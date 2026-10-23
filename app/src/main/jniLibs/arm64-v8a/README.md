`libcloudflared.so` 由 `scripts/build-cloudflared-android.sh` 產生，而且被 git 忽略。

檔名要以 `lib` 開頭、放喺 `jniLibs`，先會被解壓到 `nativeLibraryDir`，Android 先可以執行。呢個檔係 arm64 PIE（`ET_DYN`，interpreter `/system/bin/linker64`），唔係官方 linux-arm64 發行檔。
