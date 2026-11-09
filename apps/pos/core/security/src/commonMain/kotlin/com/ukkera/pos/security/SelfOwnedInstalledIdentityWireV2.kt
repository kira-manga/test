package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/**
 * Canonical additive V2 wire framing. Every public `decode*` returns bounded DATA or a frozen
 * refusal category; no decode path verifies a signature, observes a native context or grants
 * authority. Signature verification, signer trust and installed packaging remain separate
 * native/later-card layers.
 */
public object SelfOwnedInstalledIdentityWireV2 {
    public fun encodeTarget(value: SelfOwnedInstalledIdentityTargetV2): ByteArray =
        frame(
            SelfOwnedInstalledIdentityProfileV2.ID,
            SelfOwnedInstalledIdentityProfileV2.TARGET_DOMAIN,
            SelfOwnedInstalledIdentityProfileV2.MAXIMUM_TARGET_BYTES,
        ) {
            int(SelfOwnedInstalledIdentityProfileV2.FORMAT_VERSION)
            string(value.kind.wireName)
            identity(value)
        }

    public fun decodeTarget(
        bytes: ByteArray,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedInstalledIdentityTargetV2> =
        selfOwnedIdentityGuarded { decodeTargetOrThrow(bytes) }

    public fun encodePreparation(value: SelfOwnedInstalledIdentityPreparationV2): ByteArray =
        frame(
            SelfOwnedInstalledIdentityProfileV2.ID,
            SelfOwnedInstalledIdentityProfileV2.PREPARATION_DOMAIN,
            SelfOwnedInstalledIdentityProfileV2.MAXIMUM_PREPARATION_BYTES,
        ) {
            int(SelfOwnedInstalledIdentityProfileV2.FORMAT_VERSION)
            id(value.rootId); string(value.rootDocumentDigest); id(value.issuerId)
            string(value.delegationDigest); id(value.installerId)
            long(value.installerRevision); long(value.delegationRevision)
            coordinates(value.coordinates); long(value.serial)
            val nonce = value.copyNonce()
            try { field(nonce) } finally { nonce.fill(0) }
            val target = encodeTarget(value.target)
            try { field(target) } finally { target.fill(0) }
            string(value.branchName)
            string(SelfOwnedInstalledIdentityProfileV2.PREPARATION_PURPOSE)
        }

    public fun decodePreparation(
        bytes: ByteArray,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedInstalledIdentityPreparationV2> =
        selfOwnedIdentityGuarded { decodePreparationOrThrow(bytes) }

    /** Exact V2 signing message over the entire framed `native-preparation/v2` record. */
    public fun signingMessage(preparation: SelfOwnedInstalledIdentityPreparationV2): ByteArray {
        val record = encodePreparation(preparation)
        return try { signingMessageForRecord(record) } finally { record.fill(0) }
    }

    /**
     * Exact signing message for retained preparation bytes. The record is validated first; the
     * returned message covers the supplied bytes, never a re-encoded substitute.
     */
    public fun signingMessageForPreparation(
        record: ByteArray,
    ): SelfOwnedInstalledIdentityResultV2<ByteArray> =
        when (val decoded = decodePreparation(record)) {
            is SelfOwnedInstalledIdentityResultV2.Refused -> decoded
            is SelfOwnedInstalledIdentityResultV2.Data -> SelfOwnedInstalledIdentityResultV2.Data(
                signingMessageForRecord(record),
            )
        }

    public fun encodeSignedBody(value: SelfOwnedInstalledIdentitySignedBodyV2): ByteArray =
        signingMessage(value.preparation)

    public fun decodeSignedBody(
        bytes: ByteArray,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedInstalledIdentitySignedBodyV2> =
        selfOwnedIdentityGuarded {
            if (bytes.size > SelfOwnedInstalledIdentityProfileV2.MAXIMUM_DOCUMENT_BYTES) {
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_OVERSIZE)
            }
            val reader = SelfOwnedIdentityReader(bytes)
            if (reader.string() != SelfOwnedInstalledIdentityProfileV2.ID) {
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_PROFILE_UNSUPPORTED)
            }
            if (reader.string() != SelfOwnedInstalledIdentityProfileV2.SIGNING_BODY_DOMAIN) {
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_DOMAIN)
            }
            if (reader.string() != SelfOwnedInstalledIdentityProfileV2.ROLE_TAG) {
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_ROLE)
            }
            val body = reader.field(SelfOwnedInstalledIdentityProfileV2.MAXIMUM_BODY_BYTES)
            if (body.isEmpty()) {
                body.fill(0)
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_BODY_EMPTY)
            }
            reader.finish()
            val preparation = try { decodePreparationOrThrow(body) } finally { body.fill(0) }
            SelfOwnedInstalledIdentitySignedBodyV2(preparation)
        }

    public fun encodeSignedDocument(value: SelfOwnedInstalledIdentitySignedDocumentV2): ByteArray {
        val signature = value.copySignature()
        return try { encodeSignedDocument(value.preparation, signature) } finally { signature.fill(0) }
    }

    public fun encodeSignedDocument(
        preparation: SelfOwnedInstalledIdentityPreparationV2,
        signature: ByteArray,
    ): ByteArray {
        require(signature.size == SelfOwnedInstalledIdentityProfileV2.SIGNATURE_BYTES)
        val record = encodePreparation(preparation)
        return try {
            frame(
                SelfOwnedInstalledIdentityProfileV2.ID,
                SelfOwnedInstalledIdentityProfileV2.SIGNED_DOCUMENT_DOMAIN,
                SelfOwnedInstalledIdentityProfileV2.MAXIMUM_DOCUMENT_BYTES,
            ) {
                string(SelfOwnedInstalledIdentityProfileV2.ROLE_TAG)
                field(record)
                field(signature)
            }
        } finally { record.fill(0) }
    }

    public fun decodeSignedDocument(
        bytes: ByteArray,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedInstalledIdentitySignedDocumentV2> =
        selfOwnedIdentityGuarded {
            if (bytes.size > SelfOwnedInstalledIdentityProfileV2.MAXIMUM_DOCUMENT_BYTES) {
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_OVERSIZE)
            }
            val reader = SelfOwnedIdentityReader(bytes)
            if (reader.string() != SelfOwnedInstalledIdentityProfileV2.ID) {
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_PROFILE_UNSUPPORTED)
            }
            if (reader.string() != SelfOwnedInstalledIdentityProfileV2.SIGNED_DOCUMENT_DOMAIN) {
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_DOMAIN)
            }
            if (reader.string() != SelfOwnedInstalledIdentityProfileV2.ROLE_TAG) {
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_ROLE)
            }
            val body = reader.field(SelfOwnedInstalledIdentityProfileV2.MAXIMUM_BODY_BYTES)
            if (body.isEmpty()) {
                body.fill(0)
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_BODY_EMPTY)
            }
            val signature = reader.field(SelfOwnedInstalledIdentityProfileV2.MAXIMUM_DOCUMENT_BYTES)
            if (signature.size != SelfOwnedInstalledIdentityProfileV2.SIGNATURE_BYTES) {
                signature.fill(0)
                body.fill(0)
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_SIGNATURE_SHAPE)
            }
            reader.finish()
            val preparation = try { decodePreparationOrThrow(body) } finally { body.fill(0) }
            try {
                SelfOwnedInstalledIdentitySignedDocumentV2(
                    SelfOwnedInstalledIdentitySignedBodyV2(preparation),
                    signature,
                )
            } finally { signature.fill(0) }
        }

    internal fun frame(
        profile: String,
        domain: String,
        maximum: Int,
        body: SelfOwnedWireWriterV1.() -> Unit,
    ): ByteArray {
        val writer = SelfOwnedWireWriterV1(maximum)
        return try {
            writer.string(profile); writer.string(domain); writer.body(); writer.finish()
        } finally { writer.clear() }
    }

    private fun SelfOwnedWireWriterV1.identity(value: SelfOwnedInstalledIdentityTargetV2) {
        when (value) {
            is SelfOwnedWindowsPackagedAppIdentityV2 -> {
                string(value.packageFamilyName); string(value.applicationUserModelId)
            }
            is SelfOwnedWindowsServiceIdentityV2 -> {
                string(value.serviceSid); string(value.serviceAccountSid)
            }
            is SelfOwnedWindowsPackagedServiceIdentityV2 -> {
                string(value.packageFamilyName); string(value.applicationUserModelId)
                string(value.serviceSid); string(value.serviceAccountSid)
            }
            is SelfOwnedMacServiceIdentityV2 -> {
                string(value.signingIdentifier); string(value.teamIdentifier)
                string(value.designatedRequirementSha256)
            }
            is SelfOwnedMacUiIdentityV2 -> {
                string(value.signingIdentifier); string(value.teamIdentifier)
                string(value.designatedRequirementSha256)
            }
            is SelfOwnedIosActiveAppIdentityV2 -> {
                string(value.bundleIdentifier); string(value.appIdGroup)
            }
        }
    }

    private fun signingMessageForRecord(record: ByteArray): ByteArray = frame(
        SelfOwnedInstalledIdentityProfileV2.ID,
        SelfOwnedInstalledIdentityProfileV2.SIGNING_BODY_DOMAIN,
        SelfOwnedInstalledIdentityProfileV2.MAXIMUM_DOCUMENT_BYTES,
    ) {
        string(SelfOwnedInstalledIdentityProfileV2.ROLE_TAG)
        field(record)
    }

    private fun decodeTargetOrThrow(bytes: ByteArray): SelfOwnedInstalledIdentityTargetV2 {
        if (bytes.size > SelfOwnedInstalledIdentityProfileV2.MAXIMUM_TARGET_BYTES) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_OVERSIZE)
        }
        val reader = SelfOwnedIdentityReader(bytes)
        if (reader.string() != SelfOwnedInstalledIdentityProfileV2.ID) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_PROFILE_UNSUPPORTED)
        }
        if (reader.string() != SelfOwnedInstalledIdentityProfileV2.TARGET_DOMAIN) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_DOMAIN)
        }
        if (reader.int() != SelfOwnedInstalledIdentityProfileV2.FORMAT_VERSION) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_VERSION)
        }
        val kind = SelfOwnedInstalledIdentityTargetKindV2.fromWireName(reader.string())
            ?: refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_TARGET_KIND)
        val target = readIdentity(reader, kind)
        reader.finish()
        return target
    }

    private fun readIdentity(
        reader: SelfOwnedIdentityReader,
        kind: SelfOwnedInstalledIdentityTargetKindV2,
    ): SelfOwnedInstalledIdentityTargetV2 = when (kind) {
        SelfOwnedInstalledIdentityTargetKindV2.WINDOWS_PACKAGED_APP_V1 -> {
            val family = reader.string()
            val aumid = reader.string()
            requirePfn(family)
            requireAumid(aumid)
            if (!SelfOwnedInstalledIdentityGrammarV2.asciiEqualsIgnoreCase(
                    aumid.substring(0, aumid.indexOf('!')), family,
                )
            ) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
            SelfOwnedWindowsPackagedAppIdentityV2(family, aumid)
        }
        SelfOwnedInstalledIdentityTargetKindV2.WINDOWS_SERVICE_V1 -> {
            val serviceSid = reader.string()
            val accountSid = reader.string()
            requireServiceSid(serviceSid)
            requireAccountSid(accountSid)
            SelfOwnedWindowsServiceIdentityV2(serviceSid, accountSid)
        }
        SelfOwnedInstalledIdentityTargetKindV2.WINDOWS_PACKAGED_SERVICE_V1 -> {
            val family = reader.string()
            val aumid = reader.string()
            val serviceSid = reader.string()
            val accountSid = reader.string()
            requirePfn(family)
            requireAumid(aumid)
            if (!SelfOwnedInstalledIdentityGrammarV2.asciiEqualsIgnoreCase(
                    aumid.substring(0, aumid.indexOf('!')), family,
                )
            ) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
            requireServiceSid(serviceSid)
            requireAccountSid(accountSid)
            SelfOwnedWindowsPackagedServiceIdentityV2(family, aumid, serviceSid, accountSid)
        }
        SelfOwnedInstalledIdentityTargetKindV2.MACOS_SERVICE_V1 -> {
            val signing = reader.string()
            val team = reader.string()
            val designated = reader.string()
            requireSigningIdentifier(signing)
            requireTeam(team)
            requireDigest(designated)
            SelfOwnedMacServiceIdentityV2(signing, team, designated)
        }
        SelfOwnedInstalledIdentityTargetKindV2.MACOS_UI_V1 -> {
            val signing = reader.string()
            val team = reader.string()
            val designated = reader.string()
            requireSigningIdentifier(signing)
            requireTeam(team)
            requireDigest(designated)
            SelfOwnedMacUiIdentityV2(signing, team, designated)
        }
        SelfOwnedInstalledIdentityTargetKindV2.IOS_ACTIVE_APP_V1 -> {
            val bundle = reader.string()
            val group = reader.string()
            requireBundle(bundle)
            if (!SelfOwnedInstalledIdentityGrammarV2.appIdGroup(group, bundle)) {
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
            }
            SelfOwnedIosActiveAppIdentityV2(bundle, group)
        }
    }

    private fun decodePreparationOrThrow(bytes: ByteArray): SelfOwnedInstalledIdentityPreparationV2 {
        if (bytes.size > SelfOwnedInstalledIdentityProfileV2.MAXIMUM_PREPARATION_BYTES) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_OVERSIZE)
        }
        val reader = SelfOwnedIdentityReader(bytes)
        if (reader.string() != SelfOwnedInstalledIdentityProfileV2.ID) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_PROFILE_UNSUPPORTED)
        }
        if (reader.string() != SelfOwnedInstalledIdentityProfileV2.PREPARATION_DOMAIN) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_DOMAIN)
        }
        if (reader.int() != SelfOwnedInstalledIdentityProfileV2.FORMAT_VERSION) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_VERSION)
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
        val nonce = reader.field(SelfOwnedInstalledIdentityProfileV2.MAXIMUM_PREPARATION_BYTES)
        if (nonce.size != SelfOwnedInstalledIdentityProfileV2.NONCE_BYTES) {
            nonce.fill(0)
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        }
        val target = try {
            val targetBytes = reader.field(SelfOwnedInstalledIdentityProfileV2.MAXIMUM_TARGET_BYTES)
            try { decodeTargetOrThrow(targetBytes) } finally { targetBytes.fill(0) }
        } catch (failure: Throwable) {
            nonce.fill(0)
            throw failure
        }
        val branchName = reader.string()
        when (val labelRefusal = SelfOwnedInstalledIdentityGrammarV2.label(branchName)) {
            null -> Unit
            else -> {
                nonce.fill(0)
                refuse(labelRefusal)
            }
        }
        if (reader.string() != SelfOwnedInstalledIdentityProfileV2.PREPARATION_PURPOSE) {
            nonce.fill(0)
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        }
        try {
            reader.finish()
            return SelfOwnedInstalledIdentityPreparationV2(
                rootId, rootDocumentDigest, issuerId, delegationDigest, installerId,
                installerRevision, delegationRevision, coordinates, serial, nonce, target, branchName,
            )
        } finally { nonce.fill(0) }
    }

    private fun requirePfn(value: String) {
        if (!SelfOwnedInstalledIdentityGrammarV2.packageFamilyName(value)) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        }
    }

    private fun requireAumid(value: String) {
        if (!SelfOwnedInstalledIdentityGrammarV2.applicationUserModelId(value)) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        }
    }

    private fun requireServiceSid(value: String) {
        if (!SelfOwnedInstalledIdentityGrammarV2.serviceSid(value)) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        }
    }

    private fun requireAccountSid(value: String) {
        if (!SelfOwnedInstalledIdentityGrammarV2.accountSid(value)) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        }
    }

    private fun requireSigningIdentifier(value: String) {
        if (!SelfOwnedInstalledIdentityGrammarV2.signingIdentifier(value)) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        }
    }

    private fun requireTeam(value: String) {
        if (!SelfOwnedInstalledIdentityGrammarV2.teamIdentifier(value)) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        }
    }

    private fun requireDigest(value: String) {
        if (!SelfOwnedInstalledIdentityGrammarV2.digest64(value)) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        }
    }

    private fun requireBundle(value: String) {
        if (!SelfOwnedInstalledIdentityGrammarV2.bundleIdentifier(value)) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        }
    }
}

