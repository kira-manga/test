"""Measure an allow-listed prefix of the synthetic worker, without reading its database."""
import json
import os
from pathlib import Path
import re
import sys
import time

stage, worker = map(Path, sys.argv[1:3])
phase = sys.argv[3]
progress = stage / (phase + ".raw")
done = stage / (phase + ".profile.done")
output = stage / "public" / (phase + ".metrics.jsonl")
event = re.compile(
    rb"CHB_(?:STEP_(?:BEGIN|END) [0-9]{1,4} (?:create|begin|start|capture|apply|finalize|receipt/[1-9])"
    rb"(?: elapsedMillis=[0-9]+)?|NATIVE_HISTORY_(?:OPEN_ENTERED|OPERATIONAL)"
    rb"|NATIVE_CLOSE (?:seed(?:ed-[0-9]{1,4})?|restart)|EXACT_(?:LOOKUPS|RECEIPTS)_RETURNED [0-9]+)"
)
clock_ticks = os.sysconf("SC_CLK_TCK")
page_bytes = os.sysconf("SC_PAGE_SIZE")
start = time.monotonic()
offset = 0


def sample(line):
    measurement = {"event": line.decode(), "observer_seconds": round(time.monotonic() - start, 3)}
    process = Path("/proc") / str(int((worker / (phase + ".pid")).read_text()))
    try:
        args = (process / "cmdline").read_bytes().split(b"\0")
        if process.stat().st_uid == os.getuid() and bytes(worker) in args and (
            b"com.ukkera.pos.database.FrozenPaidHistoryMaster" in args
        ):
            stats = (process / "stat").read_text().split(") ", 1)[1].split()
            measurement["cpu_seconds"] = (int(stats[11]) + int(stats[12])) / clock_ticks
            measurement["rss_bytes"] = int(stats[21]) * page_bytes
            counters = dict(row.split(": ") for row in (process / "io").read_text().splitlines())
            for name in ("rchar", "wchar", "read_bytes", "write_bytes", "syscr", "syscw"):
                measurement[name] = int(counters[name])
    except (FileNotFoundError, ProcessLookupError):
        pass
    size = 0
    for file in (worker / "original").rglob("*"):
        if file.name.endswith((".db", ".db-journal", ".db-wal", ".sqlite", ".sqlite-wal", ".sqlite-journal")):
            try:
                size += file.stat().st_size
            except FileNotFoundError:
                pass
    measurement["database_bytes"] = size
    print(json.dumps(measurement, sort_keys=True), file=stream, flush=True)


with output.open("x") as stream:
    while True:
        if progress.is_file() and (worker / (phase + ".pid")).is_file():
            with progress.open("rb") as log:
                log.seek(offset)
                while line := log.readline():
                    if not line.endswith(b"\n"):
                        break
                    offset = log.tell()
                    if event.fullmatch(line.rstrip()):
                        sample(line.rstrip())
        if done.exists():
            break
        time.sleep(0.2)
