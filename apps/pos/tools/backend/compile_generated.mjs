#!/usr/bin/env node
// Compile the REAL generated types and registry using a Go overlay. Never
// install files into viki-go/internal/document or mutate its existing registry.
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {parseArgs} from 'node:util';
import {transformations, sourceEvidence, project} from './generate.mjs';

const {values} = parseArgs({options: {sources: {type: 'string'}, generated: {type: 'string'}, go: {type: 'string'}}, strict: true});
assert.ok(values.sources && values.generated && values.go, 'required --sources PATH --generated PATH --go PATH');
const sources = fs.realpathSync(path.resolve(values.sources));
const generated = fs.realpathSync(path.resolve(values.generated));
const go = fs.realpathSync(path.resolve(values.go));
const repo = path.join(sources, 'viki-go');
const before = sourceEvidence(sources);
const work = path.join(project, 'tools/backend/.work');
fs.mkdirSync(work, {recursive: true});
const temp = fs.mkdtempSync(path.join(work, 'compile-'));
const transform = await transformations(sources);
try {
  const inventory = {};
  const doc = path.join(repo, 'internal/document');
  const originalRegistry = fs.readFileSync(path.join(doc, 'main.go'));
  const registryEntries = code => Object.fromEntries([...code.toString().matchAll(/"([a-z0-9_]+)":\s*reflect\.TypeFor\[([^\]]+)\]\(\)/g)].map(match => [match[1], match[2]]));
  const originalEntries = registryEntries(originalRegistry);
  assert.ok(Object.keys(originalEntries).length > 100, 'real registry inventory missing');
  for (const dir of fs.readdirSync(doc, {withFileTypes: true}).filter(dir => dir.isDirectory())) {
    inventory[dir.name] = fs.readdirSync(path.join(doc, dir.name), {withFileTypes: true}).filter(file => file.isFile()).map(file => file.name);
  }
  const dir = path.join(generated, 'go/internal/document/branch_pos');
  inventory.branch_pos = fs.readdirSync(dir).filter(file => file.endsWith('.go'));
  const registry = path.join(temp, 'registry.go');
  const renderedRegistry = transform.registry(inventory);
  const renderedEntries = registryEntries(renderedRegistry);
  for (const [name, type] of Object.entries(originalEntries)) assert.equal(renderedEntries[name], type, `original registration changed: ${name}`);
  assert.equal(Object.keys(renderedEntries).length, Object.keys(originalEntries).length + inventory.branch_pos.length, 'unexpected aggregate registry change');
  fs.writeFileSync(registry, renderedRegistry);
  const replacements = {[path.join(doc, 'main.go')]: registry};
  for (const file of inventory.branch_pos) replacements[path.join(doc, 'branch_pos', file)] = path.join(dir, file);
  const testFile = path.join(temp, 'generated_contract_test.go');
  fs.writeFileSync(testFile, `package document
import (
 "encoding/json"
 "testing"
 "go-viki/internal/document/branch_pos"
 "go.mongodb.org/mongo-driver/bson"
 "go.mongodb.org/mongo-driver/bson/bsontype"
)
func TestBranchPosGeneratedRegistryAndExactScalars(t *testing.T) {
 for _, name := range []string{"auth__account", "pos__item", "branch_pos__company_profile", "branch_pos__operation_evidence", "branch_pos__ingestion_receipt", "branch_pos__projection_job"} {
  if _, ok := Document[name]; !ok { t.Fatalf("missing real aggregate registration: %s", name) }
 }
 m := &branch_pos.OperationEvidenceModel{DomainId: "00000000-0000-4000-8000-000000000001", Org: "org-a", Company: "company-a", Branch: "branch-a", AuthorityEpoch: "9007199254740993", JournalSequence: "9223372036854775807", CanonicalPayload: "{\\\"minor\\\":\\\"9007199254740993\\\"}"}
 m.ID = m.DomainId
 encoded, err := json.Marshal(m); if err != nil { t.Fatal(err) }
 var decoded map[string]any
 if err := json.Unmarshal(encoded, &decoded); err != nil { t.Fatal(err) }
 if decoded["id"] != m.DomainId || decoded["authority_epoch"] != "9007199254740993" || decoded["journal_sequence"] != "9223372036854775807" { t.Fatalf("JSON scalar drift: %s", encoded) }
 raw, err := bson.Marshal(m); if err != nil { t.Fatal(err) }
 b := bson.Raw(raw)
 for _, field := range []string{"_id", "domain_id", "org", "company", "branch", "authority_epoch", "journal_sequence"} { if b.Lookup(field).Type != bsontype.String { t.Fatalf("not a BSON string: %s", field) } }
 var restored branch_pos.OperationEvidenceModel
 if err := bson.Unmarshal(raw, &restored); err != nil { t.Fatal(err) }
 if restored.ID != m.DomainId || restored.JournalSequence != m.JournalSequence { t.Fatal("BSON exact roundtrip failed") }
}
`);
  replacements[path.join(doc, 'branch_pos_generated_contract_test.go')] = testFile;
  const overlay = path.join(temp, 'overlay.json');
  fs.writeFileSync(overlay, JSON.stringify({Replace: replacements}, null, 2));
  // Go's vet subprocess chdirs into each package. The overlay-only branch_pos
  // directory deliberately does not exist on disk, so vet cannot run here.
  // Disable vet only for this isolated overlay compile; source tests retain it.
  const command = ['test', '-mod=readonly', '-p', '2', '-vet=off', '-overlay', overlay, '-run', '^TestBranchPosGenerated', '-count=1', './internal/document'];
  const output = execFileSync(go, command, {cwd: repo, encoding: 'utf8', env: {...process.env, GOTOOLCHAIN: 'local', GOPROXY: 'off', GOMAXPROCS: '2'}});
  assert.deepEqual(sourceEvidence(sources), before);
  assert.deepEqual(fs.readFileSync(path.join(doc, 'main.go')), originalRegistry, 'on-disk registry changed');
  const evidence = {status: 'GENERATED_GO_REGISTRY_JSON_BSON_VERIFIED_NOT_MONGO_RUNTIME', goVersion: execFileSync(go, ['version'], {encoding: 'utf8'}).trim(), generatedModels: inventory.branch_pos.length, originalRegistrationsPreserved: Object.keys(originalEntries).length, originalRegistrySha256: createHash('sha256').update(originalRegistry).digest('hex'), existingRegistryPreservedOnDisk: true, originalYamlUnchanged: true, output: output.trim(), vet: 'DISABLED_FOR_OVERLAY_ONLY_PACKAGE_DIRECTORY; enabled in ordinary source tests', notExecuted: ['MongoDB operations', 'indexes/migrations', 'generic writes', 'service startup']};
  fs.writeFileSync(path.join(project, 'backend/evidence/generated-go-compile.json'), JSON.stringify(evidence, null, 2) + '\n');
  console.log(JSON.stringify(evidence));
} finally {
  transform.cleanup();
  fs.rmSync(temp, {recursive: true, force: true});
}
