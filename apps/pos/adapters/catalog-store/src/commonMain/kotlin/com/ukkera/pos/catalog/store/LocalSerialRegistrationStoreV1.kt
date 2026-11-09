package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRelationalSerialAuthority
import com.ukkera.pos.inventory.store.LocalStockSerialRegistration
import com.ukkera.pos.inventory.store.LocalStockSourceStamp
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One shared native-backed source for activation, protected inspection and stock/arrival consumers. */
public class LocalSerialRegistrationServices(private val publications: LocalCompanyBusinessPublications?) : LocalStockRelationalSerialAuthority {
    public val commands: List<MasterCommandHandler> = listOf(SerialRegistrationActivationHandler(publications))
    public val reads: List<MasterReadHandler> = listOf(SerialRegistrationReadHandler(publications))
    internal val history: RuntimeHistoryBusinessProducer = LocalSerialRegistrationHistoryProducer(publications)

    override suspend fun current(view: CommandReadView, serialId: DomainId): LocalStockSerialRegistration? {
        // A source selected for fresh receiving is this command's actual native cut,
        // never an arbitrary cached completed snapshot or an employee assertion.
        val native = SerialRegistrationNativeCut.current(view.scope, publications, view.originalNativeBusinessPublications())
        val reader = CurrentSerialRegistrationReader(view, publications, native)
        val active = reader.forSerialId(serialId) ?: return null
        val time = view.qualifiedSerialRegistrationTime() ?: return null
        if (!reader.skuMatches(active.signed) || !time.contains(active.signed.definition.validFrom, active.signed.definition.validUntil)) return null
        return reader.stockRegistration(active)
    }

    override suspend fun retained(original: RetainedStreamedCommand, serialId: DomainId): LocalStockSerialRegistration? {
        val native = SerialRegistrationNativeCut.read(original.identity.scope, publications, original.completedNativeBusinessPublicationsBefore())
        val reader = RetainedSerialRegistrationReader(original, publications, native)
        val active = reader.forSerialId(serialId) ?: return null
        val time = original.qualifiedSerialRegistrationTime() ?: error("Original qualified serial time unavailable")
        check(reader.skuMatches(active.signed) && time.contains(active.signed.definition.validFrom, active.signed.definition.validUntil))
        return reader.stockRegistration(active)
    }

    /** Actual native BEFORE sources for the installed arrival history producer. */
    internal suspend fun historical(command: RuntimeHistoricalCommand, serialId: DomainId): LocalStockSerialRegistration? {
        val native = SerialRegistrationNativeCut.read(command.scope, publications, command.completedNativeBusinessPublicationsBefore())
        val reader = HistoricalSerialRegistrationReader(command, publications, native)
        val active = reader.forSerialId(serialId) ?: return null
        val time = command.qualifiedSerialRegistrationTime() ?: error("Historical qualified serial time unavailable")
        check(reader.skuMatches(active.signed) && time.contains(active.signed.definition.validFrom, active.signed.definition.validUntil))
        return reader.stockRegistration(active)
    }
}

@Serializable
internal data class SerialRegistrationProducer(val operationId: OperationId, val actorId: DomainId,
    val command: String, val semanticDigest: PayloadDigest)
@Serializable
internal sealed interface SerialRegistrationBody {
    @Serializable @SerialName("ACTIVE") data class Active(val reference: LocalSerialRegistrationReferenceV1) : SerialRegistrationBody
    @Serializable @SerialName("PUBLICATION") data class Publication(val request: LocalActivateSerialRegistrationV1) : SerialRegistrationBody
}
@Serializable
internal data class SerialRegistrationRecord(val scope: BranchScope, val kind: String, val id: DomainId,
    val storageRevision: Revision, val producer: SerialRegistrationProducer, val recordedAt: UtcInstant,
    val employeePolicyRevision: Revision, val body: SerialRegistrationBody) {
    override fun toString(): String = "SerialRegistrationRecord([REDACTED])"
}

