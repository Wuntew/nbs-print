package com.nbs.print.ipp

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet6Address

/**
 * Finds IPP printers on the local network via mDNS / DNS-SD (the same mechanism AirPrint and
 * Mopria use). Emits the full, current list every time it changes.
 */
class PrinterDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    @Suppress("DEPRECATION") // resolveService is deprecated on API 34+ but works on every API we support
    fun discover(): Flow<List<PrinterRef>> = callbackFlow {
        val lock = Any()
        val found = LinkedHashMap<String, PrinterRef>() // instance name -> printer
        val secureNames = HashSet<String>()
        val queue = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        fun publish() = trySend(synchronized(lock) { found.values.sortedBy { it.name.lowercase() } })

        fun resolveNext() {
            val next = synchronized(lock) {
                if (resolving) return
                queue.removeFirstOrNull()?.also { resolving = true } ?: return
            }
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    synchronized(lock) { resolving = false }
                    resolveNext()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    toPrinter(info)?.let { (printer, secure) ->
                        synchronized(lock) {
                            // Prefer plain ipp:// when a printer advertises both: no certificate hassle.
                            if (printer.name !in found || (printer.name in secureNames && !secure)) {
                                found[printer.name] = printer
                                if (secure) secureNames.add(printer.name) else secureNames.remove(printer.name)
                            }
                        }
                        publish()
                    }
                    synchronized(lock) { resolving = false }
                    resolveNext()
                }
            })
        }

        fun listener() = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(lock) { queue.add(info) }
                resolveNext()
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(lock) { found.remove(info.serviceName); secureNames.remove(info.serviceName) }
                publish()
            }
        }

        val plain = listener()
        val secure = listener()
        trySend(emptyList())
        nsd.discoverServices("_ipp._tcp", NsdManager.PROTOCOL_DNS_SD, plain)
        nsd.discoverServices("_ipps._tcp", NsdManager.PROTOCOL_DNS_SD, secure)

        awaitClose {
            runCatching { nsd.stopServiceDiscovery(plain) }
            runCatching { nsd.stopServiceDiscovery(secure) }
        }
    }

    private fun toPrinter(info: NsdServiceInfo): Pair<PrinterRef, Boolean>? {
        val addr = info.host ?: return null
        // Link-local IPv6 addresses need a scope id that URLs can't carry; skip them.
        if (addr is Inet6Address && addr.isLinkLocalAddress) return null
        val host = (addr.hostAddress ?: return null).substringBefore('%').let { if (':' in it) "[$it]" else it }

        val secure = info.serviceType.trim('.').startsWith("_ipps")
        val rp = info.attributes["rp"]?.toString(Charsets.UTF_8)?.trim('/')?.takeIf { it.isNotEmpty() } ?: "ipp/print"
        val scheme = if (secure) "ipps" else "ipp"
        return PrinterRef(info.serviceName, "$scheme://$host:${info.port}/$rp") to secure
    }
}
