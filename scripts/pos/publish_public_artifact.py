#!/usr/bin/env python3
"""Publish a native package candidate only after strict sanitization and hygiene.

Candidate diagnostics remain local on failure. The public destination is created
atomically and must not exist; workflow uploads must depend on this step's success.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile

import artifact_manifest
import sanitize_diagnostics


NOTES = {"README-ARTIFACTS.txt", "SOURCE-REVISION.txt"}
# .cer is only the PUBLIC test-signing certificate the owner trusts before installing an MSIX.
PACKAGES = (".apk", ".dmg", ".msi", ".msix", ".cer", ".app.zip", ".xcarchive.zip")


def check_regular_tree(root: Path) -> None:
    for directory, dirs, files in os.walk(root, followlinks=False):
        for name in dirs + files:
            mode = (Path(directory) / name).lstat().st_mode
            if not (stat.S_ISDIR(mode) or stat.S_ISREG(mode)):
                raise ValueError("symlink or special file in candidate")


def redact_metadata(value):
    if isinstance(value, str):
        return sanitize_diagnostics.redact(value, os.path.expanduser("~"))
    if isinstance(value, dict):
        return {redact_metadata(key): redact_metadata(item) for key, item in value.items()}
    if isinstance(value, list):
        return [redact_metadata(item) for item in value]
    return value


def publish(candidate: Path, output: Path) -> None:
    if candidate.is_symlink() or not candidate.is_dir():
        raise ValueError("candidate must be a regular directory")
    if output.exists() or output.is_symlink():
        raise ValueError("public destination already exists")
    candidate = candidate.resolve()
    output = output.absolute()
    if candidate == output or candidate in output.parents or output in candidate.parents:
        raise ValueError("candidate and public destination must be separate")
    # Do not follow symlinks or special files while copying. Native bundles are zipped.
    check_regular_tree(candidate)
    artifact_manifest.collect(str(candidate))
    for entry in candidate.iterdir():
        if entry.name == "diagnostics" and entry.is_dir():
            continue
        if entry.is_file() and (entry.name in NOTES | {"manifest.json", "SHA256SUMS"}
                                or entry.name.endswith(PACKAGES)):
            continue
        raise ValueError("unsupported artifact candidate entry")
    manifest = json.loads((candidate / "manifest.json").read_text(encoding="utf-8"))
    if (manifest.get("schema") != artifact_manifest.SCHEMA
            or not isinstance(manifest.get("label"), str)
            or not isinstance(manifest.get("metadata"), dict)):
        raise ValueError("missing or invalid candidate manifest")
    output.parent.mkdir(parents=True, exist_ok=True)
    # Same filesystem as output makes the final directory rename atomic.
    with tempfile.TemporaryDirectory(prefix=".pos-public-check-", dir=output.parent) as temp:
        staged = Path(temp) / "payload"
        shutil.copytree(candidate, staged, symlinks=True)
        check_regular_tree(staged)
        subprocess.run([sys.executable, str(Path(__file__).with_name("sanitize_diagnostics.py")),
                        "--root", str(staged / "diagnostics")], check=True)
        for name in NOTES:
            path = staged / name
            if path.is_file():
                if path.stat().st_size > sanitize_diagnostics.MAX_TEXT_BYTES:
                    raise ValueError("artifact note exceeds size limit")
                path.write_text(sanitize_diagnostics.redact(path.read_text(encoding="utf-8"),
                                                          os.path.expanduser("~")), encoding="utf-8")
        # Regenerate hashes after sanitation, never reuse pre-sanitization digests.
        metadata = redact_metadata(manifest["metadata"])
        label = sanitize_diagnostics.redact(manifest["label"], os.path.expanduser("~"))
        artifact_manifest.write_manifest(str(staged), label, metadata)
        staged.rename(output)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        publish(args.candidate, args.output)
    except (OSError, ValueError, subprocess.CalledProcessError, SystemExit):
        print("error: public artifact validation failed; candidate withheld", file=sys.stderr)
        return 2
    print("public artifact staging passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