internal const val SERIAL_REGISTRATION_CLAIM: String = "catalog.serial.registration"
internal const val SERIAL_PUBLICATION_CLAIM: String = "catalog.serial.publication"
internal const val SERIAL_VERSION_CLAIM: String = "catalog.serial.version"
internal const val SERIAL_IDENTITY_CLAIM: String = "catalog.serial.identity"
internal const val SERIAL_CODE_CLAIM: String = "catalog.serial.code"
internal fun serialRegistrationKey(kind: String, id: DomainId): AggregateKey = AggregateKey(kind, id)
internal fun serialRegistrationClaim(id: DomainId): ClaimKey = ClaimKey(SERIAL_REGISTRATION_CLAIM, id.value)
internal fun serialPublicationClaim(id: DomainId): ClaimKey = ClaimKey(SERIAL_PUBLICATION_CLAIM, id.value)
internal fun serialVersionClaim(reference: LocalSerialRegistrationReferenceV1): ClaimKey =
    ClaimKey(SERIAL_VERSION_CLAIM, "${reference.registrationId.value}/${reference.revision.value}")
internal fun serialIdentityClaim(id: DomainId): ClaimKey = ClaimKey(SERIAL_IDENTITY_CLAIM, id.value)
/** ASCII index locator only. Every lookup joins the full exact code and permanent namespace. */
internal fun serialExactCodeClaim(code: String): ClaimKey {
    val bytes = code.encodeToByteArray(throwOnInvalidSequence = true)
    return try { ClaimKey(SERIAL_CODE_CLAIM, sha256(bytes).value) } finally { bytes.fill(0) }
}
internal fun serialRegistrationRequirement(scope: BranchScope, registrationId: DomainId, expected: Revision?,
    operationId: OperationId, digest: PayloadDigest): AuthorizationRequirement = AuthorizationRequirement(
    Permission(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER), scope,
    ActionBinding(registrationId, expected ?: Revision(0), digest, operationId, "catalog.serial_registration.activate"))
internal fun serialRegistrationResult(code: String, reference: LocalSerialRegistrationReferenceV1,
    storageRevision: Revision?): StoredBytes = LocalSerialRegistrationCodecV1.encodeResult(
        LocalSerialRegistrationResultV1(code, reference.registrationId, reference.publicationId, storageRevision))
internal fun serialRegistrationClaims(value: LocalPublishedSerialRegistrationV1, firstActivation: Boolean): List<ClaimChange> = buildList {
    val reference = LocalSerialRegistrationPublicationCodecV1.reference(value)
    add(ClaimChange.Acquire(serialPublicationClaim(value.publicationId), value.publicationId))
    add(ClaimChange.Acquire(serialVersionClaim(reference), value.publicationId))
    if (firstActivation) {
        add(ClaimChange.Acquire(serialRegistrationClaim(value.registrationId), value.registrationId))
        add(ClaimChange.Acquire(serialIdentityClaim(value.serialId), value.registrationId))
        add(ClaimChange.Acquire(serialExactCodeClaim(value.serialCode), value.registrationId))
    }
}.sortedWith(compareBy<ClaimChange> { it.key.namespace }.thenBy { it.key.key })

