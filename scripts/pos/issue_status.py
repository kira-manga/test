#!/usr/bin/env python3
"""Read-only GitHub leaf-deliverable inventory. No builds, edits or Actions.

Usage: python3 scripts/pos/issue_status.py --output /tmp/pos-progress.md
Requires an already authenticated gh CLI with repository read access. Projects scope
is unnecessary. Counts deliberately exclude PRs, tracking parents and unrelated SDK
history. Platform totals overlap when a deliverable genuinely covers multiple targets.
"""
import argparse
import collections
import json
import subprocess
from pathlib import Path
from datetime import datetime, timezone

REPOS = ('ukkera/kmp-apps', 'p2pKit/P2pKit')
STATUSES = ('in-progress', 'in-review', 'paused', 'blocked', 'not-started', 'ready', 'verified')

def get_issues(repo):
    raw = subprocess.check_output([
        'gh', 'api', '--paginate',
        f'repos/{repo}/issues?state=all&per_page=100',
    ], text=True)
    # Older gh versions emit one JSON array per page and have no --slurp flag.
    decoder = json.JSONDecoder()
    pages = []
    rest = raw.lstrip()
    while rest:
        page, end = decoder.raw_decode(rest)
        pages.append(page)
        rest = rest[end:].lstrip()
    return [issue for page in pages for issue in page if 'pull_request' not in issue]

def inventory(rows):
    leaves = []
    for issue in rows:
        labels = {label['name'] for label in issue['labels']}
        if 'pos:task' not in labels and not (
            issue['number'] == 66 and issue['repository_url'].endswith('/ukkera/kmp-apps')
        ):
            continue
        if issue['state'] == 'closed':
            status = 'cancelled' if issue.get('state_reason') == 'not_planned' else 'completed'
        else:
            statuses = [status for status in STATUSES if 'pos:' + status in labels]
            status = statuses[0] if len(statuses) == 1 else 'needs-classification'
        leaves.append((issue, labels, status))
    return leaves

def summary(leaves):
    c = collections.Counter(status for _, _, status in leaves)
    return {
        'completed': c['completed'], 'active': c['in-progress'],
        'review': c['in-review'], 'paused': c['paused'], 'blocked': c['blocked'],
        'queued': c['not-started'] + c['ready'] + c['verified'],
        'unclassified': c['needs-classification'],
        'remaining': sum(status not in ('completed', 'cancelled') for _, _, status in leaves),
        'cancelled': c['cancelled'],
    }

def render(all_rows):
    now = datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M UTC')
    text = ['# POS deliverable inventory', '', f'Snapshot: {now}. Native Issue state/search counts remain live.', '',
            'Counts are leaf deliverables, not parent issues, requirement acceptance percentages or product readiness.', '',
            '| Repository | Completed | Active | Review | Paused | Blocked | Queued | Remaining |',
            '|---|---:|---:|---:|---:|---:|---:|---:|---:|']
    result = {}
    for repo, rows in all_rows.items():
        leaves = inventory(rows)
        result[repo] = summary(leaves)
        c = result[repo]
        text.append(f'| [{repo}](https://github.com/{repo}/issues) | ' + ' | '.join(
            str(c[k]) for k in ('completed', 'active', 'review', 'paused', 'blocked', 'queued', 'remaining')) + ' |')
    source = inventory(all_rows['ukkera/kmp-apps'])
    for prefix, heading, note in [
        ('work:', 'Source work by deliverable type', 'Each leaf has one work classification.'),
        ('platform:', 'Source work by platform', 'Multiple-platform tasks appear in each applicable row; do not sum these rows.'),
    ]:
        text += ['', '## ' + heading, '', note, '', '| Group | Completed | Active | Review | Paused | Blocked | Queued | Remaining |',
                 '|---|---:|---:|---:|---:|---:|---:|---:|---:|']
        for label in sorted({label for _, labels, _ in source for label in labels if label.startswith(prefix)}):
            c = summary([x for x in source if label in x[1]])
            text.append('| ' + label[len(prefix):] + ' | ' + ' | '.join(
                str(c[k]) for k in ('completed', 'active', 'review', 'paused', 'blocked', 'queued', 'remaining')) + ' |')
    missing = [(r, i['number']) for r, rows in all_rows.items()
               for i, _, status in inventory(rows) if status == 'needs-classification']
    if missing:
        text += ['', 'Needs classification: ' + ', '.join(f'{r}#{n}' for r, n in missing)]
    text += ['', 'SDK counts cover the tracked native/protocol qualification hierarchy; unrelated closed SDK history is excluded.',
             'A closed historical-source result does not verify later code. A queued/blocked/unrun case is not a pass.', '']
    return '\n'.join(text), result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--json-output', type=Path)
    parser.add_argument('--snapshot-directory', type=Path)
    args = parser.parse_args()
    rows = {repo: get_issues(repo) for repo in REPOS}
    if args.snapshot_directory:
        args.snapshot_directory.mkdir(parents=True, exist_ok=True)
        for repo, issues in rows.items():
            (args.snapshot_directory / (repo.replace('/', '--') + '.json')).write_text(json.dumps(issues, indent=2))
    body, data = render(rows)
    if args.output:
        args.output.write_text(body)
    else:
        print(body)
    if args.json_output:
        args.json_output.write_text(json.dumps(data, indent=2) + '\n')

if __name__ == '__main__':
    main()
