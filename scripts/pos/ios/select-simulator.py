#!/usr/bin/env python3
"""Create a disposable iPhone simulator for the POS launch smoke test."""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys

UDID = re.compile(r"^[0-9A-Fa-f-]{36}$")


def simctl_json(*arguments: str):
    return json.loads(subprocess.check_output(["xcrun", "simctl", *arguments], text=True))


def version_key(value: str):
    parts = []
    for piece in (value or "0").split("."):
        digits = re.match(r"\d+", piece)
        parts.append(int(digits.group(0)) if digits else 0)
    return tuple(parts)


def main() -> int:
    runtimes = [
        item
        for item in simctl_json("list", "runtimes", "--json").get("runtimes", [])
        if item.get("isAvailable") and item.get("platform") == "iOS"
    ]
    runtimes.sort(key=lambda item: version_key(item.get("version", "0")), reverse=True)
    if not runtimes:
        print("error: no available iOS simulator runtime is installed", file=sys.stderr)
        return 2

    device_types = simctl_json("list", "devicetypes", "--json").get("devicetypes", [])
    iphones = [item for item in device_types if item.get("name", "").startswith("iPhone")]
    if not iphones:
        print("error: no iPhone simulator device type is installed", file=sys.stderr)
        return 2
    iphones.sort(key=lambda item: version_key(item.get("identifier", "")), reverse=True)

    runtime = runtimes[0]
    device_type = iphones[0]
    name = f"UkkeraPOS CI {os.getpid()}"
    try:
        udid = subprocess.check_output(
            [
                "xcrun",
                "simctl",
                "create",
                name,
                device_type["identifier"],
                runtime["identifier"],
            ],
            text=True,
            stderr=subprocess.STDOUT,
        ).strip()
    except subprocess.CalledProcessError as failure:
        print(f"error: could not create simulator: {failure.output}", file=sys.stderr)
        return 2

    if not UDID.match(udid):
        print(f"error: simctl create returned an unexpected id: {udid!r}", file=sys.stderr)
        return 2

    print(
        json.dumps(
            {
                "udid": udid,
                "name": name,
                "runtime": runtime["identifier"],
                "runtime_version": runtime.get("version"),
                "device_type": device_type["identifier"],
                "device_name": device_type.get("name"),
                "created": True,
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
