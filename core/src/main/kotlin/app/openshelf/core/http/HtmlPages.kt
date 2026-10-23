package app.openshelf.core.http

import app.openshelf.core.browse.FileIcon
import app.openshelf.core.browse.FileIcons
import app.openshelf.core.browse.FileKind
import app.openshelf.core.browse.Listing
import app.openshelf.core.browse.ListingOptions
import app.openshelf.core.browse.ListingWindow
import app.openshelf.core.browse.SortDirection
import app.openshelf.core.browse.SortKey
import app.openshelf.core.model.FileEntry
import app.openshelf.core.model.ShareDefaults
import app.openshelf.core.store.FileStore
import app.openshelf.core.store.Paths
import app.openshelf.core.text.TextFormat

internal const val HTML: String = "text/html; charset=utf-8"
internal const val TEXT: String = "text/plain; charset=utf-8"
internal const val JSON: String = "application/json; charset=utf-8"

private val CSS = """
:root {
  color-scheme: light;
  --bg: #f3f5f8;
  --surface: #ffffff;
  --surface-2: #f7f8fb;
  --ink: #1c2128;
  --muted: #5c6778;
  --line: #e4e8ef;
  --accent: #1d4f91;
  --accent-ink: #ffffff;
  --accent-soft: #e7f0fb;
  --danger: #9f2d2d;
  --danger-soft: #fdecec;
  --shadow: 0 1px 2px rgba(22, 28, 40, 0.04), 0 10px 28px rgba(22, 28, 40, 0.05);
  --radius: 16px;
  --top: 56px;
}
:root[data-theme="dark"] {
  color-scheme: dark;
  --bg: #101318;
  --surface: #181c23;
  --surface-2: #20262f;
  --ink: #e8edf4;
  --muted: #a3adbb;
  --line: #2d3542;
  --accent: #8eb4e8;
  --accent-ink: #0e1724;
  --accent-soft: #243246;
  --danger: #ffb4ae;
  --danger-soft: #3c2426;
  --shadow: 0 1px 2px rgba(0, 0, 0, 0.35), 0 12px 32px rgba(0, 0, 0, 0.28);
}
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    color-scheme: dark;
    --bg: #101318;
    --surface: #181c23;
    --surface-2: #20262f;
    --ink: #e8edf4;
    --muted: #a3adbb;
    --line: #2d3542;
    --accent: #8eb4e8;
    --accent-ink: #0e1724;
    --accent-soft: #243246;
    --danger: #ffb4ae;
    --danger-soft: #3c2426;
    --shadow: 0 1px 2px rgba(0, 0, 0, 0.35), 0 12px 32px rgba(0, 0, 0, 0.28);
  }
}
* { box-sizing: border-box; }
html { font-size: 16px; }
body {
  margin: 0;
  min-height: 100vh;
  font-family: "Segoe UI", "PingFang TC", "Noto Sans CJK TC", "Microsoft JhengHei", system-ui, sans-serif;
  background: var(--bg);
  color: var(--ink);
  line-height: 1.45;
}
main { max-width: 68rem; margin: 0 auto; padding: 1rem 1rem 2.75rem; }
h1 { font-size: 1.35rem; line-height: 1.3; margin: 0 0 0.35rem; letter-spacing: -0.01em; }
h2 { font-size: 1rem; margin: 0 0 0.65rem; }
p { margin: 0.4rem 0; }
p.note, .muted { color: var(--muted); }
a { color: var(--accent); }
code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.9em;
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 0.05rem 0.35rem;
}
button, .btn, .tools a {
  font: inherit;
  border-radius: 10px;
  padding: 0.48rem 0.85rem;
  text-decoration: none;
  display: inline-block;
  cursor: pointer;
  line-height: 1.2;
  border: 1px solid transparent;
}
button, .btn { background: var(--accent); color: var(--accent-ink); }
button.ghost, a.ghost, .btn.ghost, .tools a {
  background: var(--surface);
  color: var(--ink);
  border-color: var(--line);
}
button.danger { background: var(--danger-soft); color: var(--danger); }
button:hover, .btn:hover { filter: brightness(1.05); }
.tools a:hover, button.ghost:hover, a.ghost:hover { border-color: var(--accent); color: var(--accent); }
.tools a.on { background: var(--accent); color: var(--accent-ink); border-color: var(--accent); }
#progress {
  position: sticky;
  top: var(--top);
  z-index: 19;
  margin: 0;
  background: var(--accent);
  color: var(--accent-ink);
  padding: 0.7rem 1.15rem;
  font-weight: 700;
}
#progress[hidden] { display: none; }
input, button { font: inherit; }
input[type=text], input[type=password], input[type=search], textarea {
  width: 100%;
  padding: 0.62rem 0.75rem;
  border-radius: 10px;
  border: 1px solid var(--line);
  background: var(--surface);
  color: inherit;
  font-size: 1rem;
}
textarea { min-height: 10rem; resize: vertical; }
input[type=checkbox] { width: 1.05rem; height: 1.05rem; accent-color: var(--accent); }
.topbar {
  position: sticky;
  top: 0;
  z-index: 20;
  height: var(--top);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  padding: 0 1rem;
  background: var(--surface);
  border-bottom: 1px solid var(--line);
}
.brand {
  display: inline-flex;
  align-items: center;
  gap: 0.55rem;
  min-width: 0;
  color: inherit;
  text-decoration: none;
  font-weight: 700;
  font-size: 1rem;
}
.brand-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mark {
  width: 1.7rem;
  height: 1.7rem;
  border-radius: 8px;
  display: inline-grid;
  place-items: center;
  background: var(--accent);
  color: var(--accent-ink);
  font-size: 0.85rem;
  font-weight: 700;
}
.panel, .card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow);
}
.panel { padding: 0.95rem 0.95rem 1.05rem; margin: 0 0 1rem; }
.card { padding: 1.15rem 1.2rem 1.25rem; }
.gate { display: grid; place-items: center; min-height: calc(100vh - 9rem); }
.gate .card { width: min(100%, 24.5rem); }
.crumbs {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.3rem 0.35rem;
  margin: 0 0.15rem 0.75rem;
  font-size: 0.92rem;
}
.crumbs a { text-decoration: none; }
.crumbs a:hover { text-decoration: underline; }
.sep { color: var(--muted); }
.toolbar { display: flex; flex-direction: column; gap: 0.55rem; margin: 0 0.15rem 0.75rem; }
.field { display: flex; flex-wrap: wrap; align-items: center; gap: 0.4rem 0.55rem; }
.label { font-size: 0.78rem; color: var(--muted); min-width: 2.4rem; }
.tools { display: flex; flex-wrap: wrap; gap: 0.35rem; align-items: center; margin: 0; }
.tools a { padding: 0.32rem 0.72rem; border-radius: 999px; font-size: 0.9rem; }
.search { display: flex; gap: 0.5rem; align-items: center; margin: 0.15rem 0 0.35rem; }
.search input { flex: 1; }
.summary-row { display: flex; justify-content: space-between; align-items: flex-start; gap: 0.75rem; }
.summary-row .note { margin: 0.2rem 0; flex: 1; }
.actions {
  position: sticky;
  top: var(--top);
  z-index: 8;
  display: flex;
  flex-wrap: wrap;
  gap: 0.45rem;
  padding: 0.15rem 0.15rem 0.75rem;
  background: var(--surface);
}
.files { list-style: none; margin: 0; padding: 0; }
.file {
  display: grid;
  grid-template-columns: 1.75rem minmax(0, 1fr) 5.8rem 9.4rem auto;
  gap: 0.35rem 0.8rem;
  align-items: center;
  padding: 0.45rem 0.55rem;
  border-top: 1px solid var(--line);
}
.file.head {
  color: var(--muted);
  font-size: 0.78rem;
  font-weight: 600;
  padding-top: 0.2rem;
  padding-bottom: 0.35rem;
}
.file:hover { background: var(--surface-2); }
.file.head:hover { background: transparent; }
.file-link {
  display: block;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  color: inherit;
  text-decoration: none;
  font-weight: 600;
}
.file-link:hover { color: var(--accent); }
.meta { display: contents; }
.meta span, .when {
  color: var(--muted);
  font-size: 0.9rem;
  font-variant-numeric: tabular-nums;
  font-weight: 400;
}
.act { justify-self: end; }
.act .btn { white-space: nowrap; }
.pick { display: inline-flex; align-items: center; justify-content: center; }
.ico { width: 1.15rem; height: 1.15rem; vertical-align: -0.2rem; margin-right: 0.35rem; flex: 0 0 auto; }
.ico[data-kind="folder"] { color: #c47d09; }
.ico[data-kind="image"] { color: #0f8a62; }
.ico[data-kind="video"] { color: #1d4f91; }
.ico[data-kind="audio"] { color: #0e7490; }
.ico[data-kind="pdf"] { color: #c24141; }
.ico[data-kind="archive"] { color: #b45309; }
.ico[data-kind="text"] { color: #4b5568; }
.ico[data-kind="code"] { color: #0f766e; }
.ico[data-kind="sheet"] { color: #15803d; }
.ico[data-kind="slides"] { color: #c2410c; }
.ico[data-kind="other"] { color: #6b7280; }
:root[data-theme="dark"] .ico[data-kind="folder"] { color: #f0b429; }
:root[data-theme="dark"] .ico[data-kind="image"] { color: #3dbe8c; }
:root[data-theme="dark"] .ico[data-kind="video"] { color: #8eb4e8; }
:root[data-theme="dark"] .ico[data-kind="audio"] { color: #67d4e8; }
:root[data-theme="dark"] .ico[data-kind="pdf"] { color: #f0a0a0; }
:root[data-theme="dark"] .ico[data-kind="archive"] { color: #f0b429; }
:root[data-theme="dark"] .ico[data-kind="text"] { color: #c5ced8; }
:root[data-theme="dark"] .ico[data-kind="code"] { color: #5eead4; }
:root[data-theme="dark"] .ico[data-kind="sheet"] { color: #86efac; }
:root[data-theme="dark"] .ico[data-kind="slides"] { color: #fdba74; }
:root[data-theme="dark"] .ico[data-kind="other"] { color: #c5ced8; }
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) .ico[data-kind="folder"] { color: #f0b429; }
  :root:not([data-theme="light"]) .ico[data-kind="image"] { color: #3dbe8c; }
  :root:not([data-theme="light"]) .ico[data-kind="video"] { color: #8eb4e8; }
  :root:not([data-theme="light"]) .ico[data-kind="audio"] { color: #67d4e8; }
  :root:not([data-theme="light"]) .ico[data-kind="pdf"] { color: #f0a0a0; }
  :root:not([data-theme="light"]) .ico[data-kind="archive"] { color: #f0b429; }
  :root:not([data-theme="light"]) .ico[data-kind="text"] { color: #c5ced8; }
  :root:not([data-theme="light"]) .ico[data-kind="code"] { color: #5eead4; }
  :root:not([data-theme="light"]) .ico[data-kind="sheet"] { color: #86efac; }
  :root:not([data-theme="light"]) .ico[data-kind="slides"] { color: #fdba74; }
  :root:not([data-theme="light"]) .ico[data-kind="other"] { color: #c5ced8; }
}
.thumb {
  position: relative;
  display: inline-flex;
  vertical-align: middle;
  width: 72px;
  height: 72px;
  flex: 0 0 72px;
  margin-right: 0.75rem;
  border-radius: 10px;
  overflow: hidden;
  background: var(--surface-2);
  border: 1px solid var(--line);
  align-items: center;
  justify-content: center;
}
.thumb .ico { width: 1.6rem; height: 1.6rem; margin: 0; }
.thumb img {
  position: absolute;
  z-index: 1;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  background: var(--surface-2);
}
.thumb img[hidden] { display: none; }
.thumb .badge {
  position: absolute;
  z-index: 2;
  left: 6px;
  bottom: 5px;
  font-size: 0.68rem;
  line-height: 1;
  color: #fff;
  background: rgba(12, 16, 22, 0.62);
  border-radius: 999px;
  padding: 0.16rem 0.32rem;
}
.empty { padding: 2.4rem 1rem; text-align: center; color: var(--muted); }
.alert {
  background: var(--danger-soft);
  color: var(--danger);
  border-radius: 10px;
  padding: 0.55rem 0.75rem;
}
.mounts { list-style: none; padding: 0; margin: 0.6rem 0 0; display: grid; gap: 0.55rem; }
.mounts a {
  display: flex;
  align-items: center;
  gap: 0.7rem;
  padding: 0.85rem 0.95rem;
  border: 1px solid var(--line);
  border-radius: 12px;
  text-decoration: none;
  color: inherit;
  background: var(--surface-2);
  font-weight: 600;
}
.mounts a:hover { border-color: var(--accent); background: var(--accent-soft); }
.links { list-style: none; padding: 0; margin: 0.6rem 0 0.4rem; display: flex; flex-direction: column; gap: 0.45rem; }
.plain { margin: 0.4rem 0 0.8rem; padding-left: 1.15rem; }
.split { display: grid; grid-template-columns: 1fr; gap: 1.15rem; }
.row { display: flex; gap: 0.5rem; align-items: center; }
.grow { flex: 1; }
.files.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(148px, 1fr));
  gap: 0.7rem;
  padding-top: 0.15rem;
}
.files.grid .file {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  position: relative;
  gap: 0.35rem;
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 0.6rem;
  min-width: 0;
}
.files.grid .file.head { display: none; }
.files.grid .file-link {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  white-space: normal;
  overflow: visible;
  text-overflow: clip;
  overflow-wrap: anywhere;
  font-weight: 600;
}
.files.grid .thumb { width: 100%; height: auto; aspect-ratio: 1; flex: none; margin: 0 0 0.45rem; }
.files.grid .meta { display: flex; flex-wrap: wrap; gap: 0.35rem 0.65rem; }
.files.grid .act { justify-self: stretch; }
.files.grid .act .btn { display: block; text-align: center; }
.files.grid .pick {
  position: absolute;
  z-index: 3;
  top: 0.7rem;
  left: 0.7rem;
  background: var(--surface);
  border-radius: 8px;
  padding: 0.15rem;
  box-shadow: var(--shadow);
}
@media (min-width: 880px) {
  .toolbar { flex-direction: row; flex-wrap: wrap; align-items: center; }
}
@media (min-width: 800px) {
  .split { grid-template-columns: 1fr 1fr; gap: 1.5rem; }
}
@media (max-width: 720px) {
  main { padding: 0.75rem 0.75rem 2.5rem; }
  .file {
    grid-template-columns: 1.6rem minmax(0, 1fr) auto;
    grid-template-areas: "pick link act" "pick meta act";
    row-gap: 0.1rem;
  }
  .file.head { display: none; }
  .pick { grid-area: pick; }
  .file-link { grid-area: link; }
  .meta { grid-area: meta; display: flex; gap: 0.7rem; }
  .act { grid-area: act; align-self: center; }
  .thumb { width: 56px; height: 56px; flex-basis: 56px; margin-right: 0.6rem; }
  .actions { top: var(--top); }
  .summary-row { flex-direction: column; }
}
:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
""".trimIndent()

