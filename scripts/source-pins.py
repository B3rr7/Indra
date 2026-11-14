#!/usr/bin/env python3
"""Regenerate `pins/sources.json` from the vendored trees in `indra/jni`.

Run this **only** after an intentional change to vendored code (applying an upstream patch that is
part of the port, or a new upstream version). The gate test `SourcePinGateTest` fails whenever a
pinned tree stops matching, which is the point: a submodule update, a stray edit or a half-applied
patch becomes a test failure instead of a device build that behaves differently from the source.

The digest must stay byte-identical to `treeDigest`/`fileDigest` in
`core/src/main/java/org/indra/android/core/system/SourcePin.kt`:

    for each file, sorted by relative path (posix separators):
        sha256.update(path bytes)
        sha256.update(0x00)
        sha256.update(utf8(decimal byte length))
        sha256.update(0x00)
        sha256.update(contents)

`SourcePinGateTest` verifies this file against the Kotlin implementation, so if the two ever
disagree the gate fails rather than both being quietly wrong.

Usage:
    python3 scripts/source-pins.py           # rewrite pins/sources.json
    python3 scripts/source-pins.py --check   # exit 1 when a tree drifted (CI-friendly)
"""

from __future__ import annotations

import hashlib
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PIN_FILE = os.path.join(ROOT, "pins", "sources.json")

# Trees small enough to hash whole, and the ones Indra patches most (see AGENTS.md's patch table).
TREES = [
    "hydra",
    "hping3",
    "network-radar",
    "arpspoof",
    "ettercap-ng",
    "libncp",
    "thomson",
    "aircrack-ng",
]

# Per-file pins for the trees too large to hash whole but which still carry a port change.
FILES = [
    ("nmap-makefile", "nmap/Android.mk"),
    ("icu-uspoof", "icu4c/i18n/uspoof.cpp"),
    ("jni-build-sh", "build.sh"),
    ("jni-application-mk", "Application.mk"),
    ("jni-build-config", "build-config.mk"),
    ("jni-crypto-config", "Crypto-config.mk"),
    ("jni-droid-header", "include/droid.h"),
]

NOTE = (
    "Vendored trees Indra patches (see the patch table in AGENTS.md). Regenerate with "
    "scripts/source-pins.py after an intentional edit; SourcePinGateTest fails when a tree drifts."
)


def tree_digest(root: str) -> str:
    entries = []
    for dirpath, _, filenames in os.walk(root):
        for name in filenames:
            full = os.path.join(dirpath, name)
            entries.append((os.path.relpath(full, root).replace(os.sep, "/"), full))
    digest = hashlib.sha256()
    for relative, full in sorted(entries, key=lambda entry: entry[0]):
        with open(full, "rb") as handle:
            data = handle.read()
        digest.update(relative.encode())
        digest.update(b"\x00")
        digest.update(str(len(data)).encode())
        digest.update(b"\x00")
        digest.update(data)
    return digest.hexdigest()


def file_digest(path: str) -> str:
    with open(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


def build() -> dict:
    jni = os.path.join(ROOT, "indra", "jni")
    missing = [name for name in TREES if not os.path.isdir(os.path.join(jni, name))]
    if missing:
        sys.exit(f"indra/jni is not initialised (missing {', '.join(missing)}); run git submodule update --init")
    trees = [{"name": name, "path": f"indra/jni/{name}", "sha256": tree_digest(os.path.join(jni, name))} for name in TREES]
    files = []
    for name, relative in FILES:
        path = os.path.join(jni, relative)
        if os.path.exists(path):
            files.append({"name": name, "path": f"indra/jni/{relative}", "sha256": file_digest(path)})
    return {"note": NOTE, "trees": trees, "files": files}


def main() -> int:
    generated = build()
    if "--check" in sys.argv:
        if not os.path.exists(PIN_FILE):
            print(f"{PIN_FILE} does not exist")
            return 1
        with open(PIN_FILE) as handle:
            pinned = json.load(handle)
        problems = []
        for pin in pinned["trees"]:
            actual = tree_digest(os.path.join(ROOT, pin["path"]))
            if actual != pin["sha256"]:
                problems.append(f"{pin['name']}: pinned {pin['sha256'][:12]}…, found {actual[:12]}…")
        for pin in pinned.get("files", []):
            actual = file_digest(os.path.join(ROOT, pin["path"]))
            if actual != pin["sha256"]:
                problems.append(f"{pin['name']}: pinned {pin['sha256'][:12]}…, found {actual[:12]}…")
        if problems:
            print("\n".join(problems))
            return 1
        print(f"{len(pinned['trees']) + len(pinned.get('files', []))} pins verified")
        return 0

    os.makedirs(os.path.dirname(PIN_FILE), exist_ok=True)
    with open(PIN_FILE, "w") as handle:
        json.dump(generated, handle, indent=2)
        handle.write("\n")
    print(f"wrote {PIN_FILE}: {len(generated['trees'])} trees, {len(generated['files'])} files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
