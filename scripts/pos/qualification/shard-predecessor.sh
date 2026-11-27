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
plan=original
if [[ -f "$stage/inputs/POS-SHARD-PLAN" ]]; then
    plan=$(cat "$stage/inputs/POS-SHARD-PLAN")
    [[ "$plan" == short-100k-v1 ]]
fi
case "$plan:$mode:$segment" in
    original:pilot:0|original:full:0|original:full:1|original:full:2|short-100k-v1:full:[2-5])
        printf 'handoff=true\n' >> "$GITHUB_OUTPUT" ;;
    original:pilot:1|original:full:3|short-100k-v1:full:6) ;;
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
predecessor_workflow=$GITHUB_SHA
if [[ "$plan" == short-100k-v1 ]]; then
    [[ "$revision" == cbe3eaaa7f552d22e0240e3fdfbea97255b96280 ]]
    if (( segment == 2 )); then
        # Resume only the already-running second shard from the previous workflow revision.
        [[ "$previous:$attempt" == 45943605033:1 ]]
        predecessor_workflow=0f1341a6ddc982785328eed410ecb3deb759f8fd
    elif (( segment == 4 )); then
        # The fourth shard was dispatched before the matching-diagnostics workflow change.
        [[ "$previous:$attempt" == 45986212484:1 ]]
        predecessor_workflow=1fd7fc78f451c1e616d013d5d572faac5893ff8c
    fi
fi
expected_title="POS native checkout-reopen-diagnostic @ $revision"
gh api "repos/$GITHUB_REPOSITORY/actions/runs/$previous" | jq -e \
    --arg title "$expected_title" --arg sha "$predecessor_workflow" --arg repo "$GITHUB_REPOSITORY" --arg attempt "$attempt" \
    '.status == "completed" and .conclusion == "success" and .head_sha == $sha and .head_branch == "main" and
     .repository.full_name == $repo and .event == "workflow_dispatch" and
     (.run_attempt | tostring) == $attempt and .display_title == $title' >/dev/null
destination="$stage/shard-import"
mkdir -m700 "$destination"
gh run download "$previous" --repo "$GITHUB_REPOSITORY" --name "pos-shard-state-$previous-$attempt" \
    --dir "$destination" >/dev/null
test -s "$destination/checkpoint.gpg"
test "$(find "$destination" -mindepth 1 -maxdepth 1 -printf '%f\n')" = 'checkpoint.gpg'
