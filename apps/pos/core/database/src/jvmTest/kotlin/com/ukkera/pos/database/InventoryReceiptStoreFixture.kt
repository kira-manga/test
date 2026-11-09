package com.ukkera.pos.database

import androidx.room3.PooledConnection
import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ukkera.pos.inventory.InventoryPlanner
import com.ukkera.pos.inventory.InventoryRejection
import com.ukkera.pos.inventory.InventorySnapshot
import com.ukkera.pos.inventory.InventorySnapshotBinding
import com.ukkera.pos.inventory.InventorySnapshotCodec
import com.ukkera.pos.inventory.InventorySnapshotKind
import com.ukkera.pos.inventory.store.FixtureReceiptOrigin
import com.ukkera.pos.inventory.store.InventoryFixtureReceiveRequest
import com.ukkera.pos.inventory.store.InventoryReceiptCommandCodec
import com.ukkera.pos.inventory.store.InventoryReceiptReadCut
import com.ukkera.pos.inventory.store.InventoryReceiptRepository
import com.ukkera.pos.inventory.store.InventoryReceiptStoreFailure
import com.ukkera.pos.inventory.store.InventoryReceiptStoreProfile
import com.ukkera.pos.inventory.store.InventoryReceiptStoreRejected
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import java.nio.file.Files
import java.nio.file.Path

/**
 * TEST ONLY. Owns an entire finite handler composition and an explicitly synthetic
 * initialization ceremony. No caller can extend its returned registration list.
 * Source identities, upstream purchases, principal, custody and runtime are fixtures.
 * This demonstrates supported-command induction, NOT authenticated import/rollback.
 */
