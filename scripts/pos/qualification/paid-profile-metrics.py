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
payment_preparation = re.compile(
    rb"POS_PAID_PREPARATION (COLLECTION_ENTERED|COLLECTION_RETURNED|STOCK_ENTERED|STOCK_RETURNED|"
    rb"REQUIREMENT_ENTERED|REQUIREMENT_RETURNED|VACANCY_RETURNED|OBSERVATIONS_RETURNED|"
    rb"LITERAL_STOCK_RETURNED|EVIDENCE_SEALED) ([0-9]{1,19})"
)
retained_io = re.compile(
    rb"POS_RETAINED_IO pairs=([0-9]+) unique_pairs=([0-9]+|capped) streams=([0-9]+) "
    rb"unique_streams=([0-9]+|capped) parsed_hits=([0-9]+) owner_hits=([0-9]+) "
    rb"decodes=([0-9]+) fragments=([0-9]+) fragment_bytes=([0-9]+)"
    rb"(?: original_decode=([0-9]+) pair_check=([0-9]+) owner_reopen=([0-9]+)"
    rb" frame_read=([0-9]+) producer_check=([0-9]+) batch_close=([0-9]+))?"
)
retained_io_fields = (
    "pairs", "unique_pairs", "streams", "unique_streams", "parsed_hits", "owner_hits",
    "decodes", "fragments", "fragment_bytes",
)
retained_read_kinds = (
    "original_decode", "pair_check", "owner_reopen", "frame_read", "producer_check", "batch_close",
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
                    elif preparation := payment_preparation.fullmatch(line.rstrip()):
                        record = {"event": "POS_PAID_PREPARATION " + preparation[1].decode(),
                                  "observer_seconds": round(time.monotonic() - start, 3),
                                  "jvm_elapsed_nanos": int(preparation[2])}
                        print(json.dumps(record, sort_keys=True), file=stream, flush=True)
                    elif retained := retained_io.fullmatch(line.rstrip()):
                        record = {"event": "POS_RETAINED_IO", "observer_seconds": round(time.monotonic() - start, 3)}
                        record.update({key: ("capped" if value == b"capped" else int(value))
                                       for key, value in zip(retained_io_fields + retained_read_kinds, retained.groups(), strict=True)
                                       if value is not None})
                        if "original_decode" in record and sum(record[key] for key in retained_read_kinds) != record["streams"]:
                            continue
                        print(json.dumps(record, sort_keys=True), file=stream, flush=True)

        if done.exists():
            break
        time.sleep(0.2)