internal fun loginPage(next: String?, failed: Boolean = false): String {
    val destination = esc(safeNext(next))
    val error = if (failed) "<p class=\"alert\" role=\"alert\">密碼唔啱。</p>" else ""
    return page(
        "登入",
        """
        <div class="gate">
          <div class="card">
            <p><span class="mark" aria-hidden="true">快</span></p>
            <h1>快速臨時開放伺服器</h1>
            <p class="note">輸入分享密碼。用戶名可以填 ${esc(ShareDefaults.USERNAME)}。</p>
            $error
            <form method="post" action="/login">
              <input type="hidden" name="next" value="$destination">
              <p><label class="label" for="password">密碼</label></p>
              <p><input id="password" type="password" name="password" autocomplete="current-password" required></p>
              <p><button type="submit">登入</button></p>
            </form>
          </div>
        </div>
        """.trimIndent(),
    )
}

internal fun homePage(store: FileStore): String {
    val mounts = store.mounts()
    val rows = if (mounts.isEmpty()) {
        "<p class=\"empty\">未有掛載任何資料夾。</p>"
    } else {
        buildString {
            append("<ul class=\"mounts\">")
            for (mount in mounts) {
                val href = Paths.joinUrl("/browse", mount.id, "")
                append("<li><a href=\"")
                append(esc(href))
                append("\">")
                append(iconSvg(FileIcon.FOLDER))
                append(esc(mount.displayName))
                append("</a></li>")
            }
            append("</ul>")
        }
    }
    return page(
        "快速臨時開放伺服器",
        """
        <section class="panel">
          <h1>快速臨時開放伺服器</h1>
          <p class="note">已掛載嘅本機資料夾。WebDAV 根目錄係 <code>/dav/</code>。</p>
          $rows
        </section>
        """.trimIndent(),
    )
}

