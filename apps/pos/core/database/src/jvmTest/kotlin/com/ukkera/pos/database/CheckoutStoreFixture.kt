package com.ukkera.pos.database

import androidx.room3.PooledConnection
import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ukkera.pos.inventory.CollectionBinding
import com.ukkera.pos.inventory.CollectionExposure
import com.ukkera.pos.inventory.InventoryEffect
import com.ukkera.pos.inventory.InventoryFailure
import com.ukkera.pos.inventory.InventoryPlan
import com.ukkera.pos.inventory.InventoryPlanner
import com.ukkera.pos.inventory.InventoryRejection
import com.ukkera.pos.inventory.ReservationIssueResult
import com.ukkera.pos.inventory.ReservationSafetyResult
import com.ukkera.pos.inventory.ReservationStatus
import com.ukkera.pos.inventory.StockPositionKey
import com.ukkera.pos.inventory.store.InventoryFixtureReceiveRequest
import com.ukkera.pos.inventory.store.InventoryRetailCommandCodec
import com.ukkera.pos.inventory.store.InventoryRetailContribution
import com.ukkera.pos.inventory.store.InventoryRetailObservedFacts
import com.ukkera.pos.inventory.store.InventoryRetailReceiveBatch
import com.ukkera.pos.inventory.store.InventoryRetailRepository
import com.ukkera.pos.inventory.store.InventoryRetailStoreFailure
import com.ukkera.pos.inventory.store.InventoryRetailStoreProfile
import com.ukkera.pos.inventory.store.InventoryRetailStoreRejected
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.AttemptStatus
import com.ukkera.pos.sales.CashAssignmentReadSet
import com.ukkera.pos.sales.CashSessions
import com.ukkera.pos.sales.Collections
import com.ukkera.pos.sales.CountedCash
import com.ukkera.pos.sales.Orders
import com.ukkera.pos.sales.SalesCommand
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.SalesRuleViolation
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.SalesUniqueKind
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizationSnapshot
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val CHECKOUT_DRAWER: DomainId = testId(310_001)
internal val CHECKOUT_INVENTORY_GENESIS: OperationId = OperationId(testId(310_002).value)
internal val CHECKOUT_DEFAULT_ACTIONS: Set<SecurityAction> = setOf(
    SecurityAction.INVENTORY_ADJUST, SecurityAction.CASH_OPEN, SecurityAction.ORDER_CREATE,
    SecurityAction.COLLECTION_BEGIN, SecurityAction.COLLECTION_ATTEST, SecurityAction.COLLECTION_RESOLVE,
    SecurityAction.COLLECTION_FINALIZE, SecurityAction.COLLECTION_FINALIZE_ZERO, SecurityAction.OPERATION_RESULT_RECOVERY,
)

/** Counters record calls/proposals, NOT commits, current authority, or successful physical payment. */
internal class CheckoutCommandProbe {
    var prepares = 0
    var evaluations = 0
    var preflightChecks = 0
    var preflightPasses = 0
    var inventoryPlans = 0
    var domainCalls = 0
    var applies = 0
    var lastRequirement: AuthorizationRequirement? = null
    private var footprint: Set<RecoveryResourceKey> = emptySet()
    val lastFootprint: Set<RecoveryResourceKey> get() = footprint.toSet()
    fun prepared(requirement: AuthorizationRequirement, resources: Set<RecoveryResourceKey>) {
        lastRequirement = requirement
        footprint = resources.toSet()
    }
}

/**
 * TEST ONLY: closed real-Room checkout qualification with synthetic runtime and identity trust.
 * Authority and genuinely EMPTY assignment roots are the only seeded facts. Inventory genesis,
 * receiving, open/create, reservation, attempts, outcomes and finalization are actual commands.
 * No production gateway, accepting imported history, global pending index or frontier is provided.
 */
