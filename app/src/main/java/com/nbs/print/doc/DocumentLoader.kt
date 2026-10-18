package com.nbs.print.doc

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.util.UUID

/** Turns picked / shared content into a [PrintableDoc]. Blocking: call from a background thread. */
object DocumentLoader {
    private const val MAX_TEXT_BYTES = 10L * 1024 * 1024

    class LoadException(message: String) : Exception(message)

    private enum class Kind { PDF, IMAGE, TEXT }

    fun clearCache(context: Context) {
        File(context.cacheDir, "docs").deleteRecursively()
    }

    fun load(context: Context, uris: List<Uri>, sharedText: String?): PrintableDoc {
        if (uris.isEmpty()) {
            val text = sharedText?.takeIf { it.isNotBlank() } ?: throw LoadException("Nothing to print.")
            return TextDoc("Shared text", text)
        }

        val dir = File(context.cacheDir, "docs").apply { mkdirs() }
        val parts = ArrayList<FixedPart>()
        var text: TextDoc? = null
        val names = ArrayList<String>()
        try {
            for (uri in uris) {
                val name = displayName(context, uri)
                names += name
                val kind = detectKind(context, uri, name)
                    ?: throw LoadException("\"$name\" isn't a PDF, image or text file.")

                val file = File(dir, UUID.randomUUID().toString())
                copy(context, uri, file, if (kind == Kind.TEXT) MAX_TEXT_BYTES else Long.MAX_VALUE)

                when (kind) {
                    Kind.PDF -> parts += try {
                        PdfPart(file)
                    } catch (e: SecurityException) {
                        throw LoadException("\"$name\" is password protected. Remove the password and try again.")
                    } catch (e: IOException) {
                        throw LoadException("\"$name\" couldn't be opened as a PDF.")
                    }

                    Kind.IMAGE -> parts += ImagePart.open(file)
                        ?: throw LoadException("\"$name\" couldn't be decoded as an image.")

                    Kind.TEXT -> {
                        if (uris.size > 1) throw LoadException("Text files can only be printed one at a time.")
                        text = TextDoc(name, file.readText())
                        file.delete()
                    }
                }
            }
        } catch (t: Throwable) {
            parts.forEach { it.close() }
            throw t
        }

        text?.let { return it }
        val title = if (names.size == 1) names[0] else "${names[0]} + ${names.size - 1} more"
        return FixedDoc(title, parts)
    }

    private fun detectKind(context: Context, uri: Uri, name: String): Kind? {
        val type = context.contentResolver.getType(uri)?.lowercase()
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        return when {
            type == null -> null
            type == "application/pdf" -> Kind.PDF
            type.startsWith("image/") -> Kind.IMAGE
            type.startsWith("text/") -> Kind.TEXT
            else -> null
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0)?.let { n -> return n }
                }
            } catch (_: Exception) {
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
    }

    // Copy first: PdfRenderer needs a seekable descriptor, which some providers can't give,
    // and the copy also outlives a revoked share permission.
    private fun copy(context: Context, uri: Uri, dest: File, limit: Long) {
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        } ?: throw LoadException("Couldn't read that file.")
        input.use { src ->
            dest.outputStream().use { out ->
                val copied = src.copyTo(out)
                if (copied > limit) {
                    dest.delete()
                    throw LoadException("That file is too large to print as text.")
                }
            }
        }
    }
}
