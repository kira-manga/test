# Arrival approval client journal checks

These eight methods bind bounded client slices of the unchanged
[AAW v1 specification](../../../../../../../../../../../../../../qa/pos/designs/arrival-approval-workflow/README.md).
They exercise the actual shared gateway and encrypted JVM journal, including
non-Direct Record form/staging, denied preview, durable confirmed original
identity before delivery, refusal, lookup-only unknown issuance, requester
retirement, same-ID changed input, receipt metadata, terminal retention and reopen.

The storage uses real AES-GCM and durable file operations. Its JCA key, owner,
current-source/read/absence observations and retained client receipt/result
values are controlled data, not genuine native Master authority. No restricted
Master preview or receipt is manufactured. Positive Master issuance, consumption,
business persistence, protected original history and RPC remain UNBOUND;
complete AAW/OH cases remain NOT_RUN. Execution results are recorded separately.

Selector: `com.ukkera.pos.app.gateway.independent.arrivalapprovalclient.*` on
`:adapters:app-gateway:jvmTest`.
