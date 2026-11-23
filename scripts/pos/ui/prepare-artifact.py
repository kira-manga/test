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
elif target == "windows":
    # The same signed MSI was installed, verified and removed in the earlier
    # Windows-hosted control. Verify its actual artifact bytes again here.
    apk, = (work / "artifact").rglob("UkkeraPOS-Test-Service.msi")
    record = {"sha256": "1c4a98ac5586cc026f54fb7596f598afcc9571180a0180a52295f994b575980e"}
    rev = "e450ac51708ac9a2684187867e82467109b733d4"
else:
    manifest_path, = (work / "artifact").rglob("manifest.json")
    manifest = json.loads(manifest_path.read_text())
    record, = [entry for entry in manifest["files"] if entry["path"].endswith(".dmg")]
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
