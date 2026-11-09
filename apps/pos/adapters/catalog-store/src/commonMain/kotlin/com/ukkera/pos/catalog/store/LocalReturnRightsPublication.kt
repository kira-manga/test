package com.ukkera.pos.catalog.store

import com.ukkera.pos.security.CompanyBusinessPublicationKind
import com.ukkera.pos.security.CompanyBusinessPublicationProfileV1
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
public enum class LocalReturnRightsReason {
    CHANGE_OF_MIND, WRONG_SUPPLIED_ITEM, DEFECTIVE_OR_DAMAGED,
    MERCHANT_PREPARATION_ERROR, QUALITY_COMPLAINT, FAILED_DELIVERY, UNPERFORMED_SERVICE,
}

@Serializable
public enum class LocalReturnRightsTreatment { RECEIPT_WINDOW, CATEGORY_EXCEPTION_REVIEW, EVIDENCE_OR_POLICY_REVIEW }

@Serializable
public enum class LocalReturnRightsExceptionBasis {
    PERISHABLE_OR_PREPARED_FOOD, CORRECTLY_SUPPLIED_CUSTOMER_SPECIFIED_GOODS,
    VERIFIED_OPENED_HYGIENE_SENSITIVE_PACKAGING,
}

/** Documentary category/reason rule, not proof that any particular sale meets its conditions. */
@Serializable
public data class LocalReturnRightsRule(
    public val categoryCode: String,
    public val reason: LocalReturnRightsReason,
    public val treatment: LocalReturnRightsTreatment,
    public val receiptWindowDays: Int? = null,
    public val coveredResolutionDays: Int? = null,
    public val exceptionBasis: LocalReturnRightsExceptionBasis? = null,
) {
    init {
        require(categoryCode.length in 1..96 && categoryCode.first().isReturnCategoryAlphanumeric() &&
            categoryCode.all { it.isReturnCategoryAlphanumeric() || it in "_-." })
        when (treatment) {
            LocalReturnRightsTreatment.RECEIPT_WINDOW -> {
                require(exceptionBasis == null)
                when (reason) {
                    LocalReturnRightsReason.CHANGE_OF_MIND ->
                        require(receiptWindowDays != null && receiptWindowDays >= 14 && coveredResolutionDays == null)
                    LocalReturnRightsReason.DEFECTIVE_OR_DAMAGED ->
                        require(receiptWindowDays != null && receiptWindowDays >= 30 && coveredResolutionDays in 1..7)
                    else -> throw IllegalArgumentException("No declared ordinary period for this reason")
                }
            }
            LocalReturnRightsTreatment.CATEGORY_EXCEPTION_REVIEW ->
                require(reason == LocalReturnRightsReason.CHANGE_OF_MIND && exceptionBasis != null &&
                    receiptWindowDays == null && coveredResolutionDays == null)
            LocalReturnRightsTreatment.EVIDENCE_OR_POLICY_REVIEW ->
                require(receiptWindowDays == null && coveredResolutionDays == null && exceptionBasis == null)
        }
    }
}

/**
 * Company-owned Egypt documentary policy. Sale promises and actual customer receipt facts
 * are separate immutable sources; neither applicability nor refund authority is inferred here.
 */