internal class InventoryReceiptFixture(
    path: Path = Files.createTempDirectory("ukkera-inventory-receipt-").resolve("master.db"),
    driver: SQLiteDriver = BundledSQLiteDriver(),
) {
    val base = StoreFixture(path, driver)
    val path: Path get() = base.path
    val database: MasterRoomDatabase get() = base.database
    val receiver = FixtureReceiptHandler()
    val expansionProbe = FixtureDeclarationExpansion()
    private val genesis = FixtureInventoryGenesis()
    private var freshAuthorityInstalled = false

    suspend fun installFreshSyntheticAuthority() {
        check(!Files.exists(path)) { "Only an explicitly fresh disposable file may be provisioned" }
        base.seed()
        freshAuthorityInstalled = true
    }

    fun store(
        initializing: Boolean = false,
        fault: StoreFaultProbe = NoStoreFault,
        includeExpansionProbe: Boolean = false,
    ): AtomicMasterStore = AtomicMasterStore(database, TEST_SCOPE, TEST_AUTHORITY,
        registrations(initializing, includeExpansionProbe), base.auth, base.runtime, MasterClock { base.clockValue }, fault)

    private fun registrations(initializing: Boolean, includeExpansionProbe: Boolean): List<MasterCommandHandler> {
        // A normal reopen cannot infer initializer eligibility from absent projections.
        // This private, process-local ceremony flag is synthetic, NOT protected custody.
        require(!initializing || freshAuthorityInstalled) { "Explicit fresh fixture installation is required" }
        // Concrete implementations are constructed by this fixture, not selected by
        // matching a caller's CommandKey or supplied dynamically by request data.
        val producers = if (initializing) listOf(genesis, receiver) else listOf(receiver)
        val others = listOf(base.handler) + if (includeExpansionProbe) listOf(expansionProbe) else emptyList()
        val inventoryKinds = setOf(InventoryReceiptStoreProfile.CUT_KIND, InventoryReceiptStoreProfile.COMPANION_KIND)
        val inventoryClaims = setOf(InventoryReceiptStoreProfile.SOURCE_NAMESPACE, InventoryReceiptStoreProfile.ORIGIN_NAMESPACE)
        val frozenProducers = producers.map(::FrozenFixtureHandler)
        val frozenOthers = others.map(::FrozenFixtureHandler)
        check(frozenProducers.all { it.writableKinds == inventoryKinds && it.claimNamespaces == inventoryClaims })
        require(frozenOthers.all { it.writableKinds.intersect(inventoryKinds).isEmpty() &&
            it.claimNamespaces.intersect(inventoryClaims).isEmpty() }) { "Fixture inventory ownership overlap" }
        return frozenProducers + frozenOthers
    }

    fun genesisRequest(operationId: OperationId = FIXTURE_INVENTORY_GENESIS): MutationRequest = MutationRequest(
        operationId, InventoryReceiptStoreProfile.GENESIS_COMMAND, InventoryReceiptCommandCodec.genesisRequest())

    fun request(number: Int, value: InventoryFixtureReceiveRequest): MutationRequest = MutationRequest(
        OperationId(testId(number).value), InventoryReceiptStoreProfile.RECEIVE_COMMAND, InventoryReceiptCommandCodec.encode(value))

    suspend fun <T> withView(block: suspend (CommandReadView) -> T): T = database.useWriterConnection { connection ->
        requireDurableWriter(connection)
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val view = TransactionView(TEST_SCOPE, database.facts())
            try { block(view) } finally { view.close() }
        }
    }

    suspend fun read(): InventoryReceiptReadCut = withView { InventoryReceiptRepository.read(it) }

    suspend fun state(): InventoryFixtureState = database.useWriterConnection { connection ->
        requireDurableWriter(connection)
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val rows = listOf(InventoryReceiptStoreProfile.cutKey(TEST_SCOPE.branchId),
                InventoryReceiptStoreProfile.companionKey(TEST_SCOPE.branchId)).map { key ->
                database.facts().aggregate(TEST_SCOPE.storageKey(), key.kind, key.id.value)?.let {
                    InventoryFixtureRow(it.kind, it.id, it.revision, it.schema, it.payloadHash, StoredBytes(it.payload))
                }
            }
            val counts = listOf("operation_result", "journal_envelope", "audit_event", "outbox_intent", "unique_claim", "aggregate_state")
                .map { table -> connection.usePrepared("SELECT COUNT(*) FROM $table") { check(it.step()); it.getLong(0) } }
            val claims = connection.usePrepared("SELECT namespace,`key`,ownerId FROM unique_claim WHERE scope=? ORDER BY namespace,`key`") {
                it.bindText(1, TEST_SCOPE.storageKey())
                buildList { while (it.step()) add(Triple(it.getText(0), it.getText(1), it.getText(2))) }
            }
            InventoryFixtureState(rows, requireNotNull(database.facts().branch(TEST_SCOPE.storageKey())), counts, claims)
        }
    }

    suspend fun totalChanges(): Long = database.useWriterConnection { connection ->
        connection.usePrepared("SELECT total_changes()") { check(it.step()); it.getLong(0) }
    }

    /** Deliberate disposable-file corruption, NOT a supported mutation/import API. */
    suspend fun corruptProjection(block: suspend (PooledConnection) -> Unit) = database.useWriterConnection { connection ->
        requireDurableWriter(connection)
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val ddl = connection.usePrepared("SELECT sql FROM sqlite_master WHERE type='trigger' AND name='aggregate_revision_update'") {
                check(it.step()); it.getText(0)
            }
            connection.usePrepared("DROP TRIGGER aggregate_revision_update") { it.step() }
            try { block(connection) } finally { connection.usePrepared(ddl) { it.step() } }
            check(connection.usePrepared("SELECT sql FROM sqlite_master WHERE type='trigger' AND name='aggregate_revision_update'") {
                check(it.step()); it.getText(0)
            } == ddl)
        }
    }

    /** Restores an old projection while intentionally retaining the newer immutable tail. */
    suspend fun restoreProjectionForLimitationTest(rows: List<InventoryFixtureRow?>) = corruptProjection { connection ->
        for (row in rows) {
            requireNotNull(row)
            connection.usePrepared("UPDATE aggregate_state SET revision=?,schema=?,payloadHash=?,payload=? WHERE scope=? AND kind=? AND id=?") {
                it.bindLong(1, row.revision); it.bindText(2, row.schema); it.bindText(3, row.digest)
                it.bindBlob(4, row.payload.copyBytes()); it.bindText(5, TEST_SCOPE.storageKey())
                it.bindText(6, row.kind); it.bindText(7, row.id); it.step()
            }
        }
    }

    /** Creates an actual missing/orphan retained pair only in this disposable file. */
    suspend fun removeRetainedForCorruptionTest(operation: OperationId, removeResult: Boolean, removeJournal: Boolean) =
        database.useWriterConnection { connection ->
            requireDurableWriter(connection)
            connection.usePrepared("PRAGMA foreign_keys=OFF") { it.step() }
            try {
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    val tables = buildList {
                        if (removeJournal) add("journal_envelope")
                        if (removeResult) add("operation_result")
                    }
                    for (table in tables) {
                        val trigger = "${table}_immutable_delete"
                        val ddl = connection.usePrepared("SELECT sql FROM sqlite_master WHERE type='trigger' AND name=?") {
                            it.bindText(1, trigger); check(it.step()); it.getText(0)
                        }
                        connection.usePrepared("DROP TRIGGER $trigger") { it.step() }
                        try {
                            connection.usePrepared("DELETE FROM $table WHERE scope=? AND operationId=?") {
                                it.bindText(1, TEST_SCOPE.storageKey()); it.bindText(2, operation.value); it.step()
                            }
                        } finally { connection.usePrepared(ddl) { it.step() } }
                    }
                }
            } finally { connection.usePrepared("PRAGMA foreign_keys=ON") { it.step() } }
            requireDurableWriter(connection)
        }

    fun close() = base.close()
}

