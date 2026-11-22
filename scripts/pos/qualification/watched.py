"""Enforce the established RAM/disk reserve and retain exact timings per phase."""
import json
import os
from pathlib import Path
import signal
import sys
import subprocess
import time


def headroom(location):
    fields = dict(row.split(":", 1) for row in Path("/proc/meminfo").read_text().splitlines())
    disk = os.statvfs(location)
    return int(fields["MemAvailable"].split()[0]) * 1024, disk.f_bavail * disk.f_frsize


gib = 1024**3
artifacts = Path(sys.argv[1])
phase = sys.argv[2]
minutes = int(sys.argv[3])
start = time.monotonic()
location = Path.home()
ram, disk = headroom(location)
report = {"phase": phase, "initial_ram_bytes": ram, "initial_disk_bytes": disk}
min_ram, min_disk = ram, disk
reason = None
if min_ram < 7 * gib or min_disk < 6 * gib:
    code, reason = 75, "admission_reserve"
else:
    with (artifacts / f"{phase}.resources.tsv").open("w", buffering=1) as samples:
        samples.write("elapsed_seconds\tram_bytes\tdisk_bytes\n")
        with (artifacts.parent / f"{phase}.raw").open("wb") as log:
            child = subprocess.Popen(sys.argv[4:], stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            while child.poll() is None:
                ram, disk = headroom(location)
                min_ram, min_disk = min(ram, min_ram), min(disk, min_disk)
                elapsed = time.monotonic() - start
                samples.write(f"{elapsed:.3f}\t{ram}\t{disk}\n")
                if ram < int(2.125 * gib) or disk < int(4.4 * gib):
                    reason = "active_reserve"
                elif elapsed >= minutes * 60:
                    reason = "job_time_budget"
                if reason:
                    try:
                        os.killpg(child.pid, signal.SIGTERM)
                        child.wait(timeout=20)
                    except subprocess.TimeoutExpired:
                        os.killpg(child.pid, signal.SIGKILL)
                        child.wait()
                    except ProcessLookupError:
                        pass
                    break
                try:
                    child.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    pass
            code = (124 if reason == "job_time_budget" else 75) if reason else child.returncode
            if code < 0:
                code = 128 - code
report.update(exit=code, seconds=round(time.monotonic() - start, 3), stop=reason,
              minimum_ram_bytes=min_ram, minimum_disk_bytes=min_disk)
(artifacts / f"{phase}.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report))
sys.exit(code)
