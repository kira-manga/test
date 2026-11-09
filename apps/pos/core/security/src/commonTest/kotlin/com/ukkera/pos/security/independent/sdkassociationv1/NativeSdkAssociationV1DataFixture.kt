package com.ukkera.pos.security.independent.sdkassociationv1

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.AppId
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.core.PeerId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertContentEquals

internal fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
internal fun JsonObject.objectAt(key: String): JsonObject = getValue(key).jsonObject
internal fun JsonObject.arrayAt(key: String): JsonArray = getValue(key).jsonArray

/**
 * Harness-only carriers for opaque labels, never native/wire identity expectations.
 * The finite v1 input set is seven original labels, their seven changed-label variants,
 * and three supporting DATA labels. Exact labels are sorted before assigning distinct
 * canonical UUIDs, so mapping is deterministic and independent of test execution order.
 * Equal labels share a carrier; different declared labels cannot share one. No hashing,
 * trimming, normalization, random identity or unknown-label fallback is used.
 */
private object SyntheticOpaqueIdCarriersV1 {
    val byLabel: Map<String, String> = run {
        val originals = FrozenNativeSdkAssociationV1.data.objectAt("v2_distinct_id_variants")
            .objectAt("baseline_ids").values.map { it.jsonPrimitive.content }
        val labels = (originals + originals.map { it + "-changed" } + listOf(
            "synthetic-retained-device", "synthetic-pairing-request", "synthetic-proof-action",
        )).distinct().sorted()
        labels.mapIndexed { index, label ->
            label to ("00000000-0000-4000-8000-" + (index + 1).toString(16).padStart(12, '0'))
        }.toMap().also { require(it.values.distinct().size == labels.size) }
    }
}

internal fun syntheticIdUuid(opaqueLabel: String): String = requireNotNull(
    SyntheticOpaqueIdCarriersV1.byLabel[opaqueLabel],
) { "No declared synthetic UUID carrier for opaque label: $opaqueLabel" }

internal fun syntheticDomainId(opaqueLabel: String): DomainId = DomainId(syntheticIdUuid(opaqueLabel))

/**
 * Synthetic public DATA carriers only. These values are not a native installation,
 * signed admission, real key possession, actual Kit, employee or authority fixture.
 * Opaque native/install references in the freeze are deliberately not serialized.
 */
internal fun captureOriginalIdentity(
    appId: String = FrozenNativeSdkAssociationV1.original.text("sdk_app_id"),
    peerId: String = FrozenNativeSdkAssociationV1.original.text("sdk_peer_id"),
    fingerprint: String = FrozenNativeSdkAssociationV1.original.text("sdk_fingerprint"),
    qr: String = FrozenNativeSdkAssociationV1.original.text("sdk_pairing_qr"),
): LocalDevicePairingSdkIdentityV2 {
    val original = FrozenNativeSdkAssociationV1.original
    val scope = BranchScope(syntheticDomainId(original.text("organization_id")),
        syntheticDomainId(original.text("company_id")), syntheticDomainId(original.text("branch_id")))
    val key = syntheticDomainId(original.text("native_key_id"))
    // Merely SEC1-shaped public DATA accepted by the target descriptor boundary.
    // No scalar, signing provider, native key or custody claim is supplied.
    val point = ByteArray(65) { if (it == 0) 4.toByte() else it.toByte() }
    val admission = LocalDeviceAdmission(scope, syntheticDomainId(original.text("device_id")),
        Revision(0), true, key.value, sha256(point).value, emptySet())
    val target = LocalDevicePairingTargetDescriptor.capture(admission, key, point)
    point.fill(0)
    val installedData = SelfOwnedTargetDescriptorV1("synthetic.app", 12345,
        "a".repeat(64), "b".repeat(64))
    return LocalDevicePairingSdkIdentityV2.capture(
        syntheticDomainId(original.text("acquisition_id")), syntheticDomainId(original.text("preparation_id")),
        target, installedData, AppId(appId), PeerId(peerId), PeerFingerprint(fingerprint), qr,
    )
}

internal fun identityIds(value: LocalDevicePairingSdkIdentityV2): Map<String, String> = linkedMapOf(
    "acquisition_id" to value.originalAcquisitionId.value,
    "preparation_id" to value.originalPreparationId.value,
    "organization_id" to value.target.admission.scope.organizationId.value,
    "company_id" to value.target.admission.scope.companyId.value,
    "branch_id" to value.target.admission.scope.branchId.value,
    "device_id" to value.target.admission.deviceId.value,
    "native_key_id" to value.target.keyId.value,
)

