# Environments and synthetic fixtures

The following fixture, network and platform requirements are copied from the original test plan. No fixture or physical profile is claimed to be implemented or executed. Central service execution remains held.

### 4.1 Synthetic fixture catalog

Each fixture starts from a versioned seed manifest with stable IDs, independently reset documents and a known ledger cut. Never run against live business data, copied credentials or inherited remote environment defaults.

| Fixture | Contents and purpose |
| --- | --- |
| F-BASE | Organization O1 containing clothing C1 and restaurant C2; two branches per company; unrelated organization O2; same human with separate branch accounts; owner, other cashier, branch manager, company head, inventory, KDS and recovery identities; two exclusive drawers |
| F-AUTH | Admitted/unadmitted/retired devices, valid/revoked/expired sessions, explicit denies, absent/configured numeric limits, stale policy and version-bound approvals |
| F-CASH | Independent opening/count/transfer fixtures; 1,000 + 5,000 - 400 - 200 + 100 - 150 - 3,000 = 2,350; counted 2,360; retained float 500; card total 4,000 |
| F-ORDER | Owner-private parked/open orders, handoff candidates, tables, compatible/incompatible bills, collection locks and earlier actors |
| F-KITCHEN | Ten bread units and 1,500 g meat; burger consumes one bread and 150 g; per-portion task states, two stations, paper/KDS paths, modifier and historical recipe versions |
| F-STOCK | Last-unit competition, reservations, unavailable/expired/quarantined lots, exact unit conversions, serials, ledger/count cuts, dispatch/in-transit/receipt identities, last-unit carrying-value residual and original-cost customer return into a changed average-cost pool |
| F-PAY | Cash/external/split tenders, 300 bill with 100 collected, 200 incident, paid-unsent preparation, unknown attestation, COD collector custody and refund obligations |
| F-RETURN | Locally retained immutable receipt and remaining entitlement; partial-return races; 100 return versus replacement 130 or 80; pending refund; actual customer delivery timestamp |
| F-PRICE | Company revision 5 at 100, branch override 120 against revision 5, company revision 6 at 150; mixed tax 114 inclusive + 100 exclusive at illustrative 14%; tied/overlapping promotions |
| F-CATALOG | Selected size/color combinations, multiple barcodes, 12-unit then 10-unit pack versions, valid/invalid dimensions, serials/lots, virtual/assembled kits, output batches and packaging-deposit entitlement |
| F-PURCHASE | PO branch allowances, 24 delivered/22 accepted/2 held, invoice/advance/payment/credit allocations, 10 units at 20 + 10 at 30, supplier return carrying value 250 versus credit claim 300 with explicit variance 50, stock A/B values 100 each plus service 100 and freight 30 |
| F-RECOVERY | Checkpoint/journal/outbox/inbox, open shifts/orders, reservations/unknown payments, return entitlements, purchasing disputes, protected local credentials, delegated replacement and all handover states |
| F-SETUP | Incomplete/resumable setup, duplicate/corrupt import rows, training/live separation, local credential provisioning, opening stock/float and optional absent standby |
| F-UI | Arabic/English labels, long/mixed-direction values, large text, compact/expanded layouts, scanner/key/touch focus, clean/dirty/blocked navigation states |
| F-SCALE | Synthetic P0/P1/P2 datasets and deterministic workload seeds; no production customer data or externally sent notifications |

### 4.2 Network and failure profiles

| Profile | Required behavior under test |
| --- | --- |
| N0 | Healthy LAN, central reachable, optional standby healthy; baseline |
| N1 | WAN and central unavailable while LAN/master healthy; local login/refresh/sales/returns/receiving continue within delegated permissions |
| N2 | WAN unavailable and no standby configured or reachable; same ordinary completion boundary as N1 |
| N3 | Client-master partition, master change of LAN address, packet loss/latency/reconnect; no alternative writer and pending operations retained |
| N4 | Duplicated/reordered/truncated RPC or sync delivery, lost request/response/ACK, concurrent clients and stale document versions |
| N5 | Kill/restart, low storage, I/O failure, migration interruption, OS lifecycle suspension/termination; no false success |
| N6 | Planned handover/emergency recovery, stale old master, divergent checkpoint, unavailable central and stale optional standby |
| N7 | Delayed/invalid/revoked company configuration, permission and catalog packages; atomic activation and truthful version status |
| N8 | Peripheral failure, paper out, disconnected scale/scanner, uncertain print response and physical cash/refund evidence |

