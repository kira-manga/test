#!/usr/bin/env bash
set -euo pipefail
umask 077
stage="$RUNNER_TEMP/pos-qualification"
out="$stage/public"
runtime="$stage/tcp-runtime"
source="$stage/source"
mapping="$source/.workspace/lan-genuine-20261115/input-mapping-supporting-v1.0.1.json"
cd "$source/apps/pos"
if [[ "${1:-}" == exec ]]; then
  ./gradlew --no-daemon --max-workers=2 --console=plain \
    '-Dorg.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=1024m -Dfile.encoding=UTF-8' \
    -Pkotlin.compiler.execution.strategy=in-process -I "$stage/tcp-inputs/runtime.init.gradle" \
    :core:database:jvmTestClasses :core:database:recordNamespaceRuntimeClasspath
  classpath="$(< "$runtime/runtime-classpath.txt")"
  sudo -n unshare --net -- true
  ip -j link show > "$runtime/host.before"
  result=0
  sudo -n env POS_DISPOSABLE_TCP_NAMESPACE=true "$JAVA_HOME/bin/java" -Xmx256m -XX:ActiveProcessorCount=2 \
    -Dukkera.lan.singleNic=true -Djava.io.tmpdir=/var/tmp \
    "-Dukkera.test.runtime.classpath=$classpath" -classpath "$classpath" \
    com.ukkera.pos.database.LanGenuineProcessExecutor "$mapping" > "$runtime/process.log" 2>&1 || result=$?
  ip -j link show > "$runtime/host.after"
  cat "$runtime/process.log"
  [[ "$result" == 0 ]]
  cmp -s "$runtime/host.before" "$runtime/host.after"
  exit
fi
mkdir -m700 "$runtime"
export UKKERA_CHB_ATTEMPT="$runtime"
cp -R "$stage/tcp-inputs/.workspace" "$stage/tcp-inputs/qa" "$source/"
printf '9300d5bb3ffe345e99bf2d0d9e13b117a9a9fb39bd119b6a5e1bff26a4ca9502  %s\n' "$mapping" | sha256sum --check --status
result=0
python3 "$GITHUB_WORKSPACE/scripts/pos/qualification/watched.py" "$out" tcp 100 \
  bash "$GITHUB_WORKSPACE/scripts/pos/qualification/tcp.sh" exec || result=$?
python3 - "$out" "$runtime" "$result" <<'PY'
import hashlib,json,re,subprocess,sys
from pathlib import Path
out,runtime=map(Path,sys.argv[1:3])
rc=int(sys.argv[3])
log=runtime/'process.log'
text=log.read_text(errors='replace') if log.exists() else ''
location=re.findall(r'^Original native files, generated test keys, logs and process observations retained at (/var/tmp/pos-lan-[0-9]+)$',text,re.M)
checks={
 'synthetic_one_master_two_clients': True,
 'test_exit':rc,
 'host_interface_census_equal': (runtime/'host.before').exists() and (runtime/'host.after').exists() and
     (runtime/'host.before').read_bytes()==(runtime/'host.after').read_bytes(),
 'complete': False,
 'markers': {},
 'two_branch_tested':False,
}
if location:
    mailbox=Path(location[-1])/"exchange"
    for name in ('network.ready','master.closed','client1.closed','client2.closed',
                 'client1.live-update','client2.live-update','executor.completed'):
        digest=hashlib.sha256(name.encode("utf-8")).hexdigest()
        checks['markers'][name]=subprocess.run(
            ['sudo','-n','test','-f',str(mailbox/(digest+".bin"))],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        ).returncode == 0
checks['complete']=bool(not rc and checks['host_interface_census_equal'] and len(checks['markers'])==7 and all(checks['markers'].values()))
if not checks['complete']:
    checks['failure_stage']='native_process' if log.exists() else 'compiled_fixture'
    role_exits = dict(re.findall(r'(master|client[12]) failed with exit (-?[0-9]+)',text))
    if role_exits:
        checks['role_exits']={name:int(value) for name,value in role_exits.items()}
    checks['executor_exception_types']=sorted(set(re.findall(
        r'(?m)^(?:Exception in thread "[^"]+" |Caused by: )([A-Za-z0-9_.$]+)(?=:|$)',text)))
    checks['observed_conditions']=[label for cue,label in (
        ("Operation not permitted","namespace_operation_not_permitted"),
        ("An independent JVM failed before network setup","child_before_network"),
        ("Channel was closed","namespace_owner_closed"),
    ) if cue in text]
    if location:
        observed={}
        for role in ('master','client1','client2'):
            child=subprocess.run(['sudo','-n','cat',str(Path(location[-1])/(role+'.log'))],
                stdin=subprocess.DEVNULL, capture_output=True, text=True, errors='replace')
            if child.returncode==0:
                text_log=child.stdout
                # Keep the complete relevant tail private for the optional sealed diagnostic.
                # Preserve only logs, never the exchange or native data directory.
                (runtime/f"{role}.log").write_text(text_log[-2_097_152:])
                enums=sorted(set(re.findall(r'\b(?:NotConnected|DeadlineExceeded|Unauthorized|Negotiation|NotSent|AwaitingResponse|MayHaveExecuted|STATE_UNCERTAIN|ACTIVE_DEADLINE_REACHED)\b',child.stdout)))
                if enums:
                    observed[role]=enums
        if observed:
            checks['role_diagnostic_enums']=observed
(out/'tcp-observed.json').write_text(json.dumps(checks,indent=2)+'\n')
print(json.dumps(checks))
sys.exit(0 if checks['complete'] else rc or 1)
PY