internal fun decodeSerialRegistrationRecord(scope: BranchScope, row: StoredAggregate): SerialRegistrationRecord {
    check(row.schema == LocalSerialRegistrationProfileV1.schema(row.key.kind) && sha256(row.payload.copyBytes()) == row.digest)
    val record = serialRegistrationDecode(row.payload, SerialRegistrationRecord.serializer())
    check(record.scope == scope && record.kind == row.key.kind && record.id == row.key.id && record.storageRevision == row.revision &&
        row.revision.value >= 1 && serialRegistrationEncode(record, SerialRegistrationRecord.serializer()) == row.payload)
    check(when (val body = record.body) {
        is SerialRegistrationBody.Active -> record.kind == LocalSerialRegistrationProfileV1.ACTIVE && record.id == body.reference.registrationId
        is SerialRegistrationBody.Publication -> record.kind == LocalSerialRegistrationProfileV1.PUBLICATION &&
            record.id == body.request.publication.definition.publicationId && record.storageRevision == Revision(1) &&
            body.request.publication.definition.scope == scope
    })
    return record
}
private fun serialRegistrationChange(scope: BranchScope, operationId: OperationId, actorId: DomainId,
    digest: PayloadDigest, recordedAt: UtcInstant, policyRevision: Revision, kind: String, id: DomainId,
    before: Revision?, body: SerialRegistrationBody): AggregateChange = AggregateChange(serialRegistrationKey(kind, id), before,
    LocalSerialRegistrationProfileV1.schema(kind), serialRegistrationEncode(SerialRegistrationRecord(scope, kind, id,
        before?.next() ?: Revision(1), SerialRegistrationProducer(operationId, actorId, LocalSerialRegistrationProfileV1.ACTIVATE.name, digest),
        recordedAt, policyRevision, body), SerialRegistrationRecord.serializer()))
internal fun serialRegistrationApply(scope: BranchScope, operationId: OperationId, actorId: DomainId, digest: PayloadDigest,
    recordedAt: UtcInstant, policyRevision: Revision, request: LocalActivateSerialRegistrationV1, before: Revision?): ProposedOutcome.Apply {
    val value = request.publication.definition
    val reference = LocalSerialRegistrationPublicationCodecV1.reference(value)
    return ProposedOutcome.Apply(serialRegistrationResult("catalog.serial_registration.activated", reference, before?.next() ?: Revision(1)),
        listOf(serialRegistrationChange(scope, operationId, actorId, digest, recordedAt, policyRevision,
            LocalSerialRegistrationProfileV1.ACTIVE, value.registrationId, before, SerialRegistrationBody.Active(reference)),
            serialRegistrationChange(scope, operationId, actorId, digest, recordedAt, policyRevision,
                LocalSerialRegistrationProfileV1.PUBLICATION, value.publicationId, null, SerialRegistrationBody.Publication(request)))
            .sortedWith(compareBy<AggregateChange> { it.key.kind }.thenBy { it.key.id.value }),
        serialRegistrationClaims(value, before == null))
}

/** Exact inert retained effects. Genuine native membership and original time have separate joins. */
internal fun requireSerialRegistrationProducer(evidence: RetainedCommandEvidence, publications: LocalCompanyBusinessPublications?) {
    check(evidence.committed && evidence.command == LocalSerialRegistrationProfileV1.ACTIVATE &&
        evidence.nativeOriginalProvenance == null && evidence.jobs.isEmpty() && evidence.aggregateWrites.size == 2 &&
        semanticDigest(evidence.scope, evidence.originalActorId, evidence.command, evidence.normalized) == evidence.semanticDigest)
    val request = LocalSerialRegistrationCodecV1.decode(evidence.normalized)
    check(LocalSerialRegistrationCodecV1.encode(request) == evidence.normalized &&
        authenticSerialRegistration(publications, evidence.scope, request.publication))
    val expected = serialRegistrationApply(evidence.scope, evidence.operationId, evidence.originalActorId, evidence.semanticDigest,
        evidence.recordedAt, evidence.policyRevision, request, request.expectedActiveStorageRevision)
    check(evidence.result == expected.result && evidence.aggregateWrites.zip(expected.changes).all { (actual, wanted) ->
        actual.key == wanted.key && actual.schema == wanted.schema && actual.payload == wanted.payload &&
            actual.payloadDigest == sha256(wanted.payload.copyBytes()) &&
            actual.storageGeneration == (wanted.expectedRevision?.next() ?: Revision(1)) })
    check(evidence.claimChanges.size == expected.claims.size && evidence.claimChanges.zip(expected.claims).all { (actual, wanted) ->
        actual.action == RetainedClaimAction.ACQUIRE && actual.key == wanted.key && actual.ownerId == wanted.ownerId })
}

