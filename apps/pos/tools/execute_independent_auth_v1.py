#!/usr/bin/env python3
"""Prepare and execute the frozen auth-v1 host slices with ordinary Gradle outputs.

The Gradle runtime descriptor and logs are reusable local outputs. Execution uses
its actual classpath and a fresh synthetic fixture in a real network namespace.
Matched slices remain partial evidence; the full frozen assertions remain required.
Execution reuses disposable current fixtures. Preserve meaningful failure logs and
results before another execution.
"""

import argparse
from collections import Counter
from contextlib import contextmanager
from datetime import datetime, timezone
import errno
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import struct
import subprocess
import sys
import time
import uuid
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
QA = ROOT.parents[1] / "qa" / "pos"
FREEZE = QA / "freezes/auth-v1"
WIRING = QA / "wiring/auth-v1"
OUTPUT = ROOT / ".workspace/acceptance/auth-v1"
RUNTIME = OUTPUT / "runtime.json"
PROFILE = "independent-auth-v1-host-slices-3"
WIRING_FILE = "wiring-v3.json"
CASES_SHA256 = "e6fa22b836f7fd2c2face14d83544edd8554007faee6214c0ec36492a3c96100"
FREEZE_MANIFEST_SHA256 = "697f7732ac1b6f24bf7b854b9f605d3cf2b2c48c32b50db2285219fbe5f52018"
TARGET = ":core:database:prepareIndependentAuthV1"
MAIN_CLASS = "com.ukkera.pos.database.AuthV1Main"

SLICE_IDS = (
    "paired_without_bearer_current", "paired_without_bearer_admin", "unadmitted_valid_password",
    "unadmitted_valid_access", "wrong_branch_peer", "tampered_access", "malformed_access",
    "unauthorized_cross_user_revoke", "logout_access_refresh", "duplicate_logout",
    "logout_durable_fresh_connection", "authorized_revoke_access_refresh",
    "authorized_revoke_durable_fresh_connection",
    "sign_in_current_identity", "sign_in_durable_issuance", "concurrent_default_sign_in",
    "sign_in_reply_discard_relogin", "default_move_requires_confirmation", "confirmed_device_move",
    "concurrent_confirmed_device_moves", "explicit_two_session_cap", "refresh_wrong_device",
    "refresh_rotation_committed_lineage", "refresh_replay_no_scope_or_lifetime_gain",
    "concurrent_refresh_single_lineage", "refresh_reply_discard_relogin", "logout_reply_discard_duplicate",
    "unauthorized_credential_reset", "authorized_reset_old_and_new_credentials",
    "stale_reset_does_not_change_target", "reset_durable_fresh_connection", "concurrent_reset_same_revision",
    "unactivated_profile_cannot_authenticate", "authorized_unactivated_credential_establishment",
    "authorized_disable_auth_paths", "disable_durable_fresh_connection",
    "authorized_device_revoke_auth_paths", "revoked_device_other_admitted_sign_in",
    "device_revoke_durable_fresh_connection",
)


class GateError(RuntimeError):
    pass


class VerificationInterrupted(RuntimeError):
    pass


def require(condition, reason):
    if not condition:
        raise GateError(reason)


def now():
    return datetime.now(timezone.utc).isoformat()


def hash_bytes(data):
    return hashlib.sha256(data).hexdigest()


def read_json(path):
    return json.loads(Path(path).read_bytes())


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


@contextmanager
def signal_latch():
    class Stop:
        signum = None

        def check(self):
            if self.signum is not None:
                raise VerificationInterrupted()

    stop = Stop()
    previous = {}

    def remember(signum, _frame):
        stop.signum = signum

    try:
        for signum in (signal.SIGINT, signal.SIGTERM):
            previous[signum] = signal.signal(signum, remember)
        yield stop
    finally:
        for signum, handler in previous.items():
            signal.signal(signum, handler)


def observe_exit(process):
    # Keep the owned PID until cleanup has finished, including an intentional crash.
    return os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)


def terminate_owned_group(process):
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    return process.wait(timeout=20)