internal data class AdmissionFacts(
    val organization: String, val company: String, val branch: String, val device: String,
    val revision: Long, val admitted: Boolean, val key: String?, val fingerprint: String?,
    val capabilities: List<String>,
)

internal fun admissionFacts(value: LocalDeviceAdmission): AdmissionFacts = AdmissionFacts(
    value.scope.organizationId.value, value.scope.companyId.value, value.scope.branchId.value,
    value.deviceId.value, value.revision.value, value.admitted, value.publicKeyIdentifier,
    value.publicFingerprint, value.capabilities.sorted(),
)

internal data class IdentityFacts(
    val ids: Map<String, String>, val admission: AdmissionFacts, val pointHex: String,
    val applicationFacts: List<String>, val appId: String, val peerId: String,
    val fingerprint: String, val qr: String,
)

internal fun identityFacts(value: LocalDevicePairingSdkIdentityV2): IdentityFacts = IdentityFacts(
    identityIds(value), admissionFacts(value.target.admission), value.target.copyPublicPoint().hex(),
    value.nativeApplication.let { listOf(it.applicationId, it.applicationUid.toString(),
        it.apkSignerSha256, it.androidIdCommitment) },
    value.appId.value, value.peerId.value, value.fingerprint.value, value.pairingQr,
)

internal fun ByteArray.hex(): String = joinToString("") {
    (it.toInt() and 255).toString(16).padStart(2, '0')
}