internal data class LoadedSerialRegistration(val active: StoredAggregate, val activeRecord: SerialRegistrationRecord,
    val publication: StoredAggregate, val publicationRecord: SerialRegistrationRecord, val signed: LocalSignedSerialRegistrationV1) {
    val reference: LocalSerialRegistrationReferenceV1 get() = (activeRecord.body as SerialRegistrationBody.Active).reference
}
internal data class SerialRegistrationSourceRow(val row: StoredAggregate, val original: RetainedCommandEvidence)

/** Source selection is implemented by the real current/BEFORE/frame owners; no manufactured view. */
internal abstract class SerialRegistrationReader(protected val scope: BranchScope,
    protected val publications: LocalCompanyBusinessPublications?, protected val native: SerialRegistrationNativeCut) {
    val resources: MutableSet<RecoveryResourceKey> = mutableSetOf(serialRegistrationResource(scope.branchId))
    protected abstract suspend fun select(key: AggregateKey): SerialRegistrationSourceRow?
    protected abstract suspend fun configuration(key: AggregateKey): StoredAggregate?
    protected abstract suspend fun claim(key: ClaimKey): DomainId?

    suspend fun row(kind: String, id: DomainId): Pair<StoredAggregate, SerialRegistrationRecord>? {
        resources += serialRegistrationResource(id)
        val ownership = when (kind) {
            LocalSerialRegistrationProfileV1.ACTIVE -> serialRegistrationClaim(id)
            LocalSerialRegistrationProfileV1.PUBLICATION -> serialPublicationClaim(id)
            else -> error("Unsupported serial registration source")
        }
        val source = select(serialRegistrationKey(kind, id))
        if (source == null) { check(claim(ownership) == null); return null }
        val record = decodeSerialRegistrationRecord(scope, source.row)
        val original = source.original
        requireSerialRegistrationProducer(original, publications)
        check(record.producer == SerialRegistrationProducer(original.operationId, original.originalActorId, original.command.name, original.semanticDigest))
        val write = original.aggregateWrites.single { it.key == source.row.key }
        check(write.storageGeneration == source.row.revision && write.schema == source.row.schema &&
            write.payload == source.row.payload && write.payloadDigest == source.row.digest)
        resources += serialRegistrationResource(record.producer.actorId)
        when (val body = record.body) {
            is SerialRegistrationBody.Active -> check(claim(ownership) == body.reference.registrationId)
            is SerialRegistrationBody.Publication -> {
                val value = body.request.publication.definition
                native.requiresCandidate(body.request.publication)
                resources += native.resources(body.request.publication)
                check(claim(ownership) == id && claim(serialVersionClaim(LocalSerialRegistrationPublicationCodecV1.reference(value))) == id &&
                    claim(serialRegistrationClaim(value.registrationId)) == value.registrationId &&
                    claim(serialIdentityClaim(value.serialId)) == value.registrationId &&
                    claim(serialExactCodeClaim(value.serialCode)) == value.registrationId)
            }
        }
        return source.row to record
    }
    suspend fun active(registrationId: DomainId): LoadedSerialRegistration? {
        val (active, record) = row(LocalSerialRegistrationProfileV1.ACTIVE, registrationId) ?: return null
        val reference = (record.body as SerialRegistrationBody.Active).reference
        val (published, original) = row(LocalSerialRegistrationProfileV1.PUBLICATION, reference.publicationId)
            ?: error("Active serial registration original unavailable")
        val signed = (original.body as SerialRegistrationBody.Publication).request.publication
        check(reference == LocalSerialRegistrationPublicationCodecV1.reference(signed.definition) &&
            record.producer == original.producer && record.recordedAt == original.recordedAt &&
            record.employeePolicyRevision == original.employeePolicyRevision)
        return LoadedSerialRegistration(active, record, published, original, signed)
    }
    suspend fun forSerialId(serialId: DomainId): LoadedSerialRegistration? {
        resources += serialRegistrationResource(serialId)
        val registrationId = claim(serialIdentityClaim(serialId)) ?: return null
        val active = active(registrationId) ?: error("Reserved serial registration lost its active original")
        check(active.signed.definition.serialId == serialId)
        return active
    }
    suspend fun versionUsed(reference: LocalSerialRegistrationReferenceV1): Boolean {
        val owner = claim(serialVersionClaim(reference)) ?: return false
        val named = row(LocalSerialRegistrationProfileV1.PUBLICATION, owner) ?: error("Serial version original lost")
        val value = (named.second.body as SerialRegistrationBody.Publication).request.publication.definition
        check(value.registrationId == reference.registrationId && value.revision == reference.revision)
        return true
    }
    suspend fun permanentIdentityMatches(value: LocalPublishedSerialRegistrationV1, previous: LoadedSerialRegistration?): Boolean {
        if (previous != null) return sameSerialRegistrationPermanentIdentity(previous.signed.definition, value)
        val serialOwner = claim(serialIdentityClaim(value.serialId))
        val codeOwner = claim(serialExactCodeClaim(value.serialCode))
        if (serialOwner != null || codeOwner != null) {
            for (owner in listOfNotNull(serialOwner, codeOwner).distinct()) active(owner)
                ?: error("Permanent serial identity owner lost")
            return false
        }
        return true
    }
    suspend fun skuMatches(signed: LocalSignedSerialRegistrationV1): Boolean {
        native.requiresCandidate(signed)
        resources += native.resources(signed)
        val value = signed.definition
        val expected = native.skus.getValue(value.skuPublicationId)
        val currentRow = configuration(LocalConfigurationProfile.key(LocalConfigurationProfile.SKU, value.skuId))
        val publishedRow = configuration(LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION, value.skuPublicationId))
        val current = currentRow?.let { decodeRecord(scope, it) }
        val published = publishedRow?.let { decodeRecord(scope, it) }
        if (current == null) {
            check(published == null) { "Serial SKU lost its active branch pointer" }
            return false
        }
        val actual = current.signedSku()
        check(native.skus[actual.definition.publicationId] == actual)
        val actualPublished = if (actual.definition.publicationId == value.skuPublicationId) published else
            configuration(LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION, actual.definition.publicationId))?.let { decodeRecord(scope, it) }
        check(actualPublished != null && actualPublished.revision == Revision(0) && actualPublished.signedSku() == actual)
        resources += serialRegistrationResource(current.authorizedBy); resources += serialRegistrationResource(actualPublished.authorizedBy)
        published?.let { check(it.revision == Revision(0) && it.signedSku() == expected); resources += serialRegistrationResource(it.authorizedBy) }
        for (identity in listOf("sku-code/${actual.definition.code}") + actual.definition.barcodes.map { "barcode/$it" })
            check(claim(ClaimKey(LocalConfigurationProfile.SOURCE_CLAIM, identity)) == actual.definition.skuId)
        if (actual != expected || published == null || !serialSkuEligible(expected.definition, native)) return false
        val previous = value.previous?.let { native.registrations[it.publicationId] }?.definition
        if (previous != null && previous.skuPublicationId != value.skuPublicationId) {
            val oldSku = native.skus.getValue(previous.skuPublicationId)
            if (expected.definition.skuRevision <= oldSku.definition.skuRevision) return false
        }
        return true
    }
    suspend fun stockRegistration(active: LoadedSerialRegistration): LocalStockSerialRegistration {
        val value = active.signed.definition
        val sku = checkNotNull(configuration(LocalConfigurationProfile.key(LocalConfigurationProfile.SKU, value.skuId)))
        val skuPublication = checkNotNull(configuration(LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION, value.skuPublicationId)))
        val sources = listOf(active.active, active.publication, sku, skuPublication).map { LocalStockSourceStamp(it.key, it.revision, it.digest) }
            .distinct().sortedWith(compareBy<LocalStockSourceStamp> { it.key.kind }.thenBy { it.key.id.value })
        return LocalStockSerialRegistration(scope, value.serialId, value.skuId, value.serialCode, value.registrationId,
            value.revision, native.authority.origin.originalIssuerId, value.publicationId, value.validFrom, value.validUntil, sources)
    }
}

