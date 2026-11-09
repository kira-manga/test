#!/usr/bin/env bash
# Build the POS iOS simulator app and an unsigned device archive on macOS.
#
# This intentionally uses CODE_SIGNING_ALLOWED=NO for both products. The
# simulator app can be installed with simctl; the device archive has no
# provisioning profile and is not installable on physical hardware.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
POS_ROOT="${POS_ROOT:-$REPO_ROOT/apps/pos}"
OUT_DIR="$REPO_ROOT/build/pos-artifacts/ios"
WORK_DIR="$REPO_ROOT/build/pos-artifacts/work/ios"
SIMULATOR_SMOKE="${SIMULATOR_SMOKE:-1}"
RUN_IOS_KOTLIN_TESTS="${RUN_IOS_KOTLIN_TESTS:-0}"
SOURCE_REVISION="${SOURCE_REVISION:-}"

usage() {
  cat <<'EOF'
usage: build-ios-artifacts.sh [options]

options:
  --pos-root PATH       POS Gradle build directory (default: <repo>/apps/pos)
  --out PATH            artifact payload directory (default: <repo>/build/pos-artifacts/ios)
  --work-dir PATH       disposable build/diagnostics directory
  --skip-smoke          build and package without booting a simulator
  --run-ios-tests       run focused Kotlin/Native simulator tests first
  --source-revision SHA original source revision to record (default: git HEAD)
  -h, --help            show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --pos-root) POS_ROOT="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    --work-dir) WORK_DIR="$2"; shift 2 ;;
    --skip-smoke) SIMULATOR_SMOKE=0; shift ;;
    --run-ios-tests) RUN_IOS_KOTLIN_TESTS=1; shift ;;
    --source-revision) SOURCE_REVISION="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "error: unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

fail() { echo "error: $*" >&2; exit 2; }

[[ "$(uname -s)" == "Darwin" ]] || fail "iOS builds require macOS with Xcode."
[[ -d "$POS_ROOT" ]] || fail "POS root does not exist: $POS_ROOT"
[[ -f "$POS_ROOT/gradlew" ]] || fail "POS Gradle wrapper not found: $POS_ROOT/gradlew"

PROJECT="$POS_ROOT/apps/ios/UkkeraPos.xcodeproj"
IOS_DIR="$POS_ROOT/apps/ios"
[[ -d "$PROJECT" ]] || fail "Xcode project not found: $PROJECT"
[[ -f "$IOS_DIR/xcode-build-framework.sh" ]] || fail "Xcode framework build phase is missing: $IOS_DIR/xcode-build-framework.sh"

case "$OUT_DIR" in
  ""|"/"|"$REPO_ROOT"|"$HOME") fail "refusing unsafe output directory: $OUT_DIR" ;;