internal fun browsePage(
    mountName: String,
    mountId: String,
    relative: String,
    entries: List<FileEntry>,
    options: ListingOptions,
    pickAll: Boolean,
    page: Int = 1,
    previewToken: String? = null,
): String {
    val arranged = Listing.arrange(entries, options)
    val window = Listing.window(arranged, page, options.pageSize)
    val crumbs = breadcrumb(mountName, mountId, relative, options)
    val tools = listingTools(mountId, relative, options, window, pickAll)
    val rows = listingTable(mountId, relative, options, window, pickAll, previewToken)
    val upload = uploadForms(mountId, relative)
    val heading = if (relative.isEmpty()) mountName else relative.substringAfterLast('/')
    return page(
        mountName,
        """
        <section class="panel">
          $crumbs
          <h1>${esc(heading)}</h1>
          $tools
          $rows
        </section>
        <section class="panel">
          $upload
        </section>
        """.trimIndent(),
    )
}

internal fun confirmDeletePage(
    mountId: String,
    directory: String,
    paths: List<String>,
    options: ListingOptions,
    page: Int = 1,
): String {
    val back = listingHref("/browse", mountId, directory, options, pickAll = false, page = page)
    val items = buildString {
        append("<ul class=\"plain\">")
        for (path in paths) {
            append("<li>").append(esc(path)).append("</li>")
        }
        append("</ul>")
    }
    val hidden = hiddenListing(mountId, directory, options, page) + hiddenPaths(paths) +
        "<input type=\"hidden\" name=\"confirm\" value=\"1\">"
    return page(
        "確認刪除",
        """
        <div class="gate">
          <div class="card">
            <h1>刪除 ${paths.size} 個？</h1>
            <p class="note">會刪除下面嘅項目。資料夾會連入面嘅檔案一齊刪，冇得還原。</p>
            $items
            <form method="post" action="/batch">
              $hidden
              <p class="row">
                <button type="submit" name="action" value="delete">確認刪除</button>
                <a class="btn ghost" href="${esc(back)}">取消</a>
              </p>
            </form>
          </div>
        </div>
        """.trimIndent(),
    )
}