def run_process(command, log, timeout, stop, env=None):
    log = Path(log)
    log.parent.mkdir(parents=True, exist_ok=True)
    result = {"command": command, "started_utc": now(), "exit_code": None,
              "log": str(log), "process_reaped": False, "status": "RUNNING"}
    process = None
    try:
        stop.check()
        with log.open("wb") as stream:
            process = subprocess.Popen(command, cwd=ROOT, env=env, stdin=subprocess.DEVNULL,
                                       stdout=stream, stderr=subprocess.STDOUT,
                                       close_fds=True, start_new_session=True)
            started = time.monotonic()
            try:
                while observe_exit(process) is None:
                    stop.check()
                    if time.monotonic() - started >= timeout:
                        raise subprocess.TimeoutExpired(command, timeout)
                    time.sleep(0.05)
            finally:
                result["exit_code"] = terminate_owned_group(process)
                result["process_reaped"] = process.returncode is not None
            stop.check()
        result["status"] = "EXITED_ZERO" if result["exit_code"] == 0 else "NONZERO_EXIT"
        return result
    except BaseException as error:
        result["status"] = "INTERRUPTED_OR_TIMEOUT" if isinstance(
            error, (VerificationInterrupted, KeyboardInterrupt, subprocess.TimeoutExpired)) else "PROCESS_ERROR"
        result["sanitized_error_type"] = type(error).__name__
        raise
    finally:
        result["finished_utc"] = now()
        write_json(log.with_suffix(".process.json"), result)


def read_runtime(path, schema, target, main_class):
    descriptor = read_json(path)
    require(descriptor.get("schema") == schema and descriptor.get("preparationTask") == target and
            descriptor.get("mainClass") == main_class, "Unexpected JVM runtime descriptor")
    classpath = descriptor.get("classpath")
    require(isinstance(classpath, list) and classpath and all(isinstance(entry, str) for entry in classpath),
            "Missing JVM runtime classpath")
    java = Path(descriptor["javaExecutable"])
    require(java.is_file() and os.access(java, os.X_OK), "Gradle JVM launcher is unavailable")
    main_entry = main_class.replace(".", "/") + ".class"
    found_main = 0
    for entry in classpath:
        path = Path(entry)
        require(path.is_absolute(), "JVM runtime classpath must contain absolute paths")
        require(not any(path.is_relative_to(held) for held in (ROOT / "backend", ROOT / "tools/backend")),
                "Held backend cannot enter the local acceptance runtime")
        if path.is_dir():
            found_main += (path / main_entry).is_file()
        elif path.is_file() and zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as jar:
                found_main += main_entry in jar.namelist()
        # Gradle may include absent optional resource directories in its classpath.
    require(found_main == 1, "Acceptance main is absent or shadowed in the actual Gradle classpath")
    return descriptor


def fresh_run(output):
    """Reuse disposable fixtures after preserving meaningful prior failure diagnostics."""
    require(output.resolve() == output.absolute(), "Synthetic output ancestors must not be symlinks")
    output.mkdir(parents=True, exist_ok=True)
    run = output / "current"
    require(not run.is_symlink(), "Synthetic output directory must not be a symlink")
    if run.exists():
        shutil.rmtree(run)
    run.mkdir(mode=0o700)
    return run


def isolated_environment(run):
    for name in ("private-home", "private-tmp"):
        (run / name).mkdir(parents=True, exist_ok=True)
    return {"PATH": "/usr/bin:/bin", "TMPDIR": str(run / "private-tmp"),
            "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8", "PYTHONDONTWRITEBYTECODE": "1"}


