#!/usr/bin/env python3
"""Verify retained Android test packages against their exact clean source inputs.

Signing remains local. CI compiles the matching source but publishes the retained
signed bytes, preserving test-installation continuity without uploading a key.
This packaging check grants no runtime identity, employee or Master authority.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import subprocess


def sha256(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def verify(args: argparse.Namespace) -> dict:
    for value in (args.source_revision, args.android_source_revision):
        require(re.fullmatch(r'[0-9a-f]{40}', value) is not None, 'Invalid source revision')
    require(re.fullmatch(r'[0-9a-f]{64}', args.signer_sha256) is not None, 'Invalid signer digest')
    require(Path('SOURCE-REVISION.txt').read_text().strip() == args.source_revision,
            'Snapshot source revision differs from requested source')
    pair = json.loads((args.inputs / 'apk-pair.json').read_text())
    require(pair['source_revision'] == pair['runtime_tests_source_revision'] == args.android_source_revision
            and pair['working_tree_dirty'] is False, 'Retained APK source is not the requested clean source')
    require(pair['signer_certificate_sha256'] == args.signer_sha256, 'Unexpected retained signer')
    inventory = json.loads((args.inputs / 'application-source-files.json').read_text())
    require(inventory['source_revision'] == args.android_source_revision, 'Source inventory revision mismatch')
    files = inventory['files']
    paths = [entry['path'] for entry in files]
    tracked = subprocess.check_output(['git', 'ls-files', '-z', '--', 'apps/pos']).decode().split('\0')[:-1]
    require(paths == sorted(set(paths)) == sorted(tracked), 'Incomplete or duplicate application source inventory')
    for entry in files:
        path = Path(entry['path'])
        require(path.is_relative_to('apps/pos') and '..' not in path.parts and path.is_file()
                and not path.is_symlink(), 'Invalid application source path')
        require(sha256(path) == entry['sha256'], 'Application source differs from retained package source')
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    require(bool(sdk), 'Android SDK is not configured')
    require(re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', args.build_tools) is not None, 'Invalid build-tools version')
    tools = Path(sdk) / 'build-tools' / args.build_tools
    results = {}
    for kind, expected_name, package in (
            ('app', 'ukkera-pos-testing.apk', 'com.ukkera.pos.local.testing'),
            ('runtime_tests', 'ukkera-pos-runtime-tests.apk', 'com.ukkera.pos.local.testing.test')):
        record = pair[kind]
        require(record['filename'] == expected_name, 'Unexpected APK filename')
        path = args.inputs / expected_name
        require(path.is_file() and not path.is_symlink(), 'Missing or invalid retained APK')
        require(path.stat().st_size == record['bytes'] and sha256(path) == record['sha256'], 'Retained APK hash/size mismatch')
        report = subprocess.check_output([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(path)], text=True)
        require(re.search(r'^Number of signers: 1$', report, re.M) is not None, 'APK must have one signer')
        certs = {value.lower() for value in re.findall(r'^.*certificate SHA-256 digest: ([0-9a-fA-F]{64})$', report, re.M)}
        require(certs == {args.signer_sha256}, 'APK signer differs from expected retained certificate')
        for scheme in ('v2', 'v3'):
            require(re.search(r'^Verified using ' + scheme + r' scheme.*: true$', report, re.M) is not None,
                    'APK lacks required signature scheme')
        subprocess.run([str(tools / 'zipalign'), '-c', '-P', '16', '4', str(path)], check=True, stdout=subprocess.DEVNULL)
        badging = subprocess.check_output([str(tools / 'aapt'), 'dump', 'badging', str(path)], text=True)
        require(badging.startswith("package: name='" + package + "'") and "sdkVersion:'24'" in badging
                and "targetSdkVersion:'37'" in badging, 'APK package or SDK metadata differs')
        require(('application-debuggable' in badging) == (kind == 'runtime_tests'), 'Unexpected APK debuggability')
        results[kind] = {'filename': expected_name, 'sha256': record['sha256'], 'bytes': record['bytes'],
                         'package': package, 'signature_schemes': ['v2', 'v3'], 'alignment': '16-KiB',
                         'debuggable': kind == 'runtime_tests'}
    return {'status': 'VERIFIED_PACKAGES_NOT_INSTALLED', 'source_revision': args.source_revision,
            'android_source_revision': args.android_source_revision,
            'aux_snapshot_revision': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
            'application_source_files_checked': len(files), 'android_build_tools': args.build_tools,
            'signer_certificate_sha256': args.signer_sha256,
            'run_id': os.environ.get('GITHUB_RUN_ID', 'local'), 'run_attempt': os.environ.get('GITHUB_RUN_ATTEMPT', 'local'),
            **results, 'limits': 'Source/package checks only. No native startup, creation, Master/Client or owner acceptance.'}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-revision', required=True)
    parser.add_argument('--android-source-revision', required=True)
    parser.add_argument('--signer-sha256', required=True)
    parser.add_argument('--inputs', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--build-tools', default='37.0.0', help='Installed Android build-tools version (CI default: 37.0.0)')
    args = parser.parse_args()
    result = verify(args)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('x') as output:
        output.write(json.dumps(result, indent=2) + '\n')
    print('Retained Android packages and complete application source binding verified')


if __name__ == '__main__':
    main()
