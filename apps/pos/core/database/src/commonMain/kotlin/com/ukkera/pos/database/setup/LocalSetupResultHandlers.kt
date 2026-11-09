package com.ukkera.pos.database.setup

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterCommandHandler
import com.ukkera.pos.database.MasterReadAuthorizationDenied
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.MasterReadInvalidSelection
import com.ukkera.pos.database.PreparedMutation
import com.ukkera.pos.database.PreparedMutationAdmissionRestriction
import com.ukkera.pos.database.PreparedRead
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/**
 * Installed setup-result commands and read. Registration supplies no grant, session, actor,
 * native custody or readiness: the master kernel derives all of those from current protected
 * state inside its serialized writer transaction.
 */
public class LocalSetupAcknowledgementServices(
    private val prerequisites: LocalSetupGoLivePrerequisiteSource = UnavailableLocalSetupGoLivePrerequisites,
) {
    private val commandValues: List<MasterCommandHandler> = listOf(
        LocalSetupAcknowledgeHandler(),
        LocalSetupGoLiveHandler(prerequisites),
    )
    private val readValues: List<MasterReadHandler> = listOf(LocalSetupResultReadHandler(prerequisites))

    public val commands: List<MasterCommandHandler> get() = commandValues.toList()
    public val reads: List<MasterReadHandler> get() = readValues.toList()
}

private abstract class OriginalBoundSetupHandler : MasterCommandHandler,
    com.ukkera.pos.database.MasterRetainedResultGuard {
    override suspend fun permitsRetainedResult(view: CommandReadView, result: StoredBytes): Boolean {
        val original = view.savedOriginalSetup() ?: return false
        val outcome = LocalSetupResultCodecV1.decodeOutcome(result)
        return when (key) {
            LocalSetupResultProfileV1.ACKNOWLEDGE -> outcome.acknowledgement?.originalIdentity() == original.originalIdentity()
            LocalSetupResultProfileV1.GO_LIVE -> outcome.goLive?.originalIdentity() == original.originalIdentity()
            else -> false
        }
    }
}

private class LocalSetupAcknowledgeHandler : OriginalBoundSetupHandler() {
    override val key: CommandKey get() = LocalSetupResultProfileV1.ACKNOWLEDGE
    override val writableKinds: Set<String> get() = setOf(LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND)
    override val claimNamespaces: Set<String> get() = emptySet()

