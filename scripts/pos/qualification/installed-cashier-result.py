"""Accept numeric timings only after the exact installed selector passes."""
from datetime import datetime
import json
from pathlib import Path
import re
import sys

stage = Path(sys.argv[1])
process_exit = int(sys.argv[2])
sdk = int(sys.argv[3])
scope = sys.argv[4] if len(sys.argv) == 5 else "covered"
assert scope in ("covered", "paid")
instrumentation = (stage / "installed-cashier.instrumentation").read_text(errors="replace").splitlines()
ranges = (
    ("confirm_to_durable_ns", "confirm_to_preview_ns") if scope == "covered" else
    ("start_instruction_ns", "capture_memory_ns", "capture_sql_pending_ns", "apply_recorded_ns",
     "finalize_recorded_ns", "finalize_preview_ns", "first_confirmation_preview_ns")
)
def numeric_line(prefix, fields):
    return re.compile(prefix + "".join(f" {field}=([0-9]{{1,15}})" for field in fields))

timed = numeric_line("POS_INSTALLED_CASHIER covered_sale_records=1" if scope == "covered" else
                     "POS_INSTALLED_PAID paid_sale_records=1", ranges)
boundary_fields = {name: (f"{name}_gateway_ns", f"{name}_state_ns")
                   for name in ("start", "recover", "apply", "finalize")}
boundary_fields["preview"] = ("preview_pending_ns", "preview_gateway_ns", "preview_state_ns")
boundary_patterns = [(name, numeric_line("POS_CHECKOUT_BOUNDARY", fields)) for name, fields in boundary_fields.items()]
pre_master_fields = ("lease_ns", "lock_ns", "session_ns", "intent_ns", "ready_ns")
pre_master_pattern = numeric_line("POS_RETAIL_START_PREMASTER", pre_master_fields)
cohost_current_fields = ("lock_ns", "current_ns", "intent_ns", "ready_ns")
cohost_current_pattern = numeric_line("POS_RETAIL_START_COHOST_CURRENT", cohost_current_fields)
lock_pattern = re.compile(r"^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(\d{1,8})\s+\d{1,8} I System\.out: "
                          r"POS_CLIENT_INTENT_LOCK_STAGE holder=(WORKFLOW_READ|LIVE_POLL|WORKFLOW_PENDING|PAYMENT_START) "
                          r"phase=(WAITING|ACQUIRED|RETURNING)$")
own_recover_pattern = re.compile(
    r"POS_RETAIL_OWN_RECOVER stage=(SESSION|INTENT|SAVED_RESULT|ORIGINAL_FACT|LINKED_FACT|PROGRESS)"
    r" outcome=(SQL_ORIGINAL_PENDING|SQL_RESOLUTION_PENDING|FINANCIALLY_APPLIED"
    r"|START_RECORDED_NO_INSTRUCTION|UNAVAILABLE|SESSION_ENDED|RETRY_LATER|DENIED"
    r"|NOT_CONFIGURED|NOT_IMPLEMENTED|STORAGE_UNCERTAIN|INVALID_INPUT|CHECK_FAILED|OTHER)"
)
presentation_pattern = re.compile(r"POS_RETAIL_RECOVER_PRESENTATION reply=(PROGRESS|UNAVAILABLE|OTHER) same_id=([01])")
pre_master = []
cohost_current = []
own_recover = []
presentation = []
lock_events = []
timings = []
raw_boundaries = {name: [] for name in boundary_fields}
for line in (stage / "installed-cashier-device.log").read_text(errors="replace").splitlines():
    if "POSCashierLatency: " in line:
        match = timed.fullmatch(line.split("POSCashierLatency: ", 1)[1])
        if match:
            timings.append(tuple(map(int, match.groups())))
    if scope == "paid" and ": POS_CHECKOUT_BOUNDARY " in line:
        event = line.split(": POS_CHECKOUT_BOUNDARY ", 1)[1]
        for name, pattern in boundary_patterns:
            match = pattern.fullmatch("POS_CHECKOUT_BOUNDARY " + event)
            if match:
                raw_boundaries[name].append(tuple(map(int, match.groups())))
    if scope == "paid" and ": " in line:
        event = line.split(": ", 1)[1]
        if (match := pre_master_pattern.fullmatch(event)) and len(pre_master) < 8:
            intervals = tuple(map(int, match.groups()))
            if tuple(sorted(intervals)) == intervals and intervals[-1] <= 300_000_000_000:
                pre_master.append(dict(zip(pre_master_fields, intervals, strict=True)))
        if (match := cohost_current_pattern.fullmatch(event)) and len(cohost_current) < 8:
            intervals = tuple(map(int, match.groups()))
            if tuple(sorted(intervals)) == intervals and intervals[-1] <= 300_000_000_000:
                cohost_current.append(dict(zip(cohost_current_fields, intervals, strict=True)))
        if (match := own_recover_pattern.fullmatch(event)) and len(own_recover) < 16:
            own_recover.append(dict(zip(("stage", "outcome"), match.groups(), strict=True)))
        if (match := presentation_pattern.fullmatch(event)) and len(presentation) < 16:
            presentation.append({"reply": match[1], "same_id": match[2] == "1"})
    if scope == "paid" and len(lock_events) < 2049 and (match := lock_pattern.fullmatch(line)):
        lock_events.append(match.groups())

