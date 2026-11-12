#!/usr/bin/env python3
"""Collect native-test diagnostics into the existing strict public artifact boundary.

Sources are never modified. Missing sources are recorded for failed/early jobs;
publication success says nothing about test success or native qualification.
"""
from __future__ import annotations

import argparse
import glob
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

import artifact_manifest
import publish_public_artifact


def alias_path(value: str) -> tuple[str, Path]:
    alias, separator, path = value.partition("=")
    if not separator or not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_-]*", alias):
        raise ValueError("diagnostic sources require a simple label=path")
    return alias, Path(path)


def require_regular(path: Path, directory: bool) -> None:
    regular = path.is_dir() if directory else path.is_file()
    if path.is_symlink() or not regular:
        raise ValueError("diagnostic input is not a regular directory/file")
    if directory:
        publish_public_artifact.check_regular_tree(path)
        artifact_manifest.collect(str(path))
    else:
        artifact_manifest.check_path(path.name, False)


def copy_file(source: Path, target: Path) -> None:
    require_regular(source, directory=False)
    if target.exists():
        raise ValueError("duplicate public artifact input")
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)


def stage(arguments: argparse.Namespace) -> None:
    if not re.fullmatch(r"[0-9a-fA-F]{7,40}", arguments.source_revision):
        raise ValueError("source revision must be a Git revision identifier")
    marker = Path("SOURCE-REVISION.txt")
    require_regular(marker, directory=False)
    snapshot_source = marker.read_text(encoding="utf-8").strip()
    if snapshot_source != arguments.source_revision:
        raise ValueError("snapshot source revision differs from requested revision")
    harness = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    output = arguments.output.absolute()
    if output.exists() or output.is_symlink():
        raise ValueError("public destination already exists")
    # Collection stays outside all source roots, including an accidentally selected broad root.
    for value in arguments.diagnostics + arguments.xml_results:
        _, path = alias_path(value)
        resolved = path.resolve()
        if resolved == output or resolved in output.parents or output in resolved.parents:
            raise ValueError("diagnostic sources and public output must be separate")
    inventory = []
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".pos-diagnostic-check-", dir=output.parent) as temp:
        candidate = Path(temp) / "candidate"
        diagnostics = candidate / "diagnostics"
        diagnostics.mkdir(parents=True)
        aliases = set()
        for mode, values in (("diagnostics", arguments.diagnostics), ("junit-xml", arguments.xml_results)):
            for value in values:
                alias, source = alias_path(value)
                target = diagnostics / mode / alias
                if (mode, alias) in aliases:
                    raise ValueError("duplicate diagnostic source label")
                aliases.add((mode, alias))
                if source.is_symlink():
                    raise ValueError("diagnostic source symlink")
                present = source.exists()
                copied = 0
                if present:
                    require_regular(source, directory=True)
                    if mode == "diagnostics":
                        shutil.copytree(source, target, symlinks=True)
                        copied = sum(1 for item in target.rglob("*") if item.is_file())
                    else:
                        # JUnit XML is the retained result; HTML/JS and Gradle binary caches
                        # are deliberately outside this public diagnostic profile.
                        target.mkdir(parents=True)
                        for item in sorted(source.glob("*.xml")):
                            copy_file(item, target / item.name)
                            copied += 1
                inventory.append({"kind": mode, "label": alias, "present": present, "files": copied})
        for pattern in arguments.diagnostic_file:
            matches = sorted(glob.glob(pattern))
            for name in matches:
                source = Path(name)
                copy_file(source, diagnostics / "selected-files" / source.name)
            inventory.append({"kind": "selected-diagnostic-files", "pattern": pattern,
                              "present": bool(matches), "files": len(matches)})
        for name in arguments.apk:
            source = Path(name)
            if source.suffix != ".apk":
                raise ValueError("only explicitly selected APK packages are allowed")
            if source.is_symlink():
                raise ValueError("APK source symlink")
            present = source.exists()
            if present:
                copy_file(source, candidate / source.name)
                if artifact_manifest.sha256_file(str(source)) != artifact_manifest.sha256_file(str(candidate / source.name)):
                    raise ValueError("APK changed during diagnostic collection")
            inventory.append({"kind": "apk", "name": source.name, "present": present,
                              "sha256": artifact_manifest.sha256_file(str(source)) if present else None})
        metadata = {
            "requested_source_revision": arguments.source_revision,
            "snapshot_source_revision": snapshot_source,
            "harness_revision": harness,
            "repository": os.environ.get("GITHUB_REPOSITORY"),
            "run_id": os.environ.get("GITHUB_RUN_ID"),
            "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT"),
            "inputs": inventory,
            "scope": "Sanitized diagnostic copies and selected unchanged APK bytes; not a test verdict.",
        }
        (diagnostics / "publication-inputs.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
        (candidate / "SOURCE-REVISION.txt").write_text(arguments.source_revision + "\n", encoding="utf-8")
        (candidate / "README-ARTIFACTS.txt").write_text(
            "Public diagnostic staging passed; inspect the actual test outcomes separately.\n"
            "Text/XML/source diagnostic copies are sanitized; originals remain local unchanged.\n"
            "JUnit XML is retained without HTML reports or Gradle binary caches.\n"
            "Explicitly selected APK bytes remain unchanged; their hashes are in the manifest.\n"
            "Missing input/result roots are recorded, never inferred as passing tests.\n"
            "No complete secret scanning, native/platform or owner acceptance is established.\n",
            encoding="utf-8")
        artifact_manifest.write_manifest(str(candidate), arguments.label, metadata)
        publish_public_artifact.publish(candidate, output)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--diagnostics", action="append", default=[], metavar="LABEL=DIRECTORY")
    parser.add_argument("--xml-results", action="append", default=[], metavar="LABEL=DIRECTORY")
    parser.add_argument("--diagnostic-file", action="append", default=[], metavar="GLOB")
    parser.add_argument("--apk", action="append", default=[], metavar="FILE")
    arguments = parser.parse_args()
    try:
        stage(arguments)
    except (OSError, ValueError, UnicodeError, subprocess.SubprocessError, SystemExit):
        print("error: native diagnostics validation failed; publication withheld", file=sys.stderr)
        return 2
    print("public native diagnostics staging passed; not a test verdict")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