internal class CurrentSerialRegistrationReader(private val view: CommandReadView, publications: LocalCompanyBusinessPublications?,
    native: SerialRegistrationNativeCut) : SerialRegistrationReader(view.scope, publications, native) {
    override suspend fun select(key: AggregateKey): SerialRegistrationSourceRow? {
        val row = view.aggregate(key) ?: return null
        val record = decodeSerialRegistrationRecord(scope, row)
        val original = view.retainedCommand(record.producer.operationId) ?: error("Serial activation producer unavailable")
        return SerialRegistrationSourceRow(row, original)
    }
    override suspend fun configuration(key: AggregateKey): StoredAggregate? = view.aggregate(key)
    override suspend fun claim(key: ClaimKey): DomainId? = view.claim(key)
}

internal fun serialRegistrationRefusal(request: LocalActivateSerialRegistrationV1, previous: LoadedSerialRegistration?,
    existing: Boolean, versionUsed: Boolean, permanentIdentityMatches: Boolean, latest: Boolean, skuMatches: Boolean): String? = when {
    previous?.active?.revision != request.expectedActiveStorageRevision -> "catalog.serial_registration.stale_revision"
    existing || versionUsed || !permanentIdentityMatches -> "catalog.serial_registration.publication_already_used"
    !latest || previous?.let { request.publication.definition.revision <= it.reference.revision } == true -> "catalog.serial_registration.stale_publication"
    !skuMatches -> "catalog.serial_registration.sku_source_changed"
    else -> null
}