internal fun errorPage(message: String): String {
    return page(
        "未能完成",
        """
        <div class="gate">
          <div class="card">
            <h1>未能完成</h1>
            <p>${esc(message)}</p>
            <p><a class="btn" href="/">返回</a></p>
          </div>
        </div>
        """.trimIndent(),
    )
}

internal fun downloadReadyPage(
    mountId: String,
    files: List<String>,
    folders: List<String>,
    backHref: String,
): String {
    val links = buildString {
        append("<ul class=\"links\">")
        for (path in files) {
            val href = Paths.joinUrl("/raw", mountId, path) + "?download=1"
            append("<li><a class=\"btn\" data-progress=\"開始下載…\" href=\"")
            append(esc(href))
            append("\">下載 ")
            append(esc(path.substringAfterLast('/')))
            append("</a></li>")
        }
        append("</ul>")
    }
    val skipped = if (folders.isEmpty()) {
        ""
    } else {
        "<p class=\"note\">已略過 ${folders.size} 個資料夾。資料夾請用「下載成個資料夾」或「打包 ZIP」。</p>"
    }
    return page(
        "下載所選",
        """
        <section class="card">
          <h1>下載 ${files.size} 個檔案</h1>
          <p class="note">撳下面每一條連結就會開始下載。手機瀏覽器有時只允許一次一個。</p>
          $skipped
          $links
          <p><a class="btn ghost" href="${esc(backHref)}">返回</a></p>
        </section>
        """.trimIndent(),
    )
}

