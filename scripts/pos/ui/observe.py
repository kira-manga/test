"""Drive a synthetic, fresh installed POS setup screen through ui-test-actions."""
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import time

from agent import Client, ClientError

target = os.environ["POS_UI_TARGET"]
temp = Path(os.environ["RUNNER_TEMP"])
work = temp / "pos-ui"
report = work / "public"


def snapshot(client, name):
    screenshot = client.fetch("/v1/screenshot")
    if not screenshot.startswith(b"\x89PNG\r\n\x1a\n"):
        raise AssertionError("UI session did not return a PNG")
    (report / f"{name}.png").write_bytes(screenshot)
    return hashlib.sha256(screenshot).hexdigest()


def action(client, name, args=None):
    reply = client.command(name, args or {})
    if not reply["ok"]:
        raise AssertionError(f"{name} failed")
    return reply["result"]


def wait_for_pos(client):
    for _ in range(15):
        windows = action(client, "windows.list")["windows"]
        if any("Ukkera POS" in window.get("title", "") for window in windows):
            return
        time.sleep(1)
    raise AssertionError("The installed application window never appeared")


def reject_observer_local_network_prompt(client):
    # The loopback driver does not need local-network discovery. Match the
    # observed Python permission prompt before pressing its denial button.
    from PIL import Image

    screen = Image.open(io.BytesIO(client.fetch("/v1/screenshot"))).convert("RGB")
    if screen.size != (1024, 768):
        return False
    title_and_body = screen.crop((396, 212, 628, 324))
    expected = "d6acd0771b4e502f652bae41a0165735b551c1d0d133e64bd079de8853c59243"
    if hashlib.sha256(title_and_body.tobytes()).hexdigest() != expected:
        return False
    action(client, "pointer.click", {"x": 453, "y": 339})
    time.sleep(1)
    return True


def identify_session():
    for _ in range(240):
        for file in temp.glob("ui-session-*/connection.json"):
            connection = json.loads(file.read_text())
            if connection.get("transport") != "local":
                continue
            client = Client(connection, work / "token")
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
    result["installed_startup"] = True
    result["before_png_sha256"] = snapshot(client, "before")
    if target == "macos" and reject_observer_local_network_prompt(client):
        result["observer_local_network_permission_denied"] = True
        result["entry_png_sha256"] = snapshot(client, "entry")
    finding = action(client, "element.wait", {
        "selector": {"text_contains": "Set up or inspect the original local branch"},
        "state": "present",
        "timeout_ms": 10000,
    })
    if finding["matched"] != 1:
        raise AssertionError("Expected one accessible original branch setup control")
    action(client, "element.click", {"snapshot_id": finding["snapshot_id"], "id": finding["nodes"][0]["id"]})
    labels = ("Set up local Ukkera POS", "Local setup connection", "Choose setup mode")
    tree = action(client, "tree.snapshot")
    for _ in range(10):
        if any(label in str(node.get(field, "")) for label in labels
               for node in tree["nodes"] for field in ("name", "text")):
            result["setup_view_opened"] = True
            break
        time.sleep(1)
        tree = action(client, "tree.snapshot")
    result["after_png_sha256"] = snapshot(client, "after")
    if not result["setup_view_opened"]:
        raise AssertionError("The expected setup screen was not observed")
    result["status"] = "setup_navigation_verified"
    rc = 0
except ClientError as error:
    result["status"] = "ui_driver_failed"
    result["reason"] = str(error)
except AssertionError as error:
    result["status"] = "assertion_failed"
    result["reason"] = str(error)
finally:
    (report / "observed.json").write_text(json.dumps(result, indent=2) + "\n")
    (work / "observer.exit").write_text(f"{rc}\n")
    (work / "token").unlink(missing_ok=True)
sys.exit(rc)
