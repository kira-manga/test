# Dependencies and execution gates

**Local development authorized; central implementation and verification held.** [Ukkera POS — scope, progress, blockers and evidence (#2)](https://github.com/ukkera/kmp-apps/issues/2) is the main view.

## Current bounded integration gate

- [Finish and reconcile native admission with original SDK serving (#49)](https://github.com/ukkera/kmp-apps/issues/49) is natively **blocked by** [Consume official P2pKit and integrate protected local RPC (#48)](https://github.com/ukkera/kmp-apps/issues/48).
- [Complete shared session attachment, refresh and confirmed-close cleanup (#50)](https://github.com/ukkera/kmp-apps/issues/50) is natively **blocked by** [Finish and reconcile native admission with original SDK serving (#49)](https://github.com/ukkera/kmp-apps/issues/49).
- [Complete actual Android local runtime composition and restart path (#55)](https://github.com/ukkera/kmp-apps/issues/55) is natively **blocked by** [Finish and reconcile native admission with original SDK serving (#49)](https://github.com/ukkera/kmp-apps/issues/49).
- [Complete actual Android local runtime composition and restart path (#55)](https://github.com/ukkera/kmp-apps/issues/55) is natively **blocked by** [Complete shared session attachment, refresh and confirmed-close cleanup (#50)](https://github.com/ukkera/kmp-apps/issues/50).
- [Wire frozen acceptance to genuine production persistence and admitted RPC (#59)](https://github.com/ukkera/kmp-apps/issues/59) is natively **blocked by** [Finish and reconcile native admission with original SDK serving (#49)](https://github.com/ukkera/kmp-apps/issues/49).
- [Wire frozen acceptance to genuine production persistence and admitted RPC (#59)](https://github.com/ukkera/kmp-apps/issues/59) is natively **blocked by** [Close native restart/history/resource joins across installed business commands (#51)](https://github.com/ukkera/kmp-apps/issues/51).
- [Owner manual acceptance and real-world validation (#61)](https://github.com/ukkera/kmp-apps/issues/61) is natively **blocked by** [Independent automated business and production integration verification (#42)](https://github.com/ukkera/kmp-apps/issues/42).
- [Owner manual acceptance and real-world validation (#61)](https://github.com/ukkera/kmp-apps/issues/61) is natively **blocked by** [Runnable local prototype, synthetic setup and delivery instructions (#43)](https://github.com/ukkera/kmp-apps/issues/43).
- [[Epic] Central services — held until owner local approval (#11)](https://github.com/ukkera/kmp-apps/issues/11) is natively **blocked by** [Owner manual acceptance and real-world validation (#61)](https://github.com/ukkera/kmp-apps/issues/61).

These are evidence-backed closure/qualification gates, not an instruction to leave unrelated work idle during local development. SDK/admission/session APIs may be co-designed, but accepted integration requires actual artifacts, authority and confirmed-close behavior. Runtime/history/resource work and missing business integrations have their own Tasks. No technical workaround may bypass a blocking security/integrity invariant.

Manual acceptance #61 is separate from development closure. #60 supplies actual
external facts only to the scopes that depend on them; it is not a universal
prototype blocker. Central #11 also requires **separate explicit authorization**,
not merely #61 closure. The original package criteria below remain preserved;
[WORKFLOW.md](WORKFLOW.md) governs the current development/manual milestone split.

## Original full package completion table — verbatim

[Original task catalog](https://github.com/ukkera/kmp-apps/blob/b79ec4938da699e4c5de7aef13da56eef487c85f/docs/pos/original-handoff/POS_AGENT_TASKS.md) remains normative, including detailed acceptance exits and reviewer families. The table below is copied verbatim; it is **not current package status**.

| ID | Work package | Owner family | Independent reviewer family | Dependencies |
| --- | --- | --- | --- | --- |
| T00 | Workspace/source preflight | FOUNDATION | QA-RELEASE | none |
| T01 | Dependency and platform spike | FOUNDATION | RECOVERY | T00 |
| T02 | Domain/contract/schema freeze | FOUNDATION | PAYMENTS | T00, T01 |
| T03 | CI, fixtures and evidence harness | QA-RELEASE | FOUNDATION | T00, T01 |
| T04 | Money, quantity, time and identifiers | FOUNDATION | CASH | T02, T03 |
| T05 | Room persistence and atomic command store | FOUNDATION | RECOVERY | T01, T02, T03, T04 |
| T06 | New YAML and protected central runtime | CENTRAL | AUTH | T02, T03 |
| T07 | Central management identity/permissions | AUTH | CENTRAL | T04, T06 |
| T08 | Device admission and local human auth | AUTH | RECOVERY | T04, T05 |
| T09 | Master hosting and RPC adapters | FOUNDATION | AUTH | T01, T05, T08 |
| T10 | CMP shell, Nav3, Koin and design system | UI-NAV | AUTH | T01, T02, T03, T04 |
| T11 | Catalog and policy definitions | CATALOG | INVENTORY | T05, T06, T08 |
| T12 | Prices, promotions, tax and charges | CATALOG | RETURNS | T04, T11 |
| T13 | Inventory/reservations/counts/transfers | INVENTORY | KITCHEN | T04, T05, T08, T11 |
| T14 | Cash custody and shifts | CASH | PAYMENTS | T04, T05, T08, T10 |
| T15 | Orders, ownership and bill structure | SALES | CASH | T08, T10, T11, T12, T13 |
| T16 | Tender recording and sale commit | PAYMENTS | RETURNS | T09, T12, T13, T14, T15 |
| T17 | Cashier UI and first real offline slice | UI-CASHIER | QA-RELEASE | T10, T16 |
| T18 | Recipes, kitchen and production | KITCHEN | INVENTORY | T09, T10, T11, T13, T15 |
| T19 | Fulfillment/delivery and exceptions | SALES | CASH | T10, T14, T16, T18 |
| T20 | Returns/exchanges/deposit entitlement | RETURNS | PAYMENTS | T10, T12, T13, T14, T16, T18 |
| T21 | Purchase orders, receiving and inspection | PROCUREMENT | INVENTORY | T08, T11, T13 |
| T22 | Supplier settlement and cost corrections | PROCUREMENT | CASH | T14, T21 |
| T23 | Branch upload and central ingestion | SYNC | RECOVERY | T05, T06, T07, T09 |
| T24 | Administrative distribution/activation | SYNC | CATALOG | T07, T11, T12, T15, T16, T18, T23 |
| T25 | Backup, restore and optional standby | RECOVERY | SYNC | T05, T08, T09, T19, T20, T22, T23, T24 |
| T26 | Master transfer/replacement and fencing | RECOVERY | AUTH | T25 |
| T27 | Reports, alerts and scoped export | CENTRAL | CASH | T14, T16, T18, T19, T20, T22, T23, T24 |
| T28A | Native company/branch/people administration | UI-ADMIN | AUTH | T07, T10 |
| T28B | Native catalog/pricing/policy administration | UI-ADMIN | CATALOG | T11, T12, T24, T28A |
| T28C | Native inventory/purchasing administration | UI-ADMIN | PROCUREMENT | T09, T13, T21, T22, T28A |
| T28D | Native reports/alerts/export administration | UI-ADMIN | CASH | T27, T28A |
| T28E | Native device/recovery administration | UI-ADMIN | RECOVERY | T26, T28A |
| T29 | Setup, import and isolated training | UI-ADMIN | INVENTORY | T10, T11, T13, T14, T24, T26, T28A, T30 |
| T30 | Peripheral adapters and routing | PERIPHERALS | KITCHEN | T09, T10, T16, T18 |
| T31 | Fiscal/accounting documents and integration | FISCAL | PAYMENTS | T06, T07, T10, T12, T16, T19, T20, T22, T23 |
| T32 | Security, observability and upgrade hardening | FOUNDATION | QA-RELEASE | T05, T09, T10, T23, T24, T25, T26 |
| T33 | System/chaos/load/device qualification | QA-RELEASE | RECOVERY | T17, T19, T20, T22, T24, T26, T27, T28A, T28B, T28C, T28D, T28E, T29, T30, T31, T32, T34 |
| T34 | Reproducible native candidate packaging | QA-RELEASE | FOUNDATION | T01, T03, T10, T32 |
| T35 | Pilot, final evidence and operating handoff | QA-RELEASE | CASH | T33, T34 |

[Issue-owner lookup](TASK-PACKAGE-MAP.csv) preserves feature anchors. T06/T07 central ownership is explicit; no local child claims to complete those packages. Other broad packages may span local and held central work. A package appearing in an LF task anchor is a relationship, not proof that a particular central implementation depends on every such package.

## Accepted early children and historical milestones — preserved

| Early milestone | Timing and concrete proof; not completion of its parent package |
| --- | --- |
| E0 — source/adoption/topology plan | T00/T01: exact revisions, safe reuse exclusions, generator discovery, network/device/credential gaps and supported candidate profiles. |
| E1 — native compatibility/package probe | T01 with early T34/peripheral inputs: real storage/RPC/crypto/IPC and minimal packaged launcher on available targets; physical gaps explicit. No full T34 or platform-pass claim yet. |
| E2 — recovery/rounding/value contract review | Within T02 before freeze: F01 outcome/write-admission model, F02 serialized custody/fencing, F07 calculation profile and F08 valuation oracle fixtures. Design/protocol proof here does not depend on completing T25/T26. |
| E3 — primitive recovery integration | Early T25/T26 children after integrated T05/T08/T09: one real atomic operation, lost reply, coherent restore, stale-tail quarantine, target-bound activation and competing-target rejection. Gate M1 integration; extend coverage with each later domain. Full T25/T26 still wait for their complete dependencies. |
| E4 — resumable setup/security-root skeleton | Early T29 child alongside T10/T08: scoped wizard state, training/live isolation, safe interrupted steps and master-versus-cashier lifecycle. Full domain setup waits for T29's listed dependencies. |
| E5 — ongoing package and upgrade probes | T34/T32 children as foundations/features arrive: actual packaged native linking and supported schema fixtures, not first attempted only before pilot. Final security/package exits remain unchanged. |

| Milestone | Completion evidence |
| --- | --- |
| M0 — foundations | T00–T05 applicable foundations/CI, exact contracts, compatible graph, isolated generator discovery/probe and E0–E2 evidence; unavailable backend/device facts clearly separate. |
| M1 — real offline retail slice | T17 path with local login/count/stock/quote/cash and external attestation/receipt, second client, user switch, lost-response recovery and E3. WAN/standby absent; real storage/transport. Not full activity release. |
| M2 — central/native administration | Actual protected local backend/new YAML, company CMP flows, typed ingestion and configuration activation. Tenant/direct-ID/bulk negative tests, no mocked success. |
| M3 — full business workflows | All four activities plus cash, returns, inventory, purchasing, exceptions, reports and full administration/setup; package tests and cross-domain conservation, no reduced release. |
| M4 — resilience/operations | Complete T25/T26/T30/T31/T32 integration: all-state recovery and fencing, optional standby, qualified peripherals/applicable fiscal connector, retention/observability and upgrades. |
| M5 — candidate and pilot | Exact T34 candidates tested by T33, then T35 pilots; all required traceability and actual platform/external evidence, no unresolved critical/high correctness/security defect or undisclosed qualification gap. |

Milestones coordinate integration; they are not an alternative serial DAG. Early contracts/fixtures can proceed on frozen interfaces, but a partially proven scenario remains incomplete. Physical/fiscal/source access gates block the affected claims, not every independent safe work package. No calendar duration or staffing throughput has been proven by this review.


**Latest owner execution-order overlay:** the original M2 central milestone is not permission to run central services before M3/M4 local work. Complete applicable local children and verification first; keep full mixed-package central exits held for [Owner manual acceptance and real-world validation (#61)](https://github.com/ukkera/kmp-apps/issues/61). Preserve scope and dependencies rather than quietly deleting central portions or blocking all local work. T03’s CI/central-service elements likewise remain authorization-dependent; this phase starts no Actions or runtimes. T34 creates the actual candidate before T33 tests that candidate; T35 follows acceptance and applicable external checks.

## Shared ownership boundaries

- Core atomic/native physical journal and unknown-outcome machinery are shared once. Retail, FOOD and supplier Features own distinct domain authority, claims and commercial effects; separate refund eligibility is not the same as paying an existing obligation.
- Opening includes stock/value/supplier obligations/cash; it depends on inventory/purchasing, cash custody and report reconciliation, not invented opening purchases.
- Sales/pricing/payment own immutable commercial/tender/allocation facts; reporting/receipts construct documents; peripheral adapters own actual paper/KDS output and uncertainty.
- Native administration includes real T28A–E actions, delivered by the relevant domains, not only a navigation shell.
- Reusable policy/workflows/security/RPC/recovery belong in commonMain; platform adapters own actual keys, OS services, lifecycles, native storage/SDK and hardware.
- All four activities, all five platform master/client families, 25 ordered pairs and five protected same-device profiles remain required.
