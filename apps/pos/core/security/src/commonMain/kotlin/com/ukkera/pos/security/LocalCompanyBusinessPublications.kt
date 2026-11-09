package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.flow.MutableStateFlow

/** Separate opt-in ceremony. The two purposes in founding/installation V1 stay unchanged. */
public enum class CompanyBusinessPublicationKind(public val domain: String) {
    CATALOG_SKU_V1("ukkera.company-sku-publication/v1\n"),
    FOOD_RECIPE_V1("ukkera.company-food-recipe/v1\n"),
    COMPANY_ACTIVITY_PROFILE_V1("ukkera.company-activity-profile/v1\n"),
    RETURN_RIGHTS_V1("ukkera.company-return-rights/v1\n"),
    PRICE_POLICY_V1("ukkera.company-price-policy-entry/v1\n"),
    CATALOG_RELEASE_V1("ukkera.company-catalog-release/v1\n"),
    SERIAL_REGISTRATION_V1("ukkera.company-serial-registration/v1\n"),
    SERIAL_AUTHORITY_V1("ukkera.original-local-company-serial-authority/v1\n"),
}

public object CompanyBusinessPublicationProfileV1 {
    public const val MAXIMUM_CONTENT_BYTES: Int = 48 * 1024
    public const val MAXIMUM_PUBLICATIONS: Int = 256
    internal const val MAXIMUM_STATE_BYTES: Int = SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_BYTES
    internal const val MAXIMUM_PLAINTEXT_BYTES: Int = MAXIMUM_STATE_BYTES - 2048
    internal const val PURPOSE: String = "EXPLICIT_EXACT_LOCAL_COMPANY_BUSINESS_PUBLICATION_ED25519_V1"
}

/** Requested content, never employee-supplied trust or evidence of company authority. */
public class CompanyBusinessPublicationProposal(
    public val company: CompanyScope,
    public val kind: CompanyBusinessPublicationKind,
    public val publicationId: DomainId,
    public val entityId: DomainId,
    canonicalContent: ByteArray,
) {
    private val content = canonicalContent.copyOf()
    public val contentDigest: PayloadDigest = sha256(content)
    init {
        try {
            require(content.size in 1..CompanyBusinessPublicationProfileV1.MAXIMUM_CONTENT_BYTES)
            val prefix = kind.domain.encodeToByteArray()
            try { require(content.size > prefix.size && prefix.indices.all { content[it] == prefix[it] }) }
            finally { prefix.fill(0) }
        } catch (failure: Throwable) { content.fill(0); throw failure }
    }
    public fun copyCanonicalContent(): ByteArray = content.copyOf()
    /** Actual owned-task discard only; a delivered proposal is never cleared by this path. */
    internal fun discardUndeliveredNativeContent() { content.fill(0) }
    override fun toString(): String = "CompanyBusinessPublicationProposal([REDACTED])"
}

/** Owner-bound review of the exact retained request. It contains no reusable approval. */
public class CompanyBusinessPublicationAction internal constructor(
    internal val owner: Any,
    public val originalActionId: DomainId,
    public val proposal: CompanyBusinessPublicationProposal,
    public val signingKeyId: DomainId,
    public val rootFingerprint: String,
    public val companyName: String,
    public val headName: String,
    public val headLogin: String,
    internal val accountRevision: Long,
    internal val credentialRevision: Long,
    internal val boot: Int,
    internal val expiresElapsedMillis: Long,
) {
    override fun toString(): String = "CompanyBusinessPublicationAction([REDACTED])"
}

/** Positive durable output only; branch activation still requires its own current permission. */
public class CompanyBusinessPublicationOutput internal constructor(
    public val originalActionId: DomainId,
    public val proposal: CompanyBusinessPublicationProposal,
    public val signingKeyId: DomainId,
    public val signatureHex: String,
    public val rootFingerprint: String,
) {
    override fun toString(): String = "CompanyBusinessPublicationOutput([REDACTED])"
}

public enum class CompanyBusinessPublicationPhase { PREPARED, SIGNING_INTENT, COMPLETED }

/** Bounded native-local bookkeeping; it does not expose catalog content before local admission. */
public data class CompanyBusinessPublicationReference(
    public val originalActionId: DomainId,
    public val publicationId: DomainId,
    public val entityId: DomainId,
    public val phase: CompanyBusinessPublicationPhase,
    /** Explicit in-memory discriminator; old native SKU ledger bytes and identity are unchanged. */
    public val kind: CompanyBusinessPublicationKind = CompanyBusinessPublicationKind.CATALOG_SKU_V1,
)

/**
 * Authenticated completed contents of one observed original native-local ledger.
 * This is not company-global completeness, current employee permission or authority
 * to activate a publication. Readers must use the current native owner's snapshot
 * in its protected transaction; an old reference cannot prove current history.
 */
