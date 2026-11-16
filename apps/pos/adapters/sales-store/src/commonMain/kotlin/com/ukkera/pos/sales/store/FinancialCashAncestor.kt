package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.KSerializer

/** Actual ordinary ancestor DATA. This is neither a CommandReadView nor a historical
 * authorization/command object. No handler, prepare, evaluate, writer or payout exists. */
internal class FinancialCashAncestor(private val proof: LocalFinancialOriginalProofScope,
    private val sources: RuntimeHistoricalCashConfigurationSources) {
    val evidence = sources.evidence
    val original = sources.original
    val scope get() = evidence.scope
    val actorId get() = evidence.originalActorId
    val normalized get() = evidence.normalized
    val command get() = evidence.command
    val operationId get() = evidence.operationId
    val semanticDigest get() = evidence.semanticDigest
    val recordedAt get() = evidence.recordedAt
    val authenticationId get() = evidence.authenticationId
    val policyRevision get() = evidence.policyRevision
    init { check(command == LocalSalesStoreProfile.CASH_COMMAND &&
        LocalCashCommandCodec.encode(LocalCashCommandCodec.decode(normalized)) == normalized &&
        semanticDigest(scope, actorId, command, normalized) == semanticDigest &&
        sources.kind.name == LocalCashCommandCodec.decode(normalized).let {
            kotlinx.serialization.json.Json.parseToJsonElement(normalized.copyBytes().decodeToString())
                .let { json -> (json as kotlinx.serialization.json.JsonObject)["type"] }
                .let { type -> (type as kotlinx.serialization.json.JsonPrimitive).content }
        }) }
    suspend fun aggregateSource(key: AggregateKey): RuntimeNativeLiteralAggregateSource? = proof.aggregate(sources, key)
    suspend fun aggregate(key: AggregateKey): StoredAggregate? = aggregateSource(key)?.row
    suspend fun claim(key: ClaimKey): DomainId? = proof.claim(sources, key)
    fun requireRequirement(expected: AuthorizationRequirement) { check(sources.requirement == expected) }
    fun requireLegacyEffects(expected: ProposedOutcome.Apply, resources: Set<RecoveryResourceKey>) {
        check(evidence.committed && evidence.code == "committed" && evidence.result == expected.result &&
            evidence.resultHash == sha256(expected.result.copyBytes()) && evidence.aggregateWrites.size == expected.changes.size &&
            evidence.claimChanges.size == expected.claims.size && evidence.jobs.isEmpty() && expected.jobs.isEmpty())
        for (change in expected.changes) {
            val write = evidence.aggregateWrites.single { it.key == change.key }
            check(write.storageGeneration == (change.expectedRevision ?: Revision(0)).next() && write.schema == change.schema &&
                write.payload == change.payload && write.payloadDigest == sha256(change.payload.copyBytes()))
        }
        for (change in expected.claims) {
            val retained = evidence.claimChanges.single { it.key == change.key }
            check(retained.ownerId == change.ownerId && retained.action ==
                if (change is ClaimChange.Acquire) RetainedClaimAction.ACQUIRE else RetainedClaimAction.RELEASE)
        }
        val actual = sources.resources
        check(actual.size == resources.size && actual.all { value -> value.selectorTag == 3 && value.preserveHold &&
            value.originalEvidenceDigest == semanticDigest && resources.any { it.kind.name == value.kind?.name && it.resourceId == value.resourceId } })
    }
}

