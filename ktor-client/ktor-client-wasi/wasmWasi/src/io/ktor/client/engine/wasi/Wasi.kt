/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:OptIn(kotlin.wasm.ExperimentalWasmInterop::class, io.ktor.utils.io.InternalAPI::class, kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)

package io.ktor.client.engine.wasi

import io.ktor.client.call.UnsupportedContentTypeException
import io.ktor.client.engine.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.date.*
import io.ktor.utils.io.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.io.readByteArray
import kotlin.wasm.WasmImport
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.withScopedMemoryAllocator

/**
 * Ktor engine for core Wasm/WASI guests embedded by a host that implements
 * the `ktor_wasi` capability imports.
 *
 * Methods, headers and buffered request/response bodies are transported
 * generically. The host owns DNS, TLS, redirects, policy and the concrete
 * network stack.
 */
public data object Wasi : HttpClientEngineFactory<WasiEngineConfig> {
    override fun create(block: WasiEngineConfig.() -> Unit): HttpClientEngine =
        WasiClientEngine(WasiEngineConfig().apply(block))
}

public class WasiEngineConfig : HttpClientEngineConfig() {
    public var maxRequestBytes: Int = 16 * 1024 * 1024
    public var maxResponseBytes: Int = 16 * 1024 * 1024
    public var maxMetadataBytes: Int = 64 * 1024
}

private class WasiClientEngine(
    override val config: WasiEngineConfig,
) : HttpClientEngineBase("ktor-wasi"), BufferedHttpClientEngineFastPath {

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val body = getBodyBytes(data.body)
        require(body.size <= config.maxRequestBytes) {
            "HTTP request body is too large: ${body.size} bytes"
        }

        val headers = buildList {
            data.headers.entries().forEach { (name, values) ->
                values.forEach { value -> add(name to value) }
            }
            data.body.headers.entries().forEach { (name, values) ->
                values.forEach { value -> add(name to value) }
            }
            data.body.contentType?.let { type ->
                if (none { it.first.equals(HttpHeaders.ContentType, ignoreCase = true) }) {
                    add(HttpHeaders.ContentType to type.toString())
                }
            }
            data.body.contentLength?.let { length ->
                if (none { it.first.equals(HttpHeaders.ContentLength, ignoreCase = true) }) {
                    add(HttpHeaders.ContentLength to length.toString())
                }
            }
        }
        val metadata = encodeMetadata(
            buildList {
                add(data.method.value)
                add(data.url.toString())
                headers.forEach { (name, value) ->
                    add(name)
                    add(value)
                }
            },
        )
        require(metadata.size <= config.maxMetadataBytes) {
            "HTTP request metadata is too large: ${metadata.size} bytes"
        }

        val request = hostRequestCreate(metadata.size, body.size)
        check(request >= 0) { "Host rejected HTTP request creation" }

        try {
            copyBytesToHost(metadata) { pointer, length ->
                hostRequestMetadataCopy(request, pointer, length)
            }
            copyBytesToHost(body) { pointer, length ->
                hostRequestBodyCopy(request, pointer, length)
            }

            val requestTime = GMTDate()
            check(hostRequestExecute(request) == 0) { "Host HTTP request failed" }

            val responseMetadataLength = hostResponseMetadataLength(request)
            require(responseMetadataLength in 1..config.maxMetadataBytes) {
                "HTTP response metadata is invalid: $responseMetadataLength bytes"
            }
            val responseMetadata = copyBytesFromHost(responseMetadataLength) { pointer, length ->
                hostResponseMetadataCopy(request, pointer, length)
            }
            val responseFields = decodeMetadata(responseMetadata)
            require(responseFields.isNotEmpty() && (responseFields.size - 1) % 2 == 0) {
                "Host returned invalid HTTP response metadata"
            }
            val status = responseFields[0].toInt()
            require(status in 100..599) { "Host returned invalid HTTP status: $status" }

            val responseHeaders = Headers.build {
                responseFields.drop(1).chunked(2).forEach { (name, value) ->
                    append(name, value)
                }
            }

            val responseBodyLength = hostResponseBodyLength(request)
            require(responseBodyLength in 0..config.maxResponseBytes) {
                "HTTP response body is too large: $responseBodyLength bytes"
            }
            val responseBody = copyBytesFromHost(responseBodyLength) { pointer, length ->
                hostResponseBodyCopy(request, pointer, length)
            }

            return HttpResponseData(
                statusCode = HttpStatusCode.fromValue(status),
                requestTime = requestTime,
                headers = responseHeaders,
                version = HttpProtocolVersion.HTTP_1_1,
                body = PreSavedResponseBody(responseBody),
                callContext = callContext(),
            )
        } finally {
            hostRequestClose(request)
        }
    }
}

