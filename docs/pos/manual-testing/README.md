# Owner manual testing and real-world acceptance

**Status: NOT RUN. No current owner-testing build is qualified.**

GitHub [manual hub #61](https://github.com/ukkera/kmp-apps/issues/61) tracks this
milestone separately from [automated verification #42](https://github.com/ukkera/kmp-apps/issues/42)
and [prototype delivery #43](https://github.com/ukkera/kmp-apps/issues/43).
Development completion does not imply physical/owner acceptance or production readiness.

[Runtime campaign #63](https://github.com/ukkera/kmp-apps/issues/63) owns deferred
emulator, iOS simulator, installed/physical-device and peripheral execution using
this unchanged catalog. Pending campaign runs alone do not block a fully
implemented, reviewed and non-device-tested implementation Issue. Known defects,
missing integration and failed required non-device tests remain development work.

## Start here

1. Obtain the exact local candidate and its setup/runbook from #43. Record its commit,
   artifact checksum, application/database version and the actual OS/device model.
   Do not substitute an older APK merely because it installs.
2. Use disposable synthetic branch data and the original fixture definitions below.
   Never use live business records, real credentials or real charges. Actual terminal
   or fiscal exercises require their own explicit authorization and approved environment.
3. Start the real local master and connect the application using its actual protected
   same-device adapter or admitted LAN connection. No mock checkout/server replaces it.
4. Choose one business scenario or rehearsal. Establish its listed fixture, perform
   the numbered actions, then compare every expected result against the actual app
   and independent persisted report/ledger/result observations where specified.
5. Record each run in a copy of [RESULT-TEMPLATE.csv](RESULT-TEMPLATE.csv). Keep
   expected text unchanged; attach redacted observations, failures and defect links.
   Repeat required variants/platforms separately; never overwrite one failed run
   with a passing run from a different platform or build.

## Test catalog

| Section | What to run |
|---|---|
| [Business scenarios](SCENARIOS.md) | All **141 original scenarios**, with original trigger, expected result, fixture and variant references. |
| [Feature checklist](FEATURES.md) | All **105 feature definitions**: required local behavior and original rehearsal needs; no code-existence completion shortcut. |
| [Resilience and practical rehearsals](RESILIENCE.md) | **70 already independently specified cases**, including full activity shifts, restart, security, backup, platforms, physical peripherals and accessibility. |
| [Quality and platform matrix](QUALITY-AND-PLATFORMS.md) | All **79 original gates**, the **25 ordered two-device role pairs** and **five protected same-device profiles**. |
| [Coverage map](COVERAGE.csv) | Every original feature/scenario/gate linked to its development Issues and this separate manual-acceptance hub. |
| [Fixture and network reference](ENVIRONMENTS.md) | Original synthetic fixtures, network profiles and actual OS/peripheral obligations. |

This catalog is a **reorganization of existing requirements and frozen independent
expectations**, not a newly designed or executed suite. Trigger/expected text,
feature obligations and the detailed resilience actions/assertions are copied
without changing their required outcomes. The frozen originals remain authoritative.

## Practical execution order

- First: RSC-001/002/003 and applicable setup scenarios; then the appropriate full
  restaurant/café, clothing, supermarket and purchasing rehearsals RSC-065–068.
- Run each remaining applicable business scenario using its own reset fixture.
- Run connectivity/session/privacy/restart/backup and failure cases with the
  specified controlled-fault tools. If those tools or a safe fixture are missing,
  record **BLOCKED**, not a simulated success.
- Execute OS lifecycle, physical peripherals, Arabic/English/RTL/assistive input
  and the required role-pair matrix on the actual named profiles.
- Finish candidate/upgrade/runbook review and owner feedback under RSC-070/#61.
  Product acceptance remains pending until the required variants actually pass.

Pure code, database, load and protocol gates also retain their automated owners in
#42/#59 and the development Issues. Listing a QA-assisted real-world rerun here does
**not** move missing implementation or required non-device automation into this lane.
An owner may need technical assistance for process/power/storage fault schedules;
never claim ordinary UI clicking exercised those faults.

## Results and limitations

Use `NOT_RUN`, `PASS`, `FAIL`, `BLOCKED`, `INVALIDATED` or a justified
`NOT_APPLICABLE`. `DEFERRED_CENTRAL` is not PASS. A row is a checklist obligation,
not a single run sufficient to satisfy all of its variants. Store distinct runs
with tester, date, build, activity, platform/roles, fixture, network/fault schedule,
actual observations, evidence and defect reference. Do not put credentials in GitHub.

All four activities and five platforms remain required. Only the original seven
central-only gates are fully deferred; local portions of mixed scenarios still run.
A policy notification missed by the client cannot authorize a master-side request.
Central policy not yet received by a disconnected master remains pending, not
activated. Master/current-policy automation is tracked in #12/#13/#14/#16/#35/#50.

Physical device/peripheral checks remain pending independently of Development
Complete. #60 holds actual deployment/legal/recovery facts; illustrative tax and
capacity fixtures do not certify legal compliance or deployment capacity. Central
implementation and verification remain held until actual local owner testing and
**separate explicit approval**, even if development or owner-acceptance Issues close.
