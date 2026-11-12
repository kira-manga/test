# Development completion and owner acceptance

**Owner instructions: 29 October and 1 November 2026.** GitHub [#2](https://github.com/ukkera/kmp-apps/issues/2)
is the live dashboard. This policy supersedes the older combined Issue-closure rule,
not any original functional/security requirement or frozen expected outcome.

## Two different milestones

| Milestone | Required evidence | What it does not claim |
|---|---|---|
| **Development Complete** | Entire defined scope integrated into actual production and relevant platform code; source review; required passing unit and non-device production integration checks; no unfinished implementation or known in-scope defects; KMP and compatibility preserved. | Deferred emulator/simulator/device testing, owner acceptance, fiscal approval or production readiness. |
| **Owner Acceptance Complete** | Required real-world runs on the identified builds/platforms/peripherals; recorded actual results and owner feedback; required failures resolved and rerun; explicit owner validation. | Permission to start central work without a separate explicit owner authorization. |

Development Issues/Features/Epics can close while their linked emulator/simulator/
physical-device campaign and owner acceptance are pending. They cannot close
merely because source exists, compiles, or passes
mock-only/unit-only checks. A Task closes only its bounded scope; a Feature/Epic
needs all of its development obligations, not a count of closed children alone.

An unavailable emulator/simulator/device test is **BLOCKED**, not PASS. Record
the missing runtime, affected assertions and last actual evidence in the dedicated
campaign. Pending campaign execution alone does not prevent implementation closure.
Required non-device automation remains a closure requirement. Missing platform
implementation/toolchain compilation, broken production integration, known code
defects and failed required non-device tests remain development work; do not
relabel them deferred testing. A device run revealing a real defect creates or
reopens work in its implementation owner; preserving the failed run is mandatory.

Runtime-tool failure must not suspend independent development. Troubleshoot only
with a bounded diagnostic plan and a changed underlying condition; otherwise
preserve diagnostics, defer the run and finish actionable implementation work.

## Reuse the existing hierarchy

- #3–#10 retain their local implementation/automated-quality outcomes; #11 and
  #44–#47 remain held-central scope. No requirement is removed or copied into a
  competing implementation backlog.
- #42 owns independent automated business/production integration, with #58 evidence
  reconciliation and #59 genuine persistence/RPC fixtures. Original design freezes
  remain immutable; the implementer cannot author their component's test oracles.
- #43 owns the runnable prototype, signed/local artifacts, synthetic setup and
  operating instructions. Owner execution is not its closure criterion.
- #61 is the **manual-acceptance hub**, directly under #2 rather than inside the
  development Epic's completion count. #60 records external/owner facts beneath
  that hub, not as a universal blocker on #43 or unrelated development.
- [#63](https://github.com/ukkera/kmp-apps/issues/63) owns all deferred emulator,
  iOS simulator, installed/physical-device and peripheral runs under #61. It
  links original cases and implementation owners; pending runs alone do not
  block implementation closure. Known application failures remain with their
  development owners as well as in the campaign's evidence.
- Two GitHub milestones and `pos:development` / `pos:manual-acceptance` labels expose
  the two lanes. Closed development Issues are development outcomes only. The manual
  hub uses actual case/variant results, not its child-Issue count, as acceptance.

The original 105 features, 141 scenarios and 79 gates remain linked through
[the original requirement map](REQUIREMENT-ISSUE-MAP.csv). Its bootstrap and historic
whole-requirement scalar states are dated evidence, **not** development percentages.
Local portions of mixed requirements remain local; their central portions remain
held. All four activities, all five platforms, 25 ordered master/client pairs and
five protected same-device profiles remain required.

## Meaningful updates, not administrative churn

After an integrated increment or a materially changed blocker, update the owning
Task, Feature and Epic in the same work cycle; refresh #2 when its summary changes.
Use one short current block: implementation/revision, separate review, actual unit
and integration results, remaining development, manual link and next blocker.
Keep prior results under dated history. Do not leave contradictory old statements
under a heading claiming to be current. No update is needed for every tool call.

Keep committed documents and Issue/Epic descriptions/comments focused on project
requirements, implementation, defects, dependencies, meaningful tests and outcomes.
Private execution settings, coordination, machine configuration, local paths and
raw operational logs remain local and untracked. Genuine application runtime limits
and technical failures remain relevant. Summarize valuable older audit comments
without destructively erasing them.

Before closure link exact integrated commit(s), the review record, independently
designed cases and frozen version, relevant command/test environment, actual result
and redacted evidence. Detailed implementation/design/execution attribution stays in local provenance;
published records retain the frozen case version, review conclusions and outcomes. Make
explicit whether a result is a source check, compilation,
unit regression, production integration or installed/physical run. Unrun, skipped,
failed and externally blocked are not interchangeable. Do not fabricate percentages.

## Master-authoritative access

The master's current protected employee, session, device admission and activated
policy state govern every protected command/query/export/subscription/result read.
Identity-bearing tokens and an authenticated RPC connection do not freeze grants.
The same protected rules apply to same-device and remote clients.

- A stale/disconnected client or missing revocation notification cannot keep a
  master-side grant alive. Validate again at the protected operation boundary.
- Serialize authorization, policy/session mutation and financial/stock commit on
  the authoritative transaction boundary. If revocation wins first, the stale
  operation must not commit business effects. If the business operation durably
  commits first, later revocation does not undo it or permit a duplicate retry.
- Protected results and continuing streams also require current access at their
  release/delivery boundary. Reconnect/restart revalidates identity/session/device;
  it cannot restore stale authority from UI state or a cached snapshot.
- Client grant/navigation refresh, secret/private-state cleanup and stopping known
  unauthorized actions are still required. Preserve original unresolved operation
  identity and physical evidence; do not turn permission loss into automatic retry,
  refund, release of stock or fictional rollback.
- A central policy change that has not reached a disconnected master is still
  pending, not falsely reported as activated. This is different from a client
  missing notification of a change already committed at its master. Previously
  delivered data/exports cannot be remotely erased by changing a role.

Implementation/review ownership remains in existing #12/#13/#14/#16/#35 and bounded
#49/#50/#51; automated cases remain in #42/#59. No second permission engine or new
parallel security Epic is required merely to state this existing boundary clearly.

## Working order and safeguards

Review → prioritize → implement → verify → integrate → update GitHub → continue.
Resolve SDK/admission/session prerequisites and native history while progressing
independent business/recovery work. The first usable local prototype is a milestone,
not a checkout-only exit. See [EXECUTION.md](EXECUTION.md).

Preserve original handoffs, frozen evidence, source integrity and historic failures.
Use isolated synthetic data. Central implementation and verification require the
separate explicit authorization described above. Private contributor/environment
instructions stay local and untracked, not in committed files or Issue descriptions.

## Ordinary builds and upstream library ownership

Use standard Git/Gradle dependency management: change code, build/test the relevant
boundary, inspect errors, correct and rerun. Clean disposable outputs when needed.
No mandatory source snapshots, per-source hashes, one-use directories, operation
IDs, manifests/seals or independent routine-build reviews are required. Keep
concise commands, revisions, normal logs and actual test outcomes. Preserve
frozen expectations and historical evidence; do not reinterpret failures as passes.

P2pKit bugs/security fixes belong in its upstream repository. POS consumes an
appropriate official release, not a locally maintained fork. If official behavior
does not meet an essential security requirement, track the upstream dependency
and hold only dependent integration. Independent local application work proceeds.
