package com.ukkera.pos.database

import androidx.room3.PooledConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ukkera.pos.inventory.InventoryPlanner
import com.ukkera.pos.inventory.InventoryRejection
import com.ukkera.pos.inventory.InventorySnapshot
import com.ukkera.pos.inventory.InventorySnapshotBinding
import com.ukkera.pos.inventory.InventorySnapshotCodec
import com.ukkera.pos.inventory.InventorySnapshotKind
import com.ukkera.pos.inventory.store.InventoryFixtureReceiveRequest
import com.ukkera.pos.inventory.store.InventoryRetailCommandCodec
import com.ukkera.pos.inventory.store.InventoryRetailObservedFacts
import com.ukkera.pos.inventory.store.InventoryRetailReceiveBatch
import com.ukkera.pos.inventory.store.InventoryRetailRepository
import com.ukkera.pos.inventory.store.InventoryRetailStoreFailure
import com.ukkera.pos.inventory.store.InventoryRetailStoreProfile
import com.ukkera.pos.inventory.store.InventoryRetailStoreRejected
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Instant

/**
 * TEST ONLY: real disposable Room storage, synthetic authority/custody/purchases.
 * Reuses legacy fixture's file-inspection/corruption plumbing, NEVER its registrations.
 * Its ordinary accepting path owns exactly the two new retail genesis/receive producers.
 * No checkout/reserve/issue handler, arbitrary registration port or production gateway.
 */
internal class InventoryRetailFixture(
    path: Path = Files.createTempDirectory("ukkera-inventory-retail-").resolve("master.db"),
    driver: SQLiteDriver = BundledSQLiteDriver(),
) {
    private val storage = InventoryReceiptFixture(path, driver)
    val base: StoreFixture get() = storage.base
    val path: Path get() = storage.path
    val database: MasterRoomDatabase get() = storage.database
    val receiver = FixtureRetailReceiveHandler()
    val expansionProbe = FixtureDeclarationExpansion()
    private val genesis = FixtureRetailGenesisHandler()
    private var freshAuthorityInstalled = false

    suspend fun installFreshSyntheticAuthority() {
        storage.installFreshSyntheticAuthority()
        freshAuthorityInstalled = true
    }

    fun store(
        initializing: Boolean = false,
        fault: StoreFaultProbe = NoStoreFault,
        includeExpansionProbe: Boolean = false,
        includeLegacyProducer: Boolean = false,
    ): AtomicMasterStore = AtomicMasterStore(database, TEST_SCOPE, TEST_AUTHORITY,
        registrations(initializing, includeExpansionProbe, includeLegacyProducer), base.auth, base.runtime,
        MasterClock { base.clockValue }, fault)

    private fun registrations(initializing: Boolean, expansion: Boolean, legacy: Boolean): List<MasterCommandHandler> {
        require(!initializing || freshAuthorityInstalled) { "Explicit fresh fixture installation is required" }
        val producers = if (initializing) listOf(genesis, receiver) else listOf(receiver)
        val others = listOf(base.handler) + if (expansion) listOf(expansionProbe) else emptyList()
        val incompatible = if (legacy) listOf(storage.receiver) else emptyList()
        val inventoryKinds = setOf(InventoryRetailStoreProfile.CUT_KIND, InventoryRetailStoreProfile.COMPANION_KIND)
        val inventoryClaims = setOf(InventoryRetailStoreProfile.SOURCE_NAMESPACE, InventoryRetailStoreProfile.ORIGIN_NAMESPACE)
        val frozenProducers = producers.map(::FrozenRetailFixtureHandler)
        val frozenOthers = (others + incompatible).map(::FrozenRetailFixtureHandler)
        val expectedKeys = if (initializing) setOf(InventoryRetailStoreProfile.GENESIS_COMMAND, InventoryRetailStoreProfile.RECEIVE_COMMAND)
            else setOf(InventoryRetailStoreProfile.RECEIVE_COMMAND)
        check(frozenProducers.map { it.key }.toSet() == expectedKeys && frozenProducers.size == expectedKeys.size)
        check(frozenProducers.all { it.writableKinds == inventoryKinds && it.claimNamespaces == inventoryClaims })
        require(frozenOthers.all { it.writableKinds.intersect(inventoryKinds).isEmpty() &&
            it.claimNamespaces.intersect(inventoryClaims).isEmpty() }) { "Fixture inventory ownership overlap" }
        val all = frozenProducers + frozenOthers
        require(all.map { it.key }.distinct().size == all.size)
        return all
    }

    /**
     * Deliberately UNSUPPORTED producer control, not the closed accepting fixture above.
     * Proves a kernel-committed pure inventory contribution is not complete-producer approval.
     * Its concrete command is never registered by store(), nor used to stage valid sales.
     */
    fun unsupportedProducerControlStore(): AtomicMasterStore = AtomicMasterStore(database, TEST_SCOPE, TEST_AUTHORITY,
        listOf(FrozenRetailFixtureHandler(FixtureRetailUnsupportedProducer())), base.auth, base.runtime,
        MasterClock { base.clockValue })

    fun genesisRequest(operation: OperationId = RETAIL_FIXTURE_GENESIS): MutationRequest = MutationRequest(
        operation, InventoryRetailStoreProfile.GENESIS_COMMAND, InventoryRetailCommandCodec.genesisRequest())

    fun request(number: Int, vararg receipts: InventoryFixtureReceiveRequest): MutationRequest = MutationRequest(
        OperationId(testId(number).value), InventoryRetailStoreProfile.RECEIVE_COMMAND,
        InventoryRetailCommandCodec.encodeReceive(InventoryRetailReceiveBatch(receipts.toList())))

    suspend fun <T> withView(block: suspend (CommandReadView) -> T): T = storage.withView(block)
    suspend fun observe(): InventoryRetailObservedFacts = withView { InventoryRetailRepository.observe(it) }
    suspend fun read(): InventoryRetailObservedFacts = withView { InventoryRetailRepository.readFixtureReceipts(it) }
    suspend fun state(): InventoryFixtureState = storage.state()
    suspend fun totalChanges(): Long = storage.totalChanges()
    suspend fun corruptProjection(block: suspend (PooledConnection) -> Unit) = storage.corruptProjection(block)
    suspend fun restoreProjectionForLimitationTest(rows: List<InventoryFixtureRow?>) = storage.restoreProjectionForLimitationTest(rows)
    suspend fun removeRetainedForCorruptionTest(operation: OperationId, removeResult: Boolean, removeJournal: Boolean) =
        storage.removeRetainedForCorruptionTest(operation, removeResult, removeJournal)
    fun close() = storage.close()
}