internal class CheckoutFixture(
    path: Path = Files.createTempDirectory("ukkera-checkout-").resolve("master.db"),
    driver: SQLiteDriver = BundledSQLiteDriver(),
) {
    // Storage/corruption plumbing only: never inherit that fixture's accepting registrations.
    private val storage = InventoryReceiptFixture(path, driver)
    val base: StoreFixture get() = storage.base
    val path: Path get() = storage.path
    val database: MasterRoomDatabase get() = storage.database
    val probes: Map<CommandKey, CheckoutCommandProbe> = (CheckoutFixtureProfile.COMMANDS +
        setOf(InventoryRetailStoreProfile.GENESIS_COMMAND, InventoryRetailStoreProfile.RECEIVE_COMMAND))
        .associateWith { CheckoutCommandProbe() }
    private var freshAuthorityInstalled = false

    /** Explicit disposable-file ceremony; missing rows on a normal reopen never grant eligibility. */
    suspend fun installFreshSyntheticAuthority(
        drawers: List<DomainId> = listOf(CHECKOUT_DRAWER),
        custodians: List<DomainId> = listOf(TEST_ACTOR),
    ) {
        require(drawers.isNotEmpty() && drawers.size <= 16 && drawers.distinct().size == drawers.size)
        require(custodians.isNotEmpty() && custodians.size <= 16 && custodians.distinct().size == custodians.size)
        storage.installFreshSyntheticAuthority()
        database.useWriterConnection { connection ->
            requireDurableWriter(connection)
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                for (id in drawers) seedEmpty(CheckoutFixtureProfile.drawerKey(id), CheckoutFixtureProfile.DRAWER_SCHEMA,
                    CheckoutRows.encodeDrawer(CheckoutDrawerAssignment(TEST_SCOPE, id, Revision(0), null, null)))
                for (id in custodians) {
                    seedEmpty(CheckoutFixtureProfile.custodianKey(id), CheckoutFixtureProfile.CUSTODIAN_SCHEMA,
                        CheckoutRows.encodeCustodian(CheckoutCustodianAssignment(TEST_SCOPE, id, Revision(0), null)))
                    seedEmpty(checkoutPolicyKey(id), CHECKOUT_POLICY_SCHEMA, encodeCheckoutPolicy(CHECKOUT_DEFAULT_ACTIONS, OwnerAccess.OWN_ONLY))
                }
            }
        }
        freshAuthorityInstalled = true
    }

    /** Explicit synthetic policy control. The accepting source rereads this row inside each transaction. */
    suspend fun setGrants(
        actorId: DomainId = TEST_ACTOR,
        actions: Set<SecurityAction> = CHECKOUT_DEFAULT_ACTIONS,
        ownerAccess: OwnerAccess = OwnerAccess.OWN_ONLY,
    ) {
        val bytes = encodeCheckoutPolicy(actions, ownerAccess)
        database.useWriterConnection { connection ->
            requireDurableWriter(connection)
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                checkNotNull(database.facts().branch(TEST_SCOPE.storageKey())) { "checkout.policy.unprovisioned" }
                val key = checkoutPolicyKey(actorId)
                val before = database.facts().aggregate(TEST_SCOPE.storageKey(), key.kind, key.id.value)
                if (before == null) seedEmpty(key, CHECKOUT_POLICY_SCHEMA, bytes)
                else check(database.facts().replaceAggregate(before.scope, before.kind, before.id, before.revision,
                    Revision(before.revision).next().value, CHECKOUT_POLICY_SCHEMA, bytes.copyBytes(), sha256(bytes.copyBytes()).value) == 1)
            }
        }
    }

    private suspend fun seedEmpty(key: AggregateKey, schema: String, bytes: StoredBytes) {
        database.facts().insertAggregate(AggregateRow(TEST_SCOPE.storageKey(), key.kind, key.id.value, 1,
            schema, bytes.copyBytes(), sha256(bytes.copyBytes()).value))
    }

    private val authorization = TransactionAuthorizationSource { principal, view ->
        val state = view.aggregate(AUTH_KEY)?.payload?.copyBytes()?.decodeToString(throwOnInvalidSequence = true)
        val row = view.aggregate(checkoutPolicyKey(principal.subjectId))
        if (principal.kind != PrincipalKind.LOCAL_EMPLOYEE || state == null || row == null) null
        else {
            check(row.schema == CHECKOUT_POLICY_SCHEMA && row.revision.value > 0 && sha256(row.payload.copyBytes()) == row.digest) {
                "checkout.policy.binding"
            }
            val policy = decodeCheckoutPolicy(row.payload)
            AuthorizationSnapshot(principal.subjectId, principal.kind, principal.scope, principal.authenticationId, principal.generation,
                principal.deviceId, principal.admissionRevision, row.revision, state != "disabled", state != "revoked", true,
                AuthenticationPurpose.INTERACTIVE, if (state == "no-grants") emptyList() else policy.actions.map { name ->
                    PermissionGrant(Permission(SecurityAction.valueOf(name), PermissionVerb.PERFORM), TEST_SCOPE, GrantEffect.ALLOW,
                        PermissionLimits(maximumAmount = Money(Currency.EGP, Long.MAX_VALUE)),
                        if (policy.anyOwner) OwnerAccess.ANY_OWNER else OwnerAccess.OWN_ONLY)
                })
        }
    }

    fun store(initializing: Boolean = false, fault: StoreFaultProbe = NoStoreFault): AtomicMasterStore =
        buildStore(registrations(initializing), fault)

    private fun buildStore(registrations: List<MasterCommandHandler>, fault: StoreFaultProbe): AtomicMasterStore =
        AtomicMasterStore(database, TEST_SCOPE, TEST_AUTHORITY, registrations, authorization, base.runtime,
            MasterClock { base.clockValue }, fault)

    private fun registrations(
        initializing: Boolean,
        producerFault: Pair<CommandKey, CheckoutProducerFault>? = null,
        outside: CheckoutDeclarationControl? = null,
    ): List<MasterCommandHandler> {
        require(!initializing || freshAuthorityInstalled) { "Explicit fresh fixture installation is required" }
        val producers = buildList<MasterCommandHandler> {
            if (initializing) add(CheckoutInventoryHandler(InventoryRetailStoreProfile.GENESIS_COMMAND,
                probes.getValue(InventoryRetailStoreProfile.GENESIS_COMMAND)))
            add(CheckoutInventoryHandler(InventoryRetailStoreProfile.RECEIVE_COMMAND, probes.getValue(InventoryRetailStoreProfile.RECEIVE_COMMAND)))
            // Exact concrete family, not caller-supplied implementations selected by matching a key.
            add(CheckoutBusinessHandler(CheckoutFixtureProfile.OPEN, probes.getValue(CheckoutFixtureProfile.OPEN)))
            add(CheckoutBusinessHandler(CheckoutFixtureProfile.CREATE, probes.getValue(CheckoutFixtureProfile.CREATE)))
            add(CheckoutBusinessHandler(CheckoutFixtureProfile.BEGIN, probes.getValue(CheckoutFixtureProfile.BEGIN)))
            add(CheckoutBusinessHandler(CheckoutFixtureProfile.ATTEMPT, probes.getValue(CheckoutFixtureProfile.ATTEMPT)))
            add(CheckoutBusinessHandler(CheckoutFixtureProfile.OUTCOME, probes.getValue(CheckoutFixtureProfile.OUTCOME)))
            add(CheckoutBusinessHandler(CheckoutFixtureProfile.FINALIZE, probes.getValue(CheckoutFixtureProfile.FINALIZE)))
        }
        val expectedKeys = CheckoutFixtureProfile.COMMANDS + InventoryRetailStoreProfile.RECEIVE_COMMAND +
            if (initializing) setOf(InventoryRetailStoreProfile.GENESIS_COMMAND) else emptySet()
        check(producers.map { it.key }.toSet() == expectedKeys && producers.size == expectedKeys.size)
        producerFault?.let { require(it.first in expectedKeys) }
        val frozenProducers = producers.map { producer ->
            val selected = if (producerFault?.first == producer.key) CheckoutFaultyProducer(producer, checkNotNull(producerFault).second) else producer
            FrozenCheckoutHandler(selected).also {
                check(it.writableKinds == checkoutKinds(it.key) && it.claimNamespaces == checkoutNamespaces(it.key))
            }
        }
        val others = (listOf<MasterCommandHandler>(base.handler) + listOfNotNull(outside)).map(::FrozenCheckoutHandler)
        require(others.all { it.writableKinds.intersect(checkoutProtectedKinds).isEmpty() &&
            it.claimNamespaces.intersect(checkoutProtectedNamespaces).isEmpty() }) { "Checkout fixture ownership overlap" }
        val all = frozenProducers + others
        require(all.map { it.key }.distinct().size == all.size)
        return all
    }

    /** Deliberately faulty SUPPORTED producer, never reachable from ordinary store()/valid staging. */
    fun negativeProducerControlStore(key: CommandKey, fault: CheckoutProducerFault): AtomicMasterStore =
        buildStore(registrations(false, key to fault), NoStoreFault)

    /** Known negative declaration control, not an arbitrary accepting registration callback. */
    fun negativeDeclarationControlStore(
        protectedKind: String? = null,
        protectedNamespace: String? = null,
        expandAfterFreeze: Boolean = false,
    ): AtomicMasterStore {
        require((protectedKind == null) != (protectedNamespace == null))
        require(protectedKind == null || protectedKind in checkoutProtectedKinds)
        require(protectedNamespace == null || protectedNamespace in checkoutProtectedNamespaces)
        val control = CheckoutDeclarationControl(protectedKind, protectedNamespace)
        if (!expandAfterFreeze) control.expand()
        val handlers = registrations(false, outside = control)
        if (expandAfterFreeze) control.expand()
        return buildStore(handlers, NoStoreFault)
    }

    fun genesisRequest(operationId: OperationId = CHECKOUT_INVENTORY_GENESIS): MutationRequest = MutationRequest(
        operationId, InventoryRetailStoreProfile.GENESIS_COMMAND, InventoryRetailCommandCodec.genesisRequest())

    fun request(operationId: OperationId, request: CheckoutRequest): MutationRequest =
        MutationRequest(operationId, CheckoutFixtureCodec.keyOf(request), CheckoutFixtureCodec.encode(request))

    fun request(number: Int, request: CheckoutRequest): MutationRequest = this.request(OperationId(testId(number).value), request)

    fun receiveRequest(operationId: OperationId, vararg receipts: InventoryFixtureReceiveRequest): MutationRequest = MutationRequest(
        operationId, InventoryRetailStoreProfile.RECEIVE_COMMAND,
        InventoryRetailCommandCodec.encodeReceive(InventoryRetailReceiveBatch(receipts.toList())))

    fun receiveRequest(number: Int, vararg receipts: InventoryFixtureReceiveRequest): MutationRequest =
        receiveRequest(OperationId(testId(number).value), *receipts)

    suspend fun <T> withView(block: suspend (CommandReadView) -> T): T = storage.withView(block)
    suspend fun state(): InventoryFixtureState = storage.state()
    suspend fun totalChanges(): Long = storage.totalChanges()
    suspend fun corruptProjection(block: suspend (PooledConnection) -> Unit) = storage.corruptProjection(block)
    suspend fun removeRetainedForCorruptionTest(operation: OperationId, removeResult: Boolean, removeJournal: Boolean) =
        storage.removeRetainedForCorruptionTest(operation, removeResult, removeJournal)
    fun close() = storage.close()
}