    override fun normalize(input: StoredBytes): StoredBytes =
        LocalSetupResultCodecV1.encodeRequest(LocalSetupResultCodecV1.decodeRequest(input).installationId)

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView,
        context: CommandContext): PreparedMutation {
        val request = LocalSetupResultCodecV1.decodeRequest(normalized)
        val requirement = requirement(context, request.installationId,
            SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE, LocalSetupResultProfileV1.ACKNOWLEDGE_REASON)
        val resources = resources(context, request.installationId)
        val original = view.savedOriginalSetup()
            ?: return AcknowledgementPrepared(context, request, requirement, resources,
                original = null, existing = null, unavailable = true)
        val existing = view.aggregate(acknowledgementKey(original.installationId))?.let { row ->
            check(row.schema == LocalSetupResultProfileV1.ACKNOWLEDGEMENT_SCHEMA) {
                "Invalid setup acknowledgement storage profile"
            }
            LocalSetupResultCodecV1.decodeAcknowledgement(row.payload)
        }
        return AcknowledgementPrepared(context, request, requirement, resources,
            original = original, existing = existing, unavailable = false)
    }

    private class AcknowledgementPrepared(
        private val context: CommandContext,
        private val request: LocalSetupResultRequestV1,
        override val requirement: AuthorizationRequirement,
        override val affectedResources: Set<RecoveryResourceKey>,
        private val original: SavedOriginalSetupV1?,
        private val existing: LocalSetupAcknowledgementRecordV1?,
        private val unavailable: Boolean,
    ) : PreparedMutation() {
        // A missing/mismatched original or an already-recorded fact is a no-op hold: no new
        // rejection journal is invented, no effect is written, and same-ID retries still return
        // the genuine retained result before preparation. Current authority is checked first.
        override val admissionRestriction: PreparedMutationAdmissionRestriction?
            get() = if (unavailable || original == null ||
                request.installationId != original.installationId || existing != null)
                PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE else null
        override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> get() =
            setOf(RecoveryResourceKind.AUTHORIZATION)
        override val allowsActionApproval: Boolean get() = false

        override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
            val actual = checkNotNull(original) { "Setup acknowledgement source unavailable" }
            check(request.installationId == actual.installationId && existing == null) {
                "Setup acknowledgement is not a new original action"
            }
            val record = LocalSetupAcknowledgementRecordV1(
                organizationId = actual.scope.organizationId,
                companyId = actual.scope.companyId,
                branchId = actual.scope.branchId,
                installationId = actual.installationId,
                originalRequestId = actual.originalRequestId,
                issuedAttemptId = actual.issuedAttemptId,
                attemptSequence = actual.attemptSequence,
                definitionDigest = actual.definitionDigest,
                genesisDigest = actual.genesisDigest,
                receiptDigest = actual.receiptDigest,
                actorId = authorization.actorId,
                acknowledgedAt = recordedAt,
                operationId = context.operationId,
                policyRevision = authorization.policyRevision,
                masterAuthorityEpoch = actual.currentAuthorityEpoch,
            )
            return ProposedOutcome.Apply(
                result = LocalSetupResultCodecV1.encodeOutcome(LocalSetupResultOutcomeV1(
                    LocalSetupResultStatusV1.ACKNOWLEDGED,
                    LocalSetupResultProfileV1.ACKNOWLEDGED_CODE,
                    acknowledgement = record,
                )),
                changes = listOf(AggregateChange(
                    acknowledgementKey(actual.installationId), null,
                    LocalSetupResultProfileV1.ACKNOWLEDGEMENT_SCHEMA,
                    LocalSetupResultCodecV1.encodeAcknowledgement(record),
                )),
            )
        }
    }
}

