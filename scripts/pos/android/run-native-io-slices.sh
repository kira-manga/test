#!/usr/bin/env bash
# Execute existing native slices or a bounded app-process startup on a disposable emulator.
# ACK and cold-original groups create a fresh synthetic original through the genuine native owner.
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
# Android 30+ exposes retained OS exit causes. Keep only numeric/reason fields;
# descriptions, traces and paths never enter the uploaded diagnostic summary.
collect_process_exit() {
  python3 - "$OUT/process-exit.json" "${ADB[@]}" <<'PYEXIT'
import json,pathlib,re,subprocess,sys
record={'package':'com.ukkera.pos.local.testing','records':[]}
try:
    result=subprocess.run(sys.argv[2:]+['shell','dumpsys','activity','exit-info',record['package']],
        capture_output=True,text=True,timeout=30)
    record['command_exit_status']=result.returncode
    for block in re.split(r'ApplicationExitInfo\s+#\d+:',result.stdout)[1:17]:
        item={}
        for key in ('pid','reason','subreason','status','importance','pss','rss'):
            match=re.search(r'\b'+key+r'=(\d+)\b',block)
            if match: item[key]=int(match.group(1))
        match=re.search(r'\breason=\d+\s*\(([A-Z_]+)\)',block)
        if match: item['reason_name']=match.group(1)
        if item: record['records'].append(item)
    record['observation']='OS_EXIT_RECORDS' if record['records'] else 'NO_PARSED_EXIT_RECORDS'
except (OSError,subprocess.SubprocessError):
    record['observation']='OS_EXIT_QUERY_UNAVAILABLE'
pathlib.Path(sys.argv[1]).write_text(json.dumps(record,indent=2)+'\n')
PYEXIT
}
retain_outcome() {
  local status=$?
  if [[ "$status" -ne 0 ]]; then
    printf 'FAIL: phase=%s exit_status=%s\n' "$PHASE" "$status" > "$OUT/failure.txt"
    # Installation can fail before instrumentation owns its usual diagnostics.
    # Query only an explicitly confirmed disposable emulator; preserve any earlier
    # canonical exit observation rather than replacing its evidence at EXIT.
    if [[ "${DISPOSABLE_CONFIRMED:-false}" == true ]]; then
      timeout 20s "${ADB[@]}" logcat -d -b crash > "$OUT/failure-crash-log.txt" 2>&1 || true
      if [[ ! -e "$OUT/process-exit.json" ]]; then collect_process_exit; fi
    fi
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
    'expected_android_abi': os.environ.get('POS_EXPECTED_ANDROID_ABI', ''),
    'expected_android_page_size': os.environ.get('POS_EXPECTED_ANDROID_PAGE_SIZE', ''),
    'repository': os.environ.get('GITHUB_REPOSITORY', ''),
    'workflow': os.environ.get('GITHUB_WORKFLOW', ''),
    'run_id': os.environ.get('GITHUB_RUN_ID', ''),
    'run_attempt': os.environ.get('GITHUB_RUN_ATTEMPT', ''),
    'aot_compile_requested': os.environ.get('POS_AOT_COMPILE', 'false'),
    'payment_stock_method_sample_requested': os.environ.get('POS_SAMPLE_PAYMENT_STOCK', 'false'),
    'original_finalization_method_sample_requested': os.environ.get('POS_SAMPLE_ORIGINAL_FINALIZATION', 'false'),
    'runner_os': os.environ.get('RUNNER_OS', ''),
    'runner_arch': os.environ.get('RUNNER_ARCH', ''),
    'runner_image': os.environ.get('ImageOS', ''),
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
DISPOSABLE_CONFIRMED=true
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
PHASE=device-profile
"${ADB[@]}" shell getprop ro.build.version.sdk > "$OUT/api-level.txt"
[[ "$(tr -d '\r\n' < "$OUT/api-level.txt")" == "$POS_EXPECTED_ANDROID_API" ]] || { echo 'Emulator SDK differs from requested SDK.' >&2; exit 2; }
"${ADB[@]}" shell getprop ro.product.cpu.abi > "$OUT/abi.txt"
"${ADB[@]}" shell getprop ro.product.cpu.abilist > "$OUT/abi-list.txt"
if [[ -n "${POS_EXPECTED_ANDROID_ABI:-}" ]]; then
  [[ "$(tr -d '\r\n' < "$OUT/abi.txt")" == "$POS_EXPECTED_ANDROID_ABI" ]] || { echo 'Emulator ABI differs from requested ABI.' >&2; exit 2; }
fi
if [[ -n "${POS_EXPECTED_ANDROID_PAGE_SIZE:-}" ]]; then
  "${ADB[@]}" shell getconf PAGE_SIZE > "$OUT/page-size.txt"
  [[ "$(tr -d '\r\n' < "$OUT/page-size.txt")" == "$POS_EXPECTED_ANDROID_PAGE_SIZE" ]] || { echo 'Emulator page size differs from requested profile.' >&2; exit 2; }
fi
PHASE=install
if [[ "${POS_NATIVE_SLICE_GROUP:-io}" == ack || "${POS_NATIVE_SLICE_GROUP:-io}" == cold-original || "${POS_NATIVE_SLICE_GROUP:-io}" == lifecycle || "${POS_NATIVE_SLICE_GROUP:-io}" == startup ]]; then
  existing="$("${ADB[@]}" shell pm path com.ukkera.pos.local.testing 2>/dev/null || true)"
  [[ -z "${existing//$'\r'/}" ]] || { echo 'Original setup slices require a fresh isolated installation.' >&2; exit 2; }
fi
"${ADB[@]}" install -r "$APP_APK"
"${ADB[@]}" install -r "$TEST_APK"
"${ADB[@]}" shell dumpsys package com.ukkera.pos.local.testing > "$OUT/app-package.txt"
"${ADB[@]}" shell dumpsys package com.ukkera.pos.local.testing.test > "$OUT/test-package.txt"
# Optional, explicit emulator compilation condition. This executes no application ceremony,
# changes no APK/test bytes, key policy, business deadline or native watchdog value.
[[ "${POS_AOT_COMPILE:-false}" == true || "${POS_AOT_COMPILE:-false}" == false ]] || exit 2
if [[ "${POS_AOT_COMPILE:-false}" == true ]]; then
  PHASE=aot-compile
  for package in com.ukkera.pos.local.testing com.ukkera.pos.local.testing.test; do
    timeout 240s "${ADB[@]}" shell cmd package compile -m speed -f "$package" \
      > "$OUT/aot-$package.txt" 2>&1
    tr -d '\r' < "$OUT/aot-$package.txt" | grep -Fxq 'Success'
  done
fi
"${ADB[@]}" logcat -c
PHASE=instrumentation
case "${POS_NATIVE_SLICE_GROUP:-io}" in
  startup)
    # Packaging smoke only. It performs no native setup ceremony, employee sign-in,
    # authority injection or instrumentation, and makes no frozen-case pass claim.
    PHASE=startup
    launch_status=0
    "${ADB[@]}" shell am start -W -n \
      com.ukkera.pos.local.testing/com.ukkera.pos.android.MainActivity \
      > "$OUT/startup-launch.txt" 2>&1 || launch_status=$?
    start_pid="$("${ADB[@]}" shell pidof com.ukkera.pos.local.testing 2>/dev/null | tr -d '\r\n' || true)"
    sleep 5
    end_pid="$("${ADB[@]}" shell pidof com.ukkera.pos.local.testing 2>/dev/null | tr -d '\r\n' || true)"
    "${ADB[@]}" shell dumpsys activity activities > "$OUT/startup-activities.txt" 2>&1 || true
    "${ADB[@]}" logcat -d -b crash > "$OUT/crash-log.txt" 2>&1 || true
    collect_process_exit
    python3 - "$OUT" "$launch_status" "$start_pid" "$end_pid" <<'PYSTARTUP'
