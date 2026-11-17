package com.ukkera.pos.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_NOFOLLOW
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import dev.p2pkit.rpc.RpcFailure
import dev.p2pkit.rpc.RpcFailureKind
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock

/** Actual retained root supplied by the genuine endpoint, never a fake native/source port.
 * SELF-AUTHORED supporting fault driver: one original production exchange per advance;
 * the ACK for RESOLUTION_RETAINED would be sent only by the next advance, which is withheld. */
internal class LanForeignCatalogueClientRoot(
    private val journals: DesktopPairedDeviceClientCredentials,
    private val source: LocalClientIntentJournalSource,
    private val binding: LocalRemoteRetailPhysicalBinding,
    private val owner: LocalClientIdentity,
) {
    private suspend fun <T> current(action: suspend (LocalGatewayCredentials) -> T): T {
        val snapshot = (journals.credentials.read() as? JournalRead.Value)?.value
            ?: error("Actual protected credential journal unavailable")
        val bearer = try {
            val retained = snapshot.sessions.last { it.identity == owner && !it.originalRevoked && it.phase == StoredSessionPhaseV1.RETAINED }
            retained.access.useBytes(::OpaqueCredential)
        } finally { snapshot.destroy() }
        val credentials = LocalGatewayCredentials(binding.connectionId, bearer)
        try {
            val current = binding.endpoint.current(credentials)
            check(current is LocalSessionViewResult.Current && current.view.scope == owner.scope &&
                current.view.actorId == owner.actorId && current.view.deviceId == owner.deviceId)
            return action(credentials)
        } finally { bearer.destroy() }
    }

    suspend fun retainThenPark(exchange: LanFixtureExchange, resolution: OperationId): Nothing = current { credentials ->
        check(LanForeignCatalogueSupport.interrupted())
        val journal = checkNotNull(source.current(owner))
        val request = journal.requests.withLock {
            val entry = (journal.find(resolution) as? JournalRead.Value)?.value
                ?: error("Genuine fixed resolution was not durably staged")
            check(entry.owner == owner && entry.localAction == com.ukkera.pos.app.LocalAction.SUPPLIER_CASH_RESOLUTION &&
                entry.recorded == null && journal.markDispatched(resolution) == JournalWrite.DURABLE)
            entry.request()
        }
        val actor = checkNotNull(binding.attach(journal))
        check(actor.native?.originalActorId == owner.actorId && actor.native?.binding?.deviceId == owner.deviceId)
        val continuation = checkNotNull(actor.beginResolution(request))
        repeat(32) {
            val phase = continuation.advance(credentials)
            check(phase != null && phase !in setOf(DistributedPhysicalClientPhase.HELD, DistributedPhysicalClientPhase.UNCERTAIN)) {
                "Actual native counterpart phase refused: $phase"
            }
            if (phase == DistributedPhysicalClientPhase.RESOLUTION_RETAINED) {
                check(continuation.result() == null) { "SQL result unexpectedly preceded the withheld native ACK" }
                exchange.putText("client2.native.killable", "actual-native-force-readback-complete-before-next-ACK-exchange")
                // The parent performs a real SIGKILL137. No native-close marker or
                // acknowledged Master result is fabricated for this dead process.
                awaitCancellation()
            }
        }
        error("Actual native retention did not reach its finite protocol phase")
    }

    /** Exact actual retained originals plus wrong-digest control on the genuine TCP peer.
     * Explicit Unauthorized is a denial; deadline/transport errors are never accepted as one. */
    suspend fun verifyOriginals(exchange: LanFixtureExchange) = current { credentials ->
        val journal = checkNotNull(source.current(owner))
        for (label in listOf("payment.resolution", "payment.resolution-apply")) {
            val id = OperationId(exchange.awaitText(label))
            val entry = (journal.find(id) as? JournalRead.Value)?.value ?: error("Own durable original missing")
            val retained = checkNotNull(entry.recorded)
            val found = binding.endpoint.lookup(credentials, entry.identity())
            check(found is LookupReply.Found && StoredKernelResultV1.from(found.result) == retained)
            val wrong = entry.identity().copy(semanticSha256 = sha256("different-fixed-body".encodeToByteArray()).value)
            val denied = try {
                when (val reply = binding.endpoint.lookup(credentials, wrong)) {
                    is LookupReply.IdentityConflict -> true
                    // Sole typed UNAVAILABLE withholds original data and remains
                    // unresolved; it is never known absence or a safe financial rejection.
                    is LookupReply.Deferred -> reply.reason in setOf(DeferredReason.AUTHORIZATION_DENIED, DeferredReason.STORAGE_UNCERTAIN)
                    else -> false
                }
            } catch (failure: RpcFailure) {
                if (failure.kind != RpcFailureKind.Unauthorized) throw failure
                true
            }
            check(denied) { "Wrong digest disclosed an original or was mistaken for absence" }
            // Denial is per request, not permission to retire the shared peer or original.
            val after = binding.endpoint.lookup(credentials, entry.identity())
            check(after is LookupReply.Found && StoredKernelResultV1.from(after.result) == retained)
        }
    }
}

