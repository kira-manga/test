package com.ukkera.pos.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_NOFOLLOW
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import com.ukkera.pos.app.*
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.time.Clock

/** SELF-AUTHORED, NOT independent. Separate FRC supporting expectations are frozen
 * before execution. Uses the existing genuine M/C1/C2 setup, native journals and UI;
 * ordinary A-private reads remain denied to B. No A key/catalogue is opened on B. */
internal object LanForeignCatalogueSupport {
    const val ENABLE_PROPERTY = "ukkera.lan.foreignCatalogueSupporting"
    const val MAPPING_VERSION = "lan-foreign-catalogue-supporting-v1.0.3"
    const val KILL_PROPERTY = "ukkera.lan.foreignCatalogueKillSupporting"
    const val KILL_MAPPING_VERSION = "lan-foreign-catalogue-interruption-supporting-v1.0.1"
    const val EXISTING_PROPERTY = "ukkera.lan.foreignCatalogueExistingRoot"
    const val OBSERVATION_PROPERTY = "ukkera.lan.foreignCatalogueObservation"
    fun enabled(): Boolean = java.lang.Boolean.getBoolean(ENABLE_PROPERTY)
    fun interrupted(): Boolean = java.lang.Boolean.getBoolean(KILL_PROPERTY)
    fun mappingVersion(): String = if (interrupted()) KILL_MAPPING_VERSION else MAPPING_VERSION
    fun existingRoot(): Path? = System.getProperty(EXISTING_PROPERTY)?.takeIf { it.isNotBlank() }?.let { Path.of(it).toRealPath() }
    fun coldExchange(root: Path): Path {
        // A diagnostic attempt has a new control exchange; never overwrite a failed
        // observation. This does not select native data, keys, identities or authority.
        val observation = System.getProperty(OBSERVATION_PROPERTY, "")
        require(observation.isEmpty() || observation.matches(Regex("[a-z0-9-]{1,24}")))
        return root.resolve("foreign-exchange-${mappingVersion()}" + if (observation.isEmpty()) "" else "-$observation")
    }

    suspend fun originalClient(exchange: LanFixtureExchange, session: BranchUiSession,
        reopen: suspend () -> BranchUiSession, start: OperationId, original: OperationId,
        unknownApply: OperationId, opening: OperationId): BranchUiSession {
        check(enabled())
        exchange.putText("payment.start", start.value)
        exchange.putText("payment.unknown-apply", unknownApply.value)
        exchange.putText("payment.opening", opening.value)
        // Retire A's actual old UI/native invocation after its UNKNOWN is durably
        // imported/applied. This does not close its cash session or lend its key to B.
        session.close()
        val current = reopen()
        check(current.current() is LocalSessionCheckResult.Current)
        exchange.putText("foreign.source-ready", original.value)
        exchange.await(if (interrupted()) "client2.killed" else "client2.payment.finished")
        val visible = current.workflows.read(LocalWorkflowQuery(LocalWorkflow.CASH))
        check(visible is LocalReadResult.Available)
        val cash = DomainId(exchange.awaitText("payment.session"))
        val record = visible.page.records.single { it.reference.kind == LocalRecordKind.CASH_SESSION && it.reference.id == cash }
        val expected = record.values.single { it.label.english == "Expected cash" }.value.english
        check(Regex("-?[0-9]+\\.[0-9]{2}").find(expected)?.value == (if (interrupted()) "1000.00" else "772.00") && "EGP" in expected)
        exchange.putText("client1.payment.finished", if (interrupted()) "original-UNKNOWN-still-pending-no-payout-after-B-kill"
            else "original-custodian-current-cash77200-after-foreign-resolution")
        exchange.await("payment.operations-complete")
        return current
    }

    suspend fun resolverClient(exchange: LanFixtureExchange, session: BranchUiSession,
        root: LanForeignCatalogueClientRoot?): BranchUiSession {
        check(enabled())
        val original = OperationId(exchange.awaitText("foreign.source-ready"))
        val originalActor = DomainId(exchange.awaitText("client1.actor"))
        val resolver = DomainId(exchange.awaitText("client2.actor"))
        check(originalActor != resolver && session.display.actorId == resolver &&
            exchange.awaitText("client1.imported") != exchange.awaitText("client2.imported"))
        check(session.current() is LocalSessionCheckResult.Current)
        suspend fun stage(request: LocalRetailPhysicalRequest): OperationId {
            val prepared = session.retailPhysical.prepare(request)
            check(prepared is LocalRetailPhysicalReply.Prepared) { "Foreign supervisor preparation: $prepared" }
            val staged = session.retailPhysical.stage(prepared.value)
            check(staged is LocalRetailPhysicalReply.Staged) { "Foreign supervisor stage: $staged" }
            return staged.value.operationId
        }
        fun progress(reply: LocalRetailPhysicalReply, phase: String): LocalRetailPhysicalProgress {
            check(reply is LocalRetailPhysicalReply.Progress) { "Foreign supervisor $phase: $reply" }
            return reply.value
        }
        val reviewId = stage(LocalRetailPhysicalRequest.SupplierSupervisorReview(original))
        val review = progress(session.retailPhysical.continueSql(reviewId), "review")
        check(review.state == LocalRetailDurableState.SUPERVISOR_REVIEW_RECORDED &&
            review.originalActorId == originalActor && review.originalRecordingOperationId == original)
        exchange.putText("foreign.review", reviewId.value)
        val resolution = stage(LocalRetailPhysicalRequest.SupplierSupervisorResolve(reviewId,
            LocalRetailObservation.CashPaid(LocalRetailEvidence(DomainId.random(), UtcInstant(Clock.System.now())),
                literalSupplierAmount = LocalSupplierObservedAmount("EGP", "228"))))
        exchange.putText("payment.resolution", resolution.value)
        if (interrupted()) checkNotNull(root).retainThenPark(exchange, resolution)
        completeResolution(exchange, session, resolution)
        exchange.await("payment.operations-complete")
        return session
    }