def intent_lock_observation(events):
    if not events:
        return None
    waits = [event for event in events if event[2:] == ("PAYMENT_START", "WAITING")]
    if len(waits) != 1 or len(events) > 2048:
        return {"attributed": False}
    request, process, _, _ = waits[0]
    acquired = [event[0] for event in events if event[1:] == (process, "PAYMENT_START", "ACQUIRED")]
    if len(acquired) != 1:
        return {"attributed": False}

    def milliseconds(stamp):
        return int((datetime.strptime("2000 " + stamp, "%Y %m-%d %H:%M:%S.%f") -
                    datetime(2000, 1, 1)).total_seconds() * 1000 + 0.5)

    try:
        start, end = milliseconds(request), milliseconds(acquired[0])
        if not 0 <= end - start <= 120_000:
            return {"attributed": False}
        open_holders = {}
        overlap = []
        for time, pid, holder, phase in events:
            if pid != process or holder == "PAYMENT_START":
                continue
            at = milliseconds(time)
            if phase == "ACQUIRED":
                if holder in open_holders:
                    return {"attributed": False}
                open_holders[holder] = at
            elif phase == "RETURNING" and holder in open_holders:
                since = open_holders.pop(holder)
                overlapped = min(end, at) - max(start, since)
                if 0 < overlapped <= 120_000:
                    overlap.append({"holder": holder, "overlap_ms": overlapped,
                                    "ended_before_acquire_ms": end - at})
        if len(overlap) > 8 or any(item["ended_before_acquire_ms"] < 0 for item in overlap):
            return {"attributed": False}
        return {"attributed": bool(overlap), "start_wait_ms": end - start, "observed_overlaps": overlap}
    except ValueError:
        return {"attributed": False}

success = (
    process_exit == 0
    and instrumentation.count("OK (1 test)") == 1
    and instrumentation.count("INSTRUMENTATION_STATUS_CODE: 0") == 1
    and not any(re.search(r"^INSTRUMENTATION_STATUS_CODE: -[1-4]|FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed", line)
                for line in instrumentation)
    and len(timings) == 1
    and all(value > 0 for value in timings[0])
)
if success and scope == "covered":
    success = timings[0][0] <= timings[0][1]
elif success:
    instruction, capture, pending, apply, finalize, preview, total = timings[0]
    success = (capture <= pending <= total and finalize <= preview <= total and
               instruction <= total and apply <= total and
               # One saved Start read precedes up to six same-original fact reads.
               2 <= len(raw_boundaries["recover"]) <= 7 and
               all(len(raw_boundaries[name]) == 1 for name in ("start", "apply", "finalize", "preview")) and
               all(0 < values[0] <= values[1] for name in ("start", "recover", "apply", "finalize")
                   for values in raw_boundaries[name]) and
               0 < sum(raw_boundaries["preview"][0][:2]) <= raw_boundaries["preview"][0][-1])
record = {
    "sdk": sdk,
    "process_exit": process_exit,
    "passed": success,
    "scope": ("covered local sale confirm to recorded result and paid-sale preview on MainActivity" if scope == "covered" else
              "local native cash payment and sale finalization to protected financial preview on MainActivity"),
}
intent_lock = intent_lock_observation(lock_events)
if pre_master or cohost_current or own_recover or presentation or intent_lock:
    record["observed_paid_diagnostics"] = {
        "pre_master": pre_master,
        "cohost_current": cohost_current,
        "intent_lock": intent_lock,
        "own_recover": own_recover,
        "presentation": presentation,
    }
if success:
    record.update(zip(ranges, timings[0], strict=True))
    if scope == "paid":
        record["app_boundaries"] = {
            name: [dict(zip(boundary_fields[name], values, strict=True)) for values in rows]
            for name, rows in raw_boundaries.items()
        }
(stage / "public" / "installed-cashier-result.json").write_text(json.dumps(record, indent=2) + "\n")
if not success:
    raise SystemExit("Installed timing or exact selector assertion failed")
