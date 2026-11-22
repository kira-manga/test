"""Rebuild the exact retained-signer test APK with no signing material on the runner."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile
import zipfile
import zlib


WORK = Path(os.environ["RUNNER_TEMP"]) / "pos-ui"
BUNDLE = WORK / "signed-app.tar.xz"
PARTS_PER_GROUP = 100
MAX_PARTS = 3 * PARTS_PER_GROUP
MAX_PART_BYTES = 35_000
BASE_APK_SHA256 = "9e8dab59bcd9bd4fa7e5f10c44f0091e7a3989552d11c06b10c0f41e2b7854a4"
SIGNER_SHA256 = "ae3ac7d57bcca7b8678727bf714bacc52d214d2a11e70f39814207dceb45a693"


def check(condition, reason):
    if not condition:
        raise SystemExit(reason)


def file_digest(path):
    with path.open("rb") as value:
        return hashlib.file_digest(value, "sha256").hexdigest()


def chunk(first, last, count):
    WORK.mkdir(mode=0o700, exist_ok=True)
    group = int(os.environ["POS_APK_GROUP"])
    check(0 <= group < MAX_PARTS // PARTS_PER_GROUP, "Unknown Android package group")
    size = count - group * PARTS_PER_GROUP
    with (WORK / "signed-app.part").open("xb" if first == 0 else "ab") as output:
        for part in range(first, min(last, size)):
            value = base64.b64decode(os.environ.pop(f"POS_APK_{part:02}", ""), validate=True)
            check(0 < len(value) <= MAX_PART_BYTES, f"Absent or oversized Android package part {part}")
            output.write(value)


def rebuild(base, delta, output, sealed):
    manifest = json.loads((delta / "manifest.json").read_text())
    check(manifest["before"] == BASE_APK_SHA256 and manifest["after"] == sealed, "Unknown Android delta")
    original = base.read_bytes()
    literal = (delta / "literal.dat").read_bytes()
    with tempfile.TemporaryDirectory(dir=WORK) as temporary, zipfile.ZipFile(base) as apk, output.open("xb") as result:
        folder = Path(temporary)
        before, after = folder / "before", folder / "after"
        total = 0
        for part in manifest["pieces"]:
            kind, length = part["kind"], part["length"]
            check(type(length) is int and 0 <= length <= 64 * 1024**2 and total + length <= 64 * 1024**2,
                  "Android package exceeds its test envelope")
            if kind in {"copy", "literal"}:
                offset = part["first"]
                check(type(offset) is int and offset >= 0, "Invalid Android package offset")
                source = original if kind == "copy" else literal
                content = source[offset:offset + length]
            else:
                entry = part["path"]
                check(kind == "delta" and re.fullmatch(r"[0-9]{5}\.delta", entry), "Unknown signed APK section")
                before.write_bytes(apk.read(part["source"]))
                after.unlink(missing_ok=True)
                subprocess.run(["bspatch", str(before), str(after), str(delta / "chunks" / entry)], check=True, timeout=60)
                content = after.read_bytes()
                level = part["level"]
                check(type(level) is int and 0 <= level <= 9, "Invalid APK compression level")
                if level:
                    encoder = zlib.compressobj(level, zlib.DEFLATED, -15)
                    content = encoder.compress(content) + encoder.flush()
                check(hashlib.sha256(content).hexdigest() == part["digest"], "Android package section digest mismatch")
            check(len(content) == length, "Android package section length mismatch")
            result.write(content)
            total += length
    check(file_digest(output) == sealed, "Exact signed Android APK digest mismatch")


def verify(inputs):
    revision = os.environ["POS_APK_REVISION"]
    expected_apk = os.environ["POS_APK_EXPECTED"]
    expected_bundle = os.environ["POS_APK_BUNDLE"]
    check(re.fullmatch(r"[a-f0-9]{40}", revision) and re.fullmatch(r"[a-f0-9]{64}", expected_apk) and
          re.fullmatch(r"[a-f0-9]{64}", expected_bundle), "Missing pinned Android revision or digest")
    check(BUNDLE.stat().st_size <= MAX_PARTS * MAX_PART_BYTES and file_digest(BUNDLE) == expected_bundle,
          "Android signed input digest mismatch")
    base = inputs / "ukkera-pos-testing.apk"
    check(file_digest(base) == BASE_APK_SHA256, "Retained Android package hash mismatch")
    with tempfile.TemporaryDirectory(dir=WORK) as temporary:
        delta = Path(temporary)
        with tarfile.open(BUNDLE, "r:xz") as source:
            members = source.getmembers()
            allowed = {"app.json", "manifest.json", "literal.dat"}
            check(len(members) <= 512 and sum(entry.size for entry in members) <= 16 * 1024**2 and
                  len({entry.name for entry in members}) == len(members) and
                  all((entry.isdir() and entry.name == "chunks") or (entry.isfile() and (
                      entry.name in allowed or re.fullmatch(r"chunks/[0-9]{5}\.delta", entry.name)))
                      for entry in members), "Invalid Android delta archive")
            source.extractall(delta, filter="data")
        metadata = json.loads((delta / "app.json").read_text())
        check(metadata["source_revision"] == revision and metadata["working_tree_dirty"] is False and
              metadata["apk_sha256"] == expected_apk and metadata["package"] == "com.ukkera.pos.local.testing",
              "Unbound Android package")
        output = WORK / "ukkera-pos-testing.apk"
        rebuild(base, delta, output, expected_apk)
    sdk = Path(os.environ["ANDROID_HOME"]) / "build-tools"
    apksigner = next(path for path in sorted(sdk.glob("*/apksigner"), reverse=True) if path.is_file())
    signature = subprocess.check_output([str(apksigner), "verify", "--print-certs", str(output)], text=True)
    check(re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([a-f0-9]{64})$", signature, re.MULTILINE) ==
          [SIGNER_SHA256], "Android test signer changed")
    (WORK / "apk-pair.json").write_text(json.dumps({
        "source_revision": revision, "app": {"sha256": expected_apk},
    }) + "\n")
    print(f"POS_SIGNED_ANDROID_VERIFIED revision={revision} sha256={expected_apk}")


if __name__ == "__main__":
    parts = int(os.environ["POS_APK_PARTS"])
    check(0 < parts <= MAX_PARTS, "Invalid Android package part count")
    if sys.argv[1] == "chunk":
        chunk(int(sys.argv[2]), int(sys.argv[3]), parts)
    elif sys.argv[1] == "verify":
        verify(Path(sys.argv[2]))
    else:
        raise SystemExit("Unknown Android package command")
