"""Export only known synthetic assertions and inert runner measurements."""
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

from cold_pass_metrics import parse_cold_history_pass, parse_cold_join, parse_cold_workspace_read, parse_history_source

mode, stage, run = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3])
published = stage / "public"
if mode == "frozen":
    pattern = re.compile(
        r"CHB_(?:STEP_(?:BEGIN|END) [0-9]{1,4} (?:create|begin|start|capture|apply|finalize|receipt/[a-zA-Z0-9_-]+)"
        r"(?: elapsedMillis=[0-9]+)?|PAID_PREFIX [0-9]+|NATIVE_HISTORY_(?:OPEN_ENTERED|OPERATIONAL)"
        r"|NATIVE_CLOSE (?:seed(?:ed-[0-9]{1,4})?|restart)|READ_ONLY_NATIVE_CLOSE|SAME_PROCESS_REOPEN [0-9]+|FRESH_PROCESS_RESTART [0-9]+"
        r"|EXACT_(?:LOOKUPS|RECEIPTS)_RETURNED [0-9]+|PROTECTED_ORIGINALS_VERIFIED [0-9]+ SALE_COUNT [0-9]+)"
        r"|POS_NATIVE_CENSUS trial=[12] items=[0-9]+ bytes=[0-9]+ intake_ns=[0-9]+ schema_ns=[0-9]+"
        r" row_ns=[0-9]+ hash_ns=[0-9]+ close_ns=[0-9]+ total_ns=[0-9]+"
        r"|POS_NATIVE_STABLE_IMAGE trial=[12] bytes=[0-9]{1,11} elapsed_ns=[0-9]{1,19}"
        r"|POS_NATIVE_IMAGE_SEAL stage=(?:SEED|FRESH_SAME_CUT) exact=1 tamper_refused=3 prior_cut_refused=0"
        r"|POS_NATIVE_IMAGE_SEAL stage=FRESH_NEW_CUT exact=1 tamper_refused=3 prior_cut_refused=1"
        r"|POS_NATIVE_TYPED_CHECKPOINT anchors=[0-9]{1,3} receipts=[0-9]{1,4} bytes=[0-9]{1,7} roundtrip_ns=[0-9]{1,19}"
        r"|POS_NATIVE_TYPED_CHECKPOINT anchors=[0-9]{1,5} receipts=[0-9]{1,4} bytes=[0-9]{1,7}"
        r" encode_ns=[0-9]{1,19} seal_ns=[0-9]{1,19} verify_ns=[0-9]{1,19} refused=2"
        r"|POS_NATIVE_TYPED_CHECKPOINT_RESTORE (?:seed_saved=1|fresh_exact=1 fresh_new_cut_refused=1)"
        r"|POS_NATIVE_TYPED_PROJECTION_PREFLIGHT exact=[01] elapsed_ns=[0-9]{1,19}"
        r"|POS_NATIVE_TYPED_MEMBERSHIP_PREFILL receipts=[0-9]{1,4} elapsed_ns=[0-9]{1,19}"
        r"|POS_PHYSICAL_START_CUT_EQUAL exact=[01]"
        r"|POS_PHYSICAL_START_FRESH_STOCK_READ elapsed_ns=[0-9]{1,19}"
        r"|POS_PHYSICAL_START_FRESH_STOCK_EVIDENCE exact=1"
        r"|POS_PHYSICAL_START_STOCK_COVERAGE rows=[0-9]{1,4} missing_rows=[0-9]{1,4} claims=[0-9]{1,4}"
        r" missing_claims=[0-9]{1,4} publications=[0-9]{1,7} aggregate_pages=[0-9]{1,7} claim_pages=[0-9]{1,7}"
        r" setup=[0-9]{1,7} other_sources=[0-9]{1,7} bound=[01]"
        r"|POS_PHYSICAL_START_FRESH_LEGACY_CUTS count=[0-9]{1,2} elapsed_ns=[0-9]{1,19}"
        r"|POS_PHYSICAL_START_NESTED_CUTS selected=[0-9]{1,3} unavailable=[0-9]{1,3} outside=[0-9]{1,7}"
        r"|POS_PHYSICAL_START_NATIVE_LITERALS points=[0-9]{1,3} publication_selections=[0-9]{1,3}"
        r"|POS_PHYSICAL_START_VERIFIED_RETURN_REUSED count=1"
        r"|POS_RETURN_TRIAL_ALTERED_PROOF kind=[123]"
        r"|POS_DISTINCT_SETTLED_RETURN_INPUTS root=[01] sources=[01] before=[01] after=[01] stock=[01]"
        r"|POS_DISTINCT_RETURN_ORIGINALS comparable=[01] exact=[01] selected=\d+ roots=\d+"
        r"|POS_RETURN_CALLBACK_LIVE_READS points=\d+ ranges=\d+ originals=\d+ other=\d+ stock_points=\d+ stock_ranges=\d+ stock_sql=\d+"
        r"|POS_RETURN_CALLBACK_LITERAL_COVERAGE pairs=\d+ roots=\d+ fragments=\d+ unknown=\d+ selected=\d+ uncovered=\d+ inconsistent=[01]"
        r"|POS_CHECKOUT_WRITER start_ns=\d{1,19} elapsed_ns=\d{1,19} returned=[01] "
        r"queue_ns=(?:-1|\d{1,19}) room_entry_ns=(?:-1|\d{1,19}) begin_frame_ns=(?:-1|\d{1,19}) "
        r"sql_entry_ns=(?:-1|\d{1,19}) sql_body_ns=(?:-1|\d{1,19}) sql_return_ns=(?:-1|\d{1,19}) "
        r"settle_frame_ns=(?:-1|\d{1,19}) receipt_membership_ns=(?:-1|\d{1,19}) room_return_ns=(?:-1|\d{1,19})"
        r"|POS_CHECKOUT_SQL family=(?:PAIR|ROOT|FRAGMENT|STOCK_INDEX|AGGREGATE|CLAIM|OTHER) calls=\d+ elapsed_ns=\d+ maximum_ns=\d+ body_ns=\d+ step_calls=\d+ step_ns=\d+ blob_ns=\d+ blob_bytes=\d+"
        r"|POS_CHECKOUT_LIFECYCLE phase=(?:(?:PHYSICAL_START_|PHYSICAL_INSTRUCTION_|STREAMED_)[A-Z_]{1,40}) at_ns=\d{1,19}"
        r"|POS_CHECKOUT_PROCESS sale=\d{1,4} phase=(?:create|begin|start|capture|apply|finalize|receipt/[1-9])"
        r" metrics_ns=\d{1,19} cpu_ns=\d{1,19} allocations=\d{1,19} common_threads=\d{1,5}"
        r" exited_threads=\d{1,5} new_threads=\d{1,5} gc_count=\d{1,10} gc_ms=\d{1,12}"
        r"|POS_RETURN_BATCHED_ROOT_READS deferred=\d+"
        r"|POS_PHYSICAL_START_EXTERNAL_WITNESS publications=[0-9]{1,2} unsupported_cash=[0-9]{1,3}"
        r"|POS_PHYSICAL_START_LEGACY_EFFECTS callbacks=[0-9]{1,4} native_changed=[0-9]{1,4} stock_changed=[0-9]{1,4} publication_changed=[0-9]{1,4}"
        r"|POS_PHYSICAL_START_FRESH_LITERALS streamed=[0-9]{1,4} legacy=[0-9]{1,4} absent=[0-9]{1,4}"
        r" bytes=[0-9]{1,13} elapsed_ns=[0-9]{1,19}"
        r"|POS_PHYSICAL_START_WITNESS original=(?:-1|[01]) stock=(?:-1|[01]) native=(?:-1|[01])"
        r"|POS_PHYSICAL_START_LITERAL streamed=[0-9]{1,4} legacy=[0-9]{1,4} absent=[0-9]{1,4}"
        r" prepare_bytes=[0-9]{1,13} distinct_bytes=[0-9]{1,13} prepare_ns=[0-9]{1,19} verify_ns=[0-9]{1,19}"
        r"|POS_PHYSICAL_START_LITERAL_BOUND"
        r"|POS_HISTORY_JOURNAL_ABSENCE_INDEX entries=[0-9]{1,5}"
        r"|POS_NATIVE_TYPED_PROJECTION_LEDGERS employees=[0-9]{1,4} policies=[0-9]{1,4}"
        r" publications=[0-9]{1,4} admissions=[0-9]{1,4}"
        r"|POS_NATIVE_BOOTSTRAP_FINGERPRINT stage=(?:SEED|FRESH_SAME_CUT|FRESH_NEW_CUT)"
        r" employees=[0-9]{1,4} policies=[0-9]{1,4} publications=[0-9]{1,4} admissions=[0-9]{1,4} elapsed_ns=[0-9]{1,19}"
        r"|POS_NATIVE_CENSUS_CUT trial=[12] items=[0-9]+ bytes=[0-9]+ row_ns=[0-9]+"
        r" hash_ns=[0-9]+ full_typed_open_and_close_ns=[0-9]+"
        r"|POS_NATIVE_CENSUS_COVERAGE trial=[12] distinct=[0-9]+ repeated=[0-9]+ repeated_bytes=[0-9]+"
        r" image_rows=[0-9]+ image_bytes=[0-9]+ same_image_bytes=[0-9]+ plaintext_rows=[0-9]+ plaintext_bytes=[0-9]+"
        r"|POS_NATIVE_COMMAND_PROJECTION trial=[12] originals=[0-9]+ rows=[0-9]+ boundaries=[0-9]+"
        r"|POS_NATIVE_APPEND_PREFIX prefix_rows=[0-9]{1,19} prefix_bytes=[0-9]{1,19}"
        r" tail_rows=[0-9]{1,19} tail_bytes=[0-9]{1,19} tail_events=[0-9]{1,19}"
        r"|POS_NATIVE_TYPED_PREFIX prefix_commands=[0-9]{1,4} added_commands=[0-9]{1,4}"
        r" prefix_streamed=[0-9]{1,4} added_streamed=[0-9]{1,4} comparator_controls=5"
        r"|POS_NATIVE_MUTABLE_PREFIX prior_keys=[0-9]{1,4} final_keys=[0-9]{1,4}"
        r" tail_versions=[0-9]{1,4} tombstones=[0-9]{1,4} comparator_controls=5"
        r"|POS_NATIVE_PROJECTED_CUT families=6 keys=[0-9]{1,4} bytes=[0-9]{1,7} controls=4"
        r"|POS_NATIVE_PROJECTED_CUT families=22 keys=[0-9]{1,4} bytes=[0-9]{1,7} controls=10"
        r"|POS_NATIVE_AUTH_ONLY_CUT events=2 mutations=9 commands=0"
    )
    for phase in ("seed", "restart", "profile-reopen"):
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
        joins = [parsed for line in source.splitlines()
                 if (parsed := parse_cold_join(line.encode("utf-8"))) is not None]
        if joins:
            (published / "cold-join-timings.json").write_text(json.dumps(joins, indent=2) + "\n")
        reads = [parsed for line in source.splitlines()
                 if (parsed := parse_cold_workspace_read(line.encode("utf-8"))) is not None]
        if reads:
            (published / "cold-workspace-reads.json").write_text(json.dumps(reads, indent=2) + "\n")
    if mode == "cold-literal-control":
        source = (suite.findtext("system-out") or "") + (suite.findtext("system-err") or "")
        count = source.count("Native physical stage: COLD_STREAMED_LITERAL_REVALIDATED")
        (published / "cold-literal-validation.json").write_text(json.dumps({"validated_streams": count}) + "\n")
        assert count > 0, "No completed exact cold literal stream verification was observed"
