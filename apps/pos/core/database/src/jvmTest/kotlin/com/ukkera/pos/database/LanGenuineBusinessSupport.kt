package com.ukkera.pos.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_NOFOLLOW
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Self-authored, NOT independent. NEW opt-in supporting runner for frozen LAN v1
 * LAN-D03/D04/D06/A07 transaction invariants; NOT the unchanged v1.0.1 auth smoke.
 * Caller supplies each actual independently admitted Client's LAN_BUSINESS RPC directory
 * and current credential. No fake peer/principal, physical-payment fact or authority source.
 *
 * Counted non-sale +11400 on two real drawers is NOT either frozen paid retail sale.
 * Retained files below are executor input/result evidence, NOT production UI journals.
 * Receipt is acknowledged only after the exact result is retained. Unknown/refused responses
 * stop this runner with the original preserved; there is no retry-until-green or new ID.
 * The unchanged endpoint/executor require separate explicit hooks; default execution is OFF.
 */
internal object LanGenuineBusinessSupport {
    const val ENABLE_PROPERTY = "ukkera.lan.businessSupporting"
    const val MAPPING_VERSION = "lan-business-supporting-v1"
    val clientActions: Set<SecurityAction> = setOf(SecurityAction.CASH_READ, SecurityAction.CASH_OPEN,
        SecurityAction.CASH_MOVE, SecurityAction.OPERATION_RESULT_RECOVERY)
    private val roles = listOf("client1", "client2")
    private val observedAt = UtcInstant.parseCanonical("2026-10-23T12:00:00Z")
    private fun requireMapping(exchange: LanFixtureExchange) {
        check(java.lang.Boolean.getBoolean(ENABLE_PROPERTY))
        val bytes = checkNotNull(exchange.read("executor.frozen-input-mapping"))
        val mapping = try { Json.parseToJsonElement(bytes.decodeToString()).jsonObject } finally { bytes.fill(0) }
        check(mapping["id"]?.jsonPrimitive?.content == "POS-LAN-GENUINE-BUSINESS-SUPPORTING-EXECUTOR" &&
            mapping["version"]?.jsonPrimitive?.content == "1.0.0" &&
            mapping["supporting_extension"]?.jsonPrimitive?.content == MAPPING_VERSION &&
            mapping["status"]?.jsonPrimitive?.content == "FROZEN_BEFORE_FIRST_EXECUTION")
    }
    private fun egp(minor: Long) = Money(Currency.EGP, minor)
    private fun cash(operation: OperationId, body: LocalCashRequestV1) =
        MutationRequest(operation, LocalSalesStoreProfile.CASH_COMMAND, LocalCashCommandCodec.encode(body))

    /** Call ONLY in a new explicitly mapped run, after both genuine original enrollments. */
    suspend fun prepareMaster(exchange: LanFixtureExchange, endpoint: LocalMasterClient,
        credentials: LocalGatewayCredentials, actors: Map<String, DomainId>, masterRoot: Path) {
        requireMapping(exchange)
        check(actors.keys == roles.toSet() && actors.values.toSet().size == 2)
        val evidence = Evidence(masterRoot.resolve("business-executor-inputs"))
        for (role in roles) {
            val drawer = DomainId.random()
            val session = DomainId.random()
            // Both exact IDs are published before any Client opening/command staging.
            exchange.putText("$role.business.drawer", drawer.value)
            exchange.putText("$role.business.session", session.value)
            for (body in listOf(LocalCashRequestV1.ConfigureCustodian(actors.getValue(role)),
                LocalCashRequestV1.ConfigureDrawer(drawer, "Synthetic $role counted cash", Currency.EGP))) {
                val request = cash(OperationId.random(), body)
                evidence.request(request)
                val result = requireApplied(endpoint.execute(credentials, request), request.operationId, actors = null)
                evidence.result("${request.operationId.value}.recorded", result)
            }
        }
        exchange.putText("business.provisioned", MAPPING_VERSION)
    }