    /** Resume the exact durable B intent; no new observation, resolution ID or Start. */
    suspend fun completeResolution(exchange: LanFixtureExchange, session: BranchUiSession, resolution: OperationId) {
        val original = OperationId(exchange.awaitText("payment.original"))
        val originalActor = DomainId(exchange.awaitText("client1.actor"))
        val resolver = DomainId(exchange.awaitText("client2.actor"))
        suspend fun stage(request: LocalRetailPhysicalRequest): OperationId {
            val prepared = session.retailPhysical.prepare(request)
            check(prepared is LocalRetailPhysicalReply.Prepared) { "Foreign supervisor continuation preparation: $prepared" }
            val staged = session.retailPhysical.stage(prepared.value)
            check(staged is LocalRetailPhysicalReply.Staged)
            return staged.value.operationId
        }
        fun progress(reply: LocalRetailPhysicalReply, phase: String): LocalRetailPhysicalProgress {
            check(reply is LocalRetailPhysicalReply.Progress) { "Foreign supervisor $phase: $reply" }
            return reply.value
        }
        val retained = progress(session.retailPhysical.continueSql(resolution), "native-linked-retention")
        check(retained.state == LocalRetailDurableState.SQL_RESOLUTION_PENDING &&
            retained.operationId == resolution && retained.originalRecordingOperationId == original)
        val recovered = progress(session.retailPhysical.recoverSupplierCash(resolution), "original-resolution-recovery")
        check(recovered.operationId == resolution && recovered.state == LocalRetailDurableState.SQL_RESOLUTION_PENDING)
        val freshReview = stage(LocalRetailPhysicalRequest.SupplierSupervisorReview(original))
        val fresh = progress(session.retailPhysical.continueSql(freshReview), "resolution-review")
        check(fresh.resolutionRecorded && fresh.originalActorId == originalActor && fresh.recordingActorId == resolver)
        exchange.putText("foreign.resolution-review", freshReview.value)
        val apply = stage(LocalRetailPhysicalRequest.SupplierSupervisorApply(freshReview, LocalRetailObservationPhase.RESOLUTION))
        exchange.putText("payment.resolution-apply", apply.value)
        val applied = progress(session.retailPhysical.continueSql(apply), "apply")
        check(applied.state == LocalRetailDurableState.FINANCIALLY_APPLIED)
        repeat(3) { check(progress(session.retailPhysical.continueSql(apply), "same-original-replay") == applied) }
        check(progress(session.retailPhysical.recoverSupplierCash(apply), "application-recovery").state ==
            LocalRetailDurableState.FINANCIALLY_APPLIED)
        check(session.current() is LocalSessionCheckResult.Current)
        exchange.putText("client2.payment.finished", "different-native-device-audited-fixed-resolution-one-paid-application")
    }

