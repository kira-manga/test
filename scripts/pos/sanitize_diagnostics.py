#!/usr/bin/env python3
"""Remove local home paths and obvious credential assignments from text diagnostics."""

from __future__ import annotations

import argparse
import os
import re
import sys

TEXT_SUFFIXES = (
    ".csv",
    ".cs",
    ".crash",
    ".ips",
    ".json",
    ".log",
    ".md",
    ".plist",
    ".properties",
    ".txt",
    ".xml",
    ".yaml",
    ".yml",
)

MAX_TEXT_BYTES = 32 * 1024 * 1024
SECRET_ASSIGNMENT = re.compile(
    r"(?i)\b(GITHUB_TOKEN|ACTIONS_RUNTIME_TOKEN|ACTIONS_ID_TOKEN_REQUEST_TOKEN|"
    r"CLIENT_SECRET|NPM_TOKEN|PASSWORD)\s*[:=]\s*[^\s]+"
)


def redact(text: str, home: str) -> str:
    if home:
        text = text.replace(home, "$HOME")
    text = re.sub(r"/Users/[^/\s]+", "/Users/<redacted>", text)
    text = re.sub(r'[A-Za-z]:\\Users\\[^\\\s"]+', r"<redacted-drive>:\\Users\\<redacted>", text)
    text = re.sub(r"[A-Za-z]:/Users/[^/\s\"]+", "<redacted-drive>:/Users/<redacted>", text)
    text = re.sub(r"/home/[^/\s]+", "/home/<redacted>", text)
    text = SECRET_ASSIGNMENT.sub(r"\1=<redacted>", text)
    return text


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, help="diagnostics directory")
    arguments = parser.parse_args()
    root = os.path.abspath(arguments.root)
    if os.path.islink(root) or not os.path.isdir(root):
        print(f"error: diagnostics directory does not exist: {root}", file=sys.stderr)
        return 2

    home = os.path.expanduser("~")
    changed = 0
    for directory, dirnames, filenames in os.walk(root, followlinks=False):
        dirnames.sort()
        for name in dirnames:
            if os.path.islink(os.path.join(directory, name)):
                raise ValueError("diagnostic directory symlinks are not publishable")
        for filename in sorted(filenames):
            path = os.path.join(directory, filename)
            if os.path.islink(path) or not os.path.isfile(path):
                raise ValueError("diagnostics must be regular files")
            size = os.path.getsize(path)
            if size > MAX_TEXT_BYTES:
                raise ValueError("diagnostic exceeds the sanitization size limit")
            if filename.lower().endswith(".png"):
                with open(path, "rb") as handle:
                    if handle.read(8) != b"\x89PNG\r\n\x1a\n":
                        raise ValueError("invalid diagnostic PNG signature")
                continue
            if not filename.lower().endswith(TEXT_SUFFIXES):
                raise ValueError("unsupported diagnostic file type")
            with open(path, "r", encoding="utf-8", errors="strict") as handle:
                original = handle.read()
            if "\x00" in original:
                raise ValueError("non-text diagnostic content")
            sanitized = redact(original, home)
            if sanitized != original:
                with open(path, "w", encoding="utf-8", newline="\n") as handle:
                    handle.write(sanitized)
                changed += 1
    print(f"sanitized_diagnostic_files_changed={changed}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, UnicodeError, ValueError):
        print("error: diagnostics sanitization failed; publication refused", file=sys.stderr)
        raise SystemExit(2)
