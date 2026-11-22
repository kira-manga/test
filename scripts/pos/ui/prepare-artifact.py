"""Bind the downloaded application to its exact published package manifest."""
import hashlib
import json
import os
from pathlib import Path

work = Path(os.environ["RUNNER_TEMP"]) / "pos-ui"
target = os.environ["POS_UI_TARGET"]
if target == "android":
    apk = work / "ukkera-pos-testing.apk"
    manifest = json.loads((work / "apk-pair.json").read_text())
    record = manifest["app"]
    rev = manifest["source_revision"]
else:
    manifest_path, = (work / "artifact").rglob("manifest.json")
    manifest = json.loads(manifest_path.read_text())
    ending = ".msi" if target == "windows" else ".dmg"
    record, = [entry for entry in manifest["files"] if entry["path"].endswith(ending)]
    apk = manifest_path.parent / record["path"]
    rev = manifest["metadata"]["source_revision"]
assert rev == os.environ["POS_UI_REVISION"]
with apk.open("rb") as f:
    assert hashlib.file_digest(f, "sha256").hexdigest() == record["sha256"]
(work / "pos-revision").write_text(rev + "\n")
(work / "package-path").write_text(str(apk) + "\n")
(work / "public" / "artifact.json").write_text(json.dumps({
    "pos_revision": rev, "package_sha256": record["sha256"], "target": target,
}, indent=2) + "\n")
