"""Sample selected paid phases from outside the worker with its fixed UTC clock."""
from collections import Counter
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time

stage, worker = map(Path, sys.argv[1:3])
mode, profiler = sys.argv[3:]
if mode not in ("seed", "restart"):
    raise ValueError("Unexpected paid worker")
source = stage / f"{mode}.raw"
done = stage / f"{mode}.profile.done"
categories = {
    "physical_catalogue": ("PhysicalCatalogueGeneration", "LocalPhysicalAllocationCatalogue"),
    "native_file_custody": ("JvmPhysicalSpoolFile", "DesktopSelfOwnedHistoryWorkspace", "SelfOwnedInstallationOwner"),
    "installed_key_custody": ("DesktopMasterClientJournalCustody", "DesktopSecretStoreCustody"),
    "crypto": ("javax/crypto/", "java/security/", "sun/security/"),
    "stock_proof": ("LocalStockRelationalInspection", "StockOriginalTraversal", "StockReadProducerProof"),
    "stock_cycle_search": ("StockOriginalTraversal.reaches",),
    "retained_rows": ("RetainedOriginalReader", "StockFrameStreamDecoder", "RuntimeHistoryColdIndex"),
    "published_catalogue": ("LocalCatalogUnitAuthority", "LocalCatalogPublication"),
    "journal_replay": ("RuntimeHistoryScanner", "RuntimeHistoryColdReplay", "NativeHistoryJournal"),
    "result_authorization": ("lookupAtWriter", "lookupInternal", "currentIdentityDenial", "RetainedResultAuthorization"),
    "native_writer": ("RoomRuntimeHistoryOwner", "AndroidSelfOwnedInstallationLifetime"),
    "receipt_proof": ("LocalRetailCustomerReceipt", "LocalPaidSaleRead", "LocalReceiptMembership"),
    "sqlite": ("androidx/sqlite/", "sqlite3_"),
    "codec": ("kotlinx/serialization/", "NativeHistoryWriterV2", "LocalPhysicalJournalFormatV1"),
}
selected = {1, 5, 10, 25, 32, 50, 100, 250, 500, 1000}
begin = re.compile(rb"CHB_STEP_BEGIN (\d{1,4}) (create|begin|start|capture|apply|finalize|receipt/[1-9])")
receipt_end = re.compile(rb"CHB_EXACT_RECEIPTS_RETURNED [0-9]+")
lookup_end = re.compile(rb"CHB_EXACT_LOOKUPS_RETURNED [0-9]+")
boundary = re.compile(
    rb"CHB_(?:STEP_(?:BEGIN|END) .+|NATIVE_HISTORY_(?:OPEN_ENTERED|OPERATIONAL)"
    rb"|NATIVE_CLOSE .+|EXACT_(?:RECEIPTS|LOOKUPS)_RETURNED [0-9]+|PROTECTED_ORIGINALS_VERIFIED .+)"
)


def worker_pid():
    pid = int((worker / f"{mode}.pid").read_text())
    proc = Path("/proc") / str(pid)
    arguments = (proc / "cmdline").read_bytes().split(b"\0")
    if (proc.stat().st_uid == os.getuid()
            and b"com.ukkera.pos.database.FrozenPaidHistoryMaster" in arguments
            and bytes(worker) in arguments
            and mode.encode() in arguments):
        return pid
    return None


def capture(entry, offset):
    try:
        pid = worker_pid()
        if pid is None:
            return {**entry, "status": "worker_not_identified"}
        with tempfile.TemporaryDirectory(dir=stage, prefix="cpu-sample-") as directory:
            profile = Path(directory) / "cpu.collapsed"
            result = subprocess.run(
                [profiler, "-e", "ctimer", "-i", "20ms", "-d", "2", "-o", "collapsed", "-f", str(profile), str(pid)],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=6, check=False,
            )
            if result.returncode or not profile.is_file():
                return {**entry, "status": "sampler_unavailable", "exit": result.returncode}
            with source.open("rb") as log:
                log.seek(offset)
                crossed = any(boundary.fullmatch(line.rstrip())
                              for line in log if line.endswith(b"\n"))
            if crossed:
                return {**entry, "status": "crossed_phase_boundary"}
            counts = Counter()
            total = 0
            for line in profile.read_text(errors="replace").splitlines():
                stack, _, samples = line.rpartition(" ")
                if not samples.isdecimal():
                    continue
                count = int(samples)
                total += count
                for category, needles in categories.items():
                    if any(needle in stack for needle in needles):
                        counts[category] += count
            return {**entry, "status": "sampled", "cpu_samples": total, "inclusive_stack_membership": dict(counts)}
    except (OSError, ValueError, subprocess.TimeoutExpired):
        return {**entry, "status": "sampler_unavailable"}


offset = 0
cold_allowed = mode == "restart"
seed_close = f"CHB_NATIVE_CLOSE seeded-{os.environ['UKKERA_CHB_COUNT']}".encode()
recovery_started = False
recovery_mode = "fresh" if mode == "restart" else "same_process"
active = None
windows = 0
next_sample = 0.0
with (stage / "public" / f"{mode}.cpu-samples.jsonl").open("x") as out:
    while not done.exists():
        try:
            with source.open("rb") as log:
                log.seek(offset)
                while line := log.readline():
                    if not line.endswith(b"\n"):
                        break
                    offset = log.tell()
                    line = line.rstrip()
                    if boundary.fullmatch(line):
                        windows = 0
                        next_sample = 0.0
                    if match := begin.fullmatch(line):
                        sale, phase = int(match[1]), match[2].decode()
                        active = {"sale": sale, "phase": phase} if sale in selected else None
                    elif mode == "seed" and line == seed_close:
                        cold_allowed = True
                        active = None
                    elif cold_allowed and not recovery_started and line == b"CHB_NATIVE_HISTORY_OPEN_ENTERED":
                        active = {"phase": f"{recovery_mode}_reopen"}
                        recovery_started = True
                    elif recovery_started and line == b"CHB_NATIVE_HISTORY_OPERATIONAL":
                        active = {"phase": f"{recovery_mode}_protected_lookups"}
                    elif recovery_started and lookup_end.fullmatch(line):
                        active = {"phase": f"{recovery_mode}_receipt_checks"}
                    elif recovery_started and receipt_end.fullmatch(line):
                        active = {"phase": f"{recovery_mode}_final_consistency"}
                    elif boundary.fullmatch(line):
                        active = None
        except FileNotFoundError:
            pass
        if active and windows < 4 and time.monotonic() >= next_sample:
            observation = capture({**active, "window": windows + 1}, offset)
            print(json.dumps(observation, sort_keys=True), file=out, flush=True)
            windows += 1
            next_sample = time.monotonic() + 15
        time.sleep(0.1)
