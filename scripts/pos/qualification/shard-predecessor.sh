#!/usr/bin/env bash
set -euo pipefail
umask 077
test "$GITHUB_REPOSITORY" = 'kira-manga/test'
test "$GITHUB_REF" = 'refs/heads/main'
stage="$RUNNER_TEMP/pos-qualification"
configuration="$stage/inputs/POS-SHARD-CAMPAIGN.json"
if [[ ! -f "$configuration" ]]; then exit 0; fi
[[ -f "$stage/inputs/POS-SHARD-SECRET" ]]
[[ -f "$stage/inputs/runner/shard-continuation.py" ]]
mode=$(jq -er '.mode | select(. == "pilot" or . == "full")' "$configuration")
segment=$(jq -er '.segment | select(type == "number" and . == floor and . >= 0)' "$configuration")
case "$mode:$segment" in
    pilot:0|full:0|full:1|full:2) printf 'handoff=true\n' >> "$GITHUB_OUTPUT" ;;
    pilot:1|full:3) ;;
    *) exit 64 ;;
esac
reference="$stage/inputs/POS-SHARD-PREDECESSOR"
if (( segment == 0 )); then
    test ! -e "$reference"
    exit 0
fi
read -r previous attempt extra < "$reference"
[[ "$previous" =~ ^[1-9][0-9]{0,17}$ && "$attempt" =~ ^[1-9][0-9]{0,5}$ && -z "$extra" ]]
[[ "$previous" != "$GITHUB_RUN_ID" ]]
revision=$(cat "$stage/inputs/POS-REVISION")
expected_title="POS native checkout-reopen-diagnostic @ $revision"
gh api "repos/$GITHUB_REPOSITORY/actions/runs/$previous" | jq -e \
    --arg title "$expected_title" --arg sha "$GITHUB_SHA" --arg repo "$GITHUB_REPOSITORY" --arg attempt "$attempt" \
    '.status == "completed" and .conclusion == "success" and .head_sha == $sha and .head_branch == "main" and
     .repository.full_name == $repo and .event == "workflow_dispatch" and
     (.run_attempt | tostring) == $attempt and .display_title == $title' >/dev/null
destination="$stage/shard-import"
mkdir -m700 "$destination"
gh run download "$previous" --repo "$GITHUB_REPOSITORY" --name "pos-shard-state-$previous-$attempt" \
    --dir "$destination" >/dev/null
test -s "$destination/checkpoint.gpg"
test "$(find "$destination" -mindepth 1 -maxdepth 1 -printf '%f\n')" = 'checkpoint.gpg'
