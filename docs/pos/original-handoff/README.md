# Ukkera POS — implementation handoff package

Prepared 21 October 2026. All company administration, cashier and operational interfaces belong to the Compose Multiplatform app. Working repository: `ukkera-pos`. New YAML namespace: `BranchPos`.

## Give this package to the main agent

1. Extract the complete archive into the agent's accessible workspace. Keep all files together; the portable copies use relative document links.
2. Paste [POS_AGENT_START_PROMPT.md](POS_AGENT_START_PROMPT.md) as the execution instruction.
3. The agent begins with the [implementation plan](POS_IMPLEMENTATION_PLAN.md) and [40-task catalog](POS_AGENT_TASKS.md), then follows the domain, technology, business and quality contracts linked from them.

The main agent coordinates implementation and independent reviews, integrates source, runs the combined checks and owns the final result. A worker finishes a bounded change, queues review and takes independent ready work or reviews a peer; unreviewed dependencies are not silently used as stable foundations.

## Contents and starting status

- Complete accepted business/architecture specifications for the four activity families and all requested platforms.
- A domain/persistence/API blueprint, new YAML/generator boundaries and native company-administration identity model.
- Current official library research and the required compatibility spike for Nav3, Koin, Coil, Room, P2pKit and supporting tools.
- Forty implementation work packages with ownership, independent reviewer families, dependencies, deliverables and acceptance.
- A test hierarchy covering domain properties, real storage/services, UI, security, concurrency, faults, recovery, capacity, installed devices and fiscal/peripheral integration.
- 141 business scenarios and 79 additional engineering gates. The CSV preserves the 141 business IDs and starts every row at `NOT RUN`.
- A scoped handoff review record and a machine-readable manifest for the packaged files.

The planning package is complete enough to start implementation. It does not certify an application as production-ready: no POS runtime, generated new YAML, endpoint or installed-device test was executed while preparing it. Current published dependency candidates still require the documented build/runtime spike. Actual peripheral models, legacy OS constraints, workload and applicable fiscal credentials remain deployment facts; provisional test profiles and explicit external gates are provided.

No private cookies, production credentials, customer records, installed applications or personal-skill directory is required to read this package. The implementation agent obtains repository access and actual authorized environment inputs through its own configured workspace. Essential engineering rules are contained here; source links and historical repository findings support revalidation.
