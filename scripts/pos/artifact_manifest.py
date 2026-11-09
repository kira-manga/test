#!/usr/bin/env python3
"""Create a SHA256 manifest for a POS native packaging payload.

The payload is deliberately limited to built packages, copied diagnostics and
plain text notes. This script rejects likely credentials, signing material and
native databases instead of silently uploading them.
"""

from __future__ import annotations

import argparse
import datetime as _datetime
import hashlib
import json
import os
import sys

SCHEMA = "ukkera-pos-native-artifact-manifest/v1"
MANIFEST_NAME = "manifest.json"
SUMS_NAME = "SHA256SUMS"

FORBIDDEN_NAMES = {
    ".DS_Store",
    ".env",
    ".git-credentials",
    ".netrc",
    "Thumbs.db",
    "id_ed25519",
    "id_rsa",
    "known_hosts",
    "local.properties",
}

FORBIDDEN_SUFFIXES = (
    ".cer",
    ".crt",
    ".csr",
    ".db",
    ".db-journal",
    ".db-shm",
    ".db-wal",
    ".jks",
    ".key",
    ".keystore",
    ".mobileprovision",
    ".p12",
    ".pem",
    ".pfx",
    ".provisionprofile",
    ".realm",
    ".sqlite",
    ".sqlite-shm",
    ".sqlite-wal",
    ".sqlite3",
)

FORBIDDEN_DIRECTORIES = {
    ".git",
    ".gradle",
    ".konan",
    ".ssh",
    "DerivedData",
    "Keychains",
    "caches",
    "node_modules",
}


def fail(message: str) -> "None":
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(2)


def check_path(relative: str, is_directory: bool) -> None:
    parts = relative.split("/")
    name = parts[-1]
    if is_directory:
        if name in FORBIDDEN_DIRECTORIES:
            fail(f"forbidden directory in artifact payload: {relative}")
        return
    lowered = name.lower()
    if name in FORBIDDEN_NAMES or lowered.startswith(".env."):
        fail(f"forbidden credential/config file in artifact payload: {relative}")
    if lowered.endswith(FORBIDDEN_SUFFIXES):
        fail(f"forbidden key/database file in artifact payload: {relative}")


def sha256_file(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def collect(root: str):
    files = []
    symlinks = []
    total_bytes = 0
    for directory, dirnames, filenames in os.walk(root, followlinks=False):
        dirnames.sort()
        for dirname in list(dirnames):
            full = os.path.join(directory, dirname)
            relative = os.path.relpath(full, root).replace(os.sep, "/")
            check_path(relative, True)
        for filename in sorted(filenames):
            full = os.path.join(directory, filename)
            relative = os.path.relpath(full, root).replace(os.sep, "/")
            if relative in (MANIFEST_NAME, SUMS_NAME):
                continue
            check_path(relative, False)
            if os.path.islink(full):
                target = os.readlink(full)
                if os.path.isabs(target):
                    fail(f"absolute symlink in artifact payload: {relative} -> {target}")
                symlinks.append({"path": relative, "target": target})
                continue
            if not os.path.isfile(full):
                fail(f"non-regular file in artifact payload: {relative}")
            size = os.path.getsize(full)
            total_bytes += size
            files.append(
                {
                    "path": relative,
                    "bytes": size,
                    "sha256": sha256_file(full),
                }
            )
    return files, symlinks, total_bytes


def write_manifest(root: str, label: str, metadata: dict) -> None:
    files, symlinks, total_bytes = collect(root)
    manifest = {
        "schema": SCHEMA,
        "label": label,
        "created_utc": _datetime.datetime.now(_datetime.timezone.utc).isoformat(),
        "metadata": metadata,
        "file_count": len(files),
        "total_bytes": total_bytes,
        "files": files,
        "symlinks": symlinks,
    }
    manifest_path = os.path.join(root, MANIFEST_NAME)
    with open(manifest_path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(manifest, handle, indent=2, sort_keys=True)
        handle.write("\n")

    entries = [(item["sha256"], item["path"]) for item in files]
    entries.append((sha256_file(manifest_path), MANIFEST_NAME))
    entries.sort(key=lambda item: item[1])
    sums_path = os.path.join(root, SUMS_NAME)
    with open(sums_path, "w", encoding="utf-8", newline="\n") as handle:
        for digest, relative in entries:
            handle.write(f"{digest}  {relative}\n")

    print(f"manifest={manifest_path}")
    print(f"sums={sums_path}")
    print(f"files={len(files)} total_bytes={total_bytes}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, help="artifact payload directory")
    parser.add_argument("--label", required=True, help="human-readable artifact label")
    parser.add_argument(
        "--metadata",
        action="append",
        default=[],
        metavar="KEY=VALUE",
        help="metadata value recorded in manifest.json (repeatable)",
    )
    arguments = parser.parse_args()

    root = os.path.abspath(arguments.root)
    if not os.path.isdir(root):
        fail(f"artifact payload directory does not exist: {root}")
    metadata = {}
    for item in arguments.metadata:
        if "=" not in item:
            fail(f"metadata must be KEY=VALUE: {item}")
        key, value = item.split("=", 1)
        metadata[key] = value
    write_manifest(root, arguments.label, metadata)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
