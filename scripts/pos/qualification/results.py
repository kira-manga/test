"""Export only known synthetic assertions and inert runner measurements."""
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

mode, stage, run = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3])
published = stage / "public"
if mode == "frozen":
    pattern = re.compile(
        r"CHB_(?:STEP_(?:BEGIN|END) [0-9]{1,4} (?:create|begin|start|capture|apply|finalize|receipt/[a-zA-Z0-9_-]+)"
        r"(?: elapsedMillis=[0-9]+)?|PAID_PREFIX [0-9]+|NATIVE_HISTORY_(?:OPEN_ENTERED|OPERATIONAL)"
        r"|NATIVE_CLOSE (?:seed(?:ed-[0-9]{1,4})?|restart)|SAME_PROCESS_REOPEN [0-9]+|FRESH_PROCESS_RESTART [0-9]+"
        r"|EXACT_(?:LOOKUPS|RECEIPTS)_RETURNED [0-9]+|PROTECTED_ORIGINALS_VERIFIED [0-9]+ SALE_COUNT [0-9]+)"
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
