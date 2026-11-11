#!/usr/bin/env python3
"""Observe one short ART sample on a disposable, synthetic native run.

This changes observability only. It never launches/stops the application, changes
its operation budget, reads business state, or supplies a passing test result.
"""
import argparse
import json
import os
from pathlib import Path
import re
import selectors
import subprocess
import time

PACKAGE = 'com.ukkera.pos.local.testing'
PHASE = 'Normalized payment preparation: STOCK_ENTERED'
SAMPLE_SECONDS = 5
SAMPLE_MICROS = 10_000
MAX_WAIT_SECONDS = 1_200
DEVICE_TRACE = '/data/local/tmp/ukkera-pos-payment-stock.trace'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if os.environ.get('POS_DISPOSABLE_ANDROID') != 'true' or not re.fullmatch(r'emulator-[0-9]+', args.serial):
        parser.error('An explicitly disposable single emulator is required.')
    out = args.output
    out.mkdir(parents=True, exist_ok=False)
    adb = ['adb', '-s', args.serial]
    observed = {'status': 'WAITING_FOR_FIXED_PHASE', 'phase': PHASE,
                'sample_seconds': SAMPLE_SECONDS, 'interval_microseconds': SAMPLE_MICROS,
                'native_budget_modified': False, 'diagnostic_only': True,
                'application_source_revision': os.environ.get('POS_EXPECTED_APK_SOURCE', ''),
                'harness_revision': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
                'run_id': os.environ.get('GITHUB_RUN_ID', '')}
    monitor = None
    started = False
    target_pid = None
    selector = selectors.DefaultSelector()

    def command(*words, timeout=20):
        return subprocess.run(adb + list(words), capture_output=True, text=True, timeout=timeout)

    def write():
        (out / 'observation.json').write_text(json.dumps(observed, indent=2) + '\n')

    try:
        if command('shell', 'getprop', 'ro.kernel.qemu').stdout.strip() != '1':
            raise RuntimeError('Not an emulator')
        monitor = subprocess.Popen(adb + ['logcat', '-v', 'brief', '-s', 'UkkeraNativeOwner:I', '*:S'],
                                   stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        os.set_blocking(monitor.stdout.fileno(), False)
        selector.register(monitor.stdout, selectors.EVENT_READ)
        pending = b''
        deadline = time.monotonic() + MAX_WAIT_SECONDS
        write()
        while time.monotonic() < deadline:
            if (out.parent / 'sampling-instrumentation-finished').exists():
                observed['status'] = 'PHASE_NOT_OBSERVED_BEFORE_INSTRUMENTATION_RETURN'
                return
            if monitor.poll() is not None:
                observed['status'] = 'LOG_MONITOR_ENDED_BEFORE_PHASE'
                return
            if not selector.select(timeout=0.25):
                continue
            chunk = os.read(monitor.stdout.fileno(), 65_536)
            pending += chunk
            lines = pending.split(b'\n')
            pending = lines.pop()
            if len(pending) > 65_536:
                raise RuntimeError('Unexpected log record size')
            if not any(PHASE.encode() in line for line in lines):
                continue
            # Bind the trace to the sole current target PID, never a guessed process.
            target_pid = command('shell', 'pidof', PACKAGE).stdout.strip()
            if not re.fullmatch(r'[1-9][0-9]*', target_pid):
                observed['status'] = 'SINGLE_TARGET_PID_NOT_OBSERVED'
                return
            observed['target_pid'] = int(target_pid)
            result = command('shell', 'am', 'profile', 'start', '--user', 'current',
                             '--clock-type', 'dual', '--sampling', str(SAMPLE_MICROS), target_pid, DEVICE_TRACE)
            observed['start_exit_status'] = result.returncode
            (out / 'profile-start.txt').write_text(result.stdout + result.stderr)
            if result.returncode or re.search(r'error|failed|exception', result.stdout + result.stderr, re.I):
                observed['status'] = 'PROFILE_START_NOT_CONFIRMED'
                return
            started = True
            time.sleep(SAMPLE_SECONDS)
            result = command('shell', 'am', 'profile', 'stop', '--user', 'current', target_pid)
            observed['stop_exit_status'] = result.returncode
            (out / 'profile-stop.txt').write_text(result.stdout + result.stderr)
            started = False
            if result.returncode or re.search(r'error|failed|exception', result.stdout + result.stderr, re.I):
                observed['status'] = 'PROFILE_STOP_NOT_CONFIRMED'
                return
            result = command('pull', DEVICE_TRACE, str(out / 'payment-stock.trace'), timeout=30)
            observed['pull_exit_status'] = result.returncode
            trace = out / 'payment-stock.trace'
            observed['trace_bytes'] = trace.stat().st_size if trace.exists() else 0
            observed['status'] = 'BOUNDED_DIAGNOSTIC_TRACE_COLLECTED' if result.returncode == 0 and observed['trace_bytes'] else 'TRACE_NOT_COLLECTED'
            return
        observed['status'] = 'FIXED_PHASE_WAIT_EXPIRED'
    except (OSError, RuntimeError, subprocess.SubprocessError) as failure:
        observed['status'] = 'DIAGNOSTIC_UNAVAILABLE'
        observed['failure_type'] = type(failure).__name__
    finally:
        # Only stop this collector's profiler/logcat. Never stop or kill the app.
        if started and target_pid:
            try:
                result = command('shell', 'am', 'profile', 'stop', '--user', 'current', target_pid)
                observed['finally_stop_exit_status'] = result.returncode
            except (OSError, subprocess.SubprocessError):
                observed['finally_stop_exit_status'] = 'UNAVAILABLE'
        selector.close()
        if monitor is not None:
            monitor.terminate()
            try:
                monitor.wait(timeout=5)
            except subprocess.TimeoutExpired:
                monitor.kill()
                monitor.wait(timeout=5)
        write()


if __name__ == '__main__':
    main()
