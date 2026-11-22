#!/usr/bin/env bash
set -euo pipefail
work="$RUNNER_TEMP/pos-ui"
sdk="$ANDROID_HOME/cmdline-tools/latest/bin"
image='system-images;android-35;google_apis;x86_64'
sudo chmod a+rw /dev/kvm
"$sdk/sdkmanager" emulator "$image"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
echo "$ANDROID_HOME/platform-tools" >> "$GITHUB_PATH"
export ANDROID_AVD_HOME="$work/avds"
mkdir -p "$ANDROID_AVD_HOME"
adb start-server
echo no | "$sdk/avdmanager" create avd --force --name pos-ui --package "$image"
"$ANDROID_HOME/emulator/emulator" -avd pos-ui -port 5554 -no-window -no-audio \
  -no-snapshot -no-boot-anim -gpu swiftshader_indirect > "$work/emulator.log" 2>&1 &
emulator_pid=$!
timeout 360 bash -c 'until [[ "$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d "\r")" == 1 ]]; do kill -0 "$1" || exit 1; sleep 2; done' bash "$emulator_pid"
adb -s emulator-5554 shell input keyevent KEYCODE_WAKEUP
adb -s emulator-5554 shell wm dismiss-keyguard
adb -s emulator-5554 install "$(< "$work/package-path")"
