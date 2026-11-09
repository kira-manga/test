package com.ukkera.pos.security.independent.localserialregistration

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CompanyBusinessPublicationKind
import com.ukkera.pos.security.CompanyBusinessPublicationPhase
import com.ukkera.pos.security.CompanyBusinessPublicationProposal
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.security.LocalCompanySerialIssuerOriginV1
import com.ukkera.pos.security.LocalPublishedSerialAuthorityV1
import com.ukkera.pos.security.LocalPublishedSerialRegistrationV1
import com.ukkera.pos.security.LocalSerialAuthorityPublicationCodecV1
import com.ukkera.pos.security.LocalSerialRegistrationPublicationCodecV1
import com.ukkera.pos.security.NativeCompanyBusinessPublicationHeadV1
import com.ukkera.pos.security.NativeCompanyBusinessPublicationRecordV1
import com.ukkera.pos.security.NativeCompanyBusinessPublicationStateV1
import com.ukkera.pos.security.NativeCompanySerialAuthorityCustodyV1
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SelfOwnedOctetsV1
import com.ukkera.pos.security.makeNativeSerialAuthorityProposal
import com.ukkera.pos.security.makeNativeSerialRegistrationProposal
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/**
 * Inert syntax scaffolding for local-serial-registration-v1 assertion slices.
 *
 * Requested identity/code/time values come from the frozen blueprints. Auxiliary
 * coordinates, digests and phase-shaped buffers are NOT founding, authentication,
 * signatures, native membership, a qualified clock or successful business facts.
 * Zero-filled completion-shaped buffers are byte-length fillers, not issued
 * signatures, endorsed keys or evidence of an authenticated original.
 * Nothing here is installed, signed, admitted or returned by a native facade.
 */
internal object LocalSerialRegistrationInertInputs {
    val company = CompanyScope(id("11000000-0000-4000-8000-000000000001"), id("31000000-0000-4000-8000-000000000001"))
    val scope = BranchScope(company.organizationId, company.companyId, id("41000000-0000-4000-8000-000000000001"))
    val registrationId = id("a1000000-0000-4000-8000-000000000001")
    val freshRegistrationId = id("a1000000-0000-4000-8000-000000000002")
    val serialId = id("c1000000-0000-4000-8000-000000000001")
    val freshSerialId = id("c1000000-0000-4000-8000-000000000002")
    const val exactCode = "000AbC-07"
    const val caseChangedCode = "000abc-07"
    const val zeroChangedCode = "0AbC-07"
    const val distinctCode = "000AbC-08"
    val skuId = id("d1000000-0000-4000-8000-000000000001")
    val otherSkuId = id("d1000000-0000-4000-8000-000000000002")
    val earlierSkuPublicationId = id("e1000000-0000-4000-8000-000000000003")
    val laterSkuPublicationId = id("e1000000-0000-4000-8000-000000000004")
    val otherSkuPublicationId = auxiliaryId(24)
    val predecessorPublicationId = id("b1000000-0000-4000-8000-000000000003")
    val successorPublicationId = id("b1000000-0000-4000-8000-000000000004")
    val validFrom = UtcInstant.parseCanonical("2031-04-05T12:00:00Z")
    val validUntil = UtcInstant.parseCanonical("2031-04-05T13:00:00Z")
    val beforeFrom = UtcInstant.parseCanonical("2031-04-05T11:59:59Z")
    val afterUntil = UtcInstant.parseCanonical("2031-04-05T13:00:01Z")
    val inertDigest = PayloadDigest("01".repeat(32))
    val differentInertDigest = PayloadDigest("02".repeat(32))

