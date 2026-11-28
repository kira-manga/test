"""Drive a synthetic, fresh installed POS setup screen through ui-test-actions."""
import hashlib
import io
import json
import os
import plistlib
import re
import stat
import subprocess
from pathlib import Path
import sys
import time

from agent import ClientError
from ui_sdk import LiveUI

target = os.environ["POS_UI_TARGET"]
temp = Path(os.environ["RUNNER_TEMP"])
work = temp / "pos-ui"
report = work / "public"


def snapshot(client, name):
    capture = client.screenshot(bind_snapshot=True)
    screenshot = capture["png"]
    assert capture.get("snapshot_id"), "Evidence screenshot was not bound to the UI snapshot"
    if target == "windows":
        from PIL import Image
        image = Image.open(io.BytesIO(screenshot)).convert("RGB")
        rect = windows_pos_window(client)["bounds"]
        # Capture the foreground application interior only. Do not publish
        # runner terminals, taskbar notifications or unrelated windows.
        left, top = max(0, rect["x"] + 8), max(0, rect["y"] + 8)
        right = min(image.width, rect["x"] + rect["width"] - 8)
        bottom = min(image.height - 48, rect["y"] + rect["height"] - 8)
        assert left < right and top < bottom, "Windows application has no visible area"
        cropped = io.BytesIO()
        image.crop((left, top, right, bottom)).save(cropped, format="PNG")
        screenshot = cropped.getvalue()
    (report / f"{name}.png").write_bytes(screenshot)
    return hashlib.sha256(screenshot).hexdigest()


def action(client, name, args=None):
    reply = client.action(name, args or {})
    if not reply["ok"]:
        raise AssertionError(f"{name} failed")
    return reply["result"]


def wait_for_pos(client):
    for _ in range(15):
        windows = action(client, "windows.list")["windows"]
        matches = [window for window in windows if window.get("title") == "Ukkera POS · Local qualification"]
        if len(matches) == 1:
            return matches[0]
        time.sleep(1)
    raise AssertionError("The installed application window never appeared")


def reject_observer_local_network_prompt(client):
    # The loopback driver does not need local-network discovery. Match the
    # observed Python permission prompt before pressing its denial button.
    from PIL import Image

    screen = Image.open(io.BytesIO(client.screenshot())).convert("RGB")
    if screen.size != (1024, 768):
        return False
    title_and_body = screen.crop((396, 212, 628, 324))
    expected = "d6acd0771b4e502f652bae41a0165735b551c1d0d133e64bd079de8853c59243"
    if hashlib.sha256(title_and_body.tobytes()).hexdigest() != expected:
        return False
    action(client, "pointer.click", {"x": 453, "y": 339, "settle": "fast"})
    return True


def windows_pos_window(client):
    windows = action(client, "windows.list")["windows"]
    matches = [window for window in windows if window.get("title") == "Ukkera POS · Local qualification"]
    assert len(matches) == 1, "Expected one exact installed Windows POS window"
    return matches[0]


def click_known_windows_role(client, console=False):
    # This signed Windows package may omit the Compose controls from its
    # accessibility tree. Match only the previously observed exact button.
    from PIL import Image

    image = Image.open(io.BytesIO(client.screenshot())).convert("RGB")
    if console:
        rect, pointer = (407, 450, 740, 482), (570, 466)
        expected = "8401e63b3e0ec5174b7125801ae81fabc28c8c0b81aab271d76868910ff865c8"
    else:
        rect, pointer = (407, 299, 731, 333), (570, 316)
        expected = "2eaa7200deec7537a70ff1a9260b64bef426db12fa5612df76de77f9f5f2c254"
    window = windows_pos_window(client)["bounds"]
    assert window["x"] <= rect[0] and window["y"] <= rect[1] and \
        window["x"] + window["width"] >= rect[2] and window["y"] + window["height"] >= rect[3]
    digest = hashlib.sha256(image.crop(rect).tobytes()).hexdigest()
    if image.size != (1024, 768) or digest != expected:
        raise AssertionError("Unsupported Windows layout for the exact observed role control")
    action(client, "pointer.click", {"x": pointer[0], "y": pointer[1], "settle": "fast"})


def observed_windows_service_console(capture):
    # The two regions are from the recorded, actually connected service
    # console. Both the administration control and the setup heading must
    # match. A cursor click or generic screen change cannot pass this check.
    from PIL import Image

    image = Image.open(capture).convert("RGB")
    expected = (
        ((30, 51, 260, 77), "b0049ae2f3d21003927eba1f405f70ddd8984259665501e732ed858b32595254"),
        ((24, 262, 334, 292), "87206010a3c754f58a3ddefb0803af77082cdf8a4d999ee2d1dd93d8d7be158c"),
    )
    return all(region[2] <= image.width and region[3] <= image.height and
               hashlib.sha256(image.crop(region).tobytes()).hexdigest() == digest
               for region, digest in expected)


