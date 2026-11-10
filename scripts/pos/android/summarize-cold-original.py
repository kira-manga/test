#!/usr/bin/env python3
"""Retain only bounded diagnostic progress from disposable COS-001 evidence.

Original requests, identities, results and private native cuts stay on the runner.
This projection adds observation only; it does not change the executor or pass gate.
"""
import hashlib
import json
import pathlib
import sys

REQUEST_SYMBOLS = (
    'INSTALL_SKU', 'CONFIGURE_WAREHOUSE', 'CONFIGURE_ROUTE', 'APPROVE_OPENING',
    'INITIALIZE_STOCK', 'OPENING_STOCK', 'CONFIGURE_CUSTODIAN', 'CONFIGURE_DRAWER',
    'OPEN_DRAWER', 'CREATE_ORDER', 'BEGIN_COLLECTION', 'ORIGINAL_START',
    'ORIGINAL_OUTCOME', 'ORIGINAL_APPLICATION', 'ORIGINAL_FINALIZATION',
)
REQUEST_STATUSES = frozenset(('RETAINED_BEFORE_DISPATCH', 'DISPATCH_PENDING', 'REPLY_OBSERVED'))
REPLY_KINDS = frozenset(('RECORDED', 'DEFERRED', 'IDENTITY_CONFLICT'))


def summarize(raw):
    try:
        record = json.loads(raw)
    except (ValueError, UnicodeError):
        return {'result': 'EVIDENCE_UNAVAILABLE'}
    assert record['design'] == 'cold-original-start-v1' and record['case'] == 'COS-001'
    summary = {key: record.get(key) for key in (
        'design', 'case', 'qualification', 'protection', 'status', 'unboundFrozenAssertions')}
    summary['events'] = [event.get('step') for event in record.get('events', [])]
    cleanup = [event.get('facts', {}) for event in record.get('events', [])
               if event.get('step') == 'FINAL_CLEANUP']
    summary['cleanup'] = [{key: event.get(key) for key in ('nativeCloseConfirmed', 'failureClass')}
                          for event in cleanup]
    bindings = record.get('bindings', {})
    summary['genuine_sku_publication_binding_present'] = 'GENUINE_SKU_PUBLICATION' in bindings
    requests = record.get('requests', {})
    progress = {}
    for symbol in REQUEST_SYMBOLS:
        if symbol not in requests:
            continue
        request = requests[symbol]
        status = request.get('status')
        kind = (request.get('reply') or {}).get('kind')
        progress[symbol] = {
            'status': status if status in REQUEST_STATUSES else 'UNKNOWN_STATUS',
            'reply_kind': kind if kind in REPLY_KINDS else None,
        }
    summary['request_progress'] = progress
    summary['unknown_request_symbol_count'] = sum(key not in REQUEST_SYMBOLS for key in requests)
    summary['raw_evidence_sha256'] = hashlib.sha256(raw).hexdigest()
    return summary


if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit('usage: summarize-cold-original.py RAW_EVIDENCE SUMMARY')
    raw = pathlib.Path(sys.argv[1]).read_bytes()
    pathlib.Path(sys.argv[2]).write_text(json.dumps(summarize(raw), indent=2) + '\n')
