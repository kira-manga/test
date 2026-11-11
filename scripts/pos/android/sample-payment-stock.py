#!/usr/bin/env python3
"""Observe one short ART sample on a disposable, synthetic native run.

This changes observability only. It never launches/stops the application, changes
its operation budget, or supplies a passing test result. The optional finalization
trigger reads only the fixed synthetic test record in memory; private request and
result bytes are never retained in diagnostics.
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
FINALIZATION_EVIDENCE = '/data/user/0/' + PACKAGE + '/no_backup/cold-original-start-v1-backend-intent.json'
MAX_EVIDENCE_BYTES = 1_048_576
SAMPLE_SECONDS = 5
SAMPLE_MICROS = 10_000
MAX_WAIT_SECONDS = 1_200
DEVICE_TRACE = '/data/local/tmp/ukkera-pos-payment-stock.trace'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--phase', choices=('payment-stock', 'original-finalization'), default='payment-stock')
    args = parser.parse_args()
    if os.environ.get('POS_DISPOSABLE_ANDROID') != 'true' or not re.fullmatch(r'emulator-[0-9]+', args.serial):
        parser.error('An explicitly disposable single emulator is required.')
    out = args.output
    out.mkdir(parents=True, exist_ok=False)
    adb = ['adb', '-s', args.serial]
    phase = PHASE if args.phase == 'payment-stock' else 'ORIGINAL_FINALIZATION: DISPATCH_PENDING'
    observed = {'status': 'WAITING_FOR_FIXED_PHASE', 'phase': phase,
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
        if args.phase == 'payment-stock':
            monitor = subprocess.Popen(adb + ['logcat', '-v', 'brief', '-s', 'UkkeraNativeOwner:I', '*:S'],
                                       stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            os.set_blocking(monitor.stdout.fileno(), False)
            selector.register(monitor.stdout, selectors.EVENT_READ)
        else:
            # Root is used only to READ this fixed synthetic test record on the
            # disposable userdebug emulator. Never retain its requests/results or
            # change its bytes, process, budget or outcome. No application caller
            # receives this observer's privilege or a supplied acceptance result.
            root = command('shell', 'su', '0', 'id', '-u', timeout=5)
            if root.returncode or root.stdout.strip() != '0':
                observed['status'] = 'SYNTHETIC_PROGRESS_READ_UNAVAILABLE'
                return
        pending = b''
        deadline = time.monotonic() + MAX_WAIT_SECONDS
        write()
        while time.monotonic() < deadline:
            if (out.parent / 'sampling-instrumentation-finished').exists():
                observed['status'] = 'PHASE_NOT_OBSERVED_BEFORE_INSTRUMENTATION_RETURN'
                return
            if args.phase == 'payment-stock':
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
            else:
                result = command('exec-out', 'su', '0', 'head', '-c', str(MAX_EVIDENCE_BYTES + 1),
                                 FINALIZATION_EVIDENCE, timeout=5)
                # AtomicFile publication may be absent briefly. Never repair it,
                # inspect the backup, or treat partial JSON as a pending command.
                try:
                    record = json.loads(result.stdout) if result.returncode == 0 else None
                except (ValueError, UnicodeError):
                    record = None
                if len(result.stdout.encode('utf-8')) > MAX_EVIDENCE_BYTES:
                    raise RuntimeError('Unexpected synthetic progress record size')
                if isinstance(record, dict) and record.get('design') == 'cold-original-start-v1' and record.get('case') == 'COS-001':
                    requests = record.get('requests')
                    request = requests.get('ORIGINAL_FINALIZATION') if isinstance(requests, dict) else None
                    request = request if isinstance(request, dict) else {}
                    if request.get('status') == 'REPLY_OBSERVED':
                        observed['status'] = 'FINALIZATION_REPLY_PRECEDED_SAMPLE'
                        return
                    if request.get('status') == 'DISPATCH_PENDING':
                        observed['synthetic_pending_finalization_observed'] = True
                        break_phase = True
                    else:
                        break_phase = False
                else:
                    break_phase = False
                request = None
                requests = None
                record = None
                result = None
                if not break_phase:
                    time.sleep(0.5)
                    continue
            # Bind the trace to the sole current target PID, never a guessed process.
            target_pid = command('shell', 'pidof', PACKAGE).stdout.strip()
            if not re.fullmatch(r'[1-9][0-9]*', target_pid):
                observed['status'] = 'SINGLE_TARGET_PID_NOT_OBSERVED'
                return
            observed['target_pid'] = int(target_pid)
            result = command('shell', 'am', 'profile', 'start', '--user', 'current',
                             '--clock-type', 'dual', '--sampling', str(SAMPLE_MICROS), target_pid, DEVICE_TRACE)
            observed['start_request_exit_status'] = result.returncode
            (out / 'profile-start.txt').write_text(result.stdout + result.stderr)
            if result.returncode or re.search(r'error|failed|exception', result.stdout + result.stderr, re.I):
                observed['status'] = 'PROFILE_START_NOT_CONFIRMED'
                return
            started = True
            time.sleep(SAMPLE_SECONDS)
            result = command('shell', 'am', 'profile', 'stop', '--user', 'current', target_pid)
            observed['stop_request_exit_status'] = result.returncode
            (out / 'profile-stop.txt').write_text(result.stdout + result.stderr)
            started = False
            if result.returncode or re.search(r'error|failed|exception', result.stdout + result.stderr, re.I):
                observed['status'] = 'PROFILE_STOP_NOT_CONFIRMED'
                return
            # ActivityManager sends asynchronous profiler-control Binder messages.
            # A successful shell command does not prove the app has flushed its FD.
            # Observe the completed file rather than immediately copying its initial
            # zero-length placeholder. This wait never changes the app work budget.
            observed['profile_requests_sent'] = True
            capture_deadline = time.monotonic() + MAX_WAIT_SECONDS
            previous_size = None
            stable = 0
            while time.monotonic() < capture_deadline:
                result = command('shell', 'stat', '-c', '%s', DEVICE_TRACE, timeout=5)
                value = result.stdout.strip()
                size = int(value) if re.fullmatch(r'[0-9]+', value) else 0
                stable = stable + 1 if size > 0 and size == previous_size else 0
                previous_size = size
                finished = (out.parent / 'sampling-instrumentation-finished').exists()
                if stable >= 2 or finished:
                    observed['capture_after_instrumentation_return'] = finished
                    # The final marker follows the test-process result. Retain the
                    # actual file even when it remains empty, without inventing a
                    # profile or a successful native result.
                    result = command('pull', DEVICE_TRACE, str(out / 'payment-stock.trace'), timeout=30)
                    observed['pull_exit_status'] = result.returncode
                    trace = out / 'payment-stock.trace'
                    observed['trace_bytes'] = trace.stat().st_size if trace.exists() else 0
                    observed['status'] = 'BOUNDED_DIAGNOSTIC_TRACE_COLLECTED' if result.returncode == 0 and observed['trace_bytes'] else 'TRACE_NOT_COLLECTED'
                    errors = command('logcat', '-d', '-v', 'brief', '-s', 'ActivityThread:W', 'art:W', 'AndroidRuntime:W', '*:S')
                    # Keep error TYPE counts only, no exception messages/arguments,
                    # application data or arbitrary system log lines in this summary.
                    observed['profiling_failed_warning_count'] = errors.stdout.count('Profiling failed on path')
                    error_types = re.findall(r'\b(?:[a-zA-Z_$][\w$]*\.)+[A-Z][\w$]*(?:Exception|Error)\b', errors.stdout)
                    observed['profiler_error_types'] = {name: error_types.count(name) for name in sorted(set(error_types))}
                    return
                time.sleep(0.5)
            observed['status'] = 'TRACE_FLUSH_NOT_OBSERVED_WITHIN_HOST_WAIT'

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
