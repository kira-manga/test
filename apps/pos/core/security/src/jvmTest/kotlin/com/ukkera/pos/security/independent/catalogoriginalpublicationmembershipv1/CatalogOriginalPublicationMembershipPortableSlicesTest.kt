package com.ukkera.pos.security.independent.catalogoriginalpublicationmembershipv1

import com.ukkera.pos.security.CompanyBusinessPublicationOutput
import com.ukkera.pos.security.CompanyBusinessPublicationPhase
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.security.LocalBranchInstallationWireV2
import com.ukkera.pos.security.NativeBusinessPublicationManifest
import com.ukkera.pos.security.NativeBusinessPublicationMember
import com.ukkera.pos.security.NativeBusinessPublicationObservationWire
import com.ukkera.pos.security.NativeCompanyBusinessPublicationHeadV1
import com.ukkera.pos.security.NativeCompanyBusinessPublicationHistorySource
import com.ukkera.pos.security.NativeCompanyBusinessPublicationRecordV1
import com.ukkera.pos.security.NativeCompanyBusinessPublicationSnapshot
import com.ukkera.pos.security.NativeCompanyBusinessPublicationStateV1
import com.ukkera.pos.security.NativeCompanyBusinessPublicationWireV1
import com.ukkera.pos.security.NativeCompanyPublicationTrust
import com.ukkera.pos.security.NativeHistoryBusinessPublicationObservationProof
import com.ukkera.pos.security.NativeHistoryAnchorRecordV2
import com.ukkera.pos.security.NativeHistoryOriginalIntentV2
import com.ukkera.pos.security.NativeHistoryPendingV2
import com.ukkera.pos.security.NativeHistoryWriterV2
import com.ukkera.pos.security.OriginalAllocationCommitmentV2
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.security.SelfOwnedNativeHistoryWireV2
import com.ukkera.pos.security.SelfOwnedOctetsV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.security.independent.catalogoriginalpublicationmembershipv1.FrozenCatalogOriginalPublicationMembershipInputs.Companion.tokenDigest
import com.ukkera.pos.security.independent.catalogoriginalpublicationmembershipv1.FrozenCatalogOriginalPublicationMembershipInputs.Companion.tokenId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bounded DATA/codec/binding slices of unchanged CPM-P01..P06. No whole group passes here.
 * Constructor outputs, heads and length-only crypto padding are inert, not verified native
 * publications, actual cuts, authentic outcomes, source custody or current authorization.
 * P06 controls conserve self-produced old-format bytes, not historical golden vectors.
 * P06 actual missing-proof holds and CPM-N07..N10 still need genuine native provenance.
 */
class CatalogOriginalPublicationMembershipPortableSlicesTest {
    @Test
    fun cpmP01_inertNonemptyKnownEmptyAndUnavailableRemainDifferentData() {
        val f = FrozenCatalogOriginalPublicationMembershipInputs.load()
        val expected = f.group("CPM-P01").getValue("variants").jsonArray.first().jsonObject.getValue("expected").jsonObject
        val nonempty = f.snapshot()
        assertEquals(expected.getValue("completedCount").jsonPrimitive.int, nonempty.completedPublications.size)
        assertEquals(expected.getValue("completedOutputIdentities").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            nonempty.completedPublications.map { it.originalActionId.value }.toSet())
        assertFalse(f.outputInput("OUT-A").getValue("activatedInBranch").jsonPrimitive.boolean)
        assertSnapshot(nonempty, f.scope.company(), f.root, f.generation, f.originalOutputs())
        val empty = f.snapshot(emptyList())
        assertEquals(0, empty.completedPublications.size)
        val absentLedger = f.snapshot(emptyList(), generation = 0, present = false)
        assertEquals(0, absentLedger.completedPublications.size)
        assertFalse(absentLedger.nativeLedgerPresent)
        assertNull(NativeCompanyPublicationTrust(f.scope.company(), f.root).currentCompletedSnapshot())
        // A/B are frozen expected descriptors, not a copied P/S filtering or native-source model.
    }

