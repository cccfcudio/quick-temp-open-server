package app.openshelf.core.tunnel

object TunnelLogParser {
    private val urlPattern = Regex("""https://[A-Za-z0-9-]+\.trycloudflare\.com""")

    fun findUrl(line: String): String? = urlPattern.find(line)?.value
}

object CloudflaredCommand {
    // Quick Tunnel defaults to QUIC. HTTP/2 over TCP is easier to keep up on mobile networks.
    fun arguments(originUrl: String): List<String> {
        require(originUrl.startsWith("http://127.0.0.1:") || originUrl.startsWith("http://localhost:")) {
            "隧道只可以指向本機伺服器"
        }
        return listOf(
            "tunnel",
            "--no-autoupdate",
            "--protocol",
            "http2",
            "--edge-ip-version",
            "4",
            "--loglevel",
            "info",
            "--url",
            originUrl,
        )
    }
}
