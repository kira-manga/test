#!/usr/bin/env python3
"""Bind android-pos-packaging-contract-v1 to the public packaging command.

All Gradle, JDK, Android-tool and Git calls are synthetic mocks. The complete
command is executed with its application path redirected into a temporary
checkout; no real APK, signing material, build or Android tools are used.
Run with ``python3 apps/pos/tools/tests/test_android_packaging.py``. Optional JSON row
results can be written to ANDROID_PACKAGING_CASE_RECORD.
"""

from contextlib import contextmanager, redirect_stderr, redirect_stdout
from dataclasses import dataclass
import hashlib
import io
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import traceback
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[4]
SCRIPT = ROOT / "apps/pos/tools/package_local_android.py"
CASES = ROOT / "qa/pos/designs/android-packaging-contract-v1/cases.md"
CONTRACT_VERSION = "android-pos-packaging-contract-v1"
CASES_SHA256 = "621eb1354c6e5e33c132fd90c1da4c2f0632c8479bfcaae97db3d4da040d8bb5"
PACKAGE = "com.ukkera.pos.local.testing"
TASK = ":apps:android:assembleLocalTesting"
KEY_BYTES = b"SYNTHETIC-PRIVATE-KEY: retained-packaging-fixture"
PASSWORD = "a1" * 32
PASSWORD_ENV = "UKKERA_POS_LOCAL_KEYSTORE_PASSWORD"
SOURCE_MARKER = b"SYNTHETIC-SOURCE-MARKER: ordinary-checkout"
ROWS = []
SOURCE = SCRIPT.read_text()
SOURCE_SHA256 = hashlib.sha256(SOURCE.encode()).hexdigest()


def sha256(content):
    return hashlib.sha256(content).hexdigest()


@dataclass
class Invocation:
    exit_code: int
    stdout: str
    stderr: str
    calls: list
    report: object
    current_report: bool
    final_bytes: object
    public_files: dict
    unknown_calls: list

    def of_kind(self, kind):
        return [call for call in self.calls if call["kind"] == kind]

    def summary(self, fixture):
        def safe(text):
            return str(text).replace(str(fixture.root), "$FIXTURE")

        return {
            "exit": self.exit_code,
            "toolCalls": [
                {"kind": call["kind"], "arguments": [safe(arg) for arg in call["argv"]],
                 "exit": call["exit"],
                 **{field: call[field] for field in ("stdoutCaptured", "stderrCaptured") if field in call}}
                for call in self.calls
            ],
            "stdout": safe(self.stdout),
            "stderr": safe(self.stderr),
            "currentReport": self.current_report,
            "reportStatus": self.report.get("status") if isinstance(self.report, dict) else None,
            "finalApkExists": self.final_bytes is not None,
            "finalApkSha256": sha256(self.final_bytes) if self.final_bytes is not None else None,
            "credentialGenerationCalls": len(self.of_kind("keytool")),
            "unknownCalls": [safe(call) for call in self.unknown_calls],
        }