    @Test
    fun cpmP02_realBoundedCodecsPreserveAllFrozenMembersContextAndContent() {
        val f = FrozenCatalogOriginalPublicationMembershipInputs.load()
        val variants = f.group("CPM-P02").getValue("variants").jsonArray.map { it.jsonObject }
            .filter { it.getValue("expected").jsonObject.getValue("acceptedAsCompleteBoundedData").jsonPrimitive.boolean }
        for (variant in variants) {
            val count = variant.getValue("count").jsonPrimitive.int
            val outputs = f.boundaryOutputs(count)
            // Roundtrip conservation of the supplied sequence, not an invented membership order.
            for (source in listOf(outputs, outputs.reversed()).distinct()) {
                val snapshot = f.snapshot(source)
                assertSnapshot(snapshot, f.scope.company(), f.root, f.generation, source)
                val manifest = NativeBusinessPublicationManifest(snapshot)
                val decoded = NativeBusinessPublicationObservationWire.decodeManifest(
                    NativeBusinessPublicationObservationWire.encodeManifest(manifest))
                assertManifest(manifest, decoded)
                assertEquals(source.map(::NativeBusinessPublicationMember), decoded.members)
                if (source.isNotEmpty()) {
                    val state = NativeCompanyBusinessPublicationStateV1(f.generation, source.map(::inertRecord))
                    val roundtrip = NativeCompanyBusinessPublicationWireV1.decode(NativeCompanyBusinessPublicationWireV1.encode(state))
                    assertEquals(f.generation, roundtrip.generation)
                    assertEquals(source.size, roundtrip.records.size)
                    for ((output, record) in source.zip(roundtrip.records)) assertRecord(output, record)
                }
            }
        }
    }

    @Test
    fun cpmP02_full257MemberOverflowIsRejectedWithoutTruncation() {
        val f = FrozenCatalogOriginalPublicationMembershipInputs.load()
        val overflowCount = f.group("CPM-P02").getValue("variants").jsonArray.map { it.jsonObject }
            .single { !it.getValue("expected").jsonObject.getValue("acceptedAsCompleteBoundedData").jsonPrimitive.boolean }
            .getValue("count").jsonPrimitive.int
        val overflow = f.boundaryOutputs(overflowCount)
        assertFails { f.snapshot(overflow) }
        assertFails { NativeBusinessPublicationManifest(f.scope.company(), f.root, true, f.generation, overflow.map(::NativeBusinessPublicationMember)) }
        assertFails { NativeCompanyBusinessPublicationStateV1(f.generation, overflow.map(::inertRecord)) }

        // Build a complete overflow candidate from genuine encoder output, including member 257.
        // No guessed wire model or merely truncated count-only payload supplies this rejection.
        val bounded = NativeBusinessPublicationObservationWire.encodeManifest(NativeBusinessPublicationManifest(f.snapshot(overflow.dropLast(1))))
        val headerSize = NativeBusinessPublicationObservationWire.encodeManifest(NativeBusinessPublicationManifest(f.snapshot(emptyList()))).size
        val singletonBytes = NativeBusinessPublicationObservationWire.encodeManifest(NativeBusinessPublicationManifest(f.snapshot(overflow.takeLast(1))))
        val finalMember = singletonBytes.copyOfRange(headerSize, singletonBytes.size)
        val countBytes = writer { int(overflowCount - 1) }
        // The empty encoder output gives the full header boundary. Other matching byte
        // patterns (including across framed fields) must not be mistaken for the count.
        val countOffset = offsets(bounded, countBytes).single { it + countBytes.size == headerSize }
        val fullOverflow = bounded + finalMember
        writer { int(overflowCount) }.copyInto(fullOverflow, countOffset)
        assertFails { NativeBusinessPublicationObservationWire.decodeManifest(fullOverflow) }
    }