internal object LanForeignCatalogueInterruption {
    /** Real closed Master/A and SIGKILLed B; actual UNKNOWN remains unpaid on Master.
     * There must be no resolution/accounting original before the withheld ACK resumes. */
    suspend fun verifyBeforeResume(exchange: LanFixtureExchange, root: Path) {
        check(LanForeignCatalogueSupport.interrupted())
        check(exchange.awaitText("client2.killed") == "sigkill-exit-137" && exchange.read("client2.closed") == null)
        for (role in listOf("master", "client1")) check(exchange.awaitText("$role.closed") == "confirmed")
        val coordinates = exchange.awaitText("payment.scope.coordinates").lines().map(::DomainId)
        val scope = BranchScope(coordinates[0], coordinates[1], coordinates[2])
        val master = root.resolve("master").toRealPath()
        val paths = Files.walk(master).use { it.filter { f -> f.fileName.toString() == "master.db" }.toList() }
        check(paths.size == 1)
        val original = OperationId(exchange.awaitText("payment.original"))
        val resolution = OperationId(exchange.awaitText("payment.resolution"))
        BundledSQLiteDriver().open(paths.single().toString(), SQLITE_OPEN_READONLY or SQLITE_OPEN_NOFOLLOW).use { db ->
            fun aggregate(key: AggregateKey): StoredBytes? = db.prepare(
                "SELECT payload,payloadHash FROM aggregate_state WHERE scope=? AND kind=? AND id=?").use { cursor ->
                cursor.bindText(1, scope.storageKey()); cursor.bindText(2, key.kind); cursor.bindText(3, key.id.value)
                if (!cursor.step()) null else {
                    val bytes = cursor.getBlob(0); check(sha256(bytes).value == cursor.getText(1) && !cursor.step())
                    StoredBytes(bytes)
                }
            }
            val raw = LocalSupplierCashFacts.decodeRaw(checkNotNull(aggregate(LocalSupplierCashJournal.rawKey(original))))
            check((LocalSupplierCashCodec.decode(raw.body()) as LocalSupplierCashRequest.RecordOriginal).outcome is LocalSupplierCashOutcome.Unknown)
            val applied = LocalSupplierCashFacts.decodeApplication(checkNotNull(aggregate(LocalSupplierCashJournal.applicationKey(original))))
            check((applied.result as? LocalSupplierCashResult.Applied)?.action == LocalSupplierCashAction.UNKNOWN &&
                applied.applicationOperationId?.value == exchange.awaitText("payment.unknown-apply"))
            check(aggregate(LocalSupplierCashJournal.resolutionKey(resolution)) == null &&
                aggregate(LocalSupplierCashJournal.applicationKey(resolution)) == null)
            for (table in listOf("operation_result", "journal_envelope", "audit_event", "outbox_intent")) {
                db.prepare("SELECT COUNT(*) FROM $table WHERE scope=? AND operationId=?").use { cursor ->
                    cursor.bindText(1, scope.storageKey()); cursor.bindText(2, resolution.value)
                    check(cursor.step() && cursor.getLong(0) == 0L && !cursor.step())
                }
            }
            val sessionId = DomainId(exchange.awaitText("payment.session"))
            val cash = checkNotNull(aggregate(AggregateKey(CashNonSaleStoreProfile.SESSION_KIND, sessionId)))
            val storageRevision = db.prepare("SELECT revision FROM aggregate_state WHERE scope=? AND kind=? AND id=?").use { cursor ->
                cursor.bindText(1, scope.storageKey()); cursor.bindText(2, CashNonSaleStoreProfile.SESSION_KIND); cursor.bindText(3, sessionId.value)
                check(cursor.step()); Revision(cursor.getLong(0)).also { check(!cursor.step()) }
            }
            val session = com.ukkera.pos.sales.SalesSnapshotCodec.decodeCashSession(cash.copyBytes().decodeToString(),
                com.ukkera.pos.sales.SalesSnapshotBinding(com.ukkera.pos.sales.SalesSnapshotKind.CASH_SESSION,
                    scope, sessionId, SalesStorageRevision.toDomain(storageRevision)))
            check(session.expectedCash == Money(Currency.EGP, 100_000) && session.totals.payOuts.minor == 0L)
        }
        exchange.putText("foreign.interrupted.verified", "native-B-retained-before-ACK-real-SIGKILL137-Master-original-UNKNOWN-no-resolution-no-payout")
    }
}

/** Final fresh existing-only Master after resumed B resolution/accounting certainly close. */
internal object LanForeignCatalogueFinalCold {
    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        check(LanForeignCatalogueSupport.interrupted() && args.size == 1)
        val root = Path.of(args.single()).toRealPath()
        val phase = LanFixtureExchange(LanForeignCatalogueSupport.coldExchange(root))
        check(phase.read("foreign.cold.verified") != null && phase.read("executor.completed") != null)
        val held = LanFoundingSupervisorEndpoint.openExisting(root)
        try {
            LanFoundingSupervisorEndpoint.owner(held, true) { endpoint, credentials ->
                check(endpoint.current(credentials) is LocalSessionViewResult.Current)
            }
        } finally { check(held.service.quiesceAndClose()) }
        LanForeignCatalogueSupport.verifyAfterClosed(LanFixtureExchange(root.resolve("exchange")), root, "foreign.final-ledger.verified")
        phase.putText("foreign.final-cold.verified", "resumed-resolution-whole-native-OPERATIONAL-current-auth-one-payout-originals-close")
    }
}