internal val FIXTURE_INVENTORY_GENESIS = OperationId(testId(20_000).value)

internal fun fixtureReceipt(number: Int, expected: Long, quantity: Long = 1, value: Long = 100): InventoryFixtureReceiveRequest =
    InventoryFixtureReceiveRequest(Revision(expected), testId(number), testId(20_001), testId(20_002), null,
        Quantity(testId(20_003), quantity, 0), Money(Currency.EGP, value), null,
        FixtureReceiptOrigin(testId(20_004), testId(number + 100_000)))

internal data class InventoryFixtureRow(
    val kind: String, val id: String, val revision: Long, val schema: String, val digest: String, val payload: StoredBytes,
)
internal data class InventoryFixtureState(
    val rows: List<InventoryFixtureRow?>, val branch: BranchRow, val counts: List<Long>,
    val claims: List<Triple<String, String, String>>,
)

/** Captures declarations once, and exposes fresh copies instead of mutable backing sets. */
private class FrozenFixtureHandler(private val delegate: MasterCommandHandler) : MasterCommandHandler {
    override val key = delegate.key
    private val kinds = delegate.writableKinds.toSet()
    private val namespaces = delegate.claimNamespaces.toSet()
    override val writableKinds: Set<String> get() = kinds.toSet()
    override val claimNamespaces: Set<String> get() = namespaces.toSet()
    override fun normalize(input: StoredBytes) = delegate.normalize(input)
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext) =
        delegate.prepare(normalized, view, context)
}

internal abstract class FixtureInventoryHandler : MasterCommandHandler {
    override val writableKinds = setOf(InventoryReceiptStoreProfile.CUT_KIND, InventoryReceiptStoreProfile.COMPANION_KIND)
    override val claimNamespaces = setOf(InventoryReceiptStoreProfile.SOURCE_NAMESPACE, InventoryReceiptStoreProfile.ORIGIN_NAMESPACE)
    fun requirement(context: CommandContext, revision: Revision) = AuthorizationRequirement(
        Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM), TEST_SCOPE,
        ActionBinding(TEST_SCOPE.branchId, revision, context.semanticDigest, context.operationId, key.name))
}

