#!/usr/bin/env node
// Preserve ONLY explicitly owned source edits from ignored isolated checkouts.
// Checks apply against the exact pinned tree via a TEMPORARY Git index. No
// working-tree resets, application loader, commits, remote calls or gitlinks.
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const sources = path.join(project, '.workspace/sources');
const owned = JSON.parse(fs.readFileSync(path.join(project, 'tools/backend/source-ownership.json'), 'utf8'));
const pins = JSON.parse(fs.readFileSync(path.join(project, 'tools/backend/pins.json'), 'utf8'));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const destination = path.join(project, 'backend/patches');
const work = path.join(project, 'tools/backend/.work');
fs.mkdirSync(destination, {recursive: true});
fs.mkdirSync(work, {recursive: true});
const temp = fs.mkdtempSync(path.join(work, 'patch-index-'));
const manifest = {status: 'EXACT_PIN_APPLY_CHECKED_IN_ISOLATED_INDEX_NOT_RUNTIME', sources: {}};
try {
  for (const [name, paths] of Object.entries(owned)) {
    const repo = path.join(sources, name);
    const git = (args, options = {}) => execFileSync('git', ['-C', repo, ...args], {encoding: 'utf8', env: {...process.env, GIT_NO_REPLACE_OBJECTS: '1'}, ...options});
    assert.equal(git(['rev-parse', 'HEAD']).trim(), pins[name]);
    const tracked = new Set(git(['ls-files']).split('\n'));
    const trackedOwned = paths.filter(file => tracked.has(file));
    let patch = git(['diff', '--binary', '--no-ext-diff', '--no-textconv', '--', ...trackedOwned]);
    const files = {};
    for (const file of paths) {
      assert.match(file, /^[a-zA-Z0-9_/.-]+$/);
      assert.ok(!file.startsWith('/') && !file.split('/').includes('..') && !file.startsWith('internal/document/'));
      const full = path.join(repo, file);
      assert.ok(fs.lstatSync(full).isFile(), `owned source must be a regular file: ${name}/${file}`);
      files[file] = {sha256: hash(fs.readFileSync(full)), newFile: !tracked.has(file)};
      if (tracked.has(file)) continue;
      try { patch += git(['diff', '--no-index', '--no-ext-diff', '--no-textconv', '--', '/dev/null', file]); }
      catch (error) {
        if (error.status !== 1) throw error;
        patch += error.stdout;
      }
    }
    const patchFile = path.join(destination, `${name}.patch`);
    fs.writeFileSync(patchFile, patch);
    const env = {...process.env, GIT_NO_REPLACE_OBJECTS: '1', GIT_INDEX_FILE: path.join(temp, `${name}.index`)};
    git(['read-tree', 'HEAD'], {env});
    git(['apply', '--cached', '--check', patchFile], {env});
    manifest.sources[name] = {pin: pins[name], patch: `${name}.patch`, sha256: hash(patch), bytes: Buffer.byteLength(patch), files, appliesToPinnedTree: true};
  }
  fs.writeFileSync(path.join(destination, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
  console.log(JSON.stringify({status: manifest.status, patches: Object.fromEntries(Object.entries(manifest.sources).map(([name, value]) => [name, {sha256: value.sha256, files: Object.keys(value.files).length}]))}));
} finally {
  fs.rmSync(temp, {recursive: true, force: true});
}