def design_inputs(run=None):
    manifest_bytes = (FREEZE / "FREEZE-MANIFEST.json").read_bytes()
    require(hash_bytes(manifest_bytes) == FREEZE_MANIFEST_SHA256, "Frozen auth manifest changed")
    manifest = json.loads(manifest_bytes)
    files = {"FREEZE-MANIFEST.json": manifest_bytes}
    for entry in manifest["files"]:
        name = entry["path"]
        require(Path(name).name == name, "Invalid frozen manifest filename")
        content = (FREEZE / name).read_bytes()
        require(len(content) == entry["bytes"] and hash_bytes(content) == entry["sha256"],
                "Frozen auth input differs from the freeze")
        files[name] = content
    require(hash_bytes(files["cases.json"]) == CASES_SHA256, "Wrong auth case version")
    design = json.loads(files["cases.json"])
    cases = design["cases"]
    expected = {a["assertion_id"]: a["expected"] for c in cases for a in c["assertions"]}
    require(len(cases) == 73 and len({c["case_id"] for c in cases}) == 73 and
            sum(len(c["assertions"]) for c in cases) == len(expected) == 301,
            "Frozen auth case/assertion registry mismatch")
    wiring_bytes = (WIRING / WIRING_FILE).read_bytes()
    wiring = json.loads(wiring_bytes)
    require(wiring["freeze"]["cases_sha256"] == CASES_SHA256 and
            wiring["freeze"]["manifest_sha256"] == FREEZE_MANIFEST_SHA256,
            "Wiring does not bind the frozen auth design")
    wired_expected = {a["assertion_id"]: a["frozen_expected"] for c in wiring["cases"] for a in c["assertions"]}
    require(wired_expected == expected and len(wiring["cases"]) == 73,
            "Wiring changed or omitted frozen assertions")
    slices = wiring["slice_registry"]
    require([s["slice_id"] for s in slices] == list(SLICE_IDS) and
            all(s["coverage"] == "PARTIAL" and s["assertion_ids"] and
                all(a in expected for a in s["assertion_ids"]) for s in slices),
            "Unregistered or full-pass auth slice wiring")
    if run is not None:
        # The existing JVM entry reads only these frozen oracle inputs from its fixture.
        for name, content in files.items():
            destination = run / "frozen" / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(content)
        destination = run / "wiring" / WIRING_FILE
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(wiring_bytes)
    return design, wiring, hash_bytes(wiring_bytes)


def compile_command(descriptor=RUNTIME, offline=False):
    command = ["./gradlew", "--no-daemon", "--console=plain"]
    if offline:
        command.append("--offline")
    return command + ["--init-script", "tools/independent_auth_v1.gradle",
                      "-PindependentAuthV1RuntimeDescriptor=" + str(descriptor), TARGET]


def prepare_runtime(descriptor, stop, offline=False, timeout=900):
    phase = run_process(compile_command(descriptor, offline), OUTPUT / "compile.log", timeout, stop)
    require(phase["exit_code"] == 0, "Auth runtime preparation failed; see compile.log")
    runtime = read_runtime(descriptor, "independent-auth-compiled-runtime/v1", TARGET, MAIN_CLASS)
    write_json(OUTPUT / "compilation.json", {"status": "COMPILED_NOT_EXECUTED", "runtime_descriptor": str(descriptor),
                                             "phase": phase, "application_acceptance": False})
    return runtime


def isolated_command(run):
    return ["/usr/bin/unshare", "--net", "--", sys.executable,
            str(ROOT / "tools/execute_independent_auth_v1.py"), "--_isolated-child", str(run)]


def network_attributes(data):
    """Read bounded rtnetlink attributes; no sysfs mount/namespace assumptions."""
    values, offset = {}, 0
    while offset < len(data):
        require(len(data) - offset >= 4, "Truncated network attribute")
        length, kind = struct.unpack_from("=HH", data, offset)
        require(length >= 4 and offset + length <= len(data), "Invalid network attribute length")
        key = kind & 0x3fff  # Strip NLA_F_NESTED / NLA_F_NET_BYTEORDER from attribute identity.
        require(key not in values, "Duplicate network attribute")
        values[key] = data[offset + 4:offset + length]
        offset += (length + 3) & ~3
    return values


def network_kernel_dump(request, reply, body):
    """Read-only NETLINK_ROUTE dump in the already isolated namespace, never a TCP probe."""
    require((request, reply) in {(18, 16), (22, 20)}, "Unregistered network observation request")
    result, sequence = [], request
    with socket.socket(socket.AF_NETLINK, socket.SOCK_RAW, socket.NETLINK_ROUTE) as channel:
        channel.settimeout(2)
        channel.bind((0, 0))
        header = struct.pack("=IHHII", 16 + len(body), request, 0x301, sequence, channel.getsockname()[0])
        require(channel.sendto(header + body, (0, 0)) == len(header) + len(body), "Incomplete network observation request")
        for _ in range(32):
            data, _, received_flags, sender = channel.recvmsg(65536)
            require(sender == (0, 0) and not received_flags & socket.MSG_TRUNC, "Unbound or truncated kernel network dump")
            offset = 0
            while offset < len(data):
                require(len(data) - offset >= 16, "Truncated kernel network header")
                length, kind, flags, observed_sequence, _ = struct.unpack_from("=IHHII", data, offset)
                require(length >= 16 and offset + length <= len(data) and observed_sequence == sequence and
                        not flags & 0x10, "Invalid or interrupted kernel network dump")
                payload = data[offset + 16:offset + length]
                if kind == 3:  # NLMSG_DONE, including its optional integer error result.
                    require(not payload or (len(payload) >= 4 and struct.unpack_from("=i", payload)[0] == 0),
                            "Kernel network dump did not finish normally")
                    return result
                require(kind == reply and len(result) < 256, "Unexpected or oversized kernel network dump")
                result.append(payload)
                offset += (length + 3) & ~3
    raise GateError("Kernel network observation budget exceeded")


