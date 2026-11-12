# POS repository layout and relocation history

The POS is an independent Gradle build in `apps/pos`, in
[ukkera/kmp-apps](https://github.com/ukkera/kmp-apps) on
`implement/ukkera-pos-local`. Queue is a separate product; its sources, build and
release configuration are not part of POS changes.

## Project locations

| Content | Repository location |
|---|---|
| POS application and shared modules | `apps/pos/` |
| Original requirements (19 documents) | `docs/pos/original-handoff/` |
| Reviewed plan and accepted corrections | `docs/pos/plan-review/` |
| Independent requirements, contracts and frozen expectations | `qa/pos/requirements/`, `qa/pos/contracts/`, `qa/pos/freezes/` |
| Independent harnesses | `qa/pos/wiring/` |
| Revision-specific verification records | `qa/pos/traceability/` |
| Current scope, dependencies and work | [GitHub tracker #2](https://github.com/ukkera/kmp-apps/issues/2) |

The relocation began from commit `2462dc636dcad25a46f0edbf2f6eeae8ce1bb9f2`.
Original requirements, frozen expectations and prior failure evidence were
preserved. A relocation or source copy is not verification of application behavior.
See the [historical relocation record](https://github.com/ukkera/kmp-apps/blob/e2825ccf1f4a70daeb3fcf5255b843b0ed50a4e5/docs/pos/REPOSITORY-MOVE.md)
for its dated source and test observations; they are not current build requirements.

## Build and continuation

Use JDK 21 and a configured Android SDK. From the repository root:

```sh
cd apps/pos
./gradlew :apps:android:compileLocalTestingKotlin
./gradlew :apps:desktop:compileKotlin
```

Run the relevant module tests for code changes. These are build examples, not a
passing test result. Dependency caches, signing material and machine configuration
are not repository content. Use normal reusable Gradle builds; the retired custom
verification wrapper and single-use SDK-copy workflow are not prerequisites.

Continue through the [development guide](AGENT-HANDOFF.md),
[current evidence](CURRENT-STATUS.md) and [completion policy](planning/WORKFLOW.md).
All four business activities and five platform Master/Client roles remain in scope.
Device/owner acceptance is separate from development completion. Central
implementation and verification remain held pending actual local owner testing
and separate explicit authorization.
