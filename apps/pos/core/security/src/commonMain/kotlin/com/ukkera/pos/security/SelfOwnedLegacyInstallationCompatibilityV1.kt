package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/**
 * Result-returning compatibility consumer for the unchanged legacy Android V1 documents.
 *
 * The production `SelfOwnedInstallationWireV1.decodePreparation` delegates here so one shared
 * implementation owns the exact V1 field/refusal rules. Accepted bytes/values and matching are
 * unchanged: four-field exact target equality, role ordinal 3, unchanged domains/caps and the
 * corrected full-framed signing boundary enforced by the caller. Every result is DATA only.
 */
internal object SelfOwnedLegacyInstallationCompatibilityV1 {
    private const val PREPARATION_DOMAIN = "native-preparation/v1"
    private const val SIGNING_BODY_DOMAIN = "signed-body/v1"
    private const val SIGNED_DOCUMENT_DOMAIN = "signed-document/v1"
    private const val NATIVE_PREPARATION_ROLE = 3
    private const val PURPOSE = "PREPARE_ORIGINAL_NATIVE_KEYS_ONLY_NO_ROOM_AUTHORITY"
    private const val MAXIMUM_DOCUMENT_BYTES = 65_536
    private const val MAXIMUM_PREPARATION_BYTES = 65_024
    private const val MAXIMUM_TARGET_BYTES = 65_536
    private const val MAXIMUM_LEGACY_BRANCH_BYTES = 640