internal fun pathsPage(paths: List<String>, backHref: String): String {
    val text = esc(paths.joinToString("\n"))
    return page(
        "路徑",
        """
        <section class="card">
          <h1>已選 ${paths.size} 個路徑</h1>
          <p class="note">長按或者用全選就可以複製。</p>
          <textarea readonly>$text</textarea>
          <p><a class="btn ghost" href="${esc(backHref)}">返回</a></p>
        </section>
        """.trimIndent(),
    )
}

internal fun safeNext(raw: String?): String {
    if (raw == null || !raw.startsWith("/") || raw.startsWith("//") || raw.contains('\\') || raw.contains('\r') || raw.contains('\n')) {
        return "/"
    }
    return raw
}

internal fun esc(value: String): String = buildString(value.length) {
    for (ch in value) {
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }
}

private fun breadcrumb(mountName: String, mountId: String, relative: String, options: ListingOptions): String {
    val out = StringBuilder()
    out.append("<nav class=\"crumbs\" aria-label=\"路徑\"><a href=\"/\">全部</a>")
    out.append("<span class=\"sep\">/</span>")
    out.append("<a href=\"").append(esc(listingHref("/browse", mountId, "", options, false))).append("\">")
    out.append(esc(mountName)).append("</a>")
    if (relative.isNotEmpty()) {
        val parts = relative.split('/')
        val walked = ArrayList<String>()
        for (part in parts) {
            walked.add(part)
            out.append("<span class=\"sep\">/</span><a href=\"")
            out.append(esc(listingHref("/browse", mountId, walked.joinToString("/"), options, false)))
            out.append("\">").append(esc(part)).append("</a>")
        }
    }
    out.append("</nav>")
    return out.toString()
}

