import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import yaml from 'js-yaml';
import {generate, loadSpecification, project, transformations} from './generate.mjs';

const sources = path.join(project, '.workspace/sources');
const gofmt = path.join(project, '.workspace/toolchains/go1.27.3/go/bin/gofmt');

test('new YAML has strict source shape, explicit shared relations and no approximate scalars', () => {
  assert.equal(Object.keys(loadSpecification().models).length, 7);
  assert.throws(() => yaml.load('BranchPos:\n  A: 1\n  A: 2\n'), /duplicated mapping key/);
});

test('real Go emitter only imports referenced packages', async () => {
  const fun = await transformations(sources);
  try {
    const scalar = fun.go({moduleName: 'BranchPos', modelName: 'Probe', model: {value: {type: 'string'}}});
    assert.ok(!scalar.includes('"time"'));
    assert.ok(!scalar.includes('"github.com/paulmach/orb"'));
    const rich = fun.go({moduleName: 'BranchPos', modelName: 'Probe', model: {date: {type: 'datetime'}, data: {type: 'raw'}, geo: {type: 'geo'}}});
    assert.ok(rich.includes('"time"') && rich.includes('"go-viki/types/mongo"') && rich.includes('"github.com/paulmach/orb"'));
  } finally { fun.cleanup(); }
});

test('real registry is sorted, extension-restricted and ignores helpers/tests', async () => {
  const fun = await transformations(sources);
  try {
    const left = fun.registry({z: ['z.go', 'a_helpers.go', 'z_test.go', 'readme.md'], a: ['b.go', 'a.go']});
    const right = fun.registry({a: ['a.go', 'b.go'], z: ['readme.md', 'z_test.go', 'a_helpers.go', 'z.go']});
    assert.equal(left, right);
    assert.ok(left.includes('reflect.TypeFor[a.AModel]()'));
    assert.ok(!left.includes('Helper') && !left.includes('Test') && !left.includes('readme'));
    assert.throws(() => fun.registry({'../escape': ['a.go']}));
  } finally { fun.cleanup(); }
});

test('isolated real generation is deterministic and rejects unsafe output', async () => {
  const root = path.join(project, 'tools/backend/.work');
  fs.mkdirSync(root, {recursive: true});
  const out = fs.mkdtempSync(path.join(root, 'test-output-'));
  try {
    const one = await generate({sources, out, gofmt});
    const two = await generate({sources, out, gofmt});
    assert.deepEqual(one, two);
    assert.ok(one.existingYamlCount >= 54);
    await assert.rejects(generate({sources, out: path.join(sources, 'viki-go'), gofmt}), /output must/);
    const evil = path.join(root, 'symlink-output');
    fs.symlinkSync(path.join(sources, 'viki-go'), evil);
    try { await assert.rejects(generate({sources, out: evil, gofmt}), /symlink/); }
    finally { fs.unlinkSync(evil); }
  } finally { fs.rmSync(out, {recursive: true, force: true}); }
});
