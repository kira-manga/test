#!/usr/bin/env python3
"""Run supporting local publication checks against synthetic fixtures.

Run from the repository root. All fixtures and process diagnostics are retained in
--output-dir; these checks do not dispatch Actions or execute native POS tests.
The ten predecessor checks retain their existing inputs and expected outcomes.
This is implementer wiring, not independent frozen qualification or secret scanning.
"""
from pathlib import Path
import argparse, contextlib, hashlib, io, json, os, subprocess, sys
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output-dir', type=Path, required=True, help='Fresh local fixture/results directory; existing evidence is refused')
parser.add_argument('--baseline', required=True, help='Pre-change Git revision for native execution/guard comparison')
arguments = parser.parse_args()
root = Path.cwd()
sys.path.insert(0, str(root / 'scripts/pos'))
import artifact_manifest
output_root = arguments.output_dir.absolute()
output_root.mkdir(parents=True, exist_ok=False)
report_directory = archive = output_root
revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
source_files = list(Path('.github/workflows').glob('pos-*.yml')) + [Path('scripts/pos') / name for name in ('artifact_manifest.py', 'sanitize_diagnostics.py', 'publish_public_artifact.py', 'stage_native_diagnostics.py', 'validate_publication_workflows.rb', 'validate-config.sh', 'check_native_publication.py')]
source_hashes = {str(p): digest(root / p) for p in sorted(source_files)}
(report_directory / 'preexecution-source-binding.json').write_text(json.dumps({'base_revision': revision, 'files': source_hashes, 'checks_sha256': digest(Path(__file__))}, indent=2) + '\n')

def preserved_predecessor_checks():
    archive = output_root / 'predecessor-cases'
    archive.mkdir()
    results = []

    def run(name, mutate, success):
        case = archive / name
        candidate = case / 'candidate'
        candidate.mkdir(parents=True)
        (candidate / 'diagnostics').mkdir()
        (candidate / 'diagnostics/run.log').write_text('PASSWORD=synthetic-publication-canary\n')
        (candidate / 'SOURCE-REVISION.txt').write_text('controlled staging check\n')
        with contextlib.redirect_stdout(io.StringIO()):
            artifact_manifest.write_manifest(str(candidate), 'controlled fixture', {})
        mutate(candidate)
        before = {str(p.relative_to(candidate)): hashlib.sha256(p.read_bytes()).hexdigest() for p in candidate.rglob('*') if p.is_file() and (not p.is_symlink())}
        output = case / 'public'
        result = subprocess.run([sys.executable, 'scripts/pos/publish_public_artifact.py', '--candidate', str(candidate), '--output', str(output)], capture_output=True, text=True)
        (case / 'process.log').write_text(result.stdout + result.stderr)
        after = {str(p.relative_to(candidate)): hashlib.sha256(p.read_bytes()).hexdigest() for p in candidate.rglob('*') if p.is_file() and (not p.is_symlink())}
        assert before == after, name + ' changed original candidate'
        assert (result.returncode == 0) == success, name
        assert output.exists() == success, name + ' publication state'
        if success:
            assert 'synthetic-publication-canary' not in (output / 'diagnostics/run.log').read_text()
            for line in (output / 'SHA256SUMS').read_text().splitlines():
                digest, relative = line.split('  ', 1)
                assert hashlib.sha256((output / relative).read_bytes()).hexdigest() == digest
        results.append(dict(case=name, expected_publication=success, exit_status=result.returncode, public_directory_created=output.exists(), candidate_unchanged=True))

    def oversized(p):
        with (p / 'diagnostics/large.log').open('wb') as f:
            f.truncate(32 * 1024 * 1024 + 1)
    run('valid-text-redacted', lambda p: None, True)
    run('oversized-text-withheld', oversized, False)
    run('invalid-utf8-withheld', lambda p: (p / 'diagnostics/bad.log').write_bytes(b'\xff'), False)
    run('nul-text-withheld', lambda p: (p / 'diagnostics/bad.log').write_bytes(b'\x00'), False)
    run('unsupported-content-withheld', lambda p: (p / 'diagnostics/bad.bin').write_bytes(b'controlled'), False)
    run('diagnostic-symlink-withheld', lambda p: (p / 'diagnostics/link.log').symlink_to('run.log'), False)
    run('forbidden-file-withheld', lambda p: (p / 'diagnostics/.env').write_text('CONTROLLED=1'), False)
    run('invalid-png-withheld', lambda p: (p / 'diagnostics/bad.png').write_text('not a PNG'), False)
    run('missing-manifest-withheld', lambda p: (p / 'manifest.json').unlink(), False)
    run('missing-diagnostics-withheld', lambda p: ((p / 'diagnostics/run.log').unlink(), (p / 'diagnostics').rmdir()), False)
    return results
