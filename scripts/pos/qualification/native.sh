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
# Some hosted runners cannot resolve an exact pinned plugin through Gradle.
# Retry only those errors, keeping the published versions and artifact coordinates.
prepare_verified_plugin() {
  local phase="$1"
  local crypto_repository=
  [[ -f "$stage/$phase.raw" ]] || return 1
  if ! grep -Fq "Plugin [id: 'dev.whyoleg.cryptography', version: '0.6.0', apply: false] was not found" "$stage/$phase.raw" &&
     ! grep -Fq "Plugin [id: 'org.jetbrains.kotlin.multiplatform', version: '2.4.21', apply: false] was not found" "$stage/$phase.raw" &&
     ! grep -Fq "Plugin [id: 'org.jetbrains.compose', version: '1.12.2', apply: false] was not found" "$stage/$phase.raw" &&
     ! grep -Fq "Plugin [id: 'com.google.devtools.ksp', version: '2.3.12', apply: false] was not found" "$stage/$phase.raw" &&
     ! grep -Eq "https://repo[.]maven[.]apache[.]org/maven2/[^']+'.*status code 429 from server" "$stage/$phase.raw"; then
    return 1
  fi
  local marker='dev/whyoleg/cryptography/dev.whyoleg.cryptography.gradle.plugin/0.6.0/dev.whyoleg.cryptography.gradle.plugin-0.6.0.pom'
  local implementation='dev/whyoleg/cryptography/cryptography-gradle-plugin/0.6.0/cryptography-gradle-plugin-0.6.0'
  local plugin_repository="$stage/profile-cryptography-maven"
  mkdir -p "$plugin_repository/${marker%/*}" "$plugin_repository/${implementation%/*}" || return 1
  fetch_verified_plugin_file() {
    local repository="$1" path="$2" digest="$3"
    curl --fail --silent --show-error --location --proto '=https' --max-time 12 \
      "$repository$path" -o "$plugin_repository/$path" &&
      printf '%s  %s\n' "$digest" "$plugin_repository/$path" | sha256sum --check --status
  }
  local repository
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
  if [[ -n "$crypto_repository" ]]; then
    printf 'POS_QUALIFICATION_CRYPTOGRAPHY_REPOSITORY %s\n' "$crypto_repository"
    # Keep the exact local cryptography coordinates and verified downloads.
    # For the retry, point Maven Central at Google's documented mirror only.
    cat > "$stage/verified-cryptography.init.gradle" <<EOFCRYPTO || return 1
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
gradle.settingsEvaluated { settings ->
    def central = ['https://repo.maven.apache.org/maven2', 'https://repo.maven.apache.org/maven2/']
    [settings.pluginManagement.repositories, settings.dependencyResolutionManagement.repositories].each { repositories ->
        repositories.withType(org.gradle.api.artifacts.repositories.MavenArtifactRepository).each { repository ->
            if (repository.url.toString() in central) {
                repository.url = java.net.URI.create('https://maven-central.storage-download.googleapis.com/maven2/')
            }
        }
    }
}
EOFCRYPTO
    mv "$stage/$phase.raw" "$stage/$phase-repository-failure.raw" &&
      mv "$report/$phase.json" "$report/$phase-repository-failure.json" &&
      mv "$report/$phase.resources.tsv" "$report/$phase-repository-failure.resources.tsv"
  else
    return 1
  fi
}

