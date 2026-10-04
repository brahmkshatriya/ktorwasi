/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.utils.io.locks

import io.ktor.utils.io.InternalAPI

@InternalAPI
public actual typealias SynchronizedObject = Any

@Suppress("NOTHING_TO_INLINE")
@InternalAPI
public actual class ReentrantLock {
    public actual inline fun lock() {}
    public actual inline fun tryLock(): Boolean = true
    public actual inline fun unlock() {}
}

@InternalAPI
public val Lock: ReentrantLock = ReentrantLock()

@Suppress("NOTHING_TO_INLINE")
@InternalAPI
public actual inline fun reentrantLock(): ReentrantLock = Lock

@InternalAPI
public actual inline fun <T> ReentrantLock.withLock(block: () -> T): T = block()

@InternalAPI
public actual inline fun <T> synchronized(lock: SynchronizedObject, block: () -> T): T = block()