import json, os, pathlib, re, sys
out = pathlib.Path(sys.argv[1])
launch = (out / 'startup-launch.txt').read_text()
start, end = sys.argv[3:5]
observed = {
    'launch_command_succeeded': sys.argv[2] == '0',
    'activity_launch_reported_ok': re.search(r'^Status: ok\s*$', launch, re.M) is not None,
    'single_positive_initial_pid': re.fullmatch(r'[1-9][0-9]*', start) is not None,
    'same_pid_after_five_seconds': start == end and bool(start),
}
passed = all(observed.values())
record = {
    'status': 'BOUNDED_PROCESS_STARTUP_OBSERVED' if passed else 'STARTUP_NOT_CONFIRMED',
    'source_revision': os.environ['POS_EXPECTED_APK_SOURCE'],
    'expected_android_sdk': os.environ['POS_EXPECTED_ANDROID_API'],
    'observed_android_abi': (out / 'abi.txt').read_text().strip(),
    'observed_shell_page_size': (out / 'page-size.txt').read_text().strip() if (out / 'page-size.txt').is_file() else 'NOT_QUERIED',
    'package': 'com.ukkera.pos.local.testing',
    'initial_pid': start, 'final_pid': end, 'sample_interval_seconds': 5,
    'observed': observed,
    'instrumentation_methods_executed': 0,
    'setup_authentication_lan_business_owner_acceptance': 'NOT_RUN',
    'limits': 'Activity launch and the exact original PID at two observations only. No continuous-liveness, rendered-UI, setup, native custody, login, Master/Client or business acceptance claim.',
}
(out / 'startup.json').write_text(json.dumps(record, indent=2) + '\n')
assert passed, 'Bounded original-process startup was not confirmed; preserve diagnostics.'
PYSTARTUP
    printf '%s\n' 'PASS: bounded activity launch and same process PID after five seconds; no setup, custody, employee login, LAN or business acceptance.' > "$OUT/summary.txt"
    cat "$OUT/summary.txt"
    exit 0 ;;
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
    collect_process_exit
    # These existing tags emit static source locations/errno only, never native data.
    "${ADB[@]}" logcat -d -s UkkeraNativeOwner:I UkkeraEnrollment:W '*:S' > "$OUT/native-owner-log.txt"
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
  lifecycle)
    SELECTED_CASE="${POS_NATIVE_LIFECYCLE_CASE:?Select one conditional-design APSL supporting service segment}"
    [[ "$SELECTED_CASE" == STOP_RESUME || "$SELECTED_CASE" == LOCK_BACKGROUND_REOPEN || "$SELECTED_CASE" == STOP_CLOSE_ACCOUNTING ]] || exit 2
    PHASE=lifecycle
    set +e
    timeout 1200s "${ADB[@]}" shell am instrument -w -r \
      -e class com.ukkera.pos.android.independent.androidpersistedstopv1.NativePersistedStopServiceTest#selectedLifecycleSegment \
      -e apslCase "$SELECTED_CASE" -e keyProtection OS_ISOLATED \
      com.ukkera.pos.local.testing.test/androidx.test.runner.AndroidJUnitRunner \
      | tee "$OUT/lifecycle.txt"
    status=$?
    set -e
    "${ADB[@]}" logcat -d -b crash > "$OUT/crash-log.txt"
    collect_process_exit
    "${ADB[@]}" logcat -d -s UkkeraNativeOwner:I UkkeraEnrollment:W '*:S' > "$OUT/native-owner-log.txt"
    "${ADB[@]}" root > "$OUT/evidence-adb-context.txt" 2>&1 || true
    timeout 90s "${ADB[@]}" wait-for-device
    raw_evidence="${RUNNER_TEMP:?}/pos-native-lifecycle-selected.json"
    "${ADB[@]}" exec-out cat /data/user/0/com.ukkera.pos.local.testing/no_backup/apsl-v2-selected-case.json > "$raw_evidence" || true
    python3 - "$raw_evidence" "$OUT/lifecycle-summary.json" "$SELECTED_CASE" <<'PYLIFECYCLE'
