# Original approval final-declaration binding

The 12 bounded methods in `ApprovalFinalBindingTest` wire the unchanged
[OH v1 specification](https://github.com/ukkera/kmp-apps/blob/implement/ukkera-pos-local/qa/pos/designs/action-approval-original-history/README.md)
to inert final-declaration data checks. All 12 methods passed on the commit containing the
[execution record](https://github.com/ukkera/kmp-apps/blob/implement/ukkera-pos-local/qa/pos/traceability/approval-original-history-20261101/README.md); complete-case status is unchanged.

- OH-02/07: the original actor, operation and semantic content cannot be replaced
  when comparing paired final declarations.
- OH-04/05: expired, earlier, other-boot or other-issuer observations cannot stand
  in for the last current protected check. This is not a revocation/commit race.
- OH-01/06: matching declaration coordinates and unchanged null declarations
  retain their data framing. Neither supplies issuance, consumption or history.

Operation/actor/session/digest values and the 300-second interval come from the
frozen fixture. Boot/issuer identifiers, purpose tags and monotonic origin are
explicit ABI-only carriers, not asserted original facts or authentication.
No native proof, approval record, session token, writer, commit or permission
is fabricated. All eight complete OH cases remain **NOT_RUN**; native custody,
issuance/consumption, persistence, restart, race and RPC bindings remain **UNBOUND**.

Run `:core:security:jvmTest --tests
'com.ukkera.pos.security.ApprovalFinalBindingTest'` from the POS Gradle root.
