#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import xml.etree.ElementTree as ElementTree
from pathlib import Path

GROUP = "dev.brahmkshatriya.ktorwasi"
MODULES = (
    "ktor-client-wasi", "ktor-client-core", "ktor-http", "ktor-http-cio", "ktor-utils",
    "ktor-io", "ktor-events", "ktor-serialization", "ktor-sse",
    "ktor-websocket-serialization", "ktor-websockets",
)
POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def fail(message: str) -> None:
    raise SystemExit(message)


def version_values(node: object) -> list[str]:
    if isinstance(node, str):
        return [node]
    if not isinstance(node, dict):
        return []
    values: list[str] = []
    for key in ("requires", "strictly", "prefers"):
        value = node.get(key)
        if isinstance(value, str):
            values.append(value)
    return values


def validate_dependency(group: str, module: str, versions: list[str], version: str, source: Path) -> None:
    if group == "io.ktor":
        fail(f"{source} contains an unexpected upstream Ktor dependency: {group}:{module}")
    if group != GROUP:
        return
    allowed_modules = set(MODULES) | {f"{name}-wasm-wasi" for name in MODULES}
    if module not in allowed_modules:
        fail(f"{source} contains an unexpected fork module dependency: {group}:{module}")
    if not versions or any(value != version for value in versions):
        fail(f"{source} contains a fork dependency with the wrong version: {group}:{module}:{versions}")


def validate_pom(path: Path, module: str, version: str) -> None:
    if "SNAPSHOT" in path.read_text(encoding="utf-8").upper():
        fail(f"SNAPSHOT reference leaked into {path}")
    try:
        root = ElementTree.parse(path).getroot()
    except ElementTree.ParseError as error:
        fail(f"Invalid POM {path}: {error}")

    def text(node: ElementTree.Element, query: str) -> str:
        found = node.find(query, POM_NS)
        return found.text.strip() if found is not None and found.text else ""

    if text(root, "m:groupId") != GROUP or text(root, "m:artifactId") != module or text(root, "m:version") != version:
        fail(f"Wrong POM coordinates in {path}")

    required = (
        "m:name", "m:description", "m:url", "m:licenses/m:license/m:name",
        "m:licenses/m:license/m:url", "m:developers/m:developer/m:name",
        "m:scm/m:url", "m:scm/m:connection",
    )
    missing = [query for query in required if not text(root, query)]
    if missing:
        fail(f"{path} is missing Maven Central metadata: {missing}")

    for dependency in root.findall(".//m:dependency", POM_NS):
        dependency_version = text(dependency, "m:version")
        validate_dependency(
            text(dependency, "m:groupId"), text(dependency, "m:artifactId"),
            [dependency_version] if dependency_version else [], version, path,
        )


def validate_module_metadata(path: Path, module: str, version: str, target: bool) -> None:
    raw_text = path.read_text(encoding="utf-8")
    if "SNAPSHOT" in raw_text.upper():
        fail(f"SNAPSHOT reference leaked into {path}")
    try:
        data = json.loads(raw_text)
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        fail(f"Invalid Gradle module metadata {path}: {error}")

    component = data.get("component", {})
    expected_component_module = module.removesuffix("-wasm-wasi") if target else module
    if component.get("group") != GROUP or component.get("module") != expected_component_module or component.get("version") != version:
        fail(f"Wrong component coordinates in {path}")

    variants = data.get("variants")
    if not isinstance(variants, list) or not variants:
        fail(f"No variants in {path}")
    if not any(
        isinstance(variant, dict)
        and isinstance(variant.get("attributes"), dict)
        and variant["attributes"].get("org.jetbrains.kotlin.platform.type") == "wasm"
        and variant["attributes"].get("org.jetbrains.kotlin.wasm.target") == "wasi"
        for variant in variants
    ):
        fail(f"No Wasm/WASI variant in {path}")

    expected_targets = {f"{name}-wasm-wasi" for name in MODULES}
    for variant in variants:
        if not isinstance(variant, dict):
            continue
        available_at = variant.get("available-at")
        if isinstance(available_at, dict):
            available_group = str(available_at.get("group", ""))
            available_module = str(available_at.get("module", ""))
            available_version = str(available_at.get("version", ""))
            if available_group == "io.ktor":
                fail(f"{path} contains an upstream Ktor available-at coordinate")
            if available_group == GROUP and (available_module not in expected_targets or available_version != version):
                fail(f"{path} contains an invalid fork available-at coordinate")
        for key in ("dependencies", "dependencyConstraints"):
            dependencies = variant.get(key, [])
            if not isinstance(dependencies, list):
                continue
            for dependency in dependencies:
                if isinstance(dependency, dict):
                    validate_dependency(
                        str(dependency.get("group", "")), str(dependency.get("module", "")),
                        version_values(dependency.get("version")), version, path,
                    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Verify the isolated Ktor Wasm/WASI Maven repository.")
    parser.add_argument("repository", type=Path)
    parser.add_argument("version")
    args = parser.parse_args()

    repository = args.repository.expanduser().resolve()
    version = args.version
    if not repository.is_dir():
        fail(f"Repository does not exist: {repository}")
    if "SNAPSHOT" in version.upper():
        fail(f"Release repository version must not be a SNAPSHOT: {version}")

    group_root = repository.joinpath(*GROUP.split("."))
    if not group_root.is_dir():
        fail(f"Fork group is missing: {group_root}")
    group_prefix = Path(*GROUP.split("."))
    for path in repository.rglob("*"):
        if path.is_file() and not path.relative_to(repository).is_relative_to(group_prefix):
            fail(f"Unexpected file outside the fork group: {path}")

    expected = set(MODULES) | {f"{module}-wasm-wasi" for module in MODULES}
    actual = {path.name for path in group_root.iterdir() if path.is_dir()}
    if actual != expected:
        fail(f"Published artifact set mismatch. Missing={sorted(expected - actual)}, extra={sorted(actual - expected)}")

    checked_files = 0
    for module in sorted(expected):
        target = module.endswith("-wasm-wasi")
        version_dir = group_root / module / version
        if not version_dir.is_dir():
            fail(f"Missing version directory: {version_dir}")
        other_versions = [path.name for path in (group_root / module).iterdir() if path.is_dir() and path.name != version]
        if other_versions:
            fail(f"Unexpected versions for {module}: {other_versions}")

        stem = f"{module}-{version}"
        required = {
            f"{stem}.pom", f"{stem}.module", f"{stem}-sources.jar", f"{stem}-javadoc.jar",
            f"{stem}.klib" if target else f"{stem}.jar",
        }
        present = {path.name for path in version_dir.iterdir() if path.is_file()}
        missing = sorted(required - present)
        if missing:
            fail(f"Missing required artifacts for {module}: {missing}")

        for path in version_dir.iterdir():
            if path.is_file():
                checked_files += 1
                if path.stat().st_size <= 0:
                    fail(f"Empty artifact: {path}")
                if "SNAPSHOT" in path.name.upper():
                    fail(f"SNAPSHOT artifact leaked into repository: {path}")

        validate_pom(version_dir / f"{stem}.pom", module, version)
        validate_module_metadata(version_dir / f"{stem}.module", module, version, target)

    print(f"Verified {len(MODULES)} root publications + {len(MODULES)} Wasm/WASI publications ({checked_files} files) at {GROUP}:{version}")


if __name__ == "__main__":
    main()