    /** Coordinates two ACTUAL Client processes; these mailboxes grant no business authority. */
    suspend fun runMaster(exchange: LanFixtureExchange, masterRoot: Path, scope: BranchScope,
        actors: Map<String, DomainId>) {
        requireMapping(exchange)
        roles.forEach { exchange.await("$it.business.opened") }
        exchange.putText("business.cash.start", "both-native-openings-confirmed")
        roles.forEach { exchange.await("$it.business.cash-committed") }
        // Fresh read-only native SQLite connection and domain decoding, not row counts alone.
        verifyMasterLedger(exchange, masterRoot, scope, actors)
        exchange.putText("business.cash.verified", "both-exact-native-drawer-balances-111400")
        roles.forEach { exchange.await("$it.business.finished") }
    }

    /** Invoke in each existing Client process BEFORE its initial RPC tokens are logged out. */
    suspend fun runClient(role: String, root: Path, exchange: LanFixtureExchange,
        directory: LocalMasterClientResultAcceptance, credentials: LocalGatewayCredentials,
        scope: BranchScope, actor: DomainId) {
        requireMapping(exchange)
        check(role in roles)
        check(exchange.awaitText("business.provisioned") == MAPPING_VERSION)
        val drawer = DomainId(exchange.awaitText("$role.business.drawer"))
        val session = DomainId(exchange.awaitText("$role.business.session"))
        val evidence = Evidence(root.resolve(role).resolve("business-executor-inputs"))
        suspend fun dispatch(request: MutationRequest, alreadyRetained: Boolean = false): MutationReply.Recorded {
            if (!alreadyRetained) evidence.request(request)
            val reply = directory.executeAccepting(credentials, request) {
                evidence.result("${request.operationId.value}.accepted", it)
            }
            return requireApplied(reply, request.operationId, actor)
        }
        val opening = cash(OperationId.random(), LocalCashRequestV1.Open(session, drawer, Revision(0), Revision(0),
            BusinessDate(LocalDate(2026, 10, 23)), DomainId.random(), egp(100_000),
            LocalCashEvidenceV1(DomainId.random(), observedAt)))
        dispatch(opening)
        val opened = readSession(directory, credentials, session)
        check(opened.custodianId == actor && opened.drawerId == drawer && opened.revision == Revision(0) &&
            opened.expectedCash == egp(100_000) && opened.opening == egp(100_000) && opened.cashLedgerRevision == Revision(0))
        val body = LocalCashRequestV1.NonSale(session, Revision(0), DomainId.random(), NonSaleCashKind.NON_SALE_PAY_IN,
            egp(11_400), LocalCashEvidenceV1(DomainId.random(), observedAt), "Synthetic supporting counted cash")
        val request = cash(OperationId.random(), body)
        // BOTH immutable business originals are forced/read back before either process may
        // leave the shared start barrier. No elapsed sleep is used to manufacture ordering.
        evidence.request(request)
        exchange.putText("$role.business.opening-operation", opening.operationId.value)
        exchange.putText("$role.business.opened", "actual-rpc-counted-opening-100000-original-pay-in-durably-staged")
        exchange.await("business.cash.start")
        val first = dispatch(request, alreadyRetained = true)
        check(!first.replayed)
        // The exact body is retained before the first dispatch, including the original source.
        val duplicate = requireApplied(directory.executeAccepting(credentials, request) {
            evidence.result("${request.operationId.value}.duplicate", it)
        }, request.operationId, actor)
        sameOriginal(first, duplicate)
        val identity = OperationIdentity(scope.branchId, request.operationId, actor, 1,
            semanticDigest(scope, actor, request.command, LocalCashCommandCodec.normalize(request.payload)).value)
        val lookup = directory.lookupAccepting(credentials, identity) {
            evidence.result("${request.operationId.value}.lookup", it)
        }
        sameOriginal(first, (lookup as? LookupReply.Found)?.result ?: error("Original RPC lookup unavailable"))
        val changed = cash(request.operationId, body.copy(amount = egp(22_800)))
        evidence.request(changed, suffix = ".changed-body")
        check(directory.execute(credentials, changed) is MutationReply.IdentityConflict)
        val stale = cash(OperationId.random(), body.copy(sourceId = DomainId.random()))
        evidence.request(stale)
        val rejection = directory.executeAccepting(credentials, stale) {
            evidence.result("${stale.operationId.value}.stale", it)
        }
        check(rejection is MutationReply.Recorded && !rejection.committed &&
            LocalSalesResultCodec.decode(rejection.result) == LocalSalesResultV1.Rejected(SalesRule.REVISION_CONFLICT))
        val after = readSession(directory, credentials, session)
        check(after.custodianId == actor && after.drawerId == drawer && after.revision == Revision(1) &&
            after.expectedCash == egp(111_400) && after.opening == egp(100_000) && after.payIns == egp(11_400) &&
            after.payOuts == egp(0) && after.received == egp(0) && after.change == egp(0) &&
            after.externalCollections == egp(0) && after.externalRefunds == egp(0) && after.refunds == egp(0) &&
            after.safeDrops == egp(0) && after.reservedCashOut == egp(0) && after.cashLedgerRevision == Revision(1) &&
            after.status == "OPEN" && after.pendingAttemptIds.isEmpty() && after.pendingTransferIds.isEmpty())
        exchange.putText("$role.business.cash-operation", request.operationId.value)
        exchange.putText("$role.business.stale-operation", stale.operationId.value)
        exchange.putText("$role.business.cash-committed", "actual-rpc-cash111400-payins11400-revision1-no-physical-payment")
        exchange.await("business.cash.verified")
        // Re-observe after the other Client's real commit and the Master's independent cut.
        check(readSession(directory, credentials, session) == after)
        exchange.putText("$role.business.finished", "same-original-replay-lookup-conflict-stale-and-exact-native-cash")
    }

