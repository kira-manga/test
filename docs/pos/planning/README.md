# Ukkera POS planning system

**Start at [the pinned project tracker #2](https://github.com/ukkera/kmp-apps/issues/2).**
GitHub is the live backlog. Local development is authorized; central implementation
and verification remain held. Read [WORKFLOW.md](WORKFLOW.md) for completion rules
and [EXECUTION.md](EXECUTION.md) for dependency-led sequencing.

## Two visible outcomes

- **[Development Complete](https://github.com/ukkera/kmp-apps/milestone/1):** integrated application behavior, source review, relevant
  passing unit and production integration tests, and resolved in-scope defects.
- **[Owner Acceptance Complete](https://github.com/ukkera/kmp-apps/milestone/2):** actual required device/peripheral/manual variants,
  resolved/rerun real-world defects and explicit owner validation.

Development Features and Epics may close while corresponding manual acceptance
is pending. Missing implementation or required automation still blocks development.
Neither milestone establishes production readiness or authorizes central work.

## Epic navigation

| Epic | Why it is a separate outcome | Local LF requirements |
|---|---|---:|
| [[Epic] Local foundation, setup, identity and real LAN (#3)](https://github.com/ukkera/kmp-apps/issues/3) | One authoritative durable branch runtime; real setup, local human/device authority and secure communication, with reusable decisions in commonMain. | 18 |
| [[Epic] Catalog, inventory and purchasing (#4)](https://github.com/ukkera/kmp-apps/issues/4) | Complete product and inventory lifecycles, opening balances and purchase-to-settlement workflows for all four activities. | 22 |
| [[Epic] Sales, pricing, cash and customer remedies (#5)](https://github.com/ukkera/kmp-apps/issues/5) | Exact prices, counted cash, owned orders, payment evidence and lawful same-branch remedies without inventing money, stock or outcomes. | 23 |
| [[Epic] Restaurant/café production and cross-activity fulfillment (#6)](https://github.com/ukkera/kmp-apps/issues/6) | Recipes, tables, kitchen quantities and food operations plus retail/food handoff, COD and supplied-unpaid incidents. | 13 |
| [[Epic] Reports, receipts and fiscal/accounting (#7)](https://github.com/ukkera/kmp-apps/issues/7) | Traceable documents and authorized reports that distinguish sale, collection, tax, cost, custody and freshness. | 8 |
| [[Epic] Resilience, backup and upgrade safety (#8)](https://github.com/ukkera/kmp-apps/issues/8) | Bounded durable queues, full-state backup, controlled recovery/fencing and compatible upgrades without losing original results or authority. | 10 |
| [[Epic] Native platforms, shared UX and peripherals (#9)](https://github.com/ukkera/kmp-apps/issues/9) | One shared KMP application with genuine platform adapters, all five master/client roles, accessible Arabic/English UX and qualified hardware. | 9 |
| [[Epic] Automated verification and local prototype delivery (#10)](https://github.com/ukkera/kmp-apps/issues/10) | Freeze independent expected behavior, execute real production-boundary automation and deliver runnable candidates; owner acceptance is tracked separately. | 2 |
| [[Epic] Central services — held until owner local approval (#11)](https://github.com/ukkera/kmp-apps/issues/11) | Preserve and plan central management, ingestion, recovery and reporting counterparts; neither implementation nor verification is authorized yet. | Held counterparts only |

Keep the existing nine Epics, 32 local grouped Features, four held-central Features
and bounded Tasks. #61 is the manual-acceptance hub directly under tracker #2; #60
records applicable owner/external facts beneath it. #42 owns independent automation
and #43 the runnable prototype. Reuse existing Issues rather than duplicate work.

## How to track progress

1. Tracker #2 shows development and owner-acceptance lanes, current implemented
   increments, actual automation, remaining work and blockers.
2. Each Epic is a dashboard. Its native children link the detailed Feature/Task
   requirements, dependencies, source/commit evidence and completion criteria.
3. Use `pos:development` and `pos:manual-acceptance` filters and the matching
   milestones. Closed implementation Issues mean their bounded development scope
   is satisfied, not all product requirements or physical acceptance.
4. #61 links every original scenario/feature/gate, platform variants and a result
   template. Mark manual PASS only after actual execution on the recorded build.
5. Native blocked-by edges identify concrete dependencies. Cross-domain links do
   not imply that all work must wait or that unrelated Features form one serial chain.

Status labels retain their practical meaning: `pos:partial` = incomplete
implementation; `pos:unverified` = missing/incomplete required evidence;
`pos:unapplied` = source proposal not integrated; `pos:blocked` = a concrete affected
prerequisite; `pos:central-held` = unauthorized central execution; `pos:owner-input`
= an actual external decision. `pos:P0` is the nearest integration-enabling queue,
`pos:P1` is required local work, and `pos:P2` is the held next phase, not optionality.
Native milestone bars count completed Issues, not product or scenario coverage.
No completion percentage is inferred from code size, source presence or test counts.

## Requirements and evidence

- [REQUIREMENT-ISSUE-MAP.csv](REQUIREMENT-ISSUE-MAP.csv): all **325 entries**, retaining
  **105 features, 141 original scenarios and 79 gates** with original obligations
  and owning Issues. There are 318 local-applicable entries (including mixed) and
  seven central-only gates. Every original scenario has local obligations.
- [REVIEW-FINDING-MAP.csv](REVIEW-FINDING-MAP.csv): all 36 review findings, original
  severities/corrections and owners; no accepted engineering requirement is dropped.
- [TASK-PACKAGE-MAP.csv](TASK-PACKAGE-MAP.csv) and [DEPENDENCIES.md](DEPENDENCIES.md):
  all 40 original packages and preserved completion dependencies.
- [Current status evidence](GITHUB-STATUS-EVIDENCE.md): recent integrated increments,
  bounded automated results and actual technical gaps.
- [Manual catalog](../manual-testing/README.md): existing requirements and unchanged
  expected outcomes reorganized for separate owner acceptance; no new run claimed.
- [ISSUE-REGISTER.json](ISSUE-REGISTER.json), [REVIEW-EVIDENCE.md](REVIEW-EVIDENCE.md)
  and [ORGANIZATION-VERIFICATION.json](ORGANIZATION-VERIFICATION.json): dated
  organization/bootstrap history, not a second live backlog or application acceptance.

Historical whole-requirement states are not development percentages. Local parts
of mixed requirements stay mandatory while central portions remain held. A Task's
closure never automatically passes its whole Feature, Epic or parent package.

## Maintaining the structure

After meaningful integration or a changed blocker, update the owning Issue and
parent Epic with concise project facts, commits, actual tests and remaining work.
Keep detailed cases in linked documentation rather than repeated Issue narratives.
Preserve useful historical evidence; use a clean current summary instead of deleting
valuable audit comments. Private execution/configuration notes stay local only.

The pinned tracker and native hierarchy are the supported tracking view. A GitHub
Project view is not established in the current organization record. If one is later
available, inspect/reuse it and add these same Issues rather than create a parallel
backlog. No credential or organization-wide policy change is part of this work.
