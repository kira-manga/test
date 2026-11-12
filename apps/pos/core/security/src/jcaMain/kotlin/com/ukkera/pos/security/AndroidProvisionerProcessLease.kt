package com.ukkera.pos.security

/**
 * Process exclusion is additional to the retained cross-process kernel registry lock. One
 * lease exists per exact native root pair, so an independent LIVE/TRAINING/other-slot root is
 * not blocked merely because another composition is open in this process. The same original's
 * root pair still admits at most one owner, and release remains tied to its actual close.
 */
internal class AndroidProvisionerProcessLease private constructor(
    private val root: RootKey,
) {
    private var released = false

    fun releaseAfterActualClose() { release() }
    fun releaseUnusedBeforeNativeOpen() { release() }

    private fun release() = synchronized(gate) {
        check(!released && current[root] === this)
        released = true
        current.remove(root)
    }

    private data class RootKey(
        val targetDirectoryName: String,
        val provisionerDirectoryName: String,
    )

    companion object {
        private val gate = Any()
        private val current = mutableMapOf<RootKey, AndroidProvisionerProcessLease>()

        /** The lease follows the exact fixed native roots, never a caller-selected path. */
        fun acquire(profile: SelfOwnedNativeProfileV1): AndroidProvisionerProcessLease? =
            acquire(RootKey(profile.targetDirectoryName, profile.provisionerDirectoryName))

        /** Existing LIVE target callers cannot select a training root through this entry. */
        fun acquire(): AndroidProvisionerProcessLease? = acquire(SelfOwnedNativeProfileV1.LIVE)

        private fun acquire(root: RootKey): AndroidProvisionerProcessLease? = synchronized(gate) {
            if (current.containsKey(root)) null else AndroidProvisionerProcessLease(root).also {
                current[root] = it
            }
        }
    }
}