prior = preserved_predecessor_checks()

def snapshot(path):
    return {str(p.relative_to(path)): digest(p) for p in sorted(path.rglob('*')) if p.is_file() and (not p.is_symlink())}
results = []

def run(name, modify=lambda c: None, expected=True, extra=(), omit=()):
    case = archive / name
    case.mkdir()
    (case / 'SOURCE-REVISION.txt').write_text(revision + '\n')
    native = case / 'native'
    native.mkdir()
    (native / 'run.log').write_text('PASSWORD=controlled-publication-canary\n' + str(Path.home()) + '/local-diagnostics\n')
    (native / 'metadata.json').write_text('{"result":"controlled DATA; not a native outcome"}\n')
    tests = case / 'junit'
    tests.mkdir()
    (tests / 'TEST-synthetic.xml').write_text('<testsuite tests="1" failures="0"><system-out>PASSWORD=controlled-xml-canary</system-out></testsuite>\n')
    (tests / 'binary').mkdir()
    (tests / 'binary/output.bin').write_bytes(b'controlled Gradle cache\x00')
    selected = case / 'selected'
    selected.mkdir()
    (selected / 'service-launcher.cs').write_text('// synthetic probe source\n// PASSWORD=controlled-source-canary\n')
    (selected / 'artifact.json').write_text('{"scope":"synthetic input metadata"}\n')
    (case / 'sample.apk').write_bytes(b'controlled opaque package fixture\x00not an actual APK')
    modify(case)
    command = [sys.executable, str(root / 'scripts/pos/stage_native_diagnostics.py'), '--source-revision', revision, '--label', 'controlled-publication-check', '--output', 'public']
    flags = {'diagnostics': ['--diagnostics', 'native=native'], 'xml': ['--xml-results', 'security=junit'], 'files': ['--diagnostic-file', 'selected/*.cs', '--diagnostic-file', 'selected/artifact.json'], 'apk': ['--apk', 'sample.apk']}
    for key, value in flags.items():
        if key not in omit:
            command += value
    command += list(extra)
    before = snapshot(case)
    process = subprocess.run(command, cwd=case, text=True, capture_output=True)
    (case / 'process-output.log').write_text(process.stdout + process.stderr)
    after = snapshot(case)
    for key, value in before.items():
        assert after.get(key) == value, (name, 'original changed', key)
    assert (process.returncode == 0) == expected, (name, process.stdout, process.stderr)
    output = case / 'public'
    if name == 'destination-already-exists':
        assert (output / 'retained.txt').read_text() == 'preserve existing public output'
    else:
        assert output.exists() == expected, (name, 'public output state')
    checksums = 0
    if expected:
        combined = '\n'.join((p.read_text() for p in (output / 'diagnostics').rglob('*') if p.is_file()))
        for canary in ('controlled-publication-canary', 'controlled-xml-canary', 'controlled-source-canary', str(Path.home())):
            assert canary not in combined, (name, 'unsanitized text')
        assert not list((output / 'diagnostics').rglob('*.bin'))
        for line in (output / 'SHA256SUMS').read_text().splitlines():
            value, relative = line.split('  ', 1)
            assert digest(output / relative) == value
            checksums += 1
        manifest = json.loads((output / 'manifest.json').read_text())
        for entry in manifest['files']:
            p = output / entry['path']
            assert digest(p) == entry['sha256']
            assert p.stat().st_size == entry['bytes']
        if 'apk' not in omit and (case / 'sample.apk').exists():
            assert (output / 'sample.apk').read_bytes() == (case / 'sample.apk').read_bytes()
        if name == 'missing-diagnostic-result-and-package':
            entries = json.loads((output / 'diagnostics/publication-inputs.json').read_text())['inputs']
            assert all((not entry['present'] for entry in entries))
    results.append({'case': name, 'expected_publication': expected, 'exit_status': process.returncode, 'public_directory_created': output.exists(), 'original_inputs_unchanged': True, 'verified_checksums': checksums})

