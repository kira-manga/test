package com.ukkera.pos.security.independent.localserialprivate

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/**
 * Ephemeral syntax carriers for local-serial-private-request-v2 unit slices.
 * These IDs, reference digests and dates are not native allocations, authenticated
 * requester evidence, signatures or frozen native fixture mappings. The exact
 * frozen content strings are carried in a bounded printable field solely to
 * exercise canonical request comparison; no native publication is created.
 */
internal object LocalSerialPrivateInertInputs {
    val scope = BranchScope(id(1), id(2), id(3))
    val allocationDigest = PayloadDigest("1".repeat(64))
    val publication = id(4)
    val entity = id(5)
    val action = id(6)
    val otherCompany = id(7)
    val otherPublication = id(8)
    val otherEntity = id(9)
    const val contentA = "Synthetic local registration; branch=B1; location=East; currency=EGP; marker=LSPR-v1-A."
    const val contentB = "Synthetic local registration; branch=B1; location=West; currency=EGP; marker=LSPR-v1-B."

    fun definition(): LocalPublishedSerialRegistrationV1 = LocalPublishedSerialRegistrationV1(
        scope = scope, allocationId = id(10), allocationDigest = allocationDigest,
        serialAuthority = LocalSerialAuthorityReferenceV1(id(11), id(12),
            PayloadDigest("2".repeat(64)), PayloadDigest("3".repeat(64))),
        publicationId = publication, registrationId = entity, revision = Revision(1), previous = null,
        serialId = id(13), serialCode = contentA, skuId = id(14), skuPublicationId = id(15),
        skuPublicationDigest = PayloadDigest("4".repeat(64)),
        validFrom = UtcInstant.parseCanonical("2031-01-01T00:00:00Z"), validUntil = null,
    )

    fun origin(): NativeSerialPrivatePublicationRequestV1 =
        NativeSerialPrivatePublicationRequestCodecV1.origin(scope, allocationDigest,
            makeNativeSerialRegistrationProposal(definition()))

    fun access(origin: NativeSerialPrivatePublicationRequestV1,
        operation: NativeSerialPrivatePublicationOperationV1 = NativeSerialPrivatePublicationOperationV1.PREPARE,
        requestId: DomainId = id(20)): NativeSerialPrivatePublicationRequestV1 =
        NativeSerialPrivatePublicationRequestCodecV1.access(operation, requestId, origin,
            if (operation == NativeSerialPrivatePublicationOperationV1.REVIEW ||
                operation == NativeSerialPrivatePublicationOperationV1.RECOVER) action else null)

    /** Change one represented target field while preserving the retained origin lookup. */
    fun representedAccess(origin: NativeSerialPrivatePublicationRequestV1,
        scope: BranchScope = origin.scope,
        kind: CompanyBusinessPublicationKind = origin.kind,
        publication: DomainId = origin.publicationId,
        entity: DomainId = origin.entityId,
        contentDigest: PayloadDigest = origin.contentDigest): NativeSerialPrivatePublicationRequestV1 =
        NativeSerialPrivatePublicationRequestV1(NativeSerialPrivatePublicationOperationV1.REVIEW,
            id(21), scope, origin.originalAllocationDigest, kind, publication, entity, contentDigest,
            action, origin.requestId, NativeSerialPrivatePublicationRequestCodecV1.digest(origin), null)

    fun id(ordinal: Int): DomainId =
        DomainId("71000000-0000-4000-8000-" + ordinal.toString().padStart(12, '0'))
}
