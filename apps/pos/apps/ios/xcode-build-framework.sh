#!/usr/bin/env bash
# Xcode build phase: build/link the Kotlin/Native framework used by the Swift app.
#
# Xcode does not inherit a login shell. Resolve a JDK 21 explicitly, keep the
# framework task on the POS Gradle wrapper, and apply the shared build limits.
set -euo pipefail

if [[ -z "${SRCROOT:-}" ]]; then
  SRCROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fi
POS_ROOT="$(cd "$SRCROOT/../.." && pwd)"

if [[ -z "${CONFIGURATION:-}" || -z "${SDK_NAME:-}" ]]; then
  echo "error: CONFIGURATION and SDK_NAME must be set. Run this script from an Xcode build phase." >&2
  exit 2
fi

if [[ ! -f "$POS_ROOT/gradlew" ]]; then
  echo "error: POS Gradle wrapper not found at $POS_ROOT/gradlew" >&2
  exit 2
fi

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

if [[ -z "$JAVA_HOME" || ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "error: JDK 21 is required to build the UkkeraPos framework." >&2
  echo "       Install Temurin 21 (or set JAVA_HOME to a JDK 21 home) and rebuild." >&2
  exit 2
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

if [[ -z "${GRADLE_OPTS:-}" ]]; then
  export GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8"
fi

echo "Ukkera POS Xcode framework phase"
echo "  POS root:    $POS_ROOT"
echo "  Java:        $JAVA_HOME"
echo "  Xcode SDK:   $SDK_NAME"
echo "  Config:      $CONFIGURATION"
echo "  ARCHS:       ${ARCHS:-unset}"

cd "$POS_ROOT"
exec /bin/bash ./gradlew \
  :ui:app:embedAndSignAppleFrameworkForXcode \
  --console=plain \
  --stacktrace \
  --no-daemon \
  --max-workers=2 \
  -Pkotlin.compiler.execution.strategy=in-process
