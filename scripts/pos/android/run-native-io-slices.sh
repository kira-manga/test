#!/usr/bin/env bash
# Execute existing native I/O, key or selected original-ACK slices on a disposable emulator.
# Only the ACK group creates a fresh synthetic original through the genuine native owner.
# No group supplies fake authority or changes the frozen method assertions.
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
if [[ "${POS_NATIVE_SLICE_GROUP:-io}" == ack ]]; then
  existing="$("${ADB[@]}" shell pm path com.ukkera.pos.local.testing 2>/dev/null || true)"
  [[ -z "${existing//$'\r'/}" ]] || { echo 'Original ACK segment requires a fresh isolated installation.' >&2; exit 2; }
fi
"${ADB[@]}" install -r "$APP_APK"
"${ADB[@]}" install -r "$TEST_APK"
"${ADB[@]}" shell dumpsys package com.ukkera.pos.local.testing > "$OUT/app-package.txt"
"${ADB[@]}" shell dumpsys package com.ukkera.pos.local.testing.test > "$OUT/test-package.txt"
"${ADB[@]}" logcat -c
PHASE=instrumentation
case "${POS_NATIVE_SLICE_GROUP:-io}" in
  io) ;;
  keys)
    # Preserve the two existing frozen selectors and their original assertions.
    # The first explicitly prepares fresh synthetic native state; the second only
    # reopens it after an observed target-process stop. Never clear/reset that state.
    KEY_CLASS=com.ukkera.pos.android.independent.androidapi24v1.AndroidApi24V1NativeKeySlicesTest
    KEY_METHODS=(
      n02_n03_n07_originalNativeKeyNonexportabilityChallengeAndCloseReopenSlices
      n02_n07_existingOnlyFreshProcessStartupSlice_noIndependentPreRestartIdentityComparison
    )
    for index in 0 1; do
      PHASE="native-key-$index"
      if [[ "$index" -eq 1 ]]; then
        "${ADB[@]}" shell am force-stop com.ukkera.pos.local.testing
        remaining="$("${ADB[@]}" shell pidof com.ukkera.pos.local.testing 2>/dev/null || true)"
        [[ -z "${remaining//$'\r'/}" ]] || { echo 'Target process remains after force-stop.' >&2; exit 1; }
        printf '%s\n' 'Target force-stop completed; no retained target PID before existing-only invocation.' > "$OUT/fresh-process-boundary.txt"
      fi
      set +e
      "${ADB[@]}" shell am instrument -w -r -e class "$KEY_CLASS#${KEY_METHODS[$index]}" \
        -e syntheticAndroidApi24 true \
        com.ukkera.pos.local.testing.test/androidx.test.runner.AndroidJUnitRunner \
        | tee "$OUT/native-key-$index.txt"
      status=$?
      set -e
      "${ADB[@]}" logcat -d -b crash > "$OUT/crash-log-$index.txt"
      "${ADB[@]}" logcat -d -s AndroidApi24V1:I > "$OUT/native-key-log-$index.txt"
      [[ "$status" -eq 0 ]]
      grep -Fq 'OK (1 test)' "$OUT/native-key-$index.txt"
      [[ "$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0' "$OUT/native-key-$index.txt")" -eq 1 ]]
      if grep -Eq '^INSTRUMENTATION_STATUS_CODE: -(1|2|3|4)|FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$OUT/native-key-$index.txt"; then
        echo 'Native key selector failed or skipped.' >&2; exit 1
      fi
    done
    printf '%s\n' 'PASS: two unchanged original-key selectors, including existing-only use after observed process absence. OS_ISOLATED observations only; no hardware, independent pre/post identity comparison, Master, financial or owner acceptance.' > "$OUT/summary.txt"
    cat "$OUT/summary.txt"
    exit 0 ;;
  ack)
    SEGMENT="${POS_NATIVE_ACK_SEGMENT:?An exact frozen original ACK segment is required}"
    [[ "$SEGMENT" == INSTALLATION_ONLY_CONFIRMED_READ_REPEAT || "$SEGMENT" == CONFIRMED_CLOSE_REOPEN_REPEAT ]] || exit 2
    PHASE=original-ack
    set +e
    timeout 1200s "${ADB[@]}" shell am instrument -w -r \
      -e class com.ukkera.pos.android.independent.nativeoriginalackv4.NativeOriginalAckRecoveryTest#selectedFrozenSegment \
      -e nativeAckV4Segment "$SEGMENT" -e keyProtection OS_ISOLATED \
      com.ukkera.pos.local.testing.test/androidx.test.runner.AndroidJUnitRunner \
      | tee "$OUT/original-ack.txt"
    status=$?
    set -e
    "${ADB[@]}" logcat -d -b crash > "$OUT/crash-log.txt"
    # Read only selected synthetic evidence on the gated fresh emulator.
    # Raw app-private evidence stays in runner temp and is never uploaded.
    "${ADB[@]}" root > "$OUT/evidence-adb-context.txt" 2>&1 || true
    timeout 90s "${ADB[@]}" wait-for-device
    raw_evidence="${RUNNER_TEMP:?}/pos-native-ack-selected.json"
    "${ADB[@]}" exec-out cat /data/user/0/com.ukkera.pos.local.testing/no_backup/native-ack-v4-selected-segment.json > "$raw_evidence" || true
    python3 - "$raw_evidence" "$OUT/selected-segment.json" "$SEGMENT" <<'PYACK'
import hashlib,json,pathlib,sys
raw=pathlib.Path(sys.argv[1]).read_bytes()
try:
    record=json.loads(raw)
except (ValueError,UnicodeError):
    summary={'result':'EVIDENCE_UNAVAILABLE','selectedSegment':sys.argv[3]}
else:
    assert record['selectedSegment']==sys.argv[3]
    assert record['syntheticOnly'] is True
    summary={key:record.get(key) for key in ('fixture','selectedSegment','status','runtime','keyProtectionRequested','syntheticOnly','failure','unbound')}
    summary['events']=[event.get('event') for event in record.get('events',[])]
    summary['native_context_refusals']=[{key:event.get('facts',{}).get(key) for key in ('preparing','reason')}
        for event in record.get('events',[]) if event.get('event')=='NATIVE_CONTEXT_UNRESOLVED']
    summary['raw_evidence_sha256']=hashlib.sha256(raw).hexdigest()
pathlib.Path(sys.argv[2]).write_text(json.dumps(summary,indent=2)+'\n')
PYACK
    [[ "$status" -eq 0 ]]
    grep -Fq 'OK (1 test)' "$OUT/original-ack.txt"
    [[ "$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0' "$OUT/original-ack.txt")" -eq 1 ]]
    if grep -Eq '^INSTRUMENTATION_STATUS_CODE: -(1|2|3|4)|FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$OUT/original-ack.txt"; then
      echo 'Original ACK segment failed or skipped.' >&2; exit 1
    fi
    python3 - "$OUT/selected-segment.json" "$SEGMENT" <<'PYACKPASS'
import json,pathlib,sys
record=json.loads(pathlib.Path(sys.argv[1]).read_text())
assert record['status']==sys.argv[2]+'_SEGMENT_EXECUTED_NOT_WHOLE_NAC_CASE'
PYACKPASS
    printf '%s\n' 'PASS: one unchanged frozen original ACK segment on a fresh isolated installation; not a whole NAC/SAS/OSR case, native platform completion or owner acceptance.' > "$OUT/summary.txt"
    cat "$OUT/summary.txt"
    exit 0 ;;
  *) echo 'Unknown native slice group.' >&2; exit 2 ;;
esac
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