def network_interface_observation():
    """Exact safe link/address facts only; no app, test oracle, interface mutation or external connect."""
    links = []
    for body in network_kernel_dump(18, 16, struct.pack("=BBHiII", socket.AF_UNSPEC, 0, 0, 0, 0, 0)):
        require(len(body) >= 16, "Truncated interface observation")
        _, _, hardware, index, flags, _ = struct.unpack_from("=BBHiII", body)
        attributes = network_attributes(body[16:])
        info = network_attributes(attributes[18]) if 18 in attributes else {}
        tunnel = network_attributes(info[2]) if 2 in info else {}
        require(3 in attributes and attributes[3].endswith(b"\0") and index > 0, "Missing interface identity")
        require(16 not in attributes or len(attributes[16]) == 1, "Invalid interface operational state")
        links.append({"name": attributes[3][:-1].decode("ascii"), "ifindex": index, "hardware_type": hardware,
                      "flags": flags, "flags_hex": hex(flags), "administratively_up": bool(flags & 1),
                      "running": bool(flags & 64), "hardware_address_hex": attributes.get(1, b"").hex(),
                      "operstate": attributes[16][0] if 16 in attributes else None,
                      "kind": info[1].rstrip(b"\0").decode("ascii") if 1 in info else None,
                      "tunnel_link": struct.unpack("=I", tunnel[1])[0] if 1 in tunnel else None,
                      "tunnel_local": socket.inet_ntop(socket.AF_INET, tunnel[2]) if 2 in tunnel else None,
                      "tunnel_remote": socket.inet_ntop(socket.AF_INET, tunnel[3]) if 3 in tunnel else None})
    require(sorted((link["ifindex"], link["name"]) for link in links) == sorted(socket.if_nameindex()),
            "Interface identities changed during kernel observation")
    addresses = []
    for body in network_kernel_dump(22, 20, struct.pack("=BBBBI", socket.AF_UNSPEC, 0, 0, 0, 0)):
        require(len(body) >= 8, "Truncated interface address observation")
        family, prefix, flags, scope, index = struct.unpack_from("=BBBBI", body)
        attributes = network_attributes(body[8:])
        address = attributes.get(2, attributes.get(1))
        addresses.append({"ifindex": index, "family": family, "prefix": prefix, "scope": scope,
                          "address": socket.inet_ntop(family, address) if address and
                          family in (socket.AF_INET, socket.AF_INET6) else None})
    return {"interfaces": sorted(links, key=lambda link: link["name"]),
            "addresses": sorted(addresses, key=lambda address: (address["ifindex"], address["family"],
                                                               address["address"] or "", address["prefix"]))}


def require_inert_network_interfaces(observation):
    links = {link["name"]: link for link in observation["interfaces"]}
    require(len(links) == len(observation["interfaces"]) and sorted(links) in (["lo"], ["lo", "sit0"]),
            "Isolated namespace has an unregistered interface")
    require(links["lo"]["hardware_type"] == 772 and links["lo"]["flags"] & 8,
            "Namespace loopback identity was not proven")
    known_indices = {link["ifindex"] for link in links.values()}
    require(all(address["ifindex"] in known_indices for address in observation["addresses"]),
            "Address observation contains an unknown interface")
    if "sit0" in links:
        sit = links["sit0"]
        # Linux may create this inert IPv6-in-IPv4 fallback in a NEW namespace.
        # Do not permit arbitrary tunnels, UP/RUNNING state, addresses or endpoints.
        require(sit["hardware_type"] == 776 and sit["kind"] == "sit" and sit["flags"] == 0x80 and
                sit["operstate"] == 2 and sit["hardware_address_hex"] == "00000000" and
                sit["tunnel_link"] == 0 and sit["tunnel_local"] == "0.0.0.0" and sit["tunnel_remote"] == "0.0.0.0" and
                not any(address["ifindex"] == sit["ifindex"] for address in observation["addresses"]),
                "sit0 is not the DOWN unaddressed kernel-default tunnel")