esac
case "$OUT_DIR" in /*|[A-Za-z]:/*) ;; *) fail "output directory must be absolute: $OUT_DIR" ;; esac
case "$WORK_DIR" in
  ""|"/"|"$REPO_ROOT"|"$HOME") fail "refusing unsafe work directory: $WORK_DIR" ;;
esac
case "$WORK_DIR" in /*|[A-Za-z]:/*) ;; *) fail "work directory must be absolute: $WORK_DIR" ;; esac

PYTHON="${PYTHON:-}"
if [[ -z "$PYTHON" ]]; then
  if command -v python3 >/dev/null 2>&1; then
    PYTHON=python3
  elif command -v python >/dev/null 2>&1; then
    PYTHON=python
  else
    fail "python3 is required for simulator selection and artifact manifests."
  fi
fi
MANIFEST_TOOL="$REPO_ROOT/scripts/pos/artifact_manifest.py"
SANITIZER="$REPO_ROOT/scripts/pos/sanitize_diagnostics.py"
SELECT_SIMULATOR="$REPO_ROOT/scripts/pos/ios/select-simulator.py"
[[ -f "$MANIFEST_TOOL" ]] || fail "manifest tool not found: $MANIFEST_TOOL"
[[ -f "$SANITIZER" ]] || fail "diagnostic sanitizer not found: $SANITIZER"
[[ -f "$SELECT_SIMULATOR" ]] || fail "simulator selector not found: $SELECT_SIMULATOR"

# Xcode does not inherit the caller's shell. Use the selected developer
# directory, but validate the actual installed Xcode rather than a version guess.
if [[ -n "${XCODE_DEVELOPER_DIR:-}" ]]; then
  [[ -d "$XCODE_DEVELOPER_DIR" ]] || fail "XCODE_DEVELOPER_DIR does not exist: $XCODE_DEVELOPER_DIR"
  [[ "$(xcode-select -p)" == "$XCODE_DEVELOPER_DIR" ]] || \
    fail "Xcode developer directory is not selected. Run: sudo xcode-select -s \"$XCODE_DEVELOPER_DIR\""
fi
command -v xcodebuild >/dev/null 2>&1 || fail "xcodebuild is not available."
XCODE_VERSION_OUTPUT="$(xcodebuild -version)"
XCODE_VERSION="$(printf '%s\n' "$XCODE_VERSION_OUTPUT" | awk '/^Xcode /{print $2; exit}')"
XCODE_BUILD="$(printf '%s\n' "$XCODE_VERSION_OUTPUT" | awk '/^Build version/{print $3; exit}')"
XCODE_MAJOR="${XCODE_VERSION%%.*}"
case "$XCODE_MAJOR" in
  ''|*[!0-9]*) fail "could not determine Xcode version from: $XCODE_VERSION_OUTPUT" ;;
esac
[[ "$XCODE_MAJOR" -ge 16 ]] || fail "Xcode 16 or newer is required; installed: $XCODE_VERSION ($XCODE_BUILD)"
[[ "$(uname -m)" == "arm64" ]] || fail "POS declares iosSimulatorArm64 only; use an arm64 macOS runner (macos-26)."
SDK_SIMULATOR="$(xcrun --sdk iphonesimulator --show-sdk-version)"
SDK_DEVICE="$(xcrun --sdk iphoneos --show-sdk-version)"
[[ -n "$SDK_SIMULATOR" && -n "$SDK_DEVICE" ]] || fail "iPhone SDKs are not available to xcodebuild."

java_major() {
  "$1/bin/java" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}' | cut -d. -f1
}
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]] && [[ "$(java_major "$JAVA_HOME")" == "21" ]]; then
  :
else
  JAVA_HOME=""
  for candidate in "${JAVA_HOME_21_ARM64:-}" "${JAVA_HOME_21_arm64:-}" "${JAVA_HOME_21_X64:-}" "${JAVA_HOME_21_x64:-}"; do
    if [[ -n "$candidate" && -x "$candidate/bin/java" ]] && [[ "$(java_major "$candidate")" == "21" ]]; then
      JAVA_HOME="$candidate"
      break
    fi
  done
  if [[ -z "$JAVA_HOME" ]] && [[ -x /usr/libexec/java_home ]] && /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
    JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  fi
fi
[[ -n "$JAVA_HOME" && -x "$JAVA_HOME/bin/java" ]] || fail "JDK 21 is required (set JAVA_HOME or install Temurin 21)."
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
if [[ -z "${GRADLE_OPTS:-}" ]]; then
  export GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8"
fi

if [[ -z "$SOURCE_REVISION" ]]; then
  SOURCE_REVISION="$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || true)"
fi
[[ -n "$SOURCE_REVISION" ]] || SOURCE_REVISION="not-recorded"
AUX_SNAPSHOT_REVISION="$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || true)"
[[ -n "$AUX_SNAPSHOT_REVISION" ]] || AUX_SNAPSHOT_REVISION="not-recorded"

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"
rm -rf "$WORK_DIR"
DIAG="$WORK_DIR/diagnostics"
mkdir -p "$DIAG" "$WORK_DIR/result-bundles"

SMOKE_STATUS="skipped"
SMOKE_FAILED=0
BUILD_STATUS="running"
FINALIZED=0
SIM_UDID=""
SIM_RUNTIME=""
SIM_DEVICE=""
CREATED_SIM=0
BUNDLE_ID="com.ukkera.pos.local"

cleanup() {
  status=$?
  if [[ -n "$SIM_UDID" ]]; then
    xcrun simctl terminate "$SIM_UDID" "$BUNDLE_ID" >/dev/null 2>&1 || true
    xcrun simctl shutdown "$SIM_UDID" >/dev/null 2>&1 || true
    if [[ "$CREATED_SIM" == "1" ]]; then
      xcrun simctl delete "$SIM_UDID" >/dev/null 2>&1 || true
    fi
  fi
  if [[ "$FINALIZED" == "0" && -d "$DIAG" ]]; then
    BUILD_STATUS="failed"
    finalize_artifacts || true
  fi
  exit "$status"
}
trap cleanup EXIT

finalize_artifacts() {
  BUILD_STATUS="${BUILD_STATUS:-unknown}"
  mkdir -p "$OUT_DIR/diagnostics"
  cp -R "$DIAG/." "$OUT_DIR/diagnostics/" 2>/dev/null || true
  "$PYTHON" "$SANITIZER" --root "$OUT_DIR/diagnostics" || true

  cat > "$OUT_DIR/SOURCE-REVISION.txt" <<EOF
original_source_revision=$SOURCE_REVISION
auxiliary_snapshot_revision=$AUX_SNAPSHOT_REVISION
workflow=${GITHUB_WORKFLOW:-local}
run_id=${GITHUB_RUN_ID:-local}
run_attempt=${GITHUB_RUN_ATTEMPT:-local}
EOF

  cat > "$OUT_DIR/README-ARTIFACTS.txt" <<EOF
Ukkera POS iOS native artifacts (private/local qualification)

build_status: $BUILD_STATUS
source_revision: $SOURCE_REVISION
aux_snapshot_revision: $AUX_SNAPSHOT_REVISION
xcode: $XCODE_VERSION ($XCODE_BUILD)
iphonesimulator_sdk: $SDK_SIMULATOR
iphoneos_sdk: $SDK_DEVICE
simulator: ${SIM_DEVICE:-none} / ${SIM_RUNTIME:-none}
simulator_smoke: $SMOKE_STATUS

Contents:
- UkkeraPos-simulator.app.zip: unsigned simulator app. Install only in an iOS Simulator.
- UkkeraPos-unsigned-device.xcarchive.zip: device archive built with
  CODE_SIGNING_ALLOWED=NO. It has no provisioning profile and is NOT
  installable on a physical iPhone or iPad. Never present it as a signed app.
- diagnostics/: Xcode/build/launch logs, screenshots and signing state.
- SHA256SUMS and manifest.json: hashes and build metadata.

No credentials, provisioning profiles, signing keys, native databases or Gradle
caches are intentionally staged. App launch is not operational Master completion
and this package is not owner acceptance.
EOF

  META=(
    "--metadata" "source_revision=$SOURCE_REVISION"
    "--metadata" "aux_snapshot_revision=$AUX_SNAPSHOT_REVISION"
    "--metadata" "build_status=$BUILD_STATUS"
    "--metadata" "xcode_version=$XCODE_VERSION"
    "--metadata" "xcode_build=$XCODE_BUILD"
    "--metadata" "iphonesimulator_sdk=$SDK_SIMULATOR"
    "--metadata" "iphoneos_sdk=$SDK_DEVICE"
    "--metadata" "simulator_smoke=$SMOKE_STATUS"
    "--metadata" "simulator_device=$SIM_DEVICE"
    "--metadata" "simulator_runtime=$SIM_RUNTIME"
    "--metadata" "device_archive=unsigned-not-installable"
    "--metadata" "workflow=${GITHUB_WORKFLOW:-local}"
    "--metadata" "run_id=${GITHUB_RUN_ID:-local}"
    "--metadata" "run_attempt=${GITHUB_RUN_ATTEMPT:-local}"
  )
  "$PYTHON" "$MANIFEST_TOOL" --root "$OUT_DIR" --label "ios-simulator-unsigned-device" "${META[@]}"
  FINALIZED=1
}

cat > "$DIAG/environment.txt" <<EOF
uname: $(uname -s -m -r)
macos: $(sw_vers -productVersion) ($(sw_vers -buildVersion))
cpus: $(sysctl -n hw.ncpu)
xcode-select: $(xcode-select -p)
xcode: $XCODE_VERSION ($XCODE_BUILD)
swift: $(swift --version 2>&1 | head -n 1)
java: $(java -version 2>&1 | head -n 1)
java_home: $JAVA_HOME
iphonesimulator_sdk: $SDK_SIMULATOR
iphoneos_sdk: $SDK_DEVICE
source_revision: $SOURCE_REVISION
aux_snapshot_revision: $AUX_SNAPSHOT_REVISION
EOF
xcodebuild -version > "$DIAG/xcode-version.txt" 2>&1
xcodebuild -showsdks > "$DIAG/xcode-sdks.txt" 2>&1
xcodebuild -list -project "$PROJECT" > "$DIAG/xcodebuild-list.txt" 2>&1
xcodebuild -project "$PROJECT" -scheme UkkeraPos -configuration Debug -sdk iphonesimulator -showBuildSettings > "$DIAG/xcode-build-settings.txt" 2>&1 || true
plutil -lint "$IOS_DIR/Info.plist" > "$DIAG/plutil-info-plist.txt" 2>&1
( cd "$POS_ROOT" && bash ./gradlew --version ) > "$DIAG/gradle-version.txt" 2>&1 || true

if [[ "$RUN_IOS_KOTLIN_TESTS" == "1" ]]; then
  echo "Running focused Kotlin/Native simulator tests..."
  mkdir -p "$DIAG/kotlin-tests"
  (
    cd "$POS_ROOT"
    bash ./gradlew \
      :core:types:iosSimulatorArm64Test \
      :core:security:iosSimulatorArm64Test \
      :domain:pricing:iosSimulatorArm64Test \
      --console=plain \
      --stacktrace \
      --no-daemon \
      --max-workers=2 \
      -Pkotlin.compiler.execution.strategy=in-process
  ) 2>&1 | tee "$DIAG/kotlin-tests.log"
  for module in core/types core/security domain/pricing; do
    if [[ -d "$POS_ROOT/$module/build/test-results/iosSimulatorArm64Test" ]]; then
      mkdir -p "$DIAG/kotlin-tests/$module"
      cp "$POS_ROOT/$module/build/test-results/iosSimulatorArm64Test/"*.xml \
        "$DIAG/kotlin-tests/$module/" 2>/dev/null || true
    fi
  done
fi

if [[ "$SIMULATOR_SMOKE" == "1" ]]; then
  echo "Creating disposable iPhone simulator..."
  SIM_JSON="$DIAG/simulator-selection.json"
  "$PYTHON" "$SELECT_SIMULATOR" > "$SIM_JSON"
  SIM_UDID="$("$PYTHON" -c 'import json,sys; print(json.load(open(sys.argv[1]))["udid"])' "$SIM_JSON")"
  SIM_RUNTIME="$("$PYTHON" -c 'import json,sys; print(json.load(open(sys.argv[1]))["runtime"])' "$SIM_JSON")"
  SIM_DEVICE="$("$PYTHON" -c 'import json,sys; print(json.load(open(sys.argv[1]))["device_name"])' "$SIM_JSON")"
  CREATED_SIM=1
  xcrun simctl list runtimes > "$DIAG/simulator-runtimes.txt" 2>&1 || true
  xcrun simctl list devicetypes > "$DIAG/simulator-devicetypes.txt" 2>&1 || true
  xcrun simctl list devices available > "$DIAG/simulator-devices.txt" 2>&1 || true
  echo "Booting simulator $SIM_UDID ($SIM_DEVICE, $SIM_RUNTIME)..."
  xcrun simctl boot "$SIM_UDID" > "$DIAG/simulator-boot.txt" 2>&1 || true
  xcrun simctl bootstatus "$SIM_UDID" -b >> "$DIAG/simulator-boot.txt" 2>&1
  DESTINATION="id=$SIM_UDID"
else
  DESTINATION="generic/platform=iOS Simulator"
fi

SIM_RESULT="$WORK_DIR/result-bundles/simulator-build.xcresult"
rm -rf "$SIM_RESULT"
echo "Building simulator app (Debug)..."
(
  set -o pipefail
  xcodebuild \
    -project "$PROJECT" \
    -scheme UkkeraPos \
    -configuration Debug \
    -destination "$DESTINATION" \
    -derivedDataPath "$WORK_DIR/derived-data-simulator" \
    -resultBundlePath "$SIM_RESULT" \
    CODE_SIGNING_ALLOWED=NO \
    CODE_SIGNING_REQUIRED=NO \
    CODE_SIGN_IDENTITY= \
    CODE_SIGN_ENTITLEMENTS= \
    DEVELOPMENT_TEAM= \
    PROVISIONING_PROFILE_SPECIFIER= \
    ONLY_ACTIVE_ARCH=YES \
    ARCHS=arm64 \
    build 2>&1 | tee "$DIAG/simulator-build.log"
)

SIM_APP="$WORK_DIR/derived-data-simulator/Build/Products/Debug-iphonesimulator/UkkeraPos.app"
[[ -d "$SIM_APP" ]] || fail "simulator app was not produced at $SIM_APP"
plutil -lint "$SIM_APP/Info.plist" > "$DIAG/plutil-simulator-app.txt" 2>&1
plutil -p "$SIM_APP/Info.plist" > "$DIAG/simulator-app-info-plist.txt" 2>&1
ACTUAL_BUNDLE_ID="$(plutil -extract CFBundleIdentifier raw -o - "$SIM_APP/Info.plist")"
[[ "$ACTUAL_BUNDLE_ID" == "$BUNDLE_ID" ]] || fail "unexpected simulator bundle id: $ACTUAL_BUNDLE_ID"
file "$SIM_APP/UkkeraPos" > "$DIAG/simulator-executable-file.txt" 2>&1
lipo -info "$SIM_APP/UkkeraPos" > "$DIAG/simulator-executable-lipo.txt" 2>&1 || true
lipo -info "$SIM_APP/UkkeraPos" 2>&1 | grep -q 'arm64' || fail "simulator executable is not arm64"
if codesign -dv --verbose=4 "$SIM_APP" > "$DIAG/simulator-codesign.txt" 2>&1; then
  echo "codesign_exit=0 (signed)" >> "$DIAG/simulator-codesign.txt"
else
  echo "codesign_exit=$? (unsigned, expected)" >> "$DIAG/simulator-codesign.txt"
fi
ditto -c -k --sequesterRsrc --keepParent "$SIM_APP" "$OUT_DIR/UkkeraPos-simulator.app.zip"

if [[ "$SIMULATOR_SMOKE" == "1" ]]; then
  echo "Installing and launching $BUNDLE_ID..."
  xcrun simctl install "$SIM_UDID" "$SIM_APP" > "$DIAG/simulator-install.txt" 2>&1
  xcrun simctl launch "$SIM_UDID" "$BUNDLE_ID" > "$DIAG/simulator-launch.txt" 2>&1
  LAUNCH_PID="$(awk -v bundle="$BUNDLE_ID:" '$1 == bundle && $2 ~ /^[0-9]+$/ {print $2}' "$DIAG/simulator-launch.txt")"
  [[ "$LAUNCH_PID" =~ ^[0-9]+$ && "$LAUNCH_PID" -gt 0 ]] || fail "simctl launch did not return one app PID."
  printf '%s\n' "$LAUNCH_PID" > "$DIAG/simulator-launch-pid.txt"
  # A launchd label can survive a crashed process. Observe the actual launch PID after
  # a bounded startup interval rather than accepting the registered label alone.
  sleep 5
  PROCESS_OK=0
  attempt=0
  while [[ "$attempt" -lt 15 ]]; do
    xcrun simctl spawn "$SIM_UDID" launchctl list > "$DIAG/simulator-processes.txt" 2>&1 || true
    xcrun simctl spawn "$SIM_UDID" ps -A -o pid=,comm= > "$DIAG/simulator-ps.txt" 2>&1 || true
    if awk -v pid="$LAUNCH_PID" '$1 == pid { found=1 } END { exit !found }' "$DIAG/simulator-ps.txt"; then
      PROCESS_OK=1; break
    fi
    if awk -v pid="$LAUNCH_PID" -v bundle="$BUNDLE_ID" '$1 == pid && index($3,bundle) { found=1 } END { exit !found }' "$DIAG/simulator-processes.txt"; then
      PROCESS_OK=1; break
    fi
    attempt=$((attempt + 1))
    sleep 2
  done
  if [[ "$PROCESS_OK" == "0" ]]; then
    SMOKE_STATUS="failed"
    SMOKE_FAILED=1
    xcrun simctl spawn "$SIM_UDID" log show --last 5m --style compact \
      --predicate 'process == "UkkeraPos"' > "$DIAG/simulator-app-log.txt" 2>&1 || true
    find "$HOME/Library/Logs/DiagnosticReports" -type f -name 'UkkeraPos*' -mmin -15 \
      -exec cp {} "$DIAG/" \; 2>/dev/null || true
    echo "warning: simulator app process was not visible within 30 seconds; continuing to package the device archive." >&2
  else
    SMOKE_STATUS="passed"
  fi
  xcrun simctl io "$SIM_UDID" screenshot "$DIAG/simulator-screenshot.png" > "$DIAG/simulator-screenshot.txt" 2>&1 || true
  xcrun simctl spawn "$SIM_UDID" log show --last 5m --style compact \
    --predicate 'process == "UkkeraPos"' 2>&1 | tail -n 4000 > "$DIAG/simulator-app-log.txt" || true
  xcrun simctl terminate "$SIM_UDID" "$BUNDLE_ID" > "$DIAG/simulator-terminate.txt" 2>&1 || true
fi

DEVICE_RESULT="$WORK_DIR/result-bundles/device-archive.xcresult"
ARCHIVE="$WORK_DIR/UkkeraPos-unsigned.xcarchive"
rm -rf "$DEVICE_RESULT" "$ARCHIVE"
echo "Archiving unsigned device app (Release)..."
(
  set -o pipefail
  xcodebuild \
    -project "$PROJECT" \
    -scheme UkkeraPos \
    -configuration Release \
    -destination "generic/platform=iOS" \
    -archivePath "$ARCHIVE" \
    -derivedDataPath "$WORK_DIR/derived-data-device" \
    -resultBundlePath "$DEVICE_RESULT" \
    CODE_SIGNING_ALLOWED=NO \
    CODE_SIGNING_REQUIRED=NO \
    CODE_SIGN_IDENTITY= \
    CODE_SIGN_ENTITLEMENTS= \
    DEVELOPMENT_TEAM= \
    PROVISIONING_PROFILE_SPECIFIER= \
    ONLY_ACTIVE_ARCH=NO \
    ARCHS=arm64 \
    archive 2>&1 | tee "$DIAG/device-archive.log"
)

[[ -d "$ARCHIVE" ]] || fail "unsigned device archive was not produced at $ARCHIVE"
ARCHIVE_APP="$ARCHIVE/Products/Applications/UkkeraPos.app"
[[ -d "$ARCHIVE_APP" ]] || fail "archive does not contain UkkeraPos.app"
plutil -lint "$ARCHIVE/Info.plist" > "$DIAG/plutil-device-archive.txt" 2>&1
plutil -p "$ARCHIVE/Info.plist" > "$DIAG/device-archive-info-plist.txt" 2>&1
plutil -p "$ARCHIVE_APP/Info.plist" > "$DIAG/device-app-info-plist.txt" 2>&1
ARCHIVE_BUNDLE_ID="$(plutil -extract CFBundleIdentifier raw -o - "$ARCHIVE_APP/Info.plist")"
[[ "$ARCHIVE_BUNDLE_ID" == "$BUNDLE_ID" ]] || fail "unexpected device bundle id: $ARCHIVE_BUNDLE_ID"
if [[ -e "$ARCHIVE_APP/embedded.mobileprovision" ]]; then
  fail "unsigned device archive unexpectedly contains embedded.mobileprovision"
fi
echo "embedded_mobileprovision=absent" > "$DIAG/device-provisioning-state.txt"
if codesign -dv --verbose=4 "$ARCHIVE_APP" > "$DIAG/device-codesign.txt" 2>&1; then
  echo "codesign_exit=0 (signed)" >> "$DIAG/device-codesign.txt"
else
  echo "codesign_exit=$? (unsigned, expected)" >> "$DIAG/device-codesign.txt"
fi
file "$ARCHIVE_APP/UkkeraPos" > "$DIAG/device-executable-file.txt" 2>&1
lipo -info "$ARCHIVE_APP/UkkeraPos" > "$DIAG/device-executable-lipo.txt" 2>&1 || true
lipo -info "$ARCHIVE_APP/UkkeraPos" 2>&1 | grep -q 'arm64' || fail "device executable is not arm64"
ls -la "$ARCHIVE" > "$DIAG/device-archive-contents.txt" 2>&1 || true

echo "Packaging artifacts..."
mkdir -p "$OUT_DIR/diagnostics"
ditto -c -k --sequesterRsrc --keepParent "$ARCHIVE" "$OUT_DIR/UkkeraPos-unsigned-device.xcarchive.zip"
cp -R "$DIAG/." "$OUT_DIR/diagnostics/"

cat > "$DIAG/summary.txt" <<EOF
### Ukkera POS iOS artifacts

- Xcode: $XCODE_VERSION ($XCODE_BUILD), iOS simulator SDK $SDK_SIMULATOR, device SDK $SDK_DEVICE
- Simulator smoke: $SMOKE_STATUS
- Unsigned device archive: packaged; not installable
- Source revision: $SOURCE_REVISION
- Artifacts: \`UkkeraPos-simulator.app.zip\`, \`UkkeraPos-unsigned-device.xcarchive.zip\`
- Hashes: \`SHA256SUMS\`, metadata: \`manifest.json\`
EOF
cp "$DIAG/summary.txt" "$OUT_DIR/diagnostics/summary.txt"
BUILD_STATUS="success"
finalize_artifacts
echo "Artifacts ready in $OUT_DIR"
if [[ "$SMOKE_FAILED" == "1" ]]; then
  exit 1
fi
