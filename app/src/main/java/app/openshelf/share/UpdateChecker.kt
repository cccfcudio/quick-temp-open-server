package app.openshelf.share

import app.openshelf.BuildConfig
import app.openshelf.core.json.MiniJson
import app.openshelf.core.json.asObject
import app.openshelf.core.json.asString
import java.net.HttpURLConnection
import java.net.URL

object GitHubProject {
    const val OWNER = "cccfcudio"
    const val REPO = "quick-temp-open-server"
    const val PAGE_URL = "https://github.com/cccfcudio/quick-temp-open-server"
    const val RELEASES_URL = "https://github.com/cccfcudio/quick-temp-open-server/releases"
    const val LATEST_API =
        "https://api.github.com/repos/cccfcudio/quick-temp-open-server/releases/latest"
}

sealed interface UpdateStatus {
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val latestTag: String, val releaseUrl: String) : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
}

object UpdateChecker {
    fun currentVersionName(): String = BuildConfig.VERSION_NAME

    fun currentVersionCode(): Int = BuildConfig.VERSION_CODE

    fun checkLatest(): UpdateStatus {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(GitHubProject.LATEST_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 12_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "QuickTempOpenServer/${BuildConfig.VERSION_NAME}")
                instanceFollowRedirects = true
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                return UpdateStatus.Failed("HTTP $code")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val obj = MiniJson.parse(body).asObject() ?: return UpdateStatus.Failed("bad json")
            val tag = obj["tag_name"]?.asString()?.trim().orEmpty()
            if (tag.isEmpty()) {
                return UpdateStatus.Failed("no tag")
            }
            val htmlUrl = obj["html_url"]?.asString()?.ifBlank { null } ?: GitHubProject.RELEASES_URL
            val latest = normalizeVersion(tag)
            val current = normalizeVersion(BuildConfig.VERSION_NAME)
            if (compareVersions(latest, current) > 0) {
                UpdateStatus.Available(tag.removePrefix("v").removePrefix("V"), htmlUrl)
            } else {
                UpdateStatus.UpToDate
            }
        } catch (error: Exception) {
            UpdateStatus.Failed(error.message ?: "network")
        } finally {
            connection?.disconnect()
        }
    }

    internal fun normalizeVersion(raw: String): List<Int> {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V")
        val core = cleaned.substringBefore('-').substringBefore('+')
        return core.split('.').map { part -> part.filter { it.isDigit() }.toIntOrNull() ?: 0 }
    }

    /** @return positive if left > right */
    internal fun compareVersions(left: List<Int>, right: List<Int>): Int {
        val n = maxOf(left.size, right.size)
        for (i in 0 until n) {
            val a = left.getOrElse(i) { 0 }
            val b = right.getOrElse(i) { 0 }
            if (a != b) {
                return a.compareTo(b)
            }
        }
        return 0
    }
}
