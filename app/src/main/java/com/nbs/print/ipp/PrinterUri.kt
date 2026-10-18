package com.nbs.print.ipp

/** A printer reachable over IPP. [uri] is always an ipp:// or ipps:// URI with an explicit port and path. */
data class PrinterRef(val name: String, val uri: String)

object PrinterUri {
    const val DEFAULT_PORT = 631
    const val DEFAULT_PATH = "/ipp/print"

    /**
     * Turns what a person types ("192.168.1.20", "printer.local:631/ipp/printer", "ipp://...",
     * "http://...") into a canonical ipp(s):// URI, or null if it can't be a printer address.
     */
    fun normalize(input: String): String? {
        var s = input.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null

        var secure = false
        val scheme = s.substringBefore("://", "").lowercase()
        if (scheme.isNotEmpty() || s.contains("://")) {
            when (scheme) {
                "ipp", "http" -> {}
                "ipps", "https" -> secure = true
                else -> return null
            }
            s = s.substringAfter("://")
        }

        val hostPort = s.substringBefore('/')
        var path = s.removePrefix(hostPort)
        if (path.isEmpty() || path == "/") path = DEFAULT_PATH
        if (path.any { it == '?' || it == '#' }) return null

        val host: String
        var port = DEFAULT_PORT
        if (hostPort.startsWith("[")) { // [IPv6]:port
            val close = hostPort.indexOf(']')
            if (close < 0) return null
            host = hostPort.substring(0, close + 1)
            val rest = hostPort.substring(close + 1)
            if (rest.isNotEmpty()) port = rest.removePrefix(":").toIntOrNull() ?: return null
        } else {
            host = hostPort.substringBefore(':')
            if (hostPort.contains(':')) port = hostPort.substringAfter(':').toIntOrNull() ?: return null
        }
        if (host.isEmpty() || port !in 1..65535) return null
        if (!host.startsWith("[") && !host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null

        return "${if (secure) "ipps" else "ipp"}://$host:$port$path"
    }

    /** The http(s) URL IPP requests are POSTed to. */
    fun httpUrl(ippUri: String): String = when {
        ippUri.startsWith("ipps://") -> "https://" + ippUri.removePrefix("ipps://")
        ippUri.startsWith("ipp://") -> "http://" + ippUri.removePrefix("ipp://")
        else -> ippUri
    }

    fun host(ippUri: String): String =
        ippUri.substringAfter("://").substringBefore('/').let {
            if (it.startsWith("[")) it.substringBefore(']') + "]" else it.substringBefore(':')
        }
}
