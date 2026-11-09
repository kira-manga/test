# Approval retirement immutable-data checks

Eight JVM methods bind bounded representation slices of the independently frozen
[ASR v1 cases](../../../../../../../../../../../../../qa/pos/designs/approval-safe-replacement/cases.md):
ASR-01/04/09/10/14. They read that packet and its unchanged F1 dependency directly,
without duplicating fixture archives.

The 12 frozen action/route targets supply original operation/requester/approver,
policy and semantic fixture-digest comparisons. Native IDs, clocks, incarnations,
command/reason codes and target revisions are explicitly inert ABI carriers, not
production wire goldens. Local records are never inserted, signed or authenticated;
no restricted display receipt, native capture, Master service or accepting history
fixture is constructed.

Coverage: immutable issued bytes, fresh cancellation-session attribution, stable
original retirement locator, wrong actor/scope and consumed-original refusal,
altered event joins, exact audit-only evidence round-trip and incomplete/unknown/
noncanonical evidence rejection. The codec is not a native authority verifier;
whole ASR cases, permanent fencing, current REQUEST enforcement, operation census,
consume-versus-retire atomicity, actual SQLite/history/restart and RPC remain
**NOT_RUN / UNBOUND**. Test results are recorded separately.

Selector: `com.ukkera.pos.database.ApprovalRetirementRepresentationTest` on
`:core:database:jvmTest`.

## Cross-session replacement representation

Six supplemental JVM methods in `ApprovalCrossSessionRepresentationTest` bind
bounded ASR-10/12 immutable-attribution expectations through the protected approval
codec. The same frozen targets supply original semantic/requester coordinates;
fresh session, revision, clock and origin-digest metadata are inert syntax carriers.

They check separate current authority and original business witnesses, exact origin
selectors, consumption-copy preservation, readable v2 bytes and canonical v3 inner
bytes, and refusal of incomplete or unknown v3 extensions. The replacement's inner
v2 base carries its own new issuance metadata, not byte equality with an older issue.
No native chain, authenticated retirement, current permission, Master service,
business transaction, history/restart or RPC behavior is qualified by these checks.
Whole ASR cases remain **NOT_RUN / UNBOUND**; execution results are recorded separately.

Selector: `com.ukkera.pos.database.ApprovalCrossSessionRepresentationTest` on
`:core:database:jvmTest`.