private class FixtureInventoryGenesis : FixtureInventoryHandler() {
    override val key = InventoryReceiptStoreProfile.GENESIS_COMMAND
    override fun normalize(input: StoredBytes) = InventoryReceiptCommandCodec.normalizeGenesis(input)
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.operationId == FIXTURE_INVENTORY_GENESIS && context.scope == TEST_SCOPE)
        // Only the explicit initializer may ask this question; read() never does.
        check(view.aggregate(InventoryReceiptStoreProfile.cutKey(TEST_SCOPE.branchId)) == null &&
            view.aggregate(InventoryReceiptStoreProfile.companionKey(TEST_SCOPE.branchId)) == null)
        return object : PreparedMutation() {
            override val requirement = requirement(context, Revision(0))
            override val affectedResources = setOf(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId),
                RecoveryResourceKey(RecoveryResourceKind.STOCK, TEST_SCOPE.branchId))
            override val recoveryHoldBarrierKinds = setOf(RecoveryResourceKind.STOCK)
            override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
                val writes = InventoryReceiptRepository.fixtureGenesis(context)
                return ProposedOutcome.Apply(writes.result, writes.changes, writes.claims)
            }
        }
    }
}

internal enum class FixtureReceiptSourceMode { EXACT, IDENTICAL_DETACHED, ALTERNATE_SAME_REVISION }

internal class FixtureReceiptHandler : FixtureInventoryHandler() {
    override val key = InventoryReceiptStoreProfile.RECEIVE_COMMAND
    var prepares = 0
    var evaluations = 0
    var lastFootprint: Set<RecoveryResourceKey> = emptySet()
        private set
    var sourceMode = FixtureReceiptSourceMode.EXACT
    // Deliberately faulty producer controls, used only to test the loader's shape refusal.
    var unexpectedJob = false
    var unexpectedWrite = false
    var unexpectedClaim = false
    var wrongResult = false
    override fun normalize(input: StoredBytes) = InventoryReceiptCommandCodec.normalizeReceive(input)
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        prepares++
        val request = InventoryReceiptCommandCodec.decode(normalized)
        val current = InventoryReceiptRepository.read(view)
        val footprint = buildSet {
            add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId))
            add(RecoveryResourceKey(RecoveryResourceKind.STOCK, context.scope.branchId))
            val all = current.companion.sources.map { it.position } + request.toReceipt(context.scope).position
            for (position in all) {
                add(RecoveryResourceKey(RecoveryResourceKind.STOCK, position.pool.skuId))
                add(RecoveryResourceKey(RecoveryResourceKind.STOCK, position.pool.warehouseId))
                position.lotId?.let { add(RecoveryResourceKey(RecoveryResourceKind.STOCK, it)) }
            }
            for (source in current.companion.sources) {
                add(RecoveryResourceKey(RecoveryResourceKind.STOCK, source.receiptId))
                add(RecoveryResourceKey(RecoveryResourceKind.STOCK, source.origin.documentId))
                add(RecoveryResourceKey(RecoveryResourceKind.STOCK, source.origin.allocationId))
            }
            add(RecoveryResourceKey(RecoveryResourceKind.STOCK, request.receiptId))
            add(RecoveryResourceKey(RecoveryResourceKind.STOCK, request.origin.documentId))
            add(RecoveryResourceKey(RecoveryResourceKind.STOCK, request.origin.allocationId))
        }
        lastFootprint = footprint.toSet()
        return object : PreparedMutation() {
            override val requirement = requirement(context, request.expectedRevision)
            override val affectedResources = footprint
            override val recoveryHoldBarrierKinds = setOf(RecoveryResourceKind.STOCK)
            override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
                evaluations++
                try {
                    val source = when (sourceMode) {
                        FixtureReceiptSourceMode.EXACT -> current.snapshot
                        FixtureReceiptSourceMode.IDENTICAL_DETACHED -> InventorySnapshotCodec.decode(
                            InventorySnapshotCodec.encode(current.snapshot), InventorySnapshotBinding(
                                InventorySnapshotKind.BRANCH_INVENTORY, current.snapshot.scope, context.scope.branchId, current.snapshot.revision))
                        FixtureReceiptSourceMode.ALTERNATE_SAME_REVISION -> {
                            check(current.snapshot.revision == Revision(1))
                            val alternate = fixtureReceipt(29_999, 0, 17, 1700)
                            InventoryPlanner.receive(InventorySnapshot.empty(current.snapshot.scope), Revision(0),
                                OperationId(testId(29_998).value), listOf(alternate.toReceipt(context.scope))).nextSnapshot
                        }
                    }
                    val plan = InventoryPlanner.receive(source, request.expectedRevision, context.operationId,
                        listOf(request.toReceipt(context.scope)))
                    val writes = InventoryReceiptRepository.translateReceive(current, context, request, plan)
                    val extra = if (unexpectedWrite) listOf(AggregateChange(
                        AggregateKey(InventoryReceiptStoreProfile.CUT_KIND, testId(29_997)), null,
                        InventoryReceiptStoreProfile.CUT_SCHEMA, StoredBytes("unexpected".encodeToByteArray()))) else emptyList()
                    val jobs = if (unexpectedJob) listOf(JobIntent("qualification.unexpected", StoredBytes(byteArrayOf(1)))) else emptyList()
                    val claims = if (unexpectedClaim) listOf(ClaimChange.Acquire(
                        ClaimKey(InventoryReceiptStoreProfile.SOURCE_NAMESPACE, testId(29_994).value),
                        DomainId(context.operationId.value))) else emptyList()
                    val result = if (wrongResult) {
                        val original = writes.result.copyBytes().decodeToString()
                        val different = original.replaceFirst("\"operationId\":\"${context.operationId.value}\"",
                            "\"operationId\":\"${testId(29_995).value}\"")
                        check(different != original)
                        StoredBytes(different.encodeToByteArray())
                    } else writes.result
                    return ProposedOutcome.Apply(result, writes.changes + extra, writes.claims + claims, jobs)
                } catch (failure: InventoryRejection) {
                    val code = "fixture.inventory.${failure.failure.name.lowercase().replace('_', '-')}"
                    return ProposedOutcome.Reject(code, StoredBytes(code.encodeToByteArray()))
                } catch (failure: InventoryReceiptStoreRejected) {
                    val code = when (failure.failure) {
                        InventoryReceiptStoreFailure.TOO_LARGE -> "fixture.inventory.capacity"
                        InventoryReceiptStoreFailure.SOURCE_CONFLICT -> "fixture.inventory.source-conflict"
                        else -> throw failure
                    }
                    return ProposedOutcome.Reject(code, StoredBytes(code.encodeToByteArray()))
                }
            }
        }
    }
}

