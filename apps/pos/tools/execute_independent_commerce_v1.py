#!/usr/bin/env python3
"""Prepare and execute the frozen commerce-v1 components with ordinary Gradle outputs.

The JVM runtime descriptor is reused and refreshed by normal Gradle preparation.
Actual Java execution uses synthetic fixtures in a real network namespace; v2
retains its separate-JVM crash/restart schedule. Components never imply full passes.
Execution reuses disposable current fixtures. Preserve meaningful failure logs and
results before another execution.
"""
from __future__ import annotations

import argparse
import base64
import binascii
import datetime as dt
import fcntl
import hashlib
import json
import os
from pathlib import Path
import resource
import signal
import subprocess
import sys
import time
import uuid

sys.dont_write_bytecode = True
from execute_independent_auth_v1 import (
    GateError, VerificationInterrupted, fresh_run, hash_bytes, isolated_environment,
    network_proof, observe_exit, read_json, read_runtime, require, run_process,
    signal_latch, write_json,
)

APP = Path(__file__).resolve().parents[1]
QA = APP.parents[1] / "qa" / "pos"
FREEZE = QA / "freezes/commerce-v1"
WIRING = QA / "wiring/commerce-v1"
OUTPUT = APP / ".workspace/acceptance/commerce-v1"
RUNTIME = OUTPUT / "runtime.json"
FREEZE_HASH = "45b4e5cdf94f73fb51726acacfe96f742ca4a61d167ad425d5637a277a2eec0d"
CASES_HASH = "755831e35d5fb93bc2f48828d891b34f4147b103549a97fb05e5ee9716ea9d04"
BINDING_HASH = "86f423dc5f40341fa4f0c157736faf6bb2ebf48beb2d26ea90eca08e039d4476"
EXPANSION_BINDING_HASH = "46b14bfed68ab484ad448706fb9fbf5ebc66e76ea9ea3239c5c103a2a9874871"
MAIN = "com.ukkera.pos.database.CommerceV1Runner"
PROFILE = "independent-commerce-v1-slices-2"
EXPANSION_PROFILE = "independent-commerce-v1-expanded-slices-3"
TARGET = ":core:database:prepareIndependentCommerceV1"

LIMITATIONS = [
    "Direct production endpoint with synthetic initial trust; not installed CMP UI",
    "Synthetic peers/key custody/writer history are supplied assumptions, not platform qualification",
    "Real LAN/discovery/P2pKit request identities/partitions NOT_RUN",
    "Process restart/crash, pre/post-commit faults, races, cancellation, restore NOT_RUN",
    "Windows/macOS/Android/physical iPhone/iPad master and client matrices NOT_RUN",
    "Central phase HELD pending owner's explicit hands-on approval",
]


EXPANSION_LIMITATIONS = [
    "Direct production endpoint with synthetic trust/key custody/peers/history; not installed CMP UI or qualified OS custody",
    "Real LAN/discovery/P2pKit transport identities/partitions NOT_RUN; unissued bearer is not missing-wire-bearer coverage",
    "Only named prearmed direct-adapter drop and actual separate-JVM restart cuts; production prewrite/precommit faults NOT_RUN",
    "Barrier submission components are not physical cash/stock handover or all valid serial-history/platform qualification",
    "UI cancellation/private caches/current-grant/foreign-business-relations/restore/full reports and unwired boundary/stale variants NOT_RUN",
    "Windows/macOS/Android/physical iPhone/iPad master-client matrices NOT_RUN",
    "Central phase HELD pending owner's explicit hands-on approval; zero whole assertion/case/scenario passes",
]


def now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def digest(path: Path) -> str:
    return hash_bytes(Path(path).read_bytes())


def canonical(value) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def verify_frozen() -> tuple[dict, dict]:
    if digest(FREEZE / "FREEZE-MANIFEST.json") != FREEZE_HASH or digest(FREEZE / "cases.json") != CASES_HASH:
        raise RuntimeError("Frozen commerce suite changed; refusing execution")
    for row in read_json(FREEZE / "FREEZE-MANIFEST.json")["files"]:
        path = FREEZE / row["path"]
        if path.resolve().parent != FREEZE.resolve() or path.stat().st_size != row["bytes"] or digest(path) != row["sha256"]:
            raise RuntimeError("Frozen input integrity mismatch")
    if digest(WIRING / "BINDING-v1.json") != BINDING_HASH:
        raise RuntimeError("Reviewed execution binding changed; require a new version, not a rewritten oracle")
    return read_json(FREEZE / "cases.json"), read_json(WIRING / "BINDING-v1.json")


def selected_binding(version: str) -> tuple[dict, dict, str, str]:
    """Read the existing frozen wiring revision."""
    suite, original = verify_frozen()
    require(version in {"v1", "v2"}, "Unknown commerce binding revision")
    if version == "v1":
        return suite, original, BINDING_HASH, PROFILE
    require(digest(WIRING / "BINDING-v2.json") == EXPANSION_BINDING_HASH,
            "Expanded binding changed; require a new reviewed version")
    binding = read_json(WIRING / "BINDING-v2.json")
    require(binding.get("version") == "commerce-cash-binding-v2" and
            binding.get("frozen_cases_sha256") == CASES_HASH and
            binding.get("preserved_v1_binding_sha256") == BINDING_HASH,
            "Expanded binding does not match the original frozen suite")
    return suite, binding, EXPANSION_BINDING_HASH, EXPANSION_PROFILE


def selected_runtime_input(suite: dict, binding: dict) -> dict:
    selected = {family["case_id"] for family in binding["families"].values()}
    return {"frozen_cases_sha256": CASES_HASH, "binding": binding,
            "cases": [case for case in suite["cases"] if case["case_id"] in selected]}


def initial_inventory(suite: dict) -> dict:
    cases = []
    for case in suite["cases"]:
        assertions = [{"ordinal": i, "frozen_text": text, "status": "NOT_RUN", "observations": []}
                      for i, text in enumerate(case["assertions"], 1)]
        variants = sorted({v for target in case["per_command_variant_targets"] for v in target["required_variants"]})
        cases.append({
            "case_id": case["case_id"], "family_id": case["family_id"], "status": "NOT_RUN",
            "frozen_case_sha256": hashlib.sha256(canonical(case)).hexdigest(),
            "assertions": assertions,
            "mandatory_variants": {name: "NOT_RUN" for name in variants},
            "required_execution_modes": [{"frozen_definition": mode, "status": "NOT_RUN"}
                                         for mode in case["required_execution_modes"]],
            "scenario_ids": case["scenario_ids"], "complete_case_pass_allowed": False,
        })
    return {"schema": "independent-commerce-inventory/v1", "frozen_cases_sha256": CASES_HASH,
            "cases": cases, "components": [], "limitations": LIMITATIONS, "any_case_pass": False}


def compile_command(descriptor=RUNTIME, offline=False) -> list[str]:
    command = ["./gradlew", "--no-daemon", "--console=plain"]
    if offline:
        command.append("--offline")
    return command + ["--init-script", "tools/independent_commerce_v1.gradle",
                      "-PindependentCommerceV1RuntimeDescriptor=" + str(descriptor), TARGET]


def prepare_runtime(descriptor, stop, offline=False, timeout=900):
    phase = run_process(compile_command(descriptor, offline), OUTPUT / "compile.log", timeout, stop)
    require(phase["exit_code"] == 0, "Commerce runtime preparation failed; see compile.log")
    runtime = read_runtime(descriptor, "independent-commerce-compiled-runtime/v1", TARGET, MAIN)
    write_json(OUTPUT / "compilation.json", {"status": "COMPILED_NOT_EXECUTED", "runtime_descriptor": str(descriptor),
                                             "phase": phase, "application_acceptance": False})
    return runtime


def isolated_command(run: Path) -> list[str]:
    return ["/usr/bin/unshare", "--net", "--", sys.executable,
            str(APP / "tools/execute_independent_commerce_v1.py"), "--_isolated-child", str(run)]


def isolated_child(value: str) -> int:
    run = Path(value).resolve()
    require(run == OUTPUT / "current", "Unknown synthetic commerce output")
    record = read_json(run / "run.json")
    request = read_json(run / "execution-request.json")
    require(request.get("profile") == record["profile"] and request.get("invocation") == record["invocation"] and
            request.get("mode") == "execute", "Invalid commerce execution request")
    proof = network_proof(request["invocation"], request["parent_network_namespace"])
    proof["schema"] = "independent-commerce-network-denial/v1"
    write_json(run / "network-isolation.json", proof)
    runtime = read_runtime(record["runtime_descriptor"], "independent-commerce-compiled-runtime/v1", TARGET, MAIN)
    if record["binding_version"] == "v2":
        return isolated_expansion_supervisor(run, runtime, runtime["classpath"], proof)
    command = [runtime["javaExecutable"], "-Xmx768m", "-XX:ActiveProcessorCount=2",
               "-Djava.awt.headless=true", "-XX:+DisableAttachMechanism",
               "-XX:-HeapDumpOnOutOfMemoryError", "-XX:-CreateCoredumpOnCrash",
               "-Duser.home=" + str(run / "private-home"), "-Djava.io.tmpdir=" + str(run / "private-tmp"),
               "-cp", os.pathsep.join(runtime["classpath"]), MAIN,
               str(run / "runtime-input.json"), str(run / "observations")]
    write_json(run / "java-invocation.json", {"command": command, "profile": PROFILE,
               "runtime_descriptor": record["runtime_descriptor"], "network_proof": "network-isolation.json",
               "junit_discovery": False})
    os.execve(runtime["javaExecutable"], command, dict(os.environ))
    raise GateError("Java exec unexpectedly returned")


def new_synthetic_capsule() -> tuple[int, dict]:
    """Generate only disposable fixture secrets, seal them, and retain only the fd.

    This is not a durable key store or a backup facility. It is called inside the
    already-proven NEW network namespace, never during planning or compilation.
    No contents, password fingerprints or key hashes are returned as evidence.
    """
    fd = os.memfd_create("commerce-v2-synthetic-capsule", os.MFD_CLOEXEC | os.MFD_ALLOW_SEALING)
    alphabet = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    raw = bytearray(b"COMCAP2\x00")
    random_passwords = bytearray()
    try:
        raw.extend(os.urandom(64))
        random_passwords.extend(os.urandom(120))
        raw.extend(alphabet[value & 63] for value in random_passwords)
        require(len(raw) == 192, "Synthetic fixture capsule format mismatch")
        offset = 0
        while offset < len(raw):
            written = os.write(fd, memoryview(raw)[offset:])
            require(written > 0, "Synthetic capsule write did not progress")
            offset += written
        os.lseek(fd, 0, os.SEEK_SET)
        seals = fcntl.F_SEAL_WRITE | fcntl.F_SEAL_GROW | fcntl.F_SEAL_SHRINK | fcntl.F_SEAL_SEAL
        fcntl.fcntl(fd, fcntl.F_ADD_SEALS, seals)
        require(fcntl.fcntl(fd, fcntl.F_GET_SEALS) == seals and os.fstat(fd).st_size == 192,
                "Synthetic capsule was not completely sealed")
        return fd, {"bytes": 192, "pass_fds": [fd], "seals": ["F_SEAL_WRITE", "F_SEAL_GROW", "F_SEAL_SHRINK", "F_SEAL_SEAL"],
                    "created_after_network_proof": True, "secret_material_archived": False,
                    "memory_locked": False, "os_key_custody_qualified": False,
                    "crosses_unshare": False}
    except BaseException:
        os.close(fd)
        raise
    finally:
        for index in range(len(raw)):
            raw[index] = 0
        for index in range(len(random_passwords)):
            random_passwords[index] = 0


