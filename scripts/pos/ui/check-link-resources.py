"""Check published VM capacity without running any benchmark or app workload."""
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys


host = platform.system()
if host == "Linux":
    available = int(re.search(r"MemAvailable:\s+(\d+)", Path("/proc/meminfo").read_text())[1]) * 1024
else:
    assert host == "Darwin" and platform.machine() == "arm64"
    vm = subprocess.check_output(["vm_stat"], text=True)
    page = int(re.search(r"page size of (\d+) bytes", vm)[1])
    available = sum(int(re.search(fr"Pages {name}:\s+(\d+)", vm)[1])
                    for name in ("free", "inactive", "speculative")) * page
disk = shutil.disk_usage(os.environ["RUNNER_TEMP"]).free
booting_android = host == "Linux" and sys.argv[1] == "before_install"
# The hosted Android preflight also boots a separate emulator. Apple Silicon
# uses only the existing macOS host and signed app, with no build or simulator.
minimum_ram = 7 * 1024**3 if booting_android else 2228224 * 1024
minimum_disk = 6 * 1024**3 if booting_android else 4724464026
report = {"phase": sys.argv[1], "os": host, "available_ram": available, "available_disk": disk,
          "ram_floor": minimum_ram, "disk_floor": minimum_disk,
          "passed": available >= minimum_ram and disk >= minimum_disk}
where = Path(os.environ["RUNNER_TEMP"]) / "pos-ui" / "public" / "link-resources.jsonl"
with where.open("a", encoding="utf-8") as out:
    print(json.dumps(report), file=out)
print(json.dumps(report))
if not report["passed"]:
    sys.exit(75)
