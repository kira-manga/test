package com.ukkera.pos.security.independent.originaldeviceadmissionv1

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalAuthenticationObservation
import com.ukkera.pos.security.LocalDeviceAdmissionEffectEvidence
import com.ukkera.pos.security.LocalDeviceAdmissionProtectedImage
import com.ukkera.pos.security.LocalProvisioningAction
import com.ukkera.pos.security.LocalProvisioningRequest
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.ProtectedLocalTable
import com.ukkera.pos.security.SelfOwnedWireReaderV1
import com.ukkera.pos.security.SelfOwnedWireWriterV1
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * Bounded representation slices of frozen original-device-admission v1.0.0.
 * Oracles: qa/pos/freezes/original-device-admission-v1/{README.md,cases.md,fixtures.json}.
 * Synthetic DATA is not a native association, dispatch, protected commit or settlement.
 * No complete ODA case, native proof profile, expiry rule or installed variant is covered.
 */
class LocalDeviceAdmissionEvidenceFrozenTest {
    @Test
    fun oda008EvidenceRepresentsExactlyOneAdmissionThreeThrottlesAndOneEvent() {
        val input = evidenceInputs()
        val evidence = LocalDeviceAdmissionEffectEvidence.decode(input.capture().copyWireBytes())
        assertEquals(2, evidence.admissionDigests.size)
        assertEquals(3, evidence.accountDigests.size)
        assertEquals(5, evidence.images.size) // One admission + three accounts' throttles + one event.
        assertEquals(1, evidence.images.count { it.table == ProtectedLocalTable.ADMISSION })
        assertEquals(3, evidence.images.count { it.table == ProtectedLocalTable.THROTTLE })
        assertEquals(1, evidence.images.count { it.table == ProtectedLocalTable.AUTH_EVENT })
        assertEquals(FrozenOdaData.target, evidence.images.first().recordId)
        assertEquals(FrozenOdaData.accounts, evidence.images.subList(1, 4).map { it.recordId })
        assertEquals(List(3) { "device:${FrozenOdaData.target.value}" }, evidence.images.subList(1, 4).map { it.dimension })
        assertEquals(FrozenOdaData.request, evidence.request.requestId)
        assertEquals(input.images.last().recordId, evidence.images.last().recordId)
    }

    @Test
    fun oda008EvidenceCodecPreservesSuppliedOriginalRequestCensusAndImageDigests() {
        val input = evidenceInputs()
        val canonical = input.capture().copyWireBytes()
        val decoded = LocalDeviceAdmissionEffectEvidence.decode(canonical)
        assertEvidenceFields(input, decoded)
        assertContentEquals(canonical, decoded.copyWireBytes())
    }

    @Test
    fun oda003EvidenceBuffersAndInputCollectionsCannotChangeCapturedRepresentation() {
        val input = evidenceInputs()
        val admissions = input.admissions.toMutableList()
        val accounts = input.accounts.toMutableList()
        val images = input.images.map { it.protectedImage() }.toMutableList()
        val original = LocalDeviceAdmissionEffectEvidence(input.request, input.definition, input.authority,
            input.binding, input.eventAt, input.preflight, admissions, accounts, images)
        val canonical = original.copyWireBytes()
        admissions.clear(); accounts.clear(); images.clear()
        assertEvidenceFields(input, original)
        assertContentEquals(canonical, original.copyWireBytes())
        for (overwrite in BufferOverwrite.entries) {
            overwrite.apply(original.copyWireBytes())
            val bytes = canonical.copyOf()
            val decoded = LocalDeviceAdmissionEffectEvidence.decode(bytes)
            overwrite.apply(bytes)
            overwrite.apply(decoded.copyWireBytes())
            assertEvidenceFields(input, decoded)
            assertContentEquals(canonical, decoded.copyWireBytes())
            assertContentEquals(canonical, original.copyWireBytes())
        }
    }

    @Test
    fun oda008And014CompleteSuppliedCensusRejectsEveryMissingEffectImage() {
        val original = evidenceInputs()
        for (index in original.images.indices) {
            val changed = original.copy(images = original.images.filterIndexed { at, _ -> at != index })
            assertRejectedEvidence(original, changed, "Missing frozen effect image $index")
        }
    }

    @Test
    fun oda008And029CompleteSuppliedCensusRejectsAdditionalAndDuplicateEffects() {
        val original = evidenceInputs()
        val variants = listOf(
            "extra-admission" to (original.images + original.images.first()),
            "extra-throttle" to (original.images.dropLast(1) + original.images[1] + original.images.last()),
            "extra-event" to (original.images + original.images.last()),
            "duplicate-account-throttle" to original.images.toMutableList().also { it[2] = it[1] },
        )
        variants.forEach { (id, images) -> assertRejectedEvidence(original, original.copy(images = images), id) }
    }