private const val CHECKOUT_POLICY_KIND = "qualification.checkout.policy"
private const val CHECKOUT_POLICY_SCHEMA = "qualification.checkout.policy/v1"
private fun checkoutPolicyKey(actor: DomainId) = AggregateKey(CHECKOUT_POLICY_KIND, actor)
@Serializable private data class CheckoutSyntheticPolicy(val actions: List<String>, val anyOwner: Boolean)
private fun encodeCheckoutPolicy(actions: Set<SecurityAction>, access: OwnerAccess): StoredBytes {
    require(actions.all { it in CHECKOUT_DEFAULT_ACTIONS }) { "checkout.policy.unsupported" }
    return StoredBytes(Json.encodeToString(CheckoutSyntheticPolicy(actions.map { it.name }.sorted(), access == OwnerAccess.ANY_OWNER))
        .encodeToByteArray(throwOnInvalidSequence = true))
}
private fun decodeCheckoutPolicy(bytes: StoredBytes): CheckoutSyntheticPolicy {
    val text = bytes.copyBytes().decodeToString(throwOnInvalidSequence = true)
    val value = Json.decodeFromString<CheckoutSyntheticPolicy>(text)
    check(value.actions == value.actions.distinct().sorted() && value.actions.all { name -> CHECKOUT_DEFAULT_ACTIONS.any { it.name == name } } &&
        Json.encodeToString(value) == text) { "checkout.policy.canonical" }
    return value
}

private class FrozenCheckoutHandler(private val delegate: MasterCommandHandler) : MasterCommandHandler {
    override val key = delegate.key
    private val kinds = delegate.writableKinds.toSet()
    private val namespaces = delegate.claimNamespaces.toSet()
    override val writableKinds: Set<String> get() = kinds.toSet()
    override val claimNamespaces: Set<String> get() = namespaces.toSet()
    override fun normalize(input: StoredBytes): StoredBytes = delegate.normalize(input)
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation =
        delegate.prepare(normalized, view, context)
}

private fun checkoutKinds(key: CommandKey): Set<String> {
    val inventory = setOf(InventoryRetailStoreProfile.CUT_KIND, InventoryRetailStoreProfile.COMPANION_KIND)
    return when (key) {
        InventoryRetailStoreProfile.GENESIS_COMMAND, InventoryRetailStoreProfile.RECEIVE_COMMAND -> inventory
        CheckoutFixtureProfile.OPEN -> setOf(CheckoutFixtureProfile.SESSION_KIND, CheckoutFixtureProfile.DRAWER_KIND, CheckoutFixtureProfile.CUSTODIAN_KIND)
        CheckoutFixtureProfile.CREATE -> setOf(CheckoutFixtureProfile.ORDER_KIND)
        CheckoutFixtureProfile.BEGIN -> inventory + setOf(CheckoutFixtureProfile.ORDER_KIND, CheckoutFixtureProfile.COLLECTION_KIND)
        CheckoutFixtureProfile.ATTEMPT, CheckoutFixtureProfile.OUTCOME -> inventory + setOf(CheckoutFixtureProfile.COLLECTION_KIND, CheckoutFixtureProfile.SESSION_KIND)
        CheckoutFixtureProfile.FINALIZE -> inventory + setOf(CheckoutFixtureProfile.ORDER_KIND, CheckoutFixtureProfile.COLLECTION_KIND,
            CheckoutFixtureProfile.SALE_KIND, CheckoutFixtureProfile.ENTITLEMENT_KIND)
        else -> error("checkout.registration.unsupported")
    }
}

private fun checkoutNamespaces(key: CommandKey): Set<String> {
    fun names(vararg kinds: SalesUniqueKind) = kinds.map { CheckoutFixtureProfile.claim(it, TEST_ACTOR).namespace }.toSet()
    return when (key) {
        InventoryRetailStoreProfile.GENESIS_COMMAND -> emptySet()
        InventoryRetailStoreProfile.RECEIVE_COMMAND -> setOf(InventoryRetailStoreProfile.SOURCE_NAMESPACE, InventoryRetailStoreProfile.ORIGIN_NAMESPACE)
        CheckoutFixtureProfile.OPEN -> names(SalesUniqueKind.CASH_SESSION, SalesUniqueKind.OPENING_FLOAT_SOURCE,
            SalesUniqueKind.ACTIVE_DRAWER, SalesUniqueKind.ACTIVE_CUSTODIAN)
        CheckoutFixtureProfile.CREATE -> names(SalesUniqueKind.ORDER)
        CheckoutFixtureProfile.BEGIN -> names(SalesUniqueKind.COLLECTION)
        CheckoutFixtureProfile.ATTEMPT -> names(SalesUniqueKind.PAYMENT_ATTEMPT)
        CheckoutFixtureProfile.OUTCOME -> names(SalesUniqueKind.PAYMENT_RECORD, SalesUniqueKind.CASH_SOURCE)
        CheckoutFixtureProfile.FINALIZE -> names(SalesUniqueKind.SALE, SalesUniqueKind.SALE_ORDER, SalesUniqueKind.RETURN_ENTITLEMENT) +
            InventoryRetailStoreProfile.SOURCE_NAMESPACE
        else -> error("checkout.registration.unsupported")
    }
}

private val checkoutProtectedKinds: Set<String> get() = (CheckoutFixtureProfile.COMMANDS + InventoryRetailStoreProfile.RECEIVE_COMMAND)
    .flatMap(::checkoutKinds).toSet() + setOf(CHECKOUT_POLICY_KIND, AUTH_KEY.kind)
private val checkoutProtectedNamespaces: Set<String> get() = (CheckoutFixtureProfile.COMMANDS + InventoryRetailStoreProfile.RECEIVE_COMMAND)
    .flatMap(::checkoutNamespaces).toSet()

private suspend fun observeCheckoutInventory(view: CommandReadView): InventoryRetailObservedFacts =
    InventoryRetailRepository.observe(view).also(::requireCheckoutInventoryProducer)

