#!/usr/bin/env bash
set -euo pipefail
umask 077
work="$RUNNER_TEMP/pos-ui"
sdk="$ANDROID_HOME/cmdline-tools/latest/bin"
image='system-images;android-35;google_apis;x86_64'
export PATH="$ANDROID_HOME/platform-tools:$PATH"
export ANDROID_AVD_HOME="$work/avds"
mkdir -p "$ANDROID_AVD_HOME"

headroom() {
  local ram disk
  ram=$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)
  disk=$(df -B1 --output=avail "$work" | tail -1 | tr -d ' ')
  printf '%s\t%s\n' "$((ram * 1024))" "$disk" >> "$work/public/available-resources.tsv"
  if (( ram < 2228224 || disk < 4724464026 )); then
    echo 'Android LAN preflight reached the active resource floor.'
    exit 75
  fi
}
cleanup() {
  for serial in emulator-5554 emulator-5556; do
    timeout 8 adb -s "$serial" emu kill >/dev/null 2>&1 || true
  done
}
trap cleanup EXIT
printf 'available_ram_bytes\tavailable_disk_bytes\n' > "$work/public/available-resources.tsv"
headroom
if (( $(awk '/^MemAvailable:/ {print $2}' /proc/meminfo) < 7340032 )); then
  echo 'Android LAN preflight requires 7 GiB available at admission.'
  exit 75
fi
if (( $(df -B1 --output=avail "$work" | tail -1) < 6442450944 )); then
  echo 'Android LAN preflight requires 6 GiB disk at admission.'
  exit 75
fi
sudo chmod a+rw /dev/kvm
"$sdk/sdkmanager" --sdk_root="$ANDROID_HOME" emulator "$image"
emulator="$ANDROID_HOME/emulator/emulator"
if [[ ! -x "$emulator" ]]; then
  echo 'The SDK install did not provide the requested emulator executable.'
  exit 127
fi
python3 - "$ANDROID_HOME/emulator/source.properties" "$work/emulator-version.txt" <<'PYVERSION'
from pathlib import Path
import re, sys
properties = Path(sys.argv[1]).read_text()
match = re.search(r'^Pkg.Revision\s*=\s*([0-9]+)\.([0-9]+)\.([0-9]+)', properties, re.M)
if match is None or tuple(map(int, match.groups()[:2])) < (36, 5):
    raise SystemExit('This scenario requires Android Emulator 36.5 or later for shared Wi-Fi.')
Path(sys.argv[2]).write_text('.'.join(match.groups())+'\n')
PYVERSION
if ! "$emulator" -accel-check > "$work/emulator-acceleration.txt" 2>&1; then
  head -6 "$work/emulator-acceleration.txt"
  exit 1
fi
head -6 "$work/emulator-acceleration.txt"
adb start-server
for port in 5554 5556; do
  printf 'no\n' | "$sdk/avdmanager" create avd --force --name "pos-lan-$port" --package "$image"
  "$emulator" -avd "pos-lan-$port" -port "$port" -no-window -no-audio -no-snapshot \
    -no-boot-anim -memory 2048 -cores 2 -gpu swiftshader_indirect > "$work/emulator-$port.log" 2>&1 &
  printf '%s\n' "$!" > "$work/emulator-$port.pid"
done
for port in 5554 5556; do
  serial="emulator-$port"
  until [[ "$(timeout 5 adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do
    if ! kill -0 "$(cat "$work/emulator-$port.pid")" 2>/dev/null; then
      echo "$serial exited before Android booted."
      head -12 "$work/emulator-$port.log"
      exit 1
    fi
    headroom
    sleep 3
  done
  headroom
  adb -s "$serial" shell input keyevent KEYCODE_WAKEUP
  adb -s "$serial" shell wm dismiss-keyguard
  adb -s "$serial" install "$(< "$work/package-path")"
  adb -s "$serial" shell pm path com.ukkera.pos.local.testing > "$work/package-$port"
  [[ -s "$work/package-$port" ]]
done

python3 - "$work" <<'PY'
from pathlib import Path
import ipaddress,json,re,subprocess,sys
work = Path(sys.argv[1])
serials = ('emulator-5554','emulator-5556')
nodes = []
version = (work/'emulator-version.txt').read_text().strip()
for serial in serials:
    output = subprocess.check_output(['adb','-s',serial,'shell','ip','-4','addr','show','wlan0'], text=True, timeout=12)
    (address,) = re.findall(r'\binet ([\d.]+/\d+)',output)
    nodes.append(ipaddress.IPv4Interface(address))
assert nodes[0].ip != nodes[1].ip and nodes[0].network == nodes[1].network and nodes[0].ip.is_private
observations = []
for index,serial in enumerate(serials):
    target = str(nodes[1-index].ip)
    reply = subprocess.run(['adb','-s',serial,'shell','ping','-I','wlan0','-c','3','-W','2',target],
                           text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=15)
    replies = re.search(r'3 packets transmitted, (\d+) (?:packets )?received',reply.stdout)
    count = int(replies[1]) if replies is not None else 0
    observations.append({'source':serial,'sent':3,'received':count})
report = {'pos_source_revision':(work/'pos-revision').read_text().strip(),'emulator_version':version,
          'distinct_installed_instances':2,'shared_wifi':True,'bidirectional_icmp':observations,
          'pos_pairing_or_transactions_verified':False}
(work/'public'/'android-lan-preflight.json').write_text(json.dumps(report, indent=2)+'\n')
print(json.dumps(report))
assert all(item['received']==3 for item in observations)
PY
headroom

if [[ "${POS_ANDROID_TWO_SETUP:-false}" == true ]]; then
  python3 scripts/pos/ui/android-setup-pair.py
  headroom
fi
