# Native timestamp binding — supplement v1.0.1

**Frozen clarification; NOT RUN.** Apply this timestamp-binding supplement to
the native portions of FCC cases v1.0.0. Preserve the original README, cases,
fixtures and manifest unchanged. Report an execution using this clarification
as **base cases v1.0.0 + timestamp binding v1.0.1**, not as execution of the
original fixed-clock binding. This changes no business amount, quantity, state,
identity, source-custody, authority or recovery expectation.

No assigned requirement prescribes the fixture's literal November 5 instants
or specifies a controllable native CASH clock API. Those instants are synthetic
fixture choices. [QUALITY §3](../../requirements/original/POS_TEST_AND_QUALITY_PLAN.md)
does require “explicit test-environment abstractions for clock” and genuine
implementations when testing real transport/storage/authentication boundaries.
It does not prescribe those instants or establish a clock-control capability in
the unbound native CASH contracts. Controlled-clock qualification remains
required where applicable; it is not evidence of a native capability.

The binding below derives from the following actual requirements:

| Source | Required time/history behavior |
| --- | --- |
| [DOMAIN §2.1](../../requirements/original/POS_DOMAIN_AND_CONTRACT_BLUEPRINT.md) | Time is a UTC instant with branch IANA timezone and relevant local date; optional Client observation time is separate from Master commit time. “Master sequence orders events”; wall time must not determine revision/sequence ordering. |
| [OPS §§4, 9](../../requirements/original/POS_OPERATIONAL_SPECIFICATION.md) | Preserve actual timestamps and tax-document dates separately; preserve the payer/collector, cashier attestation and time against the locked payable. |
| [PHYSICAL-V1, Public local interaction and recovery semantics](../../contracts/LOCAL-PHYSICAL-OBSERVATION-BEHAVIOR-v1.md) | Durable recording precedes the physical instruction; the reported original outcome is preserved under its original identity; retry/reopen/relogin cannot rebind or repeat it. |
| [DOMAIN §§5.1–5.3, 7, 10](../../requirements/original/POS_DOMAIN_AND_CONTRACT_BLUEPRINT.md) | Original semantic content, durable result and historical representation survive recovery; commit precedes COMMITTED success; replay does not repeat effects. |

For genuine native execution, use this frozen **capture-once binding rule**:

1. Before execution, identify the corresponding genuine native original-time
   roles and retained representations in the actual compatible contract/source.
   No API or field name is inferred here. Bind I's original issued/recorded time,
   O's original physical-observation/attestation time, any separately retained
   Client observation/capture time, and A's original Master financial commit time
   to their actual first genuine saved values. Capture each value with its
   original identity at that first phase, before retry, refusal, account switch
   or close/reopen. Actual values are run bindings, not new expected outcomes.
2. Every later recovery comparison must equal those original saved values
   exactly, at their genuine retained precision/representation. Preserve their
   distinct roles even if clock resolution produces equal numerical instants.
   Do not replace O with A's time, replace either with lookup/reopen time, truncate
   precision, apply a tolerance, or write November 5 into native records. Missing
   required original-time evidence remains unverified; no timestamp is invented.
3. Preserve the original causal assertions: durable I before disclosure of a new
   instruction; the one actual physical event and retained original O; durable A
   before genuine financial success; actual native close before reopened-source
   recovery. Use authoritative sequences, durable boundaries and recorded actions
   to establish this order. Independently running Client/Master clocks or equal
   timestamp precision do not authorize rewriting time or replacing that causal
   proof with a numerical wall-clock sort. Permission revocation and operation
   identity retain their existing authoritative ordering and non-reuse rules.
4. Bind relevant original dates from the actual instants using the retained
   Africa/Cairo timezone and configured 04:00 operational cutoff; preserve
   business-date and tax-document-date roles. November 5 and its +02:00 offset
   remain the synthetic examples. Select the ordinary same-business-day rehearsal
   precondition; do not add a new day-boundary policy or flatten actual date
   context to the reopen date. Record actual owner/witness UTC close/reopen times
   separately from native business timestamps.

A genuine supported isolated clock boundary may still execute the original
fixed schedule as written. A native clock-control API is not required merely
to use this separately versioned actual-time binding. All other native contract,
source selection, permission, durable storage, admitted-RPC, partition and real
owner-close prerequisites remain unchanged. In particular transport Receipt,
current-source substitution or an inert descriptor cannot prove an original
timestamp or financial application.

The fixed **FCC-DATA-01** descriptor, its November 5 requirement/observation bytes,
every changed-time comparison and all DATA-only labels stay byte-for-byte
unchanged. Native actual-time binding never substitutes values into that fixture
or upgrades its authority claim. This supplement contains no execution result.
