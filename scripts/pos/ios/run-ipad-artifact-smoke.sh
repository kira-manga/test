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
printf 'repository=%s\nrun=%s\nattempt=%s\ninput_run=%s\ninput_artifact=%s\n' \
  "$GITHUB_REPOSITORY" "$GITHUB_RUN_ID" "$GITHUB_RUN_ATTEMPT" \
  "${EXPECTED_INPUT_RUN:?}" "${EXPECTED_INPUT_ARTIFACT:?}" > ipad-smoke-output/workflow.txt
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
import hashlib,json,os,pathlib,re,subprocess
root=pathlib.Path('simulator-input')
expected_source=os.environ['EXPECTED_INPUT_SOURCE']
expected_sha=os.environ['EXPECTED_INPUT_SHA256']
expected_bytes=os.environ['EXPECTED_INPUT_BYTES']
expected_run=os.environ['EXPECTED_INPUT_RUN']
expected_artifact=os.environ['EXPECTED_INPUT_ARTIFACT']
assert re.fullmatch(r'(?:[0-9a-f]{8}|[0-9a-f]{40})',expected_source)
assert re.fullmatch(r'[0-9a-f]{64}',expected_sha)
assert all(re.fullmatch(r'[1-9][0-9]*',v) for v in [expected_bytes,expected_run,expected_artifact])
artifact=json.loads(subprocess.check_output([
    'gh','api',f'repos/kira-manga/test/actions/artifacts/{expected_artifact}'],text=True))
assert artifact['id']==int(expected_artifact) and not artifact['expired']
assert artifact['workflow_run']['id']==int(expected_run)
assert artifact['name']==os.environ['EXPECTED_INPUT_ARTIFACT_NAME']
manifest=json.loads((root/'manifest.json').read_text())
assert manifest['metadata']['source_revision']==expected_source
assert str(manifest['metadata']['run_id'])==expected_run
entry=next(f for f in manifest['files'] if f['path']=='UkkeraPos-simulator.app.zip')
blob=(root/entry['path']).read_bytes()
assert len(blob)==int(expected_bytes)==entry['bytes']
assert hashlib.sha256(blob).hexdigest()==entry['sha256']==expected_sha
pathlib.Path('ipad-smoke-output/input-check.json').write_text(json.dumps({
    **entry,'source_revision':expected_source,'input_run_id':int(expected_run),
    'input_artifact_id':artifact['id'],'input_artifact_name':artifact['name']},indent=2)+'\n')
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
