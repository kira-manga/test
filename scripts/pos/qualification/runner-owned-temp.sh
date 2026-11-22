#!/usr/bin/env bash
# The original fixture intentionally requires a same-owner parent for its private mailbox.
set -euo pipefail
umask 077
scratch="$(mktemp -d "$RUNNER_TEMP/pos-qualification/fixture-tmp.XXXXXXXX")"
uid="$(id -u)"; gid="$(id -g)"
exec sudo -n unshare --mount --propagation private -- bash -euc '
  scratch="$1"; uid="$2"; gid="$3"; home="$4"; path="$5"; java="$6"; android="$7"
  shift 7
  mount --bind "$scratch" /var/tmp
  [[ "$(stat -c %u /var/tmp)" == "$uid" ]]
  exec setpriv --reuid "$uid" --regid "$gid" --init-groups --no-new-privs -- env \
    HOME="$home" PATH="$path" JAVA_HOME="$java" ANDROID_HOME="$android" ANDROID_SDK_ROOT="$android" "$@"
' bash "$scratch" "$uid" "$gid" "$HOME" "$PATH" "$JAVA_HOME" "$ANDROID_HOME" "$@"
