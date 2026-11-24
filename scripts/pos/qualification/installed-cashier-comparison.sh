#!/usr/bin/env bash
set -euo pipefail
umask 077

runner="$GITHUB_WORKSPACE/scripts/pos/qualification/installed-cashier.sh"
if [[ -z "${EXPECTED_COMPARISON:-}" ]]; then
  exec bash "$runner"
fi

[[ "${POS_INSTALLED_CASHIER_CASE:-}" == paid && "$EXPECTED_COMPARISON" =~ ^[0-9a-f]{40}$ ]]
candidate="$RUNNER_TEMP/pos-comparison"
[[ "$(cat "$candidate/pos-qualification/inputs/POS-REVISION")" == "$EXPECTED_COMPARISON" ]]

# Reset only these designated synthetic apps on the single disposable CI guest.
mapfile -t devices < <(adb devices | awk 'NR > 1 && NF { print $1 ":" $2 }')
[[ "${#devices[@]}" -eq 1 && "${devices[0]}" =~ ^emulator-[0-9]+:device$ ]]
export ANDROID_SERIAL="${devices[0]%:device}"
boot_qemu="$(adb shell getprop ro.boot.qemu | tr -d '\r')"
kernel_qemu="$(adb shell getprop ro.kernel.qemu | tr -d '\r')"
[[ "$boot_qemu" == 1 || "$kernel_qemu" == 1 ]]
[[ "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" == 35 ]]
package_installed() {
  local result
  result="$(adb shell pm list packages --user 0 "$1" | tr -d '\r')" || return 2
  grep -Fxq "package:$1" <<< "$result"
}
require_absent() {
  local installed=0
  package_installed "$1" || installed=$?
  [[ "$installed" == 1 ]]
}

test_package=com.ukkera.pos.local.testing.test
app_package=com.ukkera.pos.local.testing
require_absent "$test_package"
require_absent "$app_package"

baseline=0
comparison=0
bash "$runner" || baseline=$?
for package in "$test_package" "$app_package"; do
  installed=0
  package_installed "$package" || installed=$?
  case "$installed" in
    0) adb uninstall "$package" | grep -Fxq Success ;;
    1) ;;
    *) exit 2 ;;
  esac
  require_absent "$package"
done
RUNNER_TEMP="$candidate" bash "$runner" || comparison=$?
printf 'POS_INSTALLED_SAME_RUNNER baseline_exit=%s candidate_exit=%s\n' "$baseline" "$comparison"
if (( baseline != 0 )); then exit "$baseline"; fi
exit "$comparison"
