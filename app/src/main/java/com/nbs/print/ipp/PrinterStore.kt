package com.nbs.print.ipp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Where the next job goes: Android's own print dialog, or straight to a network printer. */
sealed interface Destination {
    data object SystemDialog : Destination
    data class Network(val printer: PrinterRef) : Destination
}

/** Remembers printers the user chose or added, and the last destination. */
class PrinterStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("printers", Context.MODE_PRIVATE)

    fun saved(): List<PrinterRef> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_SAVED, "[]"))
        (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let { PrinterRef(it.getString("name"), it.getString("uri")) }
        }
    }.getOrDefault(emptyList())

    fun save(printer: PrinterRef) {
        val list = saved().filterNot { it.uri == printer.uri } + printer
        write(list)
    }

    fun remove(printer: PrinterRef) {
        write(saved().filterNot { it.uri == printer.uri })
        if (destination() == Destination.Network(printer)) setDestination(Destination.SystemDialog)
    }

    fun destination(): Destination {
        val uri = prefs.getString(KEY_DEST_URI, null) ?: return Destination.SystemDialog
        val name = prefs.getString(KEY_DEST_NAME, null) ?: return Destination.SystemDialog
        return Destination.Network(PrinterRef(name, uri))
    }

    fun setDestination(dest: Destination) {
        prefs.edit().apply {
            when (dest) {
                Destination.SystemDialog -> { remove(KEY_DEST_URI); remove(KEY_DEST_NAME) }
                is Destination.Network -> {
                    putString(KEY_DEST_URI, dest.printer.uri)
                    putString(KEY_DEST_NAME, dest.printer.name)
                }
            }
        }.apply()
    }

    private fun write(list: List<PrinterRef>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("uri", it.uri)) }
        prefs.edit().putString(KEY_SAVED, arr.toString()).apply()
    }

    private companion object {
        const val KEY_SAVED = "saved"
        const val KEY_DEST_URI = "dest_uri"
        const val KEY_DEST_NAME = "dest_name"
    }
}
