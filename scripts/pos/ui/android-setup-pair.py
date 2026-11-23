"""Drive two actual training setup sessions bound to different Android guests."""

from concurrent.futures import ThreadPoolExecutor
import hashlib
import ipaddress
import json
import os
import re
from queue import Empty, Queue
from pathlib import Path
import secrets
import struct
import subprocess
import sys
import tempfile
import threading
import time

from agent import Client, ClientError
from lease import HoldLease


PACKAGE = "com.ukkera.pos.local.testing"
DOCUMENT_PICKERS = {"com.android.documentsui", "com.google.android.documentsui"}
GUESTS = {"master": "emulator-5554", "client": "emulator-5556"}
WORK = Path(os.environ["RUNNER_TEMP"]) / "pos-ui"
REPORT = WORK / "public"
LIBRARY = Path(os.environ["GITHUB_WORKSPACE"]) / "ui-client"
SETUP_STAGE = os.environ.get("POS_ANDROID_SETUP_STAGE", "allocation")
LAN = SETUP_STAGE == "lan-after-admission"
ADMIT = SETUP_STAGE == "admission-after-restart" or LAN
PAIR = SETUP_STAGE in {"pairing-proof", "pairing-after-restart"} or ADMIT
ACTIVATE = SETUP_STAGE == "activate" or PAIR
UUID = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")


def adb(device, *args):
    return subprocess.check_output(["adb", "-s", device, *args], timeout=25, text=True)


