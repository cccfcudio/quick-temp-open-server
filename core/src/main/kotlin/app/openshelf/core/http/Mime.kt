package app.openshelf.core.http

import app.openshelf.core.store.Paths

fun mimeFor(name: String): String {
    return when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "txt", "md" -> "text/plain; charset=utf-8"
        "html", "htm" -> "text/html; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "pdf" -> "application/pdf"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
}

fun contentDisposition(name: String, download: Boolean): String {
    val kind = if (download) "attachment" else "inline"
    val ascii = buildString(name.length) {
        for (ch in name) {
            append(if (ch.code in 32..126 && ch != '"' && ch != '\\') ch else '_')
        }
    }
    return "$kind; filename=\"$ascii\"; filename*=UTF-8''${Paths.percentEncode(name)}"
}