import hashlib,json,pathlib,sys
raw=pathlib.Path(sys.argv[1]).read_bytes()
try:
    record=json.loads(raw)
except (ValueError,UnicodeError):
    summary={'result':'EVIDENCE_UNAVAILABLE','selectedCase':sys.argv[3]}
else:
    assert record['selectedCase']==sys.argv[3] and record['syntheticOnly'] is True
    assert record['design']=='foundation-android-persisted-stop-lifecycle-v2'
    summary={key:record.get(key) for key in ('design','designCommit','fixture','selectedCase','status','runtime','keyProtectionRequested','syntheticOnly')}
    summary['failure_type']=(record.get('failure') or {}).get('type')
    summary['events']=[event.get('event') for event in record.get('events',[])]
    summary['unbound_ids']=[item.get('id') for item in record.get('unbound',[])]
    summary['raw_evidence_sha256']=hashlib.sha256(raw).hexdigest()
pathlib.Path(sys.argv[2]).write_text(json.dumps(summary,indent=2)+'\n')
PYLIFECYCLE
    [[ "$status" -eq 0 ]]
    grep -Fq 'OK (1 test)' "$OUT/lifecycle.txt"
    [[ "$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0' "$OUT/lifecycle.txt")" -eq 1 ]]
    if grep -Eq '^INSTRUMENTATION_STATUS_CODE: -(1|2|3|4)|FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$OUT/lifecycle.txt"; then
      echo 'Lifecycle segment failed or skipped.' >&2; exit 1
    fi
    python3 - "$OUT/lifecycle-summary.json" "$SELECTED_CASE" <<'PYLIFECYCLEPASS'