    fun authority(): LocalPublishedSerialAuthorityV1 = LocalPublishedSerialAuthorityV1(
        authorityId = auxiliaryId(1),
        publicationId = auxiliaryId(2),
        origin = LocalCompanySerialIssuerOriginV1(
            company = company,
            foundingActionId = auxiliaryId(3),
            rootId = auxiliaryId(4),
            rootKeyId = auxiliaryId(5),
            rootDocumentDigest = inertDigest,
            rootFingerprint = "0".repeat(64),
            headAuthorityId = auxiliaryId(6),
            headEnrollmentId = auxiliaryId(7),
            originalIssuerId = auxiliaryId(8),
            originalIssuerKeyId = auxiliaryId(9),
            originalIssuerDelegationDigest = inertDigest,
            originalScope = scope,
            originalAllocationId = auxiliaryId(10),
            originalAllocationDigest = inertDigest,
        ),
    )

    fun authorityRecord(
        phase: CompanyBusinessPublicationPhase = CompanyBusinessPublicationPhase.COMPLETED,
        definition: LocalPublishedSerialAuthorityV1 = authority(),
        actionId: DomainId = auxiliaryId(11),
        keyId: DomainId = auxiliaryId(12),
    ): NativeCompanyBusinessPublicationRecordV1 = structuralRecord(
        makeNativeSerialAuthorityProposal(definition), actionId, keyId, phase,
    )

    /** Opaque inert SKU content is deliberately not a native SKU publication/proof. */
    fun skuRecord(
        otherSku: Boolean = false,
        laterPublication: Boolean = false,
        phase: CompanyBusinessPublicationPhase = CompanyBusinessPublicationPhase.COMPLETED,
    ): NativeCompanyBusinessPublicationRecordV1 {
        val publicationId = when {
            otherSku -> otherSkuPublicationId
            laterPublication -> laterSkuPublicationId
            else -> earlierSkuPublicationId
        }
        val content = (CompanyBusinessPublicationKind.CATALOG_SKU_V1.domain + "INERT_CONTENT_NOT_A_NATIVE_SKU").encodeToByteArray()
        val proposal = try {
            CompanyBusinessPublicationProposal(company, CompanyBusinessPublicationKind.CATALOG_SKU_V1,
                publicationId, if (otherSku) otherSkuId else skuId, content)
        } finally { content.fill(0) }
        val offset = when { otherSku -> 2; laterPublication -> 1; else -> 0 }
        return structuralRecord(proposal, auxiliaryId(20 + offset), auxiliaryId(30 + offset), phase)
    }

    fun registration(
        registrationId: DomainId = this.registrationId,
        serialId: DomainId = this.serialId,
        code: String = exactCode,
        otherSku: Boolean = false,
        publicationId: DomainId = id("b1000000-0000-4000-8000-000000000001"),
        predecessor: LocalPublishedSerialRegistrationV1? = null,
    ): LocalPublishedSerialRegistrationV1 {
        val origin = authority().origin
        val sku = skuRecord(otherSku = otherSku)
        return LocalPublishedSerialRegistrationV1(
            scope = scope,
            allocationId = origin.originalAllocationId,
            allocationDigest = origin.originalAllocationDigest,
            serialAuthority = LocalSerialAuthorityPublicationCodecV1.reference(authority()),
            publicationId = publicationId,
            registrationId = registrationId,
            revision = predecessor?.revision?.next() ?: Revision(1),
            previous = predecessor?.let(LocalSerialRegistrationPublicationCodecV1::reference),
            serialId = serialId,
            serialCode = code,
            skuId = sku.entityId,
            skuPublicationId = sku.publicationId,
            skuPublicationDigest = sku.digest,
            validFrom = validFrom,
            validUntil = validUntil,
        )
    }

    /** The implementation's initial syntax is scaffolding, not a chosen native initial oracle. */
    fun historyThroughDesiredRevisionThree(): List<LocalPublishedSerialRegistrationV1> {
        val first = registration()
        val second = registration(publicationId = id("b1000000-0000-4000-8000-000000000002"), predecessor = first)
        val third = registration(publicationId = predecessorPublicationId, predecessor = second)
        return listOf(first, second, third)
    }