private class SerialRegistrationActivationHandler(private val publications: LocalCompanyBusinessPublications?) : MasterCommandHandler {
    override val key: CommandKey = LocalSerialRegistrationProfileV1.ACTIVATE
    override val writableKinds: Set<String> = setOf(LocalSerialRegistrationProfileV1.ACTIVE, LocalSerialRegistrationProfileV1.PUBLICATION)
    override val claimNamespaces: Set<String> = setOf(SERIAL_REGISTRATION_CLAIM, SERIAL_PUBLICATION_CLAIM, SERIAL_VERSION_CLAIM,
        SERIAL_IDENTITY_CLAIM, SERIAL_CODE_CLAIM)
    override fun normalize(input: StoredBytes): StoredBytes = LocalSerialRegistrationCodecV1.encode(LocalSerialRegistrationCodecV1.decode(input))
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key && semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = LocalSerialRegistrationCodecV1.decode(normalized)
        val signed = request.publication; val value = signed.definition
        val reference = LocalSerialRegistrationPublicationCodecV1.reference(value)
        val requirement = serialRegistrationRequirement(context.scope, value.registrationId, request.expectedActiveStorageRevision,
            context.operationId, context.semanticDigest)
        val resources = (serialRegistrationResources(signed) + serialRegistrationResource(context.originalActorId) +
            serialRegistrationResource(view.scope.branchId)).toMutableSet()
        var held = false; var refusal: String? = null; var previous: LoadedSerialRegistration? = null
        var time: QualifiedSerialRegistrationTime? = null
        try {
            val native = SerialRegistrationNativeCut.current(view.scope, publications, view.originalNativeBusinessPublications())
            native.requiresCandidate(signed)
            val reader = CurrentSerialRegistrationReader(view, publications, native)
            previous = reader.active(value.registrationId)
            val existing = reader.row(LocalSerialRegistrationProfileV1.PUBLICATION, value.publicationId) != null
            val versionUsed = reader.versionUsed(reference)
            val identityMatches = reader.permanentIdentityMatches(value, previous)
            val skuMatches = reader.skuMatches(signed)
            resources += reader.resources
            refusal = serialRegistrationRefusal(request, previous, existing, versionUsed, identityMatches,
                native.latest(value.registrationId) == signed, skuMatches)
            time = view.qualifiedSerialRegistrationTime()
            // OS audit time and caller timestamps cannot qualify this native interval.
            if (time == null || previous?.active?.revision?.value == Long.MAX_VALUE) held = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IllegalStateException) { held = true }
        catch (_: IllegalArgumentException) { held = true }
        return object : PreparedMutation() {
            override val requirement: AuthorizationRequirement = requirement
            override val affectedResources: Set<RecoveryResourceKey> = resources.toSet()
            override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = setOf(RecoveryResourceKind.AUTHORIZATION)
            override val admissionRestriction: PreparedMutationAdmissionRestriction? = if (held) PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE else null
            override val allowsActionApproval: Boolean = false
            override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
                check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.isBoundTo(requirement, context.originalActorId) && !held)
                refusal?.let { return ProposedOutcome.Reject(it, serialRegistrationResult(it, reference, null)) }
                if (!checkNotNull(time).contains(value.validFrom, value.validUntil)) {
                    val code = "catalog.serial_registration.outside_interval"
                    return ProposedOutcome.Reject(code, serialRegistrationResult(code, reference, null))
                }
                return serialRegistrationApply(context.scope, context.operationId, context.originalActorId, context.semanticDigest,
                    recordedAt, authorization.policyRevision, request, previous?.active?.revision)
            }
        }
    }
}

