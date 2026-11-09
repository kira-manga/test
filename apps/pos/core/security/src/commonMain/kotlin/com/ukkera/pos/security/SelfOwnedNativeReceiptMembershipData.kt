package com.ukkera.pos.security

/**
 * Lifetime-owned encrypted DATA blocks, separate from the original history reader's scratch.
 * IDs and bytes are inert data: no root, native authority, original-read handle or permission
 * can be imported through this API. Only the actual native custody can construct this resource.
 */
public class SelfOwnedNativeReceiptMembershipData internal constructor(
    internal val controller: SelfOwnedNativeReceiptMembershipDataController,
) {
    /** Append one immutable block. The bounded returned ID belongs only to this resource. */
    public suspend fun append(bytes: ByteArray): ByteArray = controller.append(bytes)

    /** Exact authenticated bytes. Missing, short, foreign or invalid DATA is never absence. */
    public suspend fun read(blockId: ByteArray): ByteArray = controller.read(blockId)

    /** Actual guards and resource checks, not a promise that a later write cannot fail. */
    public suspend fun preflight(): Unit = controller.preflight()

    /**
     * Actually close all owned handles without deleting the encrypted DATA or retaining an
     * original read. This also supports cleanup after denial in the same watched Room loan.
     * A completed park is idempotent; a consumed uncertain close is never retried.
     */
    public suspend fun park(): Unit = controller.park()

    override fun toString(): String = "SelfOwnedNativeReceiptMembershipDataV1([REDACTED])"

    public companion object {
        public const val MAX_BLOCK_BYTES: Int = 65_536
        public const val BLOCK_ID_BYTES: Int = 32
    }
}

/** Actual-resource seam only. There is no accepting in-memory or unsupported default. */
internal interface SelfOwnedNativeReceiptMembershipDataController {
    suspend fun append(bytes: ByteArray): ByteArray
    suspend fun read(blockId: ByteArray): ByteArray
    suspend fun preflight()
    suspend fun park()
}