internal val RETAIL_FIXTURE_GENESIS = OperationId(testId(201_000).value)
internal val RETAIL_UNSUPPORTED_COMMAND = CommandKey("qualification.inventory.retail.unsupported", 1)

private class FrozenRetailFixtureHandler(private val delegate: MasterCommandHandler) : MasterCommandHandler {
    override val key = delegate.key
    private val kinds = delegate.writableKinds.toSet()
    private val namespaces = delegate.claimNamespaces.toSet()
    override val writableKinds: Set<String> get() = kinds.toSet()
    override val claimNamespaces: Set<String> get() = namespaces.toSet()
    override fun normalize(input: StoredBytes) = delegate.normalize(input)
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext) =
        delegate.prepare(normalized, view, context)
}

internal abstract class FixtureRetailHandler : MasterCommandHandler {
    override val writableKinds = setOf(InventoryRetailStoreProfile.CUT_KIND, InventoryRetailStoreProfile.COMPANION_KIND)
    override val claimNamespaces = setOf(InventoryRetailStoreProfile.SOURCE_NAMESPACE, InventoryRetailStoreProfile.ORIGIN_NAMESPACE)
    fun requirement(context: CommandContext, revision: Revision) = AuthorizationRequirement(
        Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM), TEST_SCOPE,
        ActionBinding(TEST_SCOPE.branchId, revision, context.semanticDigest, context.operationId, key.name))
}

