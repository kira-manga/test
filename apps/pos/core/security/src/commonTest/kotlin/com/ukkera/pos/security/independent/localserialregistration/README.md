# Local serial registration — content and custody assertion slices

Frozen specification: **local-serial-registration-v1**,
[cases](../../../../../../../../../../../../../qa/pos/designs/local-serial-registration/cases.md),
[fixtures](../../../../../../../../../../../../../qa/pos/designs/local-serial-registration/fixtures.json),
[contract and limits](../../../../../../../../../../../../../qa/pos/designs/local-serial-registration/README.md),
[freeze](../../../../../../../../../../../../../qa/pos/designs/local-serial-registration/FREEZE.json).
The packet contains **21 cases / 164 finite variants**, not 164 executed tests.

This harness has **35 common-test methods**: 11 content/input, 13 reservation and
11 custody-representation methods. **All 35 methods passed on JVM**, with zero
failures, errors or skips. [Actual checks and limits](../../../../../../../../../../../../../qa/pos/traceability/local-serial-native-prerequisite-20261101/README.md).
Every complete LSR case, variant and journey remains **NOT_RUN/UNBOUND**. A passing
method establishes only its listed supplemental assertion slice; it would not complete a case, establish native membership or close an
original scenario.

## Boundary and inputs

The tests call actual production codecs, identity/reservation comparisons, native
publication transition validation and serial custody marker functions. They do
not provide an alternative implementation or calculate a new acceptance oracle.
Semantic refusals do not select an error subtype or invalidity precedence absent
from the freeze.

`LocalSerialRegistrationInertInputs.kt` deliberately constructs **inert internal
records**. Company, branch, registration, serial, code, SKU, requested publication
and interval values retain the frozen blueprints. Auxiliary origin/action/key
coordinates and digests are syntax scaffolding, not actual native identities or
proofs. Opaque SKU content is not a native SKU publication. Zero-filled public-key,
signature and endorsement fields merely satisfy record length shapes; they are
not authentic keys/signatures, credentials, trusted membership or successful
native originals. Nothing is installed, signed, activated or persisted.

The implementation's initial revision syntax is used solely to construct inert
chains through the requested predecessor revision 3. The frozen oracle is the
declared **3 + 1 = 4**, freshness and permanent-tuple constraints; this does not
select or qualify the initial native publication contract (U-02/U-07).

No `NativeOriginalLocalSerialAuthority`, native allocation, native snapshot/trust,
publication output, accepting principal or successful production-facade reply is
constructed. Inert COMPLETED-shaped records are never treated as satisfying a
genuine completed-original prerequisite. The phase loops are supplemental checks,
not additional frozen variants or actual uncertain-write schedules.

## Content/input mapping — 11 methods

The methods in all three tables passed; their mapped **whole cases/variants remain
NOT_RUN/UNBOUND**.

| Method | Frozen mapping | Bounded observation |
| --- | --- | --- |
| `inertPublicationCodecPreservesTheExactRequestedIdentityAndCode` | LSR-06.g; F-LSR-IDENTITY/HISTORY | Actual content codec retains `000AbC-07`, requested IDs, namespace coordinates and predecessor data. No draft file, SKU-code/barcode presentation, Arabic/English UI or restart is exercised. |
| `boundedAndOpenIntervalInputsRoundTripWithoutBecomingAnAuthoritativeClock` | LSR-08.a-f; F-LSR-TIME | Only `2031-04-05T12:00:00Z`, `2031-04-05T13:00:00Z` and absent-end content preservation. None of the `interval_eligible` or actual-time assertions is evaluated. |
| `successorContentRequiresRevisionFourAndAFreshPublicationForPredecessorThree` | LSR-07.c/f and D1-C | Supplemental syntax retains registrationId/reference and requested revision 4; rejects repeat/skip 3/5 and reused predecessor publicationId. No native completion or Master CAS. |
| `changedStableRegistrationCannotUseTheOldPredecessorCoordinates` | LSR-06.a; LSR-07 invariant | Changed registrationId fails the actual successor content constructor; original request/reference stays unchanged. |
| `caseZeroSerialSkuAndNamespaceChangesAreNotTheSamePermanentIdentity` | LSR-06.b-e | Actual identity comparison distinguishes serialId, code case/zeros, SKU and changed namespace content coordinates. No authentic membership claim. |
| `oldContentCoordinatesDoNotMatchEditedPermanentPredecessorOrIntervalInputs` | LSR-12.a-f | Actual content references differ for code/serial/SKU/namespace, publicationId, predecessor digest, interval and pinned-SKU-publication edits. PublicationId only, not the revision subclause, is mapped for LSR-12.c. Native rejection under the old original remains unbound. |
| `numericCodeDoesNotDecodeAsAnOriginalString` | LSR-21.a | Exact numeric JSON value `12345` is refused without string coercion; original `000AbC-07` remains unchanged. |
| `frozenFractionalRevisionAndMalformedSerialIdFailWithoutInvalidityPrecedence` | LSR-21.b | Exact combined `3.5` / `NOT_A_VALID_DOMAIN_ID` input is refused; no claim about which guard fails first. |
| `missingPermanentAndNamespaceFieldsAreNotAcceptingDefaults` | LSR-21.d; LSR-12.g | Null registrationId/serialId/serialAuthority content coordinates fail decoding. Native namespace/proof absence is not tested by a fabricated accepting alternative. |
| `emptyRequiredCodeIsRefusedWithoutClaimingQuantityOrCardinalityEnforcement` | LSR-04.g | Only the frozen empty-code field is refused. Quantity 1, one-per-unit cardinality, catalog activation and receiving remain unbound. |
| `truncatedRegistrationAndAuthorityContentDoNotDecodeAsOriginalDefaults` | LSR-19.a/c; malformed-frame portion of LSR-21.e | Truncated actual canonical content fails decoding; earlier inert content still encodes identically. No native corruption, restore, size/depth maximum or usable-catalog baseline. |

