/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.util.logging

@Suppress("FunctionName")
public actual fun KtorSimpleLogger(name: String): Logger = object : Logger {
    override val level: LogLevel = LogLevel.INFO

    private fun emit(level: String, message: String, cause: Throwable? = null) {
        val suffix = cause?.let { ", cause: $it" }.orEmpty()
        println("[$level] $name: $message$suffix")
    }

    override fun error(message: String) = emit("ERROR", message)
    override fun error(message: String, cause: Throwable) = emit("ERROR", message, cause)
    override fun warn(message: String) = emit("WARN", message)
    override fun warn(message: String, cause: Throwable) = emit("WARN", message, cause)
    override fun info(message: String) = emit("INFO", message)
    override fun info(message: String, cause: Throwable) = emit("INFO", message, cause)
    override fun debug(message: String) = emit("DEBUG", message)
    override fun debug(message: String, cause: Throwable) = emit("DEBUG", message, cause)
    override fun trace(message: String) = emit("TRACE", message)
    override fun trace(message: String, cause: Throwable) = emit("TRACE", message, cause)
}
