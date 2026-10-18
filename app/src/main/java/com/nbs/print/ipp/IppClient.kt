package com.nbs.print.ipp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** What to ask the printer for. Null values mean "printer default". */
data class JobTicket(
    val copies: Int = 1,
    val sides: String? = null,
    /** true = color, false = monochrome. */
    val color: Boolean? = null,
    val media: String? = null,
    /**
     * print-scaling to request: "none" when the page already carries its exact size (PDF, raster),
     * "fit" for JPEG, or null to leave the printer's default.
     */
    val scaling: String? = "none",
)

data class SentJob(val id: Int?, val state: Int?)

data class JobStatus(
    val state: Int?,
    val reasons: List<String>,
    /** Pages (impressions) the printer says it has finished, when it reports that at all. */
    val impressionsCompleted: Int? = null,
) {
    val isFinished get() = state in 7..9
    val isCompleted get() = state == 9

    companion object {
        fun from(job: IppGroup?) = JobStatus(
            state = job?.int("job-state"),
            reasons = job?.strings("job-state-reasons").orEmpty(),
            impressionsCompleted = job?.int("job-impressions-completed")?.takeIf { it >= 0 },
        )
    }
}

/** IPP over HTTP(S). Blocking; call from a background thread. */
class IppClient(private val network: Network? = null) {
    private var nextRequestId = 1

    fun getPrinterCaps(printerUri: String): PrinterCaps {
        val reply = send(printerUri, 30_000) { major, minor ->
            IppWriter(IppOp.GET_PRINTER_ATTRIBUTES, nextRequestId++, major, minor)
                .operationPreamble(printerUri)
                .name("requesting-user-name", USER)
                .keywords(
                    "requested-attributes",
                    listOf(
                        "printer-name", "printer-make-and-model", "printer-state", "printer-state-reasons",
                        "printer-is-accepting-jobs", "document-format-supported", "copies-supported",
                        "color-supported", "print-color-mode-supported", "sides-supported", "media-supported",
                        "print-scaling-supported", "printer-resolution-supported",
                        "pwg-raster-document-type-supported", "pwg-raster-document-resolution-supported",
                        "urf-supported",
                        "media-left-margin-supported", "media-right-margin-supported",
                        "media-top-margin-supported", "media-bottom-margin-supported",
                    ),
                )
                .finish()
        }
        return PrinterCaps.parse(reply)
    }

    fun printJob(printerUri: String, jobName: String, mime: String, document: File, ticket: JobTicket): SentJob {
        val reply = send(printerUri, 300_000, body = document) { major, minor ->
            val w = IppWriter(IppOp.PRINT_JOB, nextRequestId++, major, minor)
                .operationPreamble(printerUri)
                .name("requesting-user-name", USER)
                .name("job-name", jobName)
                .mime("document-format", mime)
                .group(IppTag.JOB_GROUP)
            if (ticket.copies > 1) w.integer("copies", ticket.copies)
            ticket.sides?.let { w.keyword("sides", it) }
            ticket.color?.let { w.keyword("print-color-mode", if (it) "color" else "monochrome") }
            ticket.media?.let { w.keyword("media", it) }
            ticket.scaling?.let { w.keyword("print-scaling", it) }
            w.finish()
        }
        val job = reply.group(IppTag.JOB_GROUP)
        return SentJob(job?.int("job-id"), job?.int("job-state"))
    }

    fun jobStatus(printerUri: String, jobId: Int): JobStatus {
        val reply = send(printerUri, 15_000) { major, minor ->
            IppWriter(IppOp.GET_JOB_ATTRIBUTES, nextRequestId++, major, minor)
                .operationPreamble(printerUri)
                .integer("job-id", jobId)
                .name("requesting-user-name", USER)
                .keywords("requested-attributes", listOf("job-state", "job-state-reasons", "job-impressions-completed"))
                .finish()
        }
        return JobStatus.from(reply.group(IppTag.JOB_GROUP))
    }

    fun cancelJob(printerUri: String, jobId: Int) {
        send(printerUri, 15_000) { major, minor ->
            IppWriter(IppOp.CANCEL_JOB, nextRequestId++, major, minor)
                .operationPreamble(printerUri)
                .integer("job-id", jobId)
                .name("requesting-user-name", USER)
                .finish()
        }
    }

    /** Sends one IPP request, retrying once as IPP 1.1 for printers that reject 2.0. */
    private fun send(
        printerUri: String,
        readTimeoutMs: Int,
        body: File? = null,
        build: (major: Int, minor: Int) -> ByteArray,
    ): IppMessage {
        var reply = post(printerUri, build(2, 0), body, readTimeoutMs)
        if (reply.code == IppStatus.VERSION_NOT_SUPPORTED) reply = post(printerUri, build(1, 1), body, readTimeoutMs)
        if (!reply.isSuccess) {
            val message = reply.group(IppTag.OPERATION_GROUP)?.string("status-message")
            throw IppException(statusText(reply.code, message), statusCode = reply.code)
        }
        return reply
    }

