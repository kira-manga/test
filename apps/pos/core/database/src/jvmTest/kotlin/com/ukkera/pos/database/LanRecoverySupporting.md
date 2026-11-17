# LAN recovery supporting process fixture

Self-authored, **NOT independent**. This executor does not modify or replace the
frozen inputs, actions or expectations in
[`lan-master-client-v1/cases.json`](../../../../../../../../../../../qa/pos/designs/lan-master-client-v1/cases.json)
(LAN-R01/R03/R04/R05), the
[`v2 supplement`](../../../../../../../../../../../qa/pos/designs/lan-master-client-v2/cases.json)
(LAN2-U05/U09), or their freeze records. It uses smaller supporting inputs described
below. Whole cases remain NOT RUN, regardless of this executor's exit status.

**Executed boundary:** the supporting process attempts reached genuine original
Master installation and both additional-device admissions/imports, then failed
at official `RpcHost.start()` with `NotConnected (Negotiation; NotSent)` before
publishing a TCP endpoint. The revised attempt checked the actual isolated
interface/address and deferred owner cash configuration until after Host startup;
it failed at the same boundary. The cause is not established. Current UI
authentication, subscriptions, partition/reconnect, process restarts and retained
operation recovery below remain **unexecuted** by this fixture. Compilation and
separate launch/cache fault-unit results do not qualify those actions.

The fixture uses genuine company/installation, original device and head-approved
employee admission, original SDK identities, actual Room/native-history ownership,
protected encrypted device/actor/read-view journals and current production UI
authentication and subscriptions over real TCP in three isolated LAN namespaces.
Namespace keepers retain networking only; each application restart is a fresh JVM
using the same original role root. No copied database or new identity is used.
The corrected executor puts the bridge and its slave interfaces in a separate
network-only switch namespace. Each application namespace has exactly one active
LAN interface and loopback. A JVM started after the earlier topology was configured
could enumerate the bridge slaves as additional active interfaces; the official
SDK correctly refuses competing interfaces. An isolated native-interface probe
reproduced that prerequisite violation. This correction has not yet qualified the
reconnect or restart actions below; the two failed attempts remain unchanged.
Linux POSIX file operations, installed-app observations and persistent exportable
software keys are explicit test OS stand-ins. Windows/macOS native key custody,
Android, iOS, owner devices and power-loss durability are not qualified.

## Fixed supporting actions and expected observations

Supporting wiring revision **1.0.1** explicitly enrolls
`OPERATION_RESULT_RECOVERY` with the existing own-only grants, as required for
the original lookup and delivery being tested. The earlier 1.0.0 process attempt
with the corrected network topology reached both current UI sessions and committed
the original metadata command, then timed out without result delivery. It omitted
that required grant; its source, native original and failed outcome are preserved.
No production permission check or frozen expectation is changed.

Supporting wiring revision **1.0.2** (2026-11-16, frozen before its first execution)
changes only step 4's reconnect wiring. Earlier attempts made one
`recoverTransport()` + `connect()` immediately after the partition was restored, and
that connect failed with `NotConnected (Negotiation; NotSent)`. Production does not
connect once: `LocalSetupCoordinator`'s Client monitor retries `recoverLanClient` with a
1 s doubling backoff capped at 30 s. Revision 1.0.2 mirrors that schedule. It retries
only the exact official same-peer refusal shape (`NotConnected`, `Negotiation`,
`NotSent`), at most 7 attempts. Each attempt makes a confirmed whole-role close and
recovers the same SDK identity, which is checked. Any other failure is fatal. The
attempts and their elapsed times are recorded in the exchange
(`client1.reconnect.attempts`) and the log.

The executor also samples the Master and Client1 kernel TCP socket tables (`ss -tnoi`,
synthetic addresses only, 500 ms period) during the partition/reconnect window. This
sampling is observation only.

Supporting experiment LAN-SIR v1.0.0 (`LanSameIdentityReconnectSupporting.md`)
confirmed that a same-identity duplicate connect produces exactly that refusal shape.
However, its bare official Host/Client immediate reconnect after a 14 s partition
succeeded in 256 ms. The POS-specific cause of the refusal is therefore not
established by LAN-SIR. RPC, session and native deadlines are unchanged.