    /** Independent actual read-only native observation AFTER all owners certainly close.
     * Keeps original financial custody A, resolution/current accounting B and actual SDK
     * devices separate. Same-actor verifier is deliberately not weakened for this case. */
    suspend fun verifyAfterClosed(exchange: LanFixtureExchange, root: Path, verdict: String = "foreign.live.verified") {
        check(enabled())
        for (role in if (interrupted()) listOf("master", "client1") else listOf("master", "client1", "client2"))
            check(exchange.awaitText("$role.closed") == "confirmed")
        if (interrupted()) check(exchange.awaitText("client2.killed") == "sigkill-exit-137" && exchange.read("client2.closed") == null)
        val coordinates = exchange.awaitText("payment.scope.coordinates").lines().map(::DomainId)
        check(coordinates.size == 3)
        val scope = BranchScope(coordinates[0], coordinates[1], coordinates[2])
        val actor = DomainId(exchange.awaitText("client1.actor"))
        val resolver = DomainId(exchange.awaitText("client2.actor"))
        val original = OperationId(exchange.awaitText("payment.original"))
        val resolution = OperationId(exchange.awaitText("payment.resolution"))
        val application = OperationId(exchange.awaitText("payment.resolution-apply"))
        val originalApply = OperationId(exchange.awaitText("payment.unknown-apply"))
        val cash = DomainId(exchange.awaitText("payment.session"))
        val masterRoot = root.resolve("master").toRealPath()
        val databases = Files.walk(masterRoot).use { files -> files.filter { it.fileName.toString() == "master.db" }.toList() }
        check(databases.size == 1 && actor != resolver)
        val path = databases.single().toRealPath(); check(path.startsWith(masterRoot))
        BundledSQLiteDriver().open(path.toString(), SQLITE_OPEN_READONLY or SQLITE_OPEN_NOFOLLOW).use { db ->
            fun row(key: AggregateKey): Pair<Revision, StoredBytes> = db.prepare(
                "SELECT revision,payload,payloadHash FROM aggregate_state WHERE scope=? AND kind=? AND id=?").use { cursor ->
                cursor.bindText(1, scope.storageKey()); cursor.bindText(2, key.kind); cursor.bindText(3, key.id.value)
                check(cursor.step()); val revision = Revision(cursor.getLong(0)); val bytes = cursor.getBlob(1)
                check(sha256(bytes).value == cursor.getText(2) && !cursor.step())
                revision to StoredBytes(bytes)
            }
            val slot = LocalSupplierCashJournal.decodeSlot(row(LocalSupplierCashJournal.slotKey(original)).second)
            check(slot.originalActorId == actor && slot.requirement().clientDeviceId == DomainId(exchange.awaitText("client1.imported")))
            val raw = LocalSupplierCashFacts.decodeRaw(row(LocalSupplierCashJournal.rawKey(original)).second)
            val linked = LocalSupplierCashFacts.decodeRaw(row(LocalSupplierCashJournal.resolutionKey(resolution)).second)
            check(raw.target == linked.target && raw.originalActorId == actor && raw.recordingActorId == actor &&
                linked.originalActorId == actor && linked.recordingActorId == resolver &&
                raw.phase == LocalSupplierCashPhase.ORIGINAL && linked.phase == LocalSupplierCashPhase.RESOLUTION)
            check((LocalSupplierCashCodec.decode(raw.body()) as LocalSupplierCashRequest.RecordOriginal).outcome is LocalSupplierCashOutcome.Unknown)
            val resolved = LocalSupplierCashCodec.decode(linked.body()) as LocalSupplierCashRequest.RecordResolution
            check(resolved.originalBodyDigest == raw.bodyDigest && resolved.resolverActorId == resolver &&
                (resolved.outcome as LocalSupplierCashOutcome.CashPaid).supportedAmountOrNull() == Money(Currency.EGP, 22_800))
            for ((fact, applied, action) in listOf(Triple(original, originalApply, LocalSupplierCashAction.UNKNOWN),
                Triple(resolution, application, LocalSupplierCashAction.CASH_PAID))) {
                val result = LocalSupplierCashFacts.decodeApplication(row(LocalSupplierCashJournal.applicationKey(fact)).second)
                check(result.applicationOperationId == applied && result.originalActorId == actor &&
                    (result.result as? LocalSupplierCashResult.Applied)?.action == action)
            }
            val sessionRow = row(AggregateKey(CashNonSaleStoreProfile.SESSION_KIND, cash))
            val session = SalesSnapshotCodec.decodeCashSession(sessionRow.second.copyBytes().decodeToString(), SalesSnapshotBinding(
                SalesSnapshotKind.CASH_SESSION, scope, cash, SalesStorageRevision.toDomain(sessionRow.first)))
            check(session.custodianId == actor && session.opening.amount == Money(Currency.EGP, 100_000) &&
                session.expectedCash == Money(Currency.EGP, 77_200) && session.totals.payOuts == Money(Currency.EGP, 22_800) &&
                session.totals.payIns.minor == 0L && session.totals.received.minor == 0L)
            val movement = row(LocalSupplierCashStorage.movementKey(raw.target.attemptId))
            val literal = Json.parseToJsonElement(movement.second.copyBytes().decodeToString()).jsonObject
            check(movement.first == Revision(1) && literal.getValue("operationId").jsonPrimitive.content == application.value &&
                literal.getValue("originalActorId").jsonPrimitive.content == actor.value &&
                literal.getValue("sessionId").jsonPrimitive.content == cash.value &&
                literal.getValue("delta").jsonObject.getValue("minor").jsonPrimitive.long == -22_800L)
            for (name in listOf("payment.opening", "payment.start", "payment.original", "payment.unknown-apply",
                "foreign.review", "payment.resolution", "foreign.resolution-review", "payment.resolution-apply")) {
                val operation = exchange.awaitText(name)
                for (table in listOf("operation_result", "journal_envelope", "audit_event", "outbox_intent")) {
                    db.prepare("SELECT COUNT(*) FROM $table WHERE scope=? AND operationId=?").use { cursor ->
                        cursor.bindText(1, scope.storageKey()); cursor.bindText(2, operation)
                        check(cursor.step() && cursor.getLong(0) == 1L && !cursor.step())
                    }
                }
            }
        }
        exchange.putText(verdict, "actual-different-client-native-resolution-A-custody-B-accounting-cash77200-one-payout")
    }
}
