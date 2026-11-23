#!/usr/bin/env bash
set -euo pipefail
umask 077
stage="$RUNNER_TEMP/pos-qualification"
source="$stage/source/apps/pos"
out="$stage/public"
fixture="$stage/recovery-inputs"
export POS_C22_FIXTURE="$fixture"
export POS_C22_CLOCK="$fixture/fixture.utc"
cd "$source"
cc -shared -fPIC -O2 -Wall -Wextra "$stage/inputs/runner/controlled-utc.c" -o "$fixture/controlled-utc.so"
printf '%s 0\n' "$(date -ud '2030-01-15T10:00:25Z' +%s)" > "$POS_C22_CLOCK"
cat > "$fixture/init.gradle" <<'GRADLE'
gradle.beforeProject { p ->
    if (p.path == ':core:database') {
        p.afterEvaluate {
            def fixture = new File(System.getenv('POS_C22_FIXTURE'))
            def kmp = p.extensions.getByName('kotlin')
            kmp.sourceSets.getByName('jvmTest').kotlin.srcDir(new File(fixture, 'rung-src'))
            def compilation = kmp.targets.getByName('jvm').compilations.getByName('test')
            p.tasks.register('posHostedC22Recovery', JavaExec) {
                dependsOn 'jvmTestClasses'
                classpath = p.files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
                mainClass.set('com.ukkera.pos.database.FiniteC22Inputs')
                maxHeapSize = '256m'
                jvmArgs '-XX:ActiveProcessorCount=2'
                def variant = new File(fixture, 'VARIANT')
                if (variant.isFile() && variant.text.trim() == 'c22-native-intact-missing-witness-v1.0.4') {
                    systemProperty 'ukkera.lan.verifierDiagnostics', 'true'
                }
                environment 'LD_PRELOAD', new File(fixture, 'controlled-utc.so').absolutePath
                environment 'UKKERA_CHB_TEST_UTC_FILE', System.getenv('POS_C22_CLOCK')
                args new File(fixture, 'synthetic-c22.pdf').absolutePath
            }
        }
    }
}
GRADLE
checks=()
if [[ -f "$fixture/VARIANT" ]] && [[ "$(<"$fixture/VARIANT")" == c22-native-approval-source-unavailable-v1.0.1 ]]; then
  freeze=qa/pos/freezes/food-recipe-quotation-verification-binding-v1
  test -f "$fixture/$freeze/freeze.json"
  test ! -e "$stage/source/$freeze"
  mkdir -p "$(dirname "$stage/source/$freeze")"
  cp -a "$fixture/$freeze" "$stage/source/$freeze"
  checks+=(:adapters:catalog-store:jvmTest :apps:android:compileLocalTestingKotlin :apps:desktop:compileKotlin)
fi
result=0
python3 "$GITHUB_WORKSPACE/scripts/pos/qualification/watched.py" "$out" recovery 95 \
  ./gradlew --no-daemon --max-workers=2 --console=plain \
  '-Dorg.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=1024m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process -I "$fixture/init.gradle" :core:database:posHostedC22Recovery "${checks[@]}" || result=$?
[[ -f "$stage/recovery.raw" ]] || exit "$result"
python3 - "$out" "$stage/recovery.raw" "$result" "$fixture" <<'PY'
import json,re,sqlite3,sys
from pathlib import Path
from xml.etree import ElementTree
out,log=map(Path,sys.argv[1:3])
rc=int(sys.argv[3])
lines=log.read_text().splitlines()
expected=[
  "POS_279_C22_ORIGINAL_INPUTS quote=55000 accepted=22 supplier_held=2 raw_claim=55000 payable=0 receipt_sequence=6",
  "POS_279_C22_AFTER_EXPIRY matched=22 recognized=55000 tax=0 same_id_lookup=1 exact_replay=1 case_rows=1",
  "POS_279_C22_COMMITTED_REOPEN same_id_lookup=1 exact_replay=1 case_rows=1 invoice=unchanged receipt=unchanged",
  "POS_279_C22_NATIVE_CLOSE",
]
variant=Path(sys.argv[4])/"VARIANT"
variant_name=variant.read_text().strip() if variant.is_file() else ""
assert variant_name in ("", "c22-active-reauth-v1.0.0", "c22-native-approval-source-unavailable-v1.0.1",
                        "c22-native-persisted-missing-witness-v1.0.3", "c22-native-intact-missing-witness-v1.0.4",
                        "c22-native-misbound-witness-v1.0.5")
