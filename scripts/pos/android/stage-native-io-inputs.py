#!/usr/bin/env python3
"""Stage public signed APKs and exact provenance; never copies signing material."""
import argparse
import hashlib
import json
import pathlib
import re
import shutil
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--app-artifact-directory', required=True, type=pathlib.Path)
p.add_argument('--runtime-tests-apk', required=True, type=pathlib.Path)
p.add_argument('--runtime-tests-source-revision', required=True)
p.add_argument('--apksigner', required=True)
p.add_argument('--out', required=True, type=pathlib.Path)
a = p.parse_args()
metadata = json.loads((a.app_artifact_directory / 'artifact.json').read_text())
assert re.fullmatch(r'[0-9a-f]{40}', metadata['source_revision'])
assert re.fullmatch(r'[0-9a-f]{40}', a.runtime_tests_source_revision)
assert metadata['working_tree_dirty'] is False
app = a.app_artifact_directory / 'ukkera-pos-testing.apk'
assert metadata['apk_sha256'] == hashlib.sha256(app.read_bytes()).hexdigest()
assert metadata['apk_bytes'] == app.stat().st_size
reports = []
for apk in [app, a.runtime_tests_apk]:
    report = subprocess.check_output([a.apksigner, 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    certs = re.findall(r'^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})$', report, re.M)
    assert len(certs) == 1, 'Exactly one observed signer certificate is required'
    reports.append(certs[0].lower())
assert reports[0] == reports[1], 'Application and instrumentation signers differ'
a.out.mkdir(parents=True, exist_ok=True)
manifest = {'source_revision': metadata['source_revision'],
            'runtime_tests_source_revision': a.runtime_tests_source_revision,
            'signer_certificate_sha256': reports[0],
            'limits': 'Source revisions are build provenance, not native or owner acceptance.'}
for key, src, name in [('app', app, 'ukkera-pos-testing.apk'),
                       ('runtime_tests', a.runtime_tests_apk, 'ukkera-pos-runtime-tests.apk')]:
    dst = a.out / name
    shutil.copyfile(src, dst)
    manifest[key] = {'filename': name, 'sha256': hashlib.sha256(dst.read_bytes()).hexdigest(),
                     'bytes': dst.stat().st_size}
shutil.copyfile(a.app_artifact_directory / 'artifact.json', a.out / 'artifact.json')
(a.out / 'apk-pair.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps(manifest, indent=2))
