#!/usr/bin/env bash
# Reuse the verified ARM64 simulator package. No compilation, key setup or authentication.
set -euo pipefail
[[ "${GITHUB_REPOSITORY:-}" == "kira-manga/test" && "${RUNNER_ENVIRONMENT:-}" == "github-hosted" ]] || {
  echo 'A disposable authorized auxiliary runner is required.' >&2; exit 1;
}
[[ "$(tr -d '\r\n' < SOURCE-REVISION.txt)" == "${EXPECTED_HARNESS_SOURCE:?}" ]] || exit 1
mkdir -p ipad-smoke-output
cp SOURCE-REVISION.txt ipad-smoke-output/HARNESS-SOURCE.txt
git rev-parse HEAD > ipad-smoke-output/HARNESS-REVISION.txt
printf 'repository=%s\nrun=%s\nattempt=%s\ninput_run=42680343828\ninput_artifact=13706096880\n' \
  "$GITHUB_REPOSITORY" "$GITHUB_RUN_ID" "$GITHUB_RUN_ATTEMPT" > ipad-smoke-output/workflow.txt
cp simulator-input/SOURCE-REVISION.txt ipad-smoke-output/INPUT-SOURCE.txt
xcodebuild -version > ipad-smoke-output/xcode.txt
simulator_id=''
cleanup() {
  result=$?
  if [[ -n "$simulator_id" ]]; then
    xcrun simctl spawn "$simulator_id" log show --last 3m --style compact --predicate 'process == "UkkeraPos"' \
      > ipad-smoke-output/app-log.txt 2>&1 || true
    xcrun simctl terminate "$simulator_id" com.ukkera.pos.local > /dev/null 2>&1 || true
    xcrun simctl shutdown "$simulator_id" > /dev/null 2>&1 || true
    xcrun simctl delete "$simulator_id" > /dev/null 2>&1 || true
  fi
  if [[ "$result" != 0 ]]; then printf 'FAIL: simulator smoke exited %s\n' "$result" > ipad-smoke-output/failure.txt; fi
}
trap cleanup EXIT
python3 - <<'PY'
import hashlib,json,pathlib
root=pathlib.Path('simulator-input')
manifest=json.loads((root/'manifest.json').read_text())
assert manifest['metadata']['source_revision']=='038c115e'
entry=next(f for f in manifest['files'] if f['path']=='UkkeraPos-simulator.app.zip')
blob=(root/entry['path']).read_bytes()
assert len(blob)==25615381==entry['bytes']
assert hashlib.sha256(blob).hexdigest()==entry['sha256']=='512c42221b66fdbe9e005ad5aed0784a576a6add9a7d0abf828dfe30285a9504'
pathlib.Path('ipad-smoke-output/input-check.json').write_text(json.dumps(entry,indent=2)+'\n')
PY
python3 scripts/pos/ios/select-simulator.py --family ipad > ipad-smoke-output/simulator.json
simulator_id="$(python3 -c 'import json; print(json.load(open("ipad-smoke-output/simulator.json"))["udid"])')"
xcrun simctl boot "$simulator_id"
xcrun simctl bootstatus "$simulator_id" -b > ipad-smoke-output/boot.txt 2>&1
mkdir -p simulator-input/unpacked
ditto -x -k simulator-input/UkkeraPos-simulator.app.zip simulator-input/unpacked
app='simulator-input/unpacked/UkkeraPos.app'
[[ -d "$app" ]] || { echo 'Simulator app missing' >&2; exit 1; }
xcrun simctl install "$simulator_id" "$app"
xcrun simctl launch "$simulator_id" com.ukkera.pos.local > ipad-smoke-output/launch.txt
launch_pid="$(python3 - <<'PY'
import pathlib,re
text=pathlib.Path('ipad-smoke-output/launch.txt').read_text()
match=re.fullmatch(r'com\.ukkera\.pos\.local:\s*([1-9][0-9]*)\s*',text)
assert match, 'No exact positive launch PID'
print(match.group(1))
PY
)"
printf '%s\n' "$launch_pid" > ipad-smoke-output/launch-pid.txt
sleep 5
xcrun simctl spawn "$simulator_id" launchctl list > ipad-smoke-output/processes.txt
python3 - "$launch_pid" <<'PY'
import pathlib,sys
expected=sys.argv[1]
rows=pathlib.Path('ipad-smoke-output/processes.txt').read_text().splitlines()
assert any(r.split() and r.split()[0]==expected for r in rows), 'Exact launch PID did not survive startup interval'
PY
xcrun simctl io "$simulator_id" screenshot ipad-smoke-output/screenshot.png
printf 'PASS: exact simulator package installed/launched on iPad and original PID survived 5 seconds. No setup, signing, custody, Master/Client or owner acceptance.\n' > ipad-smoke-output/summary.txt