intact_missing=variant_name=="c22-native-intact-missing-witness-v1.0.4"
persisted_missing=variant_name=="c22-native-persisted-missing-witness-v1.0.3" or intact_missing
misbound=variant_name=="c22-native-misbound-witness-v1.0.5"
active_reauth=bool(variant_name) and not (persisted_missing or misbound)
source_unavailable=variant_name=="c22-native-approval-source-unavailable-v1.0.1"
if active_reauth:
    expected.insert(1,"POS_279_C22_ACTIVE_REAUTH old_execute=denied old_lookup=denied precommit_rows=0")
if source_unavailable:
    expected[1:1]=[
        f"POS_279_C22_WITNESS_SOURCE_DEFERRED {stage} quote_hidden=1 same_id_deferrals=2 observed_source_checks=3 terminal_rows=0"
        for stage in ("active","native_reopen")
    ]
if persisted_missing:
    expected=[
        "POS_279_C22_PERSISTED_MISSING_WITNESS_ACTIVE revoked=1 same_id_deferrals=2 terminal_rows=0 raw_claim_unchanged=1 receipt_unchanged=1",
        "POS_279_C22_PERSISTED_MISSING_WITNESS_NATIVE_HOLD no_operational_client=1 terminal_rows=0",
        "POS_279_C22_NATIVE_CLOSE",
    ]
if misbound:
    expected=[
        "POS_279_C22_MISBOUND_WITNESS_ACTIVE revoked=1 native_deadline_checks=3 same_id_deferrals=2 terminal_rows=0 raw_claim_unchanged=1 receipt_unchanged=1",
        "POS_279_C22_MISBOUND_WITNESS_NATIVE_HOLD no_operational_client=1 terminal_rows=0",
        "POS_279_C22_NATIVE_CLOSE",
    ]
verified=[line for line in expected if lines.count(line)==1]
if intact_missing:
    expected.append("FINITE_PURCHASE_APPROVAL_INTACT_DECISION_ABSENT")
    marker="Native physical stage: FINITE_PURCHASE_APPROVAL_INTACT_DECISION_ABSENT"
    if sum(line.endswith(marker) for line in lines)==1:
        verified.append("FINITE_PURCHASE_APPROVAL_INTACT_DECISION_ABSENT")
record={"verified_assertions":verified,"exit":rc,"historical_missing_witness_tested":persisted_missing and verified==expected and rc==0,
        "authentic_misbound_witness_tested":misbound and verified==expected and rc==0,
        "active_reauthorization_verified":active_reauth and verified==expected and rc==0,
        "installed_source_unavailability_verified":source_unavailable and verified==expected and rc==0}
if rc == 0 and verified == expected:
    root,=[line.rsplit(" at ",1)[1] for line in lines
           if re.fullmatch(r"Supporting business native originals retained at /var/tmp/lan-business-native-test[0-9]+",line)]
    database,=Path(root).rglob("master.db")
    assert not Path(str(database)+"-wal").exists()
    journal=Path(str(database)+"-journal")
    assert not journal.exists() or journal.stat().st_size==0
    with sqlite3.connect(database.as_uri()+"?mode=ro&immutable=1",uri=True) as conn:
        counts={t:conn.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
                for t in ("operation_result","journal_envelope","audit_event","outbox_intent")}
    assert set(counts.values())=={10 if persisted_missing or misbound else 11}
    record["confirmed_terminal_rows"]=counts
    if source_unavailable:
        reports=Path("adapters/catalog-store/build/test-results/jvmTest")
        suites=[ElementTree.parse(file).getroot() for file in reports.glob("TEST-*.xml")]
        assert suites
        regression={name:sum(int(suite.get(name)) for suite in suites)
                    for name in ("tests","failures","errors","skipped")}
        assert regression["tests"]>0 and all(regression[name]==0 for name in ("failures","errors","skipped"))
        record["catalog_store_regression"]=regression
        record["platform_compile_completed"]=["Android LocalTesting","Desktop JVM"]
(out/"recovery-observed.json").write_text(json.dumps(record,indent=2)+"\n")
print(json.dumps(record))
sys.exit(rc or (0 if verified==expected else 1))
PY