public class NativeCompanyBusinessPublicationSnapshot internal constructor(
    public val company: CompanyScope,
    public val rootFingerprint: String,
    public val generation: Long,
    publications: List<CompanyBusinessPublicationOutput>,
    /** False only for the actual native owner's proved absent local ledger. */
    public val nativeLedgerPresent: Boolean = true,
) {
    private val retained = publications.toList()
    public val completedPublications: List<CompanyBusinessPublicationOutput> get() = retained.toList()
    init {
        require(generation >= 0 && retained.size in 0..CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS)
        require(nativeLedgerPresent || (generation == 0L && retained.isEmpty()))
        require(retained.all { it.proposal.company == company && it.rootFingerprint == rootFingerprint })
        require(retained.map { it.originalActionId }.distinct().size == retained.size)
        require(retained.map { it.proposal.publicationId }.distinct().size == retained.size)
        require(retained.map { it.signingKeyId }.distinct().size == retained.size)
    }
    override fun toString(): String = "NativeCompanyBusinessPublicationSnapshot([REDACTED])"
}

/**
 * Created solely by the actual original native company owner. Each accepted key is endorsed
 * by that owner's original P-256 root for ONE exact domain/content digest. Old completed keys
 * remain available for retained-history verification. This is neither a central registration
 * nor authority to import a different company/root or to activate anything at a branch.
 */
public class NativeCompanyPublicationTrust internal constructor(
    public val company: CompanyScope,
    public val rootFingerprint: String,
) {
    private val completed = MutableStateFlow<List<VerifiedNativeCompanyPublication>>(emptyList())
    private val completedSnapshot = MutableStateFlow<NativeCompanyBusinessPublicationSnapshot?>(null)

    /** No native IO or accepting completeness fallback; refreshed by the actual owner. */
    public fun currentCompletedSnapshot(): NativeCompanyBusinessPublicationSnapshot? = completedSnapshot.value

    internal fun invalidateCompletedSnapshot() { completedSnapshot.value = null }

    internal fun retainCompletedSnapshot(snapshot: NativeCompanyBusinessPublicationSnapshot) {
        check(snapshot.company == company && snapshot.rootFingerprint == rootFingerprint)
        val outputs = snapshot.completedPublications
        check(outputs.size == completed.value.size && outputs.all { output ->
            completed.value.any { it.keyId == output.signingKeyId && it.kind == output.proposal.kind &&
                it.digest == output.proposal.contentDigest }
        })
        completedSnapshot.value = snapshot
    }

    public fun verify(company: CompanyScope, keyId: DomainId, canonicalContent: ByteArray, signatureHex: String): Boolean {
        if (company != this.company || canonicalContent.size !in 1..CompanyBusinessPublicationProfileV1.MAXIMUM_CONTENT_BYTES ||
            signatureHex.length != 128 || signatureHex.any { it !in '0'..'9' && it !in 'a'..'f' }) return false
        val entry = completed.value.singleOrNull { it.keyId == keyId } ?: return false
        val prefix = entry.kind.domain.encodeToByteArray()
        val domainMatches = try { canonicalContent.size > prefix.size && prefix.indices.all { canonicalContent[it] == prefix[it] } }
        finally { prefix.fill(0) }
        if (!domainMatches || sha256(canonicalContent) != entry.digest) return false
        val signature = ByteArray(64) { index ->
            ((signatureHex[index * 2].digitToInt(16) shl 4) or signatureHex[index * 2 + 1].digitToInt(16)).toByte()
        }
        val point = entry.publicKey.copyBytes()
        return try { nativeBusinessPublicationVerifyEd25519(point, canonicalContent, signature) }
        finally { point.fill(0); signature.fill(0) }
    }

    internal fun retainVerified(entries: List<VerifiedNativeCompanyPublication>) {
        check(entries.size <= CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS && entries.map { it.keyId }.distinct().size == entries.size)
        val previous = completed.value
        check(previous.all { old -> entries.any { it == old } })
        completed.value = entries.toList()
    }
    override fun toString(): String = "NativeCompanyPublicationTrust([REDACTED])"
}

internal data class VerifiedNativeCompanyPublication(
    val keyId: DomainId, val kind: CompanyBusinessPublicationKind, val digest: PayloadDigest, val publicKey: SelfOwnedOctetsV1,
)

