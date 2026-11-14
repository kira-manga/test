package com.ukkera.pos.security

/**
 * Closed family of native history-database identities. Adding this parent changes no byte of the
 * existing [NativeHistoryDatabaseIdentityV2] encoding inside history records. A history record holds
 * either the POSIX [NativeHistoryDatabaseIdentityV2] (Android, macOS) or the Windows
 * [NativeHistoryDatabaseBindingWindowsV1]; the bare [NativeHistoryDatabaseIdentityWindowsV1] file
 * identity is never a record member. Possessing an identity value is DATA only, never custody,
 * history or writer authority.
 */
public sealed interface NativeHistoryDatabaseIdentity

/**
 * Windows Room/SQLite database file identity: the 64-bit FILE_ID_INFO volume serial number (16
 * lowercase hex digits), the 128-bit file ID (32 lowercase hex digits) and the owner account SID.
 * No POSIX device/inode/uid is fabricated for Windows.
 */
public data class NativeHistoryDatabaseIdentityWindowsV1(
    public val volumeSerial: String,
    public val fileId: String,
    public val ownerSid: String,
) : NativeHistoryDatabaseIdentity {
    init {
        require(volumeSerial.length == 16 && volumeSerial.all { it in '0'..'9' || it in 'a'..'f' })
        require(fileId.length == 32 && fileId.all { it in '0'..'9' || it in 'a'..'f' })
        require(SelfOwnedInstalledIdentityGrammarV2.accountSid(ownerSid))
    }
    override fun toString(): String = "NativeHistoryDatabaseIdentityWindowsV1([REDACTED])"
}

/**
 * Windows original Room database binding as retained in a history record: the exact Windows file
 * identity plus the same namespace digest and Room identity hash that [NativeHistoryDatabaseIdentityV2]
 * carries. Only a Windows V2 Master original record may hold it (see [NativeHistoryAnchorRecordV2]).
 */
public data class NativeHistoryDatabaseBindingWindowsV1(
    public val file: NativeHistoryDatabaseIdentityWindowsV1,
    public val namespaceDigest: PayloadDigest,
    public val roomIdentityHash: String,
) : NativeHistoryDatabaseIdentity {
    init { require(roomIdentityHash.length == 32 && roomIdentityHash.all { it in "0123456789abcdef" }) }
    override fun toString(): String = "NativeHistoryDatabaseBindingWindowsV1([REDACTED])"
}

/** Namespace digest of a record-member database identity; the bare Windows file identity has none. */
internal val NativeHistoryDatabaseIdentity.boundNamespaceDigest: PayloadDigest
    get() = when (this) {
        is NativeHistoryDatabaseIdentityV2 -> namespaceDigest
        is NativeHistoryDatabaseBindingWindowsV1 -> namespaceDigest
        is NativeHistoryDatabaseIdentityWindowsV1 -> throw IllegalStateException("native.history.database.unbound")
    }

/** Room identity hash of a record-member database identity; the bare Windows file identity has none. */
internal val NativeHistoryDatabaseIdentity.boundRoomIdentityHash: String
    get() = when (this) {
        is NativeHistoryDatabaseIdentityV2 -> roomIdentityHash
        is NativeHistoryDatabaseBindingWindowsV1 -> roomIdentityHash
        is NativeHistoryDatabaseIdentityWindowsV1 -> throw IllegalStateException("native.history.database.unbound")
    }

/** Bounded canonical framing for [NativeHistoryDatabaseIdentityWindowsV1]; decoding grants nothing. */
internal object NativeHistoryDatabaseIdentityWireWindowsV1 {
    const val PROFILE: String = "ukkera.history/native-database-identity"
    const val DOMAIN: String = "native-history-database-identity/windows-v1"
    const val FORMAT_VERSION: Int = 1
    const val MAXIMUM_BYTES: Int = 1_024

    fun encode(value: NativeHistoryDatabaseIdentityWindowsV1): ByteArray =
        SelfOwnedInstalledIdentityWireV2.frame(PROFILE, DOMAIN, MAXIMUM_BYTES) {
            int(FORMAT_VERSION); string(value.volumeSerial); string(value.fileId); string(value.ownerSid)
        }

    fun decode(bytes: ByteArray): SelfOwnedInstalledIdentityResultV2<NativeHistoryDatabaseIdentityWindowsV1> =
        selfOwnedIdentityGuarded {
            if (bytes.size > MAXIMUM_BYTES) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_OVERSIZE)
            val reader = SelfOwnedIdentityReader(bytes)
            if (reader.string() != PROFILE) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_PROFILE_UNSUPPORTED)
            if (reader.string() != DOMAIN) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_DOMAIN)
            if (reader.int() != FORMAT_VERSION) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_VERSION)
            val value = NativeHistoryDatabaseIdentityWindowsV1(reader.string(), reader.string(), reader.string())
            reader.finish()
            val canonical = encode(value)
            try { if (!canonical.contentEquals(bytes)) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR) }
            finally { canonical.fill(0) }
            value
        }
}
