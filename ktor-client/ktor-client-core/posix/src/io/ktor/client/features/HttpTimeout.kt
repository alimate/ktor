/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.features

@Suppress("ACTUAL_WITHOUT_EXPECT")
actual class HttpConnectTimeoutException : Throwable("Connect timeout has been expired")

@Suppress("ACTUAL_WITHOUT_EXPECT")
actual class HttpSocketTimeoutException : Throwable("Socket timeout has been expired")