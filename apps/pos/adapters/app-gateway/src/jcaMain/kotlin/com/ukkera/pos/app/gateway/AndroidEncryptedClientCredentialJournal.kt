package com.ukkera.pos.app.gateway

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.types.DomainId
import java.nio.ByteBuffer

/**
 * Actual Android credential journal over the retained native journal owner, not a supplied
 * key/path/custody callback. Construction is existing-only and module-private. This stores
 * revocation/recovery material; opening it never restores an interactive login or admits a
 * master. Native enrollment and cross-restart rollback admission are separate owner gates.
 */
public class AndroidEncryptedClientCredentialJournal private constructor(
    private val file: ClientJournalFileCore,
) : LocalClientCredentialJournal(file.deviceOwner) {
    override internal suspend fun read(): JournalRead<StoredClientCredentials> =
        when (val result = file.owned {
            object : ClientJournalTask<StoredClientCredentials> {
                override fun execute(): StoredClientCredentials = readState()
                override fun discard(value: StoredClientCredentials) { value.destroy() }
                override fun clearInputs() = Unit
            }
        }) {
            is ClientJournalOutcome.Value -> JournalRead.Value(result.value)
            ClientJournalOutcome.Unavailable -> JournalRead.Unavailable
        }

    override internal suspend fun replace(
        expectedRevision: Long,
        next: StoredClientCredentials,
    ): JournalWrite = when (val result = file.owned {
        // Admission precedes this bounded private copy. No caller-owned token is retained or
        // destroyed. The native task owns these bytes through cancellation and actual drain.
        require(expectedRevision in 0 until Long.MAX_VALUE && next.revision == expectedRevision + 1)
        val owned = encodeAndroidCredentials(next, file)
        try {
            object : ClientJournalTask<JournalWrite> {
                override fun execute(): JournalWrite {
                    val captured = decodeAndroidCredentials(owned, file)
                    try {
                        val actual = readState()
                        try {
                            if (actual.revision != expectedRevision) return JournalWrite.IDENTITY_CONFLICT
                            file.replaceManifest(owned, MAX_CLIENT_CREDENTIAL_BYTES)
                            return JournalWrite.DURABLE
                        } finally { actual.destroy() }
                    } finally { captured.destroy() }
                }
                override fun discard(value: JournalWrite) = Unit
                override fun clearInputs() { owned.fill(0) }
            }
        } catch (failure: Throwable) { owned.fill(0); throw failure }
    }) {
        is ClientJournalOutcome.Value -> result.value
        ClientJournalOutcome.Unavailable -> JournalWrite.UNAVAILABLE
    }

    private fun readState(): StoredClientCredentials =
        file.readManifest(MAX_CLIENT_CREDENTIAL_BYTES).useWiped { decodeAndroidCredentials(it, file) }

    /** Retire this journal view and drain its admitted work; the root owns native-owner close. */
    override public suspend fun close() { file.closeView() }

    override fun toString(): String = "AndroidEncryptedClientCredentialJournalV1([REDACTED])"

    internal companion object {
        /** Called only while the native owner is explicitly enrolling the untouched original C1. */
        fun originalEmptyManifest(file: ClientJournalFileCore): ByteArray =
            encodeAndroidCredentials(StoredClientCredentials(), file)

        /** Called only inside explicit, genuinely qualified paired-device credential enrollment. */
        fun pairedDeviceEmptyManifest(file: ClientJournalFileCore): ByteArray {
            check(file.kind == ClientJournalKind.CREDENTIALS && file.actorId == null)
            return encodeAndroidCredentials(StoredClientCredentials(formatVersion = 3), file)
        }

        fun fromExisting(file: ClientJournalFileCore): AndroidEncryptedClientCredentialJournal {
            check(file.kind == ClientJournalKind.CREDENTIALS && file.actorId == null)
            return AndroidEncryptedClientCredentialJournal(file)
        }

        /** Real pre-delivery decode, invoked inside the same retained native acquisition. */
        fun validateExisting(file: ClientJournalFileCore) {
            file.readManifest(MAX_CLIENT_CREDENTIAL_BYTES).useWiped {
                decodeAndroidCredentials(it, file).destroy()
            }
        }
    }
}

/**
 * Separate explicit Android binary profile. Never turn tokens into String/JSON/Base64.
 * The enrollment bodies contain no password/token and retain their complete existing codec.
 */
private fun encodeAndroidCredentials(
    state: StoredClientCredentials,
    file: ClientJournalFileCore,
): ByteArray {
    state.validate(file.deviceOwner)
    val out = ByteBuffer.allocate(MAX_CLIENT_CREDENTIAL_BYTES)
    try {
        out.putInt(when (state.formatVersion) {
            1 -> ANDROID_CREDENTIAL_FORMAT_V1
            2 -> ANDROID_CREDENTIAL_FORMAT_V2
            3 -> ANDROID_CREDENTIAL_FORMAT_V3
            4 -> ANDROID_CREDENTIAL_FORMAT_V4
            else -> error("client.credentials.format")
        })
        out.putLong(state.revision)
        out.credentialIdentityV1(file.deviceOwner)
        out.credentialIdV1(file.journalId)
        out.credentialIdV1(file.keyId)
        out.putInt(state.attempts.size)
        for (attempt in state.attempts) {
            out.credentialIdV1(attempt.id)
            out.credentialTextV1(attempt.login.value)
            out.credentialBinding(attempt.binding, state.formatVersion)
            out.put(if (attempt.explicitDeviceMove) 1.toByte() else 0.toByte())
        }
        out.putInt(state.sessions.size)
        for (session in state.sessions) {
            out.credentialIdV1(session.localId)
            out.credentialIdV1(session.identity.actorId)
            out.credentialTextV1(session.login.value)
            out.credentialBinding(session.binding, state.formatVersion)
            out.credentialIdV1(session.sessionId)
            out.putLong(session.generation.value)
            out.credentialMomentV1(session.accessExpiresAt)
            out.credentialMomentV1(session.workExpiresAt)
            out.credentialTextV1(when (session.phase) {
                StoredSessionPhaseV1.RETAINED -> "RETAINED"
                StoredSessionPhaseV1.REFRESH_PENDING -> "REFRESH_PENDING"
                StoredSessionPhaseV1.REVOCATION_PENDING -> "REVOCATION_PENDING"
            })
            out.put(if (session.originalRevoked) 1.toByte() else 0.toByte())
            session.access.useBytes { check(it.size == 52); out.put(it) }
            session.refresh.useBytes { check(it.size == 52); out.put(it) }
        }
        out.putInt(state.enrollments.size)
        for (entry in state.enrollments) {
            out.credentialTextV1(journalJson.encodeToString(StoredClientEnrollmentV1.serializer(), entry))
        }
        if (state.formatVersion >= 2) {
            out.putInt(state.administrations.size)
            for (entry in state.administrations)
                out.credentialTextV1(journalJson.encodeToString(StoredClientAdministrationV1.serializer(), entry))
        }
        if (state.formatVersion >= 4) {
            out.putInt(state.employeeCreations.size)
            for (entry in state.employeeCreations) entry.copyWireBytes().useWiped { bytes ->
                out.putInt(bytes.size); out.put(bytes)
            }
        }
        return out.array().copyOf(out.position())
    } finally { out.array().fill(0) }
}

private fun decodeAndroidCredentials(
    bytes: ByteArray,
    file: ClientJournalFileCore,
): StoredClientCredentials {
    check(bytes.size in 1..MAX_CLIENT_CREDENTIAL_BYTES)
    val sessions = ArrayList<StoredClientSessionV1>()
    try {
        val input = ByteBuffer.wrap(bytes)
        val version = when (input.int) {
            ANDROID_CREDENTIAL_FORMAT_V1 -> 1
            ANDROID_CREDENTIAL_FORMAT_V2 -> 2
            ANDROID_CREDENTIAL_FORMAT_V3 -> 3
            ANDROID_CREDENTIAL_FORMAT_V4 -> 4
            else -> error("client.credentials.format")
        }
        val revision = input.long
        check(revision >= 0 && input.credentialIdentityV1() == file.deviceOwner &&
            input.credentialIdV1() == file.journalId && input.credentialIdV1() == file.keyId)
        val attempts = List(input.credentialCountV1(MAX_CLIENT_AUTH_RECORDS)) {
            StoredLoginAttemptV1(input.credentialIdV1(), LocalLoginIdentifier(input.credentialTextV1(256)),
                input.credentialBinding(version), input.credentialFlagV1())
        }
        repeat(input.credentialCountV1(MAX_CLIENT_AUTH_RECORDS - attempts.size)) {
            val localId = input.credentialIdV1()
            val actor = input.credentialIdV1()
            val login = LocalLoginIdentifier(input.credentialTextV1(256))
            val binding = input.credentialBinding(version)
            val session = input.credentialIdV1()
            val generation = SessionGeneration(input.long)
            val accessExpiry = input.credentialMomentV1()
            val workExpiry = input.credentialMomentV1()
            val phase = when (input.credentialTextV1(32)) {
                "RETAINED" -> StoredSessionPhaseV1.RETAINED
                "REFRESH_PENDING" -> StoredSessionPhaseV1.REFRESH_PENDING
                "REVOCATION_PENDING" -> StoredSessionPhaseV1.REVOCATION_PENDING
                else -> error("client.credentials.phase")
            }
            val revoked = input.credentialFlagV1()
            var access: OpaqueCredential? = null
            var refresh: OpaqueCredential? = null
            try {
                access = input.credentialTokenV1()
                refresh = input.credentialTokenV1()
                val value = StoredClientSessionV1(localId,
                    LocalClientIdentity(file.deviceOwner.scope, actor, file.deviceOwner.deviceId),
                    login, binding, session, generation, accessExpiry, workExpiry,
                    phase, revoked, requireNotNull(access), requireNotNull(refresh))
                sessions.add(value)
                access = null
                refresh = null
            } finally { access?.destroy(); refresh?.destroy() }
        }
        val enrollments = List(input.credentialCountV1(MAX_CLIENT_ENROLLMENTS)) {
            journalJson.decodeFromString(StoredClientEnrollmentV1.serializer(), input.credentialTextV1(512 * 1024))
        }
        val administrations = if (version == 1) emptyList() else List(input.credentialCountV1(MAX_CLIENT_ADMINISTRATIONS)) {
            journalJson.decodeFromString(StoredClientAdministrationV1.serializer(), input.credentialTextV1(MAX_CLIENT_ADMINISTRATION_BYTES))
        }
        val employeeCreations = if (version < 4) emptyList() else List(input.credentialCountV1(MAX_CLIENT_EMPLOYEE_CREATIONS)) {
            val size = input.int
            check(size in 1..MAX_CLIENT_EMPLOYEE_CREATION_BYTES && size <= input.remaining())
            ByteArray(size).useWiped { input.get(it); StoredClientEmployeeCreationV1.decode(it, file.deviceOwner) }
        }
        check(!input.hasRemaining())
        val state = StoredClientCredentials(revision, attempts, sessions, enrollments, administrations, version, employeeCreations)
        state.validate(file.deviceOwner)
        // Reject alternate framing/UTF-8/enum/JSON projections, not just a successful decode.
        encodeAndroidCredentials(state, file).useWiped { check(it.contentEquals(bytes)) }
        return state
    } catch (failure: Throwable) {
        sessions.forEach { it.destroy() }
        throw failure
    }
}