private class FixtureRetailGenesisHandler : FixtureRetailHandler() {
    override val key = InventoryRetailStoreProfile.GENESIS_COMMAND
    override fun normalize(input: StoredBytes) = InventoryRetailCommandCodec.normalizeGenesis(input)
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.operationId == RETAIL_FIXTURE_GENESIS && context.scope == TEST_SCOPE)
        check(view.aggregate(InventoryRetailStoreProfile.cutKey(TEST_SCOPE.branchId)) == null &&
            view.aggregate(InventoryRetailStoreProfile.companionKey(TEST_SCOPE.branchId)) == null)
        return object : PreparedMutation() {
            override val requirement = requirement(context, Revision(0))
            override val affectedResources = setOf(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId),
                RecoveryResourceKey(RecoveryResourceKind.STOCK, TEST_SCOPE.branchId))
            override val recoveryHoldBarrierKinds = setOf(RecoveryResourceKind.STOCK)
            override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
                val proposal = InventoryRetailRepository.fixtureGenesis(context)
                val c = proposal.contribution
                check(c.mustCommitBeforeEpochMillis == null)
                return ProposedOutcome.Apply(proposal.result, c.changes, c.claims)
            }
        }
    }
}

internal enum class RetailProducerFault {
    NONE, EXTRA_WRITE, OMIT_CUT, OMIT_COMPANION, WRONG_RESULT, EXTRA_CLAIM,
    OMIT_SOURCE_CLAIM, WRONG_SOURCE_OWNER, RELEASE_OLD_SOURCE, EXTRA_JOB,
}

internal class FixtureRetailReceiveHandler : FixtureRetailHandler() {
    override val key = InventoryRetailStoreProfile.RECEIVE_COMMAND
    var fault = RetailProducerFault.NONE
    var sourceMode = FixtureReceiptSourceMode.EXACT
    var prepares = 0
    var evaluations = 0
    var lastFootprint: Set<RecoveryResourceKey> = emptySet()
        private set
    override fun normalize(input: StoredBytes) = InventoryRetailCommandCodec.normalizeReceive(input)

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        prepares++
        val request = InventoryRetailCommandCodec.decodeReceive(normalized)
        // This is the complete accepting producer check, not merely observe().
        val current = InventoryRetailRepository.readFixtureReceipts(view)
        val footprint = retailFixtureFootprint(context, current, request.receipts)
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
                            val alternate = fixtureReceipt(202_998, 0, 17, 1700)
                            InventoryPlanner.receive(InventorySnapshot.empty(current.snapshot.scope), Revision(0),
                                OperationId(testId(202_997).value), listOf(alternate.toReceipt(context.scope))).nextSnapshot
                        }
                    }
                    val plan = InventoryPlanner.receive(source, request.expectedRevision, context.operationId,
                        request.receipts.map { it.toReceipt(context.scope) })
                    val proposal = InventoryRetailRepository.fixtureReceive(current, context, request, plan, recordedAt.value.toEpochMilliseconds())
                    val c = proposal.contribution
                    var changes = c.changes
                    var claims = c.claims
                    var result = proposal.result
                    var jobs = emptyList<JobIntent>()
                    when (fault) {
                        RetailProducerFault.NONE -> Unit
                        RetailProducerFault.EXTRA_WRITE -> changes = changes + AggregateChange(
                            AggregateKey(InventoryRetailStoreProfile.CUT_KIND, testId(202_990)), null,
                            InventoryRetailStoreProfile.CUT_SCHEMA, StoredBytes("unexpected".encodeToByteArray()))
                        RetailProducerFault.OMIT_CUT -> changes = changes.filterNot { it.key.kind == InventoryRetailStoreProfile.CUT_KIND }
                        RetailProducerFault.OMIT_COMPANION -> changes = changes.filterNot { it.key.kind == InventoryRetailStoreProfile.COMPANION_KIND }
                        RetailProducerFault.WRONG_RESULT -> {
                            val original = result.copyBytes().decodeToString()
                            val changed = original.replaceFirst("\"operationId\":\"${context.operationId.value}\"",
                                "\"operationId\":\"${testId(202_991).value}\"")
                            check(changed != original)
                            result = StoredBytes(changed.encodeToByteArray())
                        }
                        RetailProducerFault.EXTRA_CLAIM -> claims = claims + ClaimChange.Acquire(
                            InventoryRetailStoreProfile.sourceClaim(testId(202_992)), DomainId(context.operationId.value))
                        RetailProducerFault.OMIT_SOURCE_CLAIM -> claims = claims.filterNot {
                            it.key == InventoryRetailStoreProfile.sourceClaim(request.receipts.first().receiptId)
                        }
                        RetailProducerFault.WRONG_SOURCE_OWNER -> claims = claims.map {
                            if (it.key == InventoryRetailStoreProfile.sourceClaim(request.receipts.first().receiptId))
                                ClaimChange.Acquire(it.key, testId(202_993)) else it
                        }
                        RetailProducerFault.RELEASE_OLD_SOURCE -> {
                            val old = current.companion.receipts.first()
                            claims = claims + ClaimChange.Release(InventoryRetailStoreProfile.sourceClaim(old.receiptId),
                                DomainId(old.producingOperationId.value))
                        }
                        RetailProducerFault.EXTRA_JOB -> jobs = listOf(JobIntent("qualification.unexpected", StoredBytes(byteArrayOf(1))))
                    }
                    return ProposedOutcome.Apply(result, changes, claims, jobs,
                        c.mustCommitBeforeEpochMillis?.let { UtcInstant(Instant.fromEpochMilliseconds(it)) })
                } catch (failure: InventoryRejection) {
                    val code = "fixture.inventory.retail.${failure.failure.name.lowercase().replace('_', '-')}"
                    return ProposedOutcome.Reject(code, StoredBytes(code.encodeToByteArray()))
                } catch (failure: InventoryRetailStoreRejected) {
                    val code = when (failure.failure) {
                        InventoryRetailStoreFailure.TOO_LARGE -> "fixture.inventory.retail.capacity"
                        InventoryRetailStoreFailure.SOURCE_CONFLICT -> "fixture.inventory.retail.source-conflict"
                        else -> throw failure
                    }
                    return ProposedOutcome.Reject(code, StoredBytes(code.encodeToByteArray()))
                }
            }
        }
    }
}