if [[ "$POS_SUITE" == 'installed-cashier' || "$POS_SUITE" == 'installed-cashier-paid' || "$POS_SUITE" == 'installed-cashier-paid-control' ]]; then
  test="$stage/inputs/runner/InstalledCashierFinalizeLatencySupportingTest.kt"
  target='apps/android/src/androidTest/kotlin/com/ukkera/pos/android/independent/coldoriginalstartv1/InstalledCashierFinalizeLatencySupportingTest.kt'
  [[ -f "$test" && ! -e "$target" ]]
  install -m0600 "$test" "$target"
  # An isolated supporting selector may bind its declared synthetic policy to
  # the original helper. The sealed patch applies only during this APK build.
  fixture_patch="$stage/inputs/runner/installed-cashier-fixture.patch"
  if [[ -f "$fixture_patch" ]]; then
    fixture_helper="$(dirname "$target")/NativeColdOriginalClient.kt"
    git apply --numstat "$fixture_patch" | awk -F '\t' -v expected="$fixture_helper" '
      NF != 3 || $1 !~ /^[0-9]+$/ || $2 !~ /^[0-9]+$/ || $3 != expected { invalid=1 }
      END { exit (invalid || NR != 1) }
    '
    git apply --check "$fixture_patch"
    git apply "$fixture_patch"
    sha256sum "$fixture_patch" | cut -d' ' -f1 > "$report/installed-cashier-fixture.sha256"
  fi
  sha256sum "$target" | cut -d' ' -f1 > "$report/installed-cashier-test.sha256"
  "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" 'ndk;27.2.12479018' 'cmake;3.22.1'
  # The two fresh synthetic APKs use the same disposable guest signer. Never
  # read or export packaging keys, APKs or native data in this job.
  export UKKERA_POS_TEST_KEYSTORE="$stage/installed-cashier.p12"
  export UKKERA_POS_TEST_KEYSTORE_PASSWORD="$(openssl rand -hex 24)"
  trap 'if [[ -f "$UKKERA_POS_TEST_KEYSTORE" ]]; then unlink -- "$UKKERA_POS_TEST_KEYSTORE"; fi' EXIT
  keytool -genkeypair -storetype PKCS12 -keystore "$UKKERA_POS_TEST_KEYSTORE" -storepass:env UKKERA_POS_TEST_KEYSTORE_PASSWORD \
    -alias ukkera-pos-local-testing -keyalg RSA -keysize 2048 -validity 1 -dname 'CN=Disposable POS qualification'
  build=(:apps:android:assembleLocalTesting :apps:android-runtime-tests:assembleLocalTesting)
  result=0
  python3 "$here/watched.py" "$report" installed-compile 55 "${gradle[@]}" "${build[@]}" || result=$?
  if [[ "$result" == 1 ]] && prepare_verified_plugin installed-compile; then
    result=0
    python3 "$here/watched.py" "$report" installed-compile 55 "${gradle[@]}" \
      -I "$stage/verified-cryptography.init.gradle" "${build[@]}" || result=$?
  fi
  exit "$result"
fi
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
if [[ "$POS_SUITE" == 'checkout-reopen' ]]; then
  mkdir -m700 "$stage/reopen-probe"
  bash "$stage/inputs/runner/run-hosted-reopen-probe.sh" "$stage/inputs/runner" "$here" \
    "$stage/reopen-probe" "$POS_CHECKOUT_PRIOR_SALES" 240 "${gradle[@]}" > "$report/reopen-checkout.json.tmp"
  mv "$report/reopen-checkout.json.tmp" "$report/reopen-checkout.json"
  exit 0
fi
if [[ "$POS_SUITE" == 'checkout-checkpoint' ]]; then
  tests=(:core:database:jvmTest
    --tests '*RuntimeHistoryCheckpointSupportingTest'
    --tests '*OwnedRoomStartupNativeSupportingTest'
    --tests '*RuntimeStockProjectionChangesSupportingTest')
  tree_test="$source/core/database/src/jvmTest/kotlin/com/ukkera/pos/database/RuntimeStateTreeV1SupportingTest.kt"
  if [[ -f "$tree_test" ]]; then
    tests+=(--tests '*RuntimeStateTreeV1SupportingTest' --tests '*LanBusinessTransactionIntegrationTest')
  fi
  result=0
  python3 "$here/watched.py" "$report" checkout-checkpoint 25 bash "$here/runner-owned-temp.sh" "${gradle[@]}" "${tests[@]}" || result=$?
  if [[ "$result" == 1 ]] && prepare_verified_plugin checkout-checkpoint; then
    result=0
    python3 "$here/watched.py" "$report" checkout-checkpoint 25 bash "$here/runner-owned-temp.sh" "${gradle[@]}" \
      -I "$stage/verified-cryptography.init.gradle" "${tests[@]}" || result=$?
  fi
  python3 "$here/results.py" checkout-checkpoint "$stage" "$source"
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
            if (p.findProperty('posColdPassDiagnostics') == 'true')
                environment 'POS_HISTORY_PROFILE', '1'
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
  if [[ "$POS_SUITE" == 'cold-pass-control' && "${POS_HISTORY_PROFILE:-0}" == 1 ]]; then
    control_options+=(-PposColdPassDiagnostics=true)
  fi
  control_tasks=(:adapters:app-gateway:jvmTest --tests '*PaidRetailReceiptColdNativeSupportingTest')
  workspace=0
  if [[ "$POS_SUITE" == 'cold-pass-control' && "${POS_CHECK_FLOOR_BATCH:-0}" == 1 ]]; then
    workspace=1
    control_tasks+=(:core:database:jvmTest --tests '*NativeColdHistoryWorkspaceSupportingTest'
      :core:database:compileAndroidMain)
  fi
  python3 "$here/watched.py" "$report" cold-payment 30 bash "$here/runner-owned-temp.sh" "${gradle[@]}" \
    -I "$stage/cold-payment.init.gradle" "${control_options[@]}" "${control_tasks[@]}" || result=$?
  if [[ "$workspace" == 1 && "$result" == 1 ]] && prepare_verified_plugin cold-payment; then
    result=0
    python3 "$here/watched.py" "$report" cold-payment 30 bash "$here/runner-owned-temp.sh" "${gradle[@]}" \
      -I "$stage/verified-cryptography.init.gradle" -I "$stage/cold-payment.init.gradle" \
      "${control_options[@]}" "${control_tasks[@]}" || result=$?
  fi
  if [[ "$workspace" == 1 ]]; then python3 "$here/results.py" workspace-control "$stage" "$source"; fi
  python3 "$here/results.py" "$POS_SUITE" "$stage" "$source"
  exit "$result"
  ;;
