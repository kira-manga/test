#!/usr/bin/env python3
"""Build/sign an isolated non-debuggable Android testing APK, entirely locally.

Uses the ordinary application/services and native safety checks, not a demo
composition or debug-key signature. No install, upload, central service, test
execution, real data or readiness claim. Private signing material stays ignored.
"""

import argparse
from datetime import datetime, timezone
import shutil
import hashlib
import json
import os
from pathlib import Path
import secrets
import stat
import subprocess
import sys

sys.dont_write_bytecode = True
APP = Path(__file__).resolve().parents[1]
SIGNING = APP / ".workspace/local-testing-signing"
ALIAS = "ukkera-pos-local-testing"
PASSWORD_ENV = "UKKERA_POS_LOCAL_KEYSTORE_PASSWORD"
PACKAGE = "com.ukkera.pos.local.testing"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_private(path, content):
    with path.open("xb") as stream:
        stream.write(content)
        stream.flush()
        os.fsync(stream.fileno())


def private_file(path):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ValueError("Local signing material must be owner-private regular files")


def signing_environment(initialize, keytool):
    key = SIGNING / "signing.p12"
    password = SIGNING / "password"
    if not initialize and not SIGNING.exists():
        raise ValueError("Testing signing credentials are missing; use --initialize-signing-key explicitly")
    if initialize:
        # Explicit first creation only. Missing/partial material is never regenerated.
        SIGNING.mkdir(mode=0o700, parents=True, exist_ok=False)
        write_private(password, secrets.token_hex(32).encode("ascii"))
        environment = dict(os.environ, **{PASSWORD_ENV: password.read_text()})
        result = subprocess.run([str(keytool), "-genkeypair", "-alias", ALIAS,
            "-storetype", "PKCS12", "-keystore", str(key), "-keyalg", "EC",
            "-groupname", "secp256r1", "-validity", "3650",
            "-dname", "CN=Ukkera POS local testing only", "-storepass:env", PASSWORD_ENV,
            "-keypass:env", PASSWORD_ENV], env=environment, capture_output=True, text=True)
        if result.returncode:
            raise RuntimeError("Local signing-key creation failed; retained partial material must not be replaced")
        key.chmod(0o600)
        with key.open("rb") as stream:
            os.fsync(stream.fileno())
        descriptor = os.open(SIGNING, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    directory = SIGNING.lstat()
    if not stat.S_ISDIR(directory.st_mode) or directory.st_uid != os.getuid() or directory.st_mode & 0o077:
        raise ValueError("Local signing directory is not owner-private")
    private_file(key)
    private_file(password)
    secret = password.read_text()
    if len(secret) != 64 or any(c not in "0123456789abcdef" for c in secret):
        raise ValueError("Invalid retained local signing password format")
    return key, dict(os.environ, **{PASSWORD_ENV: secret})


def compile_candidate(offline, no_build_cache):
    command = [str(APP / "gradlew"), ":apps:android:assembleLocalTesting", "--console=plain"]
    if offline:
        command.append("--offline")
    if no_build_cache:
        command.append("--no-build-cache")
    subprocess.run(command, cwd=APP, check=True)
    directory = APP / "apps/android/build/outputs/apk/localTesting"
    metadata = json.loads((directory / "output-metadata.json").read_text())
    if metadata.get("applicationId") != PACKAGE or metadata.get("variantName") != "localTesting":
        raise ValueError("Unexpected local-testing APK identity")
    elements = metadata.get("elements", [])
    if len(elements) != 1 or elements[0].get("filters") != []:
        raise ValueError("Local testing requires one unfiltered APK")
    filename = elements[0].get("outputFile", "")
    if not isinstance(filename, str) or not filename.endswith(".apk") or "\\" in filename or Path(filename).name != filename:
        raise ValueError("Unexpected local-testing APK output path")
    unsigned = directory / filename
    if not unsigned.is_file():
        raise ValueError("Gradle did not produce the local-testing APK")
    return unsigned


def android_tools():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise ValueError("Set ANDROID_HOME or ANDROID_SDK_ROOT for Android packaging")
    candidates = list((Path(sdk) / "build-tools").glob("*"))
    # Ignore previews; prefer the newest installed stable build-tools version.
    candidates = [p for p in candidates if all(part.isdigit() for part in p.name.split("."))]
    candidates.sort(key=lambda p: tuple(int(part) for part in p.name.split(".")), reverse=True)
    for directory in candidates:
        if all((directory / tool).is_file() for tool in ("zipalign", "apksigner", "aapt")):
            return directory
    raise ValueError("Install Android build-tools with zipalign, apksigner and aapt")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--initialize-signing-key", action="store_true",
        help="Explicitly create the first private local-testing signing key; refuses an existing directory")
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--no-build-cache", action="store_true")
    args = parser.parse_args()
    os.umask(0o077)
    build_tools = android_tools()
    java_home = os.environ.get("JAVA_HOME")
    keytool = Path(java_home) / "bin/keytool" if java_home else Path(shutil.which("keytool") or "")
    if not keytool.is_file():
        raise ValueError("Install JDK 21 and make keytool available")
    key, environment = signing_environment(args.initialize_signing_key, keytool)
    unsigned = compile_candidate(args.offline, args.no_build_cache)
    now = datetime.now(timezone.utc)
    output = APP / "apps/android/build/outputs/local-testing-signed"
    output.mkdir(parents=True, exist_ok=True)
    aligned = output / "aligned-unsigned.apk"
    apk = output / "ukkera-pos-testing.apk"
    (output / "artifact.json").unlink(missing_ok=True)
    subprocess.run([str(build_tools / "zipalign"), "-f", "-P", "16", "4", str(unsigned), str(aligned)], check=True)
    subprocess.run([str(build_tools / "apksigner"), "sign", "--ks", str(key), "--ks-type", "PKCS12",
        "--ks-key-alias", ALIAS, "--ks-pass", "env:" + PASSWORD_ENV,
        "--key-pass", "env:" + PASSWORD_ENV, "--out", str(apk), str(aligned)], env=environment, check=True)
    signature = subprocess.check_output([str(build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], text=True)
    badging = subprocess.check_output([str(build_tools / "aapt"), "dump", "badging", str(apk)], text=True)
    if "application-debuggable" in badging or not badging.startswith("package: name='" + PACKAGE + "'"):
        raise ValueError("Candidate is debuggable or has the wrong isolated package identity")
    (output / "signature.txt").write_text(signature)
    (output / "badging.txt").write_text(badging)
    aligned.unlink()
    metadata = {"status": "PACKAGED_NOT_EXECUTED_NOT_OWNER_READY", "synthetic_data_only": True,
        "package": PACKAGE, "debuggable": False, "ordinary_production_services_and_native_checks": True,
        "source_revision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=APP, text=True).strip(),
        "working_tree_dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=APP, text=True).strip()),
        "build_command": ":apps:android:assembleLocalTesting",
        "unsigned_sha256": digest(unsigned), "apk": apk.name, "apk_sha256": digest(apk),
        "apk_bytes": apk.stat().st_size, "signature_report_sha256": digest(output / "signature.txt"),
        "badging_report_sha256": digest(output / "badging.txt"), "packaged_utc": now.isoformat(),
        "central_implementation_and_verification": "HELD_NOT_RUN",
        "limits": "Declared minimum is Android7/API24; native security must meet the selected key-protection policy, and actual admission remains required. Packaging is not setup/checkout/native/device acceptance or proof of full API24 compatibility. No debug-key, test authority, fixture data or permissive verifier is injected."}
    (output / "artifact.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print("LOCAL_TESTING_APK " + str(apk), flush=True)
    print("PACKAGED ONLY — not yet executed or ready for hands-on checkout", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as failure:
        print("LOCAL_PACKAGING_FAILED: " + str(failure), file=sys.stderr)
        sys.exit(1)
