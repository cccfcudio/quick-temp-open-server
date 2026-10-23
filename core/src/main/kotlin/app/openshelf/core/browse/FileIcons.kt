package app.openshelf.core.browse

enum class FileIcon {
    FOLDER,
    IMAGE,
    AUDIO,
    VIDEO,
    PDF,
    ARCHIVE,
    TEXT,
    CODE,
    SHEET,
    SLIDES,
    OTHER,
}

object FileIcons {
    fun of(name: String, directory: Boolean): FileIcon {
        if (directory) {
            return FileIcon.FOLDER
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg" -> FileIcon.IMAGE
            "mp3", "wav", "flac", "aac", "ogg", "m4a", "opus" -> FileIcon.AUDIO
            "mp4", "mov", "mkv", "webm", "avi", "3gp" -> FileIcon.VIDEO
            "pdf" -> FileIcon.PDF
            "zip", "rar", "7z", "tar", "gz", "tgz" -> FileIcon.ARCHIVE
            "txt", "md", "rtf", "doc", "docx" -> FileIcon.TEXT
            "json", "xml", "html", "htm", "css", "js", "kt", "kts", "java", "py", "go", "c", "cpp", "h", "rs", "ts", "tsx", "jsx", "sh" ->
                FileIcon.CODE
            "xls", "xlsx", "csv" -> FileIcon.SHEET
            "ppt", "pptx" -> FileIcon.SLIDES
            else -> FileIcon.OTHER
        }
    }
}