def network_route_observation():
    route4 = Path("/proc/self/net/route").read_text().splitlines()
    route6 = Path("/proc/self/net/ipv6_route").read_text().splitlines()
    require(not any(line.split() and line.split()[0] != "lo" for line in route4[1:]), "Unexpected IPv4 egress interface")
    require(not any(line.split() and line.split()[-1] != "lo" for line in route6), "Unexpected IPv6 egress interface")
    return {"ipv4_route_rows": route4[1:], "ipv6_route_rows": route6}


def network_proof(invocation, parent_namespace):
    def observe(stage, facts):
        # Safe kernel/interface/numeric-errno scalars only. Preserve preflight
        # diagnostics even when a later gate refuses before network-proof.json.
        print("UKKERA_NETWORK_PREFLIGHT " + json.dumps(
            {"schema": "isolated-network-observation/v1", "invocation": invocation,
             "stage": stage, "facts": facts}, sort_keys=True), flush=True)

    namespace = os.readlink("/proc/self/ns/net")
    require(namespace != parent_namespace, "unshare did not establish a new network namespace")
    before = network_interface_observation()
    require_inert_network_interfaces(before)
    routes_before = network_route_observation()
    observe("INITIAL_KERNEL_NETWORK_STATE", {"namespace": namespace, "parent_namespace": parent_namespace,
                                             "interfaces_and_addresses": before, "routes": routes_before})
    failures, qualifications = {}, {}
    try:
        for family, host in ((socket.AF_INET, "198.51.100.1"), (socket.AF_INET6, "2001:db8::1")):
            label = "ipv4" if family == socket.AF_INET else "ipv6"
            operation = "SOCKET_SETUP"
            try:
                with socket.socket(family, socket.SOCK_STREAM) as connection:
                    connection.settimeout(2)
                    operation = "CONNECT_EX"
                    code = connection.connect_ex((host, 9))
                    operation = "SOCKET_CLOSE"
            except OSError as error:
                observe("SOCKET_OR_CONNECT_EXCEPTION_NOT_DENIAL", {
                    "family": label, "target": host, "port": 9, "operation": operation,
                    "errno": error.errno, "errno_name": errno.errorcode.get(error.errno, str(error.errno))})
                raise GateError("Socket/setup/timeout/connect exception is not a connectivity denial proof") from None
            missing_ipv6_source = (
                family == socket.AF_INET6 and code == errno.EADDRNOTAVAIL and not before["addresses"] and
                all(not link["administratively_up"] and not link["running"] and link["operstate"] == 2
                    for link in before["interfaces"]))
            observe("NUMERIC_CONNECT_RESULT", {"family": label, "target": host, "port": 9, "errno": code,
                                                "errno_name": errno.errorcode.get(code, str(code)),
                                                "result_kind": "ACTUAL_CONNECT_EX_RETURN_NOT_EXCEPTION",
                                                "missing_ipv6_source_qualification": missing_ipv6_source})
            require(code in {errno.ENETUNREACH, errno.EHOSTUNREACH, errno.EACCES, errno.EPERM, errno.EAFNOSUPPORT} or
                    missing_ipv6_source, "Numeric external-address denial was not proven")
            failures[label] = errno.errorcode.get(code, str(code))
            qualifications[label] = ("IPV6_MISSING_LOCAL_SOURCE_ALL_LINKS_DOWN_NO_ADDRESSES" if missing_ipv6_source
                                     else "EXPLICIT_OS_CONNECT_EX_REFUSAL_RETURN")
    finally:
        # Preserve final safe facts even when a numeric gate refuses. No failure is
        # converted into a proof, and namespace/interface drift always remains fatal.
        after = network_interface_observation()
        require_inert_network_interfaces(after)
        routes_after = network_route_observation()
        observe("FINAL_KERNEL_NETWORK_STATE", {"namespace": os.readlink("/proc/self/ns/net"),
                                               "interfaces_and_addresses": after, "routes": routes_after})
        require(before == after and os.readlink("/proc/self/ns/net") == namespace,
                "Isolated network interfaces/addresses/namespace changed during denial proof")
    return {"schema": "independent-auth-network-denial/v1", "invocation": invocation,
            "status": "ISOLATED_NO_EXTERNAL_NETWORK", "parent_namespace": parent_namespace, "namespace": namespace,
            "interfaces": [link["name"] for link in before["interfaces"]],
            "interface_address_observation_before": before, "interface_address_observation_after": after,
            "route_observation_before": routes_before, "route_observation_after": routes_after,
            "interface_exception": "ONLY_EXACT_DOWN_UNADDRESSED_KERNEL_DEFAULT_SIT0_IF_PRESENT",
            "non_loopback_route_count": 0, "numeric_external_connect_results": failures,
            "numeric_external_connect_qualifications": qualifications,
            "central_or_public_discovery_started": False,
            "boundary": "New Linux net namespace, inherited by child JVM; no real LAN/RPC acceptance",
            "limit": "Trusted local code, not hostile-code/privileged-setns/AF_UNIX isolation"}


