package com.ukkera.pos.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_NOFOLLOW
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
import kotlinx.serialization.json.*

/** Self-authored, NOT independent. NEW opt-in supporting mapping of frozen LAN v1
 * LAN-D04/D06/D08/A07/R04 (qa/pos/designs/lan-master-client-v1/cases.json).
 * Uses the existing genuine admitted TCP peer and actual protected BranchUiSession,
 * paired encrypted actor journal and Client/Master physical journal owners. This is a
 * supplier advance, NOT either frozen paid-retail transaction or the whole LAN suite.
 * Session/UI delivery is retired after capture; no TCP packet-loss claim is made.
 * There is no accepting reply port, source/grant/native owner substitute or new Start
 * on recovery. Unknown outcomes and all failed inputs remain retained for diagnosis. */
internal object LanGenuinePaymentSupport {
    const val ENABLE_PROPERTY = "ukkera.lan.paymentSupporting"
    const val MAPPING_VERSION = "lan-payment-supporting-v1.0.4"
    val clientActions = setOf(SecurityAction.CASH_READ, SecurityAction.CASH_OPEN,
        SecurityAction.PURCHASING_READ, SecurityAction.INVENTORY_COST_READ, SecurityAction.SUPPLIER_CASH_SETTLE,
        SecurityAction.SUPPLIER_CASH_RESOLVE, SecurityAction.OPERATION_RESULT_RECOVERY)
    val masterActions = setOf(SecurityAction.CASH_CONFIGURE, SecurityAction.SUPPLIER_ADMINISTRATION)
    private val opening = Money(Currency.EGP, 100_000)
    private val payout = Money(Currency.EGP, 22_800)
    private val finalCash = Money(Currency.EGP, 77_200)
    private val roles = listOf("client1", "client2")

    /** Hook AFTER both genuine custodian configurations. Supplier administration must be
     * explicitly enrolled with ADMINISTER; this object cannot add permissions or seed rows. */
    suspend fun prepareMaster(exchange: LanFixtureExchange, endpoint: LocalMasterClient,
        credentials: LocalGatewayCredentials, actors: Map<String, DomainId>) {
        check(java.lang.Boolean.getBoolean(ENABLE_PROPERTY) && actors.keys == roles.toSet())
        val drawer = DomainId.random(); val session = DomainId.random(); val supplier = DomainId.random()
        exchange.putText("payment.drawer", drawer.value)
        exchange.putText("payment.session", session.value)
        exchange.putText("payment.supplier", supplier.value)
        val configured = endpoint.execute(credentials, MutationRequest(OperationId.random(), LocalSalesStoreProfile.CASH_COMMAND,
            LocalCashCommandCodec.encode(LocalCashRequestV1.ConfigureDrawer(drawer, "Synthetic remote supplier cash", Currency.EGP))))
        check(configured is MutationReply.Recorded && configured.committed &&
            LocalSalesResultCodec.decode(configured.result) is LocalSalesResultV1.Applied) { "Real drawer configuration failed" }
        val supplierRequest = LocalConfigurationRequestV1.ConfigureSupplier(null,
            LocalSupplierV1(supplier, LocalNamesV1("Synthetic remote supplier", "مورد شبكة اختبار"), true))
        val created = endpoint.execute(credentials, MutationRequest(OperationId.random(), LocalConfigurationProfile.COMMAND,
            LocalConfigurationCodec.encode(supplierRequest)))
        check(created is MutationReply.Recorded && created.committed) { "Real supplier administration failed" }
        exchange.putText("payment.provisioned", MAPPING_VERSION)
    }

