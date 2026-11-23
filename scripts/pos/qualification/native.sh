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
if [[ "$POS_SUITE" == 'retained-reader' ]]; then
  result=0
  python3 "$here/watched.py" "$report" retained-reader 25 bash "$here/runner-owned-temp.sh" "${gradle[@]}" \
    :core:database:jvmTest --tests '*RetainedFragmentPageSupportingTest' \
    --tests '*RetainedCommandReadTest' --tests '*NativeOriginalSourceRootSupportingTest' || result=$?
  python3 "$here/results.py" retained-reader "$stage" "$source"
  exit "$result"
fi
case "$POS_SUITE" in
cold-payment-control|cold-pass-control|cold-owner-control|cold-literal-control)
  cat > "$stage/cold-payment.init.gradle" <<'EOFCOLD'
gradle.beforeProject { p ->
    if (p.path == ':adapters:app-gateway') {
        p.tasks.withType(Test).configureEach {
            systemProperty 'ukkera.pos.paidReceiptWorkspace', 'true'
            if (p.findProperty('posColdOwnerDiagnostics') == 'true')
                systemProperty 'ukkera.lan.verifierDiagnostics', 'true'
            testLogging { exceptionFormat "full" }
        }
    }
}
EOFCOLD
  result=0
  control_options=()
  if [[ "$POS_SUITE" == 'cold-owner-control' || "$POS_SUITE" == 'cold-literal-control' ]]; then control_options+=(-PposColdOwnerDiagnostics=true); fi
  python3 "$here/watched.py" "$report" cold-payment 30 bash "$here/runner-owned-temp.sh" "${gradle[@]}" \
    -I "$stage/cold-payment.init.gradle" "${control_options[@]}" :adapters:app-gateway:jvmTest \
    --tests '*PaidRetailReceiptColdNativeSupportingTest' || result=$?
  python3 "$here/results.py" "$POS_SUITE" "$stage" "$source"
  exit "$result"
  ;;
esac
if [[ "$POS_SUITE" == 'opening-control' ]]; then
  cat > "$stage/opening-control.init.gradle" <<'EOFCONTROL'
gradle.beforeProject { p ->
    if (p.path == ':core:database') {
        p.tasks.withType(Test).configureEach {
            testLogging {
                events "failed"
                exceptionFormat "full"
                showCauses true
                showStackTraces true
            }
        }
    }
}
EOFCONTROL
  result=0
  python3 "$here/watched.py" "$report" opening-control 45 bash "$here/runner-owned-temp.sh" "${gradle[@]}" \
    -I "$stage/opening-control.init.gradle" :core:database:jvmTest \
    --tests '*LanBusinessTransactionIntegrationTest.nativeOpeningReplyLossKeepsOneOriginalAfterReopen*' || result=$?
  python3 "$here/results.py" opening-control "$stage" "$source"
  exit "$result"
fi
if [[ "$POS_SUITE" == 'payment-regression' ]]; then
  cat > "$stage/native-regression-diagnostics.init.gradle" <<'EOFREGRESSION'
gradle.beforeProject { p ->
    if (p.path == ':core:database') {
        p.tasks.withType(Test).configureEach {
            systemProperty 'ukkera.lan.verifierDiagnostics', 'true'
            testLogging {
                events 'failed'
                exceptionFormat 'full'
                showStandardStreams = true
            }
        }
    }
}
EOFREGRESSION
  result=0
  python3 "$here/watched.py" "$report" payment-regression 95 bash "$here/runner-owned-temp.sh" "${gradle[@]}" --continue \
    -I "$stage/native-regression-diagnostics.init.gradle" \
    :adapters:inventory-store:jvmTest :adapters:sales-store:jvmTest \
    :core:database:jvmTest --tests '*NativeOriginalSourceRootSupportingTest' --tests '*LanBusinessTransactionIntegrationTest' || result=$?
  python3 "$here/results.py" payment-regression "$stage" "$source"
  exit "$result"
fi
count=1000
if [[ "$POS_SUITE" == 'paid-prefix' ]]; then count=8; fi
if [[ "$POS_SUITE" == 'paid-profile' ]]; then
  case "$POS_PAID_PROFILE_COUNT" in 5|10|25|32|50|100|250|500) count=$POS_PAID_PROFILE_COUNT ;; *) exit 2 ;; esac
