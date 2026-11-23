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
import ipaddress,json,re,secrets,select,subprocess,sys
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
def tcp_result(source, sent_exit, listener_exit, received, nonce):
    exact = received == nonce
    if sent_exit == listener_exit == 0 and exact:
        stage = 'exact_payload'
    elif received and not exact:
        stage = 'unexpected_payload'
    elif sent_exit != 0:
        stage = 'sender_exit'
    elif listener_exit != 0:
        stage = 'listener_exit'
    else:
        stage = 'empty_payload'
    return {'source':source,'verified':stage == 'exact_payload','stage':stage,
            'sender_exit':sent_exit,'listener_exit':listener_exit,'received_bytes':len(received),
            'payload_matched':exact}

def tcp_payload(index):
    source, destination = serials[index], serials[1-index]
    target = str(nodes[1-index].ip)
    nonce = secrets.token_hex(16).encode("ascii") + b"\n"
    # The guest binds its own Wi-Fi address and reports a kernel-selected
    # port. There is no ADB port forwarding or host-visible listener.
    with subprocess.Popen(['adb','-s',destination,'shell','-T','toybox','nc','-4','-l','-w','10','-W','4','-s',target],
                          stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as listener:
        try:
            ready, _, _ = select.select([listener.stdout], [], [], 6)
            port = listener.stdout.readline(8).strip() if ready else b""
            if not port.isdigit() or not 1024 <= int(port) <= 65535:
                return {'source':source,'verified':False,'stage':'listener_not_ready'}
            sent = subprocess.run(['adb','-s',source,'shell','-T','toybox','nc','-4','-n','-w','4','-q','1',target,port.decode("ascii")],
                                  input=nonce, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=7)
            received, _ = listener.communicate(timeout=6)
            return tcp_result(source, sent.returncode, listener.returncode, received, nonce)
        except subprocess.TimeoutExpired:
            return {'source':source,'verified':False,'stage':'transport_deadline'}
        finally:
            if listener.poll() is None:
                listener.kill()

def observed_tcp(index):
    attempts = []
    # These are disposable transport probes, before any POS workflow.
    # Every attempt uses a new nonce and listener. Unexpected bytes fail closed.
    for _ in range(3):
        current = tcp_payload(index)
        attempts.append(current)
        if current['verified'] or current['stage'] == 'unexpected_payload':
            break
    return {**attempts[-1], 'attempts':attempts}

tcp = [observed_tcp(index) for index in range(len(serials))]
report = {'pos_source_revision':(work/'pos-revision').read_text().strip(),'emulator_version':version,
          'distinct_installed_instances':2,'shared_wifi':True,'bidirectional_icmp':observations,'bidirectional_tcp':tcp,
          'pos_pairing_or_transactions_verified':False}
(work/'public'/'android-lan-preflight.json').write_text(json.dumps(report, indent=2)+'\n')
print(json.dumps(report))
assert all(item['verified'] for item in tcp), 'Direct guest TCP payload exchange was not confirmed in both directions.'
PY
headroom

if [[ "${POS_ANDROID_TWO_SETUP:-false}" == true ]]; then
  python3 scripts/pos/ui/android-setup-pair.py
  headroom
fi
