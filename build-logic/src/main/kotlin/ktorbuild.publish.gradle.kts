/*
 * Copyright 2014-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import ktorbuild.*
import ktorbuild.internal.*
import ktorbuild.internal.publish.*
import org.gradle.api.Project
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.kotlin.dsl.*
import org.gradle.plugins.signing.Sign

plugins {
    id("com.vanniktech.maven.publish")
    id("signing")
}

addProjectTag(ProjectTag.Published)

val ktorWasmWasiPublicationMode = providers.gradleProperty("ktorbuild.wasmWasiPublication")
    .map(String::toBoolean)
    .orElse(false)
val ktorWasmWasiPublicationModules = setOf(
    "ktor-client-wasi",
    "ktor-client-core",
    "ktor-http",
    "ktor-http-cio",
    "ktor-utils",
    "ktor-io",
    "ktor-events",
    "ktor-serialization",
    "ktor-sse",
    "ktor-websocket-serialization",
    "ktor-websockets",
)
val isKtorWasmWasiPublicationModule = ktorWasmWasiPublicationMode.get() && name in ktorWasmWasiPublicationModules

if (isKtorWasmWasiPublicationModule) {
    group = providers.gradleProperty("ktorbuild.wasmWasiPublicationGroup")
        .orElse("dev.brahmkshatriya.ktorwasi")
        .get()

    tasks.matching { it.name == "dokkaGeneratePublicationHtml" }.configureEach {
        onlyIf("Ktor WASI publication uses an empty javadoc jar") { false }
    }
}

mavenPublishing {
    if (!ktorWasmWasiPublicationMode.get() && shouldPublishToMavenCentral()) {
        publishToMavenCentral(automaticRelease = true)
    }
    if (!ktorWasmWasiPublicationMode.get()) configureSigning(this)

    pom {
        name = project.name
        description = project.description.orEmpty()
            .ifEmpty { "Ktor is a framework for quickly creating web applications in Kotlin with minimal effort." }
        val scmUrl = if (isKtorWasmWasiPublicationModule) {
            providers.gradleProperty("ktorbuild.wasmWasiScmUrl")
                .orElse("https://github.com/ktorio/ktor")
                .get()
        } else {
            "https://github.com/ktorio/ktor"
        }
        val scmGitUrl = if (scmUrl.endsWith(".git")) scmUrl else "$scmUrl.git"

        url = scmUrl
        licenses {
            license {
                name = "The Apache Software License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "JetBrains"
                name = "Jetbrains Team"
                organization = "JetBrains"
                organizationUrl = "https://www.jetbrains.com"
            }
        }
        scm {
            url = scmGitUrl
            connection = "scm:git:$scmGitUrl"
            developerConnection = "scm:git:$scmGitUrl"
        }
    }
}

publishing {
    repositories {
        if (isKtorWasmWasiPublicationModule) {
            val repositoryPath = providers.gradleProperty("ktorbuild.wasmWasiRepository")
                .orElse(rootProject.layout.projectDirectory.dir("release/maven-repository").asFile.absolutePath)
                .get()
            maven {
                name = "KtorWasmWasi"
                url = project.uri(repositoryPath)
            }
        } else {
            addTargetRepositoryIfConfigured()
        }
        mavenLocal()
    }
}

registerCommonPublishTask()

plugins.withId("ktorbuild.kmp") {
    // Don't allow cross-compilation on CI, but it is okay to use it locally
    if (ktorBuild.isCI.get()) {
        tasks.withType<AbstractPublishToMaven>().configureEach {
            val os = ktorBuild.os.get()
            // Workaround for https://github.com/gradle/gradle/issues/22641
            val predicate = provider { isAvailableForPublication(publication.name, os) }
            onlyIf("Available for publication on $os") { predicate.get() }
        }
    }

    registerTargetsPublishTasks(ktorBuild.targets)
}

private fun Project.configureSigning(mavenPublishing: MavenPublishBaseExtension) {
    extra["signing.gnupg.keyName"] = (System.getenv("SIGN_KEY_ID") ?: return)
    extra["signing.gnupg.passphrase"] = (System.getenv("SIGN_KEY_PASSPHRASE") ?: return)

    mavenPublishing.signAllPublications()
    signing.useGpgCmd()

    // Workaround for https://github.com/gradle/gradle/issues/12167
    tasks.withType<Sign>().configureEach {
        withLimitedParallelism("gpg-agent", maxParallelTasks = 1)
    }
}
