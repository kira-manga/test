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
                environment 'LD_PRELOAD', new File(fixture, 'controlled-utc.so').absolutePath
                environment 'UKKERA_CHB_TEST_UTC_FILE', System.getenv('POS_C22_CLOCK')
                args new File(fixture, 'synthetic-c22.pdf').absolutePath
            }
        }
    }
}
GRADLE
result=0
python3 "$GITHUB_WORKSPACE/scripts/pos/qualification/watched.py" "$out" recovery 95 \
  ./gradlew --no-daemon --max-workers=2 --console=plain \
  '-Dorg.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=1024m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process -I "$fixture/init.gradle" :core:database:posHostedC22Recovery || result=$?
[[ -f "$stage/recovery.raw" ]] || exit "$result"
python3 - "$out" "$stage/recovery.raw" "$result" "$fixture" <<'PY'
import json,re,sqlite3,sys
from pathlib import Path
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
active_reauth=variant.is_file()
if active_reauth:
    assert variant.read_text().strip()=="c22-active-reauth-v1.0.0"
    expected.insert(1,"POS_279_C22_ACTIVE_REAUTH old_execute=denied old_lookup=denied precommit_rows=0")
verified=[line for line in expected if lines.count(line)==1]
record={"verified_assertions":verified,"exit":rc,"historical_missing_witness_tested":False,
        "active_reauthorization_verified":active_reauth and verified==expected and rc==0}
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
    assert set(counts.values())=={11}
    record["confirmed_terminal_rows"]=counts
(out/"recovery-observed.json").write_text(json.dumps(record,indent=2)+"\n")
print(json.dumps(record))
sys.exit(rc or (0 if verified==expected else 1))
PY
