#!/usr/bin/env node
// LOCAL ONLY. Imports only the real pure transformation functions, NEVER the
// broad index.ts, models.ts, configs.ts, shell wrappers or application loader.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import yaml from 'js-yaml';

const here = path.dirname(fileURLToPath(import.meta.url));
export const project = path.resolve(here, '../..');
const work = path.join(here, '.work');
const pins = JSON.parse(fs.readFileSync(path.join(here, 'pins.json'), 'utf8'));
const hash = data => createHash('sha256').update(data).digest('hex');
const transformPaths = [
  'script/createGoLangModel.ts',
  'script/createPythonModel.ts',
  'script/server/go_registry.ts'
];

function git(repo, ...args) {
  return execFileSync('git', ['-C', repo, ...args], {encoding: 'utf8', env: {...process.env, GIT_NO_REPLACE_OBJECTS: '1'}}).trim();
}

export function sourceEvidence(sources) {
  const revisions = {};
  for (const [name, expected] of Object.entries(pins)) {
    revisions[name] = git(path.join(sources, name), 'rev-parse', 'HEAD');
    assert.equal(revisions[name], expected, `unpinned ${name} source`);
  }
  const modules = path.join(sources, 'modules');
  const names = git(modules, 'ls-files', '--', 'DB').split('\n').filter(name => /\.ya?ml$/.test(name)).sort();
  assert.ok(names.length > 0, 'missing original YAML inputs');
  const originalYaml = {};
  for (const name of names) {
    const absolute = path.join(modules, name);
    assert.ok(fs.lstatSync(absolute).isFile(), 'YAML input must not be a symlink');
    const current = fs.readFileSync(absolute);
    const original = execFileSync('git', ['-C', modules, 'show', `HEAD:${name}`]);
    assert.equal(hash(current), hash(original), `existing YAML changed: ${name}`);
    originalYaml[name] = hash(current);
  }
  const sourceHashes = {};
  for (const name of transformPaths) sourceHashes[name] = hash(fs.readFileSync(path.join(sources, 'main-next', name)));
  const gitlinks = {};
  for (const [link, name] of Object.entries({MainApp: 'main-next', Modules: 'modules', MainUtil: 'common-main-r'})) {
    const entry = git(path.join(sources, 'module-manager'), 'ls-tree', 'HEAD', link);
    assert.ok(entry.startsWith(`160000 commit ${revisions[name]}\t`), `${link} does not match pinned source`);
    gitlinks[link] = revisions[name];
  }
  return {revisions, gitlinks, originalYaml, sourceHashes};
}

function assertSafeOutput(out) {
  const allowed = [path.join(project, 'backend/generated'), work];
  assert.ok(allowed.some(root => out === root || out.startsWith(root + path.sep)), 'output must be backend/generated or tools/backend/.work');
  // Resolve every existing ancestor: a symlink must not redirect writes into a
  // source checkout (or elsewhere) despite an apparently safe lexical prefix.
  let cursor = out;
  while (!fs.existsSync(cursor)) cursor = path.dirname(cursor);
  assert.equal(fs.realpathSync(cursor), cursor, 'output parent contains a symlink');
}

export function loadSpecification(source = path.join(project, 'backend/spec/BranchPos.yaml')) {
  const raw = fs.readFileSync(source);
  assert.ok(raw.length <= 256 * 1024, 'specification too large');
  const doc = yaml.load(raw.toString('utf8'), {json: false});
  assert.deepEqual(Object.keys(doc), ['BranchPos'], 'only the new BranchPos namespace is allowed');
  for (const [name, model] of Object.entries(doc.BranchPos)) {
    assert.match(name, /^[A-Z][A-Za-z0-9]*$/);
    assert.equal(model.props?.server?.hide, undefined, 'probe must produce every declared model');
    for (const [field, config] of Object.entries(model)) {
      if (field === 'props' || field === 'front') continue;
      assert.match(field, /^[a-z][a-z0-9_]*$/);
      assert.notEqual(config.type, 'float', 'BranchPos exact values cannot be float');
      assert.notEqual(config.type, 'integer', 'BranchPos int64 wire values must be strings');
      if (config.model?.includes('\\')) assert.match(config.model, /^Org\\(Org|Company|Branch)$/);
    }
    assert.equal(model.domain_id.type, 'string');
    assert.equal(model.org.model, 'Org\\Org');
    assert.equal(model.company.model, 'Org\\Company');
    if (name !== 'CompanyProfile') assert.equal(model.branch.model, 'Org\\Branch');
  }
  return {raw, models: doc.BranchPos};
}

export async function transformations(sources) {
  fs.mkdirSync(work, {recursive: true});
  const temp = fs.mkdtempSync(path.join(work, 'transforms-'));
  for (const name of transformPaths) {
    const dest = path.join(temp, name);
    fs.mkdirSync(path.dirname(dest), {recursive: true});
    fs.copyFileSync(path.join(sources, 'main-next', name), dest);
  }
  const [go, runtime, registry] = await Promise.all(transformPaths.map(name => import(pathToFileURL(path.join(temp, name)).href)));
  return {go: go.CreateGoLangModelString, runtime: runtime.CreatePythonHelperModelString, registry: registry.CreateGoRegistryString,
    cleanup: () => fs.rmSync(temp, {recursive: true, force: true})};
}