@OptIn(DelicateCoroutinesApi::class)
private suspend fun getBodyBytes(content: OutgoingContent): ByteArray = when (content) {
    is OutgoingContent.ByteArrayContent -> content.bytes()
    is OutgoingContent.ReadChannelContent -> content.readFrom().readBuffer().readByteArray()
    is OutgoingContent.WriteChannelContent -> GlobalScope.writer(kotlin.coroutines.EmptyCoroutineContext) {
        content.writeTo(channel)
    }.channel.readBuffer().readByteArray()
    is OutgoingContent.ContentWrapper -> getBodyBytes(content.delegate())
    is OutgoingContent.NoContent -> ByteArray(0)
    is OutgoingContent.ProtocolUpgrade -> throw UnsupportedContentTypeException(content)
}

private fun encodeMetadata(fields: List<String>): ByteArray {
    val encoded = fields.map { field ->
        require('\u0000' !in field) { "HTTP metadata must not contain NUL" }
        field.encodeToByteArray()
    }
    val size = encoded.sumOf { it.size + 1 }
    return ByteArray(size).also { output ->
        var offset = 0
        encoded.forEach { field ->
            field.copyInto(output, offset)
            offset += field.size
            output[offset++] = 0
        }
    }
}

private fun decodeMetadata(metadata: ByteArray): List<String> {
    val result = mutableListOf<String>()
    var start = 0
    metadata.forEachIndexed { index, value ->
        if (value.toInt() == 0) {
            result += metadata.copyOfRange(start, index).decodeToString()
            start = index + 1
        }
    }
    require(start == metadata.size) { "unterminated HTTP metadata" }
    return result
}


private inline fun copyBytesToHost(bytes: ByteArray, copy: (pointer: Int, length: Int) -> Int) {
    if (bytes.isEmpty()) {
        check(copy(0, 0) == 0) { "Host rejected empty byte buffer" }
        return
    }
    withScopedMemoryAllocator { allocator ->
        val pointer = allocator.allocate(bytes.size)
        bytes.forEachIndexed { index, value ->
            (pointer + index).storeByte(value)
        }
        check(copy(pointer.address.toInt(), bytes.size) == 0) {
            "Host rejected byte buffer of ${bytes.size} bytes"
        }
    }
}

private inline fun copyBytesFromHost(length: Int, copy: (pointer: Int, length: Int) -> Int): ByteArray {
    if (length == 0) return ByteArray(0)
    return withScopedMemoryAllocator { allocator ->
        val pointer = allocator.allocate(length)
        check(copy(pointer.address.toInt(), length) == 0) {
            "Host failed to copy byte buffer of $length bytes"
        }
        ByteArray(length).also { output ->
            pointer.copyInto(output, 0, length)
        }
    }
}

private fun Pointer.copyInto(output: ByteArray, startIndex: Int, length: Int) {
    var offset = 0
    while (offset + 8 <= length) {
        val value = (this + offset).loadLong()
        val index = startIndex + offset
        output[index] = value.toByte()
        output[index + 1] = (value ushr 8).toByte()
        output[index + 2] = (value ushr 16).toByte()
        output[index + 3] = (value ushr 24).toByte()
        output[index + 4] = (value ushr 32).toByte()
        output[index + 5] = (value ushr 40).toByte()
        output[index + 6] = (value ushr 48).toByte()
        output[index + 7] = (value ushr 56).toByte()
        offset += 8
    }
    while (offset < length) {
        output[startIndex + offset] = (this + offset).loadByte()
        offset++
    }
}

@WasmImport("ktor_wasi", "request_create")
private external fun hostRequestCreate(metadataLength: Int, bodyLength: Int): Int

@WasmImport("ktor_wasi", "request_metadata_copy")
private external fun hostRequestMetadataCopy(request: Int, pointer: Int, length: Int): Int

@WasmImport("ktor_wasi", "request_body_copy")
private external fun hostRequestBodyCopy(request: Int, pointer: Int, length: Int): Int

@WasmImport("ktor_wasi", "request_execute")
private external fun hostRequestExecute(request: Int): Int

@WasmImport("ktor_wasi", "response_metadata_length")
private external fun hostResponseMetadataLength(request: Int): Int

@WasmImport("ktor_wasi", "response_metadata_copy")
private external fun hostResponseMetadataCopy(request: Int, pointer: Int, length: Int): Int

@WasmImport("ktor_wasi", "response_body_length")
private external fun hostResponseBodyLength(request: Int): Int

@WasmImport("ktor_wasi", "response_body_copy")
private external fun hostResponseBodyCopy(request: Int, pointer: Int, length: Int): Int

@WasmImport("ktor_wasi", "request_close")
private external fun hostRequestClose(request: Int): Int

@OptIn(InternalAPI::class, ExperimentalStdlibApi::class)
@EagerInitialization
private val initHook: Unit = engines.append(Wasi)
