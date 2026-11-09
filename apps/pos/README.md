# Ukkera POS

Local-system implementation of the accepted branch-authoritative Ukkera POS plan.

**Status:** foundation work in progress. This is not yet a complete or production-qualified POS.

- Original requirements: [`../../docs/pos/original-handoff/`](../../docs/pos/original-handoff/) (19 files, preserved).
- Lead review: [`../../docs/pos/plan-review/`](../../docs/pos/plan-review/) (preserved review and resolutions).
- Start here: [architecture](../../docs/pos/ARCHITECTURE.md),
  [current status](../../docs/pos/CURRENT-STATUS.md),
  [continuation handoff](../../docs/pos/AGENT-HANDOFF.md).
- Current owner priority: a useful, explicitly limited [Android testing candidate](../../docs/pos/ANDROID-TESTING.md)
  with real setup/login/stock/checkout; not more cosmetic cleanup. All unfinished
  local features remain required. [Cleanup evidence](../../docs/pos/CLEANUP.md)
  retains its independent runtime gaps. Ordinary implementation names use Git
  history; genuine storage/protocol versions and frozen QA evidence stay explicit.
- Detailed historical records: [TASK_BOARD.md](TASK_BOARD.md),
  [DECISIONS.md](DECISIONS.md), [INTEGRATION_STATUS.md](INTEGRATION_STATUS.md),
  [VERIFICATION_REPORT.md](VERIFICATION_REPORT.md). Earlier results do not apply
  automatically to the current source.
- Owner authorization: ordinary commits/pushes only to `ukkera/kmp-apps` branch
  **`implement/ukkera-pos-local`**. No Actions, PRs, other-branch pushes, releases,
  publishing or deployments. Builds/tests and business data stay local/synthetic.
- Migration/status: [`REPOSITORY-MOVE.md`](../../docs/pos/REPOSITORY-MOVE.md).
  The old standalone source and historical execution evidence remain preserved
  locally; historical source hashes are not results for this checkout.

Scope includes restaurants, cafes, clothing and supermarkets; Windows, macOS, Android, iPhone and iPad clients and master roles; native CMP company administration; local offline identity and durable master checkout; inventory, kitchen, cash, refunds, procurement and recovery. No scope item is removed to make an early slice pass.

The project is built in bounded independently reviewed work packages. Early module tests and a retail vertical slice are learning/integration gates, not completion of the entire scope. Actual device/peripheral/fiscal evidence remains required for those claims.

## Local verification

Open `apps/pos` as the project, or `cd apps/pos` from the repository root before
running the commands below. POS intentionally retains its own settings/catalog
and wrapper. Queue's root app selectors do not select or qualify POS.

Central server/backend implementation **and verification are held** until the owner
has tested the complete local system and explicitly approves that next phase.

Use JDK 21 and configure `ANDROID_HOME` (or `ANDROID_SDK_ROOT`). Run ordinary
Gradle commands; choose the modules relevant to the change:

```sh
./gradlew :core:security:jvmTest :ui:app:jvmTest --console=plain
./gradlew :apps:android:assembleLocalTesting --console=plain
./gradlew :apps:desktop:compileKotlin --console=plain
```

Add `--offline` only when required dependencies are cached. Inspect failed builds,
fix their actual cause, clean disposable outputs if necessary, and rerun. Keep
normal Gradle reports/logs and note the tested revision and any dirty changes.
No source-copy, single-use directory, receipt or hashing framework is required.
Existing regressions are not a substitute for independently designed acceptance
tests. Compilation is not runtime, LAN or owner acceptance. Apple klib compilation
does not establish Apple application linking/execution.

The [P2pKit integration boundary](integrations/p2pkit/README.md) uses official
upstream releases, not a maintained POS fork. Essential F13 authorization behavior
remains a dependency gate. Historical SDK patches/results remain preserved but
are not active build instructions. The current runtime is incomplete; see the
[current status](../../docs/pos/CURRENT-STATUS.md) before device testing.
