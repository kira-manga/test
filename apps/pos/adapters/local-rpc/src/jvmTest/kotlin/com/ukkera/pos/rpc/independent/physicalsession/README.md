# Physical-session adapter slices

**SYNTHETIC L1.** These 17 JVM methods wire narrower assertions from
the frozen qa/pos/designs/local-rpc-physical-session v1 specification plus its
frozen UNIT-DATA-CLARIFICATION-v1.1.md. The original cases, fixture values and
manifests remain unchanged. Passing these methods does not pass any whole PHS case.

The actual LocalRpcIdentityBinding and SDK session/close wrappers are used.
SDK capability/context inputs come solely from JVM-public data constructors,
called through getConstructor; no private accessibility changes or member
writes occur. Close actions, principal verification and POS peer/currentness
are declared synthetic inputs. No SDK engine, production host, native admission
or transport is injected. The exact frozen fixture JSON is copied into the
input helper; canonical label conversion and controlled ownership/source
helpers are reused from identityv1 without changing its expectations.

| Frozen cases | Methods | Narrow wired assertions |
| --- | ---: | --- |
| PHS-01 | 1 | Original tuple/scope and exact capability supplied to adapter checks. |
| PHS-02 | 1 | Absent native data, conflicting fingerprint/connection data and inconsistent peer/capability input refuse. |
| PHS-03 | 1 | Distinct same-peer/generation capability cannot adopt original authentication, handler or execution/result/status identity context. |
| PHS-04 | 2 | Unscoped, missing-physical, legacy and different SDK application-session inputs are ineligible; origin and operation capability axes remain distinct. |
| PHS-05 | 1 | Caller mutation cannot replace captured credentials/identity; caller/source-disposable ownership and public redaction. |
| PHS-06 | 1 | Current original tuple/purpose/null checks. Revoked/disabled/expired rows use scheduled verifier refusal. |
| PHS-07 | 2 | Zero/negative/infinite bound refusal; controlled currentness loss and disconnect during verification suppress publication. |
| PHS-10 | 2 | Local refusal and original SDK application-session revocation precede pending synthetic close; late verification cannot publish. |
| PHS-11 | 1 | Repeated successful synthetic close remains exact-A and leaves independently bound B usable. |
| PHS-12 | 2 | Retained failure remains Incomplete; B-only acknowledgement cannot confirm pending A. No exact callback count is required. |
| PHS-13 | 1 | Accepted owned retirement survives caller cancellation; another caller observes the declared retained success/failure. |
| PHS-14 | 2 | Binding/authorization cancellation propagates; ordinary source/currentness errors refuse without public private diagnostics. |

Open, Closing, Closed and Incomplete assertions observe the actual SDK
wrapper around a synthetic close action. They do not confirm native teardown.
Declared retained-close failures opt out of JVM coroutine diagnostic exception
copying so exact source-cause identity assertions and cleanup remain meaningful;
no production exception handling or global coroutine settings are changed.
Null scope/principal results establish adapter refusal only; these methods do
not execute private handlers, disclosure writers or business operations.

Unbound: PHS-08/09/15; caller-supplied label-mutation input surfaces; genuine
callback association and same-key reconnect;
protected POS native admission, trust, qualified continuity or expiry; actual
19/20- and 29/30-second deadline windows; elapsed SDK expiry; current Master
resource authority, commit/disclosure ordering, persistence, LAN/replay/restart,
installed platforms and original business effects. An unspecified required
lifetime has no runtime typed overload. A complete private retained-buffer
erasure claim is unbound because no public observation is available; no retained
bearer is inspected through reflection. Caller buffers and source-disposable
copies provide only the narrower ownership observations listed above.

Focused execution command from the POS build, for coordinated verification:

~~~sh
./gradlew :adapters:local-rpc:jvmTest --tests 'com.ukkera.pos.rpc.independent.physicalsession.*'
~~~

Specification-level status is pending coordinated execution. Source-specific
actual results will be linked later by the verification report; this wiring
document does not claim execution.
No native callback, admission, transport or whole-case qualification is claimed.
