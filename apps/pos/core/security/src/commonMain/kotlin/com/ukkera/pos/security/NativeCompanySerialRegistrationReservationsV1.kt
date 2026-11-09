package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/**
 * Validation of the actual authenticated immutable native ledger, including pending and
 * uncertain reservations. This pure check cannot allocate a namespace, release a claim,
 * supply native completeness, authorize a human or complete an interrupted signing intent.
 */
internal fun requireNativeSerialRegistrationReservationsV1(records: List<NativeCompanyBusinessPublicationRecordV1>) {
    val serials = records.filter { it.kind == CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1 }
    val authorities = records.filter { it.kind == CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1 }
    if (serials.isEmpty() && authorities.isEmpty()) return // Old native bytes/semantics remain unchanged.
    require(authorities.size == 1) { "One permanent original serial authority required" }
    val authorityRecord = authorities.single()
    val authority = authorityRecord.proposal().readSerialAuthorityDefinition()
    val authorityReference = LocalSerialAuthorityPublicationCodecV1.reference(authority)
    val authorityIndex = records.indexOf(authorityRecord)
    val byRegistration = mutableMapOf<DomainId, Pair<NativeCompanyBusinessPublicationRecordV1, LocalPublishedSerialRegistrationV1>>()
    val serialOwners = mutableMapOf<DomainId, DomainId>()
    val codeOwners = mutableMapOf<Pair<CompanyScope, String>, DomainId>()
    val nativeIds = records.flatMap { listOf(it.originalActionId, it.keyId, it.publicationId) }.toSet()
    val nonserialEntities = records.filter { it.kind != CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1 }
        .map { it.entityId }.toSet()
    require(authority.authorityId !in nativeIds && records.none {
        it !== authorityRecord && it.entityId == authority.authorityId
    }) { "Serial authority identity collides with a retained native identity" }
    for ((index, record) in records.withIndex()) {
        if (record.kind != CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1) continue
        val value = record.proposal().readSerialRegistrationDefinition()
        require(authorityRecord.phase == CompanyBusinessPublicationPhase.COMPLETED && authorityIndex < index &&
            value.serialAuthority == authorityReference && value.company == authority.company &&
            value.scope == authority.origin.originalScope && value.allocationId == authority.origin.originalAllocationId &&
            value.allocationDigest == authority.origin.originalAllocationDigest) { "Earlier completed original sole issuer required" }
        require(value.registrationId !in nativeIds && value.serialId !in nativeIds &&
            value.registrationId !in nonserialEntities && value.serialId !in nonserialEntities)
        val serialOwner = serialOwners.put(value.serialId, value.registrationId)
        val codeOwner = codeOwners.put(value.company to value.serialCode, value.registrationId)
        require((serialOwner == null || serialOwner == value.registrationId) &&
            (codeOwner == null || codeOwner == value.registrationId)) { "Serial identity is permanently reserved" }
        require(byRegistration.values.none { (_, known) -> known.serialId == value.registrationId || known.registrationId == value.serialId })
        val previous = byRegistration[value.registrationId]
        if (previous == null) {
            require(value.revision == Revision(1) && value.previous == null)
        } else {
            val (beforeRecord, before) = previous
            require(beforeRecord.phase == CompanyBusinessPublicationPhase.COMPLETED &&
                sameSerialRegistrationIdentity(before, value) &&
                value.previous == LocalSerialRegistrationPublicationCodecV1.reference(before) &&
                value.revision == before.revision.next()) { "Serial registration predecessor or identity changed" }
        }
        // The selected original SKU MUST already be a real completed member in this ledger.
        val sku = records.take(index).singleOrNull { it.publicationId == value.skuPublicationId }
        require(sku != null && sku.phase == CompanyBusinessPublicationPhase.COMPLETED &&
            sku.kind == CompanyBusinessPublicationKind.CATALOG_SKU_V1 && sku.company == value.company &&
            sku.entityId == value.skuId && sku.digest == value.skuPublicationDigest) { "Completed original SKU publication required" }
        byRegistration[value.registrationId] = record to value
    }
}

/** Every cross-ledger preflight must reserve the actual serial ID as well as its registration. */
internal fun NativeCompanyBusinessPublicationRecordV1.reservedIdentityIdsV1(): List<DomainId> =
    listOf(originalActionId, keyId, publicationId, entityId) +
        if (kind == CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1)
            listOf(proposal().readSerialRegistrationDefinition().serialId) else emptyList()
