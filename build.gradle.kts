/*
 * Copyright 2014-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

plugins {
    id("ktorbuild.root")
}

logger.lifecycle("Build version: ${project.version}")
logger.lifecycle("Kotlin version: ${libs.versions.kotlin.get()}")

val ktorWasmWasiPublicationProjects = listOf(
    ":ktor-client-wasi",
    ":ktor-client-core",
    ":ktor-http",
    ":ktor-http-cio",
    ":ktor-utils",
    ":ktor-io",
    ":ktor-events",
    ":ktor-serialization",
    ":ktor-sse",
    ":ktor-websocket-serialization",
    ":ktor-websockets",
)

tasks.register("publishKtorWasmWasiToMavenRepository") {
    group = "publishing"
    description = "Publishes exactly the Ktor WASI fork root and wasmWasi publications to the isolated repository."

    dependsOn(ktorWasmWasiPublicationProjects.flatMap { projectPath ->
        listOf(
            "$projectPath:publishKotlinMultiplatformPublicationToKtorWasmWasiRepository",
            "$projectPath:publishWasmWasiPublicationToKtorWasmWasiRepository",
        )
    })
}
