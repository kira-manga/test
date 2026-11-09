# POS application contribution rules

Read the repository-root `AGENTS.md`, `docs/pos/planning/WORKFLOW.md` and the
current owning GitHub Issue before changing this independent POS build.

## Shared implementation

Put reusable business/workflow, authentication, permissions, RPC and recovery
behavior in `commonMain`. Native code owns only real platform integrations.
Composition interfaces do not grant authority. Preserve the authoritative master,
current protected permissions, confirmed native close, original operation identity,
unknown outcomes and compatible persisted/wire formats.

There is one local branch master and no cloud checkout/lease dependency. Durable
result, effects, audit and outbox must commit before success. External payments are
cashier attestations. No negative stock, customer credit/wallet/loyalty or
cross-branch returns are introduced. Employee, central-human and device/service
trust remain separate. Recovery epochs alone do not fence an isolated writer.

All agreed business activities and platform roles remain required. No safety
bypass, UI-only substitute or test-only checkout service counts as implementation.

## Verification and delivery

- Development closure requires the entire defined production/platform scope,
  review, passing required unit and non-device integration tests, and no unfinished
  implementation or unresolved in-scope defects. Compilation alone is insufficient.
- Deferred emulator/simulator/physical-device and peripheral runs belong in #63;
  owner acceptance remains in #61. Pending campaign execution alone does not block
  implementation closure. Missing code, broken production integration and failed
  required non-device tests remain development work, not deferred device testing.
- Runtime-tool failures must not suspend independent implementation. Preserve the
  actual failure and defer the affected test unless a bounded diagnostic plan and
  changed underlying condition justify another attempt.
- Implementers must not author their components' test cases or expected outcomes.
  Follow `qa/pos/AGENTS.md`; freeze independent expectations before execution and
  preserve them unchanged during harness wiring. Test all applicable original
  scenarios and local portions of mixed requirements.
- Use isolated synthetic data; report exact revision, relevant outcomes and limits.
  Preserve originals, freezes, protected work and prior failures. Do not silently
  apply archived patches or alter old evidence. Normal builds may be rerun.
- Keep Issues and parent Epics current after meaningful work. Published documents
  contain project facts, not private execution settings or coordination records.

Central implementation and verification remain held pending owner local testing
and separate authorization. Only ordinary `[skip ci]` commits/pushes on
`implement/ukkera-pos-local`; no Actions, pull requests, releases or deployment.

## Normal build workflow

Use the POS Gradle wrapper directly with JDK 21 and a configured Android SDK.
Choose relevant module tests and compilation; batch expensive integration checks.
Do not require one-use SDK copies, operation IDs, source hashing or custom receipts.
Historical tooling and fork patches are retired, not new build prerequisites.
P2pKit fixes belong upstream; use an official version that meets F13, or keep
dependent production RPC integration blocked while other local work continues.
