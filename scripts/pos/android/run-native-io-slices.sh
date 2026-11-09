#!/usr/bin/env bash
# Wire the three unchanged frozen Android API24 native-I/O slices to a disposable emulator.
# This does not enroll an installation, create an original, or exercise business authority.
set -euo pipefail
[[ $# -eq 4 ]] || { echo 'usage: run-native-io-slices.sh APP_APK TEST_APK OUTPUT_DIRECTORY APK_PAIR_MANIFEST' >&2; exit 2; }
APP_APK="$1"
TEST_APK="$2"
OUT="$3"
PAIR_MANIFEST="$4"
[[ ! -e "$OUT/instrumentation.txt" && ! -e "$OUT/provenance.json" ]] || { echo 'Preserve the existing result; use a new output directory.' >&2; exit 2; }
mkdir -p "$OUT"
PHASE=precheck
retain_outcome() {
  local status=$?
  if [[ "$status" -ne 0 ]]; then
    printf 'FAIL: phase=%s exit_status=%s\n' "$PHASE" "$status" > "$OUT/failure.txt"
  fi
}
trap retain_outcome EXIT
python3 - "$OUT/provenance.json" <<'PYPROVENANCE'
import json, os, pathlib, subprocess, sys

def revision():
    try:
        return subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True, stderr=subprocess.DEVNULL).strip()
    except (OSError, subprocess.CalledProcessError):
        return 'unavailable'

source_file = pathlib.Path('SOURCE-REVISION.txt')
record = {
    'harness_revision': revision(),
    'snapshot_source_revision': source_file.read_text().strip() if source_file.is_file() else 'unavailable',
    'expected_apk_source_revision': os.environ.get('POS_EXPECTED_APK_SOURCE', ''),
    'expected_android_sdk': os.environ.get('POS_EXPECTED_ANDROID_API', ''),
    'repository': os.environ.get('GITHUB_REPOSITORY', ''),
    'workflow': os.environ.get('GITHUB_WORKFLOW', ''),
    'run_id': os.environ.get('GITHUB_RUN_ID', ''),
    'run_attempt': os.environ.get('GITHUB_RUN_ATTEMPT', ''),
}
pathlib.Path(sys.argv[1]).write_text(json.dumps(record, indent=2) + '\n')
PYPROVENANCE
[[ -f "$PAIR_MANIFEST" ]] || { echo "APK pair manifest is required." >&2; exit 2; }
[[ "${POS_EXPECTED_ANDROID_API:-}" =~ ^[0-9]+$ ]] || { echo "Expected Android SDK is required." >&2; exit 2; }
[[ "${POS_EXPECTED_APK_SOURCE:-}" =~ ^[0-9a-f]{40}$ ]] || { echo "Expected APK source revision is required." >&2; exit 2; }
[[ -f "$APP_APK" && -f "$TEST_APK" ]] || { echo 'Both signed APKs are required.' >&2; exit 2; }
[[ "${POS_DISPOSABLE_ANDROID:-}" == 'true' ]] || { echo 'Require POS_DISPOSABLE_ANDROID=true for a disposable emulator.' >&2; exit 2; }
command -v adb >/dev/null
SERIAL="${ANDROID_SERIAL:-}"
if [[ -z "$SERIAL" ]]; then
  SERIAL="$(adb devices | awk 'NR>1 && $2=="device" { print $1 }')"
fi
[[ "$SERIAL" == emulator-* && "$SERIAL" != *$'\n'* ]] || { echo 'Select exactly one emulator with ANDROID_SERIAL.' >&2; exit 2; }
ADB=(adb -s "$SERIAL")
[[ "$("${ADB[@]}" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'Native slices require an emulator.' >&2; exit 2; }
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
APKSIGNER="${APKSIGNER:-}"
if [[ -z "$APKSIGNER" ]]; then
  [[ -d "$SDK/build-tools" ]] || { echo 'Android build-tools are required.' >&2; exit 2; }
  APKSIGNER="$(find "$SDK/build-tools" -mindepth 2 -maxdepth 2 -name apksigner -type f | sort -V | tail -1)"
fi
[[ -x "$APKSIGNER" ]]
PHASE=signature
"$APKSIGNER" verify --verbose --print-certs "$APP_APK" > "$OUT/app-signature.txt"
"$APKSIGNER" verify --verbose --print-certs "$TEST_APK" > "$OUT/test-signature.txt"
APP_CERT="$(python3 - "$OUT/app-signature.txt" "$OUT/test-signature.txt" <<'PYCERT'
import pathlib, re, sys
certs = []
for path in sys.argv[1:]:
    text = pathlib.Path(path).read_text()
    assert re.search(r'^Number of signers: 1$', text, re.M), 'Exactly one APK signer required'
    values = set(re.findall(r'^.*certificate SHA-256 digest: ([0-9a-fA-F]{64})$', text, re.M))
    assert len(values) == 1, 'APK scheme signers must agree'
    certs.append(values.pop().lower())
assert certs[0] == certs[1], 'Application and test signer differ'
print(certs[0])
PYCERT
)"
PHASE=input-manifest
python3 - "$PAIR_MANIFEST" "$APP_APK" "$TEST_APK" "$APP_CERT" <<'PYMANIFEST'
import hashlib, json, os, pathlib, sys
manifest_path, app_path, test_path, cert = sys.argv[1:]
m = json.loads(pathlib.Path(manifest_path).read_text())
assert m['source_revision'] == os.environ['POS_EXPECTED_APK_SOURCE'], 'Unexpected APK source'
assert m['signer_certificate_sha256'].lower() == cert.lower(), 'Unexpected signer'
for key, path in [('app', app_path), ('runtime_tests', test_path)]:
    data = pathlib.Path(path).read_bytes()
    assert m[key]['sha256'] == hashlib.sha256(data).hexdigest(), f'{key} hash mismatch'
    assert m[key]['bytes'] == len(data), f'{key} size mismatch'
print('APK pair matches expected source, signer, sizes and hashes.')
PYMANIFEST
sha256sum "$APP_APK" "$TEST_APK" > "$OUT/input-apk-sha256.txt"
"${ADB[@]}" shell getprop ro.build.version.sdk > "$OUT/api-level.txt"
[[ "$(tr -d '\r\n' < "$OUT/api-level.txt")" == "$POS_EXPECTED_ANDROID_API" ]] || { echo 'Emulator SDK differs from requested SDK.' >&2; exit 2; }
"${ADB[@]}" shell getprop ro.product.cpu.abi > "$OUT/abi.txt"
PHASE=install
"${ADB[@]}" install -r "$APP_APK"
"${ADB[@]}" install -r "$TEST_APK"
"${ADB[@]}" shell dumpsys package com.ukkera.pos.local.testing > "$OUT/app-package.txt"
"${ADB[@]}" shell dumpsys package com.ukkera.pos.local.testing.test > "$OUT/test-package.txt"
"${ADB[@]}" logcat -c
PHASE=instrumentation
set +e
"${ADB[@]}" shell am instrument -w -r \
  -e class com.ukkera.pos.android.independent.androidapi24v1.AndroidApi24V1NativeIoSlicesTest \
  -e syntheticAndroidApi24 true \
  com.ukkera.pos.local.testing.test/androidx.test.runner.AndroidJUnitRunner \
  | tee "$OUT/instrumentation.txt"
INSTRUMENTATION_STATUS=$?
set -e
"${ADB[@]}" logcat -d -b crash > "$OUT/crash-log.txt"
"${ADB[@]}" logcat -d -s AndroidApi24V1:I > "$OUT/native-slice-log.txt"
[[ "$INSTRUMENTATION_STATUS" -eq 0 ]] || { echo "Instrumentation exited $INSTRUMENTATION_STATUS." >&2; exit "$INSTRUMENTATION_STATUS"; }
# These are the unchanged N18, N19 and N20 methods. Refuse missing, failed or skipped execution.
grep -Fq 'OK (3 tests)' "$OUT/instrumentation.txt"
[[ "$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0' "$OUT/instrumentation.txt")" -eq 3 ]]
if grep -Eq '^INSTRUMENTATION_STATUS_CODE: -(1|2|3|4)|FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$OUT/instrumentation.txt"; then
  echo 'Native slice execution failed or skipped.' >&2
  exit 1
fi
printf '%s\n' 'PASS: three frozen native I/O slices; no original, key custody, business, financial, or owner acceptance claim.' > "$OUT/summary.txt"
cat "$OUT/summary.txt"
