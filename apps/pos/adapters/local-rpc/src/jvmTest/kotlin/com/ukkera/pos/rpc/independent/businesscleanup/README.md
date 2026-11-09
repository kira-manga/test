# Business request cleanup v2 supporting JVM binding

This harness binds frozen version **2.0** at
`qa/pos/designs/business-rpc-request-cleanup-v2/`. Its README, cases and fixtures
are checked against their frozen hashes before use. All caller, borrowed-copy
and retained-bearer comparisons cover the exact **52 bytes** from the fixtures.
Version 1.0 remains unchanged and NOT RUN: its 32-byte carrier is unbindable to
the existing typed requests. No short fixture is padded or transformed.

| Frozen IDs | Bound supporting behavior |
| --- | --- |
| BRC-001, all nine peers | Actual typed capture; caller mutation; independent equal-coordinate owners; complete borrowed/retained buffer wiping |
| BRC-002, all three types | Held synchronous borrow; immediate terminal claim/use refusal; entered cleanup waiter; completion after return |
| BRC-003, both variants/all types | Two distinct held copies; first-return ordering; exact `BORROW_FIXTURE_FAULT`; final-return cleanup |
| BRC-004, all three types | Empty/repeated retirement; three completed waits; permanent claim/use refusal |
| BRC-005, all three types | Two entered waiters; one real waiter cancellation; unaffected held loan and owner cleanup; later successful wait |
| BRC-007, `pre_threshold`, `expiry_first`, `claim_first` | Genuine typed decoding/custody; controlled 29,999/30,000/60,000-ms boundaries; claimed owner remains live until its own cleanup |
| BRC-010, all types/both phases | Exact 256-input mixed inventory; terminal/incomplete capacity retention; refused input 257; explicit completed-cleanup recheck; one replacement restores 256 |
| BRC-011, initial/final SUPPORT only | Exact 256 retained identities, original claim patterns/rotation, retired admission refusal, three selected held loans, every unclaimed retirement before waiting, claimed owner retirement, successful final census 0 |

There are eight JVM test methods. The first seven bind supporting assertions for
36 expanded frozen IDs. The BRC-011 method touches nine further IDs only through
the declared reduced SUPPORT schedule: it holds I000/I001/I002, rather than all
256 borrows. The full frozen BRC-011 cases remain unqualified. In particular,
progressive physical census removal after each exact cleanup is not asserted;
completed entries are never filtered out to manufacture an intermediate count.
No maintenance step is added to the BRC-011 schedule.

The custody adapter uses the actual Read, Execute and Lookup serializers and
`LocalRpcBusinessRequestCustody`. `TestTimeSource` supplies monotonic time. Borrow
bodies run synchronously on explicit entry/release/return barriers. Waiters start
UNDISPATCHED and must actually suspend before a pending assertion. Every pending
check advances the actual `TestScope` scheduler to run ready continuations to
their next suspension; a completed waiter with a queued continuation cannot
count as pending. No elapsed sleep is race evidence. Teardown releases all held
bodies before bounded cleanup.

Test-only reflection reads the request's retained buffer and custody's raw
physical snapshot, including exact captured request references. It does not
invoke census closures, mutate buffers/state, prune retention or force cleanup.

BRC-006/008/009/012/013/014/015 and BRC-007 `concurrent` are unwired. Ingress
claim/refusal ownership, noncancellable callback cleanup and accounting/drain
behavior are not represented by a copied callback implementation. BRC-012's
admission race and progressive physical release remain unqualified. There is no
declared genuine owned-cleanup-fault binding for BRC-013; waiter cancellation
does not substitute for `CLEANUP_FIXTURE_FAULT`.

All genuine groups BRC-G01..G05 remain outside this binding: admitted RPC/Master
authorization, native/SDK authority, durable effects and restart recovery,
installed platform/network/lifecycle runs, and owner hands-on acceptance.
Kotlin/Native and other unexecuted targets remain NOT RUN. Supporting checks do
not qualify original business scenarios or central work.

Run from `apps/pos`:

```sh
./gradlew :adapters:local-rpc:jvmTest --tests 'com.ukkera.pos.rpc.independent.businesscleanup.*'
```

Harness wiring is not a passing execution result. Preserve the frozen result
ledger and record actual revision, outcomes and limitations in separate evidence.