class InstalledUi:
    def __init__(self, role, device):
        self.directory = WORK / role
        self.directory.mkdir(mode=0o700)
        self.token_file = self.directory / "token"
        token = secrets.token_hex(32)
        self.token_file.write_text(token)
        self.device = device
        self.stop = None
        self.heartbeat = None
        self.process = None
        self.expires_unix = None
        self.env = {
            **os.environ,
            "RUNNER_TEMP": str(self.directory),
            "GITHUB_OUTPUT": str(self.directory / "output"),
            "GITHUB_STEP_SUMMARY": str(self.directory / "private-summary"),
            "UI_TEST_TARGET": "android",
            "UI_TEST_DEVICE": device,
            "UI_TEST_TOKEN_SHA256": hashlib.sha256(token.encode()).hexdigest(),
            "UI_TEST_STARTUP_TIMEOUT": "60",
            "UI_TEST_DURATION": "16" if PAIR else "8",
            "UI_TEST_TRANSPORT": "local",
            "UI_TEST_ANDROID_IME": "0",
            "UI_TEST_LAUNCH": "",
            "UI_TEST_READY": "",
        }

    def start(self):
        # The setup review contains long, scrollable groups. Use a fixed tablet
        # viewport for these emulators so failure observations include headings
        # and the actual refusal, regardless of where the last form was scrolled.
        adb(self.device, "shell", "wm", "size", "1080x1920")
        adb(self.device, "shell", "wm", "density", "180")
        adb(self.device, "shell", "svc", "power", "stayon", "true")
        adb(self.device, "shell", "am", "start", "-W", "-n",
            f"{PACKAGE}/com.ukkera.pos.android.MainActivity")
        with (self.directory / "startup.log").open("wb") as log:
            self.process = subprocess.Popen([sys.executable, str(LIBRARY / "lifecycle.py"), "start"],
                                            env=self.env, stdout=log, stderr=subprocess.STDOUT)
        try:
            if self.process.wait(timeout=280):
                raise AssertionError("The selected guest's UI driver did not start")
        except subprocess.TimeoutExpired:
            self.process.terminate()
            raise AssertionError("The selected guest's UI driver startup deadline expired") from None
        output = (self.directory / "output").read_text().splitlines()
        (file,) = [Path(line.removeprefix("file=")) for line in output if line.startswith("file=")]
        assert file.parent.parent == self.directory
        self.stop = file.parent / "stop"
        marker = file.parent / HoldLease.heartbeat
        marker.touch()
        self.heartbeat = threading.Event()

        def renew():
            while not self.heartbeat.wait(2):
                marker.touch()

        threading.Thread(target=renew, daemon=True).start()
        connection = json.loads(file.read_bytes())
        assert connection["target"] == "android" and connection["transport"] == "local"
        self.expires_unix = int(connection["expires_unix"])
        self.session = Client(connection, self.token_file)
        self.session.identify()
        png = self.session.fetch("/v1/screenshot")
        assert png.startswith(b"\x89PNG\r\n\x1a\n")
        self.width, self.height = struct.unpack_from(">II", png, 16)

    def session_seconds_remaining(self):
        return max(0, int(self.expires_unix - time.time()))

    def close(self):
        if self.heartbeat is not None:
            self.heartbeat.set()
        if self.stop is not None:
            self.stop.touch()
        else:
            for control in self.directory.glob("ui-session-*"):
                (control / "stop").touch()
        self.token_file.unlink(missing_ok=True)

    def action(self, command, **params):
        reply = self.session.command(command, params)
        if not reply["ok"]:
            raise AssertionError(f"Android UI command failed: {command}")
        return reply["result"]

    def find(self, label, scroll=0, wait=0):
        for attempt in range(abs(scroll) + 1):
            try:
                found = self.action("element.wait", selector={"text_contains": label},
                                    state="present", timeout_ms=wait or 1400)
                if found["matched"] == 1:
                    return found
                raise AssertionError(f"Ambiguous accessible control: {label}")
            except ClientError as error:
                if error.status != 408 or attempt == abs(scroll):
                    raise AssertionError(f"Expected accessible control is missing: {label}") from None
            self.action("pointer.scroll", x=self.width // 2, y=self.height // 2,
                        dy=(min(20, self.height // 55) if scroll > 0 else -min(20, self.height // 55)))
            time.sleep(0.15)

    def choose(self, label, scroll=0):
        found = self.find(label, scroll=scroll)
        node = found["nodes"][0]
        assert node.get("enabled", True), f"Expected enabled control: {label}"
        self.action("element.click", snapshot_id=found["snapshot_id"], id=node["id"])

    def find_admission_consent(self, label, scroll):
        for attempt in range(abs(scroll) + 1):
            snapshot = self.action("tree.snapshot")
            node = admission_consent_control(snapshot, label)
            if node is not None:
                return snapshot["snapshot_id"], node["id"]
            if attempt < abs(scroll):
                visible_consent = any(
                    node.get("visible") and node["properties"].get("package") == PACKAGE
                    and label in (node.get("name"), node.get("text"))
                    for node in snapshot["nodes"]
                )
                if not visible_consent:
                    travel = min(20, self.height // 55)
                    self.action("pointer.scroll", x=self.width // 2, y=self.height // 2,
                                dy=travel if scroll > 0 else -travel)
                time.sleep(0.15)
        raise AssertionError("The exact device admission consent was not actionable")

    def await_document_picker(self, observation, key, save=False):
        # The installed application first re-reads the native original. Its
        # 30-second operation watch still owns that read and opening the picker.
        transitions = observation[key] = []
        started = time.monotonic()
        snapshot_errors = 0
        while time.monotonic() - started < 34:
            try:
                snapshot = self.action("tree.snapshot")
            except ClientError as failure:
                detail = (failure.payload or {}).get("error")
                if failure.status != 502 or not isinstance(detail, dict) or detail.get("code") != "driver_error":
                    raise
                resumed = adb(self.device, "shell", "dumpsys", "activity", "activities")
                packages = {package for package in {PACKAGE, *DOCUMENT_PICKERS}
                            if any(f" {package}/" in line for line in resumed.splitlines() if "ResumedActivity" in line)}
                snapshot_errors += 1
                transitions.append({"state": "observer_snapshot_failed", "known_foreground": sorted(packages),
                                    "elapsed_seconds": round(time.monotonic() - started, 2)})
                if not packages or snapshot_errors > 2:
                    raise
                # Only the accessibility read is retried. The previously sent
                # import/export and native save actions are never sent again.
                time.sleep(0.4)
                continue
            nodes = snapshot["nodes"]
            picker = [node for node in nodes if node.get("visible")
                      and node["properties"].get("package") in DOCUMENT_PICKERS]
            saves = [node for node in picker if node.get("clickable", node["properties"].get("clickable"))
                     and any(str(node.get(field, "")).casefold() == "save" for field in ("name", "text"))]
            assert len(saves) <= 1, "The system document picker has multiple Save controls"
            enabled_save = bool(saves and saves[0].get("enabled", True))
            state = ("system_save_available" if enabled_save else "system_picker_present" if picker
                     else "pos_visible" if any(node["properties"].get("package") == PACKAGE for node in nodes)
                     else "another_window_visible")
            if not transitions or transitions[-1]["state"] != state:
                transitions.append({"state": state, "elapsed_seconds": round(time.monotonic() - started, 2)})
            if save and enabled_save:
                self.action("element.click", snapshot_id=snapshot["snapshot_id"], id=saves[0]["id"])
                return
            if not save and picker:
                return
            time.sleep(0.4)
        observation[f"{key}_setup_outcome"] = self.visible_setup_outcome()
        raise AssertionError("The installed flow did not present the required Android document picker control")

    def visible_setup_outcome(self, document_name=None):
        # Keep only fixed statuses from the installed UI, never text field
        # values, credential inputs, branch identifiers or device identities.
        nodes = self.action("tree.snapshot")["nodes"]
        observed = tuple(str(node.get(field, "")) for node in nodes for field in ("name", "text"))
        fixed = {
            "setup_page": "Synthetic training composition",
            "entry_page": "Set up or inspect the original local branch",
            "setup_locked": "Review setup again",
            "master_operating_notice": "This device is the local master",
            "company_review": "Confirm this original company",
            "company_unresolved": "Original state remains unresolved",
            "waiting_for_original_journals": "Opening protected original client journals",
            "journal_unavailable": "Client enrollment or existing journal verification did not complete",
            "journal_connected": "The original device and first employee journals are connected",
            "local_device_pairing_available": "Pair a new target through this original Master",
            "pairing_page": "Pair a new physical device",
            "target_input_review": "The target request is reviewed DATA. Explicit preparation is still required.",
            "pairing_document_exported": "Exact retained document exported",
            "pairing_document_imported": "Complete document imported for review",
            "pairing_document_cancelled": "Document transfer cancelled",
            "pairing_document_unavailable": "Document transfer unavailable",
            "pairing_document_footer": "Transfer the complete binary document",
            "pairing_context_pending": "Reading the exact original pairing context",
            "pairing_context_unavailable": "The original pairing context is unavailable",
            "pairing_import_stage": "Import the original target identity for review",
            "pairing_import_control": "Import the target identity document (I)",
            "device_admission_page": "Retained original device admission",
            "device_admission_approval_work": "Awaiting named-head approval of this exact device original",
            "device_admission_approval_unresolved": "Original head approval is unresolved",
            "device_admission_approval_retained": "Exact original approval DATA is retained",
            "device_admission_confirmation": "Ask the same original native Master to admit this unchanged",
            "device_admission_lookup": "Read / look up only this original",
            "device_admission_head_intent": "Original head-consent intent retained",
            "device_admission_signing_intent": "Original signing unresolved",
            "device_admission_approval_phase": "Original head approval DATA retained",
            "device_admission_original_unavailable": "The exact native original cannot currently be resolved",
            "device_admission_outcome_unresolved": "The original admission outcome is unresolved",
            "device_admission_recorded": "Original admission recorded.",
            "runtime_publication_held": "no usable local client is currently published",
            "installing_first_account": "Awaiting original approval, durable installation and acknowledgement",
            "account_ack_pending": "Finish the original acknowledgement — no SQL replay",
            "acknowledging_first_account": "Completing only the original company acknowledgement",
            "account_original_receipt": "Open and verify the same original installation",
            "first_client_enrollment": "Enroll this original client once",
            "opening_original_installation": "Opening only the original installation owner",
            "automatic_reopen_unavailable": "The saved startup or stop intent could not be verified",
            "original_installation_retained": "The installation is retained, but no employee is signed in",
            "device_choices": "Device choices",
            "live_local_entry": "Employee sign-in",
            "retaining_credentials": "Retaining independent installer/head credentials",
            "recovery_action": "Inspect the same original again",
            "platform_profile": "AndroidKeyStore",
            "branch_form": "Original branch name",
            "head_enrollment_required": "The original independent head enrollment is required",
            "original_action_required": "The exact retained original action is required",
            "capacity_exhausted": "Required durable capacity is unavailable",
            "close_unconfirmed": "Actual native close is unconfirmed",
            "full_installation_held": "Full original installation and operational admission remain held",
            "company_retained": "Local company retained",
            "original_target_retained": "Original native material retained",
            "busy": "Original work is still owned or busy",
            "invalid_input": "Check the exact input.",
            "authentication_refused": "Local human authentication was refused.",
            "unsupported_platform": "This native engineering profile is unsupported",
            "invalid_application": "Installed application or native custody could not be confirmed.",
            "user_locked": "Unlock the device's operating-system user first.",
            "issuance_withdrawn": "New issuance was disabled.",
            "owner_required": "This step requires the genuine original target owner",
            "authentication_throttled": "The protected local authentication delay remains in force",
            "uncertain": "Original state is uncertain",
            "unavailable": "The native owner is unavailable",
            "lan_transport_unavailable": "This LAN attachment is unavailable",
            "lan_transport_unknown": "LAN state or cleanup is unconfirmed",
            "lan_host_listening": "The LAN Host is confirmed listening",
            "lan_client_connected": "The LAN Client is confirmed connected to the selected original Master",
            "lan_employee_authentication_attached": "Employee authentication is attached",
            "key_preparation_required": "The original native key preparation must be continued",
        }
        if document_name is not None:
            for key in ("pairing_document_exported", "pairing_document_imported",
                        "pairing_document_cancelled", "pairing_document_unavailable"):
                fixed[key] = f"{document_name}: {fixed[key]}"
        resumed = adb(self.device, "shell", "dumpsys", "activity", "activities")
        crashes = adb(self.device, "logcat", "-d", "-b", "crash", "-v", "brief")
        exceptions = []
        current_app = False
        for line in crashes.splitlines():
            if "Process: " in line:
                current_app = re.search(r"Process: " + re.escape(PACKAGE) + r"(?:[:,]|$)", line) is not None
            if current_app:
                match = re.search(r"\b([a-zA-Z_$][\w.$]*(?:Error|Exception))(?::|$)", line)
                if match is not None:
                    exceptions.append(match[1])
                stack = re.search(r"\bat (com\.ukkera\.pos\.[\w.$]+\([\w.]+:\d+\))", line)
                if stack is not None and len(exceptions) < 12:
                    exceptions.append(stack[1])
        return {
            "known_labels": [status for status, label in fixed.items() if any(label in value for value in observed)],
            "pos_accessibility_nodes": sum(node["properties"].get("package") == PACKAGE for node in nodes),
            "app_is_resumed": any(PACKAGE + "/" in line for line in resumed.splitlines() if "ResumedActivity" in line),
            "known_app_crash_frames": exceptions[:12],
        }

    def enter(self, label, value, scroll=0):
        # Use the actual focused Android text field. Training inputs use only safe ASCII.
        assert value and all(c.isascii() and (c.isalnum() or c in "- ") for c in value)
        self.choose(label, scroll=scroll)
        secret_field = "password" in label.lower()
        if secret_field:
            self.require_focused_password(label)
        adb(self.device, "shell", "input", "text", value.replace(" ", "%s"))
        if secret_field:
            self.require_focused_password(label)
        adb(self.device, "shell", "input", "keyevent", "KEYCODE_BACK")

    def require_focused_password(self, label):
        # The driver redacts secure fields. Verify focus and field type without
        # reading, saving, or reporting the typed synthetic credential.
        nodes = self.action("tree.snapshot")["nodes"]
        focused = [node for node in nodes if node.get("visible") and node["properties"].get("package") == PACKAGE
                   and node["properties"].get("focused") and node["properties"].get("password")]
        assert len(focused) == 1, f"Android secure edit did not have one focused password control: {label}"

    def review_identifier(self, label):
        # Branch and Branch name are different controls. Scan the actual review
        # once per viewport and match the exact field label, not a substring.
        for _ in range(7):
            nodes = self.action("tree.snapshot")["nodes"]
            offsets = [n for n, node in enumerate(nodes) if label in (node.get("name"), node.get("text"))]
            assert len(offsets) <= 1, f"Duplicated exact review field: {label}"
            if offsets and offsets[0] + 1 < len(nodes):
                value_node = nodes[offsets[0] + 1]
                values = {value for value in (value_node.get("name"), value_node.get("text"))
                          if isinstance(value, str) and UUID.fullmatch(value)}
                if len(values) == 1:
                    return values.pop()
            self.action("pointer.scroll", x=self.width // 2, y=self.height // 2, dy=14)
        raise AssertionError(f"Missing canonical identifier after the exact review field: {label}")

    def screenshot(self, name):
        png = self.session.fetch("/v1/screenshot")
        assert png.startswith(b"\x89PNG\r\n\x1a\n")
        # Android may redact secure activity captures. Preserve the exact image
        # and use the accessibility assertion as the test oracle.
        (REPORT / f"{name}.png").write_bytes(png)
        return hashlib.sha256(png).hexdigest()

    def process_after_submission(self, original_pid):
        # Tie the observation to the app process that handled the submitted
        # action. Publish fixed observations only, never dump records, PIDs,
        # user data, timestamps, log messages or Android trace paths.
        result = subprocess.run(["adb", "-s", self.device, "shell", "pidof", PACKAGE],
                                capture_output=True, text=True, timeout=10)
        running = result.stdout.strip()
        if result.returncode == 0 and running == original_pid:
            return {"process": "original_alive"}
        if result.returncode == 0 and running.isdecimal():
            state = "different_process"
        elif result.returncode == 1 and not running:
            state = "original_not_running"
        else:
            return {"process": "observation_unavailable"}
        info = adb(self.device, "shell", "dumpsys", "activity", "exit-info", PACKAGE)
        for record in re.split(r"(?m)^\s*ApplicationExitInfo[^\n]*:\s*$", info):
            if not re.search(r"\bpid=" + re.escape(original_pid) + r"(?=\s|$)", record):
                continue
            match = re.search(r"\bprocess=" + re.escape(PACKAGE)
                              + r"\s+reason=(\d{1,2})\s+\([^\n]*?\)\s+subreason=(\d{1,3})"
                                r"\s+\([^\n]*?\)\s+status=(-?\d{1,3})(?=\s|$)", record)
            if match:
                reason, subreason, status = map(int, match.groups())
                return {"process": state, "android_exit_reason": reason,
                        "android_exit_subreason": subreason, "android_exit_status": status}
        return {"process": state, "original_exit_record_present": False}

    def pairing_picker_transitions(self):
        # Keep only the bounded, exact names emitted by the testing package.
        records = adb(self.device, "logcat", "-d", "-v", "brief", "-s", "UkkeraPairingPicker:I")
        known = frozenset((
            "BOUND", "HOST_RESUMED", "STOP_FOR_PICKER", "STOP_WITHOUT_CURRENT_PICKER", "RETIRE_REQUESTED",
            "HOST_RETIRED", "PICKER_LAUNCHED", "RESULT_WITHOUT_REQUEST", "RESULT_ALREADY_OBSERVED",
            "RESULT_DOCUMENT", "RESULT_CANCELLED", "RESULT_UNAVAILABLE", "RESULT_FOREIGN_HOST",
            "RESULT_WAITING_FOR_RESUME", "RESULT_INACTIVE", "RESULT_DELIVERED", "RESULT_ORIGINAL_UNAVAILABLE",
            "IMPORT_COMPLETE", "IMPORT_ORIGINAL_UNAVAILABLE", "IMPORT_UNAVAILABLE",
            "HOST_PAUSED", "IMPORT_IO_ENTERED", "IO_HOST_REPLACED_OR_RETIRED", "IO_HOST_PAUSED",
            "IO_ACTIVITY_FINISHED", "IO_HOST_LOCKED", "IO_HOST_STATE_CHANGED", "IO_ORIGINAL_NOT_CURRENT",
        ))
        transitions = []
        for line in records.splitlines():
            entry = re.fullmatch(r"I/UkkeraPairingPicker\s*\(\s*\d+\): ([A-Z_]+)", line)
            if entry and entry[1] in known:
                transitions.append(entry[1])
        return transitions[-48:]

    def native_owner_refusals(self):
        # Report only existing, fixed native reason enums and static POS method
        # locations. Journal data, identities, log messages and values stay private.
        records = adb(self.device, "logcat", "-d", "-v", "threadtime", "-s", "UkkeraNativeOwner:I")
        refusals = []
        last_entered = {}
        for line in records.splitlines():
            process = re.match(r"^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+\s+(\d+)\s+\d+\s+\w\s+UkkeraNativeOwner\s*:", line)
            if process:
                approval = device_approval_refusal(line[process.end():].strip())
                if approval is not None:
                    refusals.append(approval)
                    continue
            entry = re.search(r"Native action entered: (com\.ukkera\.pos\.[\w.$]{1,150})$", line)
            if process and entry:
                last_entered[process[1]] = entry[1]
                continue
            if "Original master native work refused:" in line:
                boundary = "master_worker"
            elif "Native action refused:" in line:
                boundary = "native_task"
            elif "Native process exclusion requested:" in line:
                boundary = "process_exclusion"
            else:
                continue
            reason = re.search(r"(?:; |: )(?:reason|nativeReason)=([A-Z_]{3,40})(?:;|$)", line)
            refusal = {
                "boundary": boundary,
                "reason": reason.group(1) if reason else None,
                "frames": re.findall(r"com\.ukkera\.pos\.[\w.$<>]+:\d+", line)[:6],
            }
            if boundary == "process_exclusion":
                timing = re.search(r"; phase=([a-z0-9-]{1,90}); elapsedMillis=(\d{1,15}|unavailable); deadlineMillis=(\d{1,15}|none);", line)
                if timing:
                    refusal.update(phase=timing[1], elapsed_millis=timing[2], deadline_millis=timing[3])
                if process and process[1] in last_entered:
                    refusal["last_native_action_class"] = last_entered[process[1]]
            refusals.append(refusal)
        return refusals


def device_approval_refusal(message):
    guard = re.fullmatch(
        r"Native device (approval delivery|admission) refusal: (CURRENT_ORIGIN|PREPARED_HEAD|APPROVAL_EXPIRY|"
        r"PREPARED_OBSERVATION|CURRENT_ISSUANCE|CURRENT_HEAD_CONSENT|RETAINED_ENDORSEMENT|"
        r"DELIVER_ORIGINAL|ADMISSION_OBSERVATION)/"
        r"(AUTHENTICATION_REFUSED|ORIGINAL_ACTION_REQUIRED|ISSUANCE_WITHDRAWN|INVALID_APPLICATION|"
        r"USER_LOCKED|CONFLICT|AUTHORITY_UNAVAILABLE|STATE_UNCERTAIN|KEY_STATE_UNCERTAIN|NATIVE_RETIRED|UNAVAILABLE)",
        message,
    )
    if guard is None:
        return None
    boundary = "device_admission" if guard[1] == "admission" else "device_approval_delivery"
    return {"boundary": boundary, "guard": guard[2], "reason": guard[3]}


def setup_entry(ui):
    ui.choose("Set up or inspect the original local branch")
    ui.find("Choose the setup composition", wait=10000)
    ui.choose("SYNTHETIC TRAINING 1 - NOT LIVE")
    ui.find("Synthetic training composition", wait=10000)


def master_onboarding(ui, observation, scope_queue):
    setup_entry(ui)
    ui.choose("Create a new local company", scroll=5)
    ui.find("Name the company and both people", scroll=-5)
    observation["master_form_verified"] = True
    for label, value in (
        ("Company name", "Hosted Training Company"),
        ("Installer name", "Training Installer"),
        ("Installer login", "traininginstaller"),
        ("Company head name", "Training Head"),
        ("Company head login", "traininghead"),
    ):
        ui.enter(label, value, scroll=4)
    ui.choose("OS-isolated AndroidKeyStore", scroll=6)
    ui.choose("Prepare and review the original company", scroll=5)
    ui.find("Confirm this original company", scroll=-6)
    observation["master_original_company_review_verified"] = True
    installer = secrets.token_hex(16)
    head = secrets.token_hex(16)
    ui.enter("New installer password", installer, scroll=6)
    ui.enter("Independent new head password", head, scroll=5)
    ui.choose("I reviewed this original root", scroll=5)
    adb(ui.device, "logcat", "-b", "crash", "-c")
    observation["master_pre_submit_state"] = ui.visible_setup_outcome()
    ui.choose("Retain this local company and head", scroll=5)
    observation["master_company_submit_sent"] = True
    try:
        try:
            ui.find("Local company retained", scroll=-9)
        except AssertionError:
            observation["master_first_wait_state"] = ui.visible_setup_outcome()
            ui.find("Local company retained", wait=25000)
        observation["master_native_company_retained"] = True
        observation["master_screenshot_sha256"] = ui.screenshot("android-master-company-retained")
        ui.enter("Original branch name", "Hosted Training Branch", scroll=12)
        ui.choose("Prepare the original branch allocation", scroll=5)
        ui.find("Original branch allocation", scroll=-10)
        observation["master_branch_review_verified"] = True
        if ACTIVATE:
            scope = tuple(ui.review_identifier(label) for label in ("Organization", "Company", "Branch"))
            assert len(set(scope)) == 3
            scope_queue.put(scope)
        ui.enter("Fresh installer password", installer, scroll=9)
        observation["master_branch_secure_input_focus_verified"] = True
        ui.choose("Prepare only these original branch/device IDs", scroll=5)
        ui.choose("Retain original preparation", scroll=5)
        observation["master_branch_submit_sent"] = True
        ui.choose("Prepare only the original target", scroll=8)
        ui.find("Original native material retained", scroll=-10)
        observation["master_original_target_retained"] = True
        if ACTIVATE:
            activate_master(ui, observation, head)
        return head
    except (AssertionError, ClientError):
        observation["master_final_known_status"] = ui.visible_setup_outcome()
        raise


def activate_master(ui, observation, head_password):
    ui.choose("Define the first employee account", scroll=9)
    ui.find("Define the first LOCAL employee", scroll=-12)
    ui.enter("Exact employee login", "trainingemployee", scroll=9)
    ui.choose("Local administration: employees and device admission", scroll=11)
    ui.choose("Add 3 grants to the review", scroll=8)
    ui.enter("Access lifetime in milliseconds", "300000", scroll=20)
    ui.enter("Work lifetime in milliseconds", "900000", scroll=10)
    ui.choose("Review exactly these selections", scroll=16)
    ui.choose("Review the COMPLETE account definition", scroll=5)
    ui.find("Review the COMPLETE original installation", scroll=-6)
    observation["master_first_account_definition_reviewed"] = True
    employee = secrets.token_hex(16)
    ui.enter("Existing company-head password", head_password, scroll=44)
    ui.enter("New first-employee password", employee, scroll=6)
    ui.enter("Confirm employee password", employee, scroll=6)
    ui.choose("I reviewed ALL grants", scroll=10)
    original_pid = adb(ui.device, "shell", "pidof", PACKAGE).strip()
    assert original_pid.isdecimal(), "The installed original Master process is required before submission"
    ui.choose("Approve and install the original first account", scroll=6)
    observation["master_first_account_submit_sent"] = True
    observe_first_account_installation(ui, observation, original_pid)
    observation["master_native_installation_retained"] = True
    ui.choose("Enroll this original client once")
    await_master_publication(ui, observation)
    # This action is visible only when the existing installation is operational
    # and the actual first device and employee journals have attached.
    try:
        ui.find("Pair a new target through this original Master", scroll=15, wait=1900)
    except AssertionError:
        current = ui.visible_setup_outcome()
        observation["master_first_journal_observation"] = current
        if "setup_locked" in current["known_labels"]:
            ui.choose("Review setup again", scroll=-14)
        ui.find("Pair a new target through this original Master", scroll=12, wait=2500)
    observation["master_original_client_journals_connected"] = True
    observation["master_device_pairing_available"] = True
    observation["master_activated_screenshot_sha256"] = ui.screenshot("android-master-activated")


def observe_first_account_installation(ui, observation, original_pid):
    began = time.monotonic()
    # This is the existing setup action budget plus only the UI observation
    # needed to see the current state. Never resubmit the first account.
    transitions = observation["master_first_account_transitions"] = []
    used = set()
    actions = {
        "account_ack_pending": "Finish the original acknowledgement — no SQL replay",
        "account_original_receipt": "Open and verify the same original installation",
        "recovery_action": "Inspect the same original again",
    }
    while time.monotonic() - began < 300:
        state = ui.visible_setup_outcome()
        labels = set(state["known_labels"])
        if not transitions or transitions[-1]["known_labels"] != state["known_labels"]:
            transitions.append({**state, "elapsed_seconds": round(time.monotonic() - began, 2)})
        if state["known_app_crash_frames"] or not state["app_is_resumed"]:
            try:
                observation["master_first_account_process"] = ui.process_after_submission(original_pid)
            except (subprocess.SubprocessError, OSError):
                observation["master_first_account_process"] = {"process": "observation_unavailable"}
            raise AssertionError("The installed Master stopped during first-account installation")
        if "first_client_enrollment" in labels:
            return
        if labels & {"installing_first_account", "acknowledging_first_account", "opening_original_installation"}:
            time.sleep(0.7)
            continue
        if "close_unconfirmed" in labels:
            raise AssertionError("Original first-account native close remains unconfirmed")
        selected = next((key for key in actions if key in labels), None)
        if selected is not None and selected not in used:
            found = ui.find(actions[selected])
            node = found["nodes"][0]
            if node.get("enabled", True):
                ui.action("element.click", snapshot_id=found["snapshot_id"], id=node["id"])
                used.add(selected)
                observation[f"master_first_account_{selected}_sent"] = True
        elif "uncertain" in labels and "recovery_action" in used:
            raise AssertionError("The installed Master reported uncertainty after original inspection")
        elif "authentication_refused" in labels:
            raise AssertionError("The installed Master refused first-account authorization")
        else:
            ui.action("pointer.scroll", x=ui.width // 2, y=ui.height // 2, dy=14)
        time.sleep(0.7)
    raise AssertionError("The installed Master did not reach first-client enrollment")


def await_master_publication(ui, observation):
    # Use the installed app's existing 300-second setup operation bound.
    # Observe one previously submitted enrollment. Never submit or repeat it.
    started = time.monotonic()
    deadline = started + 300
    transitions = []
    observation["master_journal_state_transitions"] = transitions
    while time.monotonic() < deadline:
        state = ui.visible_setup_outcome()
        labels = state["known_labels"]
        if not transitions or labels != transitions[-1]["known_labels"]:
            transitions.append({"known_labels": labels, "elapsed_seconds": round(time.monotonic() - started, 2)})
        if "master_operating_notice" in labels:
            observation["master_operating_notice_verified"] = True
            ui.choose("Understood")
            return
        if set(labels) & {"journal_unavailable", "company_unresolved", "close_unconfirmed", "setup_locked"}:
            raise AssertionError("Original journal enrollment reached an installed UI refusal")
        time.sleep(1)
    raise AssertionError("The installed Master did not publish its role within the existing setup operation bound")


def prepare_client_target(ui, observation, scope):
    for label, identifier in zip(("Organization UUID", "Company UUID", "Branch UUID"), scope, strict=True):
        ui.enter(label, identifier, scroll=9)
    ui.enter("Private local device name", "Hosted Android Training Client", scroll=9)
    ui.choose("Review these exact target inputs", scroll=9)
    try:
        # The review replaces the long input form. Its status is at the top,
        # so do not continue scrolling from the previous form's submit button.
        ui.find("The target request is reviewed DATA.", scroll=-15, wait=2500)
    except (AssertionError, ClientError):
        observation["client_target_review_outcome"] = ui.visible_setup_outcome()
        raise
    observation["client_target_input_review_verified"] = True
    ui.choose("I reviewed the exact branch, capabilities, AppId and private name", scroll=20)
    ui.choose("Retain intent and prepare this new target", scroll=10)
    ui.find("The original native target is retained", scroll=-18, wait=2500)
    observation["client_native_target_retained"] = True
    ui.choose("Acquire only this target's actual original SDK identity", scroll=18)
    ui.choose("Retain intent and acquire original SDK identity", scroll=9)
    ui.find("The exact original identity is available for document export", scroll=-18, wait=2500)
    observation["client_sdk_identity_retained"] = True
    observation["client_identity_screenshot_sha256"] = ui.screenshot("android-client-native-retained")


def client_onboarding(ui, observation):
    setup_entry(ui)
    ui.choose("Prepare this new device as a pairing target", scroll=7)
    ui.find("Pair a new physical device", wait=10000)
    ui.find("Review a distinct new target", scroll=7)
    observation["client_target_form_verified"] = True
    observation["client_screenshot_sha256"] = ui.screenshot("android-client-pairing-form")


def restart_original_master(ui, observation):
    """Separate an activation handoff failure from the same installed app's settled reopen."""
    old_pid = adb(ui.device, "shell", "pidof", PACKAGE).strip()
    assert old_pid.isdecimal(), "The activated Master app process is required"
    adb(ui.device, "shell", "am", "force-stop", PACKAGE)
    stopped = subprocess.run(["adb", "-s", ui.device, "shell", "pidof", PACKAGE],
                             capture_output=True, text=True, timeout=25)
    assert stopped.returncode == 1 and not stopped.stdout.strip(), "Master process did not stop"
    adb(ui.device, "shell", "am", "start", "-W", "-n",
        f"{PACKAGE}/com.ukkera.pos.android.MainActivity")
    new_pid = adb(ui.device, "shell", "pidof", PACKAGE).strip()
    assert new_pid.isdecimal() and new_pid != old_pid, "The app must start in a different process"
    observation["master_new_process_verified"] = True
    setup_entry(ui)
    # Choosing the same training composition starts the existing-only RUN
    # attachment automatically. The setup lock stays visible until actual work
    # completes. Unlock the settled presentation after the operating notice.
    started = time.monotonic()
    transitions = []
    observation["master_restart_publication"] = transitions
    while time.monotonic() - started < 300:
        state = ui.visible_setup_outcome()
        labels = state["known_labels"]
        if not transitions or labels != transitions[-1]["known_labels"]:
            transitions.append({"elapsed_seconds": round(time.monotonic() - started, 2), **state})
        if ("setup_locked" in labels and observation.get("master_restart_notice_acknowledged")
                and not observation.get("master_restart_presentation_unlocked")):
            control = ui.find("Review setup again", wait=2500)
            node = control["nodes"][0]
            if node.get("enabled", True):
                ui.action("element.click", snapshot_id=control["snapshot_id"], id=node["id"])
                observation["master_restart_presentation_unlocked"] = True
        if {"journal_unavailable", "close_unconfirmed", "automatic_reopen_unavailable"} & set(labels):
            raise AssertionError("The installed Master reported an existing-only restart refusal")
        if "master_operating_notice" in labels and not observation.get("master_restart_notice_acknowledged"):
            ui.choose("Understood")
            observation["master_restart_notice_acknowledged"] = True
            continue
        if (observation.get("master_restart_presentation_unlocked") and "setup_page" in labels
                and "setup_locked" not in labels
                and not {"opening_original_installation", "runtime_publication_held"}.intersection(labels)):
            # The settled setup page is scrollable. Looking at the first viewport
            # cannot establish whether its connected-only action is present.
            found = ui.find("Pair a new target through this original Master", scroll=15, wait=2500)
            assert found["nodes"][0].get("enabled", True), "The existing Master pairing action stayed disabled"
            observation["master_existing_journals_reattached_after_restart"] = True
            return
        time.sleep(1)
    raise AssertionError("The original Master did not automatically reattach its existing journals within the setup bound")


def observe_document_completion(ui, observation, key, document_name, expected):
    # Returning from Android's picker retains the exact owned DATA operation,
    # but locks the setup screen. Open its real review only once it is enabled.
    transitions = observation[key] = []
    unlock_attempts = []
    pairing_active = False
    started = time.monotonic()
    while time.monotonic() - started < 45:
        state = ui.visible_setup_outcome(document_name)
        labels = state["known_labels"]
        if not transitions or transitions[-1]["known_labels"] != labels:
            transitions.append({"elapsed_seconds":round(time.monotonic() - started, 2), **state})
        if expected in labels:
            if unlock_attempts and "setup_locked" not in labels:
                observation[f"{key}_presentation_unlocked"] = True
            return
        if {"pairing_document_cancelled", "pairing_document_unavailable", "close_unconfirmed"} & set(labels):
            raise AssertionError("The installed app reported a pairing document refusal")
        if "setup_locked" in labels and state["app_is_resumed"]:
            now = time.monotonic()
            # The installed review action can be a no-op while document
            # ownership is active. A later confirmed locked screen can
            # request presentation again. This never reimports the file.
            if len(unlock_attempts) < 2 and (not unlock_attempts or now - unlock_attempts[-1] >= 7):
                found = ui.find("Review setup again", wait=2500)
                node = found["nodes"][0]
                if node.get("enabled", True):
                    ui.action("element.click", snapshot_id=found["snapshot_id"], id=node["id"])
                    unlock_attempts.append(time.monotonic())
                    observation[f"{key}_presentation_review_requests"] = len(unlock_attempts)
        elif "setup_locked" not in labels and state["app_is_resumed"]:
            if unlock_attempts and "pairing_page" in labels:
                observation[f"{key}_presentation_unlocked"] = True
            pairing_active = pairing_active or "pairing_page" in labels
            if pairing_active:
                # The challenge review is much longer than the identity form.
                # Move most of a visible page per snapshot. When the explicit
                # footer appears, step back to the exact document status above it.
                footer = "pairing_document_footer" in labels
                # ui-test-actions accepts at most 30 wheel steps per command
                # and Android converts each step into 24 pixels of swipe.
                distance = max(1, min(30, ui.height // (2 * 24)))
                ui.action("pointer.scroll", x=ui.width // 2,
                          y=ui.height * (2 if footer else 7) // 10,
                          dy=-distance // 2 if footer else distance)
        time.sleep(0.4)
    raise AssertionError("The installed app did not confirm the original pairing document transfer")


def documents_file_control(snapshot, filename):
    nodes = {node["id"]: node for node in snapshot["nodes"] if node.get("visible")
             and node["properties"].get("package") in DOCUMENT_PICKERS}
    choices = {}
    for node in nodes.values():
        if filename not in (node.get("name"), node.get("text")):
            continue
        seen = set()
        ancestor = node
        while ancestor is not None and ancestor["id"] not in seen:
            seen.add(ancestor["id"])
            if ancestor.get("clickable", ancestor["properties"].get("clickable")) and ancestor.get("bounds"):
                if ancestor.get("enabled", True):
                    choices[ancestor["id"]] = ancestor
                break
            ancestor = nodes.get(ancestor.get("parent_id"))
    if len(choices) > 1:
        # A document row and a nested label may both be clickable. Treat them
        # as one choice only if every candidate belongs to the same row.
        def nested(node):
            parent = nodes.get(node.get("parent_id"))
            visited = set()
            while parent is not None and parent["id"] not in visited:
                if parent["id"] in choices:
                    return True
                visited.add(parent["id"])
                parent = nodes.get(parent.get("parent_id"))
            return False

        choices = {key: node for key, node in choices.items() if not nested(node)}
    assert len(choices) <= 1, "The system document picker has multiple separate exact files"
    return next(iter(choices.values()), None)


def choose_original_document(ui, filename, observation, purpose):
    def selected(attempts):
        for attempt in range(attempts):
            snapshot = ui.action("tree.snapshot")
            control = documents_file_control(snapshot, filename)
            if control is not None:
                ui.action("element.click", snapshot_id=snapshot["snapshot_id"], id=control["id"])
                observation[f"{purpose}_system_exact_import_row_selected"] = True
                return True
            if attempt + 1 < attempts:
                time.sleep(0.4)
        return False

    # The isolated delivered file is already in Downloads. Do not wait for
    # multiple identical snapshots if the chooser's current folder lacks it.
    if selected(1):
        return
    ui.choose("Show roots")
    ui.choose("Downloads")
    observation[f"{purpose}_system_downloads_opened"] = True
    assert selected(5), "The exact exported document is unavailable in the Android picker"


def transfer_document(source, destination, export_action, import_action, filename, limit, observation, purpose):
    path = "/sdcard/Download/" + filename
    assert source.device != destination.device and filename.startswith("ukkera-device-") and filename.endswith(".bin")
    assert adb(source.device, "shell", "test", "!", "-e", path) == ""
    assert adb(destination.device, "shell", "test", "!", "-e", path) == ""
    source.choose(export_action, scroll=18)
    source.await_document_picker(observation, f"{purpose}_export_picker", save=True)
    document_name = {"identity": "Identity I", "challenge": "Challenge C", "possession": "Response P",
                     "approved_master": "Original approval / root"}[purpose]
    try:
        observe_document_completion(source, observation, f"{purpose}_export_completion", document_name,
                                    "pairing_document_exported")
    except AssertionError:
        # Report presence/byte count only. File presence cannot replace the
        # installed application's exact completion status or native import.
        file_size = None
        try:
            present = subprocess.run(["adb", "-s", source.device, "shell", "wc", "-c", path],
                                     capture_output=True, text=True, timeout=10)
            count = present.stdout.split(maxsplit=1)
            if present.returncode == 0 and count and count[0].isdigit():
                file_size = int(count[0])
        except (OSError, subprocess.SubprocessError):
            pass
        observation[f"{purpose}_exported_file_size_on_failure"] = file_size
        raise
    captured = subprocess.check_output(["adb", "-s", source.device, "exec-out", "cat", path], timeout=12)
    assert 1 <= len(captured) <= limit, f"Original exported {purpose} document is absent or outside its bound"
    with tempfile.TemporaryDirectory(dir=WORK) as directory:
        original = Path(directory) / filename
        with original.open("xb") as output:
            output.write(captured)
        adb(destination.device, "push", str(original), path)
    imported = subprocess.check_output(["adb", "-s", destination.device, "exec-out", "cat", path], timeout=12)
    assert imported == captured, f"{purpose} bytes changed during delivery"
    observation[f"original_{purpose}_document_bytes_verified"] = len(captured)
    timing = observation[f"{purpose}_import_timing_seconds"] = {}
    began = time.monotonic()
    destination.choose(import_action, scroll=20)
    timing["app_import_action_returned"] = round(time.monotonic() - began, 3)
    destination.await_document_picker(observation, f"{purpose}_import_picker")
    timing["system_picker_confirmed"] = round(time.monotonic() - began, 3)
    choose_original_document(destination, filename, observation, purpose)
    timing["exact_document_action_returned"] = round(time.monotonic() - began, 3)
    observe_document_completion(destination, observation, f"{purpose}_import_completion", document_name,
                                "pairing_document_imported")
    timing["installed_app_import_confirmed"] = round(time.monotonic() - began, 3)


def verify_pairing_proof(master, client, observation):
    original_refusal_count = len(master.native_owner_refusals())
    observation["pairing_driver_seconds_at_entry"] = {
        "master": master.session_seconds_remaining(), "client": client.session_seconds_remaining(),
    }
    master.choose("Pair a new target through this original Master", scroll=12)
    observation["master_pairing_action_sent"] = True
    transitions = []
    observation["master_pairing_context"] = transitions
    began = time.monotonic()
    previous = None
    # This is the existing 30-second application watch plus time to observe
    # the result. It does not repeat the action or change the native deadline.
    while time.monotonic() - began <= 37:
        snapshot = master.visible_setup_outcome()
        labels = snapshot["known_labels"]
        if labels != previous:
            transitions.append({"observation_seconds": round(time.monotonic() - began, 2),
                                "driver_seconds_remaining": master.session_seconds_remaining(), **snapshot})
            previous = labels
        if "pairing_import_control" in labels or "pairing_import_stage" in labels:
            break
        if any(label in labels for label in (
            "runtime_publication_held", "unavailable", "uncertain", "original_action_required",
            "head_enrollment_required", "owner_required", "invalid_application", "busy",
            "close_unconfirmed", "full_installation_held", "user_locked", "issuance_withdrawn",
        )):
            observation["master_pairing_native_refusals"] = master.native_owner_refusals()[original_refusal_count:][-8:]
            raise AssertionError("The installed Master reported a pairing-context refusal")
        if not snapshot["app_is_resumed"] or snapshot["known_app_crash_frames"]:
            raise AssertionError("The installed Master stopped while reading the original pairing context")
        if "pairing_context_unavailable" in labels:
            # The nullable-context placeholder is not a specific error. Bring
            # the actual problem text back into view, then read the fixed status.
            if time.monotonic() - began < 10:
                master.action("pointer.scroll", x=master.width // 2, y=master.height // 2, dy=-14)
        elif "pairing_page" in labels and "pairing_context_pending" not in labels:
            # The import button is after setup notices in the scrollable view.
            master.action("pointer.scroll", x=master.width // 2, y=master.height // 2, dy=14)
        time.sleep(0.7)
    try:
        master.find("Import the target identity document (I)", scroll=18, wait=1500)
    except (AssertionError, ClientError) as original:
        observation["master_pairing_native_refusals"] = master.native_owner_refusals()[original_refusal_count:][-8:]
        observation["master_pairing_driver_seconds_after_search"] = master.session_seconds_remaining()
        observation["master_pairing_search_error_type"] = type(original).__name__
        try:
            observation["master_pairing_context_after_search"] = master.visible_setup_outcome()
        except (AssertionError, ClientError) as diagnostic:
            observation["master_pairing_followup_snapshot_error_type"] = type(diagnostic).__name__
        raise
    observation["master_original_pairing_context_verified"] = True
    transfer_document(client, master, "Re-read and export original identity (I)", "Import the target identity document (I)",
        "ukkera-device-sdk-identity-v2.bin", 16_384, observation, "identity")
    master.choose("I reviewed this complete native/SDK identity", scroll=20)
    master.choose("Retain original request and prepare challenge", scroll=10)
    master.find("Re-read and export original challenge (C)", scroll=18, wait=2000)
    transfer_document(master, client, "Re-read and export original challenge (C)", "Import / review the complete challenge (C)",
        "ukkera-device-pairing-challenge-v2.bin", 2_113_536, observation, "challenge")
    client.choose("I reviewed the complete original Master context", scroll=26)
    client.choose("Retain exact signing intent and respond once", scroll=10)
    client.find("Re-read and export original response (P)", scroll=22, wait=2000)
    transfer_document(client, master, "Re-read and export original response (P)", "Import / review the exact response (P)",
        "ukkera-device-pairing-possession-v2.bin", 2_048, observation, "possession")
    master.choose("I reviewed the exact response against this original challenge", scroll=26)
    master.choose("Verify this original target proof once", scroll=12)
    master.find("Open separate head / admission review", scroll=22, wait=2000)
    observation["master_verified_native_target_possession"] = True


def admission_consent_control(snapshot, label):
    nodes = {node["id"]: node for node in snapshot["nodes"] if node.get("visible")
             and node["properties"].get("package") == PACKAGE}
    matches = {}
    for node in nodes.values():
        if label not in (node.get("name"), node.get("text")):
            continue
        visited = set()
        while node is not None and node["id"] not in visited:
            visited.add(node["id"])
            properties = node["properties"]
            if properties.get("checkable") and properties.get("clickable"):
                if node.get("bounds") and node.get("enabled") and not properties.get("checked"):
                    matches[node["id"]] = node
                break
            node = nodes.get(node.get("parent_id"))
    assert len(matches) <= 1, "Multiple distinct device admission consent controls"
    return next(iter(matches.values()), None)


def admit_native_device(master, client, head, observation):
    assert observation["master_verified_native_target_possession"] is True
    # The possession proof has already been accepted by the original Master.
    # The company-head password belongs only to this synthetic native Master.
    master.choose("Open separate head / admission review", scroll=15)
    master.find("The complete original is ready for explicit named-head review", scroll=20, wait=2500)
    master.enter("Existing named company-head password", head, scroll=24)
    master.choose("I reviewed this exact original action/request, branch/root", scroll=14)
    began = time.monotonic()
    elapsed = observation["master_approval_admission_timing_seconds"] = {}

    def mark(name):
        elapsed[name] = round(time.monotonic() - began, 3)

    master.choose("Approve this exact original only", scroll=10)
    mark("approve_action_returned")
    # Compose renders the separate confirmation in the same original review
    # item only after the genuine head approval is retained. Stay in that
    # viewport: reading the status at the top and scrolling back can consume the
    # original short consent lifetime. This sends each user action only once.
    control = ("Ask the same original native Master to admit this unchanged SDK-bound target once. "
               "Approval is not a permit; preserve this original if the outcome becomes uncertain.")
    try:
        snapshot_id, consent_id = master.find_admission_consent(control, scroll=8)
    except AssertionError:
        # The approval form can shrink as the native action settles. If the
        # exact-original lookup is visible, read it once before searching back.
        # The previous guests displayed the real confirmation after this read.
        before = master.visible_setup_outcome()
        observation["master_approval_confirmation_initial_miss"] = before
        labels = set(before["known_labels"])
        if ("device_admission_lookup" not in labels or "authentication_refused" in labels
                or "device_admission_outcome_unresolved" in labels or "device_admission_recorded" in labels):
            raise
        master.choose("Read / look up only this original")
        mark("exact_original_approval_lookup_returned")
        try:
            snapshot_id, consent_id = master.find_admission_consent(control, scroll=-4)
        except AssertionError:
            observation["master_after_one_approval_inspection"] = master.visible_setup_outcome()
            raise
    mark("separate_approved_confirmation_visible")
    observation["master_original_head_approval_verified"] = True
    master.action("element.click", snapshot_id=snapshot_id, id=consent_id)
    mark("separate_confirmation_checked")
    master.choose("Admit this approved original device", scroll=4)
    mark("admit_action_returned")
    try:
        master.find("Original admission recorded.", scroll=-17, wait=2000)
    except AssertionError:
        # A submitted admission is never sent again. Preserve the failed
        # assertion and observe only the existing original recovery control.
        try:
            observation["master_after_admission_failure"] = master.visible_setup_outcome()
            master.choose("Read / look up only this original", scroll=20)
            observation["master_after_one_admission_inspection"] = master.visible_setup_outcome()
        except (AssertionError, ClientError, OSError, subprocess.SubprocessError) as diagnostic:
            observation["master_admission_inspection_error_type"] = type(diagnostic).__name__
        raise
    mark("exact_original_admission_observed")
    observation["master_original_client_admission_verified"] = True
    master.choose("Retain original history and return to setup", scroll=25)
    master.choose("Pair a new target through this original Master", scroll=20)
    transfer_document(master, client, "Re-read and export original approval and root",
        "Import / review original approval and root", "ukkera-device-pairing-approved-master-v1.bin",
        2_189_312, observation, "approved_master")
    client.choose("I reviewed this exact original Master, challenge and approval/root document", scroll=25)
    client.choose("Verify and retain this original Master", scroll=12)
    client.find("The native owner verified the selected original Master.", scroll=-25, wait=2500)
    observation["client_native_original_master_verified"] = True
    observation["actual_pos_pairing_verified"] = True


def observed_wifi_network(device):
    output = adb(device, "shell", "ip", "-4", "addr", "show", "wlan0")
    (address,) = re.findall(r"\binet ([\d.]+/\d+)", output)
    network = ipaddress.IPv4Interface(address)
    assert network.ip.is_private and network.ip != network.network.network_address
    return network


def choose_exact_wifi(ui, expected):
    assert observed_wifi_network(ui.device) == expected, "The selected guest Wi-Fi interface changed"
    ui.choose("Find local networks", scroll=20)
    ui.choose(f"wlan0 · {expected.ip} · {expected.network}", scroll=-4)


def attach_approved_lan(master, client, observation):
    assert observation["actual_pos_pairing_verified"] is True
    master_network = observed_wifi_network(master.device)
    client_network = observed_wifi_network(client.device)
    assert master_network.ip != client_network.ip and master_network.network == client_network.network
    # The guest image has API 35. The tested application's Android permission
    # port only needs an interactive local network grant starting at API 37.
    assert all(adb(ui.device, "shell", "getprop", "ro.build.version.sdk").strip() == "35"
               for ui in (master, client))
    master.choose("Retain original history and return", scroll=24)
    master.find("Host on the existing operational Master", scroll=24)
    choose_exact_wifi(master, master_network)
    checked = master.find("✓ Allow Clients to discover this Master on the selected LAN", scroll=6)
    assert checked["nodes"][0].get("enabled", True), "The original Master advertisement is unavailable"
    master.choose("Review these exact LAN inputs", scroll=22)
    master.choose("Start LAN Host on this existing Master", scroll=10)
    master.find("The LAN Host is confirmed listening", scroll=-30, wait=2000)
    master.find("Observed assigned listener port", scroll=4)
    observation["master_ui_native_listener_confirmed"] = True

    client.find("Client to the selected original Master", scroll=-36)
    choose_exact_wifi(client, client_network)
    client.choose("Branch business operations", scroll=30)
    client.choose("Initialize this new device's credential journal", scroll=14)
    client.choose("Enroll an employee without an original allocation", scroll=14)
    client.choose("Review these exact LAN inputs", scroll=22)
    client.choose("Discover the approved Master", scroll=12)
    client.find("Connect to the approved original Master", scroll=22, wait=1800)
    observation["client_ui_discovered_approved_native_master"] = True
    client.choose("Connect to the approved original Master", scroll=4)
    client.find("The LAN Client is confirmed connected to the selected original Master", scroll=-32, wait=2200)
    observation["client_ui_lan_connected"] = True
    client.find("Employee authentication is attached", scroll=4)
    observation["client_ui_employee_authentication_attached"] = True
    observation["master_lan_status_png_sha256"] = master.screenshot("master-native-lan-status")
    observation["client_lan_status_png_sha256"] = client.screenshot("client-native-lan-status")


def main():
    preflight = json.loads((REPORT / "android-lan-preflight.json").read_bytes())
    assert preflight["distinct_installed_instances"] == 2 and preflight["shared_wifi"]
    directions = preflight["bidirectional_tcp"]
    assert len(directions) == len(GUESTS)
    assert {item["source"] for item in directions} == set(GUESTS.values())
    assert all(item["verified"] and item["stage"] == "exact_payload" for item in directions)
    observation = {
        "pos_source_revision": preflight["pos_source_revision"],
        "emulator_version": preflight["emulator_version"],
        "master_form_verified": False,
        "client_target_form_verified": False,
        "master_original_company_review_verified": False,
        "master_native_company_retained": False,
        "actual_pos_pairing_verified": False,
        "actual_pos_transaction_verified": False,
    }
    sessions = {role: InstalledUi(role, device) for role, device in GUESTS.items()}
    try:
        scope_queue = Queue(maxsize=1)
        if SETUP_STAGE == "pairing-after-restart" or ADMIT:
            # Both guests already passed independent install and WLAN checks.
            # Defer the second app and driver's CPU use until the native Master
            # installation and reopen have finished on this shared runner.
            master, client = sessions["master"], sessions["client"]
            master.start()
            head = master_onboarding(master, observation, scope_queue)
            restart_original_master(master, observation)
            client.start()
            assert master.session.session_id != client.session.session_id
            client_onboarding(client, observation)
            prepare_client_target(client, observation, scope_queue.get_nowait())
            verify_pairing_proof(master, client, observation)
            if ADMIT:
                admit_native_device(master, client, head, observation)
            if LAN:
                attach_approved_lan(master, client, observation)
            return
        for ui in sessions.values():
            ui.start()
        assert sessions["master"].session.session_id != sessions["client"].session.session_id
        with ThreadPoolExecutor(max_workers=2) as executor:
            master = executor.submit(master_onboarding, sessions["master"], observation, scope_queue)
            client = executor.submit(client_onboarding, sessions["client"], observation)
            client.result()
            if ACTIVATE:
                deadline = time.monotonic() + 175
                while True:
                    try:
                        scope = scope_queue.get(timeout=1)
                        break
                    except Empty:
                        if master.done():
                            master.result()
                            raise AssertionError("The original reviewed Master scope was not published")
                        if time.monotonic() >= deadline:
                            raise AssertionError("Master scope did not become available for the distinct target")
                prepare_client_target(sessions["client"], observation, scope)
            master.result()
            if PAIR:
                verify_pairing_proof(sessions["master"], sessions["client"], observation)
    except (AssertionError, ClientError) as failure:
        observation["failed_observation"] = str(failure)
        for role, ui in sessions.items():
            try:
                observation[f"{role}_final_native_refusals"] = ui.native_owner_refusals()[-12:]
            except (OSError, subprocess.SubprocessError):
                observation[f"{role}_final_native_refusals_unavailable"] = True
        raise
    finally:
        for role, ui in sessions.items():
            try:
                observation[f"{role}_pairing_picker_transitions"] = ui.pairing_picker_transitions()
            except (OSError, subprocess.SubprocessError):
                observation[f"{role}_pairing_picker_transitions_unavailable"] = True
            ui.close()
        (REPORT / "android-two-ui.json").write_text(json.dumps(observation, indent=2) + "\n")
        print(json.dumps(observation, sort_keys=True))


if __name__ == "__main__":
    main()