    @Test
    fun cpmP03_originalCopiesSurviveLaterListsAndRejectMissingMutatedOrCurrentReplacement() {
        val f = FrozenCatalogOriginalPublicationMembershipInputs.load()
        val outputs = f.originalOutputs().toMutableList()
        val snapshot = f.snapshot(outputs)
        val bundle = bundle(f, snapshot)
        val originalManifestBytes = bundle.proof.copyManifestBytes()
        val memberInput = NativeBusinessPublicationManifest(snapshot).members.toMutableList()
        val retainedManifest = NativeBusinessPublicationManifest(f.scope.company(), f.root, true, f.generation, memberInput)
        memberInput.clear()
        retainedManifest.members.toMutableList().clear()
        assertEquals(f.originalOutputs().map(::NativeBusinessPublicationMember), retainedManifest.members)
        outputs += f.output(f.outputInput("OUT-P"))
        outputs += f.output(f.outputInput("OUT-L"))
        assertEquals(f.originalIdentities().toSet(), snapshot.completedPublications.map { it.originalActionId }.toSet())
        assertContentEquals(originalManifestBytes, bundle.proof.copyManifestBytes())
        val mutableContent = f.content(f.outputInput("OUT-A"))
        val retained = f.output(f.outputInput("OUT-A"), bytes = mutableContent)
        mutableContent.fill(0)
        retained.proposal.copyCanonicalContent().fill(0)
        assertContentEquals(f.content(f.outputInput("OUT-A")), retained.proposal.copyCanonicalContent())
        snapshot.completedPublications.toMutableList().clear()
        bundle.proof.copyManifestBytes().fill(0)
        assertContentEquals(originalManifestBytes, bundle.proof.copyManifestBytes())
        val missingAWithLaterOutputs = outputs.filterNot { it.originalActionId == f.originalIdentities().first() }
        assertFails { NativeCompanyBusinessPublicationHistorySource(Any(), bundle.proof, f.snapshot(missingAWithLaterOutputs)) }
        val mutation = f.group("CPM-P03").getValue("laterChanges").jsonArray.map { it.jsonObject }
            .single { "replacementContentToken" in it }
        val changedA = f.output(f.outputInput("OUT-A"), bytes = f.content(
            mutation.getValue("replacementContentToken").jsonPrimitive.content,
            mutation.getValue("replacementOpaquePortableContentHex").jsonPrimitive.content))
        assertFails { NativeCompanyBusinessPublicationHistorySource(Any(), bundle.proof, f.snapshot(listOf(changedA, f.originalOutputs()[1]))) }
        assertFails { NativeCompanyBusinessPublicationHistorySource(Any(), bundle.proof, f.snapshot(outputs,
            generation = f.challenge("generationToken").substringAfterLast('-').toLong())) }
    }

    @Test
    fun cpmP04_originalContextAndIntentChallengeSlicesFailClosed() {
        val f = FrozenCatalogOriginalPublicationMembershipInputs.load()
        val b = bundle(f)
        b.proof.requireIntent(b.intent)
        val wrongCompany = CompanyScope(f.scope.organizationId, DomainId(f.challenge("companyId")))
        assertFails { NativeCompanyBusinessPublicationHistorySource(Any(), b.proof, f.snapshot(f.originalOutputs(wrongCompany), company = wrongCompany)) }
        val wrongRoot = tokenDigest(f.challenge("rootIdentityToken")).value
        assertFails { NativeCompanyBusinessPublicationHistorySource(Any(), b.proof, f.snapshot(f.originalOutputs(root = wrongRoot), root = wrongRoot)) }
        assertFails { NativeCompanyBusinessPublicationHistorySource(Any(), b.proof,
            f.snapshot(generation = f.challenge("generationToken").substringAfterLast('-').toLong())) }
        assertFails { b.proof.copy(scope = f.scope.copy(companyId = wrongCompany.companyId)) }
        assertFails { b.proof.copy(frameId = tokenId(f.challenge("frameIdentityToken"))) }
        assertFails { b.proof.copy(originalAnchorDigest = tokenDigest(f.challenge("originalAnchorToken"))) }
        assertFails { b.proof.copy(preparationParent = b.proof.preparationParent.copy(historyDigest = tokenDigest(f.challenge("nativeParentId")))) }
        for (dimension in listOf("operationId", "actorId", "semanticPayloadToken", "intentId", "opaquePortableDependencyBytesHex")) {
            assertFails("CPM-P04: unchanged original versus $dimension") { b.proof.requireIntent(f.intent(replacements = mapOf(dimension to f.challenge(dimension)))) }
        }
        // Fixture intentId inhabits inert facts metadata; authentic native intent/commitment remains unbound.
    }