    @Test
    fun oda008And029EvidenceRejectsChangedTargetAndPerAccountThrottleBindings() {
        val original = evidenceInputs()
        val variants = mutableListOf<Pair<String, List<ImageInput>>>()
        variants += "wrong-target-admission" to original.images.toMutableList().also {
            it[0] = it[0].copy(recordId = FrozenOdaData.existingAdmissions.first())
        }
        for (index in 1..3) {
            variants += "wrong-target-throttle-$index" to original.images.toMutableList().also {
                it[index] = it[index].copy(dimension = "device:${FrozenOdaData.existingAdmissions.last().value}")
            }
        }
        variants += "wrong-account-throttle" to original.images.toMutableList().also {
            it[2] = it[2].copy(recordId = FrozenOdaData.existingAdmissions.last())
        }
        variants.forEach { (id, images) -> assertRejectedEvidence(original, original.copy(images = images), id) }
    }

    @Test
    fun oda008And013EvidenceRejectsDuplicateCensusIdsAndAnAlreadyRetainedTarget() {
        val original = evidenceInputs()
        val variants = listOf(
            "duplicate-retained-device" to original.copy(admissions = listOf(original.admissions[0], original.admissions[0])),
            "duplicate-account" to original.copy(accounts = listOf(original.accounts[0], original.accounts[0], original.accounts[2])),
            "target-already-in-retained-census" to original.copy(admissions = listOf(
                original.admissions[0], FrozenOdaData.target to original.admissions[1].second,
            )),
        )
        variants.forEach { (id, changed) -> assertRejectedEvidence(original, changed, id) }
    }

    @Test
    fun oda008EvidenceRejectsAccountPolicyAndSessionImagesAsAdmissionEffects() {
        val original = evidenceInputs()
        listOf(ProtectedLocalTable.ACCOUNT, ProtectedLocalTable.EFFECTIVE_POLICY, ProtectedLocalTable.SESSION_ISSUANCE)
            .forEach { forbidden ->
                val changed = original.copy(images = original.images.toMutableList().also {
                    it[0] = it[0].copy(table = forbidden)
                })
                assertRejectedEvidence(original, changed, "Forbidden admission effect table $forbidden")
            }
    }
}

/** Exact identity literals from F_ODA_BASE, not native or schema-generated values. */
private object FrozenOdaData {
    val scope = BranchScope(id(1), id(2), id(3))
    val target = id(9)
    val request = id(17)
    val accounts = listOf(id(20), id(21), id(22))
    val existingAdmissions = listOf(id(8), id(10))
    private fun id(suffix: Int): DomainId = DomainId("81000000-0000-4000-8000-" + suffix.toString().padStart(12, '0'))
}

/** Inert common image carriers, not native/protected row captures. */
private data class ImageInput(val table: ProtectedLocalTable, val recordId: DomainId,
    val dimension: String, val digest: PayloadDigest) {
    fun protectedImage(): LocalDeviceAdmissionProtectedImage = LocalDeviceAdmissionProtectedImage(table, recordId, dimension, digest)
}

private data class EvidenceInputs(val request: LocalProvisioningRequest, val definition: PayloadDigest,
    val authority: PayloadDigest, val binding: PayloadDigest, val eventAt: MonotonicMoment,
    val preflight: LocalAuthenticationObservation, val admissions: List<Pair<DomainId, PayloadDigest>>,
    val accounts: List<Pair<DomainId, PayloadDigest>>, val images: List<ImageInput>) {
    fun capture(): LocalDeviceAdmissionEffectEvidence = LocalDeviceAdmissionEffectEvidence(request, definition, authority,
        binding, eventAt, preflight, admissions, accounts, images.map { it.protectedImage() })
}

/**
 * Digests, boot/context IDs and monotonic values are synthetic codec inputs only.
 * They replace no CAPTURE reference and assert no native proof or expiry rule.
 * The current common AUTH_EVENT carrier uses the request ID as its image key;
 * this is a wiring mapping, not an invented expected native event-row identity.
 */
