/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.okhttp

import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.utils.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.*
import io.ktor.util.date.*
import kotlinx.coroutines.*
import io.ktor.utils.io.*
import okhttp3.*
import okhttp3.internal.http.HttpMethod
import okio.*
import java.io.*
import java.net.*
import java.util.concurrent.*
import kotlin.coroutines.*

@InternalAPI
@Suppress("KDocMissingDocumentation")
class OkHttpEngine(override val config: OkHttpConfig) : HttpClientEngineBase("ktor-okhttp") {

    override val dispatcher by lazy {
        Dispatchers.fixedThreadPoolDispatcher(
            config.threadsCount,
            "ktor-okhttp-thread-%d"
        )
    }

    override val supportedCapabilities = setOf(HttpTimeout)

    /**
     * Cache that keeps least recently used [OkHttpClient] instances.
     */
    private val clientCache = createLRUCache(::createOkHttpClient, {}, config.clientCacheSize)

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val callContext = callContext()
        val engineRequest = data.convertToOkHttpRequest(callContext)

        val requestEngine = clientCache[data.getCapabilityOrNull(HttpTimeout)]
            ?: error("OkHttpClient can't be constructed")

        return if (data.isUpgradeRequest()) {
            executeWebSocketRequest(requestEngine, engineRequest, callContext)
        } else {
            executeHttpRequest(requestEngine, engineRequest, callContext)
        }
    }

    override fun close() {
        super.close()

        coroutineContext[Job]!!.invokeOnCompletion {
            GlobalScope.launch(dispatcher) {
                // The engine dispatcher and the cache are not closed because:
                // 1. If the engine was created by Ktor it shares common dispatcher and cache.
                // 2. If the engine was created by a user the user is responsible for lifecycle management.
                clientCache.forEach { (_, client) ->
                    client.connectionPool().evictAll()
                }
            }.invokeOnCompletion {
                (dispatcher as Closeable).close()
            }
        }
    }

    private suspend fun executeWebSocketRequest(
        engine: OkHttpClient,
        engineRequest: Request,
        callContext: CoroutineContext
    ): HttpResponseData {
        val requestTime = GMTDate()
        val session = OkHttpWebsocketSession(engine, engineRequest, callContext).apply { start() }

        val originResponse = session.originResponse.await()
        return buildResponseData(originResponse, requestTime, session, callContext)
    }

    private suspend fun executeHttpRequest(
        engine: OkHttpClient,
        engineRequest: Request,
        callContext: CoroutineContext
    ): HttpResponseData {
        val requestTime = GMTDate()
        val response = engine.execute(engineRequest)

        val body = response.body()
        callContext[Job]!!.invokeOnCompletion { body?.close() }

        val responseContent = body?.source()?.toChannel(callContext) ?: ByteReadChannel.Empty
        return buildResponseData(response, requestTime, responseContent, callContext)
    }

    private fun buildResponseData(
        response: Response, requestTime: GMTDate, body: Any, callContext: CoroutineContext
    ): HttpResponseData {
        val status = HttpStatusCode(response.code(), response.message())
        val version = response.protocol().fromOkHttp()
        val headers = response.headers().fromOkHttp()

        return HttpResponseData(status, requestTime, headers, version, body, callContext)
    }

    private companion object {
        /**
         * It's an artificial prototype object to be used to create actual clients and eliminate the following issue:
         * https://github.com/square/okhttp/issues/3372.
         */
        val okHttpClientPrototype: OkHttpClient by lazy {
            OkHttpClient.Builder().build()
        }
    }

    private fun createOkHttpClient(timeoutExtension: HttpTimeout.HttpTimeoutCapabilityConfiguration?): OkHttpClient {
        val builder = okHttpClientPrototype.newBuilder()
        timeoutExtension?.let {
            builder.setupTimeoutAttributes(it)
        }
        return builder.build()
    }
}

private fun BufferedSource.toChannel(context: CoroutineContext): ByteReadChannel = GlobalScope.writer(context) {
    use { source ->
        var lastRead = 0
        while (source.isOpen && context.isActive && lastRead >= 0) {
            channel.write { buffer ->
                lastRead = try {
                    source.read(buffer)
                } catch (cause: Throwable) {
                    throw mapExceptions(cause)
                }
            }
        }
    }
}.channel

private fun mapExceptions(cause: Throwable) = when (cause) {
    is SocketTimeoutException -> HttpSocketTimeoutException()
    else -> cause
}

private fun HttpRequestData.convertToOkHttpRequest(callContext: CoroutineContext): Request {
    val builder = Request.Builder()

    with(builder) {
        url(url.toString())

        mergeHeaders(headers, body) { key, value ->
            addHeader(key, value)
        }

        val bodyBytes = if (HttpMethod.permitsRequestBody(method.value)) {
            body.convertToOkHttpBody(callContext)
        } else null


        method(method.value, bodyBytes)
    }

    return builder.build()
}

internal fun OutgoingContent.convertToOkHttpBody(callContext: CoroutineContext): RequestBody? = when (this) {
    is OutgoingContent.ByteArrayContent -> RequestBody.create(null, bytes())
    is OutgoingContent.ReadChannelContent -> StreamRequestBody(contentLength) { readFrom() }
    is OutgoingContent.WriteChannelContent -> {
        StreamRequestBody(contentLength) { GlobalScope.writer(callContext) { writeTo(channel) }.channel }
    }
    is OutgoingContent.NoContent -> RequestBody.create(null, ByteArray(0))
    else -> throw UnsupportedContentTypeException(this)
}

/**
 * Update [OkHttpClient.Builder] setting timeout configuration taken from
 * [HttpTimeout.HttpTimeoutCapabilityConfiguration].
 */
private fun OkHttpClient.Builder.setupTimeoutAttributes(
    timeoutAttributes: HttpTimeout.HttpTimeoutCapabilityConfiguration
): OkHttpClient.Builder {
    timeoutAttributes.connectTimeoutMillis?.let {
        connectTimeout(convertLongTimeoutToLongWithInfiniteAsZero(it), TimeUnit.MILLISECONDS)
    }
    timeoutAttributes.socketTimeoutMillis?.let {
        readTimeout(convertLongTimeoutToLongWithInfiniteAsZero(it), TimeUnit.MILLISECONDS)
        writeTimeout(convertLongTimeoutToLongWithInfiniteAsZero(it), TimeUnit.MILLISECONDS)
    }
    return this
}