/** Whole positive ordinary cash replay from the actual closed BEFORE capsule. */
    internal suspend fun requireFinancialCashAncestor(owner: LocalFinancialOriginalProofScope, sources: RuntimeHistoricalCashConfigurationSources) {
        val command = FinancialCashAncestor(owner, sources)
        val request = LocalCashCommandCodec.decode(command.normalized)
        check(LocalCashCommandCodec.normalize(command.normalized) == command.normalized)
        check(command.evidence.committed)
        val scope = LocalSalesScope.from(command.scope)
        val outcome: ProposedOutcome
        val target: DomainId
        val reason: String
        val resources: Set<RecoveryResourceKey>
        var revision = Revision(0)
        var amount: Money? = null
        var action = SecurityAction.CASH_CONFIGURE
        when (request) {
            is LocalCashRequestV1.ConfigureDrawer -> {
                target = request.drawerId; reason = "cash.configure.drawer"
                val kind = LocalSalesStoreProfile.DRAWER_KIND; val claim = configuredClaim(kind, target)
                check(command.aggregate(LocalSalesStoreProfile.key(kind, target)) == null && command.claim(claim) == null &&
                    command.claim(LocalSalesStoreProfile.activeDrawer(target)) == null)
                val value = LocalDrawerState(scope, target, Revision(0), request.label, request.currency)
                resources = setOf(cashResource(target))
                outcome = salesApplied(LocalSalesAction.DRAWER_CONFIGURED, target, listOf(LocalSalesRevision(kind, target, Revision(0))),
                    listOf(rootWrite(kind, target, value, LocalDrawerState.serializer(), null)), listOf(ClaimChange.Acquire(claim, target)))
            }
            is LocalCashRequestV1.ConfigureCustodian -> {
                target = request.custodianId; reason = "cash.configure.custodian"
                val kind = LocalSalesStoreProfile.CUSTODIAN_KIND; val claim = configuredClaim(kind, target)
                check(command.aggregate(LocalSalesStoreProfile.key(kind, target)) == null &&
                    command.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.WORK_KIND, target)) == null && command.claim(claim) == null &&
                    command.claim(LocalSalesStoreProfile.activeCustodian(target)) == null)
                val value = LocalCustodianState(scope, target, Revision(0)); val work = LocalWorkState(scope, target, Revision(0))
                resources = setOf(cashResource(target), orderResource(target))
                outcome = salesApplied(LocalSalesAction.CUSTODIAN_CONFIGURED, target, listOf(LocalSalesRevision(kind, target, Revision(0)),
                    LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, target, Revision(0))),
                    listOf(rootWrite(kind, target, value, LocalCustodianState.serializer(), null),
                        rootWrite(LocalSalesStoreProfile.WORK_KIND, target, work, LocalWorkState.serializer(), null)), listOf(ClaimChange.Acquire(claim, target)))
            }
            is LocalCashRequestV1.ConfigureSafe -> {
                target = request.custodyId; reason = "cash.configure.safe"
                val kind = LocalSalesStoreProfile.SAFE_KIND; val claim = configuredClaim(kind, target)
                check(command.aggregate(LocalSalesStoreProfile.key(kind, target)) == null && command.claim(claim) == null)
                custodian(command, request.custodianId)
                val value = LocalSafeState(scope, target, Revision(0), request.custodianId, request.label, Money(request.currency, 0))
                resources = setOf(cashResource(target), cashResource(request.custodianId))
                outcome = salesApplied(LocalSalesAction.SAFE_CONFIGURED, target, listOf(LocalSalesRevision(kind, target, Revision(0))),
                    listOf(rootWrite(kind, target, value, LocalSafeState.serializer(), null)), listOf(ClaimChange.Acquire(claim, target)))
            }
            is LocalCashRequestV1.Open -> {
                target = request.drawerId; reason = "cash.open"; revision = request.expectedDrawerRevision
                amount = request.opening; action = SecurityAction.CASH_OPEN
                val drawer = root(command, LocalSalesStoreProfile.DRAWER_KIND, target, LocalDrawerState.serializer()) { value, rev ->
                    check(value.scope == scope && value.id == target && value.revision == rev && value.activeSessionId == null && value.closingTransferId == null)
                    localLabel(value.label)
                }
                val custodian = custodian(command, command.actorId)
                work(command, command.actorId)
                check(drawer.value.revision == request.expectedDrawerRevision && custodian.value.revision == request.expectedCustodianRevision &&
                    drawer.value.currency == request.opening.currency && custodian.value.activeSessionId == null &&
                    command.claim(LocalSalesStoreProfile.activeDrawer(target)) == null &&
                    command.aggregate(CashNonSaleStoreProfile.sessionKey(request.sessionId)) == null)
                val unique = listOf(LocalSalesStoreProfile.unique("cash_session", request.sessionId),
                    LocalSalesStoreProfile.unique("opening_source", request.openingSourceId))
                check(unique.all { command.claim(it) == null })
                val retained = (command.original as RetainedOriginalV2.Legacy).evidence
                val row = retained.aggregateWrites.singleOrNull { it.key == CashNonSaleStoreProfile.sessionKey(request.sessionId) }
                    ?: error("Original cash opening unavailable")
                val raw = row.payload.copyBytes()
                val value = try { SalesSnapshotCodec.decodeCashSession(raw.decodeToString(throwOnInvalidSequence = true),
                    SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, command.scope, request.sessionId, Revision(0))) } finally { raw.fill(0) }
                val zero = Money(request.opening.currency, 0)
                // Compare EVERY initial domain field. The decoded session is inert historical
                // data; it is not a reconstructed SalesCommand or an AuthorizedAction.
                check(value.id == request.sessionId && value.scope == command.scope && value.drawerId == target && value.custodianId == command.actorId &&
                    value.businessDate == request.businessDate.value && value.revision == Revision(0) &&
                    value.opening == CountedCash(request.opening, request.evidence.domain(command.actorId)) && value.openingSourceId == request.openingSourceId &&
                    value.totals == CashTotals(zero, zero, zero, zero, zero, zero, zero, zero) && value.expectedCash == request.opening &&
                    value.reservedCashOut == zero && value.cashLedgerRevision == Revision(0) && value.status == CashSessionStatus.OPEN &&
                    value.pendingAttemptIds.isEmpty() && value.pendingTransferIds.isEmpty() && value.closingCut == null && value.closeReport == null && value.review == null)
                val afterDrawer = drawer.value.copy(revision = drawer.value.revision.next(), activeSessionId = value.id)
                val afterCustodian = custodian.value.copy(revision = custodian.value.revision.next(), activeSessionId = value.id)
                resources = setOf(cashResource(target), cashResource(command.actorId), cashResource(value.id), cashResource(request.openingSourceId))
                outcome = salesApplied(LocalSalesAction.CASH_OPENED, value.id, listOf(LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, value.id, Revision(0)),
                    LocalSalesRevision(LocalSalesStoreProfile.DRAWER_KIND, target, afterDrawer.revision),
                    LocalSalesRevision(LocalSalesStoreProfile.CUSTODIAN_KIND, command.actorId, afterCustodian.revision)),
                    listOf(cashWrite(value, null), rootWrite(LocalSalesStoreProfile.DRAWER_KIND, target, afterDrawer, LocalDrawerState.serializer(), drawer.stored),
                        rootWrite(LocalSalesStoreProfile.CUSTODIAN_KIND, command.actorId, afterCustodian, LocalCustodianState.serializer(), custodian.stored)),
                    listOf(ClaimChange.Acquire(unique[0], value.id), ClaimChange.Acquire(unique[1], value.id),
                        ClaimChange.Acquire(LocalSalesStoreProfile.activeDrawer(target), value.id),
                        ClaimChange.Acquire(LocalSalesStoreProfile.activeCustodian(command.actorId), value.id)), value.expectedCash)
            }
            is LocalCashRequestV1.NonSale -> { requireFinancialCashAncestorNonSale(command, request); return }
            is LocalCashRequestV1.DropToSafe -> { requireFinancialCashAncestorDrop(command, request); return }
            is LocalCashRequestV1.SubmitClose -> { requireFinancialCashAncestorLegacyClose(command, request); return }
            else -> { check(requireFinancialCashAncestorClosing(command, request)); return }
        }
        command.requireRequirement(salesAuthorizationRequirement(action, command.scope, target, revision,
            command.operationId, command.semanticDigest, reason, command.actorId, amount))
        command.requireLegacyEffects(outcome as? ProposedOutcome.Apply ?: error("Original cash effects unavailable"),
            resources + RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, command.actorId))
    }


    private suspend fun custodian(command: FinancialCashAncestor, id: DomainId): LocalSalesRow<LocalCustodianState> =
        root(command, LocalSalesStoreProfile.CUSTODIAN_KIND, id, LocalCustodianState.serializer()) { value, revision ->
            check(value.scope.value() == command.scope && value.id == id && value.revision == revision)
        }.also { check(command.claim(LocalSalesStoreProfile.activeCustodian(id)) == it.value.activeSessionId) }
    private suspend fun work(command: FinancialCashAncestor, id: DomainId): LocalSalesRow<LocalWorkState> =
        root(command, LocalSalesStoreProfile.WORK_KIND, id, LocalWorkState.serializer()) { value, revision ->
            check(value.scope.value() == command.scope && value.id == id && value.revision == revision &&
                listOf(value.orderIds, value.returnIds, value.pendingClaimIds).all { it.size <= LocalSalesStoreProfile.MAX_OPEN_WORK && it.distinct().size == it.size })
        }
    private suspend fun <T> root(command: FinancialCashAncestor, kind: String, id: DomainId, serializer: KSerializer<T>,
        validate: (T, Revision) -> Unit): LocalSalesRow<T> {
        val row = command.aggregate(LocalSalesStoreProfile.key(kind, id)) ?: error("Original cash source unavailable")
        check(row.schema == LocalSalesStoreProfile.schema(kind) && row.revision.value > 0)
        val value = decodeLocalSalesSource(row.payload, serializer)
        validate(value, row.domainRevision())
        val configured = if (kind == LocalSalesStoreProfile.WORK_KIND) LocalSalesStoreProfile.CUSTODIAN_KIND else kind
        check(command.claim(configuredClaim(configured, id)) == id)
        return LocalSalesRow(row, value)
    }