private fun evidenceInputs(): EvidenceInputs {
    fun digest(label: String): PayloadDigest = sha256(("synthetic ODA DATA input: $label").encodeToByteArray())
    val boot = DomainId("82000000-0000-4000-8000-000000000001")
    val context = DomainId("82000000-0000-4000-8000-000000000002")
    val eventAt = MonotonicMoment(boot, 0)
    val preflight = LocalAuthenticationObservation(context, MonotonicMoment(boot, 1))
    return EvidenceInputs(
        LocalProvisioningRequest(FrozenOdaData.scope, FrozenOdaData.request, LocalProvisioningAction.ADMIT_DEVICE,
            FrozenOdaData.target, digest("complete-request-carrier")),
        digest("definition-carrier"), digest("authority-DATA-carrier"), digest("native-binding-DATA-carrier"),
        eventAt, preflight,
        FrozenOdaData.existingAdmissions.map { it to digest("admission-${it.value}") },
        FrozenOdaData.accounts.map { it to digest("account-${it.value}") },
        listOf(ImageInput(ProtectedLocalTable.ADMISSION, FrozenOdaData.target, "", digest("new-admission-image"))) +
            FrozenOdaData.accounts.map {
                ImageInput(ProtectedLocalTable.THROTTLE, it, "device:${FrozenOdaData.target.value}", digest("new-throttle-${it.value}"))
            } + ImageInput(ProtectedLocalTable.AUTH_EVENT, FrozenOdaData.request, "", digest("original-event-image")),
    )
}

private fun assertEvidenceFields(input: EvidenceInputs, actual: LocalDeviceAdmissionEffectEvidence) {
    assertEquals(input.request, actual.request)
    assertEquals(input.definition, actual.definitionDigest)
    assertEquals(input.authority, actual.authorityDigest)
    assertEquals(input.binding, actual.nativeBindingDigest)
    assertEquals(input.eventAt, actual.eventAt)
    assertEquals(input.preflight, actual.protectedPreflight)
    assertEquals(input.admissions, actual.admissionDigests)
    assertEquals(input.accounts, actual.accountDigests)
    assertEquals(input.images.map { it.protectedImage() }, actual.images)
}

private fun assertRejectedEvidence(original: EvidenceInputs, changed: EvidenceInputs, message: String) {
    // Verify the unchanged control outside assertFails; a broken mutation seam cannot pass a rejection.
    val canonical = original.capture().copyWireBytes()
    val reader = SelfOwnedWireReaderV1(canonical)
    val profile = reader.field(canonical.size)
    val domain = reader.field(canonical.size)
    val control = encodeEvidenceCarrier(profile, domain, original, canonical.size * 2)
    assertContentEquals(canonical, control, "Evidence mutation carrier must preserve the unchanged control")
    val bytes = encodeEvidenceCarrier(profile, domain, changed, canonical.size * 2)
    assertFails("$message (public construction)") { changed.capture() }
    assertFails("$message (public decode)") { LocalDeviceAdmissionEffectEvidence.decode(bytes) }
}

/** Current inert carrier mapping only, not a frozen native wire/proof or digest golden. */
private fun encodeEvidenceCarrier(profile: ByteArray, domain: ByteArray, input: EvidenceInputs, maximum: Int): ByteArray =
    withCarrierWriter(maximum) { w ->
        w.field(profile); w.field(domain)
        w.id(input.request.scope.organizationId); w.id(input.request.scope.companyId); w.id(input.request.scope.branchId)
        w.id(input.request.requestId); w.string(input.request.action.name); w.id(input.request.targetId); w.string(input.request.contentDigest.value)
        w.string(input.definition.value); w.string(input.authority.value); w.string(input.binding.value)
        w.id(input.eventAt.bootId); w.long(input.eventAt.elapsedMillis)
        w.id(input.preflight.issuerContextId); w.id(input.preflight.moment.bootId); w.long(input.preflight.moment.elapsedMillis)
        w.int(input.admissions.size); input.admissions.forEach { (id, digest) -> w.id(id); w.string(digest.value) }
        w.int(input.accounts.size); input.accounts.forEach { (id, digest) -> w.id(id); w.string(digest.value) }
        w.int(input.images.size); input.images.forEach {
            w.string(it.table.name); w.id(it.recordId); w.string(it.dimension); w.string(it.digest.value)
        }
    }

private enum class BufferOverwrite {
    FIRST, LAST, ALL;
    fun apply(bytes: ByteArray) {
        when (this) {
            FIRST -> bytes[0] = (bytes[0].toInt() xor 255).toByte()
            LAST -> bytes[bytes.lastIndex] = (bytes.last().toInt() xor 255).toByte()
            ALL -> bytes.indices.forEach { bytes[it] = (bytes[it].toInt() xor 255).toByte() }
        }
    }
}

private fun withCarrierWriter(maximum: Int, body: (SelfOwnedWireWriterV1) -> Unit): ByteArray {
    val writer = SelfOwnedWireWriterV1(maximum)
    return try { body(writer); writer.finish() } finally { writer.clear() }
}
