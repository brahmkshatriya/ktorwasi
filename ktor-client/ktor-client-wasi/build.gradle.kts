/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

description = "WASI host-capability backend for Ktor HTTP client"

plugins {
    id("ktorbuild.project.library")
}

kotlin {
    sourceSets {
        wasmWasiMain.dependencies {
            api(projects.ktorClientCore)
        }
    }
}
