# PENDING return-history compatibility v1 — bounded wiring

These ten common test methods support the unchanged frozen
[`return-pending-profile-compatibility-v1`](../../../../../../../../../../../../../../qa/pos/designs/return-pending-profile-compatibility-v1/README.md)
specification (frozen 2026-11-03T00:40:01Z). **All ten bounded methods passed**
on the source committed alongside this record; zero failures/errors/skips.
All four whole RPHC-01–04 cases and their integration, platform and owner variants
remain **NOT RUN**.

The tests call the production `returnCaseHistoryPendingQualification` and
`returnCaseHistoryUnavailableQualification` predicates with synthetic retained
DATA. The latter receives an explicit literal `expectedSourceResources` set.
Predicate `true` selects a profile for a later exact join; it is not acceptance,
successful replay or proof that an original source preparation was unavailable.

The constructor visibility suppressions apply only to inert
`RetainedCommandEvidence` and `RetainedAggregateWrite` DTOs. A synthetic
`committed` label, hash, UUID or source-reference shape cannot prove native trust
or durable producer provenance. No historical command, scan owner, principal,
authorization, admission, signature or current grant is constructed. Kotlin
warns that cross-module internal-constructor suppression is not guaranteed by
future compiler versions; this harness is verified on JVM only.

| Frozen case | Method suffixes in `ReturnPendingProfileCompatibilityFrozenDataTest` | Bounded mapping |
| --- | --- | --- |
| RPHC-01 | `rphc01_legacyShape_retainsAbsentProfileAndCurrentSerializationOnly` | L-like no-reference/no-timing DTO, original input immutability and current canonical serialization only. No newer source installation or historical writer recovery. |
| RPHC-02 | `rphc02_s0RetainedReferences_withoutTiming_neverSelectUnavailableProfileDataOnly`; `rphc02_s1RetainedTiming_exactFrozenNumbersAndReferencesDataOnly` | Source-reference membership, S0 absence, exact frozen S1 numeric/time/zone literals and local serialization order/bytes. Current-pointer/time values are comparison literals. |
| RPHC-03-H | `rphc03h_exactBeforeResources_addedDroppedOrForeignNeverSelectUnavailableDataOnly`; `rphc03h_droppedEventReference_cannotSelectPendingOrUnavailableProfileDataOnly`; `rphc03h_droppedTiming_withRetainedSourcesNeverSelectsUnavailableProfileDataOnly`; `rphc03h_emptyReferencesAndUnavailableAssessment_residualResourcesNeverSelectLegacyDataOnly`; `rphc03h_changedResultStageActionOrRevision_cannotSelectEitherLiteralProfileDataOnly`; `rphc03h_missingOrChangedEventWriteCoordinates_cannotSelectEitherLiteralProfileDataOnly` | Exact literal resource-set equality and selected incompatible event/result/write coordinates. No source row removal, source substitution, signed-trust decision or core recovery refusal is executed. |
| RPHC-04 | `rphc04_changedIntentOrActor_distinctCanonicalDataAndSemanticDigestOnly` | Changed typed timing intent and actor produce distinct canonical data/digests. This does not exercise retry conflict, disclosure/recovery access or session revocation. |

Class selector:
`com.ukkera.pos.sales.store.independent.returnpendingprofilecompatibilityv1.ReturnPendingProfileCompatibilityFrozenDataTest`.
The input file embeds the frozen logical fixtures unchanged. Case/unit/append/
capture/session/assignment IDs, case/entitlement revisions, before digests and
unassigned producer coordinates remain unbound shape fillers. The fixture has a
single synthetic event write, not a complete durable command bundle. Generated
current request/result/event bytes are local serialization comparisons, not
authentic historical byte anchors or independently frozen wire goldens. The
assessment helper supplies a supported DTO shape; qualification/refusal wire
codes are not asserted.

Actual dispatch and `source.prepare` suppression remain subject to source review,
not dynamic verification here. `RuntimeHistoricalCommand` requires its actual
core-created active scan; no legitimate public fixture is bound in this harness.
Genuine BEFORE entitlement resource derivation, complete requirements/results/
effects/resources, untouched source producers, native trust, SQLite persistence,
restart/upgrade, admitted communication, protected Master authority and every
independent no-effect balance/counter remain **NOT RUN**. Anchor mutations beyond
the exercised predicates remain unbound; the tests do not claim core rejection.
RPHC-03-P/C/R/F, authentic default-false historical RPHC-01-F, full retry/current
access variants, #59 integration, #63 devices and #61 owner acceptance stay
unverified. Held central verification is **DEFERRED**.
