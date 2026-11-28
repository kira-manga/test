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
    [[ "$plan" == short-100k-v1 || "$plan" == profile-87k-checkout-v1 || "$plan" == profile-87k-checkout-cache-v1 ||
        "$plan" == profile-87k-checkout-tree-io-v1 ]]
fi
case "$plan:$mode:$segment" in
    original:pilot:0|original:full:0|original:full:1|original:full:2|short-100k-v1:full:[2-5])
        printf 'handoff=true\n' >> "$GITHUB_OUTPUT" ;;
    original:pilot:1|original:full:3|short-100k-v1:full:6|profile-87k-checkout-v1:full:6|profile-87k-checkout-cache-v1:full:6|profile-87k-checkout-tree-io-v1:full:6) ;;
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
if [[ "$plan" == profile-87k-checkout-v1 || "$plan" == profile-87k-checkout-cache-v1 || "$plan" == profile-87k-checkout-tree-io-v1 ]]; then
    # This supporting run reads the closed 87k synthetic installation with the
    # separately qualified catalogue reader. It never exports a successor.
    [[ "$previous:$attempt" == 46037079629:1 ]]
    tree=$(cat "$stage/inputs/POS-TREE")
    case "$plan:$revision:$tree" in
        profile-87k-checkout-v1:f9c9ca1dd1947f0bccb1f70e5d33ac928511ee1e:4d4393111192165fa8351f3251a919f10abf3876) ;;
        profile-87k-checkout-cache-v1:b682ccef2d3bc7fb9caca881a82f8101b02d9a11:1464e9ad6e11e5268f64b561ffb8e2bdd183f5f3) ;;
        profile-87k-checkout-cache-v1:fb6865e52c27f71005d00074274bb05a6ef8741c:4be03806cf7f36d1aa908d5e81b7557f7095ce50) ;;
        profile-87k-checkout-cache-v1:ab10fa812e1b835d167971f0d69184c20480fa89:a06693dbadbc91f7d9bb40682be8301072586ae3) ;;
        profile-87k-checkout-tree-io-v1:250830b5d4191bd9db34efd83909d4c521bfbe8c:296abf87b75ebc0a0998e6f6d81c12144fadb799) ;;
        *) exit 64 ;;
    esac
    gh api "repos/$GITHUB_REPOSITORY/actions/runs/46064566361" | jq -e '
      .status == "completed" and .conclusion == "success" and .run_attempt == 1 and
      .head_sha == "239f7d2e17b9782dc901e4b4ea19a8e9e9a99890" and
      .display_title == "POS native checkout-reopen-diagnostic @ cbe3eaaa7f552d22e0240e3fdfbea97255b96280"' >/dev/null
    gh api "repos/$GITHUB_REPOSITORY/actions/artifacts/15230800174" | jq -e '
      .expired == false and .name == "pos-shard-state-46037079629-1" and
      .workflow_run.id == 46037079629' >/dev/null
    revision=cbe3eaaa7f552d22e0240e3fdfbea97255b96280
    predecessor_workflow=239f7d2e17b9782dc901e4b4ea19a8e9e9a99890
fi
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