## Reservation/transition mapping — 13 methods

| Method | Frozen mapping | Bounded observation |
| --- | --- | --- |
| `aSecondAuthorityCannotReplaceAnyRetainedInertAuthorityPhase` | LSR-02.b/c/e | Actual list validator refuses a second inert issuer/origin record for each retained phase; preserves first content. No actual designation, root endorsement or fault schedule. |
| `absentIncompleteOrLaterAuthorityCannotSupplyRegistrationConsumption` | LSR-01.a/d | Actual list validator refuses missing, incomplete or later-positioned authority data for an inert registration. No native namespace or protected consumer. |
| `duplicateExactCodeIsRefusedWithinAndAcrossSkusForEveryRetainedPhase` | LSR-05.a/b | Original `000AbC-07` versus fresh serialId/registration, same/other SKU: pure exact-code reservation comparison refuses collisions in each phase. Genuine completed baseline remains unbound. |
| `duplicateSerialIdIsRefusedEvenWhenCodeOrSkuDiffers` | LSR-05.c/d | Original serialId with `000AbC-08` and same/other SKU: pure comparison refuses reuse in each phase. |
| `originalSerialAndCodeCannotBeAssignedToAnotherSku` | LSR-05.e | Pure comparison refuses original serialId/code assigned to another SKU; original requested SKU stays unchanged. |
| `caseAndLeadingZeroVariantsStayDistinctInTheRepresentationComparison` | LSR-05.f/g; D1-B | `000abc-07` and `0AbC-07` remain distinct new-code inputs in the actual pure comparison, without rewriting `000AbC-07`. **Conditional positive native qualification is not exercised.** |
| `successorReservationRefusesEveryChangedPermanentCoordinate` | LSR-06.a-e; LSR-07.g | Content/list checks refuse changed registrationId, serialId, case, leading zeros, SKU or authority coordinates against the inert revision-3 history. |
| `ancestorOrEqualFieldPredecessorDigestCannotExtendTheCurrentInertHistory` | LSR-07.d/e; LSR-12.d | Pure list check refuses revision-2 ancestor / attempted revision-3 successor and equal-coordinate-but-wrong predecessor digest. No genuine predecessor/membership or current Master state. |
| `incompletePredecessorCannotAuthorizeAnotherSuccessor` | LSR-07.e | PREPARED/SIGNING_INTENT predecessor shapes cannot extend this inert chain. |
| `twoInertSuccessorsCannotBothExtendOneImmediatePredecessor` | Exclusion portion of LSR-15.a | Pure serialized list cannot contain two competing candidates from revision 3. No real concurrency/barrier/CAS, healthy exactly-one positive winner or native revision-4 result. |
| `selectedSkuPublicationMustPrecedeRegistrationAndMatchExactCoordinates` | LSR-10.i | Pure list refuses missing, incomplete, different, later-positioned or wrong-digest SKU coordinates. Real active/serialized/whole-unit SKU qualification remains unbound. |
| `reservationCoordinatesIncludeTheSerialIdInEveryRetainedPhase` | LSR-05.c/d; D1-B | Supplemental identity-coordinate function includes serialId and registrationId across phases; this is not native allocation/reservation evidence. |
| `completedInertContentCannotBeEditedInPlaceUnderItsOldActionAndPublication` | LSR-12.a | Actual generic native transition check refuses changed code content under the old action/publication. Previously encoded inert state is unchanged; no genuine completed original or durable commit is asserted. |

## Custody representation mapping — 11 methods