elif mode in ("history-source-control", "history-event-stream-control"):
    directory = run / "adapters/app-gateway/build/test-results/jvmTest"
    reports = list(directory.glob("TEST-*.SupplierCashAncestryNativeSupportingTest.xml"))
    report = None
    if len(reports) == 1:
        suite = ET.parse(reports[0]).getroot()
        report = {field: int(suite.attrib[field]) for field in ("tests", "failures", "errors", "skipped")}
        source = (suite.findtext("system-out") or "") + (suite.findtext("system-err") or "")
        # The native assertion and the diagnostic each establish a different condition.
        # No operand values, identities or underlying exception text are exported.
        if mode == "history-source-control":
            report["indexed_absences"] = sum(
                found["indexed_absences"] for line in source.splitlines()
                if (found := parse_history_source(line.encode("utf-8"))) is not None
            )
        else:
            stream = re.compile(r"POS_HISTORY_EVENT_STREAM events=([0-9]{1,5}) versions=([0-9]{1,5}) "
                                r"chunks=([0-9]{1,8}) elapsed_ns=([0-9]{1,19})")
            observations = [
                dict(zip(("events", "versions", "chunks", "elapsed_ns"), map(int, match.groups()), strict=True))
                for line in source.splitlines() if (match := stream.fullmatch(line)) is not None
            ]
            assert observations and all(0 < row["events"] <= row["versions"] <= 4096
                                        and row["chunks"] >= row["events"] and row["elapsed_ns"] > 0
                                        for row in observations), "Missing complete native history event measurements"
            (published / "history-event-stream.json").write_text(json.dumps(observations, indent=2) + "\n")
    (published / (mode + "-tests.json")).write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))
    assert report and report["tests"] == 1 and not any(report[field] for field in ("failures", "errors", "skipped"))
    if mode == "history-source-control":
        assert report["indexed_absences"] > 0, "No authenticated journal absence lookup was observed"