internal class SelfOwnedIdentityRefuse(
    val code: SelfOwnedInstalledIdentityRefusalV2,
) : Exception()

internal fun refuse(code: SelfOwnedInstalledIdentityRefusalV2): Nothing = throw SelfOwnedIdentityRefuse(code)

internal inline fun <T> selfOwnedIdentityGuarded(
    block: () -> T,
): SelfOwnedInstalledIdentityResultV2<T> = try {
    SelfOwnedInstalledIdentityResultV2.Data(block())
} catch (refused: SelfOwnedIdentityRefuse) {
    SelfOwnedInstalledIdentityResultV2.Refused(refused.code)
} catch (_: IllegalArgumentException) {
    // A valid parser path must not rely on constructor exceptions; fail closed if it does.
    SelfOwnedInstalledIdentityResultV2.Refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
}

internal class SelfOwnedIdentityReader(private val bytes: ByteArray) {
    private var at: Int = 0

    fun int(): Int {
        if (bytes.size - at < 4) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_TRUNCATED)
        var value = 0
        repeat(4) { value = (value shl 8) or (bytes[at++].toInt() and 255) }
        if (value < 0) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_NONNEGATIVE)
        return value
    }

    fun long(): Long {
        if (bytes.size - at < 8) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_TRUNCATED)
        var value = 0L
        repeat(8) { value = (value shl 8) or (bytes[at++].toLong() and 255L) }
        if (value < 0) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_NONNEGATIVE)
        return value
    }

    fun field(maximum: Int): ByteArray {
        val size = int()
        if (size > maximum) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_OVERSIZE)
        if (size > bytes.size - at) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_TRUNCATED)
        val value = bytes.copyOfRange(at, at + size)
        at += size
        return value
    }

    fun string(): String {
        val copy = field(bytes.size)
        return try {
            copy.decodeToString(throwOnInvalidSequence = true)
        } catch (_: Exception) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_ENCODING)
        } finally { copy.fill(0) }
    }

    fun uuid(): DomainId {
        val value = string()
        if (!SelfOwnedInstalledIdentityGrammarV2.canonicalUuid(value)) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        }
        return DomainId(value)
    }

    fun digest(): String {
        val value = string()
        if (!SelfOwnedInstalledIdentityGrammarV2.digest64(value)) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        }
        return value
    }

    fun finish() {
        if (at != bytes.size) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_TRAILING)
    }
}
