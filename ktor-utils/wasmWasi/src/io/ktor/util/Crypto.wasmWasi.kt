/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.util

import kotlin.random.Random

public actual suspend fun generateNonceSuspend(length: Int): String = generateNonceBlocking(length)

public actual fun generateNonceBlocking(length: Int): String {
    val bytes = Random.Default.nextBytes(length / 2 + 1)
    return bytes.toHexString().substring(0, length)
}

public actual fun sha1(bytes: ByteArray): ByteArray = Sha1().digest(bytes)
public actual fun sha256(bytes: ByteArray): ByteArray = Sha256().digest(bytes)

@Suppress("FunctionName")
public actual fun Digest(name: String): Digest = object : Digest {
    private val state = mutableListOf<ByteArray>()

    override fun plusAssign(bytes: ByteArray) {
        state += bytes.copyOf()
    }

    override fun reset() {
        state.clear()
    }

    override suspend fun build(): ByteArray {
        val bytes = state.fold(ByteArray(0)) { acc, part -> acc + part }
        return when (name.uppercase().replace("_", "-")) {
            "SHA-1", "SHA1" -> sha1(bytes)
            "SHA-256", "SHA256" -> sha256(bytes)
            else -> error("Digest $name is not supported on wasmWasi")
        }
    }
}