fi
export UKKERA_CHB_COUNT="$count"
expected=$((12 + 7 * count + 5))
export UKKERA_CHB_RUNNER="$stage/inputs/runner"
export UKKERA_CHB_ATTEMPT="$HOME/.pos-frozen-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
mkdir -m700 "$UKKERA_CHB_ATTEMPT"
printf '%s\n%s\n' "$(< "$stage/inputs/POS-REVISION")" "$(< "$stage/inputs/POS-TREE")" > "$UKKERA_CHB_ATTEMPT/SOURCE"
cc -shared -fPIC -O2 -Wall -Wextra "$UKKERA_CHB_RUNNER/controlled-utc.c" -o "$UKKERA_CHB_RUNNER/controlled-utc.so"
for phase in seed restart; do
  printf '%s 0\n' "$(date -ud '2026-08-01T07:59:00Z' +%s)" > "$UKKERA_CHB_ATTEMPT/$phase.utc"
done
# Measure compilation separately from this supporting run's fixed runtime budget.
# The same resources guard compilation and both native invocations.
if [[ "$POS_SUITE" == 'paid-profile' ]]; then
  # The frozen POS clock stalls JVM startup recording. Observe this JVM externally.
  if grep -Fq 'StartFlightRecording' "$UKKERA_CHB_RUNNER/paid-rung.init.gradle"; then
    echo "The paid profile requires external sampling with its fixed test clock."
    exit 2
  fi
  compile_tasks=(:core:database:jvmTestClasses)
  compile_result=0
  if [[ "${POS_CHECK_FLOOR_BATCH:-0}" == 1 ]]; then
    compile_tasks+=(:core:database:jvmTest --tests '*NativeColdHistoryWorkspaceSupportingTest.nativeKeyFloorBatchPreservesExactOrderAndStrictCut*')
  fi
  python3 "$here/watched.py" "$report" profile-compile 15 "${gradle[@]}" \
    -I "$UKKERA_CHB_RUNNER/paid-rung.init.gradle" "${compile_tasks[@]}" || compile_result=$?
  # The hosted runner can be rate limited by both Central endpoints. Retry
  # only this exact pinned plugin using the byte-verified public artifacts.
  crypto_repository=
  if [[ "$compile_result" == 1 && -f "$stage/profile-compile.raw" ]] &&
     grep -Fq "Plugin [id: 'dev.whyoleg.cryptography', version: '0.6.0', apply: false] was not found" "$stage/profile-compile.raw"; then
    marker='dev/whyoleg/cryptography/dev.whyoleg.cryptography.gradle.plugin/0.6.0/dev.whyoleg.cryptography.gradle.plugin-0.6.0.pom'
    implementation='dev/whyoleg/cryptography/cryptography-gradle-plugin/0.6.0/cryptography-gradle-plugin-0.6.0'
    plugin_repository="$stage/profile-cryptography-maven"
    mkdir -p "$plugin_repository/${marker%/*}" "$plugin_repository/${implementation%/*}"
    fetch_verified_plugin_file() {
      local repository="$1" path="$2" digest="$3"
      curl --fail --silent --show-error --location --proto '=https' --max-time 12 \
        "$repository$path" -o "$plugin_repository/$path" &&
        printf '%s  %s\n' "$digest" "$plugin_repository/$path" | sha256sum --check --status
    }
    for repository in https://repo1.maven.org/maven2/ https://repo.maven.apache.org/maven2/ \
        https://maven-central.storage-download.googleapis.com/maven2/; do
      if fetch_verified_plugin_file "$repository" "$marker" 02f7d7b8bb05e4c74b611ac0ab8a5edde67bbabf27a4dc078daf4b86213b6f0d &&
         fetch_verified_plugin_file "$repository" "$implementation.pom" 3de81c3773fd3f9baf73921f440c6b49a8e30282e71c4922f8c37133d62d6f6c &&
         fetch_verified_plugin_file "$repository" "$implementation.module" 3d271824f9a2428e9715143d9da200b0bb48f28a1b728af6ca208b8ce6e940d5 &&
         fetch_verified_plugin_file "$repository" "$implementation.jar" 9ddf44b4775c84916e018b5c7abe6ba34e2a1f229663013a83165b91bd8dbd0d; then
        crypto_repository="$repository"
        break
      fi
    done
  fi
  if [[ -n "$crypto_repository" ]]; then
    printf 'POS_QUALIFICATION_CRYPTOGRAPHY_REPOSITORY %s\n' "$crypto_repository"
    mv "$stage/profile-compile.raw" "$stage/profile-compile-repository-failure.raw"
    mv "$report/profile-compile.json" "$report/profile-compile-repository-failure.json"
    # Use the same Maven Central coordinates explicitly and only for this
    # exact published marker and implementation. The build's other repositories
    # and all pinned dependency versions remain in their normal resolution path.
    cat > "$stage/profile-cryptography.init.gradle" <<EOFCRYPTO