@Serializable(with = LocalReturnRightsPolicySerializerV1::class)
public class LocalPublishedReturnRightsPolicy(
    public val company: CompanyScope,
    public val publicationId: DomainId,
    public val policyId: DomainId,
    public val revision: Revision,
    public val jurisdictionCode: String,
    public val legalSourceUri: String,
    public val legalSourceAccessDate: String,
    rules: List<LocalReturnRightsRule>,
    public val continuingRightsEnglish: String,
    public val continuingRightsArabic: String,
) {
    private val ruleValues: List<LocalReturnRightsRule> = rules.also { require(it.size in 1..128) }
        .toList().also(::requireReturnRightsRules)
        .sortedWith(returnRightsRuleOrder)
    /** A detached snapshot on every read, including on platforms with mutable List implementations. */
    public val rules: List<LocalReturnRightsRule> get() = ruleValues.toMutableList()

    init {
        require(jurisdictionCode == "EG")
        requireReturnRightsSourceUri(legalSourceUri)
        require(legalSourceAccessDate.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) &&
            legalSourceAccessDate.substring(0, 4) != "0000")
        BusinessDate.parseCanonical(legalSourceAccessDate)
        requireReturnRightsNotice(continuingRightsEnglish)
        requireReturnRightsNotice(continuingRightsArabic)
    }

    override fun equals(other: Any?): Boolean = this === other || other is LocalPublishedReturnRightsPolicy &&
        company == other.company && publicationId == other.publicationId && policyId == other.policyId &&
        revision == other.revision && jurisdictionCode == other.jurisdictionCode &&
        legalSourceUri == other.legalSourceUri && legalSourceAccessDate == other.legalSourceAccessDate &&
        ruleValues == other.ruleValues && continuingRightsEnglish == other.continuingRightsEnglish &&
        continuingRightsArabic == other.continuingRightsArabic

    override fun hashCode(): Int {
        var result = company.hashCode()
        for (value in listOf(publicationId, policyId, revision, jurisdictionCode, legalSourceUri,
            legalSourceAccessDate, ruleValues, continuingRightsEnglish, continuingRightsArabic)) {
            result = 31 * result + value.hashCode()
        }
        return result
    }
    override fun toString(): String = "LocalPublishedReturnRightsPolicy([REDACTED])"
}

/** Signature syntax only. Trust still comes from the original native company-publication owner. */
@Serializable
public data class LocalSignedReturnRightsPolicy(
    public val definition: LocalPublishedReturnRightsPolicy,
    public val signingKeyId: DomainId,
    public val signatureHex: String,
) {
    init { require(signatureHex.length == 128 && signatureHex.all { it in '0'..'9' || it in 'a'..'f' }) }
}

