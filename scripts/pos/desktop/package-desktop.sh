#!/usr/bin/env bash
# Package the POS Compose desktop launcher as a local, unsigned DMG or MSI.
#
# DMG is built only on macOS and MSI only on Windows. Both are unsigned local
# qualification packages; no certificate, notarization or installer identity is
# implied.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
POS_ROOT="${POS_ROOT:-$REPO_ROOT/apps/pos}"
OUT_DIR="$REPO_ROOT/build/pos-artifacts/desktop"
WORK_DIR="$REPO_ROOT/build/pos-artifacts/work/desktop"
FORMAT=""
ARCH=""
SMOKE="${DESKTOP_SMOKE:-0}"
SOURCE_REVISION="${SOURCE_REVISION:-}"

usage() {
  cat <<'EOF'
usage: package-desktop.sh [options]

options:
  --pos-root PATH       POS Gradle build directory (default: <repo>/apps/pos)
  --out PATH            artifact payload directory
  --work-dir PATH       disposable build/diagnostics directory
  --format dmg|msi      package format (default: host platform)
  --arch arm64|x64      expected host architecture (default: host)
  --smoke               launch the packaged app with --smoke-exit after packaging
  --source-revision SHA original source revision to record
  -h, --help            show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --pos-root) POS_ROOT="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    --work-dir) WORK_DIR="$2"; shift 2 ;;
    --format) FORMAT="$2"; shift 2 ;;
    --arch) ARCH="$2"; shift 2 ;;
    --smoke) SMOKE=1; shift ;;
    --source-revision) SOURCE_REVISION="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "error: unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

fail() { echo "error: $*" >&2; exit 2; }

case "$(uname -s)" in
  Darwin) HOST_OS=macos ;;
  MINGW*|MSYS*|CYGWIN*) HOST_OS=windows ;;
  *) fail "desktop packaging is supported on macOS (dmg) and Windows (msi), not $(uname -s)." ;;
esac

if [[ -z "$FORMAT" ]]; then
  if [[ "$HOST_OS" == "macos" ]]; then FORMAT=dmg; else FORMAT=msi; fi
fi
case "$FORMAT" in
  dmg)
    [[ "$HOST_OS" == "macos" ]] || fail "DMG packaging requires macOS."
    GRADLE_TASK=packageDmg
    COMPOSE_DIR=dmg
    EXT=dmg
    ;;
  msi)
    [[ "$HOST_OS" == "windows" ]] || fail "MSI packaging requires Windows."
    GRADLE_TASK=packageMsi
    COMPOSE_DIR=msi
    EXT=msi
    ;;
  *) fail "--format must be dmg or msi." ;;
esac

if [[ -z "$ARCH" ]]; then
  case "$(uname -m)" in
    arm64|aarch64) ARCH=arm64 ;;
    x86_64|amd64) ARCH=x64 ;;
    *) fail "unsupported host architecture: $(uname -m)" ;;
  esac
fi
case "$ARCH" in
  arm64|x64) ;;
  *) fail "--arch must be arm64 or x64." ;;
esac
HOST_ARCH="$(uname -m)"
case "$HOST_ARCH" in
  arm64|aarch64) HOST_ARCH=arm64 ;;
  x86_64|amd64) HOST_ARCH=x64 ;;
esac
[[ "$ARCH" == "$HOST_ARCH" ]] || fail "cross-architecture packaging is not supported: requested $ARCH on $HOST_ARCH."

DEFAULT_OUT="$REPO_ROOT/build/pos-artifacts/desktop-$HOST_OS-$ARCH"
if [[ "$OUT_DIR" == "$REPO_ROOT/build/pos-artifacts/desktop" ]]; then
  OUT_DIR="$DEFAULT_OUT"
fi
case "$OUT_DIR" in
  ""|"/"|"$REPO_ROOT"|"$HOME") fail "refusing unsafe output directory: $OUT_DIR" ;;
