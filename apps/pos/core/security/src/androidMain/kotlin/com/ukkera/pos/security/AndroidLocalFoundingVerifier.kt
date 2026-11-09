package com.ukkera.pos.security

/** Disposable salt and native-peppered verifier, never password/KDF material or authority.
 * Copying into immutable registry accounts and abandonment cleanup cannot race. */
internal class AndroidLocalFoundingVerifier(salt: ByteArray, verifier: ByteArray,
    val boot: Int, val createdElapsedMillis: Long) {
    init {
        if (salt.size != 32 || verifier.size != 32 || boot < 0 || createdElapsedMillis < 0) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        }
    }
    private val salt = salt.copyOf()
    private val verifier = try { verifier.copyOf() } catch (failure: Throwable) {
        this.salt.fill(0); throw failure
    }
    private var cleared = false

    fun <T> copyIntoAccount(copy: (ByteArray, ByteArray) -> T): T = synchronized(this) {
        if (cleared) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        copy(salt, verifier)
    }

    fun copyForExecution(): AndroidLocalFoundingVerifier = copyIntoAccount { salt, verifier ->
        AndroidLocalFoundingVerifier(salt, verifier, boot, createdElapsedMillis)
    }

    fun clear() = synchronized(this) {
        cleared = true; salt.fill(0); verifier.fill(0)
    }

    override fun toString(): String = "AndroidLocalFoundingVerifier([REDACTED])"
}