private class LocalSetupGoLiveHandler(
    private val prerequisites: LocalSetupGoLivePrerequisiteSource,
) : OriginalBoundSetupHandler() {
    override val key: CommandKey get() = LocalSetupResultProfileV1.GO_LIVE
    override val writableKinds: Set<String> get() = setOf(LocalSetupResultProfileV1.GO_LIVE_KIND)
    override val claimNamespaces: Set<String> get() = emptySet()

    override fun normalize(input: StoredBytes): StoredBytes =
        LocalSetupResultCodecV1.encodeRequest(LocalSetupResultCodecV1.decodeRequest(input).installationId)

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView,
        context: CommandContext): PreparedMutation {
        val request = LocalSetupResultCodecV1.decodeRequest(normalized)
        val requirement = requirement(context, request.installationId,
            SecurityAction.SETUP_BUSINESS_GO_LIVE, LocalSetupResultProfileV1.GO_LIVE_REASON)
        val resources = resources(context, request.installationId)
        val original = view.savedOriginalSetup()
            ?: return GoLivePrepared(context, request, requirement, resources,
                original = null, existing = null, observations = emptyList(), unavailable = true)
        val existing = view.aggregate(goLiveKey(original.installationId))?.let { row ->
            check(row.schema == LocalSetupResultProfileV1.GO_LIVE_SCHEMA) {
                "Invalid setup go-live storage profile"
            }
            LocalSetupResultCodecV1.decodeGoLive(row.payload)
        }
        val observations = if (existing == null) observePrerequisites(prerequisites, view, original)
            else existing.prerequisites
        val sourceResources = if (existing == null && observations.all { it.settled })
            prerequisites.recoveryResources(view, original.installationId) else emptySet()
        return GoLivePrepared(context, request, requirement, resources + sourceResources.orEmpty(),
            original = original, existing = existing, observations = observations,
            unavailable = sourceResources == null)
    }

    private class GoLivePrepared(
        private val context: CommandContext,
        private val request: LocalSetupResultRequestV1,
        override val requirement: AuthorizationRequirement,
        override val affectedResources: Set<RecoveryResourceKey>,
        private val original: SavedOriginalSetupV1?,
        private val existing: LocalSetupGoLiveRecordV1?,
        private val observations: List<LocalSetupPrerequisiteObservationV1>,
        private val unavailable: Boolean,
    ) : PreparedMutation() {
        // Unknown/in-progress required prerequisites hold the action before evaluate, so no
        // go-live or rejection journal is committed. The protected read exposes the exact
        // unsettled binding; a caller cannot replace it with readiness/RUN/caller data.
        override val admissionRestriction: PreparedMutationAdmissionRestriction?
            get() = if (unavailable || original == null ||
                request.installationId != original.installationId || existing != null ||
                observations.any { !it.settled })
                PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE else null
        override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> get() =
            setOf(RecoveryResourceKind.AUTHORIZATION, RecoveryResourceKind.STOCK, RecoveryResourceKind.CASH_CUSTODY)
        override val allowsActionApproval: Boolean get() = false

        override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
            val actual = checkNotNull(original) { "Setup go-live source unavailable" }
            check(request.installationId == actual.installationId && existing == null &&
                observations.all { it.settled }) { "Setup go-live prerequisites are not settled" }
            val record = LocalSetupGoLiveRecordV1(
                organizationId = actual.scope.organizationId,
                companyId = actual.scope.companyId,
                branchId = actual.scope.branchId,
                installationId = actual.installationId,
                originalRequestId = actual.originalRequestId,
                issuedAttemptId = actual.issuedAttemptId,
                attemptSequence = actual.attemptSequence,
                definitionDigest = actual.definitionDigest,
                genesisDigest = actual.genesisDigest,
                receiptDigest = actual.receiptDigest,
                actorId = authorization.actorId,
                occurredAt = recordedAt,
                operationId = context.operationId,
                policyRevision = authorization.policyRevision,
                masterAuthorityEpoch = actual.currentAuthorityEpoch,
                prerequisites = observations,
            )
            return ProposedOutcome.Apply(
                result = LocalSetupResultCodecV1.encodeOutcome(LocalSetupResultOutcomeV1(
                    LocalSetupResultStatusV1.LIVE,
                    LocalSetupResultProfileV1.LIVE_CODE,
                    goLive = record,
                    prerequisites = observations,
                )),
                changes = listOf(AggregateChange(
                    goLiveKey(actual.installationId), null,
                    LocalSetupResultProfileV1.GO_LIVE_SCHEMA,
                    LocalSetupResultCodecV1.encodeGoLive(record),
                )),
            )
        }
    }
}