    /**
     * Posts via the Wi-Fi/Ethernet network if there is one. If that can't even connect, tries the default
     * route once (a VPN, an odd Wi-Fi state...). Only connection failures fall back, never timeouts after
     * the request went out, so a job can't be sent twice.
     */
    private fun post(printerUri: String, header: ByteArray, body: File?, readTimeoutMs: Int): IppMessage {
        try {
            return postOnce(network, printerUri, header, body, readTimeoutMs)
        } catch (e: IppException) {
            val cause = e.cause
            val couldNotConnect = cause is ConnectException || cause is NoRouteToHostException
            if (network != null && couldNotConnect) return postOnce(null, printerUri, header, body, readTimeoutMs)
            throw e
        }
    }

    private fun postOnce(net: Network?, printerUri: String, header: ByteArray, body: File?, readTimeoutMs: Int): IppMessage {
        val url = URL(PrinterUri.httpUrl(printerUri))
        try {
            val conn = (net?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            try {
                if (conn is HttpsURLConnection && isLocalHost(url.host)) {
                    // Printers ship self-signed certificates; trust them only on the local network.
                    conn.sslSocketFactory = TRUST_ALL.socketFactory
                    conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
                }
                conn.requestMethod = "POST"
                conn.connectTimeout = 6_000
                conn.readTimeout = readTimeoutMs
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/ipp")
                conn.setRequestProperty("Accept", "application/ipp")
                conn.setFixedLengthStreamingMode(header.size + (body?.length() ?: 0L))
                conn.outputStream.use { out ->
                    out.write(header)
                    body?.inputStream()?.use { it.copyTo(out) }
                }
                when (val http = conn.responseCode) {
                    HttpURLConnection.HTTP_OK -> {}
                    401, 403 -> throw IppException("This printer requires a password, which NBS Print doesn't support yet.")
                    426 -> throw IppException("This printer requires a secure connection. Add it as ipps://… instead.")
                    else -> throw IppException("The printer answered with HTTP error $http.")
                }
                return IppCodec.decode(conn.inputStream.use { it.readBytes() })
            } finally {
                conn.disconnect()
            }
        } catch (e: IppException) {
            throw e
        } catch (e: UnknownHostException) {
            throw IppException("Can't find ${url.host} on this network.", e)
        } catch (e: SocketTimeoutException) {
            throw IppException("The printer didn't answer in time.", e)
        } catch (e: IOException) {
            throw IppException("Can't reach the printer (${e.message ?: e.javaClass.simpleName}).", e)
        }
    }

    companion object {
        private const val USER = "NBS Print"

        /**
         * Sockets bound to the default network can land on mobile data when the Wi-Fi has no
         * internet (typical for printers' own access points), so bind to Wi-Fi/Ethernet explicitly.
         */
        fun forDevice(context: Context): IppClient {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val lan = cm?.allNetworks?.firstOrNull { n ->
                cm.getNetworkCapabilities(n)?.let {
                    (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                        !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                } == true
            }
            return IppClient(lan)
        }

        /** Plain-English reading of an IPP status code (RFC 8011 section 5.4), with the printer's own words appended. */
        fun statusText(code: Int, message: String?): String {
            val reason = when (code) {
                0x0400 -> "The printer didn't understand the request."
                0x0401 -> "The printer says this isn't allowed."
                0x0402 -> "The printer requires a login, which NBS Print doesn't support yet."
                0x0403 -> "The printer says you're not authorized to print."
                0x0404 -> "The printer can't do that."
                0x0405 -> "The printer timed out."
                0x0406 -> "The printer couldn't find that queue."
                0x0408 -> "The job is too big for this printer."
                0x040A -> "The printer doesn't accept this document format."
                0x040B -> "The printer doesn't support one of the print settings."
                0x040E -> "Two of the print settings conflict on this printer."
                0x0411, 0x041B -> "The printer couldn't read the page data."
                0x0500 -> "The printer had an internal error."
                0x0501 -> "The printer doesn't support this operation."
                0x0502 -> "The printer's print service isn't available right now."
                0x0503 -> "The printer doesn't support this IPP version."
                0x0504 -> "The printer reports a device error. Check paper, ink and covers."
                0x0505 -> "The printer had a temporary problem."
                0x0506 -> "The printer isn't accepting jobs."
                0x0507 -> "The printer is busy with another job."
                0x0508 -> "The printer canceled the job."
                0x0509 -> "The printer only accepts one document per job."
                0x050A -> "The printer is switched off or deactivated."
                0x050B -> "The printer's queue is full."
                else -> "The printer reported an error."
            }
            val hex = "0x" + code.toString(16).padStart(4, '0')
            return if (message.isNullOrBlank()) "$reason ($hex)" else "$reason ($hex: $message)"
        }

        internal fun isLocalHost(host: String): Boolean {
            val h = host.removePrefix("[").removeSuffix("]")
            if (h.endsWith(".local") || !h.contains('.') && !h.contains(':')) return true
            if (!h.all { it.isDigit() || it == '.' } && !h.contains(':')) return false
            return runCatching {
                InetAddress.getByName(h).let { it.isSiteLocalAddress || it.isLinkLocalAddress || it.isLoopbackAddress }
            }.getOrDefault(false)
        }

        private val TRUST_ALL: SSLContext = SSLContext.getInstance("TLS").apply {
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            init(null, arrayOf<TrustManager>(tm), SecureRandom())
        }
    }
}
