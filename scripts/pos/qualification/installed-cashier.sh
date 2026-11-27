#!/usr/bin/env bash
set -euo pipefail
umask 077
stage="$RUNNER_TEMP/pos-qualification"
report="$stage/public"
here="$GITHUB_WORKSPACE/scripts/pos/qualification"
if [[ "${1:-}" != 'instrument' ]]; then
  exec python3 "$here/watched.py" "$report" installed-cashier 20 bash "$here/installed-cashier.sh" instrument
fi
sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$sdk" == 35 ]]
scope="${POS_INSTALLED_CASHIER_CASE:-covered}"
case "$scope" in
  covered) selector=measureConfirmedCoveredSaleThroughRecordedFinancialPreview ;;
  paid|paid-control) selector=measureActualCashStartThroughRecordedFinancialPreview ;;
  *) exit 2 ;;
esac
source="$stage/source/apps/pos/apps"
mapfile -t app < <(find "$source/android/build/outputs/apk/localTesting" -maxdepth 1 -name '*.apk')
mapfile -t tests < <(find "$source/android-runtime-tests/build/outputs/apk/localTesting" -maxdepth 1 -name '*.apk')
[[ "${#app[@]}" == 1 && "${#tests[@]}" == 1 ]]
for apk in "${app[0]}" "${tests[0]}"; do
  "$ANDROID_HOME/build-tools/37.0.0/apksigner" verify "$apk"
  adb install "$apk"
done
adb shell pm path com.ukkera.pos.local.testing | grep -q '^package:'
adb shell pm path com.ukkera.pos.local.testing.test | grep -q '^package:'
case "${POS_INSTALLED_ART_MODE:-default}" in
  default) ;;
  speed)
    test "$scope" = paid-control
    app_process=$(adb shell 'pidof com.ukkera.pos.local.testing || [ "$?" -eq 1 ]' | tr -d '\r')
    test -z "$app_process"
    timeout 240s adb shell cmd package compile -m speed -f com.ukkera.pos.local.testing |
      tr -d '\r' | grep -Fxq Success
    printf '%s\n' speed > "$report/installed-cashier-art-mode.txt"
    ;;
  *) exit 2 ;;
esac
adb logcat -c
result=0
# Leave time inside the outer watchdog to retain instrumentation and device diagnostics.
timeout --kill-after=10s 12m adb shell am instrument -w -r \
  -e class "com.ukkera.pos.android.independent.coldoriginalstartv1.InstalledCashierFinalizeLatencySupportingTest#$selector" \
  -e syntheticInstalledCashierFinalizeLatency true -e keyProtection OS_ISOLATED \
  com.ukkera.pos.local.testing.test/androidx.test.runner.AndroidJUnitRunner \
  > "$stage/installed-cashier.instrumentation" 2>&1 || result=$?
cat "$stage/installed-cashier.instrumentation"
adb logcat -d > "$stage/installed-cashier-device.log"
python3 "$here/installed-cashier-result.py" "$stage" "$result" "$sdk" "$scope"
