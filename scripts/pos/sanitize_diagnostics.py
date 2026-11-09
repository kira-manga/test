#!/usr/bin/env python3
"""Remove local home paths and obvious credential assignments from text diagnostics."""

from __future__ import annotations

import argparse
import os
import re
import sys

TEXT_SUFFIXES = (
    ".csv",
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
    if not os.path.isdir(root):
        print(f"error: diagnostics directory does not exist: {root}", file=sys.stderr)
        return 2

    home = os.path.expanduser("~")
    changed = 0
    skipped = 0
    for directory, dirnames, filenames in os.walk(root, followlinks=False):
        dirnames.sort()
        for filename in sorted(filenames):
            path = os.path.join(directory, filename)
            if os.path.islink(path):
                continue
            if not filename.lower().endswith(TEXT_SUFFIXES):
                continue
            size = os.path.getsize(path)
            if size > MAX_TEXT_BYTES:
                print(f"warning: diagnostic text too large to sanitize: {path}", file=sys.stderr)
                skipped += 1
                continue
            with open(path, "r", encoding="utf-8", errors="replace") as handle:
                original = handle.read()
            sanitized = redact(original, home)
            if sanitized != original:
                with open(path, "w", encoding="utf-8", newline="\n") as handle:
                    handle.write(sanitized)
                changed += 1
    print(f"diagnostics_root={root} changed={changed} skipped={skipped}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
