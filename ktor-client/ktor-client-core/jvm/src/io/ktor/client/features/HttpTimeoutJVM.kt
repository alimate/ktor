/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.features

import io.ktor.util.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import java.net.*
import kotlin.coroutines.*

@Suppress("ACTUAL_WITHOUT_EXPECT")
actual class HttpConnectTimeoutException : ConnectException("Connect timeout has been expired")

@Suppress("ACTUAL_WITHOUT_EXPECT")
actual class HttpSocketTimeoutException : SocketTimeoutException("Socket timeout has been expired")

/**
 * Returns [ByteReadChannel] with [ByteChannel.close] handler that returns [HttpSocketTimeoutException] instead of
 * [SocketTimeoutException].
 */
@InternalAPI
fun CoroutineScope.mapEngineExceptions(input: ByteReadChannel): ByteReadChannel {
    val replacementChannel = ByteChannelWithMappedExceptions()

    writer(coroutineContext, replacementChannel) {
        try {
            input.joinTo(replacementChannel, closeOnEnd = true)
        } catch (cause: Throwable) {
            input.cancel(cause)
        }
    }

    return replacementChannel
}

/**
 * Returns [ByteWriteChannel] with [ByteChannel.close] handler that returns [HttpSocketTimeoutException] instead of
 * [SocketTimeoutException].
 */
@InternalAPI
fun CoroutineScope.mapEngineExceptions(input: ByteWriteChannel): ByteWriteChannel {
    val replacementChannel = ByteChannelWithMappedExceptions()

    writer(coroutineContext, replacementChannel) {
        try {
            replacementChannel.joinTo(input, closeOnEnd = true)
        } catch (cause: Throwable) {
            replacementChannel.close(cause)
        }
    }

    return replacementChannel
}

/**
 * Creates [ByteChannel] that maps close exceptions (close the channel with [HttpSocketTimeoutException] if asked to
 * close it with [SocketTimeoutException]).
 */
private fun ByteChannelWithMappedExceptions() = ByteChannel { cause ->
    when (cause?.rootCause) {
        is SocketTimeoutException -> HttpSocketTimeoutException()
        else -> cause
    }
}