    private suspend fun readSession(client: LocalMasterClient, credentials: LocalGatewayCredentials,
        session: DomainId): LocalCashSessionViewV1 {
        val reply = client.read(credentials, LocalCashReadCodec.request(LocalCashQueryV1.Session(session)))
        check(reply is ReadReply.Data) { "Current protected exact cash read unavailable" }
        return (LocalCashReadCodec.decode(reply.payload) as? LocalCashReadResultV1.Session)?.session
            ?: error("Current protected exact cash session not disclosed")
    }
    private fun requireApplied(reply: MutationReply, operation: OperationId, actors: DomainId?): MutationReply.Recorded {
        check(reply is MutationReply.Recorded && reply.committed && reply.operationId == operation &&
            (actors == null || reply.originalActorId == actors)) { "Original cash operation not durably applied; retain original" }
        return reply
    }
    private fun sameOriginal(first: MutationReply.Recorded, next: MutationReply.Recorded) {
        check(next.replayed && first.operationId == next.operationId && first.originalActorId == next.originalActorId &&
            first.committed == next.committed && first.code == next.code && first.result == next.result &&
            first.authorityEpoch == next.authorityEpoch && first.sequence == next.sequence && first.recordedAt == next.recordedAt)
    }

    /** Observes only this new runner's actual Master path. Never modifies a row or mints custody. */
    private suspend fun verifyMasterLedger(exchange: LanFixtureExchange, masterRoot: Path, scope: BranchScope,
        actors: Map<String, DomainId>) {
        val databases = Files.walk(masterRoot).use { files -> files.filter { it.fileName.toString() == "master.db" }.toList() }
        check(databases.size == 1)
        val path = databases.single().toRealPath()
        check(path.startsWith(masterRoot.toRealPath()))
        val bindings = roles.map { role -> listOf(role, exchange.awaitText("$role.business.session"),
            exchange.awaitText("$role.business.drawer"), exchange.awaitText("$role.business.cash-operation"),
            exchange.awaitText("$role.business.opening-operation"), exchange.awaitText("$role.business.stale-operation")) }
        BundledSQLiteDriver().open(path.toString(), SQLITE_OPEN_READONLY or SQLITE_OPEN_NOFOLLOW).use { raw ->
            for (binding in bindings) {
                val role = binding[0]; val sessionId = binding[1]; val drawerId = binding[2]
                val cashOperation = binding[3]; val openingOperation = binding[4]; val staleOperation = binding[5]
                raw.prepare("SELECT revision,payload,payloadHash FROM aggregate_state WHERE scope=? AND kind=? AND id=?").use {
                    it.bindText(1, scope.storageKey()); it.bindText(2, CashNonSaleStoreProfile.SESSION_KIND); it.bindText(3, sessionId)
                    check(it.step()); val revision = it.getLong(0); val bytes = it.getBlob(1)
                    check(sha256(bytes).value == it.getText(2)); check(!it.step())
                    val session = SalesSnapshotCodec.decodeCashSession(bytes.decodeToString(), SalesSnapshotBinding(
                        SalesSnapshotKind.CASH_SESSION, scope, DomainId(sessionId), SalesStorageRevision.toDomain(Revision(revision))))
                    check(session.id.value == sessionId && session.drawerId.value == drawerId && session.custodianId == actors.getValue(role) &&
                        session.revision == Revision(1) && session.expectedCash == egp(111_400) && session.opening.amount == egp(100_000) &&
                        session.totals.payIns == egp(11_400) && session.totals.payOuts == egp(0) && session.totals.received == egp(0) &&
                        session.totals.change == egp(0) && session.totals.externalCollections == egp(0) &&
                        session.totals.externalRefunds == egp(0) && session.totals.refunds == egp(0) && session.totals.safeDrops == egp(0) &&
                        session.cashLedgerRevision == Revision(1) && session.reservedCashOut == egp(0) &&
                        session.status == com.ukkera.pos.sales.CashSessionStatus.OPEN &&
                        session.pendingAttemptIds.isEmpty() && session.pendingTransferIds.isEmpty())
                }
                for (operation in listOf(openingOperation, cashOperation, staleOperation)) {
                    for (table in listOf("operation_result", "journal_envelope", "audit_event", "outbox_intent")) {
                        raw.prepare("SELECT COUNT(*) FROM $table WHERE scope=? AND operationId=?").use {
                            it.bindText(1, scope.storageKey()); it.bindText(2, operation)
                            check(it.step() && it.getLong(0) == 1L && !it.step())
                        }
                    }
                    raw.prepare("SELECT actorId,originalActorId FROM audit_event WHERE scope=? AND operationId=?").use {
                        it.bindText(1, scope.storageKey()); it.bindText(2, operation); check(it.step())
                        check(it.getText(0) == actors.getValue(role).value && it.getText(1) == actors.getValue(role).value && !it.step())
                    }
                }
            }
        }
    }