esac
if [[ "$POS_SUITE" == 'history-source-control' || "$POS_SUITE" == 'history-event-stream-control' ]]; then
  cat > "$stage/history-source-control.init.gradle" <<'EOFSOURCE'
gradle.beforeProject { p ->
    if (p.path == ':adapters:app-gateway') {
        p.tasks.withType(Test).configureEach {
            environment 'POS_HISTORY_PROFILE', '1'
            testLogging { exceptionFormat 'full' }
        }
    }
}
EOFSOURCE
  result=0
  tests=(:adapters:app-gateway:jvmTest --tests '*SupplierCashAncestryNativeSupportingTest')
  python3 "$here/watched.py" "$report" history-source-control 25 bash "$here/runner-owned-temp.sh" "${gradle[@]}" \
    -I "$stage/history-source-control.init.gradle" "${tests[@]}" || result=$?
  if [[ "$result" == 1 ]] && prepare_verified_plugin history-source-control; then
    result=0
    python3 "$here/watched.py" "$report" history-source-control 25 bash "$here/runner-owned-temp.sh" "${gradle[@]}" \
      -I "$stage/verified-cryptography.init.gradle" -I "$stage/history-source-control.init.gradle" "${tests[@]}" || result=$?
  fi
  python3 "$here/results.py" "$POS_SUITE" "$stage" "$source"
  exit "$result"
fi
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
  regression=(:adapters:inventory-store:jvmTest :adapters:sales-store:jvmTest
    :core:database:jvmTest --tests '*NativeOriginalSourceRootSupportingTest' --tests '*LanBusinessTransactionIntegrationTest')
  python3 "$here/watched.py" "$report" payment-regression 95 bash "$here/runner-owned-temp.sh" "${gradle[@]}" --continue \
    -I "$stage/native-regression-diagnostics.init.gradle" "${regression[@]}" || result=$?
  if [[ "$result" == 1 ]] && prepare_verified_plugin payment-regression; then
    result=0
    python3 "$here/watched.py" "$report" payment-regression 95 bash "$here/runner-owned-temp.sh" "${gradle[@]}" --continue \
      -I "$stage/verified-cryptography.init.gradle" -I "$stage/native-regression-diagnostics.init.gradle" \
      "${regression[@]}" || result=$?
  fi
  python3 "$here/results.py" payment-regression "$stage" "$source"
  exit "$result"
fi

if [[ "$POS_SUITE" == 'catalog-deposit-draft' ]]; then
  test -f "$stage/inputs/CATALOG-FREEZE-TREE"
  cp "$stage/inputs/CATALOG-FREEZE-TREE" "$report/"
  cat > "$stage/catalog-draft-test-logging.init.gradle" <<'EOFTESTLOG'
gradle.beforeProject { p ->
    p.tasks.withType(Test).configureEach {
        testLogging {
            events 'failed'
            exceptionFormat 'full'
        }
    }
}
EOFTESTLOG
  result=0
  checks=(:adapters:app-gateway:jvmTest
    --tests '*DesktopDepositPublicationSupportingTest'
    --tests '*DesktopMasterEmployeeConsoleNativeTest'
    --tests '*DesktopMasterEmployeeDataTest'
    --tests '*CatalogProductVersionFrozenMappingTest'
    :adapters:catalog-store:jvmTest --tests '*ContainerDepositPublicationTest'
    :ui:app:jvmTest --tests '*CatalogUnitAuthoring*Test'
    --tests '*CatalogProductVersionFrozenEditorTest'
    --tests '*ReturnStructuredPromiseFrozenEditorSlicesTest'
    --tests '*CompanySkuDepositEditorSupportingTest'
    :apps:desktop:compileKotlin :apps:android:compileLocalTestingKotlin)
  gradle+=(-I "$stage/catalog-draft-test-logging.init.gradle")
  python3 "$here/watched.py" "$report" catalog-deposit-draft 95 bash "$here/runner-owned-temp.sh" \
    "${gradle[@]}" --continue "${checks[@]}" || result=$?
  if [[ "$result" == 1 ]] && prepare_verified_plugin catalog-deposit-draft; then
    result=0
    python3 "$here/watched.py" "$report" catalog-deposit-draft 95 bash "$here/runner-owned-temp.sh" \
      "${gradle[@]}" --continue -I "$stage/verified-cryptography.init.gradle" "${checks[@]}" || result=$?
  fi
  python3 "$here/results.py" catalog-deposit-draft "$stage" "$source"
  exit "$result"
