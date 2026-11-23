"""Accept numeric timings only after the exact installed selector passes."""
import json
from pathlib import Path
import re
import sys

stage = Path(sys.argv[1])
process_exit = int(sys.argv[2])
sdk = int(sys.argv[3])
instrumentation = (stage / "installed-cashier.instrumentation").read_text(errors="replace").splitlines()
timed = re.compile(
    r"POS_INSTALLED_CASHIER covered_sale_records=1 "
    r"confirm_to_durable_ns=([0-9]{1,15}) confirm_to_preview_ns=([0-9]{1,15})"
)
timings = []
for line in (stage / "installed-cashier-device.log").read_text(errors="replace").splitlines():
    if "POSCashierLatency: " not in line:
        continue
    match = timed.fullmatch(line.split("POSCashierLatency: ", 1)[1])
    if match:
        timings.append(tuple(map(int, match.groups())))
success = (
    process_exit == 0
    and instrumentation.count("OK (1 test)") == 1
    and instrumentation.count("INSTRUMENTATION_STATUS_CODE: 0") == 1
    and not any(re.search(r"^INSTRUMENTATION_STATUS_CODE: -[1-4]|FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed", line)
                for line in instrumentation)
    and len(timings) == 1
    and 0 < timings[0][0] <= timings[0][1]
)
record = {
    "sdk": sdk,
    "process_exit": process_exit,
    "passed": success,
    "scope": "covered local sale confirm to recorded result and paid-sale preview on MainActivity",
}
if success:
    record.update(confirm_to_durable_ns=timings[0][0], confirm_to_preview_ns=timings[0][1])
(stage / "public" / "installed-cashier-result.json").write_text(json.dumps(record, indent=2) + "\n")
if not success:
    raise SystemExit("Installed timing or exact selector assertion failed")