def oversized(c):
    with (c / 'native/large.log').open('wb') as f:
        f.truncate(32 * 1024 * 1024 + 1)

def missing(c):
    (c / 'native/run.log').unlink()
    (c / 'native/metadata.json').unlink()
    (c / 'native').rmdir()
    (c / 'junit/TEST-synthetic.xml').unlink()
    (c / 'junit/binary/output.bin').unlink()
    (c / 'junit/binary').rmdir()
    (c / 'junit').rmdir()
    (c / 'selected/service-launcher.cs').unlink()
    (c / 'selected/artifact.json').unlink()
    (c / 'selected').rmdir()
    (c / 'sample.apk').unlink()

def existing(c):
    (c / 'public').mkdir()
    (c / 'public/retained.txt').write_text('preserve existing public output')
run('valid-diagnostics-xml-source-and-opaque-apk')
run('missing-diagnostic-result-and-package', missing, True)
run('oversized-text-withheld', oversized, False)
run('invalid-utf8-withheld', lambda c: (c / 'native/bad.log').write_bytes(b'\xff'), False)
run('nul-text-withheld', lambda c: (c / 'native/bad.log').write_bytes(b'\x00'), False)
run('unsupported-content-withheld', lambda c: (c / 'native/bad.bin').write_bytes(b'controlled'), False)
run('diagnostic-symlink-withheld', lambda c: (c / 'native/link.log').symlink_to('run.log'), False)
run('diagnostic-root-symlink-withheld', lambda c: (c / 'native-link').symlink_to('native', target_is_directory=True), False, extra=('--diagnostics', 'linked=native-link'))
run('xml-symlink-withheld', lambda c: (c / 'junit/TEST-link.xml').symlink_to('TEST-synthetic.xml'), False)
run('forbidden-file-withheld', lambda c: (c / 'native/.env').write_text('CONTROLLED=1'), False)
run('forbidden-directory-withheld', lambda c: (c / 'native/.ssh').mkdir(), False)
run('invalid-png-withheld', lambda c: (c / 'native/bad.png').write_text('not a PNG'), False)
run('source-disagreement-withheld', lambda c: (c / 'SOURCE-REVISION.txt').write_text('f' * 40), False)
run('source-marker-absent-withheld', lambda c: (c / 'SOURCE-REVISION.txt').unlink(), False)
run('destination-already-exists', existing, False)
run('source-output-overlap-withheld', expected=False, extra=('--output', 'native/public'))
run('duplicate-alias-withheld', expected=False, extra=('--diagnostics', 'native=absent'))
run('selected-file-symlink-withheld', lambda c: (c / 'selected/linked.cs').symlink_to('service-launcher.cs'), False)
run('apk-symlink-withheld', lambda c: (c / 'linked.apk').symlink_to('sample.apk'), False, extra=('--apk', 'linked.apk'))
run('unapproved-package-type-withheld', expected=False, extra=('--apk', 'native/run.log'))
run('invalid-source-revision-withheld', expected=False, extra=('--source-revision', 'not-a-revision'))
run('source-marker-symlink-withheld', lambda c: ((c / 'SOURCE-REVISION.txt').unlink(), (c / 'SOURCE-REVISION.txt').symlink_to('native/run.log')), False)
workflow = root / '.github/workflows/pos-android-native-io.yml'
base = workflow.read_text()
mutations = {'ungated-upload': base.replace("if: always() && steps.public_safe.outcome == 'success'", 'if: always()'), 'raw-upload-root': base.replace('path: build/pos-public-artifacts/android-native-io', 'path: native-io-output'), 'wrong-output-root': base.replace('--output "build/pos-public-artifacts/android-native-io"', '--output "build/pos-public-artifacts/other"'), 'ignored-gate-failure': base.replace('        id: public_safe\n', '        id: public_safe\n        continue-on-error: true\n'), 'gate-only-after-success': base.replace('        id: public_safe\n        if: always()', '        id: public_safe\n        if: success()'), 'traversal-upload-root': base.replace('build/pos-public-artifacts/android-native-io', 'build/pos-public-artifacts/../../native-io-output'), 'missing-gate': base.replace('        id: public_safe\n', '        id: changed\n'), 'gate-after-upload': base[:base.index('      - name: Validate public native diagnostics')] + base[base.index('      - name: Retain exact inputs'):] + base[base.index('      - name: Validate public native diagnostics'):base.index('      - name: Retain exact inputs')]}
mutation_results = []
for name, text in mutations.items():
    path = archive / (name + '.yml')
    path.write_text(text)
    p = subprocess.run(['ruby', 'scripts/pos/validate_publication_workflows.rb', str(path)], text=True, capture_output=True)
    assert p.returncode != 0, name
    mutation_results.append({'case': name, 'rejected': True})
