package com.ukkera.pos.security

/** A separate local profile, NOT offline Ed25519 V1, managed V2 or Room/runtime custody. */
public object SelfOwnedInstallationProfileV1 {
    public const val ID: String = "ukkera.installation/self-owned-local-p256/v1"
    public const val MINIMUM_ANDROID_API: Int = AndroidNativeKeyPolicy.MINIMUM_ANDROID_API
    public const val MAXIMUM_AUTHORITY_DOCUMENT_BYTES: Int = 65_536
    public const val MAXIMUM_REGISTRY_BYTES: Int = 4 * 1024 * 1024
    public const val MAXIMUM_INSTALLERS: Int = 64
    public const val MAXIMUM_ALLOCATIONS: Int = 4096
    public const val PASSWORD_ITERATIONS: Int = 600_000
    internal const val MAXIMUM_REGISTRY_PLAINTEXT_BYTES: Int = MAXIMUM_REGISTRY_BYTES - 2048
    internal const val NATIVE_OPERATION_MILLIS: Long = 30_000L
    internal const val PENDING_ACTION_MILLIS: Long = 300_000L
    internal const val MAXIMUM_PASSWORD_CHARS: Int = 256
    internal const val MINIMUM_PASSWORD_CHARS: Int = 12
    internal const val MAXIMUM_FAILED_ATTEMPTS: Int = 1_000_000
    internal const val MAXIMUM_COOLDOWN_MILLIS: Long = 300_000L
}

/** OS_ISOLATED permits software AndroidKeyStore; NEVER a fallback from a hardware request. */
public enum class SelfOwnedKeyProtectionV1 { OS_ISOLATED, SECURE_HARDWARE, STRONGBOX }

public enum class SelfOwnedInstallationFailureV1 {
    BUSY,
    UNSUPPORTED_PLATFORM,
    INVALID_APPLICATION,
    USER_LOCKED,
    INVALID_INPUT,
    UNAVAILABLE,
    ALREADY_ENROLLED,
    EXISTING_AUTHORITY_REQUIRED,
    ORIGINAL_ACTION_REQUIRED,
    AUTHENTICATION_REFUSED,
    THROTTLED,
    ISSUANCE_WITHDRAWN,
    AUTHORITY_UNAVAILABLE,
    STATE_UNCERTAIN,
    KEY_STATE_UNCERTAIN,
    NATIVE_RETIRED,
    CAPACITY_EXHAUSTED,
    CONFLICT,
}

/** Actual owner-entered setup facts, not proof of an existing company's legal identity. */
public class SelfOwnedCompanyFactsV1(
    public val companyName: String,
    public val responsiblePersonName: String,
    public val installerLogin: String,
    public val protection: SelfOwnedKeyProtectionV1,
) {
    init {
        selfOwnedLabel(companyName)
        selfOwnedLabel(responsiblePersonName)
        selfOwnedLogin(installerLogin)
    }
    override fun toString(): String = "SelfOwnedCompanyFactsV1([REDACTED])"
}

/** Pin the already ENROLLED native company root. An imported/displayed hash is not its origin. */
public class SelfOwnedBranchFactsV1(
    public val enrolledRootFingerprint: String,
    public val branchName: String,
    public val protection: SelfOwnedKeyProtectionV1,
) {
    init { selfOwnedHexDigest(enrolledRootFingerprint); selfOwnedBranchLabel(branchName) }
    override fun toString(): String = "SelfOwnedBranchFactsV1([REDACTED])"
}

internal fun selfOwnedLabel(value: String) {
    require(value.length in 1..160 && value == value.trim() && value.isNotBlank())
    require(value.none { it.code < 32 || it.code == 127 })
    selfOwnedUtf16(value)
}

/**
 * Actual historical legacy V1 display label (`legacyBranchDisplayLabelV1`). This is a
 * distinct domain from the strict V2 `label`: 1..160 UTF-16 code units, no C0/DEL, exact
 * leading/trailing trim under the frozen historical Kotlin set, and nonblank after trim.
 * C1 controls and `Cf` code points are old-valid and remain valid. The decoder additionally
 * enforces the 640-byte pre-decode safety cap before UTF-8 decoding.
 */
internal fun selfOwnedBranchLabel(value: String) {
    selfOwnedUtf16(value)
    require(value.length in 1..160)
    require(value.none { it.code in 0x0000..0x001F || it.code == 0x007F })
    require(selfOwnedLegacyTrim(value) == value && selfOwnedLegacyTrim(value).isNotEmpty())
}

/** Field-local legacy label precedence after strict UTF-8 decoding. */
internal fun selfOwnedLegacyBranchLabelRefusal(
    value: String,
): SelfOwnedInstalledIdentityRefusalV2? {
    if (value.isEmpty()) return SelfOwnedInstalledIdentityRefusalV2.REFUSE_ENCODING
    if (value.length > 160) return SelfOwnedInstalledIdentityRefusalV2.REFUSE_OVERSIZE
    if (value.any { it.code in 0x0000..0x001F || it.code == 0x007F }) {
        return SelfOwnedInstalledIdentityRefusalV2.REFUSE_ENCODING
    }
    val trimmed = selfOwnedLegacyTrim(value)
    if (trimmed.isEmpty() || trimmed.length != value.length) {
        return SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR
    }
    return null
}