private class SerialRegistrationReadHandler(private val publications: LocalCompanyBusinessPublications?) : MasterReadHandler {
    override val key: ReadKey = LocalSerialRegistrationProfileV1.READ
    override fun normalize(input: StoredBytes): StoredBytes = LocalSerialRegistrationCodecV1.query(LocalSerialRegistrationCodecV1.decodeQuery(input)).payload
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == key && queryDigest(context.scope, context.actorId, key, normalized) == context.semanticDigest)
        val query = LocalSerialRegistrationCodecV1.decodeQuery(normalized)
        val native = SerialRegistrationNativeCut.current(view.scope, publications, view.originalNativeBusinessPublications())
        val reader = CurrentSerialRegistrationReader(view, publications, native)
        val active = reader.active(query.registrationId)
        val publication = query.publicationId?.let { reader.row(LocalSerialRegistrationProfileV1.PUBLICATION, it)?.second?.body }
            ?.let { (it as SerialRegistrationBody.Publication).request.publication }
        val invalidSelection = publication != null && publication.definition.registrationId != query.registrationId
        val qualification = when {
            active == null -> LocalSerialRegistrationQualificationV1.NOT_ACTIVATED
            !reader.skuMatches(active.signed) -> LocalSerialRegistrationQualificationV1.SKU_SOURCE_CHANGED
            else -> {
                val time = view.qualifiedSerialRegistrationTime()
                when {
                    time == null -> LocalSerialRegistrationQualificationV1.TIME_UNAVAILABLE
                    !time.contains(active.signed.definition.validFrom, active.signed.definition.validUntil) -> LocalSerialRegistrationQualificationV1.OUTSIDE_INTERVAL
                    else -> LocalSerialRegistrationQualificationV1.QUALIFIED
                }
            }
        }
        val result = if (invalidSelection) null else LocalSerialRegistrationReadReplyV1(
            query.registrationId, active?.active?.revision, active?.signed, qualification, publication)
        val requirement = AuthorizationRequirement(Permission(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER), context.scope,
            ActionBinding(query.registrationId, active?.active?.revision ?: Revision(0), context.semanticDigest, reasonCode = "catalog.serial_registration.read"))
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> = listOf(requirement)
            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                check(authorizations.size == 1 && authorizations.single().principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
                    authorizations.single().isBoundTo(requirement, context.actorId))
                // Return known selection failure only after current protected access is checked.
                return LocalSerialRegistrationCodecV1.encodeRead(result ?: throw MasterReadInvalidSelection())
            }
        }
    }
}