private const val ANDROID_CREDENTIAL_FORMAT_V1 = 0x41434331
private const val ANDROID_CREDENTIAL_FORMAT_V2 = 0x41434332
private const val ANDROID_CREDENTIAL_FORMAT_V3 = 0x41434333
private const val ANDROID_CREDENTIAL_FORMAT_V4 = 0x41434334
private val ANDROID_CREDENTIAL_ID_V1 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

private fun ByteBuffer.credentialTextV1(value: String) {
    value.encodeToByteArray(throwOnInvalidSequence = true).useWiped { putInt(it.size); put(it) }
}
private fun ByteBuffer.credentialTextV1(maximum: Int): String {
    val size = int
    check(size in 1..maximum && size <= remaining())
    return ByteArray(size).useWiped { get(it); it.decodeToString(throwOnInvalidSequence = true) }
}
private fun ByteBuffer.credentialIdV1(value: DomainId) {
    check(ANDROID_CREDENTIAL_ID_V1.matches(value.value)); credentialTextV1(value.value)
}
private fun ByteBuffer.credentialIdV1(): DomainId = credentialTextV1(36).let {
    check(ANDROID_CREDENTIAL_ID_V1.matches(it)); DomainId(it)
}
private fun ByteBuffer.credentialBinding(value: LocalClientAuthenticationBinding, version: Int) {
    if (version >= 3) {
        put(value.credentialTagV3())
        credentialIdV1(value.credentialIdV3())
    } else {
        check(version in 1..2 && value is LocalClientAuthenticationBinding.MasterServiceIncarnation)
        credentialIdV1(value.serviceIncarnationId)
    }
}
private fun ByteBuffer.credentialBinding(version: Int): LocalClientAuthenticationBinding =
    if (version >= 3) credentialBindingV3(get(), credentialIdV1())
    else {
        check(version in 1..2)
        LocalClientAuthenticationBinding.MasterServiceIncarnation(credentialIdV1())
    }
private fun ByteBuffer.credentialIdentityV1(value: LocalClientDeviceIdentity) {
    credentialIdV1(value.scope.organizationId); credentialIdV1(value.scope.companyId)
    credentialIdV1(value.scope.branchId); credentialIdV1(value.deviceId)
}
private fun ByteBuffer.credentialIdentityV1(): LocalClientDeviceIdentity =
    LocalClientDeviceIdentity(BranchScope(credentialIdV1(), credentialIdV1(), credentialIdV1()), credentialIdV1())
private fun ByteBuffer.credentialMomentV1(value: MonotonicMoment) {
    credentialIdV1(value.bootId); putLong(value.elapsedMillis)
}
private fun ByteBuffer.credentialMomentV1(): MonotonicMoment = MonotonicMoment(credentialIdV1(), long)
private fun ByteBuffer.credentialCountV1(maximum: Int): Int = int.also { check(it in 0..maximum) }
private fun ByteBuffer.credentialFlagV1(): Boolean = when (get().toInt()) {
    0 -> false
    1 -> true
    else -> error("client.credentials.flag")
}
private fun ByteBuffer.credentialTokenV1(): OpaqueCredential =
    ByteArray(52).useWiped { get(it); OpaqueCredential(it) }
