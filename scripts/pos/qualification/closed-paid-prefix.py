"""Verify and compare the runner's already closed synthetic prefix in place."""
from hashlib import file_digest
import json
import os
from pathlib import Path
import sys


root = Path(sys.argv[1]).resolve(strict=True)
count = int(sys.argv[2])
assert count in (5, 10, 25, 35)
expected = 17 + 7 * count
census = json.loads((root / "VERIFIED-CENSUS.json").read_text())
assert census["paid_sales"] == count
assert census["cold_same_process_and_fresh_process"]
assert all(census[field] == expected for field in ("originals", "journal", "audit", "outbox"))
database, = (root / "original").rglob("master.db")
history, = (root / "original").rglob("native-history.v2")
assert history.parent == database.parent.parent
assert not any(Path(str(database) + ending).exists() for ending in ("-wal", "-journal"))


def fingerprint(path: Path):
    assert path.is_file() and not path.is_symlink()
    with path.open("rb") as source:
        first = os.fstat(source.fileno())
        digest = file_digest(source, "sha256").hexdigest()
        last = path.stat()
    before = first.st_dev, first.st_ino, first.st_size, first.st_mtime_ns
    after = last.st_dev, last.st_ino, last.st_size, last.st_mtime_ns
    assert before == after
    return before[:3], digest


paths = ("bound-commands.json", "durable-results.jsonl")
data = {name: fingerprint(root / name) for name in paths}
data["master"] = fingerprint(database)
data["native-history"] = fingerprint(history)
assert data["bound-commands.json"][1] == census["bound_input_sha256"]
print(json.dumps(data, sort_keys=True))