async function pass(sources, gofmt, spec, before) {
  const transform = await transformations(sources);
  const temp = fs.mkdtempSync(path.join(work, 'output-'));
  const files = new Map();
  const names = [];
  try {
    for (const modelName of Object.keys(spec.models).sort()) {
      const code = transform.go({moduleName: 'BranchPos', modelName, model: structuredClone(spec.models[modelName])});
      const collection = code.match(/return "(branch_pos__[a-z0-9_]+)"/)[1];
      const file = collection.slice('branch_pos__'.length) + '.go';
      names.push(file);
      files.set(`go/internal/document/branch_pos/${file}`, code);
    }
    const runtime = transform.runtime({moduleName: 'BranchPos', models: structuredClone(spec.models)});
    assert.equal(Object.keys(runtime).length, names.length);
    const relation = runtime.branch_pos__company_profile.fields.branch_bindings;
    assert.equal(relation.model, 'branch_pos__branch_binding');
    assert.equal(relation.mapped, 'company_profile');
    assert.equal(runtime.branch_pos__branch_binding.fields.company.model, 'org__company');
    assert.equal(runtime.branch_pos__branch_binding.fields.branch.model, 'org__branch');
    files.set('go/configs/models/BranchPos.yml', yaml.dump(runtime, {sortKeys: true, noRefs: true, lineWidth: 100}));
    files.set('go/internal/document/main.go', transform.registry({branch_pos: names}));
    files.set('Modules/DB/BranchPos.yaml', spec.raw);
    const goFiles = [];
    for (const [name, content] of files) {
      const dest = path.join(temp, name);
      fs.mkdirSync(path.dirname(dest), {recursive: true});
      fs.writeFileSync(dest, content);
      if (name.endsWith('.go')) goFiles.push(dest);
    }
    execFileSync(gofmt, ['-w', ...goFiles], {stdio: 'pipe'});
    for (const name of files.keys()) files.set(name, fs.readFileSync(path.join(temp, name)));
    assert.deepEqual(sourceEvidence(sources), before, 'generator modified input sources');
    return files;
  } finally {
    transform.cleanup();
    fs.rmSync(temp, {recursive: true, force: true});
  }
}

export async function generate({sources, out, gofmt}) {
  sources = fs.realpathSync(path.resolve(sources));
  out = path.resolve(out);
  gofmt = fs.realpathSync(path.resolve(gofmt));
  assertSafeOutput(out);
  const before = sourceEvidence(sources);
  const spec = loadSpecification();
  const first = await pass(sources, gofmt, spec, before);
  const second = await pass(sources, gofmt, spec, before);
  assert.deepEqual([...first.keys()], [...second.keys()]);
  for (const [name, bytes] of first) assert.equal(hash(bytes), hash(second.get(name)), `nondeterministic: ${name}`);
  const manifest = {
    status: 'ISOLATED_GENERATION_VERIFIED_NOT_RUNTIME_ACCEPTANCE',
    namespace: 'BranchPos', collectionPrefix: 'branch_pos__',
    sourceYaml: 'backend/spec/BranchPos.yaml', sourceYamlSha256: hash(spec.raw),
    node: process.version, ...before,
    existingYamlCount: Object.keys(before.originalYaml).length,
    generatedModels: Object.keys(spec.models).sort(),
    deterministicPasses: 2, existingYamlUnchanged: true,
    files: Object.fromEntries([...first].map(([name, bytes]) => [name, hash(bytes)])),
    notExecuted: ['legacy wrappers', 'application loader', 'service startup', 'database/index installation', 'generic runtime acceptance', 'deployment'],
    separateCompileEvidence: 'backend/evidence/generated-go-compile.json (not established by this generator)',
    pending: ['Mongo replica-set transaction/tenant/unique-index tests', 'protected domain-to-native mapping registry and production adapter', 'production transport authentication and mounted typed endpoint', 'native product DTO/admission integration beyond independent wire goldens']
  };
  first.set('manifest.json', Buffer.from(JSON.stringify(manifest, null, 2) + '\n'));
  if (fs.existsSync(out)) {
    for (const existing of fs.readdirSync(out, {recursive: true, withFileTypes: true})) {
      assert.ok(!existing.isSymbolicLink(), 'output may not contain symlinks');
      if (existing.isFile()) {
        const absolute = path.join(existing.parentPath, existing.name);
        assert.ok(first.has(path.relative(out, absolute)), `refusing to overwrite foreign output: ${absolute}`);
      }
    }
  }
  for (const [name, bytes] of first) {
    const target = path.join(out, name);
    fs.mkdirSync(path.dirname(target), {recursive: true});
    fs.writeFileSync(target, bytes);
  }
  return manifest;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const {values} = parseArgs({options: {sources: {type: 'string'}, out: {type: 'string'}, gofmt: {type: 'string'}}, strict: true});
  assert.ok(values.sources && values.out && values.gofmt, 'required: --sources PATH --out PATH --gofmt PATH (no unsafe defaults)');
  const manifest = await generate(values);
  console.log(JSON.stringify({status: manifest.status, models: manifest.generatedModels.length, unchangedOriginalYaml: manifest.existingYamlCount, deterministicPasses: 2}));
}