    /** Immutable, private input/result retention before dispatch/Receipt; contains no bearer. */
    private class Evidence(root: Path) {
        private val files = LanPersistentTestFiles(root)
        fun request(request: MutationRequest, suffix: String = "") = retain(request.operationId.value + suffix + ".request") {
            writeUTF(request.operationId.value); writeUTF(request.command.name); writeInt(request.command.version)
            val payload = request.payload.copyBytes()
            try { writeInt(payload.size); write(payload) } finally { payload.fill(0) }
        }
        fun result(label: String, result: MutationReply.Recorded) = retain(label) {
            writeUTF(result.operationId.value); writeUTF(result.originalActorId.value); writeBoolean(result.committed)
            writeUTF(result.code); writeLong(result.authorityEpoch.value); writeLong(result.sequence.value)
            writeUTF(result.recordedAt.canonicalString()); writeBoolean(result.replayed)
            val bytes = result.result.copyBytes()
            try { writeInt(bytes.size); write(bytes) } finally { bytes.fill(0) }
        }
        private fun retain(label: String, body: DataOutputStream.() -> Unit) {
            val bytes = ByteArrayOutputStream().also { out -> DataOutputStream(out).use { it.body() } }.toByteArray()
            try { files.locked { files.create(files.name(label), bytes) } } finally { bytes.fill(0) }
        }
    }
}