/** Exact historical Kotlin common trim set, frozen as twenty-eight literal BMP code points. */
private fun selfOwnedLegacyTrim(value: String): String {
    var start = 0
    var end = value.length
    while (start < end && selfOwnedLegacyTrimCodePoint(value[start].code)) start++
    while (end > start && selfOwnedLegacyTrimCodePoint(value[end - 1].code)) end--
    return value.substring(start, end)
}

private fun selfOwnedLegacyTrimCodePoint(code: Int): Boolean =
    code in 0x0009..0x000D ||
        code in 0x001C..0x001F ||
        code == 0x0020 || code == 0x00A0 || code == 0x1680 ||
        code in 0x2000..0x200A ||
        code == 0x2028 || code == 0x2029 || code == 0x202F ||
        code == 0x205F || code == 0x3000

/**
 * Code-point-correct `Cc`/`Cf` rejection. Kotlin's common [Char.category] classifies one
 * UTF-16 unit, so a supplementary format control such as U+110BD or U+E0020 would appear
 * as two `SURROGATE` units and evade a per-`Char` `FORMAT` check. The helper walks strict
 * UTF-16 code points; BMP categories come from `Char.category`, and the Unicode 15.0
 * supplementary `Cf` ranges are checked explicitly. `Cc` has no supplementary assignments.
 * Ordinary supplementary letters, symbols and emoji remain valid.
 */
internal fun selfOwnedHasUnicodeCcOrCf(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val first = value[index]
        if (first in '\uD800'..'\uDBFF' && index + 1 < value.length) {
            val second = value[index + 1]
            if (second in '\uDC00'..'\uDFFF') {
                val codePoint = ((first.code - 0xD800) shl 10) + (second.code - 0xDC00) + 0x10000
                if (selfOwnedSupplementaryFormatCodePoint(codePoint)) return true
                index += 2
                continue
            }
        }
        if (first.category == CharCategory.CONTROL || first.category == CharCategory.FORMAT) return true
        index++
    }
    return false
}

private fun selfOwnedSupplementaryFormatCodePoint(codePoint: Int): Boolean =
    codePoint == 0x110BD || codePoint == 0x110CD ||
        codePoint in 0x13430..0x1343F ||
        codePoint in 0x1BCA0..0x1BCA3 ||
        codePoint in 0x1D173..0x1D17A ||
        codePoint == 0xE0001 ||
        codePoint in 0xE0020..0xE007F

internal fun selfOwnedLogin(value: String) {
    require(value.length in 3..64 && value[0] in 'a'..'z')
    require(value.all { it in 'a'..'z' || it in '0'..'9' || it in "._-" })
}

internal fun selfOwnedHexDigest(value: String) {
    require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' })
}

internal fun selfOwnedUtf16(value: String) {
    var at = 0
    while (at < value.length) {
        val c = value[at++]
        if (c in '\uD800'..'\uDBFF') require(at < value.length && value[at++] in '\uDC00'..'\uDFFF')
        else require(c !in '\uDC00'..'\uDFFF')
    }
}

internal fun selfOwnedPasswordInput(value: CharArray): Boolean =
    value.size in SelfOwnedInstallationProfileV1.MINIMUM_PASSWORD_CHARS..
        SelfOwnedInstallationProfileV1.MAXIMUM_PASSWORD_CHARS && validLocalPasswordInput(value)

internal fun selfOwnedHex(bytes: ByteArray): String {
    val alphabet = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        for (b in bytes) { val v = b.toInt() and 255; append(alphabet[v ushr 4]); append(alphabet[v and 15]) }
    }
}

internal class SelfOwnedFailureV1(val reason: SelfOwnedInstallationFailureV1) :
    RuntimeException("Self-owned local provisioner unavailable")

internal fun selfOwnedFail(reason: SelfOwnedInstallationFailureV1): Nothing = throw SelfOwnedFailureV1(reason)

/** Private immutable bounded bytes. Copies handed to crypto/IO are owned and cleared there. */
internal class SelfOwnedOctetsV1(bytes: ByteArray, maximum: Int = SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES) {
    private val value: ByteArray
    init { require(bytes.size <= maximum); value = bytes.copyOf() }
    val size: Int get() = value.size
    fun copyBytes(): ByteArray = value.copyOf()
    fun same(bytes: ByteArray): Boolean = value.contentEquals(bytes)
    override fun equals(other: Any?): Boolean = other is SelfOwnedOctetsV1 && value.contentEquals(other.value)
    override fun hashCode(): Int = value.contentHashCode()
    override fun toString(): String = "SelfOwnedOctetsV1([REDACTED])"
}
