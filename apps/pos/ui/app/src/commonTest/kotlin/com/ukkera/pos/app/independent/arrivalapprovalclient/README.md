# Arrival approval client presentation checks

These eight methods bind bounded presentation slices of the unchanged
[AAW v1 specification](../../../../../../../../../../../../../qa/pos/designs/arrival-approval-workflow/README.md).
They exercise the actual shared controller, navigation and attachment lifecycle:
review/cancel, confirmation before authentication, requester preservation,
lookup-only unknown issuance, session retirement, queued-secret cleanup,
attachment replacement and mismatched returned identities.

Review and receipt values are supplied documentary data. No genuine Master
authentication, approval issuance/consumption, business commit, native custody
or RPC behavior is asserted. Whole AAW/OH acceptance remains NOT_RUN/UNBOUND.
Execution results belong in the related traceability record, not in the freeze.

Selector: `com.ukkera.pos.app.independent.arrivalapprovalclient.*` on
`:ui:app:jvmTest`.
