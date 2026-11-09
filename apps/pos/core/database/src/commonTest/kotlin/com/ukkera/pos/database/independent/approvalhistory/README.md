# Original approval-history representation slices

Links the unchanged [OH v1 cases](../../../../../../../../../../../../../qa/pos/designs/action-approval-original-history/cases.md),
[fixtures](../../../../../../../../../../../../../qa/pos/designs/action-approval-original-history/fixtures.json)
and [freeze](../../../../../../../../../../../../../qa/pos/designs/action-approval-original-history/FREEZE.json).
The freeze is not copied or replaced here. All eight whole OH cases remain
**NOT_RUN**; genuine approval/authentication/business/native/API bindings remain
**UNBOUND**. These 30 bounded methods passed on the commit containing the
[execution record](https://github.com/ukkera/kmp-apps/blob/implement/ukkera-pos-local/qa/pos/traceability/approval-original-history-20261101/README.md); complete-case status is unchanged.

## Selectors and scope

| Selector | Bounded methods | Representation coverage |
| --- | ---: | --- |
| `com.ukkera.pos.database.ApprovalHistoryUseBindingSlicesTest` | 15 | OH-01/02/03/06/07 exact original identity/requirement comparisons; Apply-consumed versus terminal Reject-unconsumed; consumption record/event/images; original expiry; A/A policy-9 retention |
| `com.ukkera.pos.database.ApprovalHistoryCanonicalFrameSlicesTest` | 15 | OH-06/07 exact retained command V1 string bytes; separate Direct profile; strict canonical/required/unsupported frames; legacy approval V1 read-only compatibility; closed approval image grammar and byte limits |

The production surface under test is the pure retained-data profile in
`RuntimeActionApprovalEvidence.kt`, with existing canonical command, approval and
authentication-event codecs. Expected consumption images are constructed from
their explicit table/mutation/revision/identity fields and SHA-256 of canonical
local bytes, not from the runtime image helper being checked.

The frozen successful business workflows are manual discounts, not unpriced
arrivals. The bounded Apply DTO comparison is not genuine issuance, consumption,
single-use/CAS enforcement, permission delegation or a committed discount. A
Reject DTO with no consumption fields does not prove a negative native mutation
census or the absence of an unavailable historical tail. Missing/unsupported
frames refuse decoding; this does not establish complete preservation of an
independently proved result or settle an unproved outcome. Those complete OH
obligations remain unbound here; absence is not an unused/no-commit verdict.

## Inert carriers and unchanged limits

- Frozen actor, scope, document, operation, policy, money and same/different
  semantic-digest values are retained as comparison inputs. Fixture digests use
  `fixture-semantic-v1`; they are not asserted as production producer, native,
  wire, signature or authenticated dependency hashes.
- `a*` approval/event/issuer/boot identities, incarnation numbers, dependency and
  native fingerprints, change counts, command identity and bounded reason code
  are explicitly inert representation carriers. They are not captured originals
  or bindings for the freeze's `AP-*` aliases and authenticated session aliases.
- The inline profile's same-device approver carrier does not bind the frozen
  Separate fixture's distinct `DEV-A`/`DEV-M` aliases. Training/live namespace,
  free-form reason, business action/error codecs and full history/result/effect/
  audit/journal/outbox joins remain UNBOUND. A/A retention does not establish a
  NamedSelf grant or its company-authorized delegation.
- No issuance, real authentication, throttle MATCH, native origin, database,
  service, RPC, retry, revocation race or recovery flow is exercised. No inert DTO
  is inserted into storage or supplied as authority to an accepting service.
  Positive issuance evidence is deliberately not fabricated to complete the
  issuance-profile grammar checks.
- Rejection assertions use the retained-format rejection type, not an invented
  business error or precedence. No historical executed golden is invented; no
  frozen expected result, unknown-fate rule or central hold is weakened.

Compilation, actual unit outcomes and complete production integration require
separate execution evidence. Method count and source wiring are not PASS results.