    @Test
    fun cpmP04_realProofDecoderRejectsIncoherentFrozenContextSubstitutions() {
        val f = FrozenCatalogOriginalPublicationMembershipInputs.load()
        val b = bundle(f)
        val encoded = NativeBusinessPublicationObservationWire.encodeProof(b.proof)
        val replacements = listOf(
            f.value("companyId") to f.challenge("companyId"),
            b.proof.frameId.value to tokenId(f.challenge("frameIdentityToken")).value,
            b.proof.originalAnchorDigest.value to tokenDigest(f.challenge("originalAnchorToken")).value,
            b.proof.preparationParent.historyDigest.value to tokenDigest(f.challenge("nativeParentId")).value)
        for ((original, replacement) in replacements) {
            val candidate = replaceEncodedFields(encoded, original.encodeToByteArray(), replacement.encodeToByteArray())
            assertFails { NativeBusinessPublicationObservationWire.decodeProof(candidate) }
        }
    }

    @Test
    fun cpmP04_dependencyMarkerRequiresTheWholeMatchingFrameBoundMarker() {
        val f = FrozenCatalogOriginalPublicationMembershipInputs.load()
        val b = bundle(f)
        val fields = f.dependencyFields(b.proof.frameId, b.proof.observationDigest)
        val framed = dependency(fields)
        NativeBusinessPublicationObservationWire.requireDependency(framed, fields)
        val candidates = listOf(
            dependency(f.dependencyFields(tokenId(f.challenge("frameIdentityToken")), b.proof.observationDigest)),
            dependency(listOf("true")),
            dependency(listOf(b.proof.observationDigest.value)),
            dependency(f.dependencyFields(b.proof.frameId, bundle(f, f.snapshot(emptyList())).proof.observationDigest)),
            FrozenCatalogOriginalPublicationMembershipInputs.hexBytes(f.value("opaquePortableDependencyBytesHex")))
        for (candidate in candidates) assertFails { NativeBusinessPublicationObservationWire.requireDependency(candidate, fields) }
        // Real dependency commitment/authentic SQL-domain bytes remain UNBOUND; no golden is inferred.
    }

    @Test
    fun cpmP05_applyAndRefusalTypedProofEnvelopesRetainOnlyTheSameOriginalData() {
        val f = FrozenCatalogOriginalPublicationMembershipInputs.load()
        val manifests = (2..3).map { purpose ->
            val b = bundle(f, purpose = purpose)
            val decoded = NativeBusinessPublicationObservationWire.decodeProof(NativeBusinessPublicationObservationWire.encodeProof(b.proof))
            decoded.requireIntent(b.intent)
            assertEquals(purpose, decoded.purposeTag)
            assertEquals(DomainId(f.value("operationId")), decoded.originalRequestId)
            assertEquals(b.proof.originalOperationId, decoded.originalOperationId)
            assertEquals(b.proof.originalActorId, decoded.originalActorId)
            assertEquals(b.proof.originalIntentDigest, decoded.originalIntentDigest)
            assertEquals(b.proof.child, decoded.child)
            assertContentEquals(b.proof.copyManifestBytes(), decoded.copyManifestBytes())
            decoded.copyManifestBytes()
        }
        assertContentEquals(manifests[0], manifests[1])
        // Purpose tags exercise DTO conservation only: not real Apply/refusal, result/resources or five changes.
    }