/** A known negative-control implementation, never part of the ordinary composition. */
internal class FixtureDeclarationExpansion : MasterCommandHandler {
    override val key = CommandKey("qualification.expand-declaration", 1)
    val declaredKinds = mutableSetOf("qualification.expansion")
    val declaredClaims = mutableSetOf<String>()
    var attemptKindExpansion = true
    var attemptClaimExpansion = true
    override val writableKinds: Set<String> get() = declaredKinds
    override val claimNamespaces: Set<String> get() = declaredClaims
    override fun normalize(input: StoredBytes) = input
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation =
        object : PreparedMutation() {
            override val requirement = AuthorizationRequirement(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM),
                TEST_SCOPE, ActionBinding(TEST_SCOPE.branchId, Revision(0), context.semanticDigest, context.operationId, key.name))
            override val affectedResources = setOf(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId),
                RecoveryResourceKey(RecoveryResourceKind.STOCK, TEST_SCOPE.branchId))
            override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome = ProposedOutcome.Apply(
                StoredBytes(byteArrayOf(1)), listOf(AggregateChange(AggregateKey(
                    if (attemptKindExpansion) InventoryReceiptStoreProfile.CUT_KIND else "qualification.expansion", testId(29_996)),
                    null, "qualification/invalid", StoredBytes(byteArrayOf(1)))),
                if (attemptClaimExpansion) listOf(ClaimChange.Acquire(
                    ClaimKey(InventoryReceiptStoreProfile.SOURCE_NAMESPACE, testId(29_996).value), testId(29_996))) else emptyList())
        }
}