private fun listingTools(
    mountId: String,
    relative: String,
    options: ListingOptions,
    window: ListingWindow<FileEntry>,
    pickAll: Boolean,
): String {
    val sorts = listOf(SortKey.NAME to "名稱", SortKey.SIZE to "大小", SortKey.MODIFIED to "修改時間")
    val kinds = listOf(
        null to "全部",
        FileKind.IMAGE to "圖片",
        FileKind.VIDEO to "影片",
        FileKind.DOCUMENT to "文件",
        FileKind.OTHER to "其他",
    )
    val sortLinks = buildString {
        append("<div class=\"field\"><span class=\"label\">排序</span><div class=\"tools\">")
        for ((key, label) in sorts) {
            val next = options.copy(sort = key)
            val on = if (options.sort == key) " class=\"on\"" else ""
            append("<a").append(on).append(" href=\"")
            append(esc(listingHref("/browse", mountId, relative, next, pickAll)))
            append("\">").append(label).append("</a>")
        }
        val flipped = options.copy(
            direction = if (options.direction == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC,
        )
        val orderLabel = if (options.direction == SortDirection.ASC) "順序" else "倒序"
        append("<a class=\"on\" href=\"")
        append(esc(listingHref("/browse", mountId, relative, flipped, pickAll)))
        append("\">").append(orderLabel).append("</a>")
        append("</div></div><div class=\"field\"><span class=\"label\">類型</span><div class=\"tools\">")
        for ((kind, label) in kinds) {
            val next = options.copy(kind = kind)
            val on = if (options.kind == kind) " class=\"on\"" else ""
            append("<a").append(on).append(" href=\"")
            append(esc(listingHref("/browse", mountId, relative, next, false)))
            append("\">").append(label).append("</a>")
        }
        append("</div></div>")
    }
    val sizes = pageSizeLinks(mountId, relative, options, pickAll)
    val pager = pagerLinks(mountId, relative, options, pickAll, window)
    val summary = "第 ${window.page}／${window.pages} 頁，共 ${window.total} 個"
    val picked = if (pickAll) "$summary，呢頁已剔 ${window.items.size} 個" else summary
    val zipFolder = Paths.joinUrl("/zip", mountId, relative)
    return """
        <div class="toolbar">
          $sortLinks
          <div class="field"><span class="label">每頁</span>$sizes</div>
        </div>
        <form class="search" method="get" action="${esc(Paths.joinUrl("/browse", mountId, relative))}">
          <input type="hidden" name="sort" value="${esc(wireSort(options.sort))}">
          <input type="hidden" name="order" value="${esc(wireOrder(options.direction))}">
          <input type="hidden" name="kind" value="${esc(wireKind(options.kind))}">
          <input type="hidden" name="ps" value="${Listing.normalizePageSize(options.pageSize)}">
          <input type="hidden" name="page" value="1">
          <input class="grow" type="text" name="q" value="${esc(options.query)}" placeholder="搜尋名稱" aria-label="搜尋名稱">
          <button type="submit">搜尋</button>
        </form>
        <div class="summary-row">
          <p class="note">$picked。剔選之後可以下載、刪除、顯示路徑，或者打包 ZIP。成個資料夾可以另載。</p>
          <button type="button" class="ghost" id="view">縮圖</button>
        </div>
        <p class="tools">
          <a href="${esc(listingHref("/browse", mountId, relative, options, true, window.page))}">全選</a>
          <a href="${esc(listingHref("/browse", mountId, relative, options, false, window.page))}">清除</a>
          <a href="${esc(zipFolder)}" data-progress="開始打包資料夾…">下載成個資料夾</a>
        </p>
        $pager
    """.trimIndent()
}

private fun listingTable(
    mountId: String,
    relative: String,
    options: ListingOptions,
    window: ListingWindow<FileEntry>,
    pickAll: Boolean,
    previewToken: String?,
): String {
    val entries = window.items
    val body = buildString {
        if (entries.isEmpty()) {
            append("<div class=\"empty\"><p>")
            append(if (options.query.isNotEmpty() || options.kind != null) "冇符合嘅項目。" else "呢個資料夾係空嘅。")
            append("</p></div>")
        } else {
            val master = if (pickAll) " checked" else ""
            append("<ul class=\"files\" id=\"files\">")
            append("<li class=\"file head\"><span class=\"pick\">")
            append("<input id=\"pick-page\" type=\"checkbox\" aria-label=\"全選呢頁\"").append(master).append(">")
            append("</span><span>名稱</span><span class=\"meta\"><span>大小</span><span>修改時間</span></span><span class=\"act\"></span></li>")
            for (entry in entries) {
                val child = if (relative.isEmpty()) entry.name else "$relative/${entry.name}"
                append("<li class=\"file\"><label class=\"pick\">")
                val checked = if (pickAll) " checked" else ""
                append("<input form=\"batch\" type=\"checkbox\" name=\"path\" value=\"")
                append(esc(child)).append("\"").append(checked)
                append(" aria-label=\"").append(esc(entry.name)).append("\">")
                append("</label>")
                val kind = FileIcons.of(entry.name, entry.directory)
                val thumb = if (!entry.directory && (kind == FileIcon.IMAGE || kind == FileIcon.VIDEO)) {
                    val base = Paths.joinUrl("/thumb", mountId, child) + "?edge=96"
                    if (previewToken.isNullOrEmpty()) base else "$base&t=$previewToken"
                } else {
                    null
                }
                if (entry.directory) {
                    val href = listingHref("/browse", mountId, child, options, false)
                    append("<a class=\"file-link\" href=\"").append(esc(href)).append("\">")
                    append(previewMark(kind, null)).append(esc(entry.name)).append("/</a>")
                } else {
                    val href = Paths.joinUrl("/raw", mountId, child)
                    append("<a class=\"file-link\" href=\"").append(esc(href)).append("\">")
                    append(previewMark(kind, thumb)).append(esc(entry.name)).append("</a>")
                }
                append("<div class=\"meta\"><span>")
                append(if (entry.directory) "資料夾" else esc(TextFormat.size(entry.size)))
                append("</span><span>")
                append(esc(TextFormat.clock(entry.modifiedEpochMs)))
                append("</span></div><div class=\"act\">")
                if (!entry.directory) {
                    val download = Paths.joinUrl("/raw", mountId, child) + "?download=1"
                    append("<a class=\"btn\" data-progress=\"開始下載…\" href=\"").append(esc(download)).append("\">下載</a>")
                }
                append("</div></li>")
            }
            append("</ul>")
        }
    }
    return """
        <form id="batch" method="post" action="/batch">
          ${hiddenListing(mountId, relative, options, window.page)}
          <div class="actions">
            <button type="submit" form="batch" formaction="/download" data-progress="正在準備下載…">下載所選</button>
            <button type="submit" form="batch" formaction="/zip" data-progress="開始打包…">打包 ZIP</button>
            <button type="submit" class="ghost" name="action" value="paths">顯示路徑</button>
            <button type="submit" class="danger" name="action" value="delete" data-progress="正在刪除…">刪除所選</button>
          </div>
          $body
        </form>
        ${pagerLinks(mountId, relative, options, pickAll, window)}
    """.trimIndent()
}

private fun uploadForms(mountId: String, relative: String): String {
    return """
        <div class="split">
          <form method="post" action="/upload" enctype="multipart/form-data">
            <h2>上載</h2>
            <input type="hidden" name="mount" value="${esc(mountId)}">
            <input type="hidden" name="dir" value="${esc(relative)}">
            <p><input type="file" name="file" required></p>
            <p><button type="submit">上載</button></p>
          </form>
          <form method="post" action="/mkdir">
            <h2>新資料夾</h2>
            <input type="hidden" name="mount" value="${esc(mountId)}">
            <input type="hidden" name="dir" value="${esc(relative)}">
            <p class="row"><input class="grow" type="text" name="name" required placeholder="名稱" aria-label="新資料夾名稱"><button type="submit">建立</button></p>
          </form>
        </div>
    """.trimIndent()
}

private fun hiddenListing(mountId: String, directory: String, options: ListingOptions, page: Int): String {
    return """
        <input type="hidden" name="mount" value="${esc(mountId)}">
        <input type="hidden" name="back" value="${esc(directory)}">
        <input type="hidden" name="sort" value="${esc(wireSort(options.sort))}">
        <input type="hidden" name="order" value="${esc(wireOrder(options.direction))}">
        <input type="hidden" name="kind" value="${esc(wireKind(options.kind))}">
        <input type="hidden" name="q" value="${esc(options.query)}">
        <input type="hidden" name="ps" value="${Listing.normalizePageSize(options.pageSize)}">
        <input type="hidden" name="page" value="${if (page < 1) 1 else page}">
    """.trimIndent()
}

private fun pageSizeLinks(
    mountId: String,
    relative: String,
    options: ListingOptions,
    pickAll: Boolean,
): String = buildString {
    append("<div class=\"tools\">")
    for (size in intArrayOf(20, 50, 100)) {
        val next = options.copy(pageSize = size)
        val on = if (Listing.normalizePageSize(options.pageSize) == size) " class=\"on\"" else ""
        append("<a").append(on).append(" href=\"")
        append(esc(listingHref("/browse", mountId, relative, next, pickAll, page = 1)))
        append("\">").append(size).append("</a>")
    }
    append("</div>")
}

private fun pagerLinks(
    mountId: String,
    relative: String,
    options: ListingOptions,
    pickAll: Boolean,
    window: ListingWindow<FileEntry>,
): String {
    if (window.pages <= 1) {
        return ""
    }
    val prev = if (window.page > 1) {
        val href = listingHref("/browse", mountId, relative, options, pickAll, window.page - 1)
        "<a href=\"${esc(href)}\">上一頁</a>"
    } else {
        ""
    }
    val next = if (window.page < window.pages) {
        val href = listingHref("/browse", mountId, relative, options, pickAll, window.page + 1)
        "<a href=\"${esc(href)}\">下一頁</a>"
    } else {
        ""
    }
    return "<p class=\"tools\">$prev<span>第 ${window.page}／${window.pages} 頁</span>$next</p>"
}

private fun hiddenPaths(paths: List<String>): String = buildString {
    for (path in paths) {
        append("<input type=\"hidden\" name=\"path\" value=\"").append(esc(path)).append("\">")
    }
}

private fun listingHref(
    prefix: String,
    mountId: String,
    relative: String,
    options: ListingOptions,
    pickAll: Boolean,
    page: Int = 1,
): String {
    return Paths.joinUrl(prefix, mountId, relative) + "?" + Listing.queryString(options, pickAll, page)
}

private fun previewMark(icon: FileIcon, thumb: String?): String {
    val badge = if (thumb != null && icon == FileIcon.VIDEO) "<span class=\"badge\">▶</span>" else ""
    val image = if (thumb == null) {
        ""
    } else {
        "<img class=\"preview\" src=\"" + esc(thumb) +
            "\" alt=\"\" width=\"72\" height=\"72\" decoding=\"async\">"
    }
    return "<span class=\"thumb\">" + iconSvg(icon) + image + badge + "</span>"
}

private fun iconSvg(icon: FileIcon): String = when (icon) {
    FileIcon.FOLDER -> fileSvg("DIR", "folder", folder = true)
    FileIcon.IMAGE -> fileSvg("IMG", "image")
    FileIcon.AUDIO -> fileSvg("AUD", "audio")
    FileIcon.VIDEO -> fileSvg("VID", "video")
    FileIcon.PDF -> fileSvg("PDF", "pdf")
    FileIcon.ARCHIVE -> fileSvg("ZIP", "archive")
    FileIcon.TEXT -> fileSvg("TXT", "text")
    FileIcon.CODE -> fileSvg("CODE", "code")
    FileIcon.SHEET -> fileSvg("XLS", "sheet")
    FileIcon.SLIDES -> fileSvg("PPT", "slides")
    FileIcon.OTHER -> fileSvg("FILE", "other")
}

private fun fileSvg(label: String, kind: String, folder: Boolean = false): String {
    val body = if (folder) {
        """<path fill="currentColor" d="M10 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-8l-2-2z"/>"""
    } else {
        """<path fill="none" stroke="currentColor" stroke-width="1.6" d="M6 3.5h8.2L19 8.2V20.5H6z"/><path fill="none" stroke="currentColor" stroke-width="1.6" d="M14 3.5V8h5"/><text x="12" y="17.5" text-anchor="middle" font-size="5.2" font-family="sans-serif" fill="currentColor">$label</text>"""
    }
    return """<svg class="ico" data-kind="$kind" viewBox="0 0 24 24" aria-hidden="true">$body</svg>"""
}

private fun wireSort(sort: SortKey): String = when (sort) {
    SortKey.NAME -> "name"
    SortKey.SIZE -> "size"
    SortKey.MODIFIED -> "modified"
}

private fun wireOrder(direction: SortDirection): String = when (direction) {
    SortDirection.ASC -> "asc"
    SortDirection.DESC -> "desc"
}

private fun wireKind(kind: FileKind?): String = when (kind) {
    null -> "all"
    FileKind.IMAGE -> "image"
    FileKind.VIDEO -> "video"
    FileKind.DOCUMENT -> "doc"
    FileKind.OTHER -> "other"
}

private fun page(title: String, body: String): String {
    return """
        <!DOCTYPE html>
        <html lang="zh-Hant">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <meta name="referrer" content="no-referrer">
          <meta name="color-scheme" content="light dark">
          <title>${esc(title)}</title>
          <style>$CSS</style>
          <script>
          try {
            var savedTheme = localStorage.getItem("openshelf-theme");
            if (savedTheme === "light" || savedTheme === "dark") {
              document.documentElement.setAttribute("data-theme", savedTheme);
            }
          } catch (e) {}
          </script>
        </head>
        <body>
        <header class="topbar">
          <a class="brand" href="/"><span class="mark" aria-hidden="true">快</span><span class="brand-name">快速臨時開放伺服器</span></a>
          <button type="button" class="ghost" id="theme">外觀</button>
        </header>
        <p id="progress" hidden></p>
        <main>
        $body
        </main>
        <script>
        document.addEventListener("click", function (event) {
          var node = event.target;
          if (!node) return;
          if (node.nodeType === 3) node = node.parentElement;
          if (!node || !node.closest) return;
          var el = node.closest("[data-progress]");
          if (!el) return;
          var bar = document.getElementById("progress");
          if (!bar) return;
          bar.hidden = false;
          bar.textContent = el.getAttribute("data-progress") || "";
        });
        document.addEventListener("error", function (event) {
          var node = event.target;
          if (!node || node.tagName !== "IMG" || !node.classList) return;
          if (node.classList.contains("preview")) node.hidden = true;
        }, true);
        (function () {
          var root = document.documentElement;
          var themeBtn = document.getElementById("theme");
          function themeLabel(mode) {
            if (mode === "dark") return "深色";
            if (mode === "light") return "淺色";
            return "外觀";
          }
          function readTheme() {
            try { return localStorage.getItem("openshelf-theme") || ""; } catch (err) { return ""; }
          }
          function applyTheme(mode) {
            if (mode === "light" || mode === "dark") root.setAttribute("data-theme", mode);
            else root.removeAttribute("data-theme");
            if (themeBtn) themeBtn.textContent = themeLabel(mode);
          }
          applyTheme(readTheme());
          if (themeBtn) {
            themeBtn.addEventListener("click", function () {
              var cur = root.getAttribute("data-theme");
              var next = cur === "dark" ? "light" : cur === "light" ? "" : "dark";
              try { localStorage.setItem("openshelf-theme", next || "system"); } catch (err) {}
              applyTheme(next);
            });
          }
          var files = document.getElementById("files");
          var viewBtn = document.getElementById("view");
          function applyView(mode) {
            if (!files) return;
            if (mode === "grid") files.classList.add("grid");
            else files.classList.remove("grid");
            if (viewBtn) viewBtn.textContent = mode === "grid" ? "列表" : "縮圖";
          }
          var savedView = "";
          try { savedView = localStorage.getItem("openshelf-view") || ""; } catch (err) {}
          if (savedView === "grid" || savedView === "list") applyView(savedView);
          if (viewBtn) {
            viewBtn.addEventListener("click", function () {
              var next = files && files.classList.contains("grid") ? "list" : "grid";
              try { localStorage.setItem("openshelf-view", next); } catch (err) {}
              applyView(next);
            });
          }
          var master = document.getElementById("pick-page");
          if (master) {
            master.addEventListener("change", function () {
              var boxes = document.querySelectorAll("input[name=path][type=checkbox]");
              for (var i = 0; i < boxes.length; i++) boxes[i].checked = master.checked;
            });
          }
          if (master) {
            var rowBoxes = document.querySelectorAll("input[name=path][type=checkbox]");
            for (var j = 0; j < rowBoxes.length; j++) {
              rowBoxes[j].addEventListener("change", function () {
                var all = rowBoxes.length > 0;
                for (var k = 0; k < rowBoxes.length; k++) if (!rowBoxes[k].checked) all = false;
                master.checked = all;
              });
            }
          }
        })();
        </script>
        </body></html>
    """.trimIndent()
}