    @Test
    fun cpmP04_inertSourceHasNoTraversalAndRejectsWrongOriginalEventDimensions() {
        val f = FrozenCatalogOriginalPublicationMembershipInputs.load()
        val b = bundle(f)
        val source = NativeCompanyBusinessPublicationHistorySource(Any(), b.proof, b.snapshot)
        assertFalse(source.hasActualOriginalEvent)
        assertFails { source.requireActualTraversal() }
        assertFails { source.snapshot }
        assertFails { source.observeOriginalEvent(b.proof.child.historySequence,
            tokenDigest(f.challenge("signedChildId")), b.proof.frameId,
            b.proof.preparationParent.historySequence, b.proof.preparationParent.historyDigest) }
        assertFails { source.observeOriginalEvent(b.proof.child.historySequence,
            b.proof.child.historyDigest, tokenId(f.challenge("frameIdentityToken")),
            b.proof.preparationParent.historySequence, b.proof.preparationParent.historyDigest) }
        assertFails { source.observeOriginalEvent(b.proof.child.historySequence,
            b.proof.child.historyDigest, b.proof.frameId,
            b.proof.preparationParent.historySequence, tokenDigest(f.challenge("nativeParentId"))) }
        assertFalse(source.hasActualOriginalEvent)
        assertFails { source.snapshot }
        // Never mark a synthetic event as traversed, create native selection or assert current authority.
    }

    @Test
    fun cpmP06_oldAnchorFormatsConserveExactBytesAndKeepObservationAbsent() {
        val f = FrozenCatalogOriginalPublicationMembershipInputs.load()
        assertEquals("ABSENT", f.group("CPM-P06").getValue("observationField").jsonPrimitive.content)
        for (format in listOf(SelfOwnedNativeHistoryWireV2.FORMAT,
            SelfOwnedNativeHistoryWireV2.FINAL_DECISION_RECORD_FORMAT,
            SelfOwnedNativeHistoryWireV2.PROTECTED_FINAL_RECORD_FORMAT)) {
            val old = inertLegacyAnchor(f, format)
            val oldBytes = SelfOwnedNativeHistoryWireV2.encodeRecord(old)
            val decoded = SelfOwnedNativeHistoryWireV2.decodeRecord(oldBytes)
            assertEquals(format, decoded.recordFormat)
            assertEquals(old.generation, decoded.generation)
            assertEquals(old.denialGeneration, decoded.denialGeneration)
            assertEquals(f.scope, decoded.allocation.coordinates.scope)
            assertEquals(DomainId(f.value("actorId")), decoded.allocation.coordinates.firstActorId)
            assertNull(decoded.pending)
            assertEquals(emptyList(), decoded.businessPublicationObservations)
            assertContentEquals(LocalBranchInstallationWireV2.encodeAllocation(old.allocation),
                LocalBranchInstallationWireV2.encodeAllocation(decoded.allocation))
            assertContentEquals(oldBytes, SelfOwnedNativeHistoryWireV2.encodeRecord(decoded))
            // Reading/encoding a separate current membership cannot rewrite this old DATA.
            for (current in listOf(f.snapshot(emptyList()), f.snapshot())) {
                NativeBusinessPublicationObservationWire.decodeManifest(
                    NativeBusinessPublicationObservationWire.encodeManifest(NativeBusinessPublicationManifest(current)))
                assertEquals(format, old.recordFormat)
                assertEquals(emptyList(), old.businessPublicationObservations)
                assertContentEquals(oldBytes, SelfOwnedNativeHistoryWireV2.encodeRecord(old))
            }
        }
        // The public old-format codec supplies these bytes, not an invented historical golden.
        // No completed installation, original command or native original snapshot is fabricated.
    }