private fun Char.isReturnCategoryAlphanumeric(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
private val returnRightsRuleOrder: Comparator<LocalReturnRightsRule> =
    compareBy<LocalReturnRightsRule> { it.categoryCode }.thenBy { it.reason.name }

private fun requireReturnRightsRules(rules: List<LocalReturnRightsRule>) {
    require(rules.size in 1..128 && rules.map { it.categoryCode to it.reason }.distinct().size == rules.size)
}

private fun requireReturnRightsNotice(text: String) {
    require(text.isNotBlank() && text.length <= 2048 && text.none { it.code < 0x20 || it.code in 0x7f..0x9f })
    // Kotlin strings can contain isolated surrogates. Never sign replacement-character encoding.
    text.encodeToByteArray(throwOnInvalidSequence = true).fill(0)
}

private fun requireReturnRightsSourceUri(uri: String) {
    require(uri.length in 9..2048 && uri.startsWith("https://") &&
        uri.none { it.isWhitespace() || it.code < 0x20 || it.code in 0x7f..0x9f })
    val authority = uri.substring(8).takeWhile { it != '/' && it != '?' && it != '#' }
    require(authority.isNotEmpty() && '@' !in authority && '\\' !in uri)
    val port: String?
    if (authority.startsWith('[')) {
        val closing = authority.indexOf(']')
        require(closing > 1)
        requireReturnRightsIpLiteral(authority.substring(1, closing))
        val remainder = authority.substring(closing + 1)
        require(remainder.isEmpty() || remainder.startsWith(':'))
        port = if (remainder.isEmpty()) null else remainder.substring(1)
    } else {
        val host = authority.substringBefore(':')
        require(host.isNotEmpty())
        requireReturnRightsIriPart(host, "-._~!$&'()*+,;=")
        port = if (':' in authority) authority.substringAfter(':') else null
    }
    if (port != null) {
        require(port.isNotEmpty() && port.all { it in '0'..'9' } && port.toIntOrNull() in 1..65535)
    }
    val suffix = uri.substring(8 + authority.length)
    require(suffix.count { it == '#' } <= 1)
    val beforeFragment = suffix.substringBefore('#')
    requireReturnRightsIriPart(beforeFragment.substringBefore('?'), "-._~!$&'()*+,;=:@/")
    if ('?' in beforeFragment) requireReturnRightsIriPart(beforeFragment.substringAfter('?'), "-._~!$&'()*+,;=:@/?", true)
    if ('#' in suffix) requireReturnRightsIriPart(suffix.substringAfter('#'), "-._~!$&'()*+,;=:@/?")
    uri.encodeToByteArray(throwOnInvalidSequence = true).fill(0)
}

private fun requireReturnRightsIriPart(text: String, allowedAscii: String, allowPrivateUse: Boolean = false) {
    text.encodeToByteArray(throwOnInvalidSequence = true).fill(0)
    var index = 0
    while (index < text.length) {
        val character = text[index]
        if (character == '%') {
            require(index + 2 < text.length && text[index + 1].isReturnUriHex() && text[index + 2].isReturnUriHex())
            index += 3
        } else if (character.code >= 0xa0) {
            val scalar = if (character.isHighSurrogate()) {
                0x10000 + ((character.code - 0xd800) shl 10) + (text[index + 1].code - 0xdc00)
            } else character.code
            require(scalar !in 0xfdd0..0xfdef && (scalar and 0xffff) < 0xfffe)
            // RFC 3987 ucschar; iprivate is permitted only in the query.
            require(scalar in 0xa0..0xd7ff || scalar in 0xf900..0xfdcf || scalar in 0xfdf0..0xffef ||
                scalar in 0x10000..0xdfffd || scalar in 0xe1000..0xefffd ||
                allowPrivateUse && (scalar in 0xe000..0xf8ff || scalar in 0xf0000..0x10fffd))
            index += if (character.isHighSurrogate()) 2 else 1
        } else {
            // Preserve the original Arabic source reference as an IRI; do not normalize or fetch it.
            require(character.isReturnCategoryAlphanumeric() || character in allowedAscii)
            index++
        }
    }
}

private fun Char.isReturnUriHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun requireReturnRightsIpLiteral(literal: String) {
    if (literal.startsWith('v') || literal.startsWith('V')) {
        val separator = literal.indexOf('.')
        require(separator > 1 && literal.substring(1, separator).all { it.isReturnUriHex() })
        val address = literal.substring(separator + 1)
        require(address.isNotEmpty() && address.all { it.isReturnCategoryAlphanumeric() || it in "-._~!$&'()*+,;=:" })
        return
    }
    val compression = literal.indexOf("::")
    require(compression == literal.lastIndexOf("::"))
    val parts = if (compression < 0) literal.split(':') else {
        val left = literal.substring(0, compression)
        val right = literal.substring(compression + 2)
        (if (left.isEmpty()) emptyList() else left.split(':')) +
            (if (right.isEmpty()) emptyList() else right.split(':'))
    }
    var groups = parts.size
    for ((index, part) in parts.withIndex()) {
        if ('.' in part) {
            require(index == parts.lastIndex && literal.endsWith(part))
            val octets = part.split('.')
            require(octets.size == 4 && octets.all { it.length in 1..3 && it.all { c -> c in '0'..'9' } &&
                (it.length == 1 || it.first() != '0') && it.toIntOrNull() in 0..255 })
            groups++
        } else require(part.length in 1..4 && part.all { it.isReturnUriHex() })
    }
    require(if (compression < 0) groups == 8 else groups < 8)
}

/** Compatibility-sensitive field ordering and duplicate visibility belong to this v1 wire serializer. */
@Serializable
@SerialName("com.ukkera.pos.catalog.store.LocalPublishedReturnRightsPolicy")
private data class LocalReturnRightsPolicyWireV1(
    val company: CompanyScope,
    val publicationId: DomainId,
    val policyId: DomainId,
    val revision: Revision,
    val jurisdictionCode: String,
    val legalSourceUri: String,
    val legalSourceAccessDate: String,
    val rules: List<LocalReturnRightsRule>,
    val continuingRightsEnglish: String,
    val continuingRightsArabic: String,
)

internal object LocalReturnRightsPolicySerializerV1 : KSerializer<LocalPublishedReturnRightsPolicy> {
    private val wire = LocalReturnRightsPolicyWireV1.serializer()
    override val descriptor: SerialDescriptor = wire.descriptor
    override fun serialize(encoder: Encoder, value: LocalPublishedReturnRightsPolicy) {
        wire.serialize(encoder, LocalReturnRightsPolicyWireV1(value.company, value.publicationId, value.policyId,
            value.revision, value.jurisdictionCode, value.legalSourceUri, value.legalSourceAccessDate, value.rules,
            value.continuingRightsEnglish, value.continuingRightsArabic))
    }
    override fun deserialize(decoder: Decoder): LocalPublishedReturnRightsPolicy {
        val value = wire.deserialize(decoder)
        requireReturnRightsRules(value.rules)
        require(value.rules == value.rules.sortedWith(returnRightsRuleOrder))
        return LocalPublishedReturnRightsPolicy(value.company, value.publicationId, value.policyId, value.revision,
            value.jurisdictionCode, value.legalSourceUri, value.legalSourceAccessDate, value.rules,
            value.continuingRightsEnglish, value.continuingRightsArabic)
    }
}

/** Strict bounded canonical content; decoding does not supply a trust anchor or an activation. */
public object LocalReturnRightsPublicationCodec {
    public fun canonicalPublication(definition: LocalPublishedReturnRightsPolicy): ByteArray = boundedReturnRightsBytes(
        CompanyBusinessPublicationKind.RETURN_RIGHTS_V1.domain +
            localConfigurationJson.encodeToString(LocalPublishedReturnRightsPolicy.serializer(), definition))

    public fun decodeCanonicalPublication(bytes: ByteArray): LocalPublishedReturnRightsPolicy {
        requireReturnRightsSize(bytes)
        val content = bytes.copyOf()
        val prefix = CompanyBusinessPublicationKind.RETURN_RIGHTS_V1.domain.encodeToByteArray()
        try {
            require(content.size > prefix.size && prefix.indices.all { content[it] == prefix[it] })
            val definition = localConfigurationJson.decodeFromString(LocalPublishedReturnRightsPolicy.serializer(),
                content.decodeToString(startIndex = prefix.size, throwOnInvalidSequence = true))
            val canonical = canonicalPublication(definition)
            try { require(canonical.contentEquals(content)) } finally { canonical.fill(0) }
            return definition
        } finally { content.fill(0); prefix.fill(0) }
    }

    public fun signedPublication(value: LocalSignedReturnRightsPolicy): String {
        val bytes = signedPublicationBytes(value)
        return try { bytes.decodeToString(throwOnInvalidSequence = true) } finally { bytes.fill(0) }
    }

    public fun decodeSignedPublication(bytes: ByteArray): LocalSignedReturnRightsPolicy {
        requireReturnRightsSize(bytes)
        val content = bytes.copyOf()
        try {
            val value = localConfigurationJson.decodeFromString(LocalSignedReturnRightsPolicy.serializer(),
                content.decodeToString(throwOnInvalidSequence = true))
            val canonical = signedPublicationBytes(value)
            try { require(canonical.contentEquals(content)) } finally { canonical.fill(0) }
            return value
        } finally { content.fill(0) }
    }

    public fun decodeSignedPublication(value: String): LocalSignedReturnRightsPolicy {
        val bytes = boundedReturnRightsBytes(value)
        return try { decodeSignedPublication(bytes) } finally { bytes.fill(0) }
    }

    public fun verify(value: LocalSignedReturnRightsPolicy, signatures: CompanyPublicationSignatureVerifier): Boolean {
        // A typed value must not bypass the envelope ceiling enforced on received wire.
        val envelope = signedPublicationBytes(value)
        envelope.fill(0)
        val canonical = canonicalPublication(value.definition)
        return try { signatures.verify(value.definition.company, value.signingKeyId, canonical, value.signatureHex) }
        finally { canonical.fill(0) }
    }

    private fun signedPublicationBytes(value: LocalSignedReturnRightsPolicy): ByteArray = boundedReturnRightsBytes(
        localConfigurationJson.encodeToString(LocalSignedReturnRightsPolicy.serializer(), value))
}

private fun requireReturnRightsSize(bytes: ByteArray) {
    require(bytes.size in 1..CompanyBusinessPublicationProfileV1.MAXIMUM_CONTENT_BYTES)
}

private fun boundedReturnRightsBytes(value: String): ByteArray {
    require(value.length in 1..CompanyBusinessPublicationProfileV1.MAXIMUM_CONTENT_BYTES)
    val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
    try { requireReturnRightsSize(bytes) } catch (failure: IllegalArgumentException) {
        bytes.fill(0)
        throw failure
    }
    return bytes
}
