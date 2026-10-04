/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.call

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.engine.PreSavedResponseBody
import io.ktor.client.plugins.RESPONSE_BODY_SAVED
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.date.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.*
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlin.coroutines.CoroutineContext

/**
 * Saves the entire content of this [HttpClientCall] to memory and returns a new [HttpClientCall]
 * with the content cached in memory.
 * This can be particularly useful for caching, debugging,
 * or processing responses without relying on the original network stream.
 *
 * By caching the content, this function simplifies the management of the [HttpResponse] lifecycle.
 * It releases the network connection and other resources associated with the original [HttpResponse],
 * ensuring they are no longer required to be explicitly closed.
 *
 * This behavior is automatically applied to non-streaming [HttpResponse] instances.
 * For streaming responses, this function allows you to convert them into a memory-based representation.
 *
 * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.client.call.save)
 *
 * @return A new [HttpClientCall] instance with all its content stored in memory.
 */
@OptIn(InternalAPI::class)
public suspend fun HttpClientCall.save(): HttpClientCall {
    if (this is SavedHttpCall) return this

    val responseBody = (attributes.getOrNull(HttpClientCall.Companion.CustomResponse) as? PreSavedResponseBody)?.bytes
        ?: response.rawContent.readBuffer().readByteArray()
    return SavedHttpCall(client, request, response, responseBody)
}

internal class SavedHttpCall private constructor(
    client: HttpClient,
    private val responseBody: ByteArray,
    private val fastDefaultTransforms: Boolean,
) : HttpClientCall(client) {
    constructor(client: HttpClient, request: HttpRequest, response: HttpResponse, responseBody: ByteArray) :
        this(client, responseBody, false) {
        this.request = SavedHttpRequest(this, request)
        this.response = SavedHttpResponse(this, responseBody, response)
        attributes.remove(HttpClientCall.Companion.CustomResponse)
        checkContentLength(response.contentLength(), responseBody.size.toLong(), request.method)
    }

    @OptIn(InternalAPI::class)
    constructor(client: HttpClient, requestData: HttpRequestData, responseData: HttpResponseData, responseBody: ByteArray) :
        this(client, responseBody, true) {
        requestData.attributes.put(RESPONSE_BODY_SAVED, Unit)
        this.request = DefaultHttpRequest(this, requestData)
        this.response = SavedHttpResponse(this, responseBody, responseData)
        checkContentLength(
            responseData.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
            responseBody.size.toLong(),
            requestData.method,
        )
    }

    override val allowDoubleReceive: Boolean = true

    internal fun tryFastDefaultBody(info: TypeInfo): Any? {
        if (!fastDefaultTransforms) return NO_FAST_BODY
        return when (info.type) {
            Unit::class -> Unit
            Int::class -> responseBody.decodeToString().toInt()
            HttpStatusCode::class -> response.status
            ByteArray::class -> responseBody.copyOf()
            Source::class -> Buffer().apply { write(responseBody) }
            ByteReadChannel::class -> ByteReadChannel(responseBody)
            else -> NO_FAST_BODY
        }
    }
}

internal object NO_FAST_BODY

internal class SavedHttpRequest(
    override val call: SavedHttpCall,
    origin: HttpRequest
) : HttpRequest by origin

internal class SavedHttpResponse private constructor(
    override val call: SavedHttpCall,
    private val body: ByteArray,
    override val status: HttpStatusCode,
    override val version: HttpProtocolVersion,
    override val requestTime: GMTDate,
    override val responseTime: GMTDate,
    override val headers: Headers,
    override val coroutineContext: CoroutineContext,
) : HttpResponse() {
    constructor(call: SavedHttpCall, body: ByteArray, origin: HttpResponse) : this(
        call, body, origin.status, origin.version, origin.requestTime, origin.responseTime, origin.headers, origin.coroutineContext
    )

    @OptIn(InternalAPI::class)
    constructor(call: SavedHttpCall, body: ByteArray, responseData: HttpResponseData) : this(
        call, body, responseData.statusCode, responseData.version, responseData.requestTime, responseData.responseTime,
        responseData.headers, responseData.callContext
    )

    @OptIn(InternalAPI::class)
    override val rawContent: ByteReadChannel get() = ByteReadChannel(body)
}
