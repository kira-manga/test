# LAN same-identity reconnect supporting experiment (LAN-SIR v1.0.0)

Self-authored, **NOT independent**. Frozen on 2026-11-16 before its first execution.
It diagnoses the recovery-fixture boundary where Client1 fails `RpcClient.open` with
`NotConnected (Negotiation; NotSent)` immediately after its partition is restored. It
changes no frozen case, production code or official SDK setting, and it is not a
recovery, restart or financial acceptance result.

## Hypothesis under test

Official P2pKit rc12 `SessionStore.tryRegister` rejects a new session for a peer that
still has an ACTIVE session in the same direction. A recovered Client keeps its original
SDK identity. After a silent partition, the Host may still hold the Client's old inbound
session until its CLOSE/FIN is delivered or keepalive (default 10 s ping / 30 s timeout)
expires. An immediate reconnect is then refused and `rpcBoundary` reports
`NotConnected (Negotiation; NotSent)`.

## Topology and inputs

Official JVM `RpcHost` and `RpcClient`, real TCP, synthetic in-memory identity and trust
stores (test-only), one probe procedure. Three disposable network namespaces: Host and
Client each have exactly one veth on `10.203.18.0/24`; the bridge and both switch-side
veths live in a network-only switch namespace. The partition sets only the Client's
switch-side veth down, as `LanRecoverySupportingExecutor` does.

## Fixed actions and expected observations

1. Client A connects and completes one probe call. Host lists exactly one Connected peer.
2. **E1 duplicate while healthy:** a second official Client B with the same identity
   connects while A is open. Expected: B fails with kind `NotConnected`, phase
   `Negotiation`, evidence `NotSent`; A's probe still succeeds afterwards.
3. Partition the Client link. A sends one probe (expected to fail; leaves unacknowledged
   TCP data). After 14 s, restore the link. Client closes A (official whole-role close).
4. **E2 immediate reconnect:** a fresh official Client C with the same identity connects
   immediately. Expected: `NotConnected (Negotiation; NotSent)` while the Host's
   observation still includes the old Connected peer generation.
5. **E3 production-shaped recovery:** close the failed Client and retry with a fresh
   Client using the production monitor schedule 1, 2, 4, 8, 16, 30 s. Expected: success
   only after the Host no longer lists the old generation as Connected, and within 45 s
   of restore (30 s keepalive timeout plus one 10 s ping interval plus 5 s margin). The
   connected Client then completes one probe call.

Each step records only fixed enums, generation numbers and elapsed milliseconds; no
identities or payloads are logged. If E1 or E2 is not observed, H1 is NOT confirmed and
that outcome is reported as observed. The executor requires
`POS_DISPOSABLE_TCP_NAMESPACE=true`; prerequisite failure is a failure, never a skip.

## Additive diagnostic wiring 1.0.1

Each actual SDK connect now records bounded private JVM exception events, so E1's
known negative control can establish which SDK boundary generated its error.
All original E1/E2/E3 actions and expected observations remain unchanged. Export
only exception types/static frames; raw recordings/messages remain private. E1
does not by itself establish the historical POS post-partition cause, and a
nonreproduced E2 must still be reported as not reproduced.

## Timing wiring correction 1.0.2

The frozen link-down input is 14 seconds. The earlier executor waited 11 seconds
after a probe with a maximum 3-second timeout, but that probe can fail immediately;
one diagnostic execution therefore measured only 11,059 ms down. That execution
does not qualify the 14-second E2 condition, and its result remains preserved.
The Client now measures monotonic elapsed time after observing the partition and
waits out the remainder of the original 14 seconds. Expected E1/E2/E3 outcomes,
SDK/session/retry timeouts and POS production code are unchanged. Record the actual
partition/restoration timestamps; do not infer the duration from a timeout ceiling.