gradle.beforeSettings { settings ->
    settings.pluginManagement.repositories.maven {
        name = 'PosQualificationCryptography'
        url = uri('$plugin_repository')
        content {
            includeVersion('dev.whyoleg.cryptography', 'dev.whyoleg.cryptography.gradle.plugin', '0.6.0')
            includeVersion('dev.whyoleg.cryptography', 'cryptography-gradle-plugin', '0.6.0')
        }
    }
}
EOFCRYPTO
    compile_result=0
    python3 "$here/watched.py" "$report" profile-compile 15 "${gradle[@]}" \
      -I "$stage/profile-cryptography.init.gradle" -I "$UKKERA_CHB_RUNNER/paid-rung.init.gradle" \
      "${compile_tasks[@]}" || compile_result=$?
  fi
  if [[ "${POS_CHECK_FLOOR_BATCH:-0}" == 1 ]]; then
    python3 "$here/results.py" floor-batch-control "$stage" "$source"
  fi
  test "$compile_result" = 0
fi
seed=0
restart=1
started=$SECONDS
phase_budget=337
if [[ "$POS_SUITE" == 'paid-profile' ]]; then phase_budget=36; fi
if [[ "${POS_HISTORY_PROFILE:-0}" == 1 && "$POS_SUITE" == 'paid-profile' ]]; then
  export UKKERA_CHB_SOURCE_DIAGNOSTICS=true
fi
observe_profile() {
  if [[ "${POS_HISTORY_PROFILE:-0}" == 1 ]]; then
    python3 "$here/profile-steps.py" "$stage" "$UKKERA_CHB_ATTEMPT" "$POS_SUITE" "$1" "$count" &
    observer=$!
    trap 'touch "$stage/seed.stack.done" "$stage/restart.stack.done"' EXIT
  fi
  if [[ "$POS_SUITE" == 'paid-profile' ]]; then
    python3 "$here/paid-profile-metrics.py" "$stage" "$UKKERA_CHB_ATTEMPT" "$1" &
    profile_pid=$!
    if [[ -n "${POS_CPU_PROFILER:-}" ]]; then
      python3 "$here/profile-paid-cpu.py" "$stage" "$UKKERA_CHB_ATTEMPT" "$1" "$POS_CPU_PROFILER" &
      cpu_observer=$!
    fi
  fi
}
finish_profile() {
  if [[ -n "${observer:-}" ]]; then
    touch "$stage/$1.stack.done"
    wait "$observer"
    unset observer
  fi
  if [[ "$POS_SUITE" == 'paid-profile' ]]; then
    touch "$stage/$1.profile.done"
    wait "$profile_pid"
    if [[ -n "${cpu_observer:-}" ]]; then
      wait "$cpu_observer"
      unset cpu_observer
    fi
  fi
}
observe_profile seed
python3 "$here/watched.py" "$report" seed "$phase_budget" "${gradle[@]}" \
  -I "$UKKERA_CHB_RUNNER/paid-rung.init.gradle" :core:database:chbFrozenPaidSeed || seed=$?
finish_profile seed
if [[ -f "$stage/seed.raw" ]] && grep -Fxq "CHB_NATIVE_CLOSE seeded-$count" "$stage/seed.raw"; then
  export UKKERA_CHB_EXISTING=true
  remaining=$((phase_budget - ((SECONDS - started + 59) / 60)))
  if (( remaining > 0 )); then
    restart=0
    observe_profile restart
    python3 "$here/watched.py" "$report" restart "$remaining" "${gradle[@]}" \
    -I "$UKKERA_CHB_RUNNER/paid-rung.init.gradle" :core:database:chbFrozenPaidRestart || restart=$?
    finish_profile restart
  else
    restart=124
    echo "Shared native job time exhausted before fresh-process start."
  fi
fi
if [[ "${POS_HISTORY_PROFILE:-0}" == 1 && "$POS_SUITE" == 'paid-profile' ]]; then
  python3 "$here/profile-stack-summary.py" "$stage" > "$report/stack-samples.json"
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
  grep -Fxq "CHB_EXACT_LOOKUPS_RETURNED $expected" "$stage/$phase.raw"
  grep -Fxq "CHB_EXACT_RECEIPTS_RETURNED $count" "$stage/$phase.raw"
  grep -Fxq "CHB_PROTECTED_ORIGINALS_VERIFIED $expected SALE_COUNT $count" "$stage/$phase.raw"
done
python3 "$UKKERA_CHB_RUNNER/verify-closed-eight-census.py" "$UKKERA_CHB_ATTEMPT" "$count" > "$stage/census.raw"
python3 "$here/results.py" census "$stage" "$UKKERA_CHB_ATTEMPT"