elif mode in ("floor-batch-control", "workspace-control"):
    directory = run / "core/database/build/test-results/jvmTest"
    reports = list(directory.glob("TEST-*.NativeColdHistoryWorkspaceSupportingTest.xml"))
    report = None
    if len(reports) == 1:
        suite = ET.parse(reports[0]).getroot()
        report = {field: int(suite.attrib[field]) for field in ("tests", "failures", "errors", "skipped")}
    report_name = "workspace-tests.json" if mode == "workspace-control" else "floor-batch-tests.json"
    (published / report_name).write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))
    expected_count = 6 if mode == "workspace-control" else 1
    assert report == {"tests": expected_count, "failures": 0, "errors": 0, "skipped": 0}
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
elif mode in {"payment-regression", "instruction-regression"}:
    verification = {}
    modules = ("core/database",)
    if mode == "payment-regression":
        modules = ("adapters/inventory-store", "adapters/sales-store", *modules)
    for module in modules:
        suite = run / module / "build/test-results/jvmTest"
        entries = [ET.parse(path).getroot() for path in suite.glob("TEST-*.xml")]
        verification[module] = {
            field: sum(int(elem.attrib.get(field, 0)) for elem in entries)
            for field in ("tests", "failures", "errors", "skipped")
        }
    (published / (mode + ".json")).write_text(json.dumps(verification, indent=2) + "\n")
    print(json.dumps(verification))
    assert all(row["tests"] > 0 and not any(row[field] for field in ("failures", "errors", "skipped"))
               for row in verification.values()), "Incomplete regression: " + mode
