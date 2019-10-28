/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.features

import io.ktor.utils.io.errors.*

/**
 * HTTP connect timeout exception.
 */
@Suppress("ACTUAL_WITHOUT_EXPECT")
actual class HttpConnectTimeoutException : IOException("Connect timeout has been expired")

/**
 * HTTP socket timeout exception.
 */
@Suppress("ACTUAL_WITHOUT_EXPECT")
actual class HttpSocketTimeoutException : IOException("Socket timeout has been expired")