def verify_macos_service(client, observed):
    label = "com.ukkera.pos.master"
    agent = Path.home() / "Library/LaunchAgents" / f"{label}.plist"
    assert not agent.exists(), "Expected a fresh macOS user service"
    action(client, "windows.focus", {"id": wait_for_pos(client)["id"]})
    choose(client, "Administer this computer's Master service", observed)
    observed["master_console_action_sent"] = True
    choose(client, "Install Master service", observed)
    observed["install_service_ui_action_sent"] = True

    for _ in range(16):
        # Read launchd state only. The installed application owns registration.
        info = subprocess.run(["/bin/launchctl", "print", f"gui/{os.getuid()}/{label}"],
                              capture_output=True, text=True, timeout=4)
        if info.returncode == 0 and re.search(r"^\s*state = running\s*$", info.stdout, re.MULTILINE):
            break
        time.sleep(0.6)
    else:
        raise AssertionError("The installed app did not register a running user Master service")

    app = Path((work / "installed").read_text().strip())
    expected = str(app / "Contents/MacOS/UkkeraPOS")
    assert agent.is_file() and not agent.is_symlink(), "The expected user LaunchAgent was not written"
    configuration = plistlib.loads(agent.read_bytes())
    assert configuration["Label"] == label and configuration["ProgramArguments"] == [expected, "--service"]
    observed["master_service_user_launch_agent_running"] = True
    socket = Path.home() / "Library/Application Support/com.ukkera.pos.local.desktop/master-console/setup.sock"
    for _ in range(16):
        try:
            state = socket.lstat()
        except FileNotFoundError:
            time.sleep(0.6)
            continue
        assert stat.S_ISSOCK(state.st_mode) and state.st_uid == os.getuid() and stat.S_IMODE(state.st_mode) == 0o600
        observed["master_console_owner_socket_ready"] = True
        break
    else:
        info = subprocess.run(["/bin/launchctl", "print", f"gui/{os.getuid()}/{label}"],
                              capture_output=True, text=True, timeout=4)
        status = re.search(r"^\s*last exit code = (\d+)\s*$", info.stdout, re.MULTILINE)
        observed["master_service_last_exit_code"] = int(status[1]) if status else None
        observed["after_png_sha256"] = snapshot(client, "after")
        raise AssertionError("The registered macOS Master did not expose its original user socket")

    connected = has_label(client, ("Master employee administration",))
    if not connected and has_label(client, ("Reconnect to setup",)):
        choose(client, "Reconnect to setup", observed)
        observed["master_console_reconnect_ui_action_sent"] = True
        connected = has_label(client, ("Master employee administration",))
    observed["master_console_accessible_verified"] = connected
    observed["after_png_sha256"] = snapshot(client, "after")
    assert observed["master_console_accessible_verified"], "The installed console did not show connected employee administration"
    observed["status"] = "master_service_console_navigation_verified"


def choose(client, label, observed):
    observed["last_expected_control"] = label
    for attempt in range(2):
        finding = client.wait({"label_contains": label, "visible": True}, timeout_ms=10_000)["result"]
        if finding["matched"] != 1:
            raise AssertionError("Expected one accessible POS setup control")
        try:
            transition = client.click(snapshot_id=finding["snapshot_id"], id=finding["nodes"][0]["id"],
                                      settle="fast", return_diff=True)["result"]
        except ClientError as error:
            if error.code != "stale_snapshot" or attempt != 0:
                raise
        else:
            change = transition.get("diff", {})
            observed.setdefault("ui_transition_modes", []).append(change.get("mode", "unavailable"))
            return


def control_missing(error):
    return (error.status, error.code) in {(404, "element_not_found"), (408, "condition_timeout")}


def macos_control_diagnostic(client, label):
    snapshot = client.inspect(limit=300, format="full", force_refresh=True)["result"]
    observed = {
        "driver_nodes": len(snapshot["nodes"]),
        "driver_truncated": bool(snapshot.get("truncated")),
        "driver_label_matches": sum(any(label in str(node.get(field, "")) for field in ("name", "text", "label"))
                                    for node in snapshot["nodes"]),
    }
    import ApplicationServices as ax

    window = wait_for_pos(client)
    application = ax.AXUIElementCreateApplication(window["process_id"])
    ax.AXUIElementSetMessagingTimeout(application, 0.3)

    def attribute(node, name):
        status, value = ax.AXUIElementCopyAttributeValue(node, name, None)
        return value if status == 0 else None

    pending = [attribute(application, "AXFocusedWindow") or application]
    visited = set()
    native_matches = 0
    deadline = time.monotonic() + 8
    while pending and len(visited) < 300 and time.monotonic() < deadline:
        node = pending.pop()
        if node in visited:
            continue
        visited.add(node)
        if attribute(node, "AXSubrole") != "AXSecureTextField":
            native_matches += any(label in str(attribute(node, key) or "") for key in ("AXTitle", "AXDescription", "AXValue"))
        for key in ("AXChildren", "AXVisibleChildren", "AXContents"):
            children = attribute(node, key)
            available = 300 - len(visited) - len(pending)
            if children is not None and available > 0:
                try:
                    pending.extend(children[:available])
                except TypeError:
                    continue
    observed.update(native_nodes=len(visited), native_label_matches=native_matches,
                    native_observation_incomplete=bool(pending))
    return observed


