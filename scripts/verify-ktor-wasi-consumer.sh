#!/usr/bin/env bash
set -euo pipefail

repository="${1:?Usage: verify-ktor-wasi-consumer.sh REPOSITORY VERSION}"
version="${2:?Usage: verify-ktor-wasi-consumer.sh REPOSITORY VERSION}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repository="$(realpath "$repository")"

if [[ ! -d "$repository" ]]; then
    echo "Repository does not exist: $repository" >&2
    exit 1
fi

consumer="$(mktemp -d)"
trap 'rm -rf "$consumer"' EXIT
mkdir -p "$consumer/src/wasmWasiMain/kotlin"

cat > "$consumer/settings.gradle.kts" <<EOF
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("${repository}") }
        mavenCentral()
    }
}

rootProject.name = "ktor-wasi-consumer-check"
EOF

cat > "$consumer/build.gradle.kts" <<EOF
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform") version "2.3.21"
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
    }
    sourceSets {
        wasmWasiMain.dependencies {
            implementation("dev.brahmkshatriya.ktorwasi:ktor-client-wasi:${version}")
        }
    }
}
EOF

cat > "$consumer/src/wasmWasiMain/kotlin/Main.kt" <<'EOF'
import io.ktor.client.HttpClient
import io.ktor.client.engine.wasi.Wasi

fun createClient(): HttpClient = HttpClient(Wasi)
EOF

"$root/gradlew" \
    --no-daemon \
    --stacktrace \
    --no-configuration-cache \
    -p "$consumer" \
    compileKotlinWasmWasi

echo "External Wasm/WASI consumer compiled dev.brahmkshatriya.ktorwasi:ktor-client-wasi:$version"
