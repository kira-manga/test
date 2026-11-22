"""Reconstruct the exact POS tree from the reviewed snapshot and a sealed delta."""
import base64
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile


def require_sha(value, size):
    if not re.fullmatch(rf"[0-9a-f]{{{size}}}", value):
        raise SystemExit("Invalid checked input digest")
    return value


stage = Path(os.environ["RUNNER_TEMP"]) / "pos-qualification"
marker = stage / "bundle.xz"
if sys.argv[1] == "chunk":
    stage.mkdir(mode=0o700, exist_ok=True)
    start = int(sys.argv[2])
    stop = int(sys.argv[3])
    with marker.open("xb" if start == 0 else "ab") as output:
        for number in range(start, stop):
            name = f"POS_INPUT_{number:02}"
            chunk = os.environ.pop(name, "")
            if not chunk:
                raise SystemExit(f"Sealed input part {number} is missing")
            output.write(base64.b64decode(chunk, validate=True))
    sys.exit(0)

expected = require_sha(os.environ["EXPECTED_BUNDLE"], 64)
with marker.open("rb") as source:
    if hashlib.file_digest(source, "sha256").hexdigest() != expected:
        raise SystemExit("Sealed input digest mismatch")
with tarfile.open(marker, "r:xz") as bundle:
    if not all(member.isfile() or member.isdir() for member in bundle.getmembers()):
        raise SystemExit("Sealed input must contain only files and directories")
    bundle.extractall(stage / "inputs", filter="data")
marker.unlink()
inputs = stage / "inputs"
expected_rev = require_sha(os.environ["EXPECTED_REVISION"], 40)
if (inputs / "POS-REVISION").read_text().strip() != expected_rev:
    raise SystemExit("Unexpected POS revision")

repo = Path(os.environ["GITHUB_WORKSPACE"])
source = stage / "source"
source.mkdir(mode=0o700)
git_env = {
    **os.environ,
    "GIT_DIR": str(repo / ".git"),
    "GIT_WORK_TREE": str(source),
    "GIT_INDEX_FILE": str(stage / "source-index"),
    "GIT_CONFIG_NOSYSTEM": "1",
    "GIT_CONFIG_GLOBAL": "/dev/null",
}


def git(*args, data=None):
    # Git's diff error can contain private filenames. Keep it out of the public log.
    result = subprocess.run(
        ["git", "-c", "core.sparseCheckout=false", "-c", "core.autocrlf=false", *args], cwd=source, env=git_env, input=data, capture_output=True, check=False
    )
    if result.returncode:
        raise SystemExit(f"POS source verification failed at {args[0]}")
    return result.stdout.decode("utf-8").strip()


git("update-index", "-z", "--index-info", data=(inputs / "base-index").read_bytes())
base_tree = git("write-tree")
if git("rev-parse", f"{base_tree}:apps/pos") != require_sha((inputs / "BASELINE-TREE").read_text().strip(), 40):
    raise SystemExit("Installed baseline tree mismatches")
git("apply", "--cached", "--binary", str(inputs / "pos.patch"))
pos_tree = git("rev-parse", f"{git('write-tree')}:apps/pos")
if pos_tree != require_sha((inputs / "POS-TREE").read_text().strip(), 40):
    raise SystemExit("Final POS tree mismatches")
git("checkout-index", "--all")
git("add", "--all", "--", "apps/pos")
if git("rev-parse", f"{git('write-tree')}:apps/pos") != pos_tree:
    raise SystemExit("Actual workspace differs from the verified POS tree")
frozen = "qa/pos/designs/cold-history-bounded-workspace-v1"
shutil.copytree(inputs / frozen, source / frozen)
git("add", "--all", "--", frozen)
if git("rev-parse", f"{git('write-tree')}:{frozen}") != require_sha((inputs / "FROZEN-TREE").read_text().strip(), 40):
    raise SystemExit("Frozen specification tree mismatches")
print(f"POS_SOURCE_VERIFIED revision={expected_rev} tree={pos_tree}")