def has_label(client, labels):
    for _ in range(10):
        tree = client.inspect(format="compact", visible_only=True)["result"]
        if any(label in str(node.get(field, "")) for label in labels
               for node in tree["nodes"] for field in ("label", "value")):
            return True
        time.sleep(1)
    return False


def identify_session():
    for _ in range(240):
        for file in temp.glob("ui-session-*/connection.json"):
            connection = json.loads(file.read_text())
            if connection.get("transport") != "local":
                continue
            client = LiveUI.open(file, work / "token")
            info = client.identify()
            if info["target"] == target:
                return client
        time.sleep(1)
    raise AssertionError("No same-runner UI session became ready")


result = {
    "target": target,
    "tested_pos_commit": (work / "pos-revision").read_text().strip(),
    "installed_startup": False,
    "setup_view_opened": False,
    "master_client_tcp_tested": False,
}
rc = 1
try:
    client = identify_session()
    if target != "android":
        wait_for_pos(client)
    if target == "windows":
        action(client, "windows.focus", {"id": windows_pos_window(client)["id"]})
    result["installed_startup"] = True
    result["before_png_sha256"] = snapshot(client, "before")
    if target == "macos" and reject_observer_local_network_prompt(client):
        result["observer_local_network_permission_denied"] = True
        result["entry_png_sha256"] = snapshot(client, "entry")
    if target == "android":
        info = client.device_info()["result"]
        result["android_accessibility_engine"] = info["driver"]["a11y_engine"]
        result["android_layout_class"] = info["window_size_class"]["layout_class"]
        from PIL import Image
        before = Image.open(report / "before.png").convert("RGB")
        result["screen_capture_opaque"] = all(low == high == 0 for low, high in before.getextrema())
    if target == "macos" and os.environ.get("POS_MACOS_CONSOLE_UI") == "true":
        verify_macos_service(client, result)
        rc = 0
        sys.exit(0)
    console = target == "windows" and os.environ.get("POS_WINDOWS_CONSOLE_UI") == "true"
    if console:
        try:
            choose(client, "Administer this computer's Master service", result)
        except ClientError as unavailable:
            if not control_missing(unavailable):
                raise
            click_known_windows_role(client, console=True)
        result["master_console_action_sent"] = True
        result["master_console_accessible_verified"] = has_label(client, ("Master employee administration",))
        result["after_png_sha256"] = snapshot(client, "after")
        result["master_console_exact_visual_verified"] = observed_windows_service_console(report / "after.png")
        if not (result["master_console_accessible_verified"] or result["master_console_exact_visual_verified"]):
            raise AssertionError("The installed console did not reach the exact connected administration view")
        result["status"] = "master_service_console_navigation_verified"
        rc = 0
        sys.exit(0)
    try:
        choose(client, "Set up or inspect the original local branch", result)
    except ClientError as unavailable:
        if target != "windows" or not control_missing(unavailable):
            raise
        click_known_windows_role(client)
        result["visual_click_sent"] = True
        result["after_png_sha256"] = snapshot(client, "after")
        if result["before_png_sha256"] == result["after_png_sha256"]:
            raise AssertionError("The Windows setup click did not change the image")
        result["status"] = "visual_transition_requires_review"
        rc = 0
        sys.exit(0)

    if target == "android":
        result["composition_choice_opened"] = has_label(client, ("Choose the setup composition",))
        if not result["composition_choice_opened"]:
            raise AssertionError("Android did not show the exact setup composition chooser")
        choose(client, "SYNTHETIC TRAINING 1 - NOT LIVE", result)
        labels = ("Synthetic training composition",)
    else:
        labels = ("Set up local Ukkera POS", "Local setup connection", "Choose setup mode")
    result["setup_view_opened"] = has_label(client, labels)
    result["after_png_sha256"] = snapshot(client, "after")
    if not result["setup_view_opened"]:
        raise AssertionError("The expected setup screen was not observed")
    result["status"] = "setup_navigation_verified"
    rc = 0
except ClientError as error:
    result["status"] = "ui_driver_failed"
    result["reason"] = str(error)
    result["driver_error_code"] = error.code
    result["driver_http_status"] = error.status
    if target == "macos" and "last_expected_control" in result and control_missing(error):
        try:
            result["macos_accessibility"] = macos_control_diagnostic(client, result["last_expected_control"])
        except (ClientError, AssertionError, ImportError, OSError, RuntimeError, TypeError) as diagnostic:
            result["macos_accessibility_error_type"] = type(diagnostic).__name__
except AssertionError as error:
    result["status"] = "assertion_failed"
    result["reason"] = str(error)
finally:
    (report / "observed.json").write_text(json.dumps(result, indent=2) + "\n")
    (work / "observer.exit").write_text(f"{rc}\n")
    (work / "token").unlink(missing_ok=True)
sys.exit(rc)
