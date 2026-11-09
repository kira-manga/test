#!/usr/bin/env node
// Local candidate wire schemas, not generated Mongo models or authorization.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {parseArgs} from 'node:util';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const ref = name => ({$ref: `#/$defs/${name}`});
const object = properties => ({type: 'object', additionalProperties: false, required: Object.keys(properties), properties});
// Exact int64 lexical bounds WITHOUT converting financial values to Number.
function positiveThrough(maximum) {
  const alternatives = ['[1-9][0-9]{0,17}'];
  for (let i = 0; i < maximum.length; i++) {
    const lower = i === 0 ? 1 : 0;
    const upper = Number(maximum[i]) - 1;
    if (upper < lower) continue;
    const digit = lower === upper ? `${lower}` : `[${lower}-${upper}]`;
    const remaining = maximum.length - i - 1;
    alternatives.push(maximum.slice(0, i) + digit + (remaining ? `[0-9]{${remaining}}` : ''));
  }
  return `(?:${alternatives.concat(maximum).join('|')})`;
}
const positive = positiveThrough('9223372036854775807');
const negative = positiveThrough('9223372036854775808');
const effectCommon = {effectId: ref('UUID'), kind: {enum: ['cashPosted', 'stockPosted']}, resourceId: ref('UUID'), revision: ref('NonnegativeInt64')};
const effect = {
  type: 'object', additionalProperties: false, required: Object.keys(effectCommon),
  properties: {...effectCommon, money: {oneOf: [ref('Money'), {type: 'null'}]}, quantity: {oneOf: [ref('Quantity'), {type: 'null'}]}},
  oneOf: [
    {properties: {kind: {const: 'cashPosted'}, money: ref('Money'), quantity: {type: 'null'}}, required: ['money']},
    {properties: {kind: {const: 'stockPosted'}, quantity: ref('Quantity'), money: {type: 'null'}}, required: ['quantity']}
  ],
  $comment: 'The unused optional member may be absent/null on input; canonical payload omits it. Effect IDs must additionally be unique within a payload.'
};
export const schema = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  $id: 'urn:ukkera:branch-pos:evidence-v1:candidate',
  title: 'BranchPos evidence-v1 candidate request and confirmed-commit ACK',
  $comment: 'NOT MOUNTED. JSON Schema is structural, not authorization. Runtime additionally checks calendar dates, duplicate JSON keys, UTF-8/body bound, hash/signature, mapping/admission, effect-ID uniqueness, and checkpoint relations. Canonical payload field order is the ordered Payload.required list; this is not generic RFC 8785.',
  oneOf: [ref('Batch'), ref('Acknowledgement')],
  $defs: {
    UUID: {type: 'string', pattern: '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'},
    PositiveInt64: {type: 'string', pattern: `^${positive}$`},
    NonnegativeInt64: {type: 'string', pattern: `^(?:0|${positive})$`},
    NonzeroInt64: {type: 'string', pattern: `^(?:${positive}|-${negative})$`},
    ContentHash: {type: 'string', pattern: '^sha256:[0-9a-f]{64}$'},
    Signature: {type: 'string', pattern: '^[A-Za-z0-9+/]{85}[AQgw]==$'},
    EvidenceV1Time: {
      type: 'string', format: 'date-time',
      pattern: '^[0-9]{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.(?:[0-9]{6}(?!000)[0-9]{3}|[0-9]{3}(?!000)[0-9]{3}|(?!000)[0-9]{3}))?Z$',
      $comment: 'Explicit evidence-v1 adapter subset: years 0000..9999, Kotlin Instant 0/3/6/9 fraction groups. Generic UtcInstant supports a wider year range. Validate the date and this bound before local effects; never normalize signed bytes.'
    },
    Scope: {...object({organizationId: ref('UUID'), companyId: ref('UUID'), branchId: ref('UUID')}), $comment: 'DOMAIN identities only. Protected per-entity one-to-one mapping resolves unchanged native strings in one admission snapshot.'},
    Money: object({currency: {const: 'EGP'}, minor: ref('NonzeroInt64')}),
    Quantity: object({unitId: ref('UUID'), scaled: ref('NonzeroInt64'), scale: {type: 'integer', minimum: 0, maximum: 6, $comment: 'Actual decoder accepts only one unquoted digit 0..6; JSON Schema alone cannot distinguish 6 from lexical 6.0/6e0.'}}),
    Effect: effect,
    Result: object({documentId: ref('UUID'), revision: ref('NonnegativeInt64')}),
    Payload: object({
      schemaVersion: {const: '1'}, domainId: ref('UUID'), scope: ref('Scope'), operationId: ref('UUID'),
      authorityEpoch: ref('PositiveInt64'), journalSequence: ref('PositiveInt64'), originMasterDeviceId: ref('UUID'),
      signingKeyId: ref('UUID'), authorityProofId: ref('UUID'), originalActorId: ref('UUID'),
      catalogRevision: ref('PositiveInt64'), policyRevision: ref('PositiveInt64'), masterCommittedAt: ref('EvidenceV1Time'),
      effects: {type: 'array', minItems: 1, maxItems: 256, items: ref('Effect')}, result: ref('Result')
    }),
    Envelope: object({payload: ref('Payload'), contentHash: ref('ContentHash'), signature: ref('Signature')}),
    Batch: object({schemaVersion: {const: '1'}, envelopes: {type: 'array', minItems: 1, maxItems: 64, items: ref('Envelope')}}),
    Receipt: object({operationId: ref('UUID'), contentHash: ref('ContentHash'), authorityEpoch: ref('PositiveInt64'), journalSequence: ref('PositiveInt64'), disposition: {enum: ['accepted', 'quarantined', 'conflict']}}),
    SequenceRange: object({first: ref('PositiveInt64'), last: ref('PositiveInt64')}),
    Checkpoint: object({authorityEpoch: ref('PositiveInt64'), highestContiguousAcceptedSequence: ref('NonnegativeInt64'), highestAcceptedSequence: ref('NonnegativeInt64'), missingRanges: {type: 'array', items: ref('SequenceRange')}}),
    Acknowledgement: object({schemaVersion: {const: '1'}, scope: ref('Scope'), coverage: {const: 'central-accepted-evidence-only'}, items: {type: 'array', minItems: 1, maxItems: 64, items: ref('Receipt')}, checkpoints: {type: 'array', minItems: 1, maxItems: 64, items: ref('Checkpoint')}})
  }
};
// ECMAScript `$` alone also matches before a final newline. Unlike Go/Kotlin
// canonical parsers, that would admit a line-suffixed scalar. Require the true
// end of input for every string pattern, including Unicode line separators.
for (const definition of Object.values(schema.$defs)) {
  if (definition.pattern) definition.pattern += '(?![\\s\\S])';
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const {values} = parseArgs({options: {check: {type: 'boolean'}}, strict: true});
  const golden = JSON.parse(fs.readFileSync(path.join(root, 'backend/contracts/golden/evidence-v1.json'), 'utf8'));
  const p = golden.envelope.payload;
  const ack = {schemaVersion: '1', scope: p.scope, coverage: 'central-accepted-evidence-only', items: [{operationId: p.operationId, contentHash: golden.envelope.contentHash, authorityEpoch: p.authorityEpoch, journalSequence: p.journalSequence, disposition: 'accepted'}], checkpoints: [{authorityEpoch: p.authorityEpoch, highestContiguousAcceptedSequence: '1', highestAcceptedSequence: '1', missingRanges: []}]};
  const outputs = {'evidence-v1.schema.json': schema, 'golden/ack-v1.json': ack};
  for (const [name, value] of Object.entries(outputs)) {
    const destination = path.join(root, 'backend/contracts', name);
    const bytes = JSON.stringify(value, null, 2) + '\n';
    if (values.check) assert.equal(fs.readFileSync(destination, 'utf8'), bytes, `candidate schema/example drift: ${name}`);
    else fs.writeFileSync(destination, bytes);
  }
  console.log(JSON.stringify({status: 'LOCAL_CANDIDATE_NOT_RUNTIME_ADMISSION', files: Object.keys(outputs), mode: values.check ? 'check' : 'write'}));
}