1. Install the genuine Master; admit two distinct Clients and employees. Start
   the original Host and complete both Clients' encrypted-journal UI sign-in and
   Current before configuring cash custody. Then start automatic CASH subscriptions
   and capture their protected coherent projection/cursor batches.
2. Client1 stages a real `DRAWER_CONFIGURE` UI intent, durably marks its exact
   original dispatched, and commits it through the real protected remote command.
   Intentionally withhold application result retention/ACK. Preserve the actual
   Master result for exact comparison; this is metadata, **not a financial sale**.
3. Partition only Client1's veth. Master commits another drawer while Client2
   remains connected. Client2 must adopt it through its production UI subscription
   before the link is restored. No manual Refresh or mutation replay is used.
4. Client1 confirms the old transport's production close, recovers the same SDK
   identity, reconnects and explicitly signs in again with the existing device-move
   option. UI close retains the old Master session; the one-session policy is
   unchanged. Its automatic UI view must
   obtain the missed drawer. Only original lookup recovers the dispatched command;
   the original ID/body/digest/actor and actual committed result remain exact,
   with no ACK or second mutation.
5. Close Client1's process and commit another Master drawer while Client2 remains
   active. A fresh Client1 JVM must reopen its exact target and credential/actor
   journals with REOPEN, retain revocation material and expose no actor journal
   before fresh current authentication. After explicit sign-in with the existing
   device-move option it must observe the
   missed drawer automatically and retain the unresolved original/result. Its old
   session-bound cursor cannot grant progress under the new session.
6. Confirm both Clients' and Master's native close. Start a fresh Master JVM using
   existing-only production composition and matching RUN/native/SDK identity.
   Commit another drawer. Fresh Client JVMs reopen the same originals, explicitly
   authenticate, obtain coherent current batches and preserve the exact original
   result through lookup. Persist final Master STOP without changing its format.

The executor requires `POS_DISPOSABLE_TCP_NAMESPACE=true` and Linux namespace
capability. Its orchestration watchdogs are harness limits, not product SLAs; RPC,
native-operation and session deadlines are unchanged. Prerequisite/failure exits
are failures, never a skipped/pass variant. All originals and logs remain under
the printed temporary evidence root on both success and failure.

## Deliberate coverage limits

No F-RETAIL stock/cash/sale totals, F-PAGES whole-case pagination, F2-TIME boundary
and 18240/sellable3 observation, unknown financial outcome, frozen atomic-cache
fault cuts, offline finalization attempt, continuous subscription across process
death, native supervisor startup, retired/sealed writer recovery, P1 performance
qualification or complete native platform campaign is supplied. Final STOP is
only the genuine persisted launch hint, not a completed administration lifecycle
stop/retirement case. These remain the original frozen requirements and open work.

`LocalLaunchStorageRecoverySupportingTest` separately checks the shared pre-native
namespace decision and real desktop launch bytes. It is also self-authored, NOT
independent, and is not Android AtomicFile execution or whole-case acceptance.

## Additive diagnostic wiring 1.0.3

The step-4 transport recovery/connect window now records bounded JVM exception
events before the official SDK maps several different failures to `NotConnected`
without retaining their cause. The 1.0.2 actions and expected observations above
remain unchanged, including exact identity, confirmed close, retry schedule,
current authentication and original-result checks. Recordings are private test
evidence; export only exception types and static frames, never raw recordings or
exception messages. This adds no product timeout, retry or authority. A passing
run without a refusal does not establish the historical POS refusal's cause.

Timing correction to the older LAN-SIR description: archived partition/restoration
timestamps show the first SDK experiment cut lasted 11,051 ms, and the diagnostic
repeat lasted 11,059 ms. The nominal 14 seconds assumed the probe consumed its
entire timeout. Neither is a qualified 14-second E2 counterexample. LAN-SIR wiring
1.0.2 now enforces the original 14-second input independently of probe duration;
the historical results and unproven POS-cause classification remain preserved.