/** No caller selects keys, paths, root anchors, signing providers or an approval callback. */
public class LocalCompanyBusinessPublications internal constructor(
    private val controller: LocalCompanyBusinessPublicationController,
    public val trust: NativeCompanyPublicationTrust,
) {
    /** Unavailable until the actual native owner observes a complete, unambiguous ledger. */
    public fun currentCompletedSnapshot(): NativeCompanyBusinessPublicationSnapshot? = trust.currentCompletedSnapshot()
    /** Legacy unbound facade. Original serial preparation requires protected requester custody. */
    public suspend fun prepareOriginalSerialAuthority(): LocalCompanyAuthorityResult<CompanyBusinessPublicationAction> =
        (controller as? LocalCompanySerialRegistrationController)?.prepareOriginalSerialAuthority()
            ?: LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)
    /** Actual settled original sole issuer only; absence/uncertainty remains unavailable. */
    public suspend fun readOriginalSerialAuthority(): LocalCompanyAuthorityResult<NativeOriginalLocalSerialAuthority> =
        (controller as? LocalCompanySerialRegistrationController)?.readOriginalSerialAuthority()
            ?: LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)
    /** Inert original recovery identity, never a registration/namespace grant. */
    public suspend fun readOriginalSerialCustody(): LocalCompanyAuthorityResult<OriginalLocalSerialCustodyReferenceV1> =
        (controller as? LocalCompanySerialRegistrationController)?.readOriginalSerialCustody()
            ?: LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)
    /** Settles only an actually retained exact next cut; never replays or signs content. */
    public suspend fun recoverOriginalSerialCustody(originalActionId: DomainId): LocalCompanyAuthorityResult<OriginalLocalSerialCustodyReferenceV1> =
        (controller as? LocalCompanySerialRegistrationController)?.recoverOriginalSerialCustody(originalActionId)
            ?: LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)
    /** Actual allocated branch AND completed original serial authority; not a P2 namespace shortcut. */
    public suspend fun originalSerialRegistrationAllocation(): LocalCompanyAuthorityResult<NativeLocalSerialRegistrationAllocation> =
        (controller as? LocalCompanySerialRegistrationController)?.originalSerialRegistrationAllocation()
            ?: LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)
    public suspend fun prepare(proposal: CompanyBusinessPublicationProposal): LocalCompanyAuthorityResult<CompanyBusinessPublicationAction> =
        controller.prepare(proposal)
    /** The supplied buffer transfers on every outcome. This action is explicitly independent of employee login. */
    public suspend fun approve(action: CompanyBusinessPublicationAction, headLogin: String, headSecret: CharArray):
        LocalCompanyAuthorityResult<CompanyBusinessPublicationOutput> = controller.approve(action, headLogin, headSecret)
    public suspend fun recover(originalActionId: DomainId): LocalCompanyAuthorityResult<CompanyBusinessPublicationOutput> =
        controller.recover(originalActionId)
    /** Rejoins PREPARED bytes only. It does not regenerate, sign or repair a signing intent. */
    public suspend fun review(originalActionId: DomainId): LocalCompanyAuthorityResult<CompanyBusinessPublicationAction> =
        controller.review(originalActionId)
    public suspend fun retained(): LocalCompanyAuthorityResult<List<CompanyBusinessPublicationReference>> = controller.retained()
    override fun toString(): String = "LocalCompanyBusinessPublications([REDACTED])"
}

internal interface LocalCompanyBusinessPublicationController {
    suspend fun prepare(proposal: CompanyBusinessPublicationProposal): LocalCompanyAuthorityResult<CompanyBusinessPublicationAction>
    suspend fun approve(action: CompanyBusinessPublicationAction, headLogin: String, headSecret: CharArray):
        LocalCompanyAuthorityResult<CompanyBusinessPublicationOutput>
    suspend fun recover(originalActionId: DomainId): LocalCompanyAuthorityResult<CompanyBusinessPublicationOutput>
    suspend fun review(originalActionId: DomainId): LocalCompanyAuthorityResult<CompanyBusinessPublicationAction>
    suspend fun retained(): LocalCompanyAuthorityResult<List<CompanyBusinessPublicationReference>>
}

internal interface SelfOwnedCompanyBusinessPublicationsOwner {
    suspend fun readOriginalBusinessPublications(): LocalCompanyAuthorityResult<LocalCompanyBusinessPublications>
}

/** Same native lifetime read only; missing support stays unavailable and never creates a root. */
public suspend fun SelfOwnedLocalSecurityContext.readOriginalBusinessPublications(): LocalCompanyAuthorityResult<LocalCompanyBusinessPublications> =
    (controller as? SelfOwnedCompanyBusinessPublicationsOwner)?.readOriginalBusinessPublications()
        ?: LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)

public suspend fun SelfOwnedNativeHistoryCustody.readOriginalBusinessPublications(): LocalCompanyAuthorityResult<LocalCompanyBusinessPublications> =
    (controller as? SelfOwnedCompanyBusinessPublicationsOwner)?.readOriginalBusinessPublications()
        ?: LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)

internal expect fun nativeBusinessPublicationVerifyEd25519(publicKey: ByteArray, content: ByteArray, signature: ByteArray): Boolean