private class CheckoutInventoryHandler(override val key: CommandKey, private val probe: CheckoutCommandProbe) : MasterCommandHandler {
    override val writableKinds: Set<String> get() = checkoutKinds(key)
    override val claimNamespaces: Set<String> get() = checkoutNamespaces(key)
    override fun normalize(input: StoredBytes): StoredBytes = when (key) {
        InventoryRetailStoreProfile.GENESIS_COMMAND -> InventoryRetailCommandCodec.normalizeGenesis(input)
        InventoryRetailStoreProfile.RECEIVE_COMMAND -> InventoryRetailCommandCodec.normalizeReceive(input)
        else -> error("checkout.inventory.command")
    }

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        probe.prepares++
        check(context.scope == view.scope && context.key == key && semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val genesis = key == InventoryRetailStoreProfile.GENESIS_COMMAND
        val current = if (genesis) null else observeCheckoutInventory(view)
        val request = if (genesis) null else InventoryRetailCommandCodec.decodeReceive(normalized)
        if (genesis) check(context.operationId == CHECKOUT_INVENTORY_GENESIS &&
            view.aggregate(InventoryRetailStoreProfile.cutKey(view.scope.branchId)) == null &&
            view.aggregate(InventoryRetailStoreProfile.companionKey(view.scope.branchId)) == null) { "checkout.genesis.fresh" }
        val requirement = AuthorizationRequirement(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM), context.scope,
            ActionBinding(context.scope.branchId, request?.expectedRevision ?: Revision(0), context.semanticDigest, context.operationId, key.name))
        val footprint = checkoutInventoryFootprint(context, current, request?.receipts.orEmpty())
        return checkoutPrepared(context, normalized, requirement, footprint, true, probe) { authorization, recordedAt ->
            probe.preflightPasses++
            val proposal = if (genesis) InventoryRetailRepository.fixtureGenesis(context) else {
                val observed = checkNotNull(current)
                val received = checkNotNull(request)
                probe.inventoryPlans++
                val plan = InventoryPlanner.receive(observed.snapshot, received.expectedRevision, context.operationId,
                    received.receipts.map { it.toReceipt(context.scope) })
                InventoryRetailRepository.fixtureReceive(observed, context, received, plan, recordedAt.value.toEpochMilliseconds())
            }
            val c = proposal.contribution
            check(c.mustCommitBeforeEpochMillis == null) { "checkout.receive.deadline" }
            checkoutApply(context, normalized, authorization, recordedAt, proposal.result, c.changes, c.claims)
        }
    }
}

private class CheckoutBusinessHandler(override val key: CommandKey, private val probe: CheckoutCommandProbe) : MasterCommandHandler {
    override val writableKinds: Set<String> get() = checkoutKinds(key)
    override val claimNamespaces: Set<String> get() = checkoutNamespaces(key)
    override fun normalize(input: StoredBytes): StoredBytes = CheckoutFixtureCodec.normalize(key, input)

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        probe.prepares++
        check(context.scope == view.scope && context.key == key && semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        return when (val request = CheckoutFixtureCodec.decode(key, normalized)) {
            is CheckoutRequest.Open -> open(request, normalized, view, context)
            is CheckoutRequest.Create -> create(request, normalized, view, context)
            is CheckoutRequest.Begin -> begin(request, normalized, view, context)
            is CheckoutRequest.Attempt -> attempt(request, normalized, view, context)
            is CheckoutRequest.Outcome -> outcome(request, normalized, view, context)
            is CheckoutRequest.Finalize -> finalize(request, normalized, view, context)
        }
    }