    /** Run after clients.gateway-phase and BEFORE waiting for the ordinary gateway-ready
     * signals. Both Clients execute this new supporting phase before the unchanged live read. */
    suspend fun runMaster(exchange: LanFixtureExchange, root: Path, scope: BranchScope,
        actors: Map<String, DomainId>) {
        check(java.lang.Boolean.getBoolean(ENABLE_PROPERTY))
        roles.forEach { exchange.await("$it.payment.finished") }
        verifyNativeLedger(exchange, root, scope, actors.getValue("client1"))
        exchange.putText("payment.verified", "actual-native-cash77200-one-payout-one-original-per-operation")
    }

    /** The caller supplies the actual published session and actual gateway password sign-in.
     * It must install heldJournals.remotePhysicalBinding(heldRpc, directory) on its real
     * LocalRemoteBusinessClientConnection, never construct a physical owner from DATA. */
    suspend fun runClient(role: String, root: Path, exchange: LanFixtureExchange,
        initial: BranchUiSession, reopen: suspend () -> BranchUiSession): BranchUiSession {
        check(java.lang.Boolean.getBoolean(ENABLE_PROPERTY) && role in roles)
        check(exchange.awaitText("payment.provisioned") == MAPPING_VERSION)
        if (role == "client2") {
            val operation = OperationId(exchange.awaitText("payment.original"))
            check(initial.retailPhysical.recoverSupplierCash(operation) is LocalRetailPhysicalReply.Unavailable) {
                "Foreign supplier original must not be disclosed"
            }
            val attempted = initial.retailPhysical.prepare(LocalRetailPhysicalRequest.SupplierCashResolve(operation,
                LocalRetailObservation.CashPaid(LocalRetailEvidence(DomainId.random(), UtcInstant(Clock.System.now())),
                    literalSupplierAmount = LocalSupplierObservedAmount("EGP", "228"))))
            check(attempted is LocalRetailPhysicalReply.Unavailable) { "Foreign actor cannot acquire the original native resolution custody" }
            exchange.putText("client2.payment.finished", "foreign-original-and-resolution-denied")
            exchange.await("payment.verified")
            return initial
        }
        val evidence = LanPersistentTestFiles(Path.of(checkNotNull(System.getProperty("ukkera.lan.paymentEvidence")))
            .resolve(role).resolve("payment-supporting-inputs"))
        fun retain(label: String, value: String) {
            val bytes = value.encodeToByteArray()
            try { evidence.locked { evidence.create(evidence.name(label), bytes) } } finally { bytes.fill(0) }
        }
        retain("native-root-correlation", root.toString())
        var session = initial
        fun observe(stage: String) {
            println("Payment session observation: stage=$stage; current=${session.isCurrent()}; lifecycle=${session.lifecycle?.value}")
        }
        val drawer = DomainId(exchange.awaitText("payment.drawer"))
        val cash = DomainId(exchange.awaitText("payment.session"))
        val supplier = DomainId(exchange.awaitText("payment.supplier"))
        fun value(field: LocalField, data: LocalScalarValue) = LocalScalarInput(field, data)
        fun id(field: LocalField, data: DomainId) = value(field, LocalScalarValue.Identifier(data))
        val openingOperation = OperationId.random()
        retain("opening.operation", openingOperation.value)
        val intent = LocalCommandIntent(openingOperation, LocalAction.CASH_OPEN, 1, null, listOf(
            id(LocalField.SESSION_ID, cash),
            value(LocalField.DRAWER_ID, LocalScalarValue.Reference(LocalRecordReference(LocalRecordKind.DRAWER, drawer, Revision(0)))),
            value(LocalField.EXPECTED_CUSTODIAN_REVISION, LocalScalarValue.Integer(0)),
            value(LocalField.BUSINESS_DATE, LocalScalarValue.Date("2026-11-15")), id(LocalField.SOURCE_ID, DomainId.random()),
            value(LocalField.OPENING_FLOAT, LocalScalarValue.Decimal("1000")), id(LocalField.EVIDENCE_ID, DomainId.random()),
            value(LocalField.OCCURRED_AT, LocalScalarValue.Text(UtcInstant(Clock.System.now()).canonicalString()))))
        check(session.workflows.stage(intent) is LocalStageResult.Staged)
        val opened = session.workflows.submit(openingOperation)
        check(opened is LocalOperationResult.Recorded && opened.decision == LocalRecordedDecision.APPLIED)
        check(session.workflows.acknowledgeRecorded(openingOperation) is LocalAcknowledgementResult.Acknowledged)
        suspend fun staged(request: LocalRetailPhysicalRequest): OperationId {
            val prepared = session.retailPhysical.prepare(request)
            check(prepared is LocalRetailPhysicalReply.Prepared) { "Payment preparation failed; original retained: $prepared" }
            val staged = session.retailPhysical.stage(prepared.value)
            check(staged is LocalRetailPhysicalReply.Staged) { "Native intent staging failed: $staged" }
            return staged.value.operationId
        }
        val start = staged(LocalRetailPhysicalRequest.SupplierCash(supplier, null, payout, "synthetic.remote.advance"))
        retain("start.operation", start.value)
        val lifetime = LocalRetailPhysicalLifetime()
        val instruction = session.retailPhysical.start(start, lifetime)
        check(instruction is LocalRetailPhysicalReply.Instruction) { "Actual remote native Start unavailable: $instruction" }
        check(instruction.value.display.amount == payout && instruction.value.display.subject == LocalPaymentSubject.SUPPLIER)
        val original = instruction.value.display.recordingOperationId
        retain("original.operation", original.value)
        check(instruction.value.capture(LocalRetailObservation.Unknown(LocalRetailEvidence(DomainId.random(), UtcInstant(Clock.System.now()))))
            is LocalRetailPhysicalCapture.CapturedInMemory)
        // No application or delivered SQL original is observed before the actual UI is retired.
        // The native worker owns the accepted event independently. This is loss of UI delivery,
        // not a fabricated dropped TCP response or a successful force flag.
        session.close(); lifetime.deny()
        session = reopen()
        observe("REAUTHENTICATED")
        check(session.isCurrent() && session.current() is LocalSessionCheckResult.Current)
        val pending = withTimeout(90_000L) {
            var found: LocalRetailPhysicalProgress? = null
            while (found == null) {
                val read = session.retailPhysical.recoverSupplierCash(original)
                if (read is LocalRetailPhysicalReply.Progress && read.value.state == LocalRetailDurableState.SQL_ORIGINAL_PENDING) found = read.value
                else { check(read !is LocalRetailPhysicalReply.Instruction); delay(100) }
            }
            found
        }
        retain("recovered-unknown", pending.toString())
        // Exact retained-Start recovery exercises recovery-only source authorization; it cannot
        // issue a replacement native instruction, even on a genuine current session.
        val recoveredStart = session.retailPhysical.recoverSupplierCash(start)
        check(recoveredStart is LocalRetailPhysicalReply.Progress && recoveredStart.value.state == LocalRetailDurableState.SQL_ORIGINAL_PENDING)
        observe("BEFORE_UNKNOWN_APPLY")
        val apply = staged(LocalRetailPhysicalRequest.SupplierCashApply(original, LocalRetailObservationPhase.ORIGINAL))
        retain("unknown-apply.operation", apply.value)
        val unknown = session.retailPhysical.continueSql(apply)
        check(unknown is LocalRetailPhysicalReply.Progress && unknown.value.state == LocalRetailDurableState.OBSERVATION_APPLIED_UNRESOLVED)
        repeat(3) { index ->
            check((session.retailPhysical.continueSql(apply) as? LocalRetailPhysicalReply.Progress)?.value == unknown.value)
            observe("UNKNOWN_APPLY_REPLAY_$index")
        }
        exchange.putText("payment.original", original.value)
        exchange.await("client2.payment.finished")
        observe("AFTER_FOREIGN_DENIAL")
        val resolution = staged(LocalRetailPhysicalRequest.SupplierCashResolve(original,
            LocalRetailObservation.CashPaid(LocalRetailEvidence(DomainId.random(), UtcInstant(Clock.System.now())),
                literalSupplierAmount = LocalSupplierObservedAmount("EGP", "228"))))
        retain("resolution.operation", resolution.value)
        val linked = session.retailPhysical.continueSql(resolution)
        check(linked is LocalRetailPhysicalReply.Progress && linked.value.state == LocalRetailDurableState.SQL_RESOLUTION_PENDING) {
            "Same actor/current policy/native original custody linked retention failed; do not relax ownership: $linked"
        }
        val recoveredResolution = session.retailPhysical.recoverSupplierCash(resolution)
        check(recoveredResolution is LocalRetailPhysicalReply.Progress &&
            recoveredResolution.value.operationId == resolution &&
            recoveredResolution.value.state == LocalRetailDurableState.SQL_RESOLUTION_PENDING &&
            recoveredResolution.value.originalRecordingOperationId == original &&
            recoveredResolution.value.resolutionOperationId == resolution && recoveredResolution.value.resolutionRecorded &&
            recoveredResolution.value.reviewDisplay?.amount == payout)
        val applyResolution = staged(LocalRetailPhysicalRequest.SupplierCashApply(original, LocalRetailObservationPhase.RESOLUTION))
        retain("resolution-apply.operation", applyResolution.value)
        val paid = session.retailPhysical.continueSql(applyResolution)
        check(paid is LocalRetailPhysicalReply.Progress && paid.value.state == LocalRetailDurableState.FINANCIALLY_APPLIED)
        repeat(3) { check((session.retailPhysical.continueSql(applyResolution) as? LocalRetailPhysicalReply.Progress)?.value == paid.value) }
        val visible = session.workflows.read(LocalWorkflowQuery(LocalWorkflow.CASH))
        check(visible is LocalReadResult.Available)
        val record = visible.page.records.single { it.reference.kind == LocalRecordKind.CASH_SESSION && it.reference.id == cash }
        val expected = record.values.single { it.label.english == "Expected cash" }.value.english
        check(Regex("-?[0-9]+\\.[0-9]{2}").find(expected)?.value == "772.00" && "EGP" in expected)
        exchange.putText("payment.start", start.value)
        exchange.putText("payment.unknown-apply", apply.value)
        exchange.putText("payment.resolution", resolution.value)
        exchange.putText("payment.resolution-apply", applyResolution.value)
        exchange.putText("payment.opening", openingOperation.value)
        exchange.putText("client1.payment.finished", "native-paired-unknown-recovery-and-one-resolved-payout")
        exchange.await("payment.verified")
        return session
    }

