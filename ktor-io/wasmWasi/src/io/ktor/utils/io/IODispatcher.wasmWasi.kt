/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.utils.io

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Embedded Wasm/WASI is single-threaded and the host capability imports used by
 * the WASI client engine are synchronous. Running inline keeps a Ktor request
 * inside the exported guest call instead of handing it to the WASI scheduler.
 */
public actual fun ioDispatcher(): CoroutineDispatcher = Dispatchers.Unconfined
