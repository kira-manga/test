#!/usr/bin/env node
// Bounded, OFFLINE, in-process verification. Never starts a service or installs
// an index. Missing source/toolchain/cache is a failure, not an auto-download.
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import {sourceEvidence, project} from './generate.mjs';

const sources = path.join(project, '.workspace/sources');
const repo = path.join(sources, 'viki-go');
const go = path.join(project, '.workspace/toolchains/go1.27.3/go/bin/go');
const gofmt = path.join(path.dirname(go), 'gofmt');
const owned = JSON.parse(fs.readFileSync(path.join(project, 'tools/backend/source-ownership.json'), 'utf8'));
const env = {...process.env, GOTOOLCHAIN: 'local', GOPROXY: 'off', GOSUMDB: 'off', GONOPROXY: 'none', GONOSUMDB: '*', GOPRIVATE: '', GOVCS: '*:off', GOTELEMETRY: 'off', GOFLAGS: '', GOMAXPROCS: '2', GOMODCACHE: path.join(project, '.workspace/cache/go/mod'), GOCACHE: path.join(project, '.workspace/cache/go/build')};
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const sourceHashes = () => Object.fromEntries(Object.entries(owned).flatMap(([name, files]) => files.map(file => [`${name}/${file}`, hash(fs.readFileSync(path.join(sources, name, file)))])));
// Optional for standalone local use; the host harness requires its fresh random
// token. Never reflect an arbitrary environment string into evidence or logs.
const invocation = process.env.UKKERA_VERIFICATION_INVOCATION;
const invocationId = typeof invocation === 'string' && /^[0-9a-f]{32}$/.test(invocation) ? invocation : null;
const results = [];
const evidence = {status: 'RUNNING', mode: 'OFFLINE_IN_PROCESS_ONLY', invocationId, startedAt: new Date().toISOString(), results, notExecuted: ['service listeners', 'remote runtime/API calls', 'MongoDB/replica-set operations', 'unique-index installation/migrations', 'production authentication or domain/native mapping adapter', 'projection worker', 'complete recovery proof', 'deployment']};
const evidenceFile = path.join(project, 'backend/evidence/local-verification.json');
fs.mkdirSync(path.dirname(evidenceFile), {recursive: true});
function run(label, binary, args, cwd = project, options = {}) {
  const started = Date.now();
  const result = spawnSync(binary, args, {cwd, env, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024, ...options});
  const finished = Date.now();
  const record = {label, command: [path.relative(project, binary) || binary, ...args.map(arg => arg.startsWith(project + '/') ? path.relative(project, arg) : arg)], cwd: path.relative(project, cwd) || '.', exitCode: result.status, status: result.status === 0 && !result.error && !result.signal ? 'PASS' : 'FAIL', startedAt: new Date(started).toISOString(), finishedAt: new Date(finished).toISOString(), durationMs: finished - started};
  if (args.includes('-json')) {
    const events = (result.stdout || '').split('\n').filter(Boolean).map(line => { try { return JSON.parse(line); } catch { return null; } }).filter(Boolean);
    const packages = {};
    for (const event of events) {
      if (!['pass', 'fail', 'skip'].includes(event.Action)) continue;
      const entry = packages[event.Package] ??= {passedTests: [], failedTests: [], skippedTests: [], status: 'pending'};
      if (event.Test) entry[event.Action === 'pass' ? 'passedTests' : event.Action === 'fail' ? 'failedTests' : 'skippedTests'].push(event.Test);
      else entry.status = event.Action;
    }
    record.packages = packages;
  } else if (label === 'node-tests') {
    record.summary = (result.stdout || '').split('\n').filter(line => /^# (tests|suites|pass|fail|cancelled|skipped|todo) /.test(line));
  }
  results.push(record);
  console.log(`${label}: ${result.status === 0 ? 'PASS' : 'FAIL'} (${record.durationMs}ms)`);
  if (result.status !== 0 || result.error) {
    // Local terminal only, bounded diagnostic. No notification/transcript upload.
    process.stderr.write((result.stderr || result.stdout || result.error?.message || 'command failed').slice(-12000));
    throw new Error(`${label} failed`);
  }
  return (result.stdout || '').trim();
}

try {
  assert.ok(invocation === undefined || invocationId !== null, 'invalid host verification invocation token');
  const before = sourceEvidence(sources);
  const ownedBefore = sourceHashes();
  evidence.sourcePins = before.revisions;
  evidence.originalYamlCount = Object.keys(before.originalYaml).length;
  evidence.sourceHashes = ownedBefore;
  evidence.nodeVersion = process.version;
  evidence.goVersion = run('go-version', go, ['version']);
  assert.match(evidence.goVersion, /^go version go1\.27\.3 /);
  const formatting = run('owned-go-format', gofmt, ['-l', ...owned['viki-go'].filter(file => file.endsWith('.go')).map(file => path.join(repo, file))]);
  assert.equal(formatting, '', 'owned Go sources are not gofmt-clean');
  run('generate-real-models', process.execPath, ['tools/backend/generate.mjs', '--sources', sources, '--out', path.join(project, 'backend/generated'), '--gofmt', gofmt]);
  run('node-tests', process.execPath, ['--test', '--test-reporter=tap', 'tools/backend/generator.test.mjs', 'tools/backend/contract.test.mjs']);
  run('core-permission-race-tests', go, ['test', '-json', '-mod=readonly', '-race', '-p', '2', '-count=1', './internal/branchpos', './internal/helpers/modelguard', './internal/helpers/models/permissions'], repo);
  run('actual-generic-boundaries', go, ['test', '-json', '-mod=readonly', '-p', '2', '-run', '^TestBranchPos', '-count=1', './internal/helpers/db_functions', './internal/helpers/sync', './services/graphql/resolvers', './services/app/handlers', './services/import_export/handlers', './services/app/grpc'], repo);
  run('generated-go-registry-json-bson', process.execPath, ['tools/backend/compile_generated.mjs', '--sources', sources, '--generated', path.join(project, 'backend/generated'), '--go', go]);
  run('preserve-scoped-patches', process.execPath, ['tools/backend/export_patches.mjs']);
  assert.deepEqual(sourceEvidence(sources), before, 'original source/generator inputs changed during tests');
  assert.deepEqual(sourceHashes(), ownedBefore, 'owned source changed during tests');
  evidence.originalYamlUnchanged = true;
  evidence.ownedSourceUnchangedDuringTests = true;
  evidence.artifacts = Object.fromEntries(['backend/generated/manifest.json', 'backend/contracts/golden/evidence-v1.json', 'backend/contracts/golden/ack-v1.json', 'backend/contracts/evidence-v1.schema.json', 'backend/evidence/generated-go-compile.json', 'backend/patches/manifest.json'].map(file => [file, hash(fs.readFileSync(path.join(project, file)))]));
  evidence.status = 'PASS_LOCAL_BOUNDED_GATES_NOT_BACKEND_ACCEPTANCE';
} catch (error) {
  evidence.status = 'FAIL';
  evidence.failure = error.message;
  process.exitCode = 1;
} finally {
  evidence.finishedAt = new Date().toISOString();
  fs.writeFileSync(evidenceFile, JSON.stringify(evidence, null, 2) + '\n');
  console.log(evidence.status);
}