    /** Fresh read-only native SQLite connection; no row injection, fake current state or
     * force proof. Literal raw/application linkage and actual cash arithmetic are checked. */
    private suspend fun verifyNativeLedger(exchange: LanFixtureExchange, root: Path, scope: BranchScope, actor: DomainId) {
        val databases = Files.walk(root).use { files -> files.filter { it.fileName.toString() == "master.db" }.toList() }
        check(databases.size == 1)
        val path = databases.single().toRealPath(); check(path.startsWith(root.toRealPath()))
        val original = OperationId(exchange.awaitText("payment.original"))
        val resolution = OperationId(exchange.awaitText("payment.resolution"))
        val apply = OperationId(exchange.awaitText("payment.unknown-apply"))
        val applyResolution = OperationId(exchange.awaitText("payment.resolution-apply"))
        val cash = DomainId(exchange.awaitText("payment.session"))
        BundledSQLiteDriver().open(path.toString(), SQLITE_OPEN_READONLY or SQLITE_OPEN_NOFOLLOW).use { db ->
            fun row(key: AggregateKey): Pair<Revision, StoredBytes> = db.prepare(
                "SELECT revision,payload,payloadHash FROM aggregate_state WHERE scope=? AND kind=? AND id=?").use { cursor ->
                cursor.bindText(1, scope.storageKey()); cursor.bindText(2, key.kind); cursor.bindText(3, key.id.value)
                check(cursor.step()); val revision = Revision(cursor.getLong(0)); val bytes = cursor.getBlob(1)
                check(sha256(bytes).value == cursor.getText(2) && !cursor.step())
                revision to StoredBytes(bytes)
            }
            val raw = LocalSupplierCashFacts.decodeRaw(row(LocalSupplierCashJournal.rawKey(original)).second)
            val linked = LocalSupplierCashFacts.decodeRaw(row(LocalSupplierCashJournal.resolutionKey(resolution)).second)
            check(raw.target == linked.target && raw.originalActorId == actor && raw.recordingActorId == actor &&
                linked.recordingActorId == actor && raw.phase == LocalSupplierCashPhase.ORIGINAL && linked.phase == LocalSupplierCashPhase.RESOLUTION)
            check((LocalSupplierCashCodec.decode(raw.body()) as LocalSupplierCashRequest.RecordOriginal).outcome is LocalSupplierCashOutcome.Unknown)
            val resolved = LocalSupplierCashCodec.decode(linked.body()) as LocalSupplierCashRequest.RecordResolution
            check(resolved.originalBodyDigest == raw.bodyDigest && resolved.resolverActorId == actor &&
                (resolved.outcome as LocalSupplierCashOutcome.CashPaid).supportedAmountOrNull() == payout)
            for ((operation, appliedOperation, action) in listOf(Triple(original, apply, LocalSupplierCashAction.UNKNOWN),
                Triple(resolution, applyResolution, LocalSupplierCashAction.CASH_PAID))) {
                val application = LocalSupplierCashFacts.decodeApplication(row(LocalSupplierCashJournal.applicationKey(operation)).second)
                check(application.applicationOperationId == appliedOperation && application.originalActorId == actor &&
                    (application.result as? LocalSupplierCashResult.Applied)?.action == action)
            }
            val sessionRow = row(AggregateKey(CashNonSaleStoreProfile.SESSION_KIND, cash))
            val session = SalesSnapshotCodec.decodeCashSession(sessionRow.second.copyBytes().decodeToString(), SalesSnapshotBinding(
                SalesSnapshotKind.CASH_SESSION, scope, cash, SalesStorageRevision.toDomain(sessionRow.first)))
            check(session.custodianId == actor && session.opening.amount == opening && session.expectedCash == finalCash &&
                session.totals.payOuts == payout && session.totals.payIns.minor == 0L && session.totals.received.minor == 0L)
            val movement = row(LocalSupplierCashStorage.movementKey(raw.target.attemptId))
            check(movement.first == Revision(1))
            val literal = Json.parseToJsonElement(movement.second.copyBytes().decodeToString()).jsonObject
            check(literal.getValue("operationId").jsonPrimitive.content == applyResolution.value &&
                literal.getValue("originalActorId").jsonPrimitive.content == actor.value &&
                literal.getValue("sessionId").jsonPrimitive.content == cash.value &&
                literal.getValue("delta").jsonObject.getValue("minor").jsonPrimitive.long == -22_800L)
            val operations = listOf("payment.opening", "payment.start", "payment.original", "payment.unknown-apply",
                "payment.resolution", "payment.resolution-apply").map { exchange.awaitText(it) }
            for (operation in operations) for (table in listOf("operation_result", "journal_envelope", "audit_event", "outbox_intent")) {
                db.prepare("SELECT COUNT(*) FROM $table WHERE scope=? AND operationId=?").use { cursor ->
                    cursor.bindText(1, scope.storageKey()); cursor.bindText(2, operation)
                    check(cursor.step() && cursor.getLong(0) == 1L && !cursor.step())
                }
            }
        }
    }
}