fi

count=1000
if [[ "$POS_SUITE" == 'paid-prefix' ]]; then count=8; fi
if [[ "$POS_SUITE" == 'paid-profile' ]]; then
  case "$POS_PAID_PROFILE_COUNT" in 5|10|25|32|35|50|100|250|500) count=$POS_PAID_PROFILE_COUNT ;; *) exit 2 ;; esac
fi
export UKKERA_CHB_COUNT="$count"
expected=$((12 + 7 * count + 5))
export UKKERA_CHB_RUNNER="$stage/inputs/runner"
if [[ "${POS_MEASURE_CHECKED_PREFIX:-0}" == 1 || "${POS_RESTART_ON_CHECKED_PREFIX:-0}" == 1 ]]; then
  test "$POS_SUITE" = paid-profile
  grep -Fxq "installed-native-read-only-startup-v1" "$UKKERA_CHB_RUNNER/READ-ONLY-MEASUREMENT"
fi
if [[ "${POS_RESTART_ON_CHECKED_PREFIX:-0}" == 1 ]]; then
  [[ "$POS_SUITE" == 'paid-profile' && "$GITHUB_RUN_ATTEMPT" == *-comparison ]]
  export UKKERA_CHB_ATTEMPT="$HOME/.pos-frozen-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT%-comparison}"
  grep -Fxq "CHB_NATIVE_CLOSE seeded-$count" "$UKKERA_CHB_ATTEMPT/gradle.log"
  grep -Fxq "CHB_NATIVE_CLOSE seed" "$UKKERA_CHB_ATTEMPT/gradle.log"
  grep -Fxq "CHB_NATIVE_CLOSE restart" "$UKKERA_CHB_ATTEMPT/gradle.log"
  test "$(< "$UKKERA_CHB_ATTEMPT/EXIT")" = 0
else
  export UKKERA_CHB_ATTEMPT="$HOME/.pos-frozen-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
  mkdir -m700 "$UKKERA_CHB_ATTEMPT"
  printf '%s\n%s\n' "$(< "$stage/inputs/POS-REVISION")" "$(< "$stage/inputs/POS-TREE")" > "$UKKERA_CHB_ATTEMPT/SOURCE"
fi
cc -shared -fPIC -O2 -Wall -Wextra "$UKKERA_CHB_RUNNER/controlled-utc.c" -o "$UKKERA_CHB_RUNNER/controlled-utc.so"
if [[ "${POS_RESTART_ON_CHECKED_PREFIX:-0}" != 1 ]]; then
  for phase in seed restart; do
    printf '%s 0\n' "$(date -ud '2026-08-01T07:59:00Z' +%s)" > "$UKKERA_CHB_ATTEMPT/$phase.utc"
  done
