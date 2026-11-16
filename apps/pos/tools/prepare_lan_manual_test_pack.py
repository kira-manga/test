#!/usr/bin/env python3
"""Copy existing manual expectations into a local run pack; execute no test or app.

This is an implementation-authored organizer, NOT an independent test design.
The versioned source expectations remain authoritative and are never rewritten.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
from pathlib import Path
import shutil
import subprocess


ROOT = Path(__file__).resolve().parents[3]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1_048_576), b""):
            digest.update(block)
    return digest.hexdigest()


def frozen_design(version: str) -> tuple[Path, dict, dict]:
    source = ROOT / "qa/pos/designs" / f"lan-master-client-{version}"
    freeze = json.loads((source / "freeze.json").read_text())
    for artifact in freeze["artifacts"]:
        path = source / artifact["path"]
        if path.parent != source or not path.is_file() or path.is_symlink():
            raise ValueError(f"Invalid frozen artifact: {version}/{artifact['path']}")
        if path.stat().st_size != artifact["bytes"] or sha256(path) != artifact["sha256"]:
            raise ValueError(f"Frozen artifact differs: {version}/{artifact['path']}")
    cases = json.loads((source / "cases.json").read_text())
    if len(cases["cases"]) != freeze["case_count"]:
        raise ValueError(f"Case count differs: {version}")
    return source, freeze, cases


def frozen_contracts(designs: list[tuple[Path, dict, dict]]) -> list[dict]:
    """Include every normative contract used by the unchanged LAN designs."""
    contracts = {}
    for _, freeze, _ in designs:
        for identity in freeze["allowed_input_identities"]:
            relative = Path(identity["path"])
            if relative.parent != Path("qa/pos/contracts"):
                continue
            if relative in contracts and contracts[relative] != identity:
                raise ValueError(f"Conflicting frozen contract versions: {relative}")
            path = ROOT / relative
            if not path.is_file() or path.is_symlink():
                raise ValueError(f"Frozen contract required: {relative}")
            if path.stat().st_size != identity["bytes"] or sha256(path) != identity["sha256"]:
                raise ValueError(f"Frozen contract differs: {relative}")
            contracts[relative] = identity
    return [contracts[path] for path in sorted(contracts)]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="New local directory; existing paths are refused")
    parser.add_argument("--android", type=Path, help="Actual APK to identify; the binary is not copied")
    parser.add_argument("--macos", type=Path, help="Actual macOS installer to identify")
    parser.add_argument("--windows", type=Path, help="Actual Windows installer to identify")
    args = parser.parse_args()

    # Validate every input before creating the result directory.
    designs = [frozen_design(version) for version in ("v1", "v2")]
    contracts = frozen_contracts(designs)
    artifacts = {}
    for platform in ("android", "macos", "windows"):
        path = getattr(args, platform)
        if path is None:
            artifacts[platform] = {"state": "NO_ARTIFACT_ATTACHED"}
        else:
            if not path.is_file() or path.is_symlink():
                raise ValueError(f"Actual installer file required: {platform}")
            artifacts[platform] = {"state": "IDENTIFIED_NOT_TESTED", "filename": path.name,
                "bytes": path.stat().st_size, "sha256": sha256(path)}
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=normal"], cwd=ROOT))
    output = args.output.resolve()
    if output.exists():
        raise ValueError("Output already exists; preserve previous results and choose a new directory")
    output.mkdir(parents=True)

    manual = output / "docs/pos/manual-testing"
    shutil.copytree(ROOT / "docs/pos/manual-testing", manual)
    shutil.copy2(ROOT / "docs/pos/CURRENT-STATUS.md", output / "docs/pos/CURRENT-STATUS.md")
    # Preserve the existing relative source links without changing the copied expectations.
    (output / "docs/pos/original-handoff").mkdir()
    for filename in ("POS_ACCEPTANCE_SCENARIOS.md", "POS_TEST_AND_QUALITY_PLAN.md"):
        shutil.copy2(ROOT / "docs/pos/original-handoff" / filename, output / "docs/pos/original-handoff" / filename)
    (output / "qa/pos/contracts").mkdir(parents=True)
    for identity in contracts:
        shutil.copy2(ROOT / identity["path"], output / identity["path"])
    rows = []
    design_records = []
    for source, freeze, cases in designs:
        destination = output / "qa/pos/designs" / source.name
        destination.mkdir(parents=True)
        for artifact in freeze["artifacts"]:
            shutil.copy2(source / artifact["path"], destination / artifact["path"])
        shutil.copy2(source / "freeze.json", destination / "freeze.json")
        design_records.append({"suite_id": freeze["suite_id"], "version": freeze["design_version"],
            "case_count": freeze["case_count"], "freeze_sha256": sha256(source / "freeze.json")})
        for case in cases["cases"]:
            for variant in case["variants"]:
                rows.append({"suite": freeze["suite_id"], "case": case["id"], "title": case["title"],
                    "variant": variant if isinstance(variant, str) else json.dumps(variant, ensure_ascii=False),
                    "result": "NOT_RUN", "expected_reference": f"qa/pos/designs/{source.name}/cases.json#{case['id']}",
                    "actions_verbatim_json": json.dumps(case["actions"], ensure_ascii=False),
                    "expected_verbatim_json": json.dumps(case["expected"], ensure_ascii=False),
                    "tested_revision": "", "master_device_os": "", "client_1_device_os": "",
                    "client_2_device_os": "", "actual_observations": "", "evidence": "", "defect_or_blocker": ""})
    with (output / "LAN-RESULTS.csv").open("w", newline="", encoding="utf-8") as result:
        writer = csv.DictWriter(result, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    (output / "candidate.json").write_text(json.dumps({"source_revision_at_preparation": revision,
        "source_state": "WORKING_TREE_NOT_A_CLEAN_COMMIT" if dirty else "CLEAN_COMMIT",
        "qualification": "NOT_QUALIFIED_BY_PACK_PREPARATION", "artifacts": artifacts,
        "designs": design_records, "contracts": contracts,
        "lan_variant_rows": len(rows), "owner_acceptance": "NOT_RUN"}, indent=2) + "\n")
    (output / "START-HERE.md").write_text(
        "# Manual test run pack\n\n"
        "Preparation runs no test and qualifies no build. Every result starts NOT_RUN. "
        "The artifact entries identify supplied files only; they do not prove installation or functionality.\n\n"
        "1. Read candidate.json and the current application delivery notes. Missing code, known defects "
        "and missing installers remain blockers for their affected cases.\n"
        "2. Use one real Master and at least two simultaneously connected Clients on an isolated LAN, "
        "with disposable synthetic data. Record each device, OS, role and installer identity.\n"
        "3. Follow qa/pos/designs/lan-master-client-v1/manual-plan.md, its unchanged fixtures, cases and suite invariants; "
        "then the applicable separately versioned v2 cases. Each variant is separate.\n"
        "4. Record actual observations in LAN-RESULTS.csv. Keep expected text unchanged. "
        "FAIL, BLOCKED, SKIPPED and NOT_RUN never count as PASS; retain failed runs.\n"
        "5. Use docs/pos/manual-testing/README.md for all 105 feature definitions, 141 business scenarios, "
        "70 resilience cases and 79 quality gates, including every completed feature. "
        "Unfinished scope remains explicitly blocked rather than removed.\n\n"
        "This organizer was authored with implementation access. It creates no new independent "
        "expectation and claims no independent review, automated result or real-device acceptance.\n")
    print(f"Prepared {len(rows)} LAN variant rows from 112 unchanged cases, plus the complete existing manual catalog.")
    print("All results: NOT_RUN. No application, network, installer or device test was executed.")


if __name__ == "__main__":
    main()
