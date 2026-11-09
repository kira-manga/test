package com.ukkera.pos.security.independent.localserialprivate

import com.ukkera.pos.security.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bounded request DATA observations; see README.md for frozen assertion limits. */
class NativeSerialPrivateRequestRepresentationSlicesTest {
    private val inputs = LocalSerialPrivateInertInputs

    @Test
    fun lspr001ExactOriginKeepsPinnedProposalAfterCallerBufferMutation() {
        val definition = inputs.definition()
        val canonical = LocalSerialRegistrationPublicationCodecV1.canonicalPublication(definition)
        val pinned = canonical.copyOf()
        val proposal = CompanyBusinessPublicationProposal(inputs.scope.company(),
            CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1, inputs.publication, inputs.entity, canonical)
        val original = NativeSerialPrivatePublicationRequestCodecV1.origin(inputs.scope, inputs.allocationDigest, proposal)
        canonical.fill(0)
        proposal.discardUndeliveredNativeContent()

        val encoded = NativeSerialPrivatePublicationRequestCodecV1.encode(original)
        val decoded = NativeSerialPrivatePublicationRequestCodecV1.decode(encoded)
        encoded.fill(0)
        assertEquals(inputs.scope, decoded.scope)
        assertEquals(CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1, decoded.kind)
        assertEquals(inputs.publication, decoded.publicationId)
        assertEquals(inputs.entity, decoded.entityId)
        assertEquals(sha256(pinned), decoded.contentDigest)
        assertContentEquals(pinned, decoded.originalProposal().copyCanonicalContent())
        assertNull(decoded.originRequestId)
        assertNull(decoded.originalActionId)
        pinned.fill(0)
    }

    @Test
    fun lspr001MissingOriginAndOriginIdentityReuseCannotBecomeAccess() {
        val original = inputs.origin()
        for (operation in listOf(NativeSerialPrivatePublicationOperationV1.PREPARE,
            NativeSerialPrivatePublicationOperationV1.STATUS,
            NativeSerialPrivatePublicationOperationV1.REVIEW,
            NativeSerialPrivatePublicationOperationV1.RECOVER)) {
            assertFailsWith<IllegalArgumentException> {
                inputs.access(original, operation, original.requestId)
            }
        }
        val bytes = NativeSerialPrivatePublicationRequestCodecV1.encode(inputs.access(original))
        assertFailsWith<IllegalArgumentException> {
            NativeSerialPrivatePublicationRequestCodecV1.decode(bytes.copyOf(bytes.size - 1))
        }
        assertEquals(inputs.publication, original.publicationId)
    }

    @Test
    fun lspr003ChangedCompanyKindOrPublicationCannotUsePinnedOriginAnchor() {
        val original = inputs.origin()
        val before = NativeSerialPrivatePublicationRequestCodecV1.encode(original)
        assertFailsWith<IllegalArgumentException> {
            inputs.representedAccess(original, scope = original.scope.copy(companyId = inputs.otherCompany))
        }
        assertFailsWith<IllegalArgumentException> {
            inputs.representedAccess(original, kind = CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1)
        }
        assertFailsWith<IllegalArgumentException> {
            inputs.representedAccess(original, publication = inputs.otherPublication)
        }
        assertContentEquals(before, NativeSerialPrivatePublicationRequestCodecV1.encode(original))
    }

    @Test
    fun lspr003ChangedEntityOrContentDoesNotCompareAsPinnedOriginal() {
        val original = inputs.origin()
        val changedContent = makeNativeSerialRegistrationProposal(inputs.definition().copy(serialCode = inputs.contentB))
        assertTrue(original.sameTarget(inputs.representedAccess(original)))
        assertFalse(original.sameTarget(inputs.representedAccess(original, entity = inputs.otherEntity)))
        assertFalse(original.sameTarget(inputs.representedAccess(original, contentDigest = changedContent.contentDigest)))
        assertEquals(inputs.contentA, original.originalProposal().readSerialRegistrationDefinition().serialCode)
    }

    @Test
    fun lspr008FreshAccessIdsRetainExactOriginInsteadOfNewPublicationIdentity() {
        val original = inputs.origin()
        val pinnedDigest = NativeSerialPrivatePublicationRequestCodecV1.digest(original)
        for ((index, operation) in listOf(NativeSerialPrivatePublicationOperationV1.PREPARE,
            NativeSerialPrivatePublicationOperationV1.REVIEW, NativeSerialPrivatePublicationOperationV1.RECOVER,
            NativeSerialPrivatePublicationOperationV1.STATUS).withIndex()) {
            val candidate = inputs.access(original, operation, inputs.id(30 + index))
            val reread = NativeSerialPrivatePublicationRequestCodecV1.decode(
                NativeSerialPrivatePublicationRequestCodecV1.encode(candidate))
            assertEquals(original.requestId, reread.originRequestId)
            assertEquals(pinnedDigest, reread.originRequestDigest)
            assertEquals(inputs.publication, reread.publicationId)
            assertEquals(inputs.entity, reread.entityId)
            assertEquals(original.contentDigest, reread.contentDigest)
            assertTrue(original.sameTarget(reread))
        }
    }

    @Test
    fun lspr003CapsuleCopiesSuppliedAndReturnedRequestDependencyAndFactBuffers() {
        val original = inputs.origin()
        val request = NativeSerialPrivatePublicationRequestCodecV1.encode(original)
        val dependencies = "INERT-DEPENDENCIES-NOT-A-GRANT".encodeToByteArray()
        val facts = "INERT-FACTS-NOT-AUTHENTIC-REQUESTER-EVIDENCE".encodeToByteArray()
        val pinnedRequest = request.copyOf()
        val pinnedDependencies = dependencies.copyOf()
        val pinnedFacts = facts.copyOf()
        val capsule = NativeSerialPrivateRequestCapsuleV1(request, dependencies, facts)
        request.fill(0); dependencies.fill(0); facts.fill(0)
        capsule.copyRequestBytes().fill(0)
        capsule.copyDependencyBytes().fill(0)
        capsule.copyFactBytes().fill(0)
        assertContentEquals(pinnedRequest, capsule.copyRequestBytes())
        assertContentEquals(pinnedDependencies, capsule.copyDependencyBytes())
        assertContentEquals(pinnedFacts, capsule.copyFactBytes())
        assertEquals(original.contentDigest,
            NativeSerialPrivatePublicationRequestCodecV1.decode(capsule.copyRequestBytes()).contentDigest)
    }

    @Test
    fun lspr008ClearingNativeOwnedDecodedCopyPreservesSuppliedOriginalAndCapsule() {
        val original = inputs.origin()
        val pinned = NativeSerialPrivatePublicationRequestCodecV1.encode(original)
        val capsule = NativeSerialPrivateRequestCapsuleV1(pinned, byteArrayOf(1), byteArrayOf(2))
        val admittedInputs = capsule.copyNativeInputs()
        val decoded = NativeSerialPrivatePublicationRequestCodecV1.decode(admittedInputs.requestBytes)
        decoded.clearOriginalContent()
        admittedInputs.clear()
        assertContentEquals(pinned, capsule.copyRequestBytes())
        assertContentEquals(pinned, NativeSerialPrivatePublicationRequestCodecV1.encode(original))
        assertEquals(inputs.contentA, original.originalProposal().readSerialRegistrationDefinition().serialCode)
    }
}