| Method | Frozen mapping | Bounded observation |
| --- | --- | --- |
| `firstPreparedIntentRetainsTheOneOriginalAuthorityWithoutASettledGrant` | LSR-02.c; LSR-01.d | Pure first-intent shape retains one original authority/action and refuses settled-source use. No native write/preparation outcome or grant. |
| `unresolvedIntentCannotBeUsedAsSettledCustodyEvenWithTheNextCompanionPresent` | LSR-01.d; LSR-19.h | Pure source guard refuses both old and next companion while intent is unresolved. |
| `exactRetainedNextShapeClearsOnlyItsOwnIntentAndPreservesTheOriginalCoordinates` | Same-original retention portion of LSR-02.d/LSR-18 | Actual pure marker functions represent only the exact retained next cut, preserve authority/action/key, and clear its intent. **Not authenticated settlement, native recovery or signing.** |
| `anOlderCompanionCannotResolveTheRetainedExactNextIntent` | LSR-17.e; LSR-18.b; LSR-19.c/h | Pure recovery comparison refuses previous-cut fallback and retains the same pending data. |
| `changedOriginalActionKeyOrFoundingContentCannotStandInForTheRetainedNext` | LSR-02.g; LSR-12.b/g; LSR-19.b/d | Pure target comparison refuses changed action, key or founding content; no claim about invalidity precedence or authentic proof. |
| `settledMarkerRefusesAbsentOrOlderSerialCompanionInsteadOfAnEmptyHistoryDefault` | LSR-17.e; LSR-19.c/h | Pure SETTLED-source guard refuses absent/older serial companion; it does not inspect actual native storage or authenticate a current cut. |
| `permanentMarkerCannotBeRemovedFromIntentOrSettledRepresentations` | LSR-02.c/e; LSR-13.e; LSR-19.h | Actual marker transition refuses removal in both representations. No cancellation UI or actual uncertain reservation. |
| `matchingIssuerTextDoesNotPermitChangedFoundingOrHeadBinding` | LSR-02.e/g | Actual marker transition refuses changed founding/head while issuer ID text matches. |
| `pendingMarkerCannotBeClearedUsingItsPreviousSettledCut` | LSR-18.b; LSR-19.h | Actual transition refuses an invented resolved fallback to the previous cut. |
| `resolvedMarkerCannotBeRewoundToThePreviousCutAndOriginalIntent` | LSR-19.h | Actual closed marker transition refuses old-cut/intent rewind. **Not an independent rollback fence for coherently rolled-back authenticated native files.** |
| `emptyOrTruncatedCustodyDataDoesNotInventAResolvedMarker` | LSR-19.a/c/h; malformed-frame portion of LSR-21.e | Empty marker fields/input and truncated actual marker wire fail; existing inert pending encoding is unchanged. No guessed native limit, recovery package or previous usable database. |

## Explicitly unbound work

- Genuine founding, root and native-head authentication; sole completed origin;
  registration/SKU originals; authentic exact namespace and predecessor membership.
- Master activation, current employee/session/device/writer/activated policy,
  serialized CAS/revocation and actual-time qualification. The **half-open** rule
  is resolved in D1, but its inclusive-start/exclusive-end/unknown-source execution
  (all LSR-08 variants) is **UNBOUND**. No clock evaluator is invented here.
- Byte-identical legacy **company-state** encoding when `serialCustody == null`.
  The frozen packet provides no permitted legacy registry/enrollment/head-account
  fixture. This suite does not invent account verifiers or signed enrollment
  originals to assert that compatibility. Generic nonserial formats and actual
  version/pending recovery remain unbound.
- Real native mandatory-marker/companion files, authenticated original reads,
  uncertainty, exact-next recovery, power/process faults, durable result/effects/
  audit/outbox atomicity and independent rollback protection.
- Every positive native clause, including legal successor and fresh case/zero
  identities. An inert syntax comparison or blanket refusal is not a positive pass.
- Business ZERO vectors, held custody 2, sellable 0, drawer cash 125000, original
  receipt/sale/return entitlement, genuine prior usable catalog and explicit release.
  No supplied vector or object equality substitutes for independently read ledgers.
- Operation deduplication/NOT_RECORDED same-original resubmission, client private
  durability/navigation, logout/account isolation, real RPC and application paths.
- Typed quantity/cardinality, overflow/maxima, depth/count limits, exact public
  routes/statuses and native initial formats not assigned by the freeze.
- All five-platform/ordered-pair/protected-one-device, installed/peripheral and
  owner campaigns; imported/central/other-branch positive lanes; central work.

## Executed selectors

From the POS build root:

```sh
./gradlew :core:security:jvmTest --tests 'com.ukkera.pos.security.independent.localserialregistration.*'
```

Individual suites are `LocalSerialRegistrationContentSlicesTest`,
`LocalSerialRegistrationReservationSlicesTest` and
`LocalSerialCustodyRepresentationSlicesTest` in the package above. A future run
must retain exact frozen expectations, actual revision/results and the distinction
between these supplemental methods and the unbound whole cases.