    @Test
    fun cpmP06_originalIntentBytesAndIdentitiesStayExactAcrossAbsentOuterProof() {
        val f = FrozenCatalogOriginalPublicationMembershipInputs.load()
        for (purpose in 2..3) {
            val intent = f.intent(purpose)
            val oldBytes = SelfOwnedNativeHistoryWireV2.encodeIntent(intent)
            val defaultAbsent = NativeHistoryPendingV2(f.child(), intent)
            val decoded = SelfOwnedNativeHistoryWireV2.decodeIntent(oldBytes)
            val explicitAbsent = NativeHistoryPendingV2(f.child(), decoded, businessPublications = null)
            for (pending in listOf(defaultAbsent, explicitAbsent)) {
                assertNull(pending.businessPublications)
                assertEquals(DomainId(f.value("operationId")), pending.originalIntent.originalRequestId)
                assertEquals(intent.originalOperationId, pending.originalIntent.originalOperationId)
                assertEquals(intent.originalActorId, pending.originalIntent.originalActorId)
                assertEquals(intent.originalRequestDigest, pending.originalIntent.originalRequestDigest)
                assertEquals(intent.dependenciesDigest, pending.originalIntent.dependenciesDigest)
                assertEquals(intent.originalFactsDigest, pending.originalIntent.originalFactsDigest)
                assertEquals(intent.resources, pending.originalIntent.resources)
                assertContentEquals(oldBytes, SelfOwnedNativeHistoryWireV2.encodeIntent(pending.originalIntent))
            }
            // Observation DATA lives in a separate new outer capsule, not a new intent field.
            // These inert empty/nonempty controls are never attached to an authentic old original.
            for (current in listOf(f.snapshot(emptyList()), f.snapshot())) {
                val b = bundle(f, current, purpose)
                val separateNewCapsule = NativeHistoryPendingV2(b.proof.child, b.intent, businessPublications = b.proof)
                assertContentEquals(oldBytes, SelfOwnedNativeHistoryWireV2.encodeIntent(separateNewCapsule.originalIntent))
                assertNull(defaultAbsent.businessPublications)
                assertNull(explicitAbsent.businessPublications)
            }
        }
        // No original result bytes, V1 business-journal golden or missing-proof recovery hold is inferred.
    }
}

private data class InertBundle(val snapshot: NativeCompanyBusinessPublicationSnapshot,
    val proof: NativeHistoryBusinessPublicationObservationProof, val intent: NativeHistoryOriginalIntentV2)

private fun bundle(f: FrozenCatalogOriginalPublicationMembershipInputs,
    snapshot: NativeCompanyBusinessPublicationSnapshot = f.snapshot(), purpose: Int = 2): InertBundle {
    val manifest = NativeBusinessPublicationManifest(snapshot)
    val intent = f.intent(purpose)
    val allocation = tokenDigest("inert-original-allocation")
    val anchor = tokenDigest(f.value("originalAnchorToken"))
    val frame = tokenId(f.value("frameIdentityToken"))
    val parent = f.parent()
    val observation = NativeBusinessPublicationObservationWire.observationDigest(f.scope, allocation, anchor, parent, frame, manifest)
    val proof = NativeHistoryBusinessPublicationObservationProof(f.scope, allocation, anchor, parent, frame,
        observation, f.child(), NativeBusinessPublicationObservationWire.intentDigest(intent), purpose,
        intent.originalRequestId, requireNotNull(intent.originalOperationId), requireNotNull(intent.originalActorId),
        intent.originalRequestDigest, intent.dependenciesDigest, intent.originalFactsDigest,
        SelfOwnedOctetsV1(NativeBusinessPublicationObservationWire.encodeManifest(manifest), NativeBusinessPublicationObservationWire.MAX_MANIFEST_BYTES))
    return InertBundle(snapshot, proof, intent)
}

private fun inertRecord(output: CompanyBusinessPublicationOutput): NativeCompanyBusinessPublicationRecordV1 =
    NativeCompanyBusinessPublicationRecordV1(output.originalActionId, output.proposal.company, output.proposal.kind,
        output.proposal.publicationId, output.proposal.entityId, output.signingKeyId, SelfOwnedOctetsV1(output.proposal.copyCanonicalContent()),
        output.proposal.contentDigest, CompanyBusinessPublicationPhase.COMPLETED, NativeCompanyBusinessPublicationHeadV1(0, 0, 0, 0),
        SelfOwnedOctetsV1(ByteArray(32)), SelfOwnedOctetsV1(ByteArray(64)), SelfOwnedOctetsV1(ByteArray(64)))