esac
case "$OUT_DIR" in /*|[A-Za-z]:/*) ;; *) fail "output directory must be absolute: $OUT_DIR" ;; esac
case "$WORK_DIR" in
  ""|"/"|"$REPO_ROOT"|"$HOME") fail "refusing unsafe work directory: $WORK_DIR" ;;
esac
case "$WORK_DIR" in /*|[A-Za-z]:/*) ;; *) fail "work directory must be absolute: $WORK_DIR" ;; esac

[[ -d "$POS_ROOT" ]] || fail "POS root does not exist: $POS_ROOT"
[[ -f "$POS_ROOT/gradlew" ]] || fail "POS Gradle wrapper not found: $POS_ROOT/gradlew"
[[ -f "$POS_ROOT/apps/desktop/build.gradle.kts" ]] || fail "desktop build file not found."

PYTHON="${PYTHON:-}"
if [[ -z "$PYTHON" ]]; then
  if command -v python3 >/dev/null 2>&1; then
    PYTHON=python3
  elif command -v python >/dev/null 2>&1; then
    PYTHON=python
  else
    fail "python3 is required for artifact manifests."
  fi
fi
MANIFEST_TOOL="$REPO_ROOT/scripts/pos/artifact_manifest.py"
SANITIZER="$REPO_ROOT/scripts/pos/sanitize_diagnostics.py"
[[ -f "$MANIFEST_TOOL" ]] || fail "manifest tool not found: $MANIFEST_TOOL"
[[ -f "$SANITIZER" ]] || fail "diagnostic sanitizer not found: $SANITIZER"

java_major() {
  "$1/bin/java" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}' | cut -d. -f1
}

# Git Bash receives JAVA_HOME as a Windows path. Normalize it so shell tests,
# the Gradle wrapper and jpackage resolution use the same executable.
if [[ "$HOST_OS" == "windows" && -n "${JAVA_HOME:-}" ]] && command -v cygpath >/dev/null 2>&1; then
  JAVA_HOME="$(cygpath -u "$JAVA_HOME")"
fi
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]] && [[ "$(java_major "$JAVA_HOME")" == "21" ]]; then
  :
else
  JAVA_HOME=""
  for candidate in "${JAVA_HOME_21_X64:-}" "${JAVA_HOME_21_x64:-}" "${JAVA_HOME_21_ARM64:-}" "${JAVA_HOME_21_arm64:-}"; do
    if [[ "$HOST_OS" == "windows" && -n "$candidate" ]] && command -v cygpath >/dev/null 2>&1; then
      candidate="$(cygpath -u "$candidate")"
    fi
    if [[ -n "$candidate" && -x "$candidate/bin/java" ]] && [[ "$(java_major "$candidate")" == "21" ]]; then
      JAVA_HOME="$candidate"
      break
    fi
  done
  if [[ -z "$JAVA_HOME" && "$HOST_OS" == "macos" && -x /usr/libexec/java_home ]] && /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
    JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  fi
fi
[[ -n "$JAVA_HOME" && -x "$JAVA_HOME/bin/java" ]] || fail "JDK 21 is required (set JAVA_HOME or install Temurin 21)."
[[ -x "$JAVA_HOME/bin/jpackage" ]] || fail "JDK 21 jpackage was not found under $JAVA_HOME."
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
mkdir -p "$DIAG"

PACKAGE=""
SMOKE_STATUS="skipped"
BUILD_STATUS="running"
FINALIZED=0
MOUNT_POINT=""

cleanup() {
  status=$?
  if [[ -n "$MOUNT_POINT" && -d "$MOUNT_POINT" ]]; then
    hdiutil detach "$MOUNT_POINT" >/dev/null 2>&1 || hdiutil detach "$MOUNT_POINT" -force >/dev/null 2>&1 || true
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
  if [[ -d "$POS_ROOT/apps/desktop/build/compose/logs" ]]; then
    mkdir -p "$DIAG/compose-tool-logs"
    cp -R "$POS_ROOT/apps/desktop/build/compose/logs/." "$DIAG/compose-tool-logs/" || true
  fi
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
Ukkera POS desktop package (unsigned local qualification)

build_status: $BUILD_STATUS
host: $HOST_OS / $HOST_ARCH
format: $FORMAT
source_revision: $SOURCE_REVISION
aux_snapshot_revision: $AUX_SNAPSHOT_REVISION
java: $(java -version 2>&1 | head -n 1)
package: ${PACKAGE:-not-produced}
desktop_smoke: $SMOKE_STATUS

Contents:
- The unsigned $FORMAT package (when produced).
- diagnostics/: Gradle/package verification logs and signing state.
- SHA256SUMS and manifest.json: hashes and build metadata.

This package is unsigned. macOS Gatekeeper and Windows SmartScreen can warn or
block it; that is expected for this local test package. It is not notarized or
code-signed, is not an operational Master, and is not owner acceptance.
No credentials, signing keys, native databases or Gradle caches are intentionally
staged.
EOF

  META=(
    "--metadata" "source_revision=$SOURCE_REVISION"
    "--metadata" "aux_snapshot_revision=$AUX_SNAPSHOT_REVISION"
    "--metadata" "build_status=$BUILD_STATUS"
    "--metadata" "host_os=$HOST_OS"
    "--metadata" "host_arch=$HOST_ARCH"
    "--metadata" "format=$FORMAT"
    "--metadata" "package=$PACKAGE"
    "--metadata" "desktop_smoke=$SMOKE_STATUS"
    "--metadata" "signing=unsigned-local-test"
    "--metadata" "workflow=${GITHUB_WORKFLOW:-local}"
    "--metadata" "run_id=${GITHUB_RUN_ID:-local}"
    "--metadata" "run_attempt=${GITHUB_RUN_ATTEMPT:-local}"
  )
  "$PYTHON" "$MANIFEST_TOOL" --root "$OUT_DIR" --label "desktop-$HOST_OS-$ARCH-unsigned-$FORMAT" "${META[@]}"
  FINALIZED=1
}

cat > "$DIAG/environment.txt" <<EOF
host_os: $HOST_OS
host_arch: $HOST_ARCH
uname: $(uname -s -m -r)
java_home: $JAVA_HOME
java: $(java -version 2>&1 | head -n 1)
jpackage: $("$JAVA_HOME/bin/jpackage" --version 2>&1 | head -n 1)
source_revision: $SOURCE_REVISION
aux_snapshot_revision: $AUX_SNAPSHOT_REVISION
EOF
bash "$POS_ROOT/gradlew" --version > "$DIAG/gradle-version.txt" 2>&1 || true
"$JAVA_HOME/bin/jpackage" --version > "$DIAG/jpackage-version.txt" 2>&1 || true

PKG_LOG="$DIAG/package.log"
DIST_DIR="$POS_ROOT/apps/desktop/build/compose/binaries/main/$COMPOSE_DIR"
rm -rf "$DIST_DIR"

echo "Building $FORMAT with :apps:desktop:$GRADLE_TASK ..."
(
  cd "$POS_ROOT"
  bash ./gradlew \
    "$GRADLE_TASK" \
    --console=plain \
    --stacktrace \
    --no-daemon \
    --max-workers=2 \
    -Pkotlin.compiler.execution.strategy=in-process
) 2>&1 | tee "$PKG_LOG"

PACKAGE_PATH="$(find "$DIST_DIR" -type f -name "*.$EXT" -print 2>/dev/null | sort | head -n 1)"
[[ -n "$PACKAGE_PATH" && -f "$PACKAGE_PATH" ]] || fail "no .$EXT package was produced under $DIST_DIR"
COUNT="$(find "$DIST_DIR" -type f -name "*.$EXT" -print | wc -l | tr -d ' ')"
[[ "$COUNT" == "1" ]] || fail "expected one .$EXT package, found $COUNT under $DIST_DIR"
PACKAGE="$(basename "$PACKAGE_PATH")"
cp "$PACKAGE_PATH" "$OUT_DIR/$PACKAGE"
FINAL_PACKAGE="$OUT_DIR/$PACKAGE"
echo "package=$FINAL_PACKAGE" > "$DIAG/package-path.txt"

if [[ "$FORMAT" == "dmg" ]]; then
  hdiutil verify "$FINAL_PACKAGE" > "$DIAG/dmg-verify.txt" 2>&1
  MOUNT_POINT="$WORK_DIR/dmg-mount"
  mkdir -p "$MOUNT_POINT"
  hdiutil attach -nobrowse -readonly -mountpoint "$MOUNT_POINT" "$FINAL_PACKAGE" > "$DIAG/dmg-attach.txt" 2>&1
  APP_PATH=""
  for candidate in "$MOUNT_POINT"/*.app; do
    if [[ -d "$candidate" ]]; then APP_PATH="$candidate"; break; fi
  done
  [[ -n "$APP_PATH" && -d "$APP_PATH" ]] || fail "DMG does not contain an .app bundle."
  plutil -p "$APP_PATH/Contents/Info.plist" > "$DIAG/dmg-app-info-plist.txt" 2>&1
  DMG_BUNDLE_ID="$(plutil -extract CFBundleIdentifier raw -o - "$APP_PATH/Contents/Info.plist")"
  [[ "$DMG_BUNDLE_ID" == "com.ukkera.pos.local.desktop" ]] || fail "unexpected DMG bundle id: $DMG_BUNDLE_ID"
  file "$APP_PATH/Contents/MacOS/UkkeraPOS" > "$DIAG/dmg-executable-file.txt" 2>&1
  lipo -info "$APP_PATH/Contents/MacOS/UkkeraPOS" > "$DIAG/dmg-executable-lipo.txt" 2>&1 || true
  if [[ "$ARCH" == "arm64" ]]; then EXPECTED_MACHO_ARCH=arm64; else EXPECTED_MACHO_ARCH=x86_64; fi
  lipo -info "$APP_PATH/Contents/MacOS/UkkeraPOS" 2>&1 | grep -q "$EXPECTED_MACHO_ARCH" || \
    fail "DMG executable architecture does not match $ARCH"
  if codesign -dv --verbose=4 "$APP_PATH" > "$DIAG/dmg-codesign.txt" 2>&1; then
    echo "codesign_exit=0 (signed)" >> "$DIAG/dmg-codesign.txt"
  else
    echo "codesign_exit=$? (unsigned, expected)" >> "$DIAG/dmg-codesign.txt"
  fi
  if spctl --assess --type execute --verbose=4 "$APP_PATH" > "$DIAG/dmg-spctl.txt" 2>&1; then
    echo "spctl_exit=0 (accepted)" >> "$DIAG/dmg-spctl.txt"
  else
    echo "spctl_exit=$? (not accepted, expected for unsigned local package)" >> "$DIAG/dmg-spctl.txt"
  fi
  if [[ "$SMOKE" == "1" ]]; then
    echo "Launching packaged macOS app with --smoke-exit ..."
    if "$PYTHON" - "$APP_PATH/Contents/MacOS/UkkeraPOS" "$DIAG/dmg-smoke.log" <<'PY'
import subprocess
import sys

executable, log_path = sys.argv[1], sys.argv[2]
try:
    with open(log_path, "w", encoding="utf-8") as log:
        result = subprocess.run(
            [executable, "--smoke-exit"],
            stdout=log,
            stderr=subprocess.STDOUT,
            timeout=60,
        )
    print(f"exit_code={result.returncode}")
    raise SystemExit(0 if result.returncode == 0 else 1)
except subprocess.TimeoutExpired:
    print("timeout=60s")
    raise SystemExit(124)
PY
    then
      SMOKE_STATUS="passed"
    else
      SMOKE_STATUS="failed"
      fail "packaged macOS app did not exit successfully for --smoke-exit."
    fi
  fi
  hdiutil detach "$MOUNT_POINT" > "$DIAG/dmg-detach.txt" 2>&1 || hdiutil detach "$MOUNT_POINT" -force > "$DIAG/dmg-detach.txt" 2>&1
  MOUNT_POINT=""
else
  [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* || "$(uname -s)" == CYGWIN* ]] || fail "MSI verification requires Windows."
  MSI_WIN="$(cygpath -w "$FINAL_PACKAGE")"
  MSI_PATH="$(cygpath -w "$FINAL_PACKAGE")" powershell.exe -NoProfile -NonInteractive -Command \
    '$sig = Get-AuthenticodeSignature -LiteralPath $env:MSI_PATH; "Status=" + $sig.Status; "StatusMessage=" + $sig.StatusMessage' \
    > "$DIAG/msi-signature.txt" 2>&1 || true
  EXTRACT_DIR="$WORK_DIR/msi-admin"
  mkdir -p "$EXTRACT_DIR"
  EXTRACT_WIN="$(cygpath -w "$EXTRACT_DIR")"
  MSI_LOG_WIN="$(cygpath -w "$DIAG/msi-admin-install.log")"
  if ! MSYS_NO_PATHCONV=1 msiexec.exe /a "$MSI_WIN" /qn TARGETDIR="$EXTRACT_WIN" /l*v "$MSI_LOG_WIN" > "$DIAG/msi-admin-stdout.txt" 2>&1; then
    echo "administrative MSI extraction failed; trying 7z fallback..." >> "$DIAG/msi-admin-stdout.txt"
    if ! 7z x -y -o"$EXTRACT_DIR" "$FINAL_PACKAGE" >> "$DIAG/msi-admin-stdout.txt" 2>&1; then
      fail "could not extract the MSI for verification; see diagnostics/msi-admin-stdout.txt"
    fi
  fi
  EXE_PATH="$(find "$EXTRACT_DIR" -type f -name 'UkkeraPOS.exe' -print -quit)"
  [[ -n "$EXE_PATH" && -f "$EXE_PATH" ]] || fail "MSI extraction did not contain UkkeraPOS.exe."
  file "$EXE_PATH" > "$DIAG/msi-executable-file.txt" 2>&1
  EXE_PATH="$(cygpath -w "$EXE_PATH")" powershell.exe -NoProfile -NonInteractive -Command \
    '$v=(Get-Item -LiteralPath $env:EXE_PATH).VersionInfo; "FileVersion=" + $v.FileVersion; "ProductVersion=" + $v.ProductVersion; "ProductName=" + $v.ProductName' \
    > "$DIAG/msi-executable-version.txt" 2>&1 || true
  if [[ "$SMOKE" == "1" ]]; then
    echo "Launching packaged Windows app with --smoke-exit ..."
    if "$PYTHON" - "$EXE_PATH" "$DIAG/msi-smoke.log" <<'PY'
import subprocess
import sys

executable, log_path = sys.argv[1], sys.argv[2]
try:
    with open(log_path, "w", encoding="utf-8") as log:
        result = subprocess.run(
            [executable, "--smoke-exit"],
            stdout=log,
            stderr=subprocess.STDOUT,
            timeout=60,
        )
    print(f"exit_code={result.returncode}")
    raise SystemExit(0 if result.returncode == 0 else 1)
except subprocess.TimeoutExpired:
    print("timeout=60s")
    raise SystemExit(124)
PY
    then
      SMOKE_STATUS="passed"
    else
      SMOKE_STATUS="failed"
      fail "packaged Windows app did not exit successfully for --smoke-exit."
    fi
  fi
fi

cat > "$DIAG/summary.txt" <<EOF
### Ukkera POS desktop $FORMAT package

- Host: $HOST_OS / $HOST_ARCH
- Java: $(java -version 2>&1 | head -n 1)
- Package: $PACKAGE
- Desktop smoke: $SMOKE_STATUS
- Signing: unsigned local test package
- Source revision: $SOURCE_REVISION
- Hashes: \`SHA256SUMS\`, metadata: \`manifest.json\`
EOF
BUILD_STATUS="success"
finalize_artifacts
echo "Artifacts ready in $OUT_DIR"
