"""Apply a sealed exact candidate to the already verified supporting-profile source."""

import os
from pathlib import Path
import re
import shutil
import subprocess

stage = Path(os.environ["RUNNER_TEMP"]) / "pos-qualification"
alternate = Path(os.environ["RUNNER_TEMP"]) / "pos-comparison"
inputs = stage / "inputs"
revision = os.environ["EXPECTED_COMPARISON"]
assert re.fullmatch(r"[0-9a-f]{40}", revision)
assert (inputs / "COMPARISON-REVISION").read_text().strip() == revision
tree = (inputs / "COMPARISON-TREE").read_text().strip()
assert re.fullmatch(r"[0-9a-f]{40}", tree)
frozen = "qa/pos/designs/cold-history-bounded-workspace-v1"

second = alternate / "pos-qualification"
source = second / "source"
source.mkdir(mode=0o700, parents=True)
shutil.copyfile(stage / "source-index", second / "source-index")
second_inputs = second / "inputs"
second_inputs.mkdir(mode=0o700)
shutil.copytree(inputs / "runner", second_inputs / "runner")
(second_inputs / "POS-REVISION").write_text(revision + "\n")
(second_inputs / "POS-TREE").write_text(tree + "\n")

git_dir = Path(os.environ["GITHUB_WORKSPACE"]) / ".git"
environment = {
    **os.environ,
    "GIT_DIR": str(git_dir),
    "GIT_INDEX_FILE": str(second / "source-index"),
    "GIT_WORK_TREE": str(source),
    "GIT_CONFIG_NOSYSTEM": "1",
    "GIT_CONFIG_GLOBAL": "/dev/null",
}

def git(*args):
    result = subprocess.run(
        ["git", "-c", "core.sparseCheckout=false", "-c", "core.autocrlf=false", *args],
        cwd=source, env=environment, capture_output=True,
    )
    if result.returncode:
        raise SystemExit(f"Comparison source verification failed at {args[0]}")
    return result.stdout.decode().strip()

baseline = git("write-tree")
assert git("rev-parse", f"{baseline}:apps/pos") == (inputs / "POS-TREE").read_text().strip()
git("apply", "--cached", "--binary", str(inputs / "comparison.patch"))
candidate = git("write-tree")
changed = git("diff", "--name-only", baseline, candidate).splitlines()
assert changed and all(path.startswith("apps/pos/") for path in changed)
git("checkout-index", "--all")
git("add", "--all", "--", "apps/pos", frozen)
tracked = git("write-tree")
assert tracked == candidate
assert git("rev-parse", f"{tracked}:apps/pos") == tree
assert git("rev-parse", f"{tracked}:{frozen}") == (inputs / "FROZEN-TREE").read_text().strip()
print(f"POS_COMPARISON_VERIFIED revision={revision} tree={tree}")