class PackagingFixture:
    """An isolated checkout layout and a strict external-command boundary."""

    def __init__(self, credentials=True):
        self.temporary = tempfile.TemporaryDirectory(prefix="android-packaging-contract-")
        self.root = Path(self.temporary.name)
        self.app = self.root / "app"
        self.script_path = self.app / "tools/package_local_android.py"
        self.build = self.app / "apps/android/build/outputs/apk/localTesting"
        self.output = self.app / "apps/android/build/outputs/local-testing-signed"
        self.signing = self.app / ".workspace/local-testing-signing"
        self.key = self.signing / "signing.p12"
        self.password = self.signing / "password"
        self.final = self.output / "ukkera-pos-testing.apk"
        self.report = self.output / "artifact.json"
        self.sdk = self.root / "sdk"
        self.tools = self.sdk / "build-tools/35.0.0"
        self.jdk = self.root / "jdk"
        self.build.mkdir(parents=True)
        self.tools.mkdir(parents=True)
        (self.jdk / "bin").mkdir(parents=True)
        (self.app / "gradlew").write_text("synthetic Gradle entry point\n")
        for executable in ("zipalign", "apksigner", "aapt"):
            (self.tools / executable).write_text("synthetic external tool\n")
        (self.jdk / "bin/keytool").write_text("synthetic credential generator\n")
        self.source_marker = self.app / "src/source-marker.txt"
        self.source_marker.parent.mkdir()
        self.source_marker.write_bytes(SOURCE_MARKER)
        self.metadata = {
            "applicationId": PACKAGE,
            "variantName": "localTesting",
            "elements": [{"filters": [], "outputFile": "pos-local-testing.apk"}],
        }
        self.metadata_text = None
        self.unsigned_bytes = b"UNALIGNED: synthetic first invocation"
        self.selected_state = "file"
        self.gradle_exit = 0
        self.align_exit = 0
        self.sign_exit = 0
        self.verify_exit = 0
        self.aapt_exit = 0
        self.badging = "package: name='" + PACKAGE + "' versionCode='1' versionName='fixture'\n"
        self.calls = []
        self.unknown_calls = []
        self.virtual_paths = {}
        if credentials:
            self.make_credentials()

    def __enter__(self):
        return self

    def __exit__(self, *unused):
        self.temporary.cleanup()

    def make_credentials(self, key=KEY_BYTES, password=PASSWORD.encode()):
        self.signing.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.signing.chmod(0o700)
        if key is not None:
            self.key.write_bytes(key)
            self.key.chmod(0o600)
        if password is not None:
            self.password.write_bytes(password)
            self.password.chmod(0o600)

    def credential_snapshot(self):
        if not self.signing.exists():
            return None
        return {
            "directoryIdentity": (self.signing.stat().st_dev, self.signing.stat().st_ino),
            "directoryMode": stat.S_IMODE(self.signing.stat().st_mode),
            "files": {
                str(path.relative_to(self.signing)): (path.read_bytes(), stat.S_IMODE(path.stat().st_mode),
                                                     path.stat().st_dev, path.stat().st_ino)
                for path in self.signing.rglob("*") if path.is_file()
            },
        }

    def write_build_output(self):
        text = self.metadata_text if self.metadata_text is not None else json.dumps(self.metadata)
        (self.build / "output-metadata.json").write_text(text)
        selected = self.build / "pos-local-testing.apk"
        if self.selected_state == "file":
            selected.write_bytes(self.unsigned_bytes)
        elif self.selected_state == "directory":
            selected.mkdir(exist_ok=True)

    def seed_old_result(self, aligned=False, signed=False, report=False):
        self.output.mkdir(parents=True, exist_ok=True)
        if aligned:
            (self.output / "aligned-unsigned.apk").write_bytes(b"STALE ALIGNED OUTPUT")
        if signed:
            self.final.write_bytes(b"STALE SIGNED OUTPUT")
        if report:
            self.report.write_text(json.dumps({"status": "PACKAGED_NOT_EXECUTED_NOT_OWNER_READY",
                                               "apk_sha256": "stale"}))

    def path(self, text):
        original = Path(text)
        return self.virtual_paths.get(str(original), original)

    def external(self, command, check=False, **kwargs):
        argv = [str(arg) for arg in command]
        name = Path(argv[0]).name
        call = {"kind": None, "argv": argv, "exit": 0}
        stdout = ""
        stderr = ""
        if argv[0] == str(self.app / "gradlew"):
            call["kind"] = "gradle"
            call["exit"] = self.gradle_exit
            call["cwd"] = kwargs.get("cwd")
            if not self.gradle_exit:
                self.write_build_output()
        elif name == "keytool":
            call["kind"] = "keytool"
            key = self.path(argv[argv.index("-keystore") + 1])
            key.write_bytes(KEY_BYTES)
            # Deliberately secret-bearing mock diagnostics verify captured output.
            stdout = "generated " + KEY_BYTES.decode() + " password=" + PASSWORD
            stderr = "synthetic generator diagnostics password=" + PASSWORD
            for stream_name in ("stdout", "stderr"):
                destination = kwargs.get(stream_name)
                captured = bool(kwargs.get("capture_output")) or destination == subprocess.PIPE
                call[stream_name + "Captured"] = captured
                if not captured:
                    content = stdout if stream_name == "stdout" else stderr
                    if destination != subprocess.DEVNULL:
                        stream = destination if hasattr(destination, "write") else getattr(sys, stream_name)
                        print(content, end="", file=stream)
                    if stream_name == "stdout":
                        stdout = None
                    else:
                        stderr = None
        elif name == "zipalign":
            call["kind"] = "align"
            call["exit"] = self.align_exit
            if not self.align_exit:
                original = self.path(argv[-2]).read_bytes()
                content = b"ALIGNED:" + original
                self.path(argv[-1]).write_bytes(content)
                call["outputBytes"] = content
        elif name == "apksigner" and argv[1] == "sign":
            call["kind"] = "sign"
            call["exit"] = self.sign_exit
            call["inputPath"] = argv[-1]
            call["inputBytes"] = self.path(argv[-1]).read_bytes()
            call["keyPath"] = argv[argv.index("--ks") + 1]
            call["keyBytes"] = self.path(call["keyPath"]).read_bytes()
            call["password"] = kwargs.get("env", {}).get(PASSWORD_ENV)
            if not self.sign_exit:
                final = self.path(argv[argv.index("--out") + 1])
                content = b"SIGNED:" + call["inputBytes"]
                final.write_bytes(content)
                call["outputPath"] = str(final)
                call["outputBytes"] = content
        elif name == "apksigner" and argv[1] == "verify":
            call["kind"] = "verify"
            call["exit"] = self.verify_exit
            stdout = "Verified synthetic signature\n"
        elif name == "aapt":
            call["kind"] = "aapt"
            call["exit"] = self.aapt_exit
            stdout = self.badging
        elif argv == ["git", "rev-parse", "HEAD"]:
            call["kind"] = "git-revision"
            stdout = "synthetic-revision\n"
        elif argv == ["git", "status", "--porcelain"]:
            call["kind"] = "git-status"
        else:
            self.unknown_calls.append(argv)
            raise AssertionError("Unmocked external command: " + repr(argv))
        self.calls.append(call)
        if check and call["exit"]:
            raise subprocess.CalledProcessError(call["exit"], argv, output=stdout, stderr=stderr)
        return subprocess.CompletedProcess(argv, call["exit"], stdout, stderr)

    def output_of_external(self, command, **kwargs):
        return self.external(command, check=True, **kwargs).stdout

    def invoke(self, *arguments):
        call_start = len(self.calls)
        unknown_start = len(self.unknown_calls)
        before = (self.report.read_bytes(), self.report.stat().st_mtime_ns) if self.report.exists() else None
        stdout, stderr = io.StringIO(), io.StringIO()
        environment = {"ANDROID_HOME": str(self.sdk), "JAVA_HOME": str(self.jdk)}
        original_is_file, original_read_bytes = Path.is_file, Path.read_bytes

        def is_file(path):
            return original_is_file(self.virtual_paths.get(str(path), path))

        def read_bytes(path):
            return original_read_bytes(self.virtual_paths.get(str(path), path))

        previous_umask = os.umask(0o077)
        os.umask(previous_umask)
        exit_code = 0
        try:
            with mock.patch.dict(os.environ, environment, clear=True), \
                    mock.patch.object(sys, "argv", [str(self.script_path), *arguments]), \
                    mock.patch.object(sys, "dont_write_bytecode", True), \
                    mock.patch("subprocess.run", side_effect=self.external), \
                    mock.patch("subprocess.check_output", side_effect=self.output_of_external), \
                    mock.patch("secrets.token_hex", return_value=PASSWORD), \
                    mock.patch.object(Path, "is_file", is_file), \
                    mock.patch.object(Path, "read_bytes", read_bytes), \
                    redirect_stdout(stdout), redirect_stderr(stderr):
                try:
                    exec(compile(SOURCE, str(SCRIPT), "exec"),
                         {"__name__": "__main__", "__file__": str(self.script_path)})
                except SystemExit as failure:
                    exit_code = failure.code if isinstance(failure.code, int) else int(bool(failure.code))
                except Exception:
                    # The Python interpreter also exits nonzero for an uncaught CLI exception.
                    exit_code = 1
                    traceback.print_exc()
        finally:
            os.umask(previous_umask)
        after = (self.report.read_bytes(), self.report.stat().st_mtime_ns) if self.report.exists() else None
        try:
            report = json.loads(after[0]) if after else None
        except (ValueError, UnicodeDecodeError):
            report = None
        public_files = {
            path.name: path.read_bytes() for path in self.output.glob("*")
            if path.is_file() and path.suffix in (".json", ".txt")
        }
        return Invocation(exit_code, stdout.getvalue(), stderr.getvalue(), self.calls[call_start:],
                          report, after is not None and after != before,
                          self.final.read_bytes() if self.final.is_file() else None,
                          public_files, self.unknown_calls[unknown_start:])


class AndroidPackagingContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if sha256(CASES.read_bytes()) != CASES_SHA256:
            raise AssertionError("The unchanged frozen android-pos-packaging-contract-v1 cases are required")

    @contextmanager
    def row(self, case_id, parameter, inputs):
        record = {"case": case_id, "parameter": parameter, "inputs": inputs,
                  "observations": [], "outcome": "RUNNING"}
        with self.subTest(case=case_id, parameter=parameter):
            try:
                yield record
            except Exception as failure:
                record["outcome"] = "FAIL" if isinstance(failure, AssertionError) else "ERROR"
                record["assertion"] = str(failure)
                raise
            else:
                record["outcome"] = "PASS"
            finally:
                ROWS.append(record)

    def observe(self, record, fixture, *arguments):
        invocation = fixture.invoke(*arguments)
        record["observations"].append(invocation.summary(fixture))
        self.assertEqual(invocation.unknown_calls, [], "The fixture must mock every external call")
        return invocation

    def assert_no_secret_disclosure(self, invocation):
        public = invocation.stdout + invocation.stderr + "\n".join(
            content.decode(errors="replace") for content in invocation.public_files.values())
        logged_commands = "\n".join(" ".join(call["argv"]) for call in invocation.calls)
        for secret in (PASSWORD, KEY_BYTES.decode()):
            self.assertNotIn(secret, public, "Secret material appeared in public output/report metadata")
            self.assertNotIn(secret, logged_commands, "Secret material appeared in user-visible command arguments")

    def assert_packaging_only(self, invocation):
        if isinstance(invocation.report, dict):
            self.assertFalse(invocation.report.get("owner_accepted", False))
            self.assertFalse(invocation.report.get("runtime_tested", False))
            self.assertFalse(invocation.report.get("launched", False))
            status = str(invocation.report.get("status", ""))
            positive = status.replace("NOT_EXECUTED", "").replace("NOT_OWNER_READY", "")
            self.assertNotRegex(positive.upper(), r"OWNER_(?:READY|ACCEPTED|APPROVED)|RUNTIME_(?:TESTED|PASS)|LAUNCHED")
        self.assertNotRegex(invocation.stdout.upper(),
                            r"(?m)^(?:OWNER[_ ](?:ACCEPTED|APPROVED|READY)|RUNTIME[_ ](?:TESTED|PASSED)|APP[_ ]LAUNCHED)\b")

    def assert_success(self, invocation, fixture):
        self.assertEqual(invocation.exit_code, 0, invocation.stderr)
        self.assertIsNotNone(invocation.final_bytes, "A successful package needs an existing final APK")
        for kind in ("align", "sign", "verify", "aapt"):
            self.assertEqual(len(invocation.of_kind(kind)), 1, "Required successful tool call: " + kind)
            self.assertEqual(invocation.of_kind(kind)[0]["exit"], 0)
        kinds = [call["kind"] for call in invocation.calls]
        self.assertLess(kinds.index("align"), kinds.index("sign"))
        self.assertLess(kinds.index("sign"), kinds.index("verify"))
        self.assertLess(kinds.index("sign"), kinds.index("aapt"))
        aligned = invocation.of_kind("align")[0]
        signed = invocation.of_kind("sign")[0]
        self.assertEqual(signed["inputPath"], aligned["argv"][-1])
        self.assertEqual(signed["inputBytes"], aligned["outputBytes"])
        self.assertNotEqual(signed["inputBytes"], fixture.unsigned_bytes)
        self.assertEqual(invocation.final_bytes, signed["outputBytes"])
        self.assertEqual(invocation.of_kind("verify")[0]["argv"][-1], str(fixture.final))
        self.assertEqual(invocation.of_kind("aapt")[0]["argv"][-1], str(fixture.final))
        if isinstance(invocation.report, dict) and "apk_sha256" in invocation.report:
            self.assertEqual(invocation.report["apk_sha256"], sha256(invocation.final_bytes))
        self.assert_packaging_only(invocation)
        self.assert_no_secret_disclosure(invocation)

    def assert_rejected(self, invocation):
        self.assertNotEqual(invocation.exit_code, 0, "The current invocation must reject this input")
        self.assertNotIn("LOCAL_TESTING_APK", invocation.stdout,
                         "The failed invocation must not announce a packaged result")
        self.assertNotIn("PACKAGED ONLY", invocation.stdout,
                         "The failed invocation must not announce packaging completion")
        if invocation.current_report and isinstance(invocation.report, dict):
            self.assertNotRegex(str(invocation.report.get("status", "")).upper(),
                                r"PACKAGED|SUCCESS|ACCEPTED|APPROVED|OWNER_READY")
        self.assert_packaging_only(invocation)

    def test_c01_ordinary_gradle_and_optional_flags(self):
        rows = [("neither", ()), ("offline", ("--offline",)),
                ("no-build-cache", ("--no-build-cache",)),
                ("both", ("--offline", "--no-build-cache"))]
        for parameter, flags in rows:
            with self.row("C01", parameter, {"flags": list(flags)}) as record, PackagingFixture() as fixture:
                invocation = self.observe(record, fixture, *flags)
                self.assert_success(invocation, fixture)
                builds = invocation.of_kind("gradle")
                self.assertEqual(len(builds), 1)
                self.assertEqual(builds[0]["argv"][0], str(fixture.app / "gradlew"))
                self.assertEqual(builds[0]["cwd"], fixture.app)
                self.assertEqual(builds[0]["argv"].count(TASK), 1)
                for flag in ("--offline", "--no-build-cache"):
                    self.assertEqual(builds[0]["argv"].count(flag), int(flag in flags))

    def test_c02_failed_build_rejects_stale_outputs(self):
        with self.row("C02", "gradle-exit-29", {"gradleExit": 29, "staleBuildOutputs": True}) as record, PackagingFixture() as fixture:
            fixture.write_build_output()
            fixture.seed_old_result(signed=True, report=True)
            before = fixture.credential_snapshot()
            fixture.gradle_exit = 29
            invocation = self.observe(record, fixture)
            self.assert_rejected(invocation)
            self.assertEqual(len(invocation.of_kind("gradle")), 1)
            self.assertEqual(invocation.of_kind("sign"), [])
            self.assertEqual(invocation.of_kind("keytool"), [])
            self.assertEqual(fixture.credential_snapshot(), before)

    def test_c03_metadata_identity_and_validity(self):
        rows = [("wrong-application-id", "applicationId", "com.ukkera.pos"),
                ("application-id-suffix", "applicationId", PACKAGE + ".extra"),
                ("missing-application-id", "applicationId", None),
                ("wrong-variant", "variantName", "release"),
                ("missing-variant", "variantName", None),
                ("malformed-json", None, None)]
        for parameter, field, value in rows:
            with self.row("C03", parameter, {"field": field, "value": value}) as record, PackagingFixture() as fixture:
                fixture.write_build_output()
                fixture.metadata["unrelatedDecoy"] = {"applicationId": PACKAGE, "variantName": "localTesting"}
                if field is None:
                    fixture.metadata_text = '{"applicationId":"' + PACKAGE + '","variantName":"localTesting",'
                elif value is None:
                    del fixture.metadata[field]
                else:
                    fixture.metadata[field] = value
                invocation = self.observe(record, fixture)
                self.assert_rejected(invocation)

    def test_c04_exactly_one_unfiltered_output(self):
        valid = {"filters": [], "outputFile": "pos-local-testing.apk"}
        rows = [("empty", []),
                ("two-valid", [valid, {"filters": [], "outputFile": "second.apk"}]),
                ("two-duplicates", [valid, dict(valid)]),
                ("abi-filter", [{"filters": [{"filterType": "ABI", "value": "arm64-v8a"}],
                                 "outputFile": "pos-local-testing.apk"}])]
        for parameter, elements in rows:
            with self.row("C04", parameter, {"elements": elements}) as record, PackagingFixture() as fixture:
                fixture.metadata["elements"] = elements
                (fixture.build / "second.apk").write_bytes(b"SECOND UNFILTERED APK")
                invocation = self.observe(record, fixture)
                self.assert_rejected(invocation)

    def test_c05_apk_path_is_a_basename(self):
        rows = [("parent", "../pos-local-testing.apk"),
                ("absolute", "/tmp/pos-local-testing.apk"),
                ("slash-nested", "sub/pos-local-testing.apk"),
                ("backslash-nested", "sub\\\\pos-local-testing.apk"),
                ("apks-suffix", "pos-local-testing.apks"),
                ("uppercase-suffix", "pos-local-testing.APK"),
                ("empty", ""), ("non-string", 17)]
        for parameter, filename in rows:
            with self.row("C05", parameter, {"outputFile": filename}) as record, PackagingFixture() as fixture:
                fixture.metadata["elements"][0]["outputFile"] = filename
                if filename == "/tmp/pos-local-testing.apk":
                    candidate = fixture.root / "virtual-tmp/pos-local-testing.apk"
                    fixture.virtual_paths[filename] = candidate
                elif isinstance(filename, str) and filename:
                    candidate = fixture.build / filename
                else:
                    candidate = None
                if candidate is not None:
                    candidate.parent.mkdir(parents=True, exist_ok=True)
                    candidate.write_bytes(b"REJECTED-PATH CANDIDATE EXISTS")
                    record["candidateFileExists"] = candidate.is_file()
                else:
                    record["candidateFileExists"] = None
                invocation = self.observe(record, fixture)
                self.assert_rejected(invocation)

    def test_c06_selected_apk_must_be_a_file(self):
        for state in ("absent", "directory"):
            with self.row("C06", state, {"selectedPathState": state}) as record, PackagingFixture() as fixture:
                fixture.selected_state = state
                invocation = self.observe(record, fixture)
                self.assert_rejected(invocation)

    def test_c07_no_implicit_credential_initialization(self):
        with self.row("C07", "absent-signing-directory", {"explicitInitialization": False}) as record, PackagingFixture(credentials=False) as fixture:
            invocation = self.observe(record, fixture)
            self.assert_rejected(invocation)
            self.assertEqual(invocation.of_kind("keytool"), [])
            self.assertEqual(invocation.of_kind("sign"), [])
            self.assertFalse(fixture.signing.exists(), "Packaging must not populate a missing signing directory")
            public = (invocation.stdout + invocation.stderr).lower()
            identifies_initialization = "--initialize-signing-key" in public or (
                ("initializ" in public or "initialis" in public) and
                any(word in public for word in ("explicit", "need", "required", "first", "run")))
            self.assertTrue(identifies_initialization, "The result must identify the need for explicit initialization")
            self.assert_no_secret_disclosure(invocation)

    def test_c08_explicit_first_initialization_is_private_and_durable(self):
        with self.row("C08", "explicit-first-initialization", {"explicitInitialization": True}) as record, PackagingFixture(credentials=False) as fixture:
            invocation = self.observe(record, fixture, "--initialize-signing-key")
            self.assertEqual(invocation.exit_code, 0, invocation.stderr)
            self.assertEqual(len(invocation.of_kind("keytool")), 1)
            self.assertGreater(len(fixture.key.read_bytes()), 0)
            self.assertGreater(len(fixture.password.read_bytes()), 0)
            for path in (fixture.signing, fixture.key, fixture.password):
                self.assertEqual(stat.S_IMODE(path.stat().st_mode) & 0o077, 0,
                                 "Credential storage must deny group/other access")
            self.assert_no_secret_disclosure(invocation)
            record["privateCompletePairRetained"] = True

    def test_c09_existing_credentials_are_never_regenerated(self):
        rows = [("empty-directory-init", None, None, True, True),
                ("key-only-init", KEY_BYTES, None, True, True),
                ("password-only-init", None, PASSWORD.encode(), True, True),
                ("empty-files-init", b"", b"", True, True),
                ("empty-key-init", b"", PASSWORD.encode(), True, True),
                ("empty-password-init", KEY_BYTES, b"", True, True),
                ("key-only-packaging", KEY_BYTES, None, False, True),
                ("password-only-packaging", None, PASSWORD.encode(), False, True),
                ("complete-pair-init", KEY_BYTES, PASSWORD.encode(), True, False)]
        for parameter, key, password, initialize, reject in rows:
            inputs = {"explicitInitialization": initialize, "keyPresent": key is not None,
                      "keyEmpty": key == b"", "passwordPresent": password is not None,
                      "passwordEmpty": password == b"", "exitConstrained": reject}
            with self.row("C09", parameter, inputs) as record, PackagingFixture(credentials=False) as fixture:
                fixture.make_credentials(key, password)
                marker = fixture.signing.parent / "preserve-marker.txt"
                marker.write_bytes(b"SYNTHETIC PRESERVE MARKER")
                marker.chmod(0o600)
                before = fixture.credential_snapshot()
                before_marker = (marker.read_bytes(), marker.stat().st_dev, marker.stat().st_ino)
                flags = ("--initialize-signing-key",) if initialize else ()
                invocation = self.observe(record, fixture, *flags)
                if reject:
                    self.assert_rejected(invocation)
                self.assertEqual(invocation.of_kind("keytool"), [])
                if not initialize:
                    self.assertEqual(invocation.of_kind("sign"), [])
                self.assertEqual(fixture.credential_snapshot(), before,
                                 "Existing directory, credential and marker bytes must remain unchanged")
                self.assertEqual((marker.read_bytes(), marker.stat().st_dev, marker.stat().st_ino), before_marker)
                if not reject:
                    self.assertNotRegex(invocation.stdout.lower(), r"(?:generated|created).*(?:credentials|signing key)")
                record["originalCredentialAndMarkerFilesPreserved"] = True

    def test_c10_repeated_packaging_reuses_credentials_and_outputs(self):
        with self.row("C10", "two-consecutive-invocations", {"invocations": 2, "ordinaryOutputReuse": True}) as record, PackagingFixture() as fixture:
            before_credentials = fixture.credential_snapshot()
            before_paths = {str(path.relative_to(fixture.app)) for path in fixture.app.rglob("*") if path.is_file()}
            first = self.observe(record, fixture)
            self.assert_success(first, fixture)
            fixture.unsigned_bytes = b"UNALIGNED: synthetic second invocation"
            second = self.observe(record, fixture)
            self.assert_success(second, fixture)
            self.assertNotEqual(first.final_bytes, second.final_bytes)
            self.assertEqual(second.final_bytes, b"SIGNED:ALIGNED:" + fixture.unsigned_bytes)
            for invocation in (first, second):
                self.assertEqual(invocation.of_kind("keytool"), [])
                signer = invocation.of_kind("sign")[0]
                self.assertEqual(signer["keyPath"], str(fixture.key))
                self.assertEqual(signer["keyBytes"], KEY_BYTES)
                self.assertEqual(signer["password"], PASSWORD)
                self.assertEqual(invocation.of_kind("gradle")[0]["argv"][0], str(fixture.app / "gradlew"))
                self.assert_no_secret_disclosure(invocation)
            self.assertEqual(fixture.credential_snapshot(), before_credentials)
            self.assertEqual(fixture.source_marker.read_bytes(), SOURCE_MARKER)
            for path in fixture.app.rglob("*"):
                if path.is_file() and str(path.relative_to(fixture.app)) not in before_paths:
                    self.assertNotEqual(path.read_bytes(), SOURCE_MARKER,
                                        "Packaging must not create a copied source snapshot")
            record["samePrivatePairAndOrdinaryOutputsReused"] = True

    def test_c11_alignment_failure_stops_signing(self):
        with self.row("C11", "zipalign-exit-31", {"zipalignExit": 31, "staleAlignedOutput": True}) as record, PackagingFixture() as fixture:
            fixture.seed_old_result(aligned=True, signed=True, report=True)
            fixture.align_exit = 31
            invocation = self.observe(record, fixture)
            self.assert_rejected(invocation)
            self.assertEqual(len(invocation.of_kind("align")), 1)
            self.assertEqual(invocation.of_kind("sign"), [])

    def test_c12_signing_failure_rejects_packaging(self):
        with self.row("C12", "signing-exit-32", {"signingExit": 32, "staleSignedOutput": True}) as record, PackagingFixture() as fixture:
            fixture.seed_old_result(signed=True, report=True)
            fixture.sign_exit = 32
            invocation = self.observe(record, fixture)
            self.assert_rejected(invocation)
            self.assertEqual(len(invocation.of_kind("sign")), 1)

    def test_c13_signature_verification_is_required(self):
        with self.row("C13", "verification-exit-33", {"verificationExit": 33, "aaptOtherwiseValid": True}) as record, PackagingFixture() as fixture:
            fixture.verify_exit = 33
            invocation = self.observe(record, fixture)
            self.assert_rejected(invocation)
            verification = invocation.of_kind("verify")
            self.assertEqual(len(verification), 1)
            self.assertEqual(verification[0]["argv"][-1], str(fixture.final))

    def test_c14_final_package_identity_and_debuggability(self):
        good = "package: name='" + PACKAGE + "' versionCode='1'\n"
        decoy = "application-label:'" + PACKAGE + "'\n"
        rows = [("wrong-package", 0, "package: name='com.ukkera.pos' versionCode='1'\n" + decoy),
                ("package-suffix", 0, "package: name='" + PACKAGE + ".extra' versionCode='1'\n" + decoy),
                ("debuggable", 0, good + "application-debuggable\n"),
                ("no-package-name", 0, decoy),
                ("aapt-exit-35", 35, good)]
        for parameter, exit_code, badging in rows:
            with self.row("C14", parameter, {"aaptExit": exit_code, "badging": badging}) as record, PackagingFixture() as fixture:
                fixture.aapt_exit = exit_code
                fixture.badging = badging
                invocation = self.observe(record, fixture)
                self.assert_rejected(invocation)
                self.assertEqual(len(invocation.of_kind("verify")), 1)
                self.assertEqual(len(invocation.of_kind("aapt")), 1)
                self.assertEqual(invocation.of_kind("aapt")[0]["argv"][-1], str(fixture.final))


if __name__ == "__main__":
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(AndroidPackagingContract)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    destination = os.environ.get("ANDROID_PACKAGING_CASE_RECORD")
    if destination:
        record_path = Path(destination)
        record_path.parent.mkdir(parents=True, exist_ok=True)
        record_path.write_text(json.dumps({
            "contractVersion": CONTRACT_VERSION,
            "casesSha256": CASES_SHA256,
            "implementationSha256": SOURCE_SHA256,
            "testMethodsExecuted": result.testsRun,
            "parameterRowsExecuted": len(ROWS),
            "commandInvocations": sum(len(row["observations"]) for row in ROWS),
            "passedRows": sum(row["outcome"] == "PASS" for row in ROWS),
            "failedRows": sum(row["outcome"] == "FAIL" for row in ROWS),
            "errorRows": sum(row["outcome"] == "ERROR" for row in ROWS),
            "rows": ROWS,
            "realAndroidChecks": [{"check": number, "outcome": "NOT_RUN"} for number in range(1, 8)],
            "runtimeInstallationOwnerAcceptance": "NOT_RUN",
            "centralImplementationAndVerification": "HELD_NOT_RUN",
        }, indent=2) + "\n")
    sys.exit(0 if result.wasSuccessful() else 1)
