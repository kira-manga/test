package com.ukkera.pos.catalog.store

import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Authenticated complete contents selected by the existing original native owner. */
internal class SerialRegistrationNativeCut private constructor(
    val authority: LocalPublishedSerialAuthorityV1,
    val authorityActionId: DomainId,
    val authoritySigningKeyId: DomainId,
    val registrations: Map<DomainId, LocalSignedSerialRegistrationV1>,
    val skus: Map<DomainId, LocalSignedSkuV1>,
) {
    val authorityReference: LocalSerialAuthorityReferenceV1 = LocalSerialAuthorityPublicationCodecV1.reference(authority)

    fun requiresCandidate(signed: LocalSignedSerialRegistrationV1) {
        check(registrations[signed.definition.publicationId] == signed) { "Completed serial registration original unavailable" }
        val sku = skus[signed.definition.skuPublicationId] ?: error("Serial registration original SKU unavailable")
        check(sku.definition.skuId == signed.definition.skuId &&
            serialSkuDigest(sku) == signed.definition.skuPublicationDigest)
    }

    fun latest(registrationId: DomainId): LocalSignedSerialRegistrationV1? = registrations.values
        .filter { it.definition.registrationId == registrationId }.maxByOrNull { it.definition.revision }

    fun resources(signed: LocalSignedSerialRegistrationV1): Set<RecoveryResourceKey> = buildSet {
        addAll(serialRegistrationResources(signed))
        add(serialRegistrationResource(authority.authorityId)); add(serialRegistrationResource(authority.publicationId))
        add(serialRegistrationResource(authorityActionId)); add(serialRegistrationResource(authoritySigningKeyId))
        val origin = authority.origin
        listOf(origin.foundingActionId, origin.rootId, origin.rootKeyId, origin.headAuthorityId,
            origin.headEnrollmentId, origin.originalIssuerId, origin.originalIssuerKeyId,
            origin.originalAllocationId).forEach { add(serialRegistrationResource(it)) }
        registrations.values.filter { it.definition.registrationId == signed.definition.registrationId }.forEach {
            addAll(serialRegistrationResources(it))
            skus[it.definition.skuPublicationId]?.let { sku ->
                add(serialRegistrationResource(sku.signingKeyId)); addAll(localCatalogUnitResources(sku.definition))
                addAll(localCatalogProductComparisonResources(listOf(sku.definition)))
            }
        }
        val relevant = localCatalogProductRelevantDefinitions(skus.getValue(signed.definition.skuPublicationId).definition.productId,
            signed.definition.skuId, skus.values.map { it.definition })
        addAll(localCatalogProductComparisonResources(relevant))
        relevant.forEach { sku -> addAll(localCatalogUnitResources(sku)) }
    }

    companion object {
        fun current(scope: BranchScope, publications: LocalCompanyBusinessPublications?,
            snapshot: NativeCompanyBusinessPublicationSnapshot?): SerialRegistrationNativeCut {
            val observed = checkNotNull(snapshot) { "Serial registration native membership unavailable" }
            check(observed.company == scope.company() && observed.rootFingerprint == publications?.trust?.rootFingerprint)
            return read(scope, publications, observed.completedPublications)
        }

        fun read(scope: BranchScope, publications: LocalCompanyBusinessPublications?,
            outputs: List<CompanyBusinessPublicationOutput>): SerialRegistrationNativeCut {
            val owner = checkNotNull(publications) { "Serial registration native owner unavailable" }
            check(owner.trust.company == scope.company() && outputs.size <= CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS &&
                outputs.all { it.proposal.company == scope.company() && it.rootFingerprint == owner.trust.rootFingerprint } &&
                outputs.map { it.originalActionId }.distinct().size == outputs.size &&
                outputs.map { it.proposal.publicationId }.distinct().size == outputs.size &&
                outputs.map { it.signingKeyId }.distinct().size == outputs.size)
            val authorityOutput = outputs.singleOrNull { it.proposal.kind == CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1 }
                ?: error("Completed original sole serial authority unavailable")
            val authority = authorityOutput.readNativeSerialAuthorityDefinition()
            check(authority.origin.originalScope == scope && authority.company == scope.company())
            val authorityBytes = LocalSerialAuthorityPublicationCodecV1.canonicalPublication(authority)
            try { check(owner.trust.verify(scope.company(), authorityOutput.signingKeyId, authorityBytes, authorityOutput.signatureHex)) }
            finally { authorityBytes.fill(0) }
            val authorityReference = LocalSerialAuthorityPublicationCodecV1.reference(authority)
            val authorityIndex = outputs.indexOf(authorityOutput)
            val registrations = mutableMapOf<DomainId, LocalSignedSerialRegistrationV1>()
            val skus = mutableMapOf<DomainId, LocalSignedSkuV1>()
            val previous = mutableMapOf<DomainId, LocalSignedSerialRegistrationV1>()
            val serialOwners = mutableMapOf<DomainId, DomainId>()
            val codeOwners = mutableMapOf<String, DomainId>()
            for ((index, output) in outputs.withIndex()) when (output.proposal.kind) {
                CompanyBusinessPublicationKind.CATALOG_SKU_V1 -> {
                    val sku = output.readNativeCompanySku()
                    val bytes = LocalConfigurationCodec.canonicalPublication(sku.definition)
                    try { check(owner.trust.verify(scope.company(), sku.signingKeyId, bytes, sku.signatureHex)) }
                    finally { bytes.fill(0) }
                    check(skus.put(sku.definition.publicationId, sku) == null)
                }
                CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1 -> {
                    val signed = output.readNativeSerialRegistration()
                    val value = signed.definition
                    check(index > authorityIndex && value.scope == scope && value.serialAuthority == authorityReference &&
                        value.allocationId == authority.origin.originalAllocationId &&
                        value.allocationDigest == authority.origin.originalAllocationDigest)
                    check(authenticSerialRegistration(owner, scope, signed))
                    val sku = skus[value.skuPublicationId] ?: error("Earlier completed original serial SKU unavailable")
                    check(sku.definition.skuId == value.skuId && serialSkuDigest(sku) == value.skuPublicationDigest)
                    val before = previous[value.registrationId]
                    if (before == null) check(value.revision == Revision(1) && value.previous == null)
                    else {
                        check(sameSerialRegistrationPermanentIdentity(before.definition, value) &&
                            value.previous == LocalSerialRegistrationPublicationCodecV1.reference(before.definition) &&
                            value.revision == before.definition.revision.next())
                    }
                    val serialOwner = serialOwners.put(value.serialId, value.registrationId)
                    val codeOwner = codeOwners.put(value.serialCode, value.registrationId)
                    check((serialOwner == null || serialOwner == value.registrationId) &&
                        (codeOwner == null || codeOwner == value.registrationId))
                    check(previous.values.none { it.definition.registrationId == value.serialId || it.definition.serialId == value.registrationId })
                    check(registrations.put(value.publicationId, signed) == null)
                    previous[value.registrationId] = signed
                }
                else -> Unit
            }
            return SerialRegistrationNativeCut(authority, authorityOutput.originalActionId, authorityOutput.signingKeyId, registrations, skus)
        }
    }
}

