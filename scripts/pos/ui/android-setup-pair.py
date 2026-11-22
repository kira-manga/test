"""Drive two actual training setup sessions bound to different Android guests."""

from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
import re
from pathlib import Path
import secrets
import struct
import subprocess
import sys
import threading
import time

from agent import Client, ClientError
from lease import HoldLease


PACKAGE = "com.ukkera.pos.local.testing"
GUESTS = {"master": "emulator-5554", "client": "emulator-5556"}
WORK = Path(os.environ["RUNNER_TEMP"]) / "pos-ui"
REPORT = WORK / "public"
LIBRARY = Path(os.environ["GITHUB_WORKSPACE"]) / "ui-client"
ACTIVATE = os.environ.get("POS_ANDROID_SETUP_STAGE") == "activate"
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
        self.env = {
            **os.environ,
            "RUNNER_TEMP": str(self.directory),
            "GITHUB_OUTPUT": str(self.directory / "output"),
            "GITHUB_STEP_SUMMARY": str(self.directory / "private-summary"),
            "UI_TEST_TARGET": "android",
            "UI_TEST_DEVICE": device,
            "UI_TEST_TOKEN_SHA256": hashlib.sha256(token.encode()).hexdigest(),
            "UI_TEST_STARTUP_TIMEOUT": "60",
            "UI_TEST_DURATION": "8",
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
        self.session = Client(connection, self.token_file)
        self.session.identify()
        png = self.session.fetch("/v1/screenshot")
        assert png.startswith(b"\x89PNG\r\n\x1a\n")
        self.width, self.height = struct.unpack_from(">II", png, 16)

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

    def visible_setup_outcome(self):
        # Keep only fixed statuses from the installed UI, never text field
        # values, credential inputs, branch identifiers or device identities.
        nodes = self.action("tree.snapshot")["nodes"]
        observed = tuple(str(node.get(field, "")) for node in nodes for field in ("name", "text"))
        fixed = {
            "setup_page": "Synthetic training composition",
            "entry_page": "Set up or inspect the original local branch",
            "setup_locked": "Review setup again",
            "company_review": "Confirm this original company",
            "company_unresolved": "Original state remains unresolved",
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
            "uncertain": "Original state is uncertain",
            "unavailable": "The native owner is unavailable",
            "key_preparation_required": "The original native key preparation must be continued",
        }
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
        adb(self.device, "shell", "input", "text", value.replace(" ", "%s"))
        adb(self.device, "shell", "input", "keyevent", "KEYCODE_BACK")

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


def setup_entry(ui):
    ui.choose("Set up or inspect the original local branch")
    ui.find("Choose the setup composition", wait=10000)
    ui.choose("SYNTHETIC TRAINING 1 - NOT LIVE")
    ui.find("Synthetic training composition", wait=10000)


def master_onboarding(ui, observation):
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
        scope = tuple(ui.review_identifier(label) for label in ("Organization", "Company", "Branch")) if ACTIVATE else None
        ui.enter("Fresh installer password", installer, scroll=9)
        ui.choose("Prepare only these original branch/device IDs", scroll=5)
        ui.choose("Retain original preparation", scroll=5)
        ui.choose("Prepare only the original target", scroll=8)
        ui.find("Original native material retained", scroll=-10)
        observation["master_original_target_retained"] = True
        if ACTIVATE:
            assert len(set(scope)) == 3
            activate_master(ui, observation, head)
            return scope
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
    ui.choose("Approve and install the original first account", scroll=6)
    ui.find("Enroll this original client once", scroll=20)
    observation["master_native_installation_retained"] = True
    ui.choose("Enroll this original client once")
    ui.find("The original device and first employee journals are connected", scroll=-12, wait=2500)
    observation["master_original_client_journals_connected"] = True
    ui.find("Pair a new target through this original Master", scroll=14)
    observation["master_device_pairing_available"] = True
    observation["master_activated_screenshot_sha256"] = ui.screenshot("android-master-activated")


def prepare_client_target(ui, observation, scope):
    for label, identifier in zip(("Organization UUID", "Company UUID", "Branch UUID"), scope, strict=True):
        ui.enter(label, identifier, scroll=9)
    ui.enter("Private local device name", "Hosted Android Training Client", scroll=9)
    ui.choose("Review these exact target inputs", scroll=9)
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


def main():
    preflight = json.loads((REPORT / "android-lan-preflight.json").read_bytes())
    assert preflight["distinct_installed_instances"] == 2 and preflight["shared_wifi"]
    assert all(entry["received"] == 3 for entry in preflight["bidirectional_icmp"])
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
        for ui in sessions.values():
            ui.start()
        assert sessions["master"].session.session_id != sessions["client"].session.session_id
        with ThreadPoolExecutor(max_workers=2) as executor:
            master = executor.submit(master_onboarding, sessions["master"], observation)
            client = executor.submit(client_onboarding, sessions["client"], observation)
            client.result()
            scope = master.result()
        if ACTIVATE:
            prepare_client_target(sessions["client"], observation, scope)
    except (AssertionError, ClientError) as failure:
        observation["failed_observation"] = str(failure)
        raise
    finally:
        for ui in sessions.values():
            ui.close()
        (REPORT / "android-two-ui.json").write_text(json.dumps(observation, indent=2) + "\n")
        print(json.dumps(observation, sort_keys=True))


if __name__ == "__main__":
    main()
