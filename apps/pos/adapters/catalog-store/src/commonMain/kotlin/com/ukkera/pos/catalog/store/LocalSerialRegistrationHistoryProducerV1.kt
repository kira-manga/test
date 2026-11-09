package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.LocalCompanyBusinessPublications
import com.ukkera.pos.security.LocalSerialRegistrationPublicationCodecV1
import com.ukkera.pos.types.DomainId

/** Genuine original native cut, exact earlier writers and unchanged literal effects only. */
internal class LocalSerialRegistrationHistoryProducer(private val publications: LocalCompanyBusinessPublications?) : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> = setOf(LocalSerialRegistrationProfileV1.ACTIVATE)
    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands || publications == null) return false
        val original = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        val request = LocalSerialRegistrationCodecV1.decode(command.normalized)
        check(LocalSerialRegistrationCodecV1.encode(request) == command.normalized &&
            semanticDigest(command.scope, command.actorId, command.command, command.normalized) == command.semanticDigest)
        val signed = request.publication; val value = signed.definition
        val reference = LocalSerialRegistrationPublicationCodecV1.reference(value)
        val native = SerialRegistrationNativeCut.read(command.scope, publications, command.completedNativeBusinessPublicationsBefore())
        native.requiresCandidate(signed)
        if (original.committed) {
            requireSerialRegistrationProducer(original, publications)
            command.requireLegacyReadEvidence()
        }
        val reader = HistoricalSerialRegistrationReader(command, publications, native)
        val previous = reader.active(value.registrationId)
        val existing = reader.row(LocalSerialRegistrationProfileV1.PUBLICATION, value.publicationId) != null
        val versionUsed = reader.versionUsed(reference)
        val identityMatches = reader.permanentIdentityMatches(value, previous)
        val skuMatches = reader.skuMatches(signed)
        check(previous?.active?.revision?.value != Long.MAX_VALUE)
        // The original native time witness must exist. Audit recordedAt and today's
        // clock cannot reinterpret an expired original or complete missing qualification.
        val time = command.qualifiedSerialRegistrationTime() ?: return false
        command.requireRequirement(serialRegistrationRequirement(command.scope, value.registrationId,
            request.expectedActiveStorageRevision, command.operationId, command.semanticDigest))
        val resources = serialRegistrationResources(signed) + serialRegistrationResource(command.actorId) + reader.resources
        val refusal = serialRegistrationRefusal(request, previous, existing, versionUsed, identityMatches,
            native.latest(value.registrationId) == signed, skuMatches)
            ?: if (!time.contains(value.validFrom, value.validUntil)) "catalog.serial_registration.outside_interval" else null
        if (refusal != null) {
            // The kernel has no closed serial-rejection original profile. Preserve
            // the history hold rather than borrow another command's accepting join.
            return false
        }
        if (!original.committed) return false
        command.requireLegacyEffects(serialRegistrationApply(command.scope, command.operationId, command.actorId,
            command.semanticDigest, command.recordedAt, command.policyRevision, request, previous?.active?.revision), resources)
        return true
    }
}

/** Actual core-selected BEFORE rows have already joined their strictly earlier native producer. */
internal class HistoricalSerialRegistrationReader(private val command: RuntimeHistoricalCommand,
    publications: LocalCompanyBusinessPublications?, native: SerialRegistrationNativeCut) :
    SerialRegistrationReader(command.scope, publications, native) {
    override suspend fun select(key: AggregateKey): SerialRegistrationSourceRow? {
        val source = command.aggregateSource(key) ?: return null
        val original = (source.original as? RetainedOriginalV2.Legacy)?.evidence ?: error("Unsupported original serial activation producer")
        check(original.recordedAt <= command.recordedAt)
        return SerialRegistrationSourceRow(source.row, original)
    }
    override suspend fun configuration(key: AggregateKey): StoredAggregate? = command.aggregateSource(key)?.row
    override suspend fun claim(key: ClaimKey): DomainId? = command.claim(key)
}

/** Literal loan-bound original sources; sourceRow performs a complete scan to EOF. */
internal class RetainedSerialRegistrationReader(private val original: RetainedStreamedCommand,
    publications: LocalCompanyBusinessPublications?, native: SerialRegistrationNativeCut) :
    SerialRegistrationReader(original.identity.scope, publications, native) {
    override suspend fun select(key: AggregateKey): SerialRegistrationSourceRow? {
        val row = original.sourceRow(key) ?: return null
        val record = decodeSerialRegistrationRecord(scope, row)
        val producer = (original.original(record.producer.operationId) as? RetainedOriginalV2.Legacy)?.evidence
            ?: error("Literal serial activation original unavailable")
        check(record.recordedAt <= original.identity.recordedAt && producer.operationId != original.identity.producer.operationId)
        return SerialRegistrationSourceRow(row, producer)
    }
    override suspend fun configuration(key: AggregateKey): StoredAggregate? = stockReceivingCurrent(original, key)
    override suspend fun claim(key: ClaimKey): DomainId? {
        var observed = false; var owner: DomainId? = null
        original.frames { frame ->
            val value = (frame as? RetainedCommandFrameV2.Source)?.value
            if (value is StreamedSourceObservationV2.Claim && value.key == key) {
                check(!observed); observed = true; owner = value.ownerId
            }
        }
        check(observed) { "Literal serial ownership observation unavailable" }
        return owner
    }
}
