"""Observe the existing frozen worker without changing its business inputs or clocks."""
import os
from pathlib import Path
import re
import subprocess
import sys
import time

base, installed = map(Path, sys.argv[1:3])
supporting = len(sys.argv) == 4 and sys.argv[3] == "paid-profile"
source = base / "seed.raw"
done = base / "profile.done"
phases = rb"create|begin|start|capture|apply|finalize|receipt/[12]" if supporting else rb"start|apply"
line_start = re.compile(rb"CHB_STEP_BEGIN ([0-9]+) (" + phases + rb")\r?\n")
line_end = re.compile(rb"CHB_STEP_(?:BEGIN|END) ")
selected = {8, 16, 25, 35, 40, 41, 42, 43, 44}
if supporting:
    selected.update({1, 5, 10, 32, 50, 100, 250, 500, 1000})
active = None
cursor = 0
while not done.exists():
    if source.is_file():
        with source.open("rb") as log:
            log.seek(cursor)
            while line := log.readline():
                if not line.endswith(b"\n"):
                    break
                cursor = log.tell()
                if match := line_start.fullmatch(line):
                    number, step = int(match[1]), match[2].decode()
                    active = (number, step, time.monotonic()) if number in selected else None
                elif line_end.match(line):
                    active = None
    if active:
        number, step, start = active
        threshold = (4 if number <= 16 else 9 if number <= 35 else 23)
        if supporting and step not in {"start", "apply"}:
            threshold = 3
        if time.monotonic() - start >= threshold:
            active = None                                      # One bounded sample per selected slow step.
            try:
                pid = int((installed / "seed.pid").read_text())
                proc = Path(f"/proc/{pid}")
                args = (proc / "cmdline").read_bytes().split(b"\0")
                if (proc.stat().st_uid == os.getuid() and b"com.ukkera.pos.database.FrozenPaidHistoryMaster" in args
                        and bytes(installed) in args):
                    jcmd = Path(os.environ["JAVA_HOME"]) / "bin/jcmd"
                    filename_step = step.replace("/", "-")
                    with (base / f"profile-{number}-{filename_step}.txt").open("xb") as stack:
                        try:
                            subprocess.run([str(jcmd), str(pid), "Thread.print"], timeout=4,
                                           stdout=stack, stderr=subprocess.STDOUT, check=False)
                        except subprocess.TimeoutExpired:
                            pass
            except (FileNotFoundError, ProcessLookupError, ValueError):
                pass
    time.sleep(0.5)