def isolated_child(run):
    run = Path(run).resolve()
    require(run == OUTPUT / "current", "Unknown synthetic auth output")
    request = read_json(run / "request.json")
    require(request["profile"] == PROFILE and request["mode"] in {"execute", "network-probe"},
            "Invalid auth execution request")
    proof = network_proof(request["invocation"], request["parent_network_namespace"])
    write_json(run / "network-proof.json", proof)
    if request["mode"] == "network-probe":
        return 0
    runtime = read_runtime(request["runtime_descriptor"], "independent-auth-compiled-runtime/v1", TARGET, MAIN_CLASS)
    java = runtime["javaExecutable"]
    command = [java, "-Xmx256m", "-Djava.awt.headless=true", "-XX:+DisableAttachMechanism",
               "-XX:-HeapDumpOnOutOfMemoryError", "-XX:-CreateCoredumpOnCrash",
               "-Duser.home=" + str(run / "private-home"), "-Djava.io.tmpdir=" + str(run / "private-tmp"),
               "-cp", os.pathsep.join(runtime["classpath"]), MAIN_CLASS, str(run)]
    write_json(run / "java-invocation.json", {"command": command, "profile": PROFILE,
              "runtime_descriptor": request["runtime_descriptor"], "network_proof": "network-proof.json"})
    os.execve(java, command, dict(os.environ))
    raise AssertionError("execve returned")


def assertion_results(design, wiring, run, invocation):
    slices = {s["slice_id"]: s for s in wiring["slice_registry"]}
    observed = {}
    events = []
    errors = []
    path = run / "observations.jsonl"
    if path.exists():
        data = path.read_bytes()
        for number, line in enumerate(data.splitlines(), 1):
            try:
                event = json.loads(line)
                require(event["invocation"] == invocation and event["sequence"] == number, "Unbound/out-of-order observation")
                events.append(event)
                if event["kind"] == "slice":
                    key = event["id"]
                    require(key in slices and key not in observed and event.get("coverage") == "PARTIAL" and
                            event.get("assertion_ids") == slices[key]["assertion_ids"] and
                            event["outcome"] in {"PASS_SLICE", "FAIL", "ERROR"}, "Unregistered/duplicate slice evidence")
                    observed[key] = event
            except (ValueError, KeyError, TypeError, GateError):
                errors.append({"line": number, "reason": "MALFORMED_OR_UNBOUND_OBSERVATION"})
    cases = []
    for case in design["cases"]:
        assertions = []
        for assertion in case["assertions"]:
            identities = [sid for sid, value in slices.items() if assertion["assertion_id"] in value["assertion_ids"]]
            results = [{"slice_id": sid, "outcome": observed[sid]["outcome"] if sid in observed else "NOT_RUN",
                        "coverage": "PARTIAL", "observation_sequence": observed[sid]["sequence"] if sid in observed else None}
                       for sid in identities]
            outcomes = {r["outcome"] for r in results}
            status = "FAIL" if "FAIL" in outcomes else "ERROR" if "ERROR" in outcomes else "PARTIAL" if "PASS_SLICE" in outcomes else "NOT_RUN"
            assertions.append({"assertion_id": assertion["assertion_id"], "frozen_expected": assertion["expected"],
                               "status": status, "slices": results,
                               "full_assertion_verified": False, "remaining_obligations": "Full frozen assertion, adapters, fixtures, schedules and platforms remain required"})
        statuses = {a["status"] for a in assertions}
        status = "FAIL" if "FAIL" in statuses else "ERROR" if "ERROR" in statuses else "PARTIAL" if "PARTIAL" in statuses else "NOT_RUN"
        cases.append({"case_id": case["case_id"], "status": status, "full_case_verified": False, "assertions": assertions,
                      "variants": [{"variant_id": v["variant_id"], "status": "NOT_RUN", "frozen_expected": v["expected"]} for v in case["variants"]],
                      "environment_obligations": case["environment"], "owner_decision_dependencies": case["owner_decision_dependencies"],
                      "behavior_contract_dependencies": case["behavior_contract_dependencies"]})
    complete = (not errors and len(observed) == len(slices) and all(e["outcome"] == "PASS_SLICE" for e in observed.values()) and
                events and events[-1].get("outcome") == "PARTIAL_AUTH_SLICES_NOT_ACCEPTANCE")
    result = {"schema": "independent-auth-results/v1", "invocation": invocation, "freeze_sha256": CASES_SHA256,
              "ownership": wiring["ownership"], "cases": cases, "malformed_evidence": errors,
              "all_planned_partial_slices_completed": bool(complete), "full_assertion_pass_count": 0, "full_case_pass_count": 0,
              "assertion_status_counts": dict(Counter(a["status"] for c in cases for a in c["assertions"])),
              "case_status_counts": dict(Counter(c["status"] for c in cases)), "global_not_run": wiring["global_not_run"]}
    write_json(run / "results.json", result)
    return result