elif mode == "catalog-deposit-draft":
    verification = {}
    required = {
        "adapters/app-gateway": {"DesktopDepositPublicationSupportingTest",
                                 "DesktopMasterEmployeeConsoleNativeTest",
                                 "DesktopMasterEmployeeDataTest",
                                 "CatalogProductVersionFrozenMappingTest"},
        "adapters/catalog-store": {"ContainerDepositPublicationTest"},
        "ui/app": {"CompanySkuDepositEditorSupportingTest", "CatalogProductVersionFrozenEditorTest",
                   "ReturnStructuredPromiseFrozenEditorSlicesTest"},
    }
    for module, expected in required.items():
        directory = run / module / "build/test-results/jvmTest"
        suites = {element.get("name", "").rsplit(".", 1)[-1].removesuffix("[jvm]"): element
                  for element in (ET.parse(path).getroot() for path in directory.glob("TEST-*.xml"))}
        fields = ("tests", "failures", "errors", "skipped")
        verification[module] = {
            "suites": {name: {field: int(suite.get(field, 0)) for field in fields}
                       for name, suite in sorted(suites.items())},
            "missing": sorted(expected - suites.keys()),
        }
        if module == "ui/app" and not any("CatalogUnitAuthoring" in name for name in suites):
            verification[module]["missing"].append("CatalogUnitAuthoring")
        if module == "adapters/app-gateway":
            native = suites.get("DesktopMasterEmployeeConsoleNativeTest")
            verification[module]["executed_original_retry"] = native is not None and any(
                row.get("name", "").startswith("originalDepositPublicationRefusesSameIdentityWithAnyChangedTerm")
                and all(row.find(outcome) is None for outcome in ("failure", "error", "skipped"))
                for row in native.findall("testcase"))
    output = stage / "catalog-deposit-draft.raw"
    raw = output.read_text(errors="replace") if output.exists() else ""
    verification["compile"] = {name: bool(re.search(
        rf"^> Task {re.escape(name)}(?: UP-TO-DATE| FROM-CACHE)?$", raw, re.M))
        for name in (":apps:desktop:compileKotlin", ":apps:android:compileLocalTestingKotlin")}
    (published / "catalog-deposit-draft.json").write_text(json.dumps(verification, indent=2) + "\n")
    print(json.dumps(verification))
    assert all(verification["compile"].values())
    assert verification["adapters/app-gateway"]["executed_original_retry"]
    assert all(not verification[module]["missing"] and verification[module]["suites"] and all(
        row["tests"] and not any(row[field] for field in ("failures", "errors", "skipped"))
        for row in verification[module]["suites"].values()) for module in required)

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