internal fun authenticSerialRegistration(publications: LocalCompanyBusinessPublications?, scope: BranchScope,
    signed: LocalSignedSerialRegistrationV1): Boolean {
    if (signed.definition.scope != scope) return false
    val bytes = LocalSerialRegistrationPublicationCodecV1.canonicalPublication(signed.definition)
    return try { publications?.trust?.verify(scope.company(), signed.signingKeyId, bytes, signed.signatureHex) == true }
    finally { bytes.fill(0) }
}
internal fun serialSkuDigest(signed: LocalSignedSkuV1): PayloadDigest {
    val bytes = LocalConfigurationCodec.canonicalPublication(signed.definition)
    return try { sha256(bytes) } finally { bytes.fill(0) }
}
internal fun sameSerialRegistrationPermanentIdentity(before: LocalPublishedSerialRegistrationV1,
    after: LocalPublishedSerialRegistrationV1): Boolean = before.scope == after.scope &&
    before.allocationId == after.allocationId && before.allocationDigest == after.allocationDigest &&
    before.serialAuthority == after.serialAuthority && before.registrationId == after.registrationId &&
    before.serialId == after.serialId && before.serialCode == after.serialCode && before.skuId == after.skuId

/** The exact originally pinned SKU must be a validated active serialized whole-count unit. */
internal fun serialSkuEligible(sku: LocalPublishedSkuV1, native: SerialRegistrationNativeCut): Boolean {
    val base = sku.unitDefinitions?.singleOrNull { it.id == sku.baseUnitId } ?: return false
    if (!sku.active || sku.tracking != LocalTrackingV1.SERIAL || sku.quantityScale != 0 || base.dimension != UnitDimension.COUNT ||
        localCatalogUnitShapeRejection(sku) != null || localCatalogUnitHistoryRejection(native.skus.values.toList()) != null ||
        localCatalogProductShapeRejection(sku) != null) return false
    val relevant = localCatalogProductRelevantDefinitions(sku.productId, sku.skuId, native.skus.values.map { it.definition })
    val product = sku.productDefinition
    return localCatalogProductVersionHistoryRejection(relevant.mapNotNull { it.productDefinition }
        .filter { product != null && it.id == product.id && it.revision <= product.revision }) == null
}

internal fun serialRegistrationResource(id: DomainId): RecoveryResourceKey = RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, id)
internal fun serialRegistrationResources(signed: LocalSignedSerialRegistrationV1): Set<RecoveryResourceKey> = buildSet {
    val value = signed.definition
    listOf(value.registrationId, value.publicationId, value.serialId, value.skuId, value.skuPublicationId,
        value.allocationId, value.serialAuthority.authorityId, value.serialAuthority.publicationId, signed.signingKeyId)
        .forEach { add(serialRegistrationResource(it)) }
    value.previous?.let { add(serialRegistrationResource(it.publicationId)) }
    add(RecoveryResourceKey(RecoveryResourceKind.STOCK, value.serialId)); add(RecoveryResourceKey(RecoveryResourceKind.STOCK, value.skuId))
}
