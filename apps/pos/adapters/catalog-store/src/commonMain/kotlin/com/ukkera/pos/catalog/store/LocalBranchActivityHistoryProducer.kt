package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.RuntimeHistoricalAuthority
import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.database.RuntimeHistoryBusinessProducer
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.types.DomainId

/**
 * Genuine original-native BEFORE joins for the initial-only employee command. This never
 * constructs a historical CommandReadView, re-prepares a handler, recreates a session or
 * accepts an unavailable signature/source. The sole rejection requires its separate closed
 * core bridge; every present source has already joined its strictly earlier original.
 */
internal class LocalBranchActivityHistoryProducer(private val signatures: CompanyPublicationSignatureVerifier) : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> get() = setOf(LocalBranchActivityProfileV1.ACTIVATE_INITIAL)

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands || command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND) return false
        val retained = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        if (retained.nativeOriginalProvenance != null) return false
        try {
            check(semanticDigest(command.scope, command.actorId, command.command, command.normalized) == command.semanticDigest)
            val request = branchActivityDecode(command.normalized, LocalActivateInitialBranchActivityV1.serializer(),
                LocalBranchActivityProfileV1.MAX_INPUT_BYTES)
            val signed = branchActivitySigned(request)
            requireBranchActivitySource(authenticBranchActivity(signatures, command.scope, signed))
            val reference = branchActivityReference(signed)
            val reader = HistoricalBranchActivityReader(command, signatures)
            val previous = reader.active()
            val publication = reader.row(LocalBranchActivityProfileV1.PUBLICATION, reference.publicationId)
            check(publication == null || previous?.publication?.row?.key == publication.row.key)
            reader.requireAuthenticSources()
            command.requireRequirement(branchActivityActivationRequirement(command.scope, previous?.active?.row?.revision,
                command.operationId, command.semanticDigest))
            val resources = branchActivityCommandResources(command.scope, command.actorId, command.operationId, reference) + reader.resources
            if (previous != null) {
                if (retained.committed) return false
                command.requireBranchActivityActivationRejection(branchActivityAlreadyActivated(reference, previous.applied()), resources)
                return true
            }
            if (!retained.committed) return false
            requireBranchActivityProducer(retained, signatures)
            val applied = branchActivityApplied(command.scope, reference, request, command.operationId, command.actorId,
                command.policyRevision, command.recordedAt)
            command.requireLegacyEffects(ProposedOutcome.Apply(branchActivitySuccess(applied),
                branchActivityChanges(command.scope, command.operationId, command.actorId, command.semanticDigest,
                    command.recordedAt, command.policyRevision, request, reference), branchActivityClaims(command.scope, reference)), resources)
            return true
        } catch (_: BranchActivitySourceUnavailable) {
            return false
        }
    }
}

/** Fixed native-selected BEFORE keys only, with exact already-joined producer pairing. */
private class HistoricalBranchActivityReader(private val command: RuntimeHistoricalCommand,
    private val signatures: CompanyPublicationSignatureVerifier) {
    val resources: MutableSet<RecoveryResourceKey> = mutableSetOf(branchActivityResource(command.scope.branchId))
    private val rows: MutableMap<AggregateKey, BranchActivityRowV1?> = mutableMapOf()
    private val signedSources: MutableList<LocalSignedCompanyActivityProfileV1> = mutableListOf()
    private var unsupportedOriginal: Boolean = false

    suspend fun row(kind: String, id: DomainId): BranchActivityRowV1? {
        val key = branchActivityKey(kind, id)
        resources += branchActivityResource(id)
        if (key in rows) return rows.getValue(key)
        check(rows.size < 3)
        val selected = command.aggregateSource(key)
        val claim = when (kind) {
            LocalBranchActivityProfileV1.ACTIVE -> branchActivityBranchClaim(command.scope)
            LocalBranchActivityProfileV1.PUBLICATION -> branchActivityPublicationClaim(id)
            else -> error("Unknown historical branch activity kind")
        }
        val owner = command.claim(claim)
        if (selected == null) {
            check(owner == null)
            rows[key] = null
            return null
        }
        val record = decodeBranchActivityRecord(command.scope, selected.row)
        resources += branchActivityRecordResources(record)
        val original = (selected.original as? RetainedOriginalV2.Legacy)?.evidence
        if (original == null) unsupportedOriginal = true
        else {
            requireBranchActivityStoredWrite(selected.row, record, original)
            if (original.nativeOriginalProvenance == null) signedSources += requireBranchActivityProducerShape(original)
            else unsupportedOriginal = true
        }
        when (val body = record.body) {
            is BranchActivityBodyV1.Active -> check(owner == body.reference.publicationId)
            is BranchActivityBodyV1.Publication -> check(owner == id && command.claim(branchActivityBranchClaim(command.scope)) == id)
        }
        return BranchActivityRowV1(selected.row, record).also { rows[key] = it }
    }

    suspend fun active(): LoadedBranchActivityV1? {
        val active = row(LocalBranchActivityProfileV1.ACTIVE, command.scope.branchId) ?: return null
        val reference = (active.record.body as BranchActivityBodyV1.Active).reference
        val publication = row(LocalBranchActivityProfileV1.PUBLICATION, reference.publicationId)
            ?: error("Original applied branch activity publication lost")
        val signed = branchActivitySigned((publication.record.body as BranchActivityBodyV1.Publication).request)
        check(branchActivityReference(signed) == reference && active.record.producer == publication.record.producer &&
            active.record.recordedAt == publication.record.recordedAt &&
            active.record.permissionPolicyRevision == publication.record.permissionPolicyRevision)
        return LoadedBranchActivityV1(active, publication, signed)
    }

    fun requireAuthenticSources() {
        requireBranchActivitySource(!unsupportedOriginal)
        signedSources.forEach { requireBranchActivitySource(authenticBranchActivity(signatures, command.scope, it)) }
    }
}
