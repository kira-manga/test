#!/usr/bin/env bash
set -euo pipefail
umask 077
stage="$RUNNER_TEMP/pos-qualification"
report="$RUNNER_TEMP/pos-qualification/public"
mkdir -p "$report"
cp "$stage/inputs/POS-REVISION" "$report/"
uname -sm > "$report/os.txt"
printf 'image=%s\ncpus=%s\n' "${ImageVersion:-unknown}" "$(nproc)" > "$report/hardware.txt"
awk '/MemTotal|MemAvailable/{print}' /proc/meminfo >> "$report/hardware.txt"
df -B1 --output=size,avail "$HOME" >> "$report/hardware.txt"
java -version 2> "$report/jdk.txt"
source="$stage/source/apps/pos"
here="$GITHUB_WORKSPACE/scripts/pos/qualification"
cd "$source"
declare -a gradle=(./gradlew --no-daemon --max-workers=2 --console=plain
  '-Dorg.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=1024m -Dfile.encoding=UTF-8'
  -Pkotlin.compiler.execution.strategy=in-process)
if [[ "$POS_SUITE" == 'recovery' ]]; then
  exec bash "$here/recovery.sh"
fi
if [[ "$POS_SUITE" == 'tcp' ]]; then
  exec bash "$here/tcp.sh"
fi
if [[ "$POS_SUITE" == 'companion' ]]; then
  result=0
  python3 "$here/watched.py" "$report" companion 95 "${gradle[@]}" \
    :core:database:jvmTest --tests '*CapturedMatchSourceDeferralNativeSupportingTest' \
    :domain:inventory:jvmTest --tests '*InterBranchTransferTest' \
    :adapters:inventory-store:jvmTest --tests '*InterBranchTransferEvidenceV1Test' || result=$?
  python3 "$here/results.py" companion "$stage" "$source"
  exit "$result"
fi
export UKKERA_CHB_RUNNER="$stage/inputs/runner"
export UKKERA_CHB_ATTEMPT="$HOME/.pos-frozen-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
export UKKERA_CHB_COUNT=1000
mkdir -m700 "$UKKERA_CHB_ATTEMPT"
printf '%s\n%s\n' "$(< "$stage/inputs/POS-REVISION")" "$(< "$stage/inputs/POS-TREE")" > "$UKKERA_CHB_ATTEMPT/SOURCE"
cc -shared -fPIC -O2 -Wall -Wextra "$UKKERA_CHB_RUNNER/controlled-utc.c" -o "$UKKERA_CHB_RUNNER/controlled-utc.so"
for phase in seed restart; do
  printf '%s 0\n' "$(date -ud '2026-08-01T07:59:00Z' +%s)" > "$UKKERA_CHB_ATTEMPT/$phase.utc"
done
seed=0
restart=1
python3 "$here/watched.py" "$report" seed 295 "${gradle[@]}" \
  -I "$UKKERA_CHB_RUNNER/paid-rung.init.gradle" :core:database:chbFrozenPaidSeed || seed=$?
if [[ -f "$stage/seed.raw" ]] && grep -Fxq 'CHB_NATIVE_CLOSE seeded-1000' "$stage/seed.raw"; then
  export UKKERA_CHB_EXISTING=true
  restart=0
  python3 "$here/watched.py" "$report" restart 32 "${gradle[@]}" \
    -I "$UKKERA_CHB_RUNNER/paid-rung.init.gradle" :core:database:chbFrozenPaidRestart || restart=$?
fi
: > "$UKKERA_CHB_ATTEMPT/gradle.log"
if [[ -f "$stage/seed.raw" ]]; then cat "$stage/seed.raw" >> "$UKKERA_CHB_ATTEMPT/gradle.log"; fi
if [[ -f "$stage/restart.raw" ]]; then cat "$stage/restart.raw" >> "$UKKERA_CHB_ATTEMPT/gradle.log"; fi
result=$seed
if [[ "$seed" == 0 ]]; then result=$restart; fi
printf '%s\n' "$result" > "$UKKERA_CHB_ATTEMPT/EXIT"
python3 "$here/results.py" frozen "$stage" "$UKKERA_CHB_ATTEMPT"
test "$result" = 0
for phase in seed restart; do
  grep -Fxq 'CHB_EXACT_LOOKUPS_RETURNED 7017' "$stage/$phase.raw"
  grep -Fxq 'CHB_EXACT_RECEIPTS_RETURNED 1000' "$stage/$phase.raw"
  grep -Fxq 'CHB_PROTECTED_ORIGINALS_VERIFIED 7017 SALE_COUNT 1000' "$stage/$phase.raw"
done
python3 "$UKKERA_CHB_RUNNER/verify-closed-eight-census.py" "$UKKERA_CHB_ATTEMPT" 1000 > "$stage/census.raw"
python3 "$here/results.py" census "$stage" "$UKKERA_CHB_ATTEMPT"