private fun inertLegacyAnchor(f: FrozenCatalogOriginalPublicationMembershipInputs, format: Int): NativeHistoryAnchorRecordV2 {
    val c = SelfOwnedAllocationCoordinatesV1(tokenId("inert-legacy-allocation"), f.scope,
        tokenId("inert-legacy-installation"), tokenId("inert-legacy-assignment"), tokenId("inert-legacy-device"),
        tokenId("inert-legacy-storage-instance"), tokenId("inert-legacy-storage-key"), tokenId("inert-legacy-pepper-key"),
        tokenId("inert-legacy-target-key"), DomainId(f.value("actorId")))
    // Length-only uncompressed-point-shaped padding: not a native key or authenticated allocation.
    val pointPadding = ByteArray(65).also { it[0] = 4 }
    val allocation = OriginalAllocationCommitmentV2(c, tokenDigest("inert-legacy-native-binding"),
        tokenDigest(f.value("rootIdentityToken")), tokenDigest("inert-legacy-delegation"),
        tokenDigest("inert-legacy-preparation"), pointPadding)
    return NativeHistoryAnchorRecordV2(allocation, f.generation, 0, null, null, null, null, null, null,
        emptyList(), null, null, null, recordFormat = format)
}

private fun assertRecord(output: CompanyBusinessPublicationOutput, actual: NativeCompanyBusinessPublicationRecordV1) {
    assertEquals(output.originalActionId, actual.originalActionId)
    assertEquals(output.proposal.company, actual.company)
    assertEquals(output.proposal.kind, actual.kind)
    assertEquals(output.proposal.publicationId, actual.publicationId)
    assertEquals(output.proposal.entityId, actual.entityId)
    assertEquals(output.signingKeyId, actual.keyId)
    assertEquals(output.proposal.contentDigest, actual.digest)
    assertEquals(CompanyBusinessPublicationPhase.COMPLETED, actual.phase)
    assertContentEquals(output.proposal.copyCanonicalContent(), actual.content.copyBytes())
}

private fun assertSnapshot(actual: NativeCompanyBusinessPublicationSnapshot, company: CompanyScope, root: String,
    generation: Long, outputs: List<CompanyBusinessPublicationOutput>) {
    assertEquals(company, actual.company); assertEquals(root, actual.rootFingerprint); assertEquals(generation, actual.generation)
    assertEquals(true, actual.nativeLedgerPresent)
    assertEquals(outputs.map { it.originalActionId }, actual.completedPublications.map { it.originalActionId })
    for ((expected, retained) in outputs.zip(actual.completedPublications)) {
        assertEquals(expected.proposal.company, retained.proposal.company)
        assertEquals(expected.rootFingerprint, retained.rootFingerprint)
        assertContentEquals(expected.proposal.copyCanonicalContent(), retained.proposal.copyCanonicalContent())
    }
}

private fun assertManifest(expected: NativeBusinessPublicationManifest, actual: NativeBusinessPublicationManifest) {
    assertEquals(expected.company, actual.company); assertEquals(expected.rootFingerprint, actual.rootFingerprint)
    assertEquals(expected.nativeLedgerPresent, actual.nativeLedgerPresent); assertEquals(expected.generation, actual.generation)
    assertEquals(expected.members, actual.members)
}

private fun writer(block: NativeHistoryWriterV2.() -> Unit): ByteArray {
    val writer = NativeHistoryWriterV2(1024)
    return try { writer.block(); writer.finish() } finally { writer.clear() }
}

private fun dependency(fields: List<String>): ByteArray = writer { field(writer { fields.forEach { string(it) } }) }

private fun offsets(bytes: ByteArray, field: ByteArray): List<Int> = bytes.indices.filter { at ->
    at <= bytes.size - field.size && field.indices.all { bytes[at + it] == field[it] }
}

private fun replaceEncodedFields(bytes: ByteArray, original: ByteArray, replacement: ByteArray): ByteArray {
    assertEquals(original.size, replacement.size)
    val at = offsets(bytes, original)
    check(at.isNotEmpty())
    return bytes.copyOf().also { candidate -> at.forEach { replacement.copyInto(candidate, it) } }
}