private class FixtureRetailUnsupportedProducer : FixtureRetailHandler() {
    override val key = RETAIL_UNSUPPORTED_COMMAND
    override fun normalize(input: StoredBytes) = InventoryRetailCommandCodec.normalizeReceive(input)
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        val request = InventoryRetailCommandCodec.decodeReceive(normalized)
        val current = InventoryRetailRepository.readFixtureReceipts(view)
        return object : PreparedMutation() {
            override val requirement = requirement(context, request.expectedRevision)
            override val affectedResources = retailFixtureFootprint(context, current, request.receipts)
            override val recoveryHoldBarrierKinds = setOf(RecoveryResourceKind.STOCK)
            override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
                val plan = InventoryPlanner.receive(current.snapshot, request.expectedRevision, context.operationId,
                    request.receipts.map { it.toReceipt(context.scope) })
                val c = InventoryRetailRepository.projectContribution(current, context, plan, recordedAt.value.toEpochMilliseconds(), request.receipts)
                return ProposedOutcome.Apply(StoredBytes("unsupported-producer-control".encodeToByteArray()), c.changes, c.claims,
                    mustCommitBefore = c.mustCommitBeforeEpochMillis?.let { UtcInstant(Instant.fromEpochMilliseconds(it)) })
            }
        }
    }
}

private fun retailFixtureFootprint(context: CommandContext, current: InventoryRetailObservedFacts,
    receipts: List<InventoryFixtureReceiveRequest>): Set<RecoveryResourceKey> = buildSet {
    add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId))
    fun stock(id: DomainId) { add(RecoveryResourceKey(RecoveryResourceKind.STOCK, id)) }
    stock(context.scope.branchId)
    for (position in current.snapshot.positions.map { it.key } + receipts.map { it.toReceipt(context.scope).position }) {
        stock(position.pool.skuId); stock(position.pool.warehouseId); position.lotId?.let(::stock)
    }
    current.snapshot.sourceIds.forEach(::stock)
    for (source in current.companion.receipts) {
        stock(source.receiptId); stock(source.origin.documentId); stock(source.origin.allocationId)
    }
    for (source in receipts) {
        stock(source.receiptId); stock(source.origin.documentId); stock(source.origin.allocationId)
    }
    for (reservation in current.snapshot.reservations) {
        stock(reservation.reservationId); stock(reservation.binding.orderId); stock(reservation.binding.collectionId)
        reservation.allocations.forEach { stock(it.allocationId) }
    }
}