import json,pathlib,sys
record=json.loads(pathlib.Path(sys.argv[1]).read_text())
assert record['selectedCase']==sys.argv[2]
assert record['status']=='SELECTED_APSL_SEGMENTS_EXECUTED_NOT_WHOLE_CASE'
PYLIFECYCLEPASS
    printf '%s\n' 'PASS: one unchanged supporting APSL service lifecycle segment from a conditional, not frozen, design; whole-case review/qualification, process death, force-stop, hardware and owner acceptance remain unqualified.' > "$OUT/summary.txt"
    cat "$OUT/summary.txt"
    exit 0 ;;
  cold-original)
    PHASE=cold-original
    # Optional diagnostics on the unchanged synthetic APK pair. The collector owns
    # only ART sampling/logcat; the native work budget and pass assertions remain.
    sample_pid=''
    [[ "${POS_SAMPLE_PAYMENT_STOCK:-false}" == true || "${POS_SAMPLE_PAYMENT_STOCK:-false}" == false ]] || exit 2
    [[ "${POS_SAMPLE_ORIGINAL_FINALIZATION:-false}" == true || "${POS_SAMPLE_ORIGINAL_FINALIZATION:-false}" == false ]] || exit 2
    [[ "${POS_SAMPLE_PAYMENT_STOCK:-false}:${POS_SAMPLE_ORIGINAL_FINALIZATION:-false}" != true:true ]] || exit 2
    sample_phase=''
    if [[ "${POS_SAMPLE_PAYMENT_STOCK:-false}" == true ]]; then sample_phase=payment-stock; fi
    if [[ "${POS_SAMPLE_ORIGINAL_FINALIZATION:-false}" == true ]]; then sample_phase=original-finalization; fi
    if [[ -n "$sample_phase" ]]; then
      python3 scripts/pos/android/sample-payment-stock.py --serial "$SERIAL" --output "$OUT/method-sample" --phase "$sample_phase" &
      sample_pid=$!
    fi
    set +e
    timeout 1200s "${ADB[@]}" shell am instrument -w -r \
      -e class com.ukkera.pos.android.independent.coldoriginalstartv1.NativeColdOriginalTest#cos001NativeCashOriginalAcrossTwoColdReopens \
      -e syntheticNativeColdOriginalCos001 true -e keyProtection OS_ISOLATED \
      com.ukkera.pos.local.testing.test/androidx.test.runner.AndroidJUnitRunner \
      | tee "$OUT/cold-original.txt"
    status=$?
    if [[ -n "$sample_pid" ]]; then
      touch "$OUT/sampling-instrumentation-finished"
      wait "$sample_pid" || true
    fi
    set -e
    "${ADB[@]}" logcat -d -b crash > "$OUT/crash-log.txt"
    collect_process_exit
    "${ADB[@]}" logcat -d -s UkkeraNativeOwner:I UkkeraEnrollment:W '*:S' > "$OUT/native-owner-log.txt"
    "${ADB[@]}" root > "$OUT/evidence-adb-context.txt" 2>&1 || true
    timeout 90s "${ADB[@]}" wait-for-device
    # Retain only bounded nonsecret observations; requests, results and private cuts stay local.
    raw_evidence="${RUNNER_TEMP:?}/pos-native-cold-original-selected.json"
    "${ADB[@]}" exec-out cat /data/user/0/com.ukkera.pos.local.testing/no_backup/cold-original-start-v1-backend-intent.json > "$raw_evidence" || true
    python3 scripts/pos/android/summarize-cold-original.py "$raw_evidence" "$OUT/cold-original-summary.json"
    [[ "$status" -eq 0 ]]
    grep -Fq 'OK (1 test)' "$OUT/cold-original.txt"
    [[ "$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0' "$OUT/cold-original.txt")" -eq 1 ]]
    if grep -Eq '^INSTRUMENTATION_STATUS_CODE: -(1|2|3|4)|FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$OUT/cold-original.txt"; then
      echo 'Cold-original selector failed or skipped.' >&2; exit 1
    fi
    python3 - "$OUT/cold-original-summary.json" <<'PYCOLDPASS'
import json,pathlib,sys
record=json.loads(pathlib.Path(sys.argv[1]).read_text())
assert record['status']=='BOUNDED_NATIVE_ASSERTIONS_OBSERVED_WHOLE_CASE_NOT_QUALIFIED'
assert record['events'].count('COLD_REOPEN_ORIGINAL_RECOVERY_OBSERVED')==2
assert record['cleanup']==[{'nativeCloseConfirmed':True,'failureClass':None}]
PYCOLDPASS
    printf '%s\n' 'PASS: unchanged COS-001 bounded native backend selector with two confirmed owner reopens; full frozen UI, receipt, LAN, failure variants and owner acceptance remain unqualified.' > "$OUT/summary.txt"
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
