"""Exercise the installed POS training quote and app lifecycle on iOS."""
import hashlib
import json
import os
from pathlib import Path
import sys
import time

from agent import ClientError
from ui_sdk import LiveUI

BUNDLE = "com.ukkera.pos.local"
TRAINING = "Open training workspace"
QUOTE = "Review, before you collect"
FAMILY = os.environ["POS_IOS_FAMILY"]
DEVICE = os.environ["POS_IOS_SIMULATOR"]
TEMP = Path(os.environ["RUNNER_TEMP"])
WORK = TEMP / "pos-ios-ui"
REPORT = Path("ipad-smoke-output")
observed = {"family": FAMILY, "status": "failed", "transitions": []}


def connect():
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        for path in TEMP.glob("ui-session-*/connection.json"):
            connection = json.loads(path.read_text())
            if connection.get("transport") == "local":
                client = LiveUI.open(path, WORK / "token")
                assert client.identify()["target"] == "ios"
                return client
        time.sleep(0.25)
    raise AssertionError("The local iOS UI session did not start")


def execute(client, command, **parameters):
    reply = client.action(command, parameters)
    assert reply["ok"], f"{command} failed"
    return reply["result"]


def expect(client, label, state="visible", timeout_ms=10000):
    observed["last_assertion"] = {"label": label, "state": state}
    return client.wait({"label_contains": label, "visible": True}, state=state, timeout_ms=timeout_ms)["result"]


def find_visible(client, label, viewport, state):
    for attempt in range(5):
        try:
            return expect(client, label, state=state, timeout_ms=1300)
        except ClientError as failure:
            if failure.code != "condition_timeout" or viewport is None or attempt == 4:
                raise
        width, height = viewport["width"], viewport["height"]
        execute(client, "pointer.swipe", x=width // 3, y=height * 72 // 100,
                end_x=width // 3, end_y=height * 35 // 100, settle="fast")


def choose(client, label, viewport=None):
    observed["expected_control"] = label
    for attempt in range(2):
        finding = find_visible(client, label, viewport, "actionable")
        eligible = [node for node in finding["nodes"] if node.get("enabled") and node.get("visible")]
        assert len(eligible) == 1, f"Expected one actionable {label} control"
        try:
            action = client.click(id=eligible[0]["id"], snapshot_id=finding["snapshot_id"],
                                  settle="fast", return_diff=True)["result"]
        except ClientError as failure:
            if failure.code != "stale_snapshot" or attempt != 0:
                raise
        else:
            observed["transitions"].append({"control": label, "mode": action["diff"]["mode"]})
            return


def capture(client, name):
    result = client.screenshot(bind_snapshot=True)
    assert result["snapshot_id"], "Screenshot has no associated snapshot"
    data = result["png"]
    (REPORT / f"{name}.png").write_bytes(data)
    observed[f"{name}_png_sha256"] = hashlib.sha256(data).hexdigest()


def exercise(client):
    info = client.device_info()["result"]
    assert info["device_id"] == DEVICE and info["form_factor_hardware"] == "simulator"
    assert info["window_size_class"]["layout_class"] == ("tablet" if FAMILY == "ipad" else "phone")
    observed["device"] = {key: info[key] for key in ("os_version", "window_size_class", "driver")}
    viewport = info["viewport"]

    ready = execute(client, "app.wait_ready", id=BUNDLE)
    initial_pid = int((REPORT / "launch-pid.txt").read_text())
    assert ready["ready"] and ready["pid"] == initial_pid
    observed["initial_pid"] = initial_pid
    initial = client.inspect(format="compact", visible_only=True, force_refresh=True)["result"]
    observed["accessibility_engine"] = initial["performance"]["engine"]
    assert observed["accessibility_engine"] == "axe_serve", "The persistent native AXe backend did not start"

    choose(client, TRAINING, viewport)
    expect(client, "Sample data. Nothing is charged")
    choose(client, "Add Fresh milk", viewport)
    choose(client, "Review training total")
    expect(client, QUOTE)
    expect(client, "Training calculation")
    expect(client, "42.50")
    find_visible(client, "Live checkout unavailable", viewport, "disabled")
    observed["quote_verified"] = True
    capture(client, "quote")

    background = execute(client, "app.background", id=BUNDLE)
    assert background["backgrounded"] and background["pid"] == initial_pid
    foreground = execute(client, "app.foreground", id=BUNDLE)
    assert foreground["ready"] and foreground["pid"] == initial_pid
    expect(client, "Local access is locked and private views are cleared")
    observed["background_lock_verified"] = True

    terminated = execute(client, "app.kill", id=BUNDLE, preserve_state=True)
    assert terminated["terminated"] and terminated["previous_pid"] == initial_pid
    reopened = execute(client, "app.launch", id=BUNDLE)
    assert reopened["ready"] and reopened["pid"] != initial_pid
    observed["reopened_pid"] = reopened["pid"]
    choose(client, TRAINING, viewport)
    expect(client, "A good day starts here")
    expect(client, "Review training total", state="disabled")
    observed["reopened_basket_empty_verified"] = True
    capture(client, "reopened")
    assert any(step["mode"] == "diff" for step in observed["transitions"]), "No structural diff was returned"
    observed["status"] = "training_quote_and_lifecycle_verified"


client = None
try:
    client = connect()
    exercise(client)
except ClientError as failure:
    observed.update(driver_error_code=failure.code, driver_http_status=failure.status)
except (AssertionError, KeyError, OSError, ValueError) as failure:
    observed["reason"] = str(failure)
finally:
    (REPORT / "installed-ui.json").write_text(json.dumps(observed, indent=2) + "\n")
    exit_code = 0 if observed["status"] == "training_quote_and_lifecycle_verified" else 1
    (WORK / "observer.exit").write_text(f"{exit_code}\n")
    if client is not None:
        try:
            client.close_session()
        except ClientError:
            pass
    (WORK / "token").unlink(missing_ok=True)
sys.exit(exit_code)