private class LocalSetupResultReadHandler(
    private val prerequisites: LocalSetupGoLivePrerequisiteSource,
) : MasterReadHandler {
    override val key: ReadKey get() = LocalSetupResultProfileV1.READ

    override fun normalize(input: StoredBytes): StoredBytes =
        LocalSetupResultCodecV1.encodeRequest(LocalSetupResultCodecV1.decodeRequest(input).installationId)

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView,
        context: ReadContext): PreparedRead {
        val request = LocalSetupResultCodecV1.decodeRequest(normalized)
        val original = view.savedOriginalSetup() ?: throw MasterReadAuthorizationDenied()
        if (request.installationId != original.installationId) throw MasterReadAuthorizationDenied()
        val acknowledgement = view.aggregate(acknowledgementKey(original.installationId))?.let { row ->
            check(row.schema == LocalSetupResultProfileV1.ACKNOWLEDGEMENT_SCHEMA) {
                "Invalid setup acknowledgement storage profile"
            }
            LocalSetupResultCodecV1.decodeAcknowledgement(row.payload)
        }
        val goLive = view.aggregate(goLiveKey(original.installationId))?.let { row ->
            check(row.schema == LocalSetupResultProfileV1.GO_LIVE_SCHEMA) {
                "Invalid setup go-live storage profile"
            }
            LocalSetupResultCodecV1.decodeGoLive(row.payload)
        }
        if (acknowledgement != null && acknowledgement.originalIdentity() != original.originalIdentity() ||
            goLive != null && goLive.originalIdentity() != original.originalIdentity()) throw MasterReadAuthorizationDenied()
        val observations = observePrerequisites(prerequisites, view, original)
        val requirement = AuthorizationRequirement(
            permission = Permission(SecurityAction.SETUP_RESULT_READ, PermissionVerb.PERFORM),
            scope = context.scope,
            binding = ActionBinding(original.installationId, Revision(0), context.semanticDigest, null,
                LocalSetupResultProfileV1.READ_REASON),
        )
        val saved = LocalSetupSavedOriginalResultV1(
            organizationId = original.scope.organizationId,
            companyId = original.scope.companyId,
            branchId = original.scope.branchId,
            installationId = original.installationId,
            assignmentId = original.assignmentId,
            deviceId = original.deviceId,
            firstActorId = original.firstActorId,
            originalRequestId = original.originalRequestId,
            issuedAttemptId = original.issuedAttemptId,
            attemptSequence = original.attemptSequence,
            definitionDigest = original.definitionDigest,
            genesisDigest = original.genesisDigest,
            receiptDigest = original.receiptDigest,
            installationAuthorityEpoch = original.installationAuthorityEpoch,
            currentAuthorityEpoch = original.currentAuthorityEpoch,
            installedAt = original.installedAt,
            nativeSettlement = original.nativeSettlement,
        )
        return ReadPrepared(requirement, LocalSetupResultProjectionV1(saved, acknowledgement, goLive, observations))
    }

    private class ReadPrepared(
        private val requirement: AuthorizationRequirement,
        private val projection: LocalSetupResultProjectionV1,
    ) : PreparedRead() {
        override val requirements: List<AuthorizationRequirement> get() = listOf(requirement)

        override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
            val authorization = authorizations.single()
            check(authorization.isBoundTo(requirement)) { "Setup read authorization mismatch" }
            return LocalSetupResultCodecV1.encodeProjection(projection)
        }
    }
}

private fun requirement(context: CommandContext, installationId: com.ukkera.pos.types.DomainId,
    action: SecurityAction, reason: String): AuthorizationRequirement =
    AuthorizationRequirement(
        permission = Permission(action, PermissionVerb.PERFORM),
        scope = context.scope,
        binding = ActionBinding(installationId, Revision(0), context.semanticDigest, context.operationId, reason),
    )

private fun resources(context: CommandContext,
    installationId: com.ukkera.pos.types.DomainId): Set<RecoveryResourceKey> = setOf(
    RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId),
    RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, installationId),
)

private fun acknowledgementKey(installationId: com.ukkera.pos.types.DomainId): AggregateKey =
    AggregateKey(LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND, installationId)

private fun goLiveKey(installationId: com.ukkera.pos.types.DomainId): AggregateKey =
    AggregateKey(LocalSetupResultProfileV1.GO_LIVE_KIND, installationId)

private suspend fun observePrerequisites(source: LocalSetupGoLivePrerequisiteSource,
    view: CommandReadView, original: SavedOriginalSetupV1): List<LocalSetupPrerequisiteObservationV1> {
    val native = if (original.nativeSettlement)
        LocalSetupPrerequisiteObservationV1.settled(
            LocalSetupPrerequisiteCodeV1.NATIVE_INSTALLATION_SETTLEMENT,
            "native.installation.settlement:${original.receiptDigest.value}")
    else LocalSetupPrerequisiteObservationV1.unavailable(
        LocalSetupPrerequisiteCodeV1.NATIVE_INSTALLATION_SETTLEMENT,
        "unavailable:native.installation.settlement")
    val observed = source.observe(view, original)
    require(observed.none { it.code == LocalSetupPrerequisiteCodeV1.NATIVE_INSTALLATION_SETTLEMENT }) {
        "Setup prerequisite source cannot replace native installation evidence"
    }
    val combined = (listOf(native) + observed).sortedBy { it.code.ordinal }
    LocalSetupPrerequisiteObservationV1.requireComplete(combined)
    return combined
}