ruby = "require 'json';require 'yaml';require 'open3';out=[];Dir['.github/workflows/pos-*.yml'].sort.each do |p|;previous=YAML.safe_load(Open3.capture2('git','show',ARGV[0]+':'+p)[0]);current=YAML.load_file(p);before=Marshal.load(Marshal.dump(previous));after=Marshal.load(Marshal.dump(current));[before,after].each do |d|;d.fetch('jobs').each do |j,job|;job['steps'].reject! {|s| s['id']=='public_safe' || s['uses'].to_s.start_with?('actions/upload-artifact@')};if p.end_with?('pos-stock-native.yml');job['steps'].reject!{|s| ['Retain actual results and sanitize diagnostics','Collect actual result XML without modifying diagnostics'].include?(s['name'])};end;end;end;raise p+' changed existing execution/guard' unless before==after;out<<File.basename(p);end;puts JSON.generate(out)"
comparison = subprocess.run(['ruby', '-e', ruby, arguments.baseline], capture_output=True, text=True)
assert comparison.returncode == 0, comparison.stderr
files = json.loads(comparison.stdout)
before = subprocess.check_output(['git', 'show', arguments.baseline + ':.github/workflows/pos-stock-native.yml'], text=True)
current = (root / '.github/workflows/pos-stock-native.yml').read_text()
for expected in ('for module in inventory-store sales-store;', 'directory="apps/pos/adapters/$module/build/test-results/iosSimulatorArm64Test"', 'find "$directory" -maxdepth 1 -type f -name \'*.xml\' -exec cp {} "stock-native-output/results/$module/" \\;'):
    assert expected in before and expected in current, expected
assert 'python3 scripts/pos/sanitize_diagnostics.py --root stock-native-output' not in current
report = {'schema': 'pos-native-diagnostic-publication-checks/v1', 'base_revision': revision, 'comparison_baseline': arguments.baseline, 'source_files_sha256': source_hashes, 'preserved_predecessor_cases': prior, 'collector_cases': results, 'workflow_guard_mutations': mutation_results, 'unchanged_execution_and_guard_workflows': files, 'stock_collection': 'Existing XML directories/copy preserved; in-place sanitizer/manifest replaced with separate staged copy', 'limits': 'Implementer local supporting checks with synthetic inputs and opaque package bytes. No independent/native/signature/OS/Actions/public-upload qualification; no full secret scanning. No POS build/test assertion/frozen expectation or visibility change.'}
(report_directory / 'check-results.json').write_text(json.dumps(report, indent=2) + '\n')
print('PASS:', len(prior), 'preserved publication cases;', len(results), 'collector cases;', len(mutation_results), 'rejected workflow mutations;', len(files), 'workflow execution/guard comparisons')
