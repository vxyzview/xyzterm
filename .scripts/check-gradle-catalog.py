#!/usr/bin/env python3
"""
Pre-commit hook: verify gradle/libs.versions.toml is internally consistent
and that every libs.X reference in build.gradle.kts / settings.gradle.kts /
*.gradle / *.kts files resolves to an entry in the catalog.

Exit non-zero if anything is broken — surfaces dependency-metadata bugs
locally instead of waiting for CI to flag them.
"""
import re
import subprocess
import sys
from pathlib import Path

TOML = Path("gradle/libs.versions.toml")
if not TOML.exists():
    sys.exit(0)  # not a gradle project


def section(text, name):
    m = re.search(rf"\[{name}\]\s*\n(.+?)(?=\n\[|\Z)", text, re.S)
    return m.group(1) if m else ""


text = TOML.read_text()
libs_text = section(text, "libraries")
plugins_text = section(text, "plugins")
versions_text = section(text, "versions")

# 1) version refs vs defined versions
defined_versions = {
    m.group(1) for m in re.finditer(r"^(\w[\w-]*)\s*=", versions_text, re.M)
}
all_refs = set(re.findall(r'version\.ref\s*=\s*"([^"]+)"', libs_text)) | set(
    re.findall(r'version\.ref\s*=\s*"([^"]+)"', plugins_text)
)
missing_versions = all_refs - defined_versions
unused_versions = defined_versions - all_refs

# 2) libs.X references vs catalog entries
catalog_libs = {
    m.group(1).replace("-", ".")
    for m in re.finditer(r'^(\w[\w-]*)\s*=\s*\{[^}]*module\s*=\s*"([^"]+)"', libs_text, re.M)
}
catalog_plugins = {
    m.group(1).replace("-", ".")
    for m in re.finditer(r'^(\w[\w-]*)\s*=\s*\{[^}]*id\s*=\s*"([^"]+)"', plugins_text, re.M)
}

result = subprocess.run(
    [
        "grep",
        "-rEno",
        r"libs\.[A-Za-z0-9_.]+",
        ".",
        "--include=*.kts",
        "--include=*.gradle",
        "--exclude-dir=.git",
        "--exclude-dir=.gradle",
        "--exclude-dir=build",
    ],
    capture_output=True,
    text=True,
)

usage_re = re.compile(r"libs\.([A-Za-z0-9_.]+)")
chain_re = re.compile(r"\.(get|invoke)(?:\b|$)")

dangling_refs = []
for line in result.stdout.splitlines():
    parts = line.split(":", 2)
    if len(parts) < 3:
        continue
    m = usage_re.search(parts[2])
    if not m:
        continue
    raw = m.group(1)
    bare = chain_re.split(raw)[0]
    is_plugin = bare.startswith("plugins.")
    lookup = bare[len("plugins.") :] if is_plugin else bare
    catalog = catalog_plugins if is_plugin else catalog_libs
    if lookup not in catalog:
        dangling_refs.append((parts[0], parts[1], raw))

failed = False
if missing_versions:
    print("MISSING versions (referenced but not defined):")
    for v in sorted(missing_versions):
        print(f"  {v}")
    failed = True
if unused_versions:
    print("UNUSED versions (defined but never referenced):")
    for v in sorted(unused_versions):
        print(f"  {v}")
    failed = True
if dangling_refs:
    print("DANGLING libs.X refs (no matching catalog entry):")
    for p, ln, ref in dangling_refs:
        print(f"  {p}:{ln} libs.{ref}")
    failed = True

if failed:
    print("\nFix the catalog or the build script. Commit aborted.")
    sys.exit(1)

print("OK — gradle/libs.versions.toml is consistent.")