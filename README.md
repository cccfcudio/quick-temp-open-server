# Quick Temp Open Server

Repository: [`cccfcudio/quick-temp-open-server`](https://github.com/cccfcudio/quick-temp-open-server)

## English

### What it is

**Quick Temp Open Server** is a small **file hub for Android and desktop (JVM)**. You **mount folders you choose**; the app starts a **local HTTP server and WebDAV** endpoint on your network. Other devices on the same LAN can **browse, download, and upload** through a web UI or any WebDAV client.

Optionally, **Try Cloudflare** runs **cloudflared Quick Tunnel** and gives you a **temporary public HTTPS URL** on `trycloudflare.com`—useful for quick tests; the hostname changes every time you start it.

Android `applicationId` remains `app.openshelf`. **v1 only mounts local folders.** S3, remote WebDAV, Aliyun Drive, and OneDrive appear in the UI as placeholders (not implemented yet).

The Android app defaults to **English** (`res/values/`). **Traditional Chinese** (Hong Kong-style copy) is in `values-zh-rHK` and `values-b+zh+Hant`; set the device or per-app language to 繁體中文 to use it.

### What it’s for

- **LAN file sharing** without syncing everything to a cloud drive: mount SAF folders or the app’s private share folder, tap start, open the LAN URL on a PC or tablet.
- **WebDAV workflows**—map the server in a file manager, backup tool, or editor while sharing is on.
- **Short-lived public access** for demos or debugging via Cloudflare Quick Tunnel. **Not** a production hosting solution. When the tunnel is on, a **password is required**; **loopback is authenticated too**, because cloudflared forwards from `127.0.0.1`.

On Android, sharing runs as a **foreground service** (stop from the notification). The same **`:core` JVM module** implements the server; you can run it on a desktop for development with `./gradlew :core:run`.

### Quick highlights

- Mount **any folder you pick** (plus app shortcuts to Camera, Pictures, Downloads)—not a gallery-only or photo-type WebDAV host.
- **One server**: web browsing, uploads/downloads, **WebDAV** (`/dav/`), thumbnails, ZIP/batch download, and a small JSON API.
- Listings support sort, type filter, and pagination; multi-select in the app and in the web UI.
- Auth: HTTP Basic (configurable username), Bearer token, `X-QuickTemp-Token`, or login cookie.

### How this differs from gallery WebDAV or OpenList

Quick Temp Open Server is **not** a temporary gallery WebDAV app and **not** Docker OpenList.

- You mount **folders you choose** (Storage Access Framework) and the app share directory—not a fixed camera roll.
- The same server serves **HTML browsing**, **file transfer**, and **WebDAV**; file types are not limited to photos.
- **Optional public HTTPS** via Quick Tunnel (testing/development only; URL changes each run).

### More detail

Architecture, HTTP routes, build instructions, and cloudflared notes are documented in the **Traditional Chinese** section below (kept in sync with the codebase).

---

## 中文（繁體）

快速臨時開放伺服器。GitHub 倉庫係 [`cccfcudio/quick-temp-open-server`](https://github.com/cccfcudio/quick-temp-open-server)。

呢個係一部手機上嘅簡易檔案中樞。你掛載自己嘅資料夾，手機就開一個本機 HTTP / WebDAV 伺服器；同一網路上嘅其他裝置可以列出、下載、上載。想臨時畀外網用，可以開 Try Cloudflare（cloudflared Quick Tunnel），攞一條 `trycloudflare.com` 嘅 HTTPS 網址。

Android `applicationId` 維持 `app.openshelf`。

v1 只做本機資料夾。S3、遠端 WebDAV、阿里雲盤、OneDrive 只留介面同 TODO，未實作。

## 同相簿專用嘅臨時 WebDAV 有咩分別

Quick Temp Open Server 唔係相簿暫存伺服器，亦唔係 Docker 版 OpenList。

- 掛載對象係你揀嘅任何資料夾（Storage Access Framework），加上應用程式自己嘅分享夾。相簿、圖片、下載只係打開系統選擇器嘅捷徑，唔會預先攞權限。
- 同一部伺服器同時提供網頁瀏覽、檔案下載／上載、WebDAV，同埋一個很小嘅 JSON 介面。唔限於相簿檔案類型。
- 可以選擇開公開 HTTPS 隧道。隧道一開就強制密碼；本機迴環都要認證，因為 cloudflared 係由 `127.0.0.1` 轉發。
- 分享行喺前台服務，通知可以停止。Quick Tunnel 只係測試同開發，每次網址都會變。

## 架構

```
:core   純 JVM。檔案庫、HTTP/1.1、WebDAV、密碼、JSON、隧道指令同日誌解析。單元測試喺呢度。
:app    Android。Compose 介面、SAF、前台服務、cloudflared 行程。
```

主要路徑：

1. 揀資料夾，或者一撳掛上應用程式分享夾（`Android/data/.../files/share`）。
2. `ShareService` 啟動 `ShareHub`，綁定 `ShareHttpServer`（預設連接埠 `28765`，聽 `0.0.0.0`）。
3. 其他裝置用 `http://<手機IP>:28765/` 瀏覽，或者用 `/dav/` 做 WebDAV。檔案清單可以按名稱、大小、修改時間排序（順序／倒序會記住），亦可以按類型同名稱篩選。每頁 20、50 或 100 項，頁面大小會記住；頁碼跟住資料夾走。長按進入多選，可以全選呢一頁、清除、確認後刪除、複製或分享路徑，將所選檔案下載到系統下載，以及將所選打包成 ZIP。網頁版用剔選框同連結做同樣嘅動作，版面係檔案列（可切換縮圖格），排序、類型同每頁係一組篩選按鈕，下載同打包放喺清單上方。頁面有一段小腳本，撳下載或打包會即時顯示進度；表單本身仍然可以喺冇腳本時提交。排序、類型同每頁數量記喺 `openshelf_list` cookie（`sort.order.kind.pageSize.query`）。舊版四段 cookie 仍然讀得到。

ZIP 同下載路由（要登入，同其他路徑一樣，本機迴環都唔豁免）：

- `GET /zip/{mountId}` 同 `GET /zip/{mountId}/{相對路徑}`：將該資料夾（包含子資料夾）打包成一個 ZIP。
- `POST /zip`：表單欄位同 `/batch`，`mount`、`back`、重複嘅 `path`。只打包目前資料夾入面剔選嘅項目。
- `POST /download`：同一個表單。一個檔案會 303 去 `/raw/...?download=1`；多個檔案回一個頁面，每條連結下載一個檔案。只選資料夾時會說明要用「下載成個資料夾」。
- 單檔 `GET /raw/...?download=1` 以實際讀到嘅位元組做 `Content-Length`（64 MiB 以內），並用 `application/octet-stream` 附件下載。空檔會回「未能完成」頁，寫明檔案係空嘅。

瀏覽查詢可以加 `ps=20|50|100` 同 `page=N`。申報未壓縮大小喺 256 MiB 以內，或者大小未知嘅 ZIP，會先寫入暫存檔，再用確實嘅 `Content-Length` 送出，等手機瀏覽器開始下載；再大就用 `Transfer-Encoding: chunked`。上限 4000 個項目、未壓縮 4 GiB。清單上大於上限嘅大小會當成未知，一個錯誤長度唔會擋住成個打包；真正讀到超過上限先回 413。瀏覽器要求 HTML 時，錯誤會顯示「未能完成」頁。App 入面下載同打包都行喺前台服務，一開始就有進度；Android 10 或以上存入系統下載（個別檔案放 `Download/Quick Temp Open Server`），較舊版本存入應用程式下載目錄。資料夾同常見副檔名（圖片、音訊、影片、PDF、壓縮檔、文字、程式碼等）喺兩個瀏覽器都有圖示。圖片會顯示縮圖，影片會抽一幀並加播放標記；其他檔案維持圖示。

- `GET /thumb/{mountId}/{路徑}?edge=96`：要登入。有密碼時，清單把會話權杖放喺 `t=`，等 `<img>` 唔使再送一次 Basic（透過 `user:pass@` 或者 Cloudflare 打開頁面時，瀏覽器通常唔會把 Authorization 帶去圖片）。回一張最長邊受限制的 JPEG（48–512，預設 160）。手機用取樣解碼同記憶體快取，唔會把原圖放進每一格。抽唔到畫面時格子會留低原本嘅圖示。
4. 可選：`CloudflaredRunner` 執行 `libcloudflared.so`，對住 `http://127.0.0.1:<port>`。畫面上可以複製公開網址；二維碼唔包含密碼。

認證：HTTP Basic（用戶名可喺 App 設定，預設 `quick`；要同密碼一齊啱）、`Authorization: Bearer`、`X-QuickTemp-Token`，或者登入頁發出嘅 cookie。密碼用 SHA-256 再做常數時間比較。Try Cloudflare 隧道會每分鐘檢查公開網址是否仍然可達。

`http://127.0.0.1` 唔會豁免密碼。公開隧道嘅連線都係由本機轉入。

## 建置 debug APK

需要 JDK 17 或以上、Android SDK Platform 35、Build-Tools 35.0.0。`minSdk` 26，只打包 `arm64-v8a`。

```bash
export ANDROID_HOME="$HOME/android-sdk"   # 或者寫入 local.properties 嘅 sdk.dir
./gradlew :app:assembleDebug
```

Windows：

```bat
gradlew.bat :app:assembleDebug
```

輸出：`app/build/outputs/apk/debug/quick-temp-open-server-0.1.8-debug.apk`。可以再複製去 `artifacts/`（呢個目錄入面嘅 APK 唔入 git）。

單元測試同本機試伺服器唔使 Android SDK：

```bash
./gradlew :core:test
./gradlew :core:run --args="/path/to/folder 28765 your-password"
```

### cloudflared

APK 要包含可執行嘅 cloudflared，先跑：

```bash
scripts/build-cloudflared-android.sh
```

需要 Go 1.27 或以上（同 `third_party/CLOUDFLARED_VERSION` 嘅 `go.mod` 一致）。腳本會 clone 嗰個 tag，套上 `third_party/android_dns.go`，再以 `CGO_ENABLED=0 GOOS=android GOARCH=arm64 -buildmode=pie` 編譯到：

`app/src/main/jniLibs/arm64-v8a/libcloudflared.so`

呢個 `.so` 同 `app/src/main/assets/cloudflared-build.txt` 都唔入 git。未編譯都仍然可以 `assembleDebug`，只係 App 入面會話搵唔到 cloudflared。

官方 `cloudflared-linux-arm64` 係靜態連結嘅 non-PIE `ET_EXEC`，Android 唔會執行，所以唔好直接放進套件。

Quick Tunnel 指令係 `tunnel --no-autoupdate --protocol http2 --edge-ip-version 4 --url http://127.0.0.1:<port>`。上游喺未指定協定時會用 QUIC；手機上用 HTTP/2 較穩。`--dns-resolver-addrs` 只存在於具名隧道嘅 `tunnel run`，Quick Tunnel 用唔到，所以 DNS 改喺編譯進去嘅解析器。

## 授權

Quick Temp Open Server 本身係 Apache License 2.0，見 `LICENSE` 同 `NOTICE`。

cloudflared 係 Cloudflare, Inc. 嘅作品，同樣係 Apache 2.0。重新散佈時保留授權全文（`app/src/main/assets/licenses/Apache-2.0.txt`）同聲明（`NOTICE`、`app/src/main/assets/licenses/cloudflared-NOTICE.txt`）。Android 二進位係由 Quick Temp Open Server 按上面嘅方式重編譯，並加咗只用於呢個建置嘅 DNS 檔，唔係 upstream 發行檔。

Try Cloudflare Quick Tunnel 只係測試同開發。唔好當成正式公開服務。