    fun successor(predecessor: LocalPublishedSerialRegistrationV1): LocalPublishedSerialRegistrationV1 = registration(
        publicationId = successorPublicationId, predecessor = predecessor,
    )

    fun registrationRecord(
        definition: LocalPublishedSerialRegistrationV1,
        phase: CompanyBusinessPublicationPhase = CompanyBusinessPublicationPhase.PREPARED,
        actionId: DomainId = auxiliaryId(40 + definition.revision.value.toInt()),
        keyId: DomainId = auxiliaryId(50 + definition.revision.value.toInt()),
    ): NativeCompanyBusinessPublicationRecordV1 = structuralRecord(
        makeNativeSerialRegistrationProposal(definition), actionId, keyId, phase,
    )

    fun prefixRecords(): List<NativeCompanyBusinessPublicationRecordV1> = listOf(
        authorityRecord(), skuRecord(), skuRecord(otherSku = true), skuRecord(laterPublication = true),
    )

    fun completedShapeHistory(
        lastPhase: CompanyBusinessPublicationPhase = CompanyBusinessPublicationPhase.COMPLETED,
    ): List<NativeCompanyBusinessPublicationRecordV1> = historyThroughDesiredRevisionThree().mapIndexed { index, value ->
        registrationRecord(value, if (index == 2) lastPhase else CompanyBusinessPublicationPhase.COMPLETED)
    }

    data class InertCustodyStep(
        val before: NativeCompanyBusinessPublicationStateV1,
        val settledBefore: NativeCompanySerialAuthorityCustodyV1,
        val next: NativeCompanyBusinessPublicationStateV1,
        val changed: NativeCompanyBusinessPublicationRecordV1,
        val pending: NativeCompanySerialAuthorityCustodyV1,
    )

    /** Pure PREPARED -> SIGNING_INTENT shapes; no signing or native settlement occurs. */
    fun custodySigningStep(): InertCustodyStep {
        val prepared = authorityRecord(phase = CompanyBusinessPublicationPhase.PREPARED)
        val before = NativeCompanyBusinessPublicationStateV1(0, listOf(prepared))
        val first = NativeCompanySerialAuthorityCustodyV1.firstIntent(null, before, prepared)
        val settledBefore = first.settleActualNext(before)
        val signing = authorityRecord(phase = CompanyBusinessPublicationPhase.SIGNING_INTENT)
        val next = before.next(signing)
        return InertCustodyStep(before, settledBefore, next, signing,
            settledBefore.beginNext(before, next, signing))
    }

    fun structuralRecord(
        proposal: CompanyBusinessPublicationProposal,
        actionId: DomainId,
        keyId: DomainId,
        phase: CompanyBusinessPublicationPhase,
    ): NativeCompanyBusinessPublicationRecordV1 {
        val content = proposal.copyCanonicalContent()
        return try {
            NativeCompanyBusinessPublicationRecordV1(
                originalActionId = actionId,
                company = proposal.company,
                kind = proposal.kind,
                publicationId = proposal.publicationId,
                entityId = proposal.entityId,
                keyId = keyId,
                content = SelfOwnedOctetsV1(content),
                digest = proposal.contentDigest,
                phase = phase,
                head = if (phase == CompanyBusinessPublicationPhase.PREPARED) null else NativeCompanyBusinessPublicationHeadV1(0, 0, 0, 0),
                publicKey = SelfOwnedOctetsV1(ByteArray(if (phase == CompanyBusinessPublicationPhase.PREPARED) 0 else 32)),
                contentSignature = SelfOwnedOctetsV1(ByteArray(if (phase == CompanyBusinessPublicationPhase.COMPLETED) 64 else 0)),
                rootEndorsement = SelfOwnedOctetsV1(ByteArray(if (phase == CompanyBusinessPublicationPhase.COMPLETED) 64 else 0)),
            )
        } finally { content.fill(0) }
    }

    fun auxiliaryId(number: Int): DomainId = id("71000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
    private fun id(value: String): DomainId = DomainId(value)
}