    fun matchTarget(
        bytes: ByteArray,
        expected: SelfOwnedTargetDescriptorV1,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedTargetDescriptorV1> =
        selfOwnedIdentityGuarded {
            if (bytes.size > MAXIMUM_TARGET_BYTES) {
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_OVERSIZE)
            }
            val reader = SelfOwnedIdentityReader(bytes)
            val actual = readTarget(reader)
            reader.finish()
            if (!actual.same(expected)) {
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_IDENTITY_MISMATCH)
            }
            actual
        }

    fun decodePreparation(
        bytes: ByteArray,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedPreparationBodyV1> =
        selfOwnedIdentityGuarded { decodePreparationOrThrow(bytes) }

    fun decodeSignedBody(
        bytes: ByteArray,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedPreparationBodyV1> =
        selfOwnedIdentityGuarded {
            if (bytes.size > MAXIMUM_DOCUMENT_BYTES) {
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_OVERSIZE)
            }
            val reader = SelfOwnedIdentityReader(bytes)
            if (reader.string() != SelfOwnedInstallationProfileV1.ID) {
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_PROFILE_UNSUPPORTED)
            }
            if (reader.string() != SIGNING_BODY_DOMAIN) {
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_DOMAIN)
            }
            if (reader.int() != NATIVE_PREPARATION_ROLE) {
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_ROLE)
            }
            val body = reader.field(MAXIMUM_PREPARATION_BYTES)
            if (body.isEmpty()) {
                body.fill(0)
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_BODY_EMPTY)
            }
            reader.finish()
            val preparation = try { decodePreparationOrThrow(body) } finally { body.fill(0) }
            preparation
        }

    fun decodeEnvelope(
        bytes: ByteArray,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedPreparationBodyV1> =
        selfOwnedIdentityGuarded {
            if (bytes.size > MAXIMUM_DOCUMENT_BYTES) {
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_OVERSIZE)
            }
            val reader = SelfOwnedIdentityReader(bytes)
            if (reader.string() != SelfOwnedInstallationProfileV1.ID) {
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_PROFILE_UNSUPPORTED)
            }
            if (reader.string() != SIGNED_DOCUMENT_DOMAIN) {
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_DOMAIN)
            }
            if (reader.int() != NATIVE_PREPARATION_ROLE) {
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_ROLE)
            }
            val body = reader.field(MAXIMUM_PREPARATION_BYTES)
            if (body.isEmpty()) {
                body.fill(0)
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_BODY_EMPTY)
            }
            val signature = reader.field(MAXIMUM_DOCUMENT_BYTES)
            if (signature.size != 64) {
                signature.fill(0)
                body.fill(0)
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_SIGNATURE_SHAPE)
            }
            try {
                reader.finish()
                decodePreparationOrThrow(body)
            } finally {
                body.fill(0)
                signature.fill(0)
            }
        }

    /** Preserve the existing internal throwing contract on the actual production path. */
    fun requirePreparation(bytes: ByteArray): SelfOwnedPreparationBodyV1 =
        when (val result = decodePreparation(bytes)) {
            is SelfOwnedInstalledIdentityResultV2.Data -> result.value
            is SelfOwnedInstalledIdentityResultV2.Refused ->
                throw IllegalArgumentException(result.code.name)
        }

    private fun decodePreparationOrThrow(bytes: ByteArray): SelfOwnedPreparationBodyV1 {
        if (bytes.size > MAXIMUM_PREPARATION_BYTES) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_OVERSIZE)
        }
        val reader = SelfOwnedIdentityReader(bytes)
        if (reader.string() != SelfOwnedInstallationProfileV1.ID) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_PROFILE_UNSUPPORTED)
        }
        if (reader.string() != PREPARATION_DOMAIN) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_DOMAIN)
        }
        val rootId = reader.uuid()
        val rootDocumentDigest = reader.digest()
        val issuerId = reader.uuid()
        val delegationDigest = reader.digest()
        val installerId = reader.uuid()
        val installerRevision = reader.long()
        val delegationRevision = reader.long()
        val ids = List(12) { reader.uuid() }
        if (ids.toSet().size != 12) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_DUPLICATE_COORDINATE)
        }
        val coordinates = SelfOwnedAllocationCoordinatesV1(
            ids[0], BranchScope(ids[1], ids[2], ids[3]),
            ids[4], ids[5], ids[6], ids[7], ids[8], ids[9], ids[10], ids[11],
        )
        val serial = reader.long()
        val nonce = reader.field(MAXIMUM_PREPARATION_BYTES)
        if (nonce.size != 32) {
            nonce.fill(0)
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        }
        val target = try {
            readTarget(reader)
        } catch (failure: Throwable) {
            nonce.fill(0)
            throw failure
        }
        val branchNameBytes = try {
            reader.field(MAXIMUM_LEGACY_BRANCH_BYTES)
        } catch (failure: Throwable) {
            nonce.fill(0)
            throw failure
        }
        val branchName = try {
            selfOwnedLegacyBranchLabelV1(branchNameBytes)
        } catch (failure: Throwable) {
            nonce.fill(0)
            throw failure
        } finally {
            branchNameBytes.fill(0)
        }
        val protection = when (val ordinal = reader.int()) {
            0 -> SelfOwnedKeyProtectionV1.OS_ISOLATED
            1 -> SelfOwnedKeyProtectionV1.SECURE_HARDWARE
            2 -> SelfOwnedKeyProtectionV1.STRONGBOX
            else -> {
                nonce.fill(0)
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
            }
        }
        if (reader.string() != PURPOSE) {
            nonce.fill(0)
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        }
        try {
            reader.finish()
            return SelfOwnedPreparationBodyV1(
                rootId, rootDocumentDigest, issuerId, delegationDigest, installerId,
                installerRevision, delegationRevision, coordinates, serial, SelfOwnedOctetsV1(nonce),
                target, branchName, protection,
            )
        } finally { nonce.fill(0) }
    }

    /** Byte-cap, strict UTF-8 and then the field-local historical label precedence. */
    private fun selfOwnedLegacyBranchLabelV1(raw: ByteArray): String {
        if (raw.size > MAXIMUM_LEGACY_BRANCH_BYTES) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_OVERSIZE)
        }
        if (raw.isEmpty()) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_ENCODING)
        val value = try {
            raw.decodeToString(throwOnInvalidSequence = true)
        } catch (_: Exception) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_ENCODING)
        }
        when (val refusal = selfOwnedLegacyBranchLabelRefusal(value)) {
            null -> return value
            else -> refuse(refusal)
        }
    }

    private fun readTarget(reader: SelfOwnedIdentityReader): SelfOwnedTargetDescriptorV1 {
        val applicationId = reader.string()
        if (applicationId.length !in 1..255 || applicationId.any { character ->
                character !in 'a'..'z' && character !in 'A'..'Z' &&
                    character !in '0'..'9' && character != '.' && character != '_'
            }
        ) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        val applicationUid = reader.int()
        val signer = reader.string()
        if (!SelfOwnedInstalledIdentityGrammarV2.digest64(signer)) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        }
        val commitment = reader.string()
        if (!SelfOwnedInstalledIdentityGrammarV2.digest64(commitment)) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        }
        return SelfOwnedTargetDescriptorV1(applicationId, applicationUid, signer, commitment)
    }
}
