/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.utils.io.charsets

import kotlinx.io.*

public actual abstract class Charset(internal val _name: String) {
    public actual abstract fun newEncoder(): CharsetEncoder
    public actual abstract fun newDecoder(): CharsetDecoder

    actual override fun equals(other: Any?): Boolean =
        this === other || (other is Charset && _name == other._name)

    actual override fun hashCode(): Int = _name.hashCode()
    actual override fun toString(): String = _name

    public companion object {
        private fun getCharset(name: String): Charset? = when (name.replace('_', '-').lowercase()) {
            "utf8", "utf-8" -> Charsets.UTF_8
            "iso-8859-1", "latin1" -> Charsets.ISO_8859_1
            else -> null
        }

        public fun forName(name: String): Charset =
            getCharset(name) ?: throw IllegalArgumentException("Charset $name is not supported")

        public fun isSupported(name: String): Boolean = getCharset(name) != null
    }
}

public actual fun Charsets.isSupported(name: String): Boolean = Charset.isSupported(name)
public actual fun Charsets.forName(name: String): Charset = Charset.forName(name)
public actual val Charset.name: String get() = _name

public actual abstract class CharsetEncoder(internal val _charset: Charset)
public actual val CharsetEncoder.charset: Charset get() = _charset

public actual abstract class CharsetDecoder(internal val _charset: Charset)
public actual val CharsetDecoder.charset: Charset get() = _charset

public actual fun CharsetEncoder.encodeToByteArray(
    input: CharSequence,
    fromIndex: Int,
    toIndex: Int,
): ByteArray = encodeToByteArrayImpl(input, fromIndex, toIndex)

@OptIn(InternalIoApi::class)
public actual fun CharsetDecoder.decode(input: Source, dst: Appendable, max: Int): Int {
    val count = minOf(input.buffer.size, max.toLong()).toInt()
    val bytes = input.readByteArray(count)
    val text = try {
        when (charset) {
            Charsets.UTF_8 -> bytes.decodeToString()
            Charsets.ISO_8859_1 -> buildString(bytes.size) {
                bytes.forEach { append((it.toInt() and 0xff).toChar()) }
            }
            else -> error("Unsupported charset: ${charset.name}")
        }
    } catch (cause: Throwable) {
        throw MalformedInputException("Failed to decode bytes: ${cause.message ?: "no cause provided"}")
    }
    dst.append(text)
    return text.length
}

private class CharsetImpl(name: String) : Charset(name) {
    override fun newEncoder(): CharsetEncoder = object : CharsetEncoder(this) {}
    override fun newDecoder(): CharsetDecoder = object : CharsetDecoder(this) {}
}

public actual object Charsets {
    public actual val UTF_8: Charset = CharsetImpl("UTF-8")
    public actual val ISO_8859_1: Charset = CharsetImpl("ISO-8859-1")
}

public actual open class MalformedInputException actual constructor(message: String) : IOException(message)

internal actual fun CharsetEncoder.encodeImpl(
    input: CharSequence,
    fromIndex: Int,
    toIndex: Int,
    dst: Sink,
): Int {
    require(fromIndex <= toIndex)
    when (charset) {
        Charsets.UTF_8 -> dst.writeString(input, fromIndex, toIndex)
        Charsets.ISO_8859_1 -> {
            val bytes = ByteArray(toIndex - fromIndex) { index ->
                val code = input[fromIndex + index].code
                if (code > 0xff) '?'.code.toByte() else code.toByte()
            }
            dst.write(bytes)
        }
        else -> error("Unsupported charset: ${charset.name}")
    }
    return toIndex - fromIndex
}

internal actual fun CharsetEncoder.encodeToByteArrayImpl(
    input: CharSequence,
    fromIndex: Int,
    toIndex: Int,
): ByteArray {
    if (fromIndex >= toIndex) return ByteArray(0)
    return when (charset) {
        Charsets.UTF_8 -> input.subSequence(fromIndex, toIndex).toString().encodeToByteArray()
        Charsets.ISO_8859_1 -> ByteArray(toIndex - fromIndex) { index ->
            val code = input[fromIndex + index].code
            if (code > 0xff) '?'.code.toByte() else code.toByte()
        }
        else -> error("Unsupported charset: ${charset.name}")
    }
}
