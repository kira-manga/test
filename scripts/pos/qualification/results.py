"""Export only known synthetic assertions and inert runner measurements."""
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

from cold_pass_metrics import parse_cold_history_pass, parse_history_source

mode, stage, run = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3])
published = stage / "public"
if mode == "frozen":
    pattern = re.compile(
        r"CHB_(?:STEP_(?:BEGIN|END) [0-9]{1,4} (?:create|begin|start|capture|apply|finalize|receipt/[a-zA-Z0-9_-]+)"
        r"(?: elapsedMillis=[0-9]+)?|PAID_PREFIX [0-9]+|NATIVE_HISTORY_(?:OPEN_ENTERED|OPERATIONAL)"
        r"|NATIVE_CLOSE (?:seed(?:ed-[0-9]{1,4})?|restart)|SAME_PROCESS_REOPEN [0-9]+|FRESH_PROCESS_RESTART [0-9]+"
        r"|EXACT_(?:LOOKUPS|RECEIPTS)_RETURNED [0-9]+|PROTECTED_ORIGINALS_VERIFIED [0-9]+ SALE_COUNT [0-9]+)"
        r"|POS_NATIVE_CENSUS trial=[12] items=[0-9]+ bytes=[0-9]+ intake_ns=[0-9]+ schema_ns=[0-9]+"
        r" row_ns=[0-9]+ hash_ns=[0-9]+ close_ns=[0-9]+ total_ns=[0-9]+"
    )
    for phase in ("seed", "restart"):
        log = stage / f"{phase}.raw"
        if not log.exists():
            continue
        lines = log.read_text(errors="replace").splitlines()
        allowed = [line for line in lines if pattern.fullmatch(line)]
        # Record structured Gradle/process failures without source lines or stack arguments.
        for line in lines:
            match = re.fullmatch(r"Execution failed for task '(:[a-zA-Z0-9:_-]+)'\.", line)
            if match:
                allowed.append(f"GRADLE_FAILURE {match[1]}")
            match = re.fullmatch(r".*finished with non-zero exit value ([0-9]+)", line)
            if match:
                allowed.append(f"PROCESS_EXIT {match[1]}")
        (published / f"{phase}.assertions.txt").write_text("\n".join(allowed) + "\n")
        print(f"{phase}: {len(allowed)} assertion/progress lines")
        print("\n".join(allowed[-18:]))
elif mode == "census":
    census = json.loads((run / "VERIFIED-CENSUS.json").read_text())
    keys = ("paid_sales", "originals", "journal", "audit", "outbox", "both_original_issue_portions_per_sale_verified",
            "receipt_heads", "receipt_events", "receipt_event_portions", "cold_same_process_and_fresh_process", "bound_input_sha256")
    safe = {key: census[key] for key in keys}
    safe["scope"] = "frozen 1000-sale" if safe["paid_sales"] == 1000 else f"{safe['paid_sales']}-sale supporting prefix"
    (published / "verified-census.json").write_text(json.dumps(safe, indent=2) + "\n")
    print(json.dumps(safe))
