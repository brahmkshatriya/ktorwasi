/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine

import io.ktor.utils.io.InternalAPI

/**
 * Marks a response body that an engine has already fully buffered in memory.
 *
 * This lets the default SaveBody path reuse the engine buffer instead of
 * consuming a ByteReadChannel into a second byte array. It is an engine/core
 * optimization detail, not a general response-body type for applications.
 */
@InternalAPI
public class PreSavedResponseBody(public val bytes: ByteArray)
