package com.nbs.print.ipp

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

// Minimal IPP (RFC 8010) encoder/decoder. Pure Kotlin, no Android dependencies.

object IppTag {
    const val OPERATION_GROUP = 0x01
    const val JOB_GROUP = 0x02
    const val END = 0x03
    const val PRINTER_GROUP = 0x04

    const val INTEGER = 0x21
    const val BOOLEAN = 0x22
    const val ENUM = 0x23
    const val RESOLUTION = 0x32
    const val RANGE = 0x33
    const val BEGIN_COLLECTION = 0x34
    const val TEXT_WITH_LANGUAGE = 0x35
    const val NAME_WITH_LANGUAGE = 0x36
    const val END_COLLECTION = 0x37
    const val TEXT = 0x41
    const val NAME = 0x42
    const val KEYWORD = 0x44
    const val URI = 0x45
    const val CHARSET = 0x47
    const val NATURAL_LANGUAGE = 0x48
    const val MIME_TYPE = 0x49
    const val MEMBER_NAME = 0x4A
}

object IppOp {
    const val PRINT_JOB = 0x0002
    const val CANCEL_JOB = 0x0008
    const val GET_JOB_ATTRIBUTES = 0x0009
    const val GET_PRINTER_ATTRIBUTES = 0x000B
}

object IppStatus {
    /** Anything below 0x0100 is a success (possibly with substituted attributes). */
    fun isSuccess(code: Int) = code < 0x0100
    const val VERSION_NOT_SUPPORTED = 0x0503
    const val TEMPORARY_ERROR = 0x0505
    const val BUSY = 0x0507

    /** The printer is occupied (typically still printing another job) and wants us to try again shortly. */
    fun isRetryable(code: Int?) = code == BUSY || code == TEMPORARY_ERROR
}

/** [statusCode] is the IPP status the printer answered with, when the failure came from one. */
class IppException(message: String, cause: Throwable? = null, val statusCode: Int? = null) : Exception(message, cause)

sealed interface IppValue {
    data class Integer(val value: Int, val isEnum: Boolean = false) : IppValue
    data class Bool(val value: Boolean) : IppValue

    /** keyword, uri, mimeMediaType, name, text, charset... all carried as UTF-8 text. */
    data class Text(val value: String, val tag: Int) : IppValue
    data class Resolution(val crossFeed: Int, val feed: Int, val units: Int) : IppValue
    data class Range(val lower: Int, val upper: Int) : IppValue
    data class Collection(val members: Map<String, List<IppValue>>) : IppValue
    class Other(val tag: Int, val bytes: ByteArray) : IppValue
}

data class IppAttribute(val name: String, val values: List<IppValue>)

class IppGroup(val tag: Int, val attributes: List<IppAttribute>) {
    operator fun get(name: String): List<IppValue> = attributes.firstOrNull { it.name == name }?.values.orEmpty()

    fun strings(name: String): List<String> = get(name).mapNotNull { (it as? IppValue.Text)?.value }
    fun string(name: String): String? = strings(name).firstOrNull()
    fun ints(name: String): List<Int> = get(name).mapNotNull { (it as? IppValue.Integer)?.value }
    fun int(name: String): Int? = ints(name).firstOrNull()
    fun bool(name: String): Boolean? = (get(name).firstOrNull() as? IppValue.Bool)?.value
}

data class IppMessage(
    val major: Int,
    val minor: Int,
    /** Operation id for a request, status code for a response. */
    val code: Int,
    val requestId: Int,
    val groups: List<IppGroup>,
) {
    fun group(tag: Int): IppGroup? = groups.firstOrNull { it.tag == tag }
    val isSuccess get() = IppStatus.isSuccess(code)
}

/** Builds an IPP request (or, for tests, response) header and attribute groups. */
class IppWriter(code: Int, requestId: Int, major: Int = 2, minor: Int = 0) {
    private val bytes = ByteArrayOutputStream()
    private val out = DataOutputStream(bytes)

    init {
        out.writeByte(major)
        out.writeByte(minor)
        out.writeShort(code)
        out.writeInt(requestId)
    }

    fun group(tag: Int) = apply { out.writeByte(tag) }

    /** Writes one value; pass an empty [name] for the 2nd and later values of a multi-valued attribute. */
    fun raw(tag: Int, name: String, value: ByteArray) = apply {
        val n = name.toByteArray(Charsets.UTF_8)
        out.writeByte(tag)
        out.writeShort(n.size)
        out.write(n)
        out.writeShort(value.size)
        out.write(value)
    }

    fun text(tag: Int, name: String, value: String) = raw(tag, name, value.toByteArray(Charsets.UTF_8))
    fun keyword(name: String, value: String) = text(IppTag.KEYWORD, name, value)
    fun keywords(name: String, values: List<String>) = apply {
        values.forEachIndexed { i, v -> text(IppTag.KEYWORD, if (i == 0) name else "", v) }
    }

    fun uri(name: String, value: String) = text(IppTag.URI, name, value)
    fun mime(name: String, value: String) = text(IppTag.MIME_TYPE, name, value)
    fun name(name: String, value: String) = text(IppTag.NAME, name, value)
    fun integer(name: String, value: Int) = raw(IppTag.INTEGER, name, int4(value))
    fun enum(name: String, value: Int) = raw(IppTag.ENUM, name, int4(value))
    fun bool(name: String, value: Boolean) = raw(IppTag.BOOLEAN, name, byteArrayOf(if (value) 1 else 0))

    /** The standard first attributes of every operation. */
    fun operationPreamble(printerUri: String? = null) = apply {
        group(IppTag.OPERATION_GROUP)
        text(IppTag.CHARSET, "attributes-charset", "utf-8")
        text(IppTag.NATURAL_LANGUAGE, "attributes-natural-language", "en")
        if (printerUri != null) uri("printer-uri", printerUri)
    }

    fun finish(): ByteArray {
        out.writeByte(IppTag.END)
        out.flush()
        return bytes.toByteArray()
    }

    companion object {
        fun int4(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    }
}

object IppCodec {
    fun decode(data: ByteArray): IppMessage {
        val r = Reader(data)
        val major = r.u8()
        val minor = r.u8()
        val code = r.u16()
        val requestId = r.i32()

        val groups = ArrayList<IppGroup>()
        var groupTag = -1
        var attrs = ArrayList<IppAttribute>()
        var name: String? = null
        var values = ArrayList<IppValue>()

        fun flushAttribute() {
            name?.let { attrs.add(IppAttribute(it, values)) }
            name = null
            values = ArrayList()
        }

        fun flushGroup() {
            flushAttribute()
            if (groupTag >= 0) groups.add(IppGroup(groupTag, attrs))
            attrs = ArrayList()
        }

        while (true) {
            val tag = r.u8()
            if (tag == IppTag.END) break
            if (tag <= 0x0F) { // delimiter: start of a new attribute group
                flushGroup()
                groupTag = tag
                continue
            }
            val attrName = r.string(r.u16())
            val valueLen = r.u16()
            val value = r.read(valueLen)
            if (attrName.isNotEmpty()) {
                flushAttribute()
                name = attrName
            }
            if (name == null) throw IppException("Malformed IPP response (value without attribute)")
            values.add(if (tag == IppTag.BEGIN_COLLECTION) readCollection(r) else toValue(tag, value))
        }
        flushGroup()
        return IppMessage(major, minor, code, requestId, groups)
    }

    private fun readCollection(r: Reader): IppValue.Collection {
        val members = LinkedHashMap<String, MutableList<IppValue>>()
        var member: String? = null
        while (true) {
            val tag = r.u8()
            r.read(r.u16()) // (empty) name
            val value = r.read(r.u16())
            when (tag) {
                IppTag.END_COLLECTION -> return IppValue.Collection(members)
                IppTag.MEMBER_NAME -> member = String(value, Charsets.UTF_8)
                else -> {
                    val key = member ?: throw IppException("Malformed IPP collection")
                    val v = if (tag == IppTag.BEGIN_COLLECTION) readCollection(r) else toValue(tag, value)
                    members.getOrPut(key) { ArrayList() }.add(v)
                }
            }
        }
    }

    private fun toValue(tag: Int, v: ByteArray): IppValue = when (tag) {
        IppTag.INTEGER, IppTag.ENUM -> IppValue.Integer(Reader(v).i32(), isEnum = tag == IppTag.ENUM)
        IppTag.BOOLEAN -> IppValue.Bool(v.isNotEmpty() && v[0].toInt() != 0)
        IppTag.RESOLUTION -> Reader(v).let { IppValue.Resolution(it.i32(), it.i32(), it.u8()) }
        IppTag.RANGE -> Reader(v).let { IppValue.Range(it.i32(), it.i32()) }
        IppTag.TEXT_WITH_LANGUAGE, IppTag.NAME_WITH_LANGUAGE -> Reader(v).let {
            it.read(it.u16()) // language
            IppValue.Text(it.string(it.u16()), tag)
        }
        in 0x40..0x4F -> IppValue.Text(String(v, Charsets.UTF_8), tag)
        else -> IppValue.Other(tag, v)
    }

    private class Reader(private val data: ByteArray) {
        private var pos = 0

        fun read(n: Int): ByteArray {
            if (n < 0 || pos + n > data.size) throw IppException("Truncated IPP response")
            return data.copyOfRange(pos, pos + n).also { pos += n }
        }

        fun u8() = read(1)[0].toInt() and 0xFF
        fun u16() = read(2).let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }
        fun i32() = read(4).let {
            ((it[0].toInt() and 0xFF) shl 24) or ((it[1].toInt() and 0xFF) shl 16) or
                ((it[2].toInt() and 0xFF) shl 8) or (it[3].toInt() and 0xFF)
        }

        fun string(n: Int) = String(read(n), Charsets.UTF_8)
    }
}