    private suspend fun open(request: CheckoutRequest.Open, normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        val drawerRow = checkoutCurrentRow(view, CheckoutFixtureProfile.drawerKey(request.drawerId))
        val drawer = CheckoutRows.drawer(drawerRow, context.scope, request.drawerId)
        val custodianRow = checkoutCurrentRow(view, CheckoutFixtureProfile.custodianKey(context.originalActorId))
        val custodian = CheckoutRows.custodian(custodianRow, context.scope, context.originalActorId)
        val claims = listOf(SalesUniqueKind.CASH_SESSION to request.sessionId, SalesUniqueKind.OPENING_FLOAT_SOURCE to request.openingSourceId,
            SalesUniqueKind.ACTIVE_DRAWER to drawer.drawerId, SalesUniqueKind.ACTIVE_CUSTODIAN to custodian.custodianId)
        val unused = view.aggregate(CheckoutFixtureProfile.salesKey(SalesSnapshotKind.CASH_SESSION, request.sessionId)) == null &&
            checkoutClaimsAbsent(view, claims)
        val requirement = salesAuthorizationRequirement(SecurityAction.CASH_OPEN, context.scope, drawer.drawerId, request.expectedDrawerRevision,
            context.operationId, context.semanticDigest, key.name, custodian.custodianId, request.opening)
        val footprint = checkoutBaseFootprint(context) + listOf(drawer.drawerId, custodian.custodianId, request.sessionId, request.openingSourceId)
            .map { RecoveryResourceKey(RecoveryResourceKind.CASH_CUSTODY, it) }
        return checkoutPrepared(context, normalized, requirement, footprint, false, probe) { authorization, recordedAt ->
            checkoutRequire(authorization.actorId == custodian.custodianId, SalesRule.OWNER_REQUIRED)
            checkoutRequire(drawer.revision == request.expectedDrawerRevision && custodian.revision == request.expectedCustodianRevision,
                SalesRule.REVISION_CONFLICT)
            checkoutRequire(drawer.activeSessionId == null, SalesRule.DRAWER_ASSIGNED)
            checkoutRequire(custodian.activeSessionId == null, SalesRule.CUSTODIAN_ASSIGNED)
            checkoutRequire(drawer.unresolvedClosingTransferId == null, SalesRule.CUSTODY_UNRESOLVED)
            checkoutRequire(unused, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
            probe.preflightPasses++
            val command = checkoutSalesCommand(context, authorization, recordedAt)
            probe.domainCalls++
            val transition = CashSessions.open(command, CashAssignmentReadSet(context.scope, drawer.drawerId, drawer.revision,
                drawer.activeSessionId, drawer.unresolvedClosingTransferId, custodian.custodianId, custodian.revision, custodian.activeSessionId),
                request.expectedDrawerRevision, request.expectedCustodianRevision, request.sessionId, request.businessDate.value,
                CountedCash(request.opening, request.evidence.toDomain(authorization.actorId)), request.openingSourceId)
            val translated = translateCheckoutOpen(command, request, drawerRow, drawer, custodianRow, custodian, transition)
            val result = CheckoutResult.Opened(CheckoutCashRef(CheckoutRef(transition.value.id, transition.value.revision),
                drawer.revision.next(), custodian.revision.next()))
            checkoutApply(context, normalized, authorization, recordedAt, CheckoutFixtureCodec.encodeResult(result), translated.changes, translated.claims)
        }
    }

    private suspend fun create(request: CheckoutRequest.Create, normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        val unused = view.aggregate(CheckoutFixtureProfile.salesKey(SalesSnapshotKind.ORDER, request.orderId)) == null &&
            checkoutClaimsAbsent(view, listOf(SalesUniqueKind.ORDER to request.orderId))
        val requirement = salesAuthorizationRequirement(SecurityAction.ORDER_CREATE, context.scope, request.orderId, Revision(0),
            context.operationId, context.semanticDigest, key.name, context.originalActorId)
        val footprint = checkoutBaseFootprint(context) + (listOf(request.orderId, request.quoteId) + request.lines.map { it.portionId })
            .map { RecoveryResourceKey(RecoveryResourceKind.ORDER_CUSTODY, it) }
        return checkoutPrepared(context, normalized, requirement, footprint, false, probe) { authorization, recordedAt ->
            checkoutRequire(unused, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
            probe.preflightPasses++
            val command = checkoutSalesCommand(context, authorization, recordedAt)
            val (quote, stock) = checkoutBasket(context.scope, request)
            probe.domainCalls++
            val transition = Orders.create(command, context.scope, request.orderId, quote, stock)
            val translated = translateCheckoutCreate(command, request, quote, stock, transition)
            checkoutApply(context, normalized, authorization, recordedAt,
                CheckoutFixtureCodec.encodeResult(CheckoutResult.Created(CheckoutRef(transition.value.id, transition.value.revision))),
                translated.changes, translated.claims)
        }
    }

    private suspend fun begin(request: CheckoutRequest.Begin, normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        val order = readCheckoutOrder(view, request.order.id)
        val cash = readCheckoutCash(view, request.cash.session.id)
        val inventory = observeCheckoutInventory(view)
        val unused = view.aggregate(CheckoutFixtureProfile.salesKey(SalesSnapshotKind.COLLECTION, request.collectionId)) == null &&
            checkoutClaimsAbsent(view, listOf(SalesUniqueKind.COLLECTION to request.collectionId))
        val requirement = salesAuthorizationRequirement(SecurityAction.COLLECTION_BEGIN, context.scope, order.order.id, request.order.revision,
            context.operationId, context.semanticDigest, key.name, order.order.ownerId, order.order.quote.payable)
        val footprint = checkoutCashFootprint(context, cash) + checkoutInventoryFootprint(context, inventory) +
            checkoutOrderResources(order.order.id, request.collectionId) + checkoutStockResources(request.reservationId)
        return checkoutPrepared(context, normalized, requirement, footprint, true, probe) { authorization, recordedAt ->
            checkoutRequire(order.order.ownerId == authorization.actorId, SalesRule.OWNER_REQUIRED)
            checkoutRequire(order.order.revision == request.order.revision && inventory.snapshot.revision == request.expectedInventoryRevision,
                SalesRule.REVISION_CONFLICT)
            requireCheckoutCashCurrent(cash, request.cash, authorization.actorId)
            checkoutRequire(order.order.collectionId == null && order.order.paidSaleId == null, SalesRule.PAYABLE_LOCKED)
            checkoutRequire(order.order.handoff == null, SalesRule.HANDOFF_CONFLICT)
            checkoutRequire(unused, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
            probe.preflightPasses++
            val command = checkoutSalesCommand(context, authorization, recordedAt)
            probe.inventoryPlans++
            val reservation = InventoryPlanner.reserve(inventory.snapshot, request.expectedInventoryRevision, context.operationId, request.reservationId,
                CollectionBinding(order.order.id, order.order.quote.revision, request.collectionId, authorization.actorId, CollectionExposure.UNKNOWN),
                order.order.stockRequirements.allocations, recordedAt.value.toEpochMilliseconds(), request.reservationExpiresAtEpochMillis)
            probe.domainCalls++
            val transition = Collections.begin(command, order.order, request.order.revision, cash.session, request.cash.session.revision,
                request.collectionId, reservation)
            val translated = translateCheckoutBegin(command, request, order, cash, reservation, transition)
            val contribution = checkoutContribution(inventory, context, reservation.plan, recordedAt)
            check(contribution.claims.isEmpty()) { "checkout.begin.source-free" }
            val result = CheckoutResult.Begun(CheckoutSalesRef(CheckoutRef(transition.value.order.id, transition.value.order.revision),
                CheckoutRef(transition.value.collection.id, transition.value.collection.revision), cash.reference()),
                checkoutStockReference(reservation.plan, request.reservationId))
            checkoutApply(context, normalized, authorization, recordedAt, CheckoutFixtureCodec.encodeResult(result),
                contribution.changes + translated.changes, contribution.claims + translated.claims, checkoutDeadline(contribution))
        }
    }

    private suspend fun attempt(request: CheckoutRequest.Attempt, normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        val current = readCheckoutSales(view, request.sales)
        val inventory = observeCheckoutInventory(view)
        val unused = checkoutClaimsAbsent(view, listOf(SalesUniqueKind.PAYMENT_ATTEMPT to request.attemptId,
            SalesUniqueKind.PAYMENT_RECORD to request.attemptId, SalesUniqueKind.CASH_SOURCE to request.attemptId))
        val requirement = salesAuthorizationRequirement(SecurityAction.COLLECTION_ATTEST, context.scope, current.collection.id,
            request.sales.collection.revision, context.operationId, context.semanticDigest, key.name, current.collection.collectorId, request.amount)
        val footprint = checkoutSalesFootprint(context, current) + checkoutInventoryFootprint(context, inventory) +
            setOf(RecoveryResourceKey(RecoveryResourceKind.CASH_CUSTODY, request.attemptId))
        return checkoutPrepared(context, normalized, requirement, footprint, true, probe) { authorization, recordedAt ->
            requireCheckoutSalesCurrent(current, request.sales, authorization.actorId)
            checkoutRequire(!current.collection.unresolved, SalesRule.PAYMENT_UNRESOLVED)
            checkoutRequire(request.amount.currency == current.collection.quote.payable.currency && request.amount.minor > 0 &&
                request.amount.minor <= current.collection.remainingPayable.minor, SalesRule.INVALID_AMOUNT)
            checkoutRequire(unused && current.collection.attempts.none { it.id == request.attemptId }, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
            val reservation = requireCheckoutReservationCurrent(inventory, request.stock, current)
            checkoutRequire(reservation.binding.exposure == CollectionExposure.UNKNOWN || reservation.binding.exposure == CollectionExposure.PART_PAID,
                SalesRule.STOCK_PROOF_MISMATCH)
            probe.preflightPasses++
            probe.inventoryPlans++
            val safety = InventoryPlanner.revalidateReservation(inventory.snapshot, request.stock.inventoryRevision, context.operationId,
                request.stock.reservation.id, request.stock.reservation.revision, recordedAt.value.toEpochMilliseconds())
            // No payment intent/claim survives an unsafe revalidation; no physical admission is returned.
            if (safety !is ReservationSafetyResult.Safe) return@checkoutPrepared checkoutReject(CheckoutRejectionCodes.inventory(InventoryFailure.UNSAFE_STOCK))
            val command = checkoutSalesCommand(context, authorization, recordedAt)
            probe.domainCalls++
            val transition = Collections.startAttempt(command, current.collection, request.sales.collection.revision, current.cash.session,
                request.sales.cash.session.revision, request.attemptId, request.tender.toDomain(), request.amount, safety, request.payerReference)
            val translated = translateCheckoutAttempt(command, request, current, safety, transition)
            val contribution = checkoutContribution(inventory, context, safety.plan, recordedAt)
            check(contribution.claims.isEmpty()) { "checkout.attempt.source-free" }
            val result = CheckoutResult.Attempted(current.reference(afterCollection = transition.value.collection, afterCash = transition.value.session),
                checkoutStockReference(safety.plan, current.collection.reservationId), request.attemptId)
            checkoutApply(context, normalized, authorization, recordedAt, CheckoutFixtureCodec.encodeResult(result),
                contribution.changes + translated.changes, contribution.claims + translated.claims, checkoutDeadline(contribution))
        }
    }

    private suspend fun outcome(request: CheckoutRequest.Outcome, normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        val current = readCheckoutSales(view, request.sales)
        val attempt = current.collection.attempts.singleOrNull { it.id == request.attemptId }
        val derive = request.exposure as? CheckoutExposureUpdate.Derive
        // PRESERVE neither reads stock nor obtains a STOCK recovery barrier.
        val inventory = if (derive == null) null else observeCheckoutInventory(view)
        val action = if (attempt?.status == AttemptStatus.OUTCOME_UNKNOWN) SecurityAction.COLLECTION_RESOLVE else SecurityAction.COLLECTION_ATTEST
        val requirement = salesAuthorizationRequirement(action, context.scope, current.collection.id, request.sales.collection.revision,
            context.operationId, context.semanticDigest, key.name, current.collection.collectorId, attempt?.amount ?: Money(Currency.EGP, 0))
        val footprint = checkoutSalesFootprint(context, current) + if (inventory == null) emptySet() else checkoutInventoryFootprint(context, inventory)
        val sourceUnused = checkoutClaimsAbsent(view, listOf(SalesUniqueKind.PAYMENT_RECORD to request.attemptId, SalesUniqueKind.CASH_SOURCE to request.attemptId))
        return checkoutPrepared(context, normalized, requirement, footprint, inventory != null, probe) { authorization, recordedAt ->
            requireCheckoutSalesCurrent(current, request.sales, authorization.actorId)
            val original = attempt ?: throw SalesRuleViolation(SalesRule.INVALID_STATE)
            checkoutRequire(original.status == AttemptStatus.AWAITING_ATTESTATION || original.status == AttemptStatus.OUTCOME_UNKNOWN,
                SalesRule.IMMUTABLE_RECORD)
            checkoutRequire(sourceUnused, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
            if (derive != null) requireCheckoutReservationCurrent(checkNotNull(inventory), derive.stock, current)
            probe.preflightPasses++
            val command = checkoutSalesCommand(context, authorization, recordedAt)
            val observation = request.outcome.toDomain(authorization.actorId)
            probe.domainCalls++
            val transition = Collections.recordOutcome(command, current.collection, request.sales.collection.revision, current.cash.session,
                request.sales.cash.session.revision, request.attemptId, observation)
            val translated = translateCheckoutOutcome(command, request, current, observation, transition)
            val contribution = if (derive == null) null else {
                val observed = checkNotNull(inventory)
                probe.inventoryPlans++
                val plan = InventoryPlanner.recordCollectionExposure(observed.snapshot, derive.stock.inventoryRevision, context.operationId,
                    derive.stock.reservation.id, derive.stock.reservation.revision, checkoutDerivedExposure(transition.value.collection))
                check(plan.mustCommitBeforeEpochMillis == null && plan.effects.size == 1 && plan.effects.single() is InventoryEffect.ReservationRecorded) {
                    "checkout.outcome.exposure-plan"
                }
                checkoutContribution(observed, context, plan, recordedAt).also {
                    check(it.mustCommitBeforeEpochMillis == null && it.claims.isEmpty()) { "checkout.outcome.source-free" }
                }
            }
            val stock = contribution?.let { CheckoutReservationRef(it.snapshot.revision,
                CheckoutRef(current.collection.reservationId, checkNotNull(it.snapshot.reservation(current.collection.reservationId)).revision)) }
            val result = CheckoutResult.OutcomeRecorded(current.reference(afterCollection = transition.value.collection, afterCash = transition.value.session),
                request.attemptId, stock)
            checkoutApply(context, normalized, authorization, recordedAt, CheckoutFixtureCodec.encodeResult(result),
                contribution?.changes.orEmpty() + translated.changes, contribution?.claims.orEmpty() + translated.claims,
                contribution?.let(::checkoutDeadline))
        }
    }

    private suspend fun finalize(request: CheckoutRequest.Finalize, normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        val current = readCheckoutSales(view, request.sales)
        val inventory = observeCheckoutInventory(view)
        val sourceUnused = view.claim(InventoryRetailStoreProfile.sourceClaim(request.issueId)) == null &&
            request.issueId !in inventory.snapshot.sourceIds &&
            view.aggregate(CheckoutFixtureProfile.salesKey(SalesSnapshotKind.PAID_SALE, request.saleId)) == null &&
            view.aggregate(CheckoutFixtureProfile.salesKey(SalesSnapshotKind.RECEIPT_ENTITLEMENT, request.saleId)) == null &&
            checkoutClaimsAbsent(view, listOf(SalesUniqueKind.SALE to request.saleId, SalesUniqueKind.SALE_ORDER to current.order.order.id,
                SalesUniqueKind.RETURN_ENTITLEMENT to request.saleId))
        // Select the real zero permission and REAL current owner before either planner branch.
        val action = if (current.collection.quote.payable.minor == 0L) SecurityAction.COLLECTION_FINALIZE_ZERO else SecurityAction.COLLECTION_FINALIZE
        val requirement = salesAuthorizationRequirement(action, context.scope, current.order.order.id, request.sales.order.revision,
            context.operationId, context.semanticDigest, key.name, current.order.order.ownerId, current.collection.quote.payable)
        val footprint = checkoutSalesFootprint(context, current) + checkoutInventoryFootprint(context, inventory) +
            checkoutStockResources(request.issueId) + setOf(RecoveryResourceKey(RecoveryResourceKind.REFUND_ENTITLEMENT, request.saleId))
        return checkoutPrepared(context, normalized, requirement, footprint, true, probe) { authorization, recordedAt ->
            requireCheckoutFinalizationCurrent(current, request, inventory, authorization.actorId)
            checkoutRequire(sourceUnused, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
            probe.preflightPasses++
            probe.inventoryPlans++
            val issue = InventoryPlanner.issueReservation(inventory.snapshot, request.stock.inventoryRevision, context.operationId,
                request.stock.reservation.id, request.stock.reservation.revision, request.issueId, recordedAt.value.toEpochMilliseconds())
            when (issue) {
                is ReservationIssueResult.Held -> {
                    val contribution = checkoutContribution(inventory, context, issue.plan, recordedAt)
                    check(issue.reservationId == current.collection.reservationId && contribution.claims.isEmpty() &&
                        contribution.snapshot.reservation(issue.reservationId)?.status == ReservationStatus.HELD &&
                        contribution.snapshot.issues == inventory.snapshot.issues) { "checkout.finalize.held" }
                    val result = CheckoutResult.Held(current.reference(), checkoutStockReference(issue.plan, issue.reservationId))
                    checkoutApply(context, normalized, authorization, recordedAt, CheckoutFixtureCodec.encodeResult(result),
                        contribution.changes, contribution.claims, checkoutDeadline(contribution))
                }
                is ReservationIssueResult.Issued -> {
                    val command = checkoutSalesCommand(context, authorization, recordedAt)
                    probe.domainCalls++
                    val transition = Collections.finalizePaid(command, current.order.order, request.sales.order.revision,
                        current.collection, request.sales.collection.revision, request.saleId, issue)
                    val translated = translateCheckoutFinalized(command, request, current, issue, transition)
                    val contribution = checkoutContribution(inventory, context, issue.plan, recordedAt)
                    check(contribution.claims == listOf(ClaimChange.Acquire(InventoryRetailStoreProfile.sourceClaim(request.issueId),
                        DomainId(context.operationId.value)))) { "checkout.finalize.issue-source" }
                    val result = CheckoutResult.Issued(current.reference(transition.value.order, transition.value.collection),
                        checkoutStockReference(issue.plan, current.collection.reservationId), request.issueId, request.saleId)
                    checkoutApply(context, normalized, authorization, recordedAt, CheckoutFixtureCodec.encodeResult(result),
                        contribution.changes + translated.changes, contribution.claims + translated.claims, checkoutDeadline(contribution))
                }
            }
        }
    }
}

private suspend fun checkoutClaimsAbsent(view: CommandReadView, keys: List<Pair<SalesUniqueKind, DomainId>>): Boolean {
    var absent = true
    for ((kind, id) in keys) if (view.claim(CheckoutFixtureProfile.claim(kind, id)) != null) absent = false
    return absent
}

private fun checkoutPrepared(
    context: CommandContext,
    normalized: StoredBytes,
    required: AuthorizationRequirement,
    footprint: Set<RecoveryResourceKey>,
    stockBarrier: Boolean,
    probe: CheckoutCommandProbe,
    body: (AuthorizedAction, UtcInstant) -> ProposedOutcome,
): PreparedMutation {
    probe.prepared(required, footprint)
    return object : PreparedMutation() {
        override val requirement = required
        override val affectedResources: Set<RecoveryResourceKey> = footprint.toSet()
        override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = if (stockBarrier) setOf(RecoveryResourceKind.STOCK) else emptySet()
        override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
            probe.evaluations++
            check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.isBoundTo(requirement, context.originalActorId) &&
                semanticDigest(context.scope, context.originalActorId, context.key, normalized) == context.semanticDigest) { "checkout.authorization.binding" }
            probe.preflightChecks++
            return try {
                body(authorization, recordedAt).also { if (it is ProposedOutcome.Apply) probe.applies++ }
            } catch (failure: SalesRuleViolation) {
                checkoutReject(CheckoutRejectionCodes.sales(failure.rule))
            } catch (failure: InventoryRejection) {
                checkoutReject(CheckoutRejectionCodes.inventory(failure.failure))
            } catch (_: CheckoutCapacityExceeded) {
                checkoutReject(CheckoutRejectionCodes.CAPACITY)
            } catch (failure: CheckoutContractRejected) {
                if (failure.failure == CheckoutContractFailure.TOO_LARGE) checkoutReject(CheckoutRejectionCodes.CAPACITY) else throw failure
            } catch (failure: InventoryRetailStoreRejected) {
                when (failure.failure) {
                    InventoryRetailStoreFailure.TOO_LARGE -> checkoutReject(CheckoutRejectionCodes.CAPACITY)
                    InventoryRetailStoreFailure.SOURCE_CONFLICT -> checkoutReject(CheckoutRejectionCodes.SOURCE_CONFLICT)
                    else -> throw failure
                }
            }
        }
    }
}

private fun checkoutReject(code: String): ProposedOutcome.Reject =
    ProposedOutcome.Reject(code, CheckoutFixtureCodec.encodeResult(CheckoutResult.Rejected(code)))

private fun checkoutSalesCommand(context: CommandContext, authorization: AuthorizedAction, recordedAt: UtcInstant): SalesCommand =
    SalesCommand(context.operationId, authorization, recordedAt.value, context.semanticDigest, context.key.name)

private fun checkoutContribution(observed: InventoryRetailObservedFacts, context: CommandContext, plan: InventoryPlan,
    recordedAt: UtcInstant): InventoryRetailContribution = InventoryRetailRepository.projectContribution(observed, context, plan,
    recordedAt.value.toEpochMilliseconds()).also {
    check(plan.sourceSnapshot === observed.snapshot && it.snapshot === plan.nextSnapshot &&
        it.mustCommitBeforeEpochMillis == plan.mustCommitBeforeEpochMillis && it.changes.size == 2) { "checkout.inventory.exact-plan" }
}

private fun checkoutStockReference(plan: InventoryPlan, reservationId: DomainId): CheckoutReservationRef =
    CheckoutReservationRef(plan.nextSnapshot.revision, CheckoutRef(reservationId, checkNotNull(plan.nextSnapshot.reservation(reservationId)).revision))

private fun checkoutDeadline(contribution: InventoryRetailContribution): UtcInstant? =
    contribution.mustCommitBeforeEpochMillis?.let { UtcInstant(Instant.fromEpochMilliseconds(it)) }

private fun checkoutBaseFootprint(context: CommandContext): Set<RecoveryResourceKey> =
    setOf(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId))

private fun checkoutCashFootprint(context: CommandContext, cash: CheckoutLiveCash): Set<RecoveryResourceKey> = checkoutBaseFootprint(context) +
    (listOf(cash.session.id, cash.session.drawerId, cash.session.custodianId, cash.session.openingSourceId) + cash.session.pendingAttemptIds +
        cash.session.pendingTransferIds).map { RecoveryResourceKey(RecoveryResourceKind.CASH_CUSTODY, it) }

private fun checkoutOrderResources(orderId: DomainId, collectionId: DomainId): Set<RecoveryResourceKey> =
    listOf(orderId, collectionId).map { RecoveryResourceKey(RecoveryResourceKind.ORDER_CUSTODY, it) }.toSet()
private fun checkoutStockResources(id: DomainId): Set<RecoveryResourceKey> = setOf(RecoveryResourceKey(RecoveryResourceKind.STOCK, id))

private fun checkoutSalesFootprint(context: CommandContext, current: CheckoutLiveSales): Set<RecoveryResourceKey> =
    checkoutCashFootprint(context, current.cash) + checkoutOrderResources(current.order.order.id, current.collection.id) +
        current.collection.attempts.map { RecoveryResourceKey(RecoveryResourceKind.CASH_CUSTODY, it.id) }

/** Bounded aliases of the actual cut, including collateral expiry/hold targets. Not a production frontier. */
private fun checkoutInventoryFootprint(context: CommandContext, current: InventoryRetailObservedFacts?,
    receipts: List<InventoryFixtureReceiveRequest> = emptyList()): Set<RecoveryResourceKey> = buildSet {
    addAll(checkoutBaseFootprint(context))
    fun stock(id: DomainId) { add(RecoveryResourceKey(RecoveryResourceKind.STOCK, id)) }
    fun position(key: StockPositionKey) { stock(key.pool.skuId); stock(key.pool.warehouseId); key.lotId?.let(::stock) }
    stock(context.scope.branchId)
    current?.let { observed ->
        observed.snapshot.positions.forEach { position(it.key) }
        observed.snapshot.sourceIds.forEach(::stock)
        observed.companion.receipts.forEach { stock(it.receiptId); stock(it.origin.documentId); stock(it.origin.allocationId) }
        observed.snapshot.reservations.forEach { reservation ->
            stock(reservation.reservationId); stock(reservation.binding.orderId); stock(reservation.binding.collectionId)
            reservation.allocations.forEach { stock(it.allocationId); position(it.position) }
        }
        observed.snapshot.issues.forEach { stock(it.issueId); stock(it.reservationId) }
    }
    receipts.forEach { receipt ->
        position(receipt.toReceipt(context.scope).position); stock(receipt.receiptId); stock(receipt.origin.documentId); stock(receipt.origin.allocationId)
    }
}

/** Separate negative controls; there is no fault property on an ordinary accepting handler. */
internal enum class CheckoutProducerFault {
    OMIT_CUT, OMIT_COMPANION, OMIT_ORDER, OMIT_COLLECTION, OMIT_CASH, OMIT_SALE, OMIT_ENTITLEMENT,
    EXTRA_WRITE, WRONG_RESULT, EXTRA_CLAIM, OMIT_CLAIM, WRONG_CLAIM_OWNER, RELEASE_RECEIPT_SOURCE, EXTRA_JOB,
}

private class CheckoutFaultyProducer(private val delegate: MasterCommandHandler, private val fault: CheckoutProducerFault) : MasterCommandHandler {
    override val key: CommandKey get() = delegate.key
    override val writableKinds: Set<String> get() = delegate.writableKinds
    override val claimNamespaces: Set<String> get() = delegate.claimNamespaces
    override fun normalize(input: StoredBytes): StoredBytes = delegate.normalize(input)
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        val prepared = delegate.prepare(normalized, view, context)
        val release = if (fault == CheckoutProducerFault.RELEASE_RECEIPT_SOURCE) {
            require(InventoryRetailStoreProfile.SOURCE_NAMESPACE in claimNamespaces)
            val receipt = observeCheckoutInventory(view).companion.receipts.first()
            ClaimChange.Release(InventoryRetailStoreProfile.sourceClaim(receipt.receiptId), DomainId(receipt.producingOperationId.value))
        } else null
        return object : PreparedMutation() {
            override val requirement = prepared.requirement
            override val affectedResources = prepared.affectedResources
            override val recoveryHoldBarrierKinds = prepared.recoveryHoldBarrierKinds
            override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
                val original = prepared.evaluate(authorization, recordedAt)
                if (original !is ProposedOutcome.Apply) return original
                var changes = original.changes
                var claims = original.claims
                var result = original.result
                var jobs = original.jobs
                val omitted = when (fault) {
                    CheckoutProducerFault.OMIT_CUT -> InventoryRetailStoreProfile.CUT_KIND
                    CheckoutProducerFault.OMIT_COMPANION -> InventoryRetailStoreProfile.COMPANION_KIND
                    CheckoutProducerFault.OMIT_ORDER -> CheckoutFixtureProfile.ORDER_KIND
                    CheckoutProducerFault.OMIT_COLLECTION -> CheckoutFixtureProfile.COLLECTION_KIND
                    CheckoutProducerFault.OMIT_CASH -> CheckoutFixtureProfile.SESSION_KIND
                    CheckoutProducerFault.OMIT_SALE -> CheckoutFixtureProfile.SALE_KIND
                    CheckoutProducerFault.OMIT_ENTITLEMENT -> CheckoutFixtureProfile.ENTITLEMENT_KIND
                    else -> null
                }
                if (omitted != null) {
                    check(changes.any { it.key.kind == omitted }) { "checkout.negative.not-applicable" }
                    changes = changes.filterNot { it.key.kind == omitted }
                } else when (fault) {
                    CheckoutProducerFault.EXTRA_WRITE -> changes = changes + AggregateChange(AggregateKey(writableKinds.sorted().first(), testId(319_990)),
                        null, "qualification.checkout.invalid/v1", StoredBytes(byteArrayOf(1)))
                    CheckoutProducerFault.WRONG_RESULT -> result = CheckoutFixtureCodec.encodeResult(CheckoutResult.Rejected(CheckoutRejectionCodes.SOURCE_CONFLICT))
                    CheckoutProducerFault.EXTRA_CLAIM -> claims = claims + ClaimChange.Acquire(ClaimKey(claimNamespaces.sorted().first(), testId(319_991).value), testId(319_992))
                    CheckoutProducerFault.OMIT_CLAIM -> { check(claims.isNotEmpty()); claims = claims.dropLast(1) }
                    CheckoutProducerFault.WRONG_CLAIM_OWNER -> {
                        check(claims.isNotEmpty()); claims = listOf(ClaimChange.Acquire(claims.first().key, testId(319_992))) + claims.drop(1)
                    }
                    CheckoutProducerFault.RELEASE_RECEIPT_SOURCE -> claims = claims + checkNotNull(release)
                    CheckoutProducerFault.EXTRA_JOB -> jobs = listOf(JobIntent("qualification.checkout.unexpected", StoredBytes(byteArrayOf(1))))
                    else -> error("checkout.negative.not-applicable")
                }
                return ProposedOutcome.Apply(result, changes, claims, jobs, original.mustCommitBefore)
            }
        }
    }
}

internal val CHECKOUT_DECLARATION_CONTROL: CommandKey = CommandKey("qualification.checkout.declaration-control", 1)
private class CheckoutDeclarationControl(private val targetKind: String?, private val targetNamespace: String?) : MasterCommandHandler {
    override val key: CommandKey = CHECKOUT_DECLARATION_CONTROL
    private val kinds = mutableSetOf("qualification.checkout.outside")
    private val namespaces = mutableSetOf<String>()
    override val writableKinds: Set<String> get() = kinds
    override val claimNamespaces: Set<String> get() = namespaces
    fun expand() { targetKind?.let(kinds::add); targetNamespace?.let(namespaces::add) }
    override fun normalize(input: StoredBytes): StoredBytes = input
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation = object : PreparedMutation() {
        override val requirement = AuthorizationRequirement(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM), context.scope,
            ActionBinding(context.scope.branchId, Revision(0), context.semanticDigest, context.operationId, key.name))
        override val affectedResources = checkoutBaseFootprint(context) + checkoutStockResources(context.scope.branchId)
        override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome = ProposedOutcome.Apply(StoredBytes(byteArrayOf(1)),
            listOf(AggregateChange(AggregateKey(targetKind ?: "qualification.checkout.outside", testId(319_993)), null,
                "qualification.checkout.invalid/v1", StoredBytes(byteArrayOf(1)))),
            targetNamespace?.let { listOf(ClaimChange.Acquire(ClaimKey(it, testId(319_994).value), testId(319_995))) }.orEmpty())
    }
}