def execute_parent(args, stop):
    if args.compile:
        prepare_runtime(args.runtime_descriptor, stop, args.offline, args.timeout_seconds)
        print("COMPILED_NOT_EXECUTED — " + str(args.runtime_descriptor))
        return 0
    mode = "execute" if args.execute is not None else "network-probe"
    descriptor = args.execute if args.execute is not None else args.runtime_descriptor
    run = fresh_run(OUTPUT)
    invocation = uuid.uuid4().hex
    evidence = {"schema": "independent-auth-execution/v1", "profile": PROFILE, "mode": mode,
                "invocation": invocation, "started_at": now(), "status": "STARTED_NOT_ACCEPTED",
                "runtime_descriptor": str(descriptor), "phases": [], "central_backend": "HELD_NOT_RUN",
                "application_acceptance": False, "full_case_pass_count": 0}
    design = wiring = None
    candidate = "ERROR_NOT_ACCEPTED"
    try:
        design, wiring, wiring_hash = design_inputs(run)
        if mode == "execute":
            prepare_runtime(descriptor, stop, args.offline, args.timeout_seconds)
        write_json(run / "request.json", {"profile": PROFILE, "mode": mode, "invocation": invocation,
                   "parent_network_namespace": os.readlink("/proc/self/ns/net"),
                   "runtime_descriptor": str(descriptor), "wiring_file": WIRING_FILE, "wiring_sha256": wiring_hash})
        phase = run_process(isolated_command(run), run / "execution.log", args.timeout_seconds, stop,
                            isolated_environment(run))
        evidence["phases"].append(phase)
        proof = read_json(run / "network-proof.json")
        require(proof.get("status") == "ISOLATED_NO_EXTERNAL_NETWORK" and proof.get("invocation") == invocation,
                "Auth network isolation was not proven")
        require(phase["exit_code"] == 0, "Auth execution did not complete; see execution.log")
        candidate = "PARTIAL_AUTH_SLICES_NOT_APPLICATION_ACCEPTANCE" if mode == "execute" else "NETWORK_PROBE_ONLY_NOT_APPLICATION_VERIFICATION"
    except BaseException as error:
        evidence["sanitized_error_type"] = type(error).__name__
        if isinstance(error, GateError):
            evidence["gate_reason"] = str(error)
        candidate = "INTERRUPTED_OR_TIMEOUT_NOT_ACCEPTED" if isinstance(error, (
            VerificationInterrupted, KeyboardInterrupt, subprocess.TimeoutExpired)) else "ERROR_NOT_ACCEPTED"
    finally:
        if design is not None and wiring is not None:
            try:
                after_design, after_wiring, _ = design_inputs()
                require(after_design == design and after_wiring == wiring, "Frozen auth inputs changed during execution")
                result = assertion_results(design, wiring, run, invocation)
                evidence["assertion_status_counts"] = result["assertion_status_counts"]
                evidence["case_status_counts"] = result["case_status_counts"]
                if mode == "execute" and candidate == "PARTIAL_AUTH_SLICES_NOT_APPLICATION_ACCEPTANCE" and not result["all_planned_partial_slices_completed"]:
                    candidate = "INCOMPLETE_OR_FAILED_SLICES_NOT_ACCEPTED"
            except BaseException as error:
                evidence["result_error_type"] = type(error).__name__
                candidate = "RESULT_EVIDENCE_UNAVAILABLE_NOT_ACCEPTED"
    evidence["terminal_cutoff_signal"] = signal.Signals(stop.signum).name if stop.signum else None
    evidence["status"] = "INTERRUPTED_NOT_ACCEPTED" if stop.signum else candidate
    evidence["finished_at"] = now()
    evidence["results_are_diagnostic_if_run_not_accepted"] = True
    write_json(run / "execution.json", evidence)
    print(f"{evidence['status']} — {run}/execution.json", flush=True)
    return 0 if evidence["status"] in {"PARTIAL_AUTH_SLICES_NOT_APPLICATION_ACCEPTANCE",
                                       "NETWORK_PROBE_ONLY_NOT_APPLICATION_VERIFICATION"} else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    choice = parser.add_mutually_exclusive_group(required=True)
    choice.add_argument("--show-plan", action="store_true", help="Print the commands without preparing or executing")
    choice.add_argument("--compile", action="store_true", help="Prepare the reusable JVM runtime descriptor")
    choice.add_argument("--execute", nargs="?", const=True, type=Path, metavar="RUNTIME_DESCRIPTOR",
                        help="Prepare the runtime, then execute the frozen partial slices")
    choice.add_argument("--probe-network", action="store_true", help="Prove network isolation without running Java")
    choice.add_argument("--_isolated-child", type=Path, help=argparse.SUPPRESS)
    parser.add_argument("--runtime-descriptor", type=Path, default=RUNTIME)
    parser.add_argument("--offline", action="store_true", help="Ask Gradle to use cached dependencies")
    parser.add_argument("--timeout-seconds", type=int, default=900)
    args = parser.parse_args()
    args.runtime_descriptor = args.runtime_descriptor.resolve()
    if args.execute is True:
        args.execute = args.runtime_descriptor
    elif args.execute is not None:
        args.execute = args.execute.resolve()
    for descriptor in (args.runtime_descriptor, args.execute):
        if descriptor is not None:
            require(descriptor.is_relative_to(ROOT / ".workspace/acceptance") and descriptor.suffix == ".json",
                    "Runtime descriptors belong in ignored acceptance output")
    require(args.timeout_seconds > 0, "Execution timeout must be positive")
    if args.show_plan:
        print(json.dumps({"profile": PROFILE, "status": "PLAN_ONLY_NOT_VERIFIED",
                          "compile_command": compile_command(args.runtime_descriptor, args.offline),
                          "main_class": MAIN_CLASS, "slice_count": len(SLICE_IDS),
                          "full_assertion_passes_possible": 0, "full_case_passes_possible": 0,
                          "network": "REQUIRED_NEW_NAMESPACE_NO_FALLBACK", "output": str(OUTPUT),
                          "central_backend": "HELD_NOT_RUN"}, indent=2))
        return 0
    require(sys.platform == "linux", "Auth slice execution requires Linux network namespaces/process groups")
    original_umask = os.umask(0o077)
    try:
        if args._isolated_child is not None:
            return isolated_child(args._isolated_child)
        with signal_latch() as stop:
            return execute_parent(args, stop)
    finally:
        os.umask(original_umask)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except GateError as error:
        print("AUTH_V1_GATE: " + str(error), file=sys.stderr)
        raise SystemExit(2)
    except (KeyboardInterrupt, VerificationInterrupted):
        print("AUTH_V1_INTERRUPTED_NOT_ACCEPTED", file=sys.stderr)
        raise SystemExit(130)
    except Exception as error:
        print("AUTH_V1_RUNNER_ERROR: " + type(error).__name__ + "; no acceptance result", file=sys.stderr)
        raise SystemExit(2)
