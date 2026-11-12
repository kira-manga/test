#!/usr/bin/env bash
# Lightweight validation for the POS native packaging configuration.
# This does not build the app; it catches script/YAML/project syntax mistakes
# before a macOS or Windows runner is spent.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

fail() { echo "error: $*" >&2; exit 1; }

echo "== shell syntax =="
while IFS= read -r -d '' script; do
  bash -n "$script"
  echo "ok shell: $script"
done < <(find scripts/pos apps/pos/apps/ios -type f -name '*.sh' -print0)

[[ -f apps/pos/apps/ios/xcode-build-framework.sh ]] || fail "iOS framework build phase is missing"

echo "== python syntax =="
while IFS= read -r -d '' script; do
  PYTHONDONTWRITEBYTECODE=1 python3 -m py_compile "$script"
  echo "ok python: $script"
done < <(find scripts/pos -type f -name '*.py' -print0)

echo "== xml/plist/project =="
command -v xmllint >/dev/null 2>&1 || fail "xmllint is required"
xmllint --noout apps/pos/apps/ios/Info.plist
xmllint --noout apps/pos/apps/ios/UkkeraPos.entitlements
xmllint --noout apps/pos/apps/ios/UkkeraPos.xcodeproj/xcshareddata/xcschemes/UkkeraPos.xcscheme

python3 - <<'PY'
import re
import plistlib
from pathlib import Path

project = Path("apps/pos/apps/ios/UkkeraPos.xcodeproj/project.pbxproj").read_text()
entitlements = plistlib.loads(Path("apps/pos/apps/ios/UkkeraPos.entitlements").read_bytes())
if entitlements != {"keychain-access-groups": ["$(AppIdentifierPrefix)$(PRODUCT_BUNDLE_IDENTIFIER)"]}:
    raise SystemExit("iOS entitlements must contain only the exact profile-prefix + bundle Keychain group")
if project.count("CODE_SIGN_ENTITLEMENTS = UkkeraPos.entitlements;") != 2:
    raise SystemExit("both iOS configurations must reference the exact Keychain entitlements file")

if project.count("{") != project.count("}"):
    raise SystemExit("project.pbxproj has unbalanced braces")
target_ids = re.findall(r"^\s*([A-F0-9]{24}) = \{\n\s*isa = PBXNativeTarget;", project, re.M)
if len(target_ids) != 1:
    raise SystemExit(f"expected one PBXNativeTarget, found {target_ids}")
target_id = target_ids[0]
scheme = Path("apps/pos/apps/ios/UkkeraPos.xcodeproj/xcshareddata/xcschemes/UkkeraPos.xcscheme").read_text()
if not re.search(rf'BlueprintIdentifier\s*=\s*"{re.escape(target_id)}"', scheme):
    raise SystemExit("shared scheme does not reference the native target")
if "container:UkkeraPos.xcodeproj" not in scheme:
    raise SystemExit("shared scheme has an unexpected project reference")
print(f"ok project: native target {target_id} is referenced by the shared scheme")
PY

echo "== gradle packaging config =="
grep -q 'TargetFormat.Dmg' apps/pos/apps/desktop/build.gradle.kts || fail "desktop DMG target missing"
grep -q 'TargetFormat.Msi' apps/pos/apps/desktop/build.gradle.kts || fail "desktop MSI target missing"
grep -q 'upgradeUuid' apps/pos/apps/desktop/build.gradle.kts || fail "stable MSI upgradeUuid missing"
grep -q 'bundleID' apps/pos/apps/desktop/build.gradle.kts || fail "macOS bundle ID missing"
echo "ok desktop packaging DSL"

echo "== workflows =="
command -v ruby >/dev/null 2>&1 || fail "ruby (psych) is required for YAML validation"
for workflow in .github/workflows/*.yml; do
  ruby -ryaml -e 'YAML.load_file(ARGV[0])' "$workflow"
  if grep -qE '^\s*(push|pull_request|schedule):' "$workflow"; then
    fail "workflow must be manual workflow_dispatch only: $workflow"
  fi
  grep -q 'workflow_dispatch:' "$workflow" || fail "workflow_dispatch missing: $workflow"
  grep -q 'kira-manga/test' "$workflow" || fail "auxiliary-repository guard missing: $workflow"
  echo "ok workflow: $workflow"
done

echo "== public upload boundaries =="
ruby scripts/pos/validate_publication_workflows.rb

echo "== artifact hygiene =="
python3 - <<'PY'
import contextlib
import importlib.util
import io

spec = importlib.util.spec_from_file_location("manifest", "scripts/pos/artifact_manifest.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
for forbidden in ("local.properties", "store.p12", "history.sqlite", "id_rsa"):
    try:
        with contextlib.redirect_stderr(io.StringIO()):
            module.check_path(forbidden, False)
    except SystemExit:
        continue
    raise SystemExit(f"artifact hygiene did not reject {forbidden}")
print("ok artifact hygiene rejects credentials, keys and native databases")
PY

find scripts/pos -type d -name __pycache__ -prune -exec rm -rf {} +

echo "All POS native packaging configuration checks passed."
