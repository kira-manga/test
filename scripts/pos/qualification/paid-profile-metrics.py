"""Measure an allow-listed prefix of the synthetic worker, without reading its database."""
import json
import os
from pathlib import Path
import re
import sys
import time

from cold_pass_metrics import (
    parse_cold_earlier, parse_cold_history_pass, parse_cold_join, parse_cold_workspace_read, parse_history_source,
)

stage, worker = map(Path, sys.argv[1:3])
phase = sys.argv[3]
progress = stage / (phase + ".raw")
done = stage / (phase + ".profile.done")
output = stage / "public" / (phase + ".metrics.jsonl")
event = re.compile(
    rb"CHB_(?:STEP_(?:BEGIN|END) [0-9]{1,4} (?:create|begin|start|capture|apply|finalize|receipt/[1-9])"
    rb"(?: elapsedMillis=[0-9]+)?|NATIVE_HISTORY_(?:OPEN_ENTERED|OPERATIONAL)"
    rb"|NATIVE_CLOSE (?:seed(?:ed-[0-9]{1,4})?|restart)|READ_ONLY_NATIVE_CLOSE|EXACT_(?:LOOKUPS|RECEIPTS)_RETURNED [0-9]+"
    rb"|RETURN_(?:BEGIN|END) (?:record|receipt-evidence|qualification|accept|restore)(?: elapsedMillis=[0-9]+)?)"
)
cold_history_stage = re.compile(
    rb"UkkeraNativeOwner I Native physical stage: "
    rb"(COLD_HISTORY_(?:TRANSCRIPT_CAPTURED|INDEX_BUILT|INDEX_SPILL_RETURNED|REPLAY_ENTERED))"
)
payment_owner_stage = re.compile(
    rb"UkkeraNativeOwner I Native physical stage: "
    rb"(PHYSICAL_(?:START_(?:AUTHENTICATION|PREFLIGHT|PREPARATION|NATIVE_ACQUIRE|COMMIT|RECHECK|PERSIST|RELEASE)"
    rb"|INSTRUCTION_(?:AUTHENTICATION|NATIVE_PREPARE|AUTHORIZATION|RETAINED_READ|DOMAIN_PREPARE|RELEASE))"
    rb"_(?:ENTERED|RETURNED))"
)
physical_dependencies = re.compile(
    rb"POS_PHYSICAL_READ_DEPENDENCIES complete=(true|false) total=([0-9]{1,4}) "
    rb"retained=([0-9]{1,4}) aggregates=([0-9]{1,3}) ranges=([0-9]{1,3}) "
    rb"claims=([0-9]{1,3}) resources=([0-9]{1,3})"
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
retained_read_sites = (
    "source_frames_hit", "source_frames_miss", "direct_source_batch", "direct_producer_read",
    "replay_installed", "replay_released", "replay_evicted", "replay_unavailable_scan",
    "replay_size_refused", "replay_budget_refused", "commerce_key_scan",
)
retained_sites_event = re.compile(
    b"POS_RETAINED_SITES " + b" ".join(site.encode("ascii") + rb"=([0-9]{1,19})" for site in retained_read_sites)
    + rb"(?: nested_return_complete_producer=([0-9]{1,19}))?"
    + rb"(?: direct_group_reuse=([0-9]{1,19}))?"
    + rb"(?: direct_stock_closure_reuse=([0-9]{1,19}))?"
)
retained_proof_phases = ("posting_frame", "stock_transitions", "installed_producer", "final_literal", "direct_sources")
retained_proof_detailed_phases = retained_proof_phases + (
    "literal_batch_close", "direct_original_pair", "direct_typed_streamed", "direct_typed_legacy_stock", "direct_typed_commerce",
)


def proof_pattern(phases):
    return re.compile(
        b"POS_RETAINED_PROOF " + b" ".join(
            phase.encode("ascii") + rb"=([0-9]{1,19}):([0-9]{1,19}):([0-9]{1,19})" for phase in phases
        )
    )


retained_proof = proof_pattern(retained_proof_phases)
retained_proof_detailed = proof_pattern(retained_proof_detailed_phases)
sampled_io_buckets = ("original_db", "scratch_db", "process_metadata", "jdk_archive", "other", "unknown")
sampled_io = re.compile(
    rb"POS_SYNTHETIC_COLD_IO phase=(batch|exit) monotonic_ns=([0-9]{1,19}) calls=([0-9]{1,19}) bytes=([0-9]{1,19}) "
    + b" ".join(bucket.encode("ascii") + rb"=([0-9]{1,19}):([0-9]{1,19})" for bucket in sampled_io_buckets)
)
history_source_replay = re.compile(rb"POS_HISTORY_SOURCE_REPLAY images=([0-9]{1,19}) closure_chunks=([0-9]{1,19})")
typed_return_inputs = re.compile(
    rb"POS_TYPED_RETURN_INPUTS outcome=(FIRST|SAME|DIFFERENT|CAPACITY) "
    rb"direct_calls=([0-9]{1,4}) distinct_inputs=([0-9]{1,4}) elapsed_ns=([0-9]{1,19})"
)
typed_return_ancestors = re.compile(
    rb"POS_TYPED_RETURN_ANCESTORS streamed=([0-9]{1,4}) legacy=([0-9]{1,4}) repeated=([0-9]{1,7}) "
    rb"streamed_ns=([0-9]{1,14}) legacy_ns=([0-9]{1,14})"
)
clock_ticks = os.sysconf("SC_CLK_TCK")
page_bytes = os.sysconf("SC_PAGE_SIZE")
start = time.monotonic()
offset = 0


def sample(line, **fields):
    measurement = {"event": line.decode(), "observer_seconds": round(time.monotonic() - start, 3), **fields}
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
                    elif cold := cold_history_stage.fullmatch(line.rstrip()):
                        sample(cold[1])
                    elif cold_pass := parse_cold_history_pass(line.rstrip()):
                        sample(b"POS_COLD_PASS", **cold_pass)
                    elif workspace_read := parse_cold_workspace_read(line.rstrip()):
                        sample(b"POS_COLD_WORKSPACE", **workspace_read)
                    elif join := parse_cold_join(line.rstrip()):
                        sample(b"POS_COLD_JOIN", **join)
                    elif earlier := parse_cold_earlier(line.rstrip()):
                        sample(b"POS_COLD_EARLIER", **earlier)
                    elif history := parse_history_source(line.rstrip()):
                        sample(b"POS_HISTORY_SOURCE", **history)
                    elif replay := history_source_replay.fullmatch(line.rstrip()):
                        images, closure_chunks = map(int, replay.groups())
                        sample(b"POS_HISTORY_SOURCE_REPLAY", images=images, closure_chunks=closure_chunks)
                    elif typed := typed_return_inputs.fullmatch(line.rstrip()):
                        calls, distinct, elapsed = map(int, typed.groups()[1:])
                        if 0 < distinct <= calls <= 1024 and elapsed > 0:
                            sample(b"POS_TYPED_RETURN_INPUTS", outcome=typed[1].decode(),
                                   direct_calls=calls, distinct_inputs=distinct, elapsed_ns=elapsed)
                    elif ancestors := typed_return_ancestors.fullmatch(line.rstrip()):
                        streamed, legacy, repeated, stock_ns, legacy_ns = map(int, ancestors.groups())
                        if streamed + legacy <= 256 and (stock_ns == 0) == (streamed == 0) and (legacy_ns == 0) == (legacy == 0):
                            sample(b"POS_TYPED_RETURN_ANCESTORS", streamed=streamed, legacy=legacy,
                                   repeated=repeated, streamed_ns=stock_ns, legacy_ns=legacy_ns)
                    elif retained_sites := retained_sites_event.fullmatch(line.rstrip()):
                        *sites, nested, direct_group, direct_stock_closure = retained_sites.groups()
                        counts = dict(zip(retained_read_sites, map(int, sites), strict=True))
                        if nested is not None:
                            counts["nested_return_complete_producer"] = int(nested)
                        if direct_group is not None:
                            counts["direct_group_reuse"] = int(direct_group)
                        if direct_stock_closure is not None:
                            counts["direct_stock_closure_reuse"] = int(direct_stock_closure)
                        sample(b"POS_RETAINED_SITES", **counts)
                    elif io := sampled_io.fullmatch(line.rstrip()):
                        tick, calls, size, *buckets = map(int, io.groups()[1:])
                        sample(b"POS_SYNTHETIC_COLD_IO", native_phase=io[1].decode(), monotonic_ns=tick,
                               wrapped_calls=calls, wrapped_bytes=size,
                               sampled={name: dict(calls=buckets[2*i], bytes=buckets[2*i+1])
                                        for i, name in enumerate(sampled_io_buckets)})
                    elif (proof := retained_proof_detailed.fullmatch(line.rstrip()) or retained_proof.fullmatch(line.rstrip())):
                        phases = retained_proof_detailed_phases if proof.re is retained_proof_detailed else retained_proof_phases
                        values = list(map(int, proof.groups()))
                        groups = [values[i:i+3] for i in range(0, len(values), 3)]
                        if any(interrupted > calls or (calls == 0 and elapsed)
                               for calls, elapsed, interrupted in groups):
                            continue
                        record = {"event": "POS_RETAINED_PROOF", "observer_seconds": round(time.monotonic() - start, 3)}
                        record["phases"] = {phase: dict(calls=calls, inclusive_ns=elapsed, interrupted=interrupted)
                            for phase, (calls, elapsed, interrupted) in zip(phases, groups, strict=True)}
                        print(json.dumps(record, sort_keys=True), file=stream, flush=True)
                    elif payment_owner := payment_owner_stage.fullmatch(line.rstrip()):
                        record = {"event": payment_owner[1].decode(),
                                  "observer_seconds": round(time.monotonic() - start, 3)}
                        print(json.dumps(record, sort_keys=True), file=stream, flush=True)
                    elif dependencies := physical_dependencies.fullmatch(line.rstrip()):
                        total, *counts = map(int, dependencies.groups()[1:])
                        if total == sum(counts) and counts[0] <= 8192 and sum(counts[1:]) <= 512:
                            record = {"event": "POS_PHYSICAL_READ_DEPENDENCIES",
                                      "observer_seconds": round(time.monotonic() - start, 3),
                                      "complete": dependencies[1] == b"true", "total": total}
                            record.update(zip(("retained", "aggregates", "ranges", "claims", "resources"), counts,
                                              strict=True))
                            print(json.dumps(record, sort_keys=True), file=stream, flush=True)
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
