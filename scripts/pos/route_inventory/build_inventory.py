#!/usr/bin/env python3
"""Bind captured registry metadata to current project sources; invokes no POS code."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--metadata', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[3]
    result = json.loads(args.metadata.read_text())
    if result.get('schema') != 'pos-installed-registry-metadata/v1':
        raise ValueError('Unsupported registry metadata schema')
    index: dict[str, list[dict]] = {}
    for source in sorted((root / 'apps/pos').glob('**/src/commonMain/**/*.kt')):
        text = source.read_text()
        package = re.search(r'^package ([\w.]+)', text, re.MULTILINE)
        if not package:
            continue
        for match in re.finditer(r'\b(?:class|object|interface)\s+(\w+)', text):
            name = package[1] + '.' + match[1]
            location = {'path': source.relative_to(root).as_posix(),
                        'line': text.count('\n', 0, match.start()) + 1,
                        'sha256': digest(source)}
            if not any(row['path'] == location['path'] for row in index.get(name, [])):
                index.setdefault(name, []).append(location)

    bound: dict[str, str] = {}

    def locate(name: str) -> list[dict]:
        outer = name.split('$', 1)[0]
        locations = index.get(outer, [])
        if len(locations) != 1:
            raise ValueError(f'Inspect ambiguous or missing source for {name}: {locations}')
        for location in locations:
            bound[location['path']] = location['sha256']
        return locations

    for category in ('commands', 'reads'):
        keys = [row['key'] for row in result[category]]
        if keys != sorted(set(keys)):
            raise ValueError(f'Duplicate or unsorted {category} keys')
        for row in result[category]:
            row['handler_sources'] = locate(row['handler_class'])
            for selector in row['rpc_key_selectors']:
                locate('com.ukkera.pos.database.' + selector)
    for row in result['history_producers']:
        row['sources'] = locate(row['class'])
    for row in result['commands']:
        for candidate in row['rejection_profile_candidates']:
            candidate['sources'] = locate(candidate['class'])
    for row in result['native_original_imports']:
        row['sources'] = locate(row['interpreter_class'])
    if set(command for producer in result['history_producers'] for command in producer['commands']) - set(row['key'] for row in result['commands']):
        raise ValueError('History producer advertises an uninstalled command')
    for relative in (
        'apps/pos/adapters/catalog-store/src/commonMain/kotlin/com/ukkera/pos/catalog/store/LocalBranchCommerceRegistry.kt',
        'apps/pos/adapters/app-gateway/src/androidMain/kotlin/com/ukkera/pos/app/gateway/AndroidLocalBranchRuntime.kt',
        'apps/pos/core/database/src/commonMain/kotlin/com/ukkera/pos/database/AtomicMasterStore.kt',
        'apps/pos/core/database/src/commonMain/kotlin/com/ukkera/pos/database/CommandContract.kt',
        'apps/pos/core/database/src/commonMain/kotlin/com/ukkera/pos/database/ReadContract.kt',
        'apps/pos/core/database/src/commonMain/kotlin/com/ukkera/pos/database/RuntimeBusinessHistoryJoin.kt',
        'apps/pos/core/database/src/commonMain/kotlin/com/ukkera/pos/database/RoomRuntimeHistoryFrame.kt',
        'apps/pos/core/database/src/commonMain/kotlin/com/ukkera/pos/database/NativeOriginalIntake.kt',
        'scripts/pos/route_inventory/RegistryMetadataProbe.java',
        'scripts/pos/route_inventory/classpath.init.gradle',
    ):
        bound[relative] = digest(root / relative)
    # Bind literal protocol declarations to both the client profile switch and actual
    # Host installBusiness call sites. This parses source; it does not open an SDK Host.
    db = root / 'apps/pos/core/database/src/commonMain/kotlin/com/ukkera/pos/database'
    bridge = db / 'LocalMasterClientRpcBridgeV1.kt'
    bridge_text = bridge.read_text()
    host = root / 'apps/pos/adapters/local-rpc/src/commonMain/kotlin/com/ukkera/pos/rpc/LocalRpcMasterSessionIngress.kt'
    host_text = host.read_text()
    protocols = {}
    for source in sorted(db.glob('*.kt')):
        text = source.read_text()
        objects = list(re.finditer(r'public object (LocalMaster\w*RpcProtocolV1)\s*\{', text))
        for i, obj in enumerate(objects):
            chunk = text[obj.end():objects[i+1].start() if i+1 < len(objects) else len(text)]
            literals = list(re.finditer(r'public val (READ|EXECUTE|LOOKUP):.*?name\s*=\s*"([^"]+)"\s*,\s*version\s*=\s*(\d+)', chunk, re.DOTALL))
            if {x[1] for x in literals} != {'READ', 'EXECUTE', 'LOOKUP'} or len(literals) != 3:
                raise ValueError(f'Inspect changed protocol declaration {obj[1]}')
            bound[source.relative_to(root).as_posix()] = digest(source)
            protocols[obj[1]] = [{'operation': x[1], 'name': x[2], 'version': int(x[3]),
                                 'path': source.relative_to(root).as_posix(),
                                 'line': text.count('\n', 0, obj.end() + x.start()) + 1}
                                for x in literals]
    profiles = dict(re.findall(r'LocalMasterRpcWorkflowProfile\.(\w+)\s*->\s*(LocalMaster\w*RpcProtocolV1)\.READ', bridge_text))
    host_profiles = dict(re.findall(r'installBusiness\(configuration, LocalMasterRpcWorkflowProfile\.(\w+),\s*(LocalMaster\w*RpcProtocolV1)\.READ', host_text))
    if profiles != host_profiles or set(profiles.values()) != set(protocols):
        raise ValueError('Inspect client/Host workflow protocol mapping mismatch')
    result['business_rpc_protocols'] = [{'profile': profile, 'protocol_class': name,
                                       'procedures': protocols[name]}
                                      for profile, name in sorted(profiles.items())]
    procedure_ids = [(row['name'], row['version']) for rows in protocols.values() for row in rows]
    if len(procedure_ids) != len(set(procedure_ids)):
        raise ValueError('Duplicate declared RPC procedure')
    bound[host.relative_to(root).as_posix()] = digest(host)
    bound[bridge.relative_to(root).as_posix()] = digest(bridge)
    bound[Path(__file__).resolve().relative_to(root).as_posix()] = digest(Path(__file__).resolve())
    result['source_revision'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    result['working_tree_dirty'] = bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=root))
    result['source_sha256'] = dict(sorted(bound.items()))
    result['metadata_sha256'] = digest(args.metadata)
    result['counts'] = {
        'command_keys': len(result['commands']), 'read_keys': len(result['reads']),
        'history_producer_objects': len(result['history_producers']),
        'commands_with_registered_history': sum(bool(x['history_producer_classes']) for x in result['commands']),
        'commands_without_registered_history': sum(not x['history_producer_classes'] for x in result['commands']),
        'native_original_import_domains': len(result['native_original_imports']),
        'declared_business_rpc_profiles': len(profiles),
        'declared_business_rpc_procedures': len(procedure_ids),
        'rejection_enum_classes': len({p['class'] for row in result['commands'] for p in row['rejection_profile_candidates']}),
    }
    args.output_dir.mkdir(parents=True, exist_ok=True)
    output = args.output_dir / 'inventory.json'
    with output.open('x') as stream:
        json.dump(result, stream, indent=2)
        stream.write('\n')
    for category in ('commands', 'reads'):
        lines = ['# Installed commerce ' + category, '',
                 'Source-bound registry metadata only; registration and key selection do not prove subtype support or authority.', '',
                 '| Route | Installed handler source | History registrations |',
                 '|---|---|---|' if category == 'commands' else '|---|---|---|']
        if category == 'reads':
            lines[-2] = '| Route | Installed handler source | Closed RPC key selectors |'
        for row in result[category]:
            location = row['handler_sources'][0]
            # The generated Markdown lives four directories below the repository root.
            link = f"[{row['handler_class'].split('.')[-1]}](../../../../{location['path']}#L{location['line']})"
            values = row['history_producer_classes'] if category == 'commands' else row['rpc_key_selectors']
            final = ', '.join('`' + value.split('.')[-1] + '`' for value in values)
            lines.append('| `' + row['key'] + '` | ' + link + ' | ' + (final or ('**No registered producer**' if category == 'commands' else 'None in eleven inspected selectors')) + ' |')
        with (args.output_dir / (category + '.md')).open('x') as stream:
            stream.write('\n'.join(lines) + '\n')
    lines = ['# Declared business RPC procedures', '',
             'Source-parsed literal procedures joined to the client profile switch and Host registration call sites. No Host/Client or procedure was invoked.', '',
             '| Profile | Operation | Procedure | Declaration |', '|---|---|---|---|']
    for profile in result['business_rpc_protocols']:
        for row in profile['procedures']:
            lines.append(f"| `{profile['profile']}` | `{row['operation']}` | `{row['name']}/v{row['version']}` | [{profile['protocol_class']}](../../../../{row['path']}#L{row['line']}) |")
    with (args.output_dir / 'rpc-procedures.md').open('x') as stream:
        stream.write('\n'.join(lines) + '\n')
    print(json.dumps({'output': str(output), 'counts': result['counts'], 'bound_source_files': len(bound)}, indent=2))


if __name__ == '__main__':
    main()