fi
# Measure compilation separately from this supporting run's fixed runtime budget.
# The same resources guard compilation and both native invocations.
if [[ "$POS_SUITE" == 'paid-profile' ]]; then
  # The frozen POS clock stalls JVM startup recording. Observe this JVM externally.
  if grep -Fq 'StartFlightRecording' "$UKKERA_CHB_RUNNER/paid-rung.init.gradle"; then
    echo "The paid profile requires external sampling with its fixed test clock."
    exit 2
  fi
  compile_tasks=(:core:database:jvmTestClasses)
  instruction_checks=0
  profile_compile_prefix=()
  if [[ "${POS_PAID_INSTRUCTION_CHECKS:-0}" == 1 &&
        ( -z "${EXPECTED_COMPARISON:-}" || "$GITHUB_RUN_ATTEMPT" == *-comparison ) ]]; then
    instruction_checks=1
    compile_tasks+=(:core:database:jvmTest
      --tests '*PhysicalInstructionDependenciesSupportingTest'
      --tests '*StockRetainedAuthorizationNativeSupportingTest'
      --tests '*RuntimeStockProjectionChangesSupportingTest'
      --tests '*LanBusinessTransactionIntegrationTest')
    profile_compile_prefix=(bash "$here/runner-owned-temp.sh" env POS_HISTORY_PROFILE=0
      "UKKERA_CHB_RUNNER=$UKKERA_CHB_RUNNER" "UKKERA_CHB_ATTEMPT=$UKKERA_CHB_ATTEMPT"
      "UKKERA_CHB_COUNT=$UKKERA_CHB_COUNT")
  fi
  compile_result=0
  if [[ "${POS_CHECK_FLOOR_BATCH:-0}" == 1 ]]; then
    compile_tasks+=(:core:database:jvmTest --tests '*NativeColdHistoryWorkspaceSupportingTest.nativeKeyFloorBatchPreservesExactOrderAndStrictCut*')
  fi
  python3 "$here/watched.py" "$report" profile-compile 15 "${profile_compile_prefix[@]}" "${gradle[@]}" \
    -I "$UKKERA_CHB_RUNNER/paid-rung.init.gradle" "${compile_tasks[@]}" || compile_result=$?
  if [[ "$compile_result" == 1 ]] && prepare_verified_plugin profile-compile; then
    compile_result=0
    python3 "$here/watched.py" "$report" profile-compile 15 "${profile_compile_prefix[@]}" "${gradle[@]}" \
      -I "$stage/verified-cryptography.init.gradle" -I "$UKKERA_CHB_RUNNER/paid-rung.init.gradle" \
      "${compile_tasks[@]}" || compile_result=$?
  fi
  if [[ "${POS_CHECK_FLOOR_BATCH:-0}" == 1 ]]; then
    python3 "$here/results.py" floor-batch-control "$stage" "$source"
  fi
  if [[ "$instruction_checks" == 1 ]]; then
    python3 "$here/results.py" instruction-regression "$stage" "$source"
  fi
  test "$compile_result" = 0
  if [[ -f "$stage/verified-cryptography.init.gradle" ]]; then
    gradle+=(-I "$stage/verified-cryptography.init.gradle")
  fi
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
    trap 'touch "$stage/seed.stack.done" "$stage/restart.stack.done" "$stage/profile-reopen.stack.done" "$stage/profile-reopen.profile.done"' EXIT
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
measure_checked_prefix() {
  # A protected user restart appends live authorization history after native
  # startup. Compare fresh native owners without signing in on either source.
  local phase=profile-reopen
  local outcome=0
  python3 "$here/closed-paid-prefix.py" "$UKKERA_CHB_ATTEMPT" "$count" > "$stage/prefix-before.json"
  if [[ "${POS_RESTART_ON_CHECKED_PREFIX:-0}" == 1 ]]; then
    cmp "$RUNNER_TEMP/../pos-qualification/prefix-after.json" "$stage/prefix-before.json"
  fi
  cp "$UKKERA_CHB_ATTEMPT/restart.utc" "$UKKERA_CHB_ATTEMPT/$phase.utc"
  if [[ -f "$UKKERA_CHB_ATTEMPT/$phase.pid" ]]; then
    mv "$UKKERA_CHB_ATTEMPT/$phase.pid" "$stage/control-$phase.pid"
  fi
  observe_profile "$phase"
  python3 "$here/watched.py" "$report" "$phase" "$phase_budget" "${gradle[@]}" \
    -I "$UKKERA_CHB_RUNNER/paid-rung.init.gradle" :core:database:chbFrozenPaidReadOnly || outcome=$?
  finish_profile "$phase"
  python3 "$here/results.py" frozen "$stage" "$UKKERA_CHB_ATTEMPT"
  if (( outcome != 0 )); then return "$outcome"; fi
  grep -Fxq "CHB_NATIVE_HISTORY_OPEN_ENTERED" "$stage/$phase.raw"
  grep -Fxq "CHB_NATIVE_HISTORY_OPERATIONAL" "$stage/$phase.raw"
  grep -Fxq "CHB_READ_ONLY_NATIVE_CLOSE" "$stage/$phase.raw"
  python3 "$here/closed-paid-prefix.py" "$UKKERA_CHB_ATTEMPT" "$count" > "$stage/prefix-after.json"
  cmp "$stage/prefix-before.json" "$stage/prefix-after.json"
  printf '{"same_closed_installation":true,"mode":"read-only-native-startup","sales":%s,"originals_in_control_census":%s}\n' \
    "$count" "$expected" > "$report/shared-installation.json"
}
if [[ "${POS_RESTART_ON_CHECKED_PREFIX:-0}" == 1 ]]; then
  measure_checked_prefix
  exit 0
fi
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
if [[ "${POS_MEASURE_CHECKED_PREFIX:-0}" == 1 ]]; then measure_checked_prefix; fi