`BASE-OFFLINE` in traceability means N0, N1 and N2, plus scenario-specific profiles. Financial mutators also require N4 retry/concurrency and N5 commit-boundary checks even when a row lists a shorter business-specific profile. Negative scope cases must exercise direct request paths, not only disabled UI controls.

### 4.3 Platform roles

`ALL-MASTER` means Windows, macOS, Android, physical iPhone and physical iPad hosting the master, respecting each platform's operating contract. `ALL-CLIENT` means those platforms acting as admitted clients. `CMP-ADMIN` is the company administration UI on those same app platforms. `CENTRAL` means the actual isolated Go/auth/MongoDB service stack. `PHYSICAL-PERIPHERAL` requires the selected compatible adapter and named device.

Run shared domain tests on the available JVM/native targets, every applicable L2 suite against each production SQLite driver, and critical workflows with every required master platform. At candidate time run at least the core login → stock check → sale → same-branch return → drawer close flow across all 25 ordered master/client platform pairs. Do not claim native iPhone/iPad qualification from simulator-only evidence. Wider scenarios may use risk-based pairwise combinations, but payment uncertainty, recovery, role changes and platform lifecycle need their explicit critical variants.

One-device tests must use the protected local adapter; they cannot route through an unsupported P2pKit loopback workaround. Desktop service/UI separation and mobile in-process/service arrangements must preserve identical authorization and command invariants.


## 14. Required OS, packaging and physical peripheral matrix

Determine minimum supported OS versions, CPU architectures and actual device models during the foundation compatibility spike; record them before freezing dependencies and packaging. The unspecified facts do not authorize dropping an agreed platform. Desktop Windows/macOS launchers, native libraries and service lifecycle need their own runners; a macOS development build does not prove a Windows package.

| Surface | Required gate before its deployment claim |
| --- | --- |
| Windows | Debug/release compilation, packaged runtime installation/launch, single writer/service supervision, logout/UI-close independence, restart/upgrade/uninstall data policy, firewall/LAN permissions, real scanner/printer/drawer adapter |
| macOS | Same desktop requirements plus supported architectures, app privacy/network behavior, packaged native library loading, sandbox/entitlement/signing/notarization checks as applicable to distribution |
| Android | Release/R8/lint/resource/ABI checks, phone/tablet UI, foreground-service declaration/lifecycle and notification, permission denial, battery/OEM restrictions, screen-idle policy, manual lock, swipe-away/force-stop/reopen and local-network reconnection |
| iPhone | Simulator compile/integration plus physical app/master tests, local-network permissions, active-app screen-idle prevention, manual lock/background/termination truthfulness, reopen recovery, LTR/RTL gesture matrix, packaged framework/linkage |
| iPad | Physical compact/expanded/rotation/multitasking behavior, same hosting limits and recovery, touch/keyboard/RTL gestures, supported refresh rates; iPhone evidence does not replace tablet qualification |
| Printer/KDS | Selected LAN/USB/Bluetooth mechanisms per supported OS; Arabic/bidi/font/codepage/raster output, totals/QR/barcodes, paper out, cutter, connection loss/unknown response, explicit duplicate-copy marking and no duplicate preparation instruction |
| Cash drawer | Correct assigned physical drawer, open command permissions, unavailable/jammed/repeat request behavior and no fictional cash movement; opening hardware is separate from recorded custody |
| Barcode scanner | Real HID/camera/SDK paths chosen for each profile; leading zeroes, variants/pack/weighted formats, fast scans, focus restoration and malformed code handling |
| Scale | Named supported model/protocol; tare/zero/stability/unit/precision, disconnect/invalid readings, operator-approved manual fallback if permitted; no inferred legal calibration approval |
| External payment terminal | Synthetic manual attestation flows in automation; supervised real terminal receipt/cancel/refund reconciliation when the launch business authorizes it. Do not initiate real charges merely to run automated tests |
| Fiscal/accounting | Determine applicable Egyptian invoice/e-receipt regime and required fields/taxes/legal rounding, credentials and submission deadlines; validate official sandbox, retries/rejections/corrections and actual launch requirements before claiming compliant readiness |

Hardware-specific capabilities can be supported only on qualified adapter/OS combinations; explain unsupported combinations during setup. General app/platform support is not a promise that every printer or scale connects to every OS.

Unsigned compile/packaging can proceed locally. Production signing, store distribution, notarization and real fiscal/terminal operations need their actual authorized credentials and execution environment; do not fabricate evidence or put secrets into fixtures. Capture these gates as `BLOCKED` until satisfied, not as green source checks.