def supervise_incarnation(run: Path, job: dict, phase: dict, capsule_fd: int, runtime: dict,
                          manifest: dict, classpath: list[str], namespace: str, stop) -> dict:
    """One exact owned child; never reap its PID before its final kill/cleanup.

    The child stays in the externally supervised process group, so the outer
    unreaped leader protects final group cleanup even if this supervisor dies.
    Watchdog polling below does NOT schedule competing business submissions.
    """
    phase_index = phase["index"]
    directory = run / "observations" / job["family"] / job["id"] / f"phase-{phase_index}"
    supervision = run / "supervision" / job["family"] / job["id"]
    directory.mkdir(parents=True, exist_ok=True)
    supervision.mkdir(parents=True, exist_ok=True)
    input_path = supervision / f"phase-{phase_index}.input.json"
    phase_request = {"job_id": job["id"], "phase_index": phase_index, "capsule_fd": capsule_fd,
                     "database_path": str(directory.parent / "state/master.sqlite"),
                     "prior_observations": str(directory.parent / "phase-0") if phase_index else "NONE"}
    write_json(input_path, {**runtime, "phase_request": phase_request})
    command = [manifest["javaExecutable"], "-Xmx768m", "-XX:ActiveProcessorCount=2",
               "-Djava.awt.headless=true", "-XX:+DisableAttachMechanism", "-XX:-HeapDumpOnOutOfMemoryError",
               "-XX:-CreateCoredumpOnCrash", "-Duser.home=" + str(run / "private-home"),
               "-Djava.io.tmpdir=" + str(run / "private-tmp"), "-cp", os.pathsep.join(classpath),
               MAIN, str(input_path), str(directory)]
    require(os.readlink("/proc/self/ns/net") == namespace, "Supervisor namespace changed before JVM startup")
    invocation = {"command": command, "input_sha256": digest(input_path), "pass_fds": [capsule_fd],
                  "close_fds": True, "new_session": False, "process_group": os.getpgrp(),
                  "network_namespace": namespace, "junit_discovery": False,
                  "secrets_in_argv_environment_or_output": False}
    write_json(supervision / f"phase-{phase_index}.invocation.json", invocation)
    result = {"job_id": job["id"], "phase_index": phase_index, "started_utc": now(),
              "expected_cut": phase["expected_cut"], "status": "STARTED_NOT_ACCEPTED",
              "process_reaped": False, "pid": None, "exit_code": None, "intentional_SIGKILL": False,
              "network_namespace": namespace, "pass_fds": [capsule_fd],
              "parent_owns_room_or_business_service": False,
              "observation_directory": str(directory.relative_to(run))}
    process = None
    log = supervision / f"phase-{phase_index}.log"
    try:
        stop.check()
        with log.open("xb") as stream:
            process = subprocess.Popen(command, cwd=APP, env=dict(os.environ), stdin=subprocess.DEVNULL,
                                       stdout=stream, stderr=subprocess.STDOUT, close_fds=True,
                                       pass_fds=(capsule_fd,), start_new_session=False)
            result["pid"] = process.pid
            process_stat = Path(f"/proc/{process.pid}/stat").read_text()
            require(process_stat.split(" ", 1)[0] == str(process.pid), "Owned JVM PID observation mismatch")
            result["process_start_ticks"] = process_stat.rsplit(")", 1)[1].split()[19]
            result["process_group"] = os.getpgid(process.pid)
            require(result["process_group"] == os.getpgrp(), "JVM escaped the owned supervisor group")
            start = time.monotonic()
            while True:
                stop.check()
                observed_exit = observe_exit(process)  # WNOWAIT: preserves exact PID custody.
                ready = directory / "cut-ready"
                if phase["expected_cut"] is not None and ready.is_dir():
                    require(observed_exit is None, "Cut marker appeared only after the owned JVM exited")
                    require(not ready.is_symlink(), "Symlinked process cut marker")
                    cut_path = directory / "cut-ready.json"
                    cut = read_json(cut_path)
                    expected_operation = runtime["binding"]["families"][job["family"]]["frozen_action"]["operation_id"]
                    require(cut.get("cut") == phase["expected_cut"] and cut.get("job_id") == job["id"] and
                            cut.get("pid") == process.pid and cut.get("original_operation_id") == expected_operation and
                            cut.get("waiting_for_external_SIGKILL") is True and
                            cut.get("not_graceful_close_or_same_process_reopen") is True,
                            "Wrong or incomplete named process cut")
                    if job["mode"] == "LOST_RESPONSE_RESTART":
                        require(cut.get("response_suppressed") is True, "Response-loss cut was not prearmed/suppressed")
                        # Lifecycle measurements of the actual suspended client, not a
                        # suppression label or a return value already delivered to it.
                        pending = {
                            "adapter_protocol": "commerce-one-shot-response-loss/v1",
                            "initiator_continuation_present": True, "initiator_continuation_active": True,
                            "initiator_continuation_completed": False, "initiator_continuation_cancelled": False,
                            "initiator_job_active": True, "initiator_job_completed": False,
                            "initiator_job_cancelled": False, "initiator_received_results": 0,
                            "request_channel_completed": True, "request_channel_cancelled": False,
                            "server_observation_channel_completed": True, "server_observation_channel_cancelled": False,
                            "server_coroutine_started": True, "server_coroutine_completed": True,
                            "server_coroutine_cancelled": False, "distinct_client_and_server_coroutines": True,
                        }
                        state = cut.get("initiator_state")
                        require(isinstance(state, dict) and state == pending and
                                all(type(state[key]) is type(value) for key, value in pending.items()),
                                "Response-loss cut lacks a real pending/no-result initiating continuation")
                        result["response_loss_pending_initiator_state"] = state
                    result["cut_record_sha256"] = digest(cut_path)
                    result["cut_observed_utc"] = now()
                    os.kill(process.pid, signal.SIGKILL)
                    result["intentional_SIGKILL"] = True
                    result["SIGKILL_sent_utc"] = now()
                    break
                if observed_exit is not None:
                    require(phase["expected_cut"] is None, "JVM exited before the required restart cut")
                    break
                if time.monotonic() - start >= 180:
                    raise subprocess.TimeoutExpired(command, 180)
                time.sleep(0.05)  # Lifecycle watchdog only; never a business-race barrier.
    except BaseException as error:
        result["status"] = "INCARNATION_ERROR_NOT_ACCEPTED"
        result["sanitized_error_type"] = type(error).__name__
        if isinstance(error, GateError):
            result["gate_reason"] = str(error)
        if isinstance(error, (VerificationInterrupted, KeyboardInterrupt)):
            raise
    finally:
        if process is not None:
            # No Popen.poll()/wait before this signal; the child PID cannot have
            # been reused, including when observe_exit saw a zombie. Final outer
            # group KILL also covers descendants before that leader is reaped.
            if process.returncode is None:
                try:
                    os.kill(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                try:
                    result["exit_code"] = process.wait(timeout=20)
                    result["process_reaped"] = process.returncode is not None
                    result["reaped_utc"] = now()
                except subprocess.TimeoutExpired:
                    result["status"] = "PROCESS_REAP_FAILED_NOT_ACCEPTED"
            else:
                result["exit_code"] = process.returncode
                result["process_reaped"] = True
        if result["status"] == "STARTED_NOT_ACCEPTED":
            expected_exit = -signal.SIGKILL if phase["expected_cut"] is not None else 0
            result["status"] = "EXPECTED_INCARNATION_OBSERVED" if (
                result["process_reaped"] and result["exit_code"] == expected_exit and
                (phase["expected_cut"] is None or result["intentional_SIGKILL"])) else "UNEXPECTED_EXIT_NOT_ACCEPTED"
        result["finished_utc"] = now()
        if log.exists():
            result["log_sha256"] = digest(log)
        write_json(supervision / f"phase-{phase_index}.process.json", result)
    require(result["process_reaped"] or process is None, "Owned JVM could not be reaped; no successor or next job allowed")
    return result


def isolated_expansion_supervisor(run: Path, manifest: dict, classpath: list[str], proof: dict) -> int:
    require(os.getpgrp() == os.getpid(), "Expansion supervisor is not the outer owned group leader")
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    runtime = read_json(run / "runtime-input.json")
    require(runtime["binding"]["version"] == "commerce-cash-binding-v2", "Wrong expansion supervisor input")
    jobs = runtime["binding"]["jobs"]
    record = read_json(run / "run.json")
    write_json(run / "java-invocation.json", {"profile": EXPANSION_PROFILE, "mode": "SEPARATE_JVM_INCARNATIONS",
               "runtime_descriptor": record["runtime_descriptor"],
               "network_proof": "network-isolation.json", "junit_discovery": False,
               "planned_jobs": [job["id"] for job in jobs], "per_incarnation_records": "supervision/family/job/phase-N.*"})
    results = []
    with signal_latch() as stop:
        for job in jobs:
            stop.check()
            capsule_fd, capsule = new_synthetic_capsule()
            capsule_record = {"job_id": job["id"], **capsule, "namespace": proof["namespace"], "closed": False}
            try:
                for phase in job["phases"]:
                    require(phase["index"] == 0 or (results[-1]["job_id"] == job["id"] and
                            results[-1]["phase_index"] == phase["index"] - 1 and results[-1]["process_reaped"] and
                            results[-1]["intentional_SIGKILL"] and results[-1]["exit_code"] == -signal.SIGKILL),
                            "Successor cannot start without the exact predecessor's actual kill/reap")
                    result = supervise_incarnation(run, job, phase, capsule_fd, runtime, manifest, classpath, proof["namespace"], stop)
                    results.append(result)
                    if result["status"] != "EXPECTED_INCARNATION_OBSERVED":
                        break  # Never repair/retry a failed job or invent a successful restart.
            finally:
                os.close(capsule_fd)
                capsule_record["closed"] = True
                capsule_record["closed_utc"] = now()
                write_json(run / "supervision" / job["family"] / job["id"] / "capsule.json", capsule_record)
        stop.check()
    complete = len(results) == sum(len(job["phases"]) for job in jobs) and all(
        item["status"] == "EXPECTED_INCARNATION_OBSERVED" for item in results)
    write_json(run / "supervision.json", {"schema": "independent-commerce-process-supervision/v1",
               "profile": EXPANSION_PROFILE, "invocation": record["invocation"], "supervisor_pid": os.getpid(),
               "network_namespace": proof["namespace"], "status": "ALL_PLANNED_PHASES_OBSERVED" if complete else "INCOMPLETE_NOT_ACCEPTED",
               "phases": results, "business_objects_in_supervisor": False, "secret_bytes_archived": False,
               "acceptance_verdict": "NOT_A_CASE_RESULT"})
    return 0 if complete else 2


def execute_run(args, stop) -> int:
    suite, binding, binding_hash, profile = selected_binding(args.binding_version)
    runtime_input = selected_runtime_input(suite, binding)
    run = fresh_run(OUTPUT)
    invocation = uuid.uuid4().hex
    record = {"schema": "independent-commerce-run/v3", "profile": profile, "mode": "execute",
              "binding_version": args.binding_version, "binding_sha256": binding_hash,
              "invocation": invocation, "utc": now(), "runtime_descriptor": str(args.runtime_descriptor),
              "central_backend": "HELD_NOT_RUN"}
    write_json(run / "run.json", record)
    write_json(run / "runtime-input.json", runtime_input)
    write_json(run / "verdict-inventory.before.json", initial_inventory(suite))
    write_json(run / "wiring-blockers.json", binding["blocked_bindings"])
    result = {"profile": profile, "invocation": invocation, "status": "STARTED_NOT_ACCEPTED", "utc": now(),
              "runtime_descriptor": str(args.runtime_descriptor), "runtime_prepared": False,
              "runtime_descriptor_valid": False, "frozen_inputs_unchanged": False,
              "network_isolation_evidence_present": False, "central_backend": "HELD_NOT_RUN",
              "application_acceptance": False, "complete_case_passes": 0, "complete_assertion_passes": 0}
    try:
        prepare_runtime(args.runtime_descriptor, stop, args.offline, args.timeout_seconds)
        result["runtime_prepared"] = result["runtime_descriptor_valid"] = True
        request = {"profile": profile, "mode": "execute", "invocation": invocation,
                   "parent_network_namespace": os.readlink("/proc/self/ns/net")}
        write_json(run / "execution-request.json", request)
        phase = run_process(isolated_command(run), run / "execution.log", args.timeout_seconds, stop,
                            isolated_environment(run))
        result.update(phase)
        proof = read_json(run / "network-isolation.json")
        require(proof.get("status") == "ISOLATED_NO_EXTERNAL_NETWORK" and
                proof.get("invocation") == invocation and
                proof.get("parent_namespace") == request["parent_network_namespace"] and
                proof.get("namespace") != request["parent_network_namespace"],
                "Commerce network isolation was not proven")
        result["network_isolation_evidence_present"] = True
        require(phase["exit_code"] == 0, "Commerce execution did not complete; see execution.log")
        result["status"] = "PARTIAL_COMMERCE_SLICES_NOT_APPLICATION_ACCEPTANCE"
    except BaseException as error:
        result["sanitized_error_type"] = type(error).__name__
        if isinstance(error, GateError):
            result["gate_reason"] = str(error)
        result["status"] = "INTERRUPTED_OR_TIMEOUT_NOT_ACCEPTED" if isinstance(error, (
            VerificationInterrupted, KeyboardInterrupt, subprocess.TimeoutExpired)) else "ERROR_NOT_ACCEPTED"
    finally:
        try:
            after_suite, after_binding, _, _ = selected_binding(args.binding_version)
            result["frozen_inputs_unchanged"] = selected_runtime_input(after_suite, after_binding) == runtime_input
            if not result["frozen_inputs_unchanged"]:
                result["status"] = "INDEPENDENT_INPUTS_CHANGED_NOT_ACCEPTED"
        except BaseException as error:
            result["input_error_type"] = type(error).__name__
            result["status"] = "INDEPENDENT_INPUTS_CHANGED_NOT_ACCEPTED"
    pattern = "*/*/phase-*/events.jsonl" if args.binding_version == "v2" else "*/*/events.jsonl"
    result["application_observations_present"] = any((run / "observations").glob(pattern))
    result["java_invocation_present"] = (run / "java-invocation.json").is_file()
    result["terminal_cutoff_signal"] = signal.Signals(stop.signum).name if stop.signum else None
    if stop.signum:
        result["status"] = "INTERRUPTED_NOT_ACCEPTED"
    result["results_are_diagnostic_if_run_not_accepted"] = True
    result["finished_utc"] = now()
    write_json(run / "execution-result.json", result)
    summary = None
    try:
        summary = evaluate_expansion(run) if args.binding_version == "v2" else evaluate(run)
        print(json.dumps({"output": str(run), "execution_status": result["status"], **summary}, indent=2))
    except BaseException as error:
        write_json(run / "evaluation-blocked.json", {"status": "RESULT_EVIDENCE_UNAVAILABLE_NOT_ACCEPTED",
                                                   "error_type": type(error).__name__})
    return 0 if (result["status"] == "PARTIAL_COMMERCE_SLICES_NOT_APPLICATION_ACCEPTANCE" and summary is not None and
                 summary["component_status_counts"]["MATCHED_COMPONENT"] > 0 and
                 summary["component_status_counts"]["FAIL"] == 0 and
                 summary["component_status_counts"]["BLOCKED"] == 0) else 2


def evaluate(run: Path) -> dict:
    """Named components only. All expected amounts originate in the frozen JSON.

    Storage names and typed reply bindings locate observations; they do not supply
    the expected business numbers. Missing evidence is BLOCKED, never an empty
    value made to match. The full independently worded assertions remain verbatim.
    """
    suite, binding = verify_frozen()
    inventory = initial_inventory(suite)
    case_index = {c["case_id"]: c for c in inventory["cases"]}
    frozen_index = {c["case_id"]: c for c in suite["cases"]}
    components = inventory["components"]

    def component(case_id, ordinal, name, expected, observed, evidence, variant="V-VALID", missing=False):
        item = {"id": f"{case_id}/component/{len(components) + 1}", "case_id": case_id,
                "assertion_ordinal": ordinal, "description": name, "expected_from_frozen": expected,
                "observed": observed, "evidence": evidence, "variant": variant,
                "status": "BLOCKED" if missing else ("MATCHED_COMPONENT" if observed == expected else "FAIL"),
                "whole_assertion_pass": False, "remaining_obligations": LIMITATIONS}
        components.append(item)
        case = case_index[case_id]
        assertion = case["assertions"][ordinal - 1]
        assertion["observations"].append(item["id"])
        verdict = "FAIL" if item["status"] == "FAIL" else ("BLOCKED" if missing else "PARTIAL")
        if assertion["status"] != "FAIL":
            assertion["status"] = verdict
        if case["status"] != "FAIL":
            case["status"] = verdict
        if variant in case["mandatory_variants"]:
            case["mandatory_variants"][variant] = "PARTIAL"  # NEVER a full per-command variant PASS

    for family, family_binding in binding["families"].items():
        case_id = family_binding["case_id"]
        frozen = frozen_index[case_id]
        aliases = family_binding["aliases"]
        for slice_name in binding["independent_resets"]:
            directory = run / "observations" / family / slice_name
            events_file = directory / "events.jsonl"
            if not events_file.exists():
                continue
            events = [json.loads(line) for line in events_file.read_text().splitlines() if line]
            mutations = {e["body"]["label"]: e["body"] for e in events if e["kind"] == "mutation_observed"}
            original = mutations.get("original")
            if original is None:
                # Setup is NOT a case or an authored business-assertion attempt.
                inventory.setdefault("harness_blockers", []).append({"case_id": case_id, "slice": slice_name,
                    "reason": "Original frozen action not observed; fixture/executor setup incomplete",
                    "evidence": str(events_file.relative_to(run))})
                continue
            before = read_json(directory / original["before_snapshot"])
            after = read_json(directory / original["after_snapshot"])
            evidence = [str((directory / name).relative_to(run)) for name in (original["staged_intent"], original["before_snapshot"], original["after_snapshot"])]
            expected_minor = frozen["numeric_expected"]["drawer_minor" if family == "CASH-01" else "DA_minor"]
            expected_cash = [{"id": aliases["SA"], "drawer_id": aliases["DA"], "custodian_id": aliases["A"],
                              "status": "OPEN", "opening_minor": expected_minor, "expected_cash_minor": expected_minor}]
            selected_keys = tuple(expected_cash[0])
            actual_cash = [{key: row.get(key) for key in selected_keys} for row in after["cash_sessions"]]
            component(case_id, 1, "Durable sole A/DA session and counted opening/expected cash (immutability/platform portions not completed)",
                      expected_cash, actual_cash, evidence)
            original_reply = original["reply"]
            component(case_id, 1, "Original command applied/rejected through the real guarded endpoint",
                      {"type": "Recorded", "committed": family == "CASH-01"},
                      {"type": original_reply["type"], "committed": original_reply.get("committed")}, evidence)
            # Retain and compare a current authorized projection as well as fresh-driver rows.
            own_events = [e["body"] for e in events if e["kind"] == "protected_read_observed" and e["body"]["label"] == "original-after-A"]
            public_session = None
            if own_events and own_events[-1]["reply"]["type"] == "Data":
                public_session = own_events[-1]["reply"]["payload"].get("session")
            component(case_id, 1, "Protected own-cash projection agrees with frozen counted cash",
                      expected_minor, (public_session or {}).get("expectedCash", {}).get("minor"),
                      [str(events_file.relative_to(run))], missing=public_session is None)
            if family == "CASH-01":
                zero = frozen["numeric_expected"]["sales_minor"]
                cash_fields = ("received_minor", "change_minor", "refunds_minor", "pay_ins_minor", "pay_outs_minor",
                               "safe_drops_minor", "external_collections_minor", "external_refunds_minor")
                totals = [{k: row.get(k) for k in cash_fields} for row in after["cash_sessions"]]
                component(case_id, 2, "Observed cash counters stay zero; complete sales/expense/tax reporting not exercised",
                          [{k: zero for k in cash_fields}], totals, evidence)
                paid_sales = [r for r in after["aggregates"] if r["kind"] == "sales.paid_sale"]
                component(case_id, 2, "No paid-sale aggregate created by the counted opening", 0, len(paid_sales), evidence)
            else:
                component(case_id, 1, "Competing B open leaves all prior business aggregates and claims unchanged", True,
                          before["aggregates"] == after["aggregates"] and before["claims"] == after["claims"], evidence)

            for label, mutation in mutations.items():
                if label == "original" or label.startswith("fixture-"):
                    continue
                m_before = read_json(directory / mutation["before_snapshot"])
                m_after = read_json(directory / mutation["after_snapshot"])
                m_evidence = [str((directory / name).relative_to(run)) for name in
                              (mutation["staged_intent"], mutation["before_snapshot"], mutation["after_snapshot"])]
                ordinal = 3 if family == "CASH-01" and label == "assertion-extra-open" else (2 if family == "CASH-02" and label == "assertion-extra-open" else 1)
                variant = "V-DUPLICATE" if label.startswith("duplicate") else ("V-MISMATCH" if slice_name.startswith("V-MISMATCH") else "V-VALID")
                unchanged = m_before["aggregates"] == m_after["aggregates"] and m_before["claims"] == m_after["claims"]
                component(case_id, ordinal, f"{label}: durable business aggregates/claims unchanged", True, unchanged, m_evidence, variant)
                reply = mutation["reply"]
                if label == "assertion-extra-open":
                    component(case_id, ordinal, "Additional opening explicitly required by frozen assertion is rejected",
                              {"type": "Recorded", "committed": False},
                              {"type": reply["type"], "committed": reply.get("committed")}, m_evidence, variant)
                elif label.startswith("mismatch"):
                    safe_conflict = reply["type"] == "IdentityConflict" or (reply["type"] == "Deferred" and reply.get("reason") == "AUTHORIZATION_DENIED")
                    component(case_id, ordinal, "Same-ID changed semantic body/actor returns mismatch or access denial, not original private result",
                              True, safe_conflict and "result" not in reply, m_evidence, variant)
                else:
                    fields = ("type", "committed", "code", "original_actor_id", "operation_id", "authority_epoch", "sequence", "result_sha256")
                    component(case_id, ordinal, f"{label}: exact retained original result/actor/journal identity",
                              {k: original_reply.get(k) for k in fields}, {k: reply.get(k) for k in fields}, m_evidence, variant)
                    component(case_id, ordinal, f"{label}: no duplicate result/journal/outbox", True,
                              all(m_before[k] == m_after[k] for k in ("operations", "journals", "outbox")), m_evidence, variant)

    result_path = run / "execution-result.json"
    result = read_json(result_path) if result_path.exists() else {}
    inventory["execution_integrity"] = result
    inventory["evidence_qualification"] = "PARTIAL_ONLY" if all(result.get(k) for k in ("runtime_prepared", "runtime_descriptor_valid", "frozen_inputs_unchanged", "network_isolation_evidence_present")) else "UNQUALIFIED_OR_BLOCKED"
    inventory["status_counts"] = {status: sum(c["status"] == status for c in inventory["cases"])
                                  for status in ("NOT_RUN", "BLOCKED", "PARTIAL", "FAIL")}
    inventory["component_status_counts"] = {status: sum(c["status"] == status for c in components)
                                            for status in ("MATCHED_COMPONENT", "BLOCKED", "FAIL")}
    inventory["scenario_statuses"] = {}
    for case in inventory["cases"]:
        for scenario in case["scenario_ids"]:
            previous = inventory["scenario_statuses"].get(scenario, "NOT_RUN")
            rank = {"NOT_RUN": 0, "BLOCKED": 1, "PARTIAL": 2, "FAIL": 3}
            inventory["scenario_statuses"][scenario] = max(previous, case["status"], key=lambda x: rank[x])
    write_json(run / "verdict-inventory.after.json", inventory)
    summary = {"status": ("FAIL_OBSERVED_COMPONENTS" if inventory["component_status_counts"]["FAIL"] else
                          ("PARTIAL_EXECUTION_ONLY" if components else "NOT_RUN_OR_BLOCKED")),
               "case_status_counts": inventory["status_counts"], "component_status_counts": inventory["component_status_counts"],
               "complete_case_passes": 0, "complete_assertion_passes": 0, "original_scenario_passes": 0,
               "evidence_qualification": inventory["evidence_qualification"], "limitations": LIMITATIONS,
               "frozen_cases_sha256": CASES_HASH, "binding_sha256": BINDING_HASH}
    write_json(run / "summary.json", summary)
    return summary


def evaluate_expansion(run: Path) -> dict:
    """Evaluate only frozen sub-obligations mapped in the separately frozen v2.

    Frozen expected values and assertion definitions remain unchanged.
    No missing snapshot is an empty store, no compiler/process success is a case,
    and no matched component promotes any complete assertion/variant/scenario.
    """
    suite, binding, _, _ = selected_binding("v2")
    inventory = initial_inventory(suite)
    inventory["schema"] = "independent-commerce-inventory/v2-expansion"
    inventory["limitations"] = EXPANSION_LIMITATIONS
    inventory["remaining_frozen_obligations"] = binding["remaining_frozen_obligations"]
    inventory["preserved_prior_evidence"] = "Original frozen v1 binding and historical evidence are preserved; this run does not re-award their components"
    cases = {case["case_id"]: case for case in inventory["cases"]}
    frozen_cases = {case["case_id"]: case for case in suite["cases"]}
    components = inventory["components"]
    rank = {"NOT_RUN": 0, "PARTIAL": 1, "BLOCKED": 2, "FAIL": 3}
    retained_fields = ("type", "committed", "code", "original_actor_id", "operation_id", "authority_epoch",
                       "sequence", "recorded_at", "result_sha256", "result")

    def component(job, ordinal, description, expected, observed, evidence, missing=False, integrity=None):
        case_id, variant = job["case_id"], job["frozen_variant"]
        # These are evidence-binding gates for the EXISTING frozen obligation,
        # not new business oracles. Known contradictions stay FAIL even if some
        # other evidence is absent; missing artifacts/fields never compare equal.
        status = ("FAIL" if integrity and integrity["mismatches"] else
                  "BLOCKED" if missing or (integrity and integrity["missing"]) else
                  "MATCHED_COMPONENT" if expected == observed else "FAIL")
        item = {"id": f"{case_id}/v2-component/{len(components) + 1}", "case_id": case_id,
                "job_id": job["id"], "assertion_ordinal": ordinal, "description": description,
                "frozen_assertion": frozen_cases[case_id]["assertions"][ordinal - 1],
                "variant": variant, "frozen_variant_reference": f"variant-matrix.json#/variants/{variant}",
                "expected_from_frozen": expected, "observed": observed, "evidence": evidence,
                "status": status, "whole_assertion_pass": False, "remaining_obligations": EXPANSION_LIMITATIONS}
        if integrity is not None:
            item["observation_binding_integrity"] = integrity
        components.append(item)
        case = cases[case_id]
        assertion = case["assertions"][ordinal - 1]
        assertion["observations"].append(item["id"])
        partial_status = "PARTIAL" if status == "MATCHED_COMPONENT" else status
        assertion["status"] = max(assertion["status"], partial_status, key=lambda x: rank[x])
        case["status"] = max(case["status"], partial_status, key=lambda x: rank[x])
        if variant in case["mandatory_variants"]:
            case["mandatory_variants"][variant] = max(case["mandatory_variants"][variant], partial_status, key=lambda x: rank[x])

    def evidence(directory, *names):
        return [str((directory / name).relative_to(run)) for name in names]

    def optional_json(path):
        if not path.is_file():
            return None
        return read_json(path)

    def snapshot(directory, name):
        if name is None:
            return None
        require(Path(name).name == name and name.endswith(".json"), "Snapshot filename traverses a phase directory")
        value = optional_json(directory / name)
        if value is not None:
            require(value.get("schema") == "commerce-v1-fresh-driver-observation/1" and
                    all(isinstance(value.get(key), list) for key in
                        ("aggregates", "cash_sessions", "claims", "operations", "journals", "audits", "outbox")),
                    "Incomplete snapshot must not masquerade as an empty business store")
        return value

    def events(directory):
        path = directory / "events.jsonl"
        if not path.is_file():
            return []
        content = path.read_bytes()
        rows = [json.loads(line) for line in content.decode("utf-8").splitlines() if line]
        require([row.get("ordinal") for row in rows] == list(range(1, len(rows) + 1)) and
                all(isinstance(row.get("body"), dict) for row in rows), "Noncontiguous or malformed observation log")
        return rows

    def one(rows, kind, label=None):
        matches = [row for row in rows if row["kind"] == kind and (label is None or row["body"].get("label") == label)]
        require(len(matches) <= 1, "Duplicate named observation cannot overwrite earlier evidence")
        return matches[0] if matches else None

    def body(row):
        return row["body"] if row is not None else None

    def states(directory, observed):
        return (snapshot(directory, observed.get("before_snapshot")), snapshot(directory, observed.get("after_snapshot"))) if observed else (None, None)

    def unchanged(before, after, keys=("aggregates", "claims")):
        return all(before[key] == after[key] for key in keys) if before is not None and after is not None else None

    def cash_shape(value):
        keys = ("id", "drawer_id", "custodian_id", "status", "opening_minor", "expected_cash_minor")
        return [{key: row.get(key) for key in keys} for row in value["cash_sessions"]] if value is not None else None

    def new_integrity():
        return {"missing": [], "mismatches": []}

    def fields_present(check, label, value, fields):
        if value is None:
            check["missing"].append(label)
            return False
        if not isinstance(value, dict):
            check["mismatches"].append({"field": label, "expected": "object", "observed_type": type(value).__name__})
            return False
        absent = [field for field in fields if field not in value or value[field] is None]
        check["missing"].extend(f"{label}.{field}" for field in absent)
        return not absent

    def bound_fields(check, label, value, expected):
        fields_present(check, label, value, expected)
        if isinstance(value, dict):
            for field, required_value in expected.items():
                if field in value and value[field] is not None and (
                        value[field] != required_value or type(value[field]) is not type(required_value)):
                    check["mismatches"].append({"field": f"{label}.{field}", "expected": required_value, "observed": value[field]})

    def binding_condition(check, label, valid):
        if not valid:
            check["mismatches"].append({"field": label, "expected": True, "observed": False})

    def hex_digest(value):
        return isinstance(value, str) and len(value) == 64 and all(char in "0123456789abcdef" for char in value)

    def counter_text(value):
        return (isinstance(value, str) and 1 <= len(value) <= 19 and all(char in "0123456789" for char in value) and
                str(int(value)) == value and int(value) <= 2**63 - 1)

    def referenced_json(directory, name, check, label, suffix=".json"):
        if name is None:
            check["missing"].append(label + " filename")
            return None
        if not isinstance(name, str) or Path(name).name != name or not name.endswith(suffix):
            binding_condition(check, label + " phase-local filename", False)
            return None
        value = optional_json(directory / name)
        if value is None:
            check["missing"].append(label + " file " + name)
        return value

    intent_fields = ("operation_id", "actor_alias", "command", "command_version", "payload_utf8", "payload_base64", "payload_sha256")
    command_name, command_version = binding["route"]["production_command"].rsplit("/", 1)

    def validate_intent(job, intent, operation_id, actor_alias, check, label):
        complete = fields_present(check, label, intent, intent_fields)
        bound_fields(check, label, intent, {"operation_id": operation_id, "actor_alias": actor_alias,
                                          "command": command_name, "command_version": int(command_version)})
        if not complete:
            return None
        try:
            raw = base64.b64decode(intent["payload_base64"], validate=True)
            binding_condition(check, label + " exact base64/UTF8/SHA256 bytes",
                              raw.decode("utf-8") == intent["payload_utf8"] and
                              base64.b64encode(raw).decode("ascii") == intent["payload_base64"] and
                              hashlib.sha256(raw).hexdigest() == intent["payload_sha256"])
            payload = json.loads(raw.decode("utf-8"))
            binding_condition(check, label + " object command body", isinstance(payload, dict))
            return payload if isinstance(payload, dict) else None
        except (ValueError, TypeError, UnicodeError, binascii.Error):
            binding_condition(check, label + " decodable exact staged bytes", False)
            return None

    def staged_evidence(job, directory, name, operation_id, actor_alias, check, label):
        intent = referenced_json(directory, name, check, label, ".intent.json")
        payload = validate_intent(job, intent, operation_id, actor_alias, check, label)
        identity_name = name[:-len(".intent.json")] + ".identity.json" if isinstance(name, str) and name.endswith(".intent.json") else None
        identity = referenced_json(directory, identity_name, check, label + " identity")
        aliases = binding["families"][job["family"]]["aliases"]
        bound_fields(check, label + " identity", identity, {
            "branch_id": aliases["C1-B1"], "operation_id": operation_id, "original_actor_id": aliases[actor_alias],
            "semantic_digest_version": 1,
        })
        if fields_present(check, label + " identity", identity, ("semantic_sha256",)):
            binding_condition(check, label + " semantic digest shape", hex_digest(identity["semantic_sha256"]))
        return intent, payload, identity

    def recorded_integrity(check, label, reply, operation_id, actor_id, committed=None):
        if not fields_present(check, label, reply, ("type",)):
            return
        bound_fields(check, label, reply, {"type": "Recorded"})
        if reply["type"] != "Recorded":
            return
        fields_present(check, label, reply, retained_fields)
        expected = {"operation_id": operation_id, "original_actor_id": actor_id}
        if committed is not None:
            expected["committed"] = committed
        bound_fields(check, label, reply, expected)
        if "committed" in reply and reply["committed"] is not None:
            binding_condition(check, label + " committed boolean", type(reply["committed"]) is bool)
        for key in ("code", "recorded_at"):
            if key in reply and reply[key] is not None:
                binding_condition(check, label + "." + key + " retained text", isinstance(reply[key], str) and bool(reply[key]))
        for key in ("authority_epoch", "sequence"):
            if key in reply and reply[key] is not None:
                binding_condition(check, label + "." + key + " canonical counter", counter_text(reply[key]))
        if "result_sha256" in reply and reply["result_sha256"] is not None:
            binding_condition(check, label + " result digest shape", hex_digest(reply["result_sha256"]))

    def stable_result(reply):
        if not isinstance(reply, dict) or reply.get("type") != "Recorded" or any(
                field not in reply or reply[field] is None for field in retained_fields):
            return None
        return {field: reply[field] for field in retained_fields}

    def stable_intent(intent):
        if not isinstance(intent, dict) or any(field not in intent or intent[field] is None for field in intent_fields):
            return None
        return {field: intent[field] for field in intent_fields}

    def original_components(job, directory, observed):
        family = job["family"]
        frozen = frozen_cases[job["case_id"]]
        aliases = binding["families"][family]["aliases"]
        amount = frozen["numeric_expected"]["drawer_minor" if family == "CASH-01" else "DA_minor"]
        before, after = states(directory, observed)
        reply = observed.get("reply") if observed else None
        refs = evidence(directory, "events.jsonl") + (evidence(directory, *[
            observed[key] for key in ("staged_intent", "before_snapshot", "after_snapshot", "reply_file")
            if isinstance(observed.get(key), str)]) if observed else [])
        check = new_integrity()
        action = frozen["actions"][0]
        expected_reply = {"type": "Recorded", "committed": family == "CASH-01",
                          "operation_id": action["operation_id"], "original_actor_id": aliases[action["actor"]]}
        recorded_integrity(check, "original reply", reply, action["operation_id"], aliases[action["actor"]], family == "CASH-01")
        bound_fields(check, "original dispatch observation", observed,
                     {"operation_id": action["operation_id"], "actor_alias": action["actor"]})
        _, _, identity = staged_evidence(job, directory, observed.get("staged_intent") if observed else None,
                                        action["operation_id"], action["actor"], check, "original intent")
        reply_file = referenced_json(directory, observed.get("reply_file") if observed else None, check, "original reply")
        if reply_file is not None and reply is not None:
            binding_condition(check, "original raw reply file/observation equality", reply_file == reply)
        if after is None:
            check["missing"].append("original fresh durable snapshot")
        else:
            origins = [row for row in after["operations"] if row.get("operation_id") == action["operation_id"]]
            binding_condition(check, "one durable frozen original operation", len(origins) == 1)
            if len(origins) == 1:
                origin = origins[0]
                bound_fields(check, "original durable identity", origin, {"operation_id": action["operation_id"],
                    "actor_id": aliases[action["actor"]], "command": command_name, "command_version": int(command_version)})
                pairs = {"epoch": "authority_epoch", "sequence": "sequence", "committed_at": "recorded_at",
                         "result_hash": "result_sha256", "result": "result"}
                fields_present(check, "original durable origin", origin, tuple(pairs) + ("digest",))
                if isinstance(reply, dict):
                    bound_fields(check, "original durable origin", origin,
                                 {key: reply[value] for key, value in pairs.items() if value in reply and reply[value] is not None})
                if isinstance(identity, dict) and identity.get("semantic_sha256") is not None:
                    bound_fields(check, "original durable semantic identity", origin, {"digest": identity["semantic_sha256"]})
        expected = [{"id": aliases["SA"], "drawer_id": aliases["DA"], "custodian_id": aliases["A"], "status": "OPEN",
                     "opening_minor": amount, "expected_cash_minor": amount}]
        component(job, 1, "Original frozen action leaves sole durable A/DA counted custody", expected, cash_shape(after), refs,
                  missing=after is None, integrity=check)
        component(job, 1, "Original frozen command applied/rejected through actual guarded endpoint",
                  expected_reply, {key: reply.get(key) for key in expected_reply} if isinstance(reply, dict) else reply,
                  refs, missing=reply is None, integrity=check)
        if family == "CASH-01":
            zero = frozen["numeric_expected"]["sales_minor"]
            fields = ("received_minor", "change_minor", "refunds_minor", "pay_ins_minor", "pay_outs_minor",
                      "safe_drops_minor", "external_collections_minor", "external_refunds_minor")
            observed_totals = [{field: row.get(field) for field in fields} for row in after["cash_sessions"]] if after is not None else None
            component(job, 2, "Observed cash counters remain frozen zero; full tax/expense/reporting not qualified",
                      [{field: zero for field in fields}], observed_totals, refs, missing=after is None, integrity=check)
            component(job, 2, "Counted opening creates no paid-sale document", 0,
                      len([row for row in after["aggregates"] if row["kind"] == "sales.paid_sale"]) if after is not None else None,
                      refs, missing=after is None, integrity=check)
        else:
            component(job, 1, "Occupied-drawer rejection preserves existing business aggregates/claims", True,
                      unchanged(before, after), refs, missing=before is None or after is None, integrity=check)
        # An unvalidated initial reply must never become a learned historical oracle.
        reference = reply if not check["missing"] and not check["mismatches"] and stable_result(reply) is not None else None
        return reference, before, after

    def retry_components(job, directory, observed, original_reply, original_intent):
        before, after = states(directory, observed)
        refs = evidence(directory, "events.jsonl")
        reply = observed.get("reply") if observed else None
        action = frozen_cases[job["case_id"]]["actions"][0]
        aliases = binding["families"][job["family"]]["aliases"]
        check = new_integrity()
        recorded_integrity(check, "validated original reference", original_reply, action["operation_id"], aliases[action["actor"]], job["family"] == "CASH-01")
        recorded_integrity(check, "replay reply", reply, action["operation_id"], aliases[action["actor"]], job["family"] == "CASH-01")
        intent, _, _ = staged_evidence(job, directory, observed.get("staged_intent") if observed else None,
                                     action["operation_id"], action["actor"], check, "replay intent")
        validate_intent(job, original_intent, action["operation_id"], action["actor"], check, "original retained intent")
        reply_file = referenced_json(directory, observed.get("reply_file") if observed else None, check, "replay reply")
        if reply_file is not None and reply is not None:
            binding_condition(check, "replay raw reply file/observation equality", reply_file == reply)
        bound_fields(check, "replay dispatch observation", observed, {"operation_id": action["operation_id"], "actor_alias": action["actor"]})
        component(job, 1, "Exact same operation/body/original actor retained, including original CAS and evidence bytes",
                  stable_intent(original_intent), stable_intent(intent), refs,
                  missing=stable_intent(original_intent) is None or stable_intent(intent) is None, integrity=check)
        component(job, 1, "Replay returns exact retained original result/source actor/journal origin", stable_result(original_reply), stable_result(reply),
                  refs, missing=stable_result(original_reply) is None or stable_result(reply) is None, integrity=check)
        component(job, 1, "Replay adds no business/result/journal/outbox effects", True,
                  unchanged(before, after, ("aggregates", "claims", "operations", "journals", "outbox")), refs,
                  missing=before is None or after is None, integrity=check)

    def extra_components(job, directory, rows):
        observed = body(one(rows, "mutation_observed", "assertion-extra-open"))
        before, after = states(directory, observed)
        reply = observed.get("reply") if observed else None
        ordinal = 3 if job["family"] == "CASH-01" else 2
        refs = evidence(directory, "events.jsonl")
        component(job, ordinal, "Remaining frozen different-ID/other-drawer opening is rejected",
                  {"type": "Recorded", "committed": False},
                  {"type": reply.get("type"), "committed": reply.get("committed")} if reply else None, refs, missing=reply is None)
        component(job, ordinal, "Remaining rejected opening does not alter prior custody or other business state", True,
                  unchanged(before, after), refs, missing=before is None or after is None)

    def denial_component(job, directory, observed, protected_read=False):
        before, after = states(directory, observed)
        reply = observed.get("reply") if observed else None
        safe = None
        if reply is not None:
            safe = reply.get("type") == ("Unavailable" if protected_read else "Deferred") and \
                reply.get("reason") in {"AUTHORIZATION_DENIED", "WRONG_SCOPE"} and \
                set(reply) == {"type", "reason"}
        refs = evidence(directory, "events.jsonl")
        label = observed.get("label") if observed else "scheduled-denial"
        component(job, 1, f"{label}: typed safe access denial without private result payload", True, safe, refs, missing=reply is None)
        component(job, 1, f"{label}: protected read/denied mutation leaves business aggregates and claims unchanged", True,
                  unchanged(before, after), refs, missing=before is None or after is None)

    def inspect_race_binding(job, directory, rows, race, barrier):
        """Consume the pre-existing frozen race binding, not a new winner/schedule oracle."""
        check = new_integrity()
        family = binding["families"][job["family"]]
        aliases = family["aliases"]
        frozen = frozen_cases[job["case_id"]]
        action = frozen["actions"][0]
        amount = frozen["numeric_expected"]["drawer_minor" if job["family"] == "CASH-01" else "DA_minor"]
        bound_fields(check, "race observation", race, {"mode": job["mode"], "before_snapshot": "race.before.json",
                                                       "after_snapshot": "race.after.json"})
        bound_fields(check, "armed barrier", body(barrier), {"participants": 2, "mode": job["mode"],
                                                             "original_operation_id_preserved": action["operation_id"]})
        fields_present(check, "armed barrier", body(barrier), ("expected_drawer_revision",))
        requests = race.get("requests") if isinstance(race, dict) else None
        if requests is None:
            check["missing"].append("both indexed race request records")
            return check
        if not isinstance(requests, list):
            binding_condition(check, "race request array", False)
            return check
        binding_condition(check, "exactly two race request records", len(requests) == 2)
        payloads = []
        for index in range(2):
            label = f"race-{index}"
            if index >= len(requests):
                check["missing"].append(label + " request record")
                continue
            request = requests[index]
            actor_alias, operation_id = action["actor"], action["operation_id"]
            session_alias = family["session_for_original_action"]
            source_alias, evidence_alias = action["inputs"]["source"], "evidence-open"
            if index == 1 and job["mode"] == "RACE_DISTINCT":
                if job["family"] == "CASH-02":
                    actor_alias, operation_id = "A", family["auxiliary_operations"]["race-distinct-A"]
                    session_alias, source_alias, evidence_alias = "SA", "safe-float-A-fixture", "evidence-fixture-open-A"
                else:
                    operation_id = family["auxiliary_operations"]["race-distinct-other"]
            bound_fields(check, label + " request", request, {
                "index": index, "actor_alias": actor_alias, "operation_id": operation_id,
                "intent": label + ".intent.json", "reply_file": label + ".reply.json",
            })
            intent_name = request.get("intent") if isinstance(request, dict) else None
            _, payload, _ = staged_evidence(job, directory, intent_name, operation_id, actor_alias, check, label)
            payloads.append(payload)
            expected_body = {
                "type": "OPEN", "sessionId": aliases[session_alias], "drawerId": aliases[action["inputs"]["drawer"]],
                "businessDate": binding["metadata"]["business_date"], "openingSourceId": aliases[source_alias],
                "opening": {"currency": binding["metadata"]["currency"], "minor": amount},
                "evidence": {"evidenceId": aliases[evidence_alias], "occurredAt": binding["metadata"]["evidence_occurred_at"]},
            }
            bound_fields(check, label + " frozen Open body", payload, expected_body)
            fields_present(check, label + " captured CAS", payload, ("expectedDrawerRevision", "expectedCustodianRevision"))
            if isinstance(payload, dict):
                binding_condition(check, label + " no undeclared Open body fields",
                                  not (set(payload) - set(expected_body) - {"expectedDrawerRevision", "expectedCustodianRevision"}))
                for key in ("expectedDrawerRevision", "expectedCustodianRevision"):
                    if payload.get(key) is not None:
                        binding_condition(check, label + " canonical " + key, counter_text(payload[key]))

            # These are the real protected pre-dispatch reads that produced BOTH
            # captured optimistic preconditions, not values learned after the race.
            own = one(rows, "protected_read_observed", f"prerequisite-own-{actor_alias}-{session_alias}")
            drawers = one(rows, "protected_read_observed", f"prerequisite-drawers-{actor_alias}-{session_alias}")
            for source_label, event in (("own", own), ("drawers", drawers)):
                bound_fields(check, label + " prerequisite " + source_label, body(event), {"actor_alias": actor_alias})
                if event is not None and barrier is not None:
                    binding_condition(check, label + " " + source_label + " read before armed barrier", event["ordinal"] < barrier["ordinal"])
            own_reply = body(own).get("reply") if own else None
            drawers_reply = body(drawers).get("reply") if drawers else None
            bound_fields(check, label + " own read", own_reply, {"type": "Data"})
            bound_fields(check, label + " drawers read", drawers_reply, {"type": "Data"})
            own_payload = own_reply.get("payload") if isinstance(own_reply, dict) else None
            drawers_payload = drawers_reply.get("payload") if isinstance(drawers_reply, dict) else None
            bound_fields(check, label + " own read payload", own_payload, {"type": "OWN_CASH", "custodianId": aliases[actor_alias]})
            fields_present(check, label + " own read payload", own_payload, ("assignmentRevision",))
            bound_fields(check, label + " drawers read payload", drawers_payload, {"type": "DRAWERS"})
            fields_present(check, label + " drawers read payload", drawers_payload, ("drawers",))
            choices = drawers_payload.get("drawers") if isinstance(drawers_payload, dict) else None
            if isinstance(choices, list):
                chosen = [item for item in choices if isinstance(item, dict) and item.get("id") == aliases[action["inputs"]["drawer"]]]
                binding_condition(check, label + " one captured target drawer", len(chosen) == 1)
                if len(chosen) == 1 and fields_present(check, label + " captured drawer", chosen[0], ("revision",)):
                    bound_fields(check, label + " drawer CAS/read binding", payload, {"expectedDrawerRevision": chosen[0]["revision"]})
            elif choices is not None:
                binding_condition(check, label + " drawer choices array", False)
            if isinstance(own_payload, dict) and own_payload.get("assignmentRevision") is not None:
                bound_fields(check, label + " custodian CAS/read binding", payload, {"expectedCustodianRevision": own_payload["assignmentRevision"]})
            if isinstance(payload, dict) and payload.get("expectedDrawerRevision") is not None:
                bound_fields(check, label + " armed shared drawer CAS", body(barrier), {"expected_drawer_revision": payload["expectedDrawerRevision"]})

            reply = request.get("reply") if isinstance(request, dict) else None
            reply_name = request.get("reply_file") if isinstance(request, dict) else None
            raw_reply = referenced_json(directory, reply_name, check, label + " server reply")
            if raw_reply is not None and reply is not None:
                binding_condition(check, label + " raw reply/participant correlation", raw_reply == reply)
            if fields_present(check, label + " reply", reply, ("type",)):
                if reply["type"] == "Recorded":
                    recorded_integrity(check, label + " reply", reply, operation_id, aliases[actor_alias])
                elif reply["type"] == "IdentityConflict":
                    # The production conflict DTO has no embedded operation ID.
                    # Its pairing is therefore proved by the exact indexed request,
                    # dispatch and raw reply file below, never an invented ID field.
                    binding_condition(check, label + " safe indexed conflict", reply == {"type": "IdentityConflict"})
                else:
                    binding_condition(check, label + " recorded or conflicting race reply", False)
            linked = {}
            for kind in ("race_participant_ready", "race_dispatch_enter", "race_server_reply"):
                matches = [event for event in rows if event["kind"] == kind and event["body"].get("index") == index]
                if not matches:
                    check["missing"].append(label + " " + kind)
                else:
                    binding_condition(check, label + " unique " + kind, len(matches) == 1)
                    linked[kind] = matches[0]
            ready = linked.get("race_participant_ready")
            entered = linked.get("race_dispatch_enter")
            returned = linked.get("race_server_reply")
            bound_fields(check, label + " ready", body(ready), {"index": index, "operation_id": operation_id})
            bound_fields(check, label + " returned file", body(returned), {"index": index, "reply_file": label + ".reply.json"})
            if all(value is not None for value in (barrier, ready, entered, returned)):
                binding_condition(check, label + " indexed request/dispatch/response order",
                                  barrier["ordinal"] < ready["ordinal"] < entered["ordinal"] < returned["ordinal"])
        if len(payloads) == 2 and all(isinstance(payload, dict) for payload in payloads):
            if all(payload.get("expectedDrawerRevision") is not None for payload in payloads):
                binding_condition(check, "both staged drawer revisions equal", payloads[0]["expectedDrawerRevision"] == payloads[1]["expectedDrawerRevision"])
            if job["mode"] == "RACE_SAME_ID" or job["family"] == "CASH-01":
                binding_condition(check, "declared identical race bodies retained", payloads[0] == payloads[1])
        return check

    for job in binding["jobs"]:
        root = run / "observations" / job["family"] / job["id"]
        first, second = root / "phase-0", root / "phase-1"
        rows0 = events(first)
        mode = job["mode"]
        if not rows0:
            inventory.setdefault("harness_blockers", []).append({"job_id": job["id"], "reason": "Scheduled first incarnation has no observation log"})
        if mode in {"DENIED", "DENIED_EXPLICIT"}:
            labels = ["denied-explicit"] if mode == "DENIED_EXPLICIT" else [
                "denied-unadmitted", "denied-device-only", "denied-no-grant", "denied-foreign-branch",
                "denied-foreign-company", "denied-foreign-organization"]
            for label in labels:
                denial_component(job, first, body(one(rows0, "mutation_observed", label)))
                if mode == "DENIED":
                    for suffix in ("own", "drawers"):
                        denial_component(job, first, body(one(rows0, "protected_probe_observed", f"{label}-{suffix}")), True)
            continue
        if mode == "PRIVACY":
            original_components(job, first, body(one(rows0, "mutation_observed", "original")))
            private = body(one(rows0, "protected_probe_observed", "wrong-owner-session"))
            before, after = states(first, private)
            reply = private.get("reply") if private else None
            safe = None
            if reply is not None:
                safe = (reply.get("type") == "Unavailable" and reply.get("reason") in {"AUTHORIZATION_DENIED", "WRONG_SCOPE"} and
                        set(reply) == {"type", "reason"}) or (reply.get("type") == "Data" and private.get("decoded_kind") == "NOT_AVAILABLE" and
                        reply.get("payload") == {"type": "NOT_AVAILABLE"})
            component(job, 1, "B's direct protected Session query reveals no private A session/cash", True, safe,
                      evidence(first, "events.jsonl"), missing=reply is None)
            component(job, 1, "Wrong-owner read changes no durable business data", True, unchanged(before, after),
                      evidence(first, "events.jsonl"), missing=before is None or after is None)
            denial_component(job, first, body(one(rows0, "protected_probe_observed", "no-read-grant-session")), True)
            extra_components(job, first, rows0)
            continue
        if mode in {"RACE_SAME_ID", "RACE_DISTINCT"}:
            race = body(one(rows0, "race_observed"))
            before, after = states(first, race)
            requests = race.get("requests") if race else None
            refs = evidence(first, "events.jsonl", "race.before.json", "race.after.json",
                            "race-0.intent.json", "race-0.identity.json", "race-0.reply.json",
                            "race-1.intent.json", "race-1.identity.json", "race-1.reply.json")
            barrier = one(rows0, "race_barrier_armed")
            race_binding = inspect_race_binding(job, first, rows0, race, barrier)
            ready = [row for row in rows0 if row["kind"] == "race_participant_ready"]
            entered = [row for row in rows0 if row["kind"] == "race_dispatch_enter"]
            schedule = barrier is not None and len(ready) == 2 and len(entered) == 2 and \
                {row["body"].get("index") for row in ready} == {0, 1} and \
                {row["body"].get("index") for row in entered} == {0, 1} and \
                max(row["ordinal"] for row in ready) < min(row["ordinal"] for row in entered)
            component(job, 1, "Both first submissions reached the one prearmed barrier before either entered dispatch", True, schedule, refs,
                      missing=race is None, integrity=race_binding)
            aliases = binding["families"][job["family"]]["aliases"]
            frozen = frozen_cases[job["case_id"]]
            amount = frozen["numeric_expected"]["drawer_minor" if job["family"] == "CASH-01" else "DA_minor"]
            original_id = frozen["actions"][0]["operation_id"]
            if mode == "RACE_SAME_ID":
                expected_cash = [{"id": aliases["SA"], "drawer_id": aliases["DA"], "custodian_id": aliases["A"],
                                  "status": "OPEN", "opening_minor": amount, "expected_cash_minor": amount}]
                component(job, 1, "Same-ID first submissions retain exactly the frozen custody/balance", expected_cash, cash_shape(after), refs,
                          missing=after is None, integrity=race_binding)
                exact = None
                if isinstance(requests, list) and len(requests) == 2 and all(isinstance(request, dict) for request in requests):
                    intents = [optional_json(first / f"race-{index}.intent.json") for index in range(2)]
                    exact = all(intent is not None for intent in intents) and \
                        all(request.get("operation_id") == original_id for request in requests) and intents[0] == intents[1]
                    replies = [request.get("reply") for request in requests]
                    expected = {"type": "Recorded", "committed": job["family"] == "CASH-01", "operation_id": original_id,
                                "original_actor_id": aliases[frozen["actions"][0]["actor"]]}
                    original_shapes = [{key: reply.get(key) for key in expected} if isinstance(reply, dict) else reply for reply in replies]
                    component(job, 1, "Both concurrent deliveries resolve the frozen original outcome/actor", [expected, expected], original_shapes, refs,
                              integrity=race_binding)
                    component(job, 1, "Concurrent duplicate replies share original result and journal origin", stable_result(replies[0]), stable_result(replies[1]), refs,
                              missing=any(stable_result(reply) is None for reply in replies), integrity=race_binding)
                else:
                    component(job, 1, "Both concurrent deliveries resolve the frozen original outcome/actor", "two retained original replies", None, refs,
                              missing=True, integrity=race_binding)
                component(job, 1, "Concurrent duplicate deliveries used complete exact original staged bytes", True, exact, refs,
                          missing=requests is None, integrity=race_binding)
                component(job, 1, "Only one original durable result and journal origin is created", {"results": 1, "journals": 1},
                          {"results": len([row for row in after["operations"] if row["operation_id"] == original_id]),
                           "journals": len([row for row in after["journals"] if row["operation_id"] == original_id])} if after is not None else None,
                          refs, missing=after is None, integrity=race_binding)
                if job["family"] == "CASH-02":
                    component(job, 1, "Concurrent occupied-drawer rejections leave existing assignments/business unchanged", True,
                              unchanged(before, after), refs, missing=before is None or after is None, integrity=race_binding)
            else:
                component(job, 1, "Competing distinct-ID opening starts at the frozen unoccupied-drawer cut", [],
                          before["cash_sessions"] if before is not None else None, refs, missing=before is None, integrity=race_binding)
                allowed = [(aliases["SA"], aliases["A"])] if job["family"] == "CASH-01" else [
                    (aliases["SA"], aliases["A"]), (aliases["SB"], aliases["B"])]
                sole = None
                if after is not None:
                    shapes = cash_shape(after)
                    sole = len(shapes) == 1 and any(shapes[0] == {"id": session, "drawer_id": aliases["DA"], "custodian_id": owner,
                        "status": "OPEN", "opening_minor": amount, "expected_cash_minor": amount} for session, owner in allowed)
                component(job, 1, "Exactly one permitted serial-history winner retains one counted opening, never two floats", True, sole, refs,
                          missing=after is None, integrity=race_binding)
                outcomes = None
                if isinstance(requests, list) and len(requests) == 2 and all(isinstance(request, dict) for request in requests):
                    replies = [request.get("reply") for request in requests]
                    applied = [reply for reply in replies if isinstance(reply, dict) and reply.get("type") == "Recorded" and reply.get("committed") is True]
                    rejected = [reply for reply in replies if isinstance(reply, dict) and ((reply.get("type") == "Recorded" and reply.get("committed") is False) or reply.get("type") == "IdentityConflict")]
                    outcomes = len(applied) == 1 and len(rejected) == 1 and len({request.get("operation_id") for request in requests}) == 2 and \
                        requests[0].get("operation_id") == original_id
                    if outcomes and after is not None and len(after["cash_sessions"]) == 1:
                        outcomes = applied[0].get("original_actor_id") == after["cash_sessions"][0]["custodian_id"]
                component(job, 1, "One distinct operation accepted, loser rejected/conflicted, winning actor retained", True, outcomes, refs,
                          missing=requests is None, integrity=race_binding)
            continue

        # Three restart jobs: every missing second process/lookup/retry stays BLOCKED.
        rows1 = events(second)
        observed0 = body(one(rows0, "mutation_observed", "original"))
        original_intent = optional_json(first / "original.intent.json")
        original_reply = observed0.get("reply") if observed0 else None
        cut_snapshot = None
        if mode != "LOST_REQUEST_RESTART":
            original_reply, _, cut_snapshot = original_components(job, first, observed0)
        else:
            before = snapshot(first, "original.before.json")
            cut_snapshot = snapshot(first, "original.after-suppression.json")
            suppression = one(rows0, "request_suppressed_before_delivery")
            armed = one(rows0, "fault_armed_before_target")
            dispatched_original = [row for row in rows0 if row["kind"] == "dispatch" and
                                   row["body"].get("operation_id") == frozen_cases[job["case_id"]]["actions"][0]["operation_id"]]
            suppressed = bool(suppression and armed and armed["ordinal"] < suppression["ordinal"] and not dispatched_original)
            component(job, 1, "Original exact intent retained while prearmed direct adapter does not deliver it", True, suppressed,
                      evidence(first, "events.jsonl", "original.intent.json"), missing=suppression is None or original_intent is None)
            component(job, 1, "Before-delivery drop leaves all original business/result/journal/outbox state unchanged", True,
                      unchanged(before, cut_snapshot, ("aggregates", "claims", "operations", "journals", "outbox")),
                      evidence(first, "original.before.json", "original.after-suppression.json"), missing=before is None or cut_snapshot is None)
        if mode == "DUPLICATE_RESTART":
            duplicate = body(one(rows0, "mutation_observed", "duplicate-before-restart"))
            retry_components(job, first, duplicate, original_reply, original_intent)
            _, duplicate_after = states(first, duplicate)
            cut_snapshot = duplicate_after
        supervision = run / "supervision" / job["family"] / job["id"]
        process0 = optional_json(supervision / "phase-0.process.json")
        process1 = optional_json(supervision / "phase-1.process.json")
        if mode == "LOST_RESPONSE_RESTART":
            armed = one(rows0, "fault_armed_before_target")
            waiting = one(rows0, "initiator_waiting_before_dispatch", "original")
            dispatch = one(rows0, "dispatch", "original")
            discarded = one(rows0, "server_reply_before_independent_read", "original")
            mutation = one(rows0, "mutation_observed", "original")
            at_cut = one(rows0, "initiator_pending_at_response_loss_cut", "original")
            state_file = optional_json(first / "original.initiator-state.json")
            cut_file = optional_json(first / "cut-ready.json")
            expected_pending = {
                "adapter_protocol": "commerce-one-shot-response-loss/v1",
                "initiator_continuation_present": True, "initiator_continuation_active": True,
                "initiator_continuation_completed": False, "initiator_continuation_cancelled": False,
                "initiator_job_active": True, "initiator_job_completed": False,
                "initiator_job_cancelled": False, "initiator_received_results": 0,
                "request_channel_completed": True, "request_channel_cancelled": False,
                "server_observation_channel_completed": True, "server_observation_channel_cancelled": False,
                "server_coroutine_started": True, "server_coroutine_completed": True,
                "server_coroutine_cancelled": False, "distinct_client_and_server_coroutines": True,
            }
            expected_before_dispatch = {**expected_pending, "server_observation_channel_completed": False,
                                        "server_coroutine_started": False, "server_coroutine_completed": False,
                                        "distinct_client_and_server_coroutines": False}
            received = one(rows0, "initiator_response_received", "original")
            discarded_in_order = bool(
                armed and waiting and dispatch and discarded and mutation and at_cut and state_file and cut_file and process0 and
                armed["ordinal"] < waiting["ordinal"] < dispatch["ordinal"] < discarded["ordinal"] < mutation["ordinal"] < at_cut["ordinal"] and
                waiting["body"].get("state") == expected_before_dispatch and
                discarded["body"].get("initiating_client_response_delivered") is False and
                discarded["body"].get("prearmed_response_suppression") is True and
                discarded["body"].get("state") == expected_pending and
                mutation["body"].get("initiating_client_response_delivered") is False and
                mutation["body"].get("reply_observation_source") == "SEPARATE_SERVER_CHANNEL_NOT_INITIATOR" and
                at_cut["body"].get("state_file") == "original.initiator-state.json" and
                at_cut["body"].get("state") == state_file == cut_file.get("initiator_state") == expected_pending and
                all(type(state_file[key]) is type(value) for key, value in expected_pending.items()) and
                received is None and process0.get("response_loss_pending_initiator_state") == expected_pending and
                process0.get("intentional_SIGKILL") is True and process0.get("process_reaped") is True and
                process0.get("exit_code") == -signal.SIGKILL and cut_file.get("pid") == process0.get("pid") and
                process0.get("cut_record_sha256") == digest(first / "cut-ready.json"))
            component(job, 1, "Response loss is armed before dispatch, not retroactively after initiating-client success", True, discarded_in_order,
                      evidence(first, "events.jsonl", "original.initiator-state.json", "cut-ready.json") +
                      evidence(supervision, "phase-0.process.json"),
                      missing=any(value is None for value in (armed, waiting, dispatch, discarded, mutation, at_cut,
                                                            state_file, cut_file, process0)))

        incarnation0 = body(one(rows0, "incarnation_begin"))
        incarnation1 = body(one(rows1, "incarnation_begin"))
        auth0 = [row["body"] for row in rows0 if row["kind"] == "real_sign_in" and row["body"].get("actor_alias") == binding["families"][job["family"]]["original_actor"]]
        auth1 = [row["body"] for row in rows1 if row["kind"] == "real_sign_in" and row["body"].get("actor_alias") == binding["families"][job["family"]]["original_actor"]]
        restart = None
        if all(value is not None for value in (process0, process1, incarnation0, incarnation1)) and len(auth0) == len(auth1) == 1:
            restart = process0.get("intentional_SIGKILL") is True and process0.get("exit_code") == -signal.SIGKILL and \
                process0.get("process_reaped") is True and process1.get("process_reaped") is True and process1.get("exit_code") == 0 and \
                process0.get("expected_cut") == job["phases"][0]["expected_cut"] and \
                dt.datetime.fromisoformat(process1["started_utc"]) >= dt.datetime.fromisoformat(process0["reaped_utc"]) and \
                (process0.get("pid"), process0.get("process_start_ticks")) != (process1.get("pid"), process1.get("process_start_ticks")) and \
                incarnation0.get("pid") == process0.get("pid") and incarnation1.get("pid") == process1.get("pid") and \
                incarnation0.get("issuer_id") != incarnation1.get("issuer_id") and incarnation0.get("boot_id") != incarnation1.get("boot_id") and \
                incarnation0.get("network_namespace") == incarnation1.get("network_namespace") == process0.get("network_namespace") == process1.get("network_namespace") and \
                auth0[0].get("result") == auth1[0].get("result") == "AUTHENTICATED" and \
                auth0[0].get("session_id") is not None and auth1[0].get("session_id") is not None and \
                auth0[0]["session_id"] != auth1[0]["session_id"] and auth1[0].get("after_actual_process_reap") is True
        component(job, 1, "Actual SIGKILL/reap precedes a distinct JVM/issuer/boot and fresh original-user sign-in", True, restart,
                  evidence(first, "events.jsonl", "cut-ready.json") + evidence(second, "events.jsonl") +
                  evidence(supervision, "phase-0.process.json", "phase-1.process.json"), missing=restart is None)
        recovered_snapshot = snapshot(second, "after-real-authentication.json")
        component(job, 1, "New process authenticates without rewriting the original business/source/result/journal/outbox history", True,
                  unchanged(cut_snapshot, recovered_snapshot, ("aggregates", "claims", "operations", "journals", "outbox")),
                  evidence(second, "after-real-authentication.json") + evidence(first, "events.jsonl"), missing=cut_snapshot is None or recovered_snapshot is None)
        lookup = body(one(rows1, "lookup_observed", "recovery-lookup"))
        lookup_reply = lookup.get("reply") if lookup else None
        lookup_before, lookup_after = states(second, lookup)
        component(job, 1, "Original-operation lookup itself adds no business effects or replaces durable origin", True,
                  unchanged(lookup_before, lookup_after, ("aggregates", "claims", "operations", "journals", "outbox")),
                  evidence(second, "events.jsonl"), missing=lookup_before is None or lookup_after is None)
        if mode == "LOST_REQUEST_RESTART":
            component(job, 1, "Pre-delivery original ID is absent at this qualified synthetic current writer before resend", "AbsentAtCurrentWriter",
                      lookup_reply.get("type") if lookup_reply else None, evidence(second, "events.jsonl"), missing=lookup_reply is None)
            resend = body(one(rows1, "mutation_observed", "same-id-resend"))
            original_reply, _, _ = original_components(job, second, resend)
            resent_intent = optional_json(second / resend["staged_intent"]) if resend else None
            component(job, 1, "First post-absence resend reuses the unchanged staged original operation/body/actor", original_intent, resent_intent,
                      evidence(first, "original.intent.json") + evidence(second, "events.jsonl"), missing=original_intent is None or resent_intent is None)
            retry_components(job, second, body(one(rows1, "mutation_observed", "same-id-retry")), original_reply, original_intent)
        else:
            lookup_integrity = new_integrity()
            action = frozen_cases[job["case_id"]]["actions"][0]
            aliases = binding["families"][job["family"]]["aliases"]
            bound_fields(lookup_integrity, "recovery lookup reply", lookup_reply, {"type": "Found"})
            if isinstance(lookup_reply, dict) and lookup_reply.get("type") == "Found":
                fields_present(lookup_integrity, "recovery lookup reply", lookup_reply, ("result",))
                recovered = lookup_reply.get("result")
            else:
                recovered = None
            recorded_integrity(lookup_integrity, "validated original reference", original_reply,
                               action["operation_id"], aliases[action["actor"]], job["family"] == "CASH-01")
            recorded_integrity(lookup_integrity, "recovered original reply", recovered,
                               action["operation_id"], aliases[action["actor"]], job["family"] == "CASH-01")
            bound_fields(lookup_integrity, "original recovery lookup identity", lookup.get("identity") if lookup else None,
                         {"branch_id": aliases["C1-B1"], "operation_id": action["operation_id"],
                          "original_actor_id": aliases[action["actor"]], "semantic_digest_version": 1})
            raw_lookup = referenced_json(second, lookup.get("reply_file") if lookup else None, lookup_integrity, "recovery lookup reply")
            if raw_lookup is not None and lookup_reply is not None:
                binding_condition(lookup_integrity, "raw recovery lookup reply/observation equality", raw_lookup == lookup_reply)
            component(job, 1, "Fresh-session lookup finds the exact original durable result/actor/journal origin", stable_result(original_reply),
                      stable_result(recovered), evidence(second, "events.jsonl", "recovery-lookup.reply.json") + evidence(first, "original.reply.json"),
                      missing=stable_result(original_reply) is None or stable_result(recovered) is None, integrity=lookup_integrity)
            retry_components(job, second, body(one(rows1, "mutation_observed", "after-restart-retry")), original_reply, original_intent)
        extra_components(job, second, rows1)

    result = optional_json(run / "execution-result.json") or {}
    supervision = optional_json(run / "supervision.json") or {}
    inventory["execution_integrity"] = result
    qualified = result.get("status") == "PARTIAL_COMMERCE_SLICES_NOT_APPLICATION_ACCEPTANCE" and \
        all(result.get(key) is True for key in ("runtime_prepared", "runtime_descriptor_valid", "frozen_inputs_unchanged", "network_isolation_evidence_present")) and \
        not result.get("terminal_cutoff_signal") and supervision.get("status") == "ALL_PLANNED_PHASES_OBSERVED" and \
        supervision.get("invocation") == result.get("invocation")
    inventory["evidence_qualification"] = "PARTIAL_ONLY" if qualified else "DIAGNOSTIC_UNQUALIFIED_OR_BLOCKED"
    inventory["status_counts"] = {status: sum(case["status"] == status for case in inventory["cases"]) for status in rank}
    inventory["component_status_counts"] = {status: sum(item["status"] == status for item in components) for status in ("MATCHED_COMPONENT", "BLOCKED", "FAIL")}
    inventory["scenario_statuses"] = {}
    for case in inventory["cases"]:
        for scenario in case["scenario_ids"]:
            previous = inventory["scenario_statuses"].get(scenario, "NOT_RUN")
            inventory["scenario_statuses"][scenario] = max(previous, case["status"], key=lambda x: rank[x])
    write_json(run / "verdict-inventory.after.json", inventory)
    summary = {"status": "FAIL_OBSERVED_COMPONENTS" if inventory["component_status_counts"]["FAIL"] else
               ("PARTIAL_EXECUTION_WITH_BLOCKED_COMPONENTS" if inventory["component_status_counts"]["BLOCKED"] else "PARTIAL_EXECUTION_ONLY"),
               "case_status_counts": inventory["status_counts"], "component_status_counts": inventory["component_status_counts"],
               "complete_case_passes": 0, "complete_assertion_passes": 0, "original_scenario_passes": 0,
               "evidence_qualification": inventory["evidence_qualification"], "limitations": EXPANSION_LIMITATIONS,
               "frozen_cases_sha256": CASES_HASH, "binding_sha256": EXPANSION_BINDING_HASH,
               "preserved_v1_binding_sha256": BINDING_HASH}
    write_json(run / "summary.json", summary)
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", nargs="?", choices=("plan", "compile", "execute"))
    parser.add_argument("--runtime-descriptor", type=Path, default=RUNTIME)
    parser.add_argument("--offline", action="store_true", help="Ask Gradle to use cached dependencies")
    parser.add_argument("--binding-version", choices=("v1", "v2"), default="v1")
    parser.add_argument("--timeout-seconds", type=int, default=1800)
    parser.add_argument("--_isolated-child", help=argparse.SUPPRESS)
    args = parser.parse_args()
    args.runtime_descriptor = args.runtime_descriptor.resolve()
    require(args.runtime_descriptor.is_relative_to(APP / ".workspace/acceptance") and
            args.runtime_descriptor.suffix == ".json", "Runtime descriptors belong in ignored acceptance output")
    require(args.timeout_seconds > 0, "Execution timeout must be positive")
    if args.stage == "plan":
        print(json.dumps({"status": "PLAN_ONLY_NOT_VERIFIED", "binding_version": args.binding_version,
                          "compile_command": compile_command(args.runtime_descriptor, args.offline),
                          "main_class": MAIN, "output": str(OUTPUT),
                          "network": "REQUIRED_NEW_NAMESPACE_NO_FALLBACK", "central_backend": "HELD_NOT_RUN"}, indent=2))
        return 0
    require(sys.platform == "linux", "Commerce slice execution requires Linux network namespaces/process groups")
    original_umask = os.umask(0o077)
    try:
        if args._isolated_child is not None:
            require(args.stage is None, "Child execution cannot also select a public stage")
            return isolated_child(args._isolated_child)
        require(args.stage is not None, "A plan, compile or execute stage is required")
        with signal_latch() as stop:
            if args.stage == "compile":
                prepare_runtime(args.runtime_descriptor, stop, args.offline, args.timeout_seconds)
                print("COMPILED_NOT_EXECUTED — " + str(args.runtime_descriptor))
                return 0
            return execute_run(args, stop)
    finally:
        os.umask(original_umask)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except GateError as error:
        print("COMMERCE_V1_GATE: " + str(error), file=sys.stderr)
        raise SystemExit(2)
    except (KeyboardInterrupt, VerificationInterrupted):
        print("COMMERCE_V1_INTERRUPTED_NOT_ACCEPTED", file=sys.stderr)
        raise SystemExit(130)
    except Exception as error:
        print("COMMERCE_V1_RUNNER_ERROR: " + type(error).__name__ + "; no acceptance result", file=sys.stderr)
        raise SystemExit(2)