elif mode in ("cold-payment-control", "cold-pass-control", "cold-owner-control", "cold-literal-control"):
    suite_dir = run / "adapters/app-gateway/build/test-results/jvmTest"
    entries = list(suite_dir.glob("TEST-*.PaidRetailReceiptColdNativeSupportingTest.xml"))
    report = None
    if len(entries) == 1:
        suite = ET.parse(entries[0]).getroot()
        report = {field: int(suite.attrib[field]) for field in ("tests", "failures", "errors", "skipped")}
        timing = re.compile(r"Paid retail receipt original reopened in [0-9.]+(?:ms|s)")
        lines = (suite.findtext("system-out") or "").splitlines()
        (published / "cold-payment-timings.txt").write_text("\n".join(line for line in lines if timing.fullmatch(line)) + "\n")
    (published / "cold-payment-tests.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))
    assert report == {"tests": 2, "failures": 0, "errors": 0, "skipped": 0}
    if mode == "cold-owner-control":
        source = (suite.findtext("system-out") or "") + (suite.findtext("system-err") or "")
        count = source.count("Native physical stage: COLD_STREAMED_PREDECESSOR_REUSED")
        (published / "cold-predecessor-reuse.json").write_text(json.dumps({"reuse_observations": count}) + "\n")
        assert count > 0, "No observed exact native predecessor reuse"
    if mode == "cold-pass-control":
        source = (suite.findtext("system-out") or "") + (suite.findtext("system-err") or "")
        passes = [parsed for line in source.splitlines()
                  if (parsed := parse_cold_history_pass(line.encode("utf-8"))) is not None]
        (published / "cold-native-passes.json").write_text(json.dumps(passes, indent=2) + "\n")
        assert {item["phase"] for item in passes} == {"CAPTURE", "REPLAY"}, "Missing cold pass timings"
    if mode == "cold-literal-control":
        source = (suite.findtext("system-out") or "") + (suite.findtext("system-err") or "")
        count = source.count("Native physical stage: COLD_STREAMED_LITERAL_REVALIDATED")
        (published / "cold-literal-validation.json").write_text(json.dumps({"validated_streams": count}) + "\n")
        assert count > 0, "No completed exact cold literal stream verification was observed"
elif mode == "history-source-control":
    directory = run / "adapters/app-gateway/build/test-results/jvmTest"
    reports = list(directory.glob("TEST-*.SupplierCashAncestryNativeSupportingTest.xml"))
    report = None
    if len(reports) == 1:
        suite = ET.parse(reports[0]).getroot()
        report = {field: int(suite.attrib[field]) for field in ("tests", "failures", "errors", "skipped")}
        source = (suite.findtext("system-out") or "") + (suite.findtext("system-err") or "")
        # The native assertion and the diagnostic each establish a different condition.
        # No operand values, identities or underlying exception text are exported.
        report["indexed_absences"] = sum(
            found["indexed_absences"] for line in source.splitlines()
            if (found := parse_history_source(line.encode("utf-8"))) is not None
        )
    (published / "history-source-control-tests.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))
    assert report and report["tests"] == 1 and not any(report[field] for field in ("failures", "errors", "skipped"))
    assert report["indexed_absences"] > 0, "No authenticated journal absence lookup was observed"
elif mode == "floor-batch-control":
    directory = run / "core/database/build/test-results/jvmTest"
    reports = list(directory.glob("TEST-*.NativeColdHistoryWorkspaceSupportingTest.xml"))
    report = None
    if len(reports) == 1:
        suite = ET.parse(reports[0]).getroot()
        report = {field: int(suite.attrib[field]) for field in ("tests", "failures", "errors", "skipped")}
    (published / "floor-batch-tests.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))
    assert report == {"tests": 1, "failures": 0, "errors": 0, "skipped": 0}
elif mode == "retained-reader":
    directory = run / "core/database/build/test-results/jvmTest"
    suites = {}
    for name in ("RetainedFragmentPageSupportingTest", "RetainedCommandReadTest", "NativeOriginalSourceRootSupportingTest"):
        entries = list(directory.glob(f"TEST-*.{name}.xml"))
        if len(entries) == 1:
            suite = ET.parse(entries[0]).getroot()
            suites[name] = {field: int(suite.attrib[field]) for field in ("tests", "failures", "errors", "skipped")}
        else:
            suites[name] = None
    (published / "retained-reader-tests.json").write_text(json.dumps(suites, indent=2) + "\n")
    print(json.dumps(suites))
    assert all(row and row["tests"] and not any(row[field] for field in ("failures", "errors", "skipped"))
               for row in suites.values())
elif mode == "opening-control":
    directory = run / "core/database/build/test-results/jvmTest"
    entries = list(directory.glob("TEST-*LanBusinessTransactionIntegrationTest*.xml"))
    results = None
    if len(entries) == 1:
        suite = ET.parse(entries[0]).getroot()
        results = {field: int(suite.attrib[field]) for field in ("tests", "failures", "errors", "skipped")}
        if results["tests"] == 1:
            (stage / "opening-control.xml").write_bytes(entries[0].read_bytes())
    (published / "opening-control-tests.json").write_text(json.dumps(results, indent=2) + "\n")
    print(json.dumps(results))
    assert results == {"tests": 1, "failures": 0, "errors": 0, "skipped": 0}
elif mode == "payment-regression":
    verification = {}
    for module in ("adapters/inventory-store", "adapters/sales-store", "core/database"):
        suite = run / module / "build/test-results/jvmTest"
        entries = [ET.parse(path).getroot() for path in suite.glob("TEST-*.xml")]
        verification[module] = {
            field: sum(int(elem.attrib.get(field, 0)) for elem in entries)
            for field in ("tests", "failures", "errors", "skipped")
        }
    (published / "payment-regression.json").write_text(json.dumps(verification, indent=2) + "\n")
    print(json.dumps(verification))
    assert all(row["tests"] > 0 and not any(row[field] for field in ("failures", "errors", "skipped"))
               for row in verification.values()), "Incomplete inventory, sales or native proof regression"
else:
    suites = {
        "core:database": "CapturedMatchSourceDeferralNativeSupportingTest",
        "domain:inventory": "InterBranchTransferTest",
        "adapters:inventory-store": "InterBranchTransferEvidenceV1Test",
    }
    results = {}
    for module, suite in suites.items():
        directory = run.joinpath(*module.split(":"), "build/test-results/jvmTest")
        candidates = list(directory.glob(f"TEST-*.{suite}.xml"))
        if candidates:
            elem = ET.parse(candidates[0]).getroot()
            results[suite] = {name: int(elem.attrib[name]) for name in ("tests", "failures", "errors", "skipped")}
        else:
            results[suite] = None
    (published / "companion-tests.json").write_text(json.dumps(results, indent=2) + "\n")
    print(json.dumps(results))
    assert all(value and value["tests"] > 0 and not any(value[field] for field in ("failures", "errors", "skipped"))
               for value in results.values()), "Incomplete companion verification"
