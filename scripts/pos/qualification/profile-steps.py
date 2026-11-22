"""Observe the existing frozen worker without changing its business inputs or clocks."""
import os
from pathlib import Path
import re
import subprocess
import sys
import time

base, installed = map(Path, sys.argv[1:3])
suite, mode, count = sys.argv[3:]
supporting = suite == "paid-profile"
if mode not in ("seed", "restart") or not count.isdecimal():
    raise ValueError("Invalid observation phase")
source = base / f"{mode}.raw"
done = base / f"{mode}.stack.done"
seed_close = f"CHB_NATIVE_CLOSE seeded-{count}".encode()
cold_selected = mode == "restart"
cold_started = None
cold_samples = (6, 15, 22, 26)
cold_index = 0
phases = rb"create|begin|start|capture|apply|finalize|receipt/[12]" if supporting else rb"start|apply"
line_start = re.compile(rb"CHB_STEP_BEGIN ([0-9]+) (" + phases + rb")\r?\n")
line_end = re.compile(rb"CHB_STEP_(?:BEGIN|END) ")
selected = {8, 16, 25, 35, 40, 41, 42, 43, 44}
if supporting:
    selected.update({1, 5, 10, 32, 50, 100, 250, 500, 1000})
active = None
cursor = 0
def sample_threads(name):
    try:
        pid = int((installed / f"{mode}.pid").read_text())
        proc = Path(f"/proc/{pid}")
        args = (proc / "cmdline").read_bytes().split(b"\0")
        if (
            proc.stat().st_uid != os.getuid()
            or b"com.ukkera.pos.database.FrozenPaidHistoryMaster" not in args
            or bytes(installed) not in args
            or mode.encode() not in args
        ):
            return
        jcmd = Path(os.environ["JAVA_HOME"]) / "bin/jcmd"
        with (base / f"profile-{name}.txt").open("xb") as stack:
            try:
                subprocess.run(
                    [str(jcmd), str(pid), "Thread.print"], timeout=3,
                    stdout=stack, stderr=subprocess.STDOUT, check=False,
                )
            except subprocess.TimeoutExpired:
                pass
    except (FileNotFoundError, ProcessLookupError, ValueError):
        pass


while not done.exists():
    if source.is_file():
        with source.open("rb") as log:
            log.seek(cursor)
            while line := log.readline():
                if not line.endswith(b"\n"):
                    break
                cursor = log.tell()
                marker = line.rstrip(b"\r\n")
                if marker == seed_close and mode == "seed":
                    cold_selected = True
                elif cold_selected and marker == b"CHB_NATIVE_HISTORY_OPEN_ENTERED":
                    cold_started = time.monotonic()
                    cold_selected = False
                elif marker == b"CHB_NATIVE_HISTORY_OPERATIONAL":
                    cold_started = None
                elif mode == "seed":
                    if match := line_start.fullmatch(line):
                        number, step = int(match[1]), match[2].decode()
                        active = (number, step, time.monotonic()) if number in selected else None
                    elif line_end.match(line):
                        active = None
    if cold_started is not None and cold_index < len(cold_samples):
        threshold = cold_samples[cold_index]
        if time.monotonic() - cold_started >= threshold:
            sample_threads(f"cold-{mode}-{threshold}s")
            cold_index += 1
    if active:
        number, step, start = active
        threshold = (4 if number <= 16 else 9 if number <= 35 else 23)
        if supporting and step not in {"start", "apply"}:
            threshold = 3
        if time.monotonic() - start >= threshold:
            active = None
            sample_threads(f"{number}-{step.replace('/', '-')}")
    time.sleep(0.2)