internal fun hexBytes(text: String): ByteArray {
    require(text.length % 2 == 0)
    return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/**
 * Mutation seam for a runtime-produced carrier, using the actual shared wire primitives.
 * It is not an independently specified whole-wire encoder or golden-byte/digest oracle.
 * Each helper first reproduces the captured carrier exactly, then changes named fields.
 * Nested native-layout qualification remains outside these bounded DATA slices.
 */
internal fun rewriteIdentity(
    original: LocalDevicePairingSdkIdentityV2,
    changedIdLabels: Map<String, String> = emptyMap(),
    purpose: ByteArray? = null,
    domain: ByteArray? = null,
    appId: ByteArray? = null,
    peerId: ByteArray? = null,
    fingerprint: ByteArray? = null,
    qr: ByteArray? = null,
): ByteArray {
    val captured = original.copyWireBytes()
    val carrier = IdentityCarrier.read(captured)
    assertContentEquals(captured, carrier.encode(), "Mutation carrier must preserve the captured original")
    val ids = identityIds(original) + changedIdLabels.mapValues { syntheticIdUuid(it.value) }
    return carrier.copy(
        acquisition = ids.getValue("acquisition_id").encodeToByteArray(),
        preparation = ids.getValue("preparation_id").encodeToByteArray(),
        targetBytes = if (changedIdLabels.isEmpty()) carrier.targetBytes else rewriteTargetIds(carrier.targetBytes, ids),
        purpose = purpose ?: carrier.purpose,
        domain = domain ?: carrier.domain,
        appId = appId ?: carrier.appId,
        peerId = peerId ?: carrier.peerId,
        fingerprint = fingerprint ?: carrier.fingerprint,
        qr = qr ?: carrier.qr,
    ).encode()
}

private data class IdentityCarrier(
    val profile: ByteArray, val domain: ByteArray, val purpose: ByteArray,
    val acquisition: ByteArray, val preparation: ByteArray, val targetBytes: ByteArray,
    val installed: SelfOwnedTargetDescriptorV1,
    val appId: ByteArray, val peerId: ByteArray, val fingerprint: ByteArray, val qr: ByteArray,
) {
    fun encode(): ByteArray = withWriter { w ->
        w.field(profile); w.field(domain); w.field(purpose)
        w.field(acquisition); w.field(preparation); w.field(targetBytes); w.target(installed)
        w.field(appId); w.field(peerId); w.field(fingerprint); w.field(qr)
    }

    companion object {
        fun read(bytes: ByteArray): IdentityCarrier {
            val r = SelfOwnedWireReaderV1(bytes)
            val maximum = LocalDevicePairingProfileV2.MAXIMUM_SDK_IDENTITY_BYTES
            return IdentityCarrier(r.field(maximum), r.field(maximum), r.field(maximum),
                r.field(maximum), r.field(maximum), r.field(maximum), r.target(),
                r.field(maximum), r.field(maximum), r.field(maximum), r.field(maximum)).also { r.finish() }
        }
    }
}

private data class TargetCarrier(
    val profile: ByteArray, val domain: ByteArray, val purpose: ByteArray,
    val organization: ByteArray, val company: ByteArray, val branch: ByteArray, val device: ByteArray,
    val revision: Long, val admitted: Boolean, val publicKey: ByteArray?, val fingerprint: ByteArray?,
    val capabilities: List<ByteArray>, val key: ByteArray, val point: ByteArray,
) {
    fun encode(): ByteArray = withWriter { w ->
        w.field(profile); w.field(domain); w.field(purpose)
        w.field(organization); w.field(company); w.field(branch); w.field(device)
        w.long(revision); w.bool(admitted)
        w.bool(publicKey != null); publicKey?.let { w.field(it) }
        w.bool(fingerprint != null); fingerprint?.let { w.field(it) }
        w.int(capabilities.size); capabilities.forEach { w.field(it) }
        w.field(key); w.field(point)
    }

    companion object {
        fun read(bytes: ByteArray): TargetCarrier {
            val r = SelfOwnedWireReaderV1(bytes)
            val maximum = LocalDevicePairingProfileV2.MAXIMUM_SDK_IDENTITY_BYTES
            val profile = r.field(maximum); val domain = r.field(maximum); val purpose = r.field(maximum)
            val organization = r.field(maximum); val company = r.field(maximum)
            val branch = r.field(maximum); val device = r.field(maximum)
            val revision = r.long(); val admitted = r.bool()
            val publicKey = if (r.bool()) r.field(maximum) else null
            val fingerprint = if (r.bool()) r.field(maximum) else null
            val capabilities = List(r.int()) { r.field(maximum) }
            return TargetCarrier(profile, domain, purpose, organization, company, branch, device,
                revision, admitted, publicKey, fingerprint, capabilities, r.field(maximum), r.field(maximum))
                .also { r.finish() }
        }
    }
}

private fun rewriteTargetIds(bytes: ByteArray, ids: Map<String, String>): ByteArray {
    val carrier = TargetCarrier.read(bytes)
    assertContentEquals(bytes, carrier.encode(), "Nested mutation carrier must preserve original DATA")
    return carrier.copy(
        organization = ids.getValue("organization_id").encodeToByteArray(),
        company = ids.getValue("company_id").encodeToByteArray(),
        branch = ids.getValue("branch_id").encodeToByteArray(),
        device = ids.getValue("device_id").encodeToByteArray(),
        publicKey = ids.getValue("native_key_id").encodeToByteArray(),
        key = ids.getValue("native_key_id").encodeToByteArray(),
    ).encode()
}

private fun withWriter(body: (SelfOwnedWireWriterV1) -> Unit): ByteArray {
    val writer = SelfOwnedWireWriterV1(LocalDevicePairingProfileV2.MAXIMUM_SDK_IDENTITY_BYTES + 1)
    return try { body(writer); writer.finish() } finally { writer.clear() }
}

internal enum class BufferOverwrite {
    FIRST, LAST, ALL;
    fun apply(bytes: ByteArray) {
        require(bytes.isNotEmpty())
        when (this) {
            FIRST -> bytes[0] = (bytes[0].toInt() xor 255).toByte()
            LAST -> bytes[bytes.lastIndex] = (bytes.last().toInt() xor 255).toByte()
            ALL -> bytes.indices.forEach { bytes[it] = (bytes[it].toInt() xor 255).toByte() }
        }
    }
}

internal fun capturedDefinition(identity: LocalDevicePairingSdkIdentityV2): LocalDevicePairingDefinition {
    val retainedData = LocalDeviceAdmission(identity.target.admission.scope,
        syntheticDomainId("synthetic-retained-device"), Revision(0), true,
        "synthetic-retained-key", null, emptySet())
    // An opaque represented byte carrier, not the authoritative service-admission codec.
    return LocalDevicePairingDefinition.captureSdkBound(syntheticDomainId("synthetic-pairing-request"),
        identity.target, byteArrayOf(1, 2, 3), listOf(retainedData), identity)
}

internal fun possessionFields(identity: LocalDevicePairingSdkIdentityV2): NativeDevicePairingPossessionV1 =
    NativeDevicePairingPossessionV1(syntheticDomainId("synthetic-proof-action"), PayloadDigest("c".repeat(64)),
        identity.target.admission.deviceId, identity.target.keyId, identity.target.descriptorDigest,
        SelfOwnedOctetsV1(ByteArray(64) { it.toByte() }, 64), identity.descriptorDigest)

internal fun capturedPossession(identity: LocalDevicePairingSdkIdentityV2): LocalDevicePairingPossession =
    LocalDevicePairingPossession.decode(NativeDevicePairingWireV2.encodePossession(possessionFields(identity)))
