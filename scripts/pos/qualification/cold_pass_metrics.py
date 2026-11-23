"""Bounded parsers for native history timing records."""
import re


cold_history_pass = re.compile(
    rb"POS_COLD_PASS (CAPTURE|REPLAY) "
    rb"(CENSUS|EMPLOYEE_ENROLLMENTS|EVENTS|LINEAGE|COMMANDS|STOCK_AGGREGATES|STOCK_REVERSE|"
    rb"ACCOUNT_RELATIONS|ACCESS_RELATIONS|ARCHIVE_RELATIONS|APPROVAL_RELATIONS) "
    rb"(BRANCH|OPERATION|AGGREGATE|CLAIM|JOURNAL|AUDIT|OUTBOX|ACCOUNT|POLICY|ADMISSION|"
    rb"THROTTLE|ACCESS|CONFIGURATION|REFRESH|AUTH_EVENT|ARCHIVE|APPROVAL|STOCK_POSITION|"
    rb"STOCK_ALLOCATION|STOCK_RESERVATION|STOCK_VALUE|JOURNAL_FRAGMENT|HEAD|EVENT|ROW_VERSION|"
    rb"CHUNK|ROW_TIP|WRITE_FRAME|CAPTURE) "
    rb"elapsed_ns=([0-9]{1,19}) original_calls=([0-9]{1,12}) original_ns=([0-9]{1,19}) "
    rb"workspace_reads=([0-9]{1,12}) workspace_read_ns=([0-9]{1,19}) "
    rb"workspace_writes=([0-9]{1,12}) workspace_write_ns=([0-9]{1,19})"
)
cold_history_pass_counters = (
    "elapsed_ns", "original_calls", "original_ns", "workspace_reads", "workspace_read_ns",
    "workspace_writes", "workspace_write_ns",
)

def parse_cold_history_pass(line):
    match = cold_history_pass.fullmatch(line)
    if match is None:
        return None
    phase, history_pass, table, *values = match.groups()
    return {
        "phase": phase.decode("ascii"),
        "history_pass": history_pass.decode("ascii"),
        "table": table.decode("ascii"),
        **dict(zip(cold_history_pass_counters, map(int, values), strict=True)),
    }


history_source_fields = (
    "events", "versions", "chunks", "capture_ns", "total_ns",
    "selected_images", "selected_chunks", "selected_immutable", "indexed_absences",
)
history_source = re.compile(
    rb"POS_HISTORY_SOURCE (SCALAR|LEGACY) " +
    b" ".join(field.encode("ascii") + rb"=([0-9]{1,19})" for field in history_source_fields)
)

def parse_history_source(line):
    match = history_source.fullmatch(line)
    if match is None:
        return None
    source, *values = match.groups()
    fields = dict(zip(history_source_fields, map(int, values), strict=True))
    if not 0 < fields["capture_ns"] <= fields["total_ns"]:
        return None
    return {"source": source.decode("ascii"), **fields}
