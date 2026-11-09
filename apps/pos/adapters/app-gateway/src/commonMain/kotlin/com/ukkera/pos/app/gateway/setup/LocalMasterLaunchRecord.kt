package com.ukkera.pos.app.gateway.setup

import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.types.DomainId

/** OS-private atomic bytes only. Null means observed absence, never a corrupt/partial read. */
public interface LocalMasterLaunchStorage {
    /** An interrupted write must fail, not silently restore an older RUN record. Caller owns bytes. */
    public fun read(): ByteArray?
    /** Force contents and containing directory before returning. No native records may be changed. */
    public fun replaceAndForce(bytes: ByteArray)
}

/**
 * Shared non-secret startup hint and its compatible encoding. RUN permits an EXISTING-ONLY
 * attempt, never master authority. The original native role, identity, history and journals
 * remain the final gates. No bearer, credential or pending business effect is reconstructed.
 */
public class LocalMasterLaunchRecord(private val storage: LocalMasterLaunchStorage) {
    private val monitor = LocalSetupMonitor()

    public sealed interface Read {
        public data object Absent : Read
        public data object Stopped : Read
        public data object Unresolved : Read
        public class Running internal constructor(private val identities: List<String>) : Read {
            public fun matches(actual: SelfOwnedAllocationCoordinatesV1): Boolean = identities == fields(actual)
            override fun toString(): String = "LocalMasterLaunchRecord.Running([REDACTED])"
        }
    }

    public fun read(): Read = withSetupLock(monitor) {
        try {
            val bytes = storage.read() ?: return@withSetupLock Read.Absent
            try {
                require(bytes.size in 1..MAXIMUM_BYTES)
                val lines = bytes.decodeToString(throwOnInvalidSequence = true).split('\n')
                require(lines.first() == FORMAT && lines.last().isEmpty())
                when {
                    lines == listOf(FORMAT, "STOP", "") -> Read.Stopped
                    lines.size == 15 && lines[1] == "RUN" -> {
                        val ids = lines.subList(2, 14)
                        require(ids.distinct().size == 12 && ids.all { DomainId(it).value == it })
                        Read.Running(ids.toList())
                    }
                    else -> Read.Unresolved
                }
            } finally { bytes.fill(0) }
        } catch (_: Exception) { Read.Unresolved }
    }

    /** Only the coordinator's successful explicit native connection calls this transition. */
    public fun retainRunning(actual: SelfOwnedAllocationCoordinatesV1): Unit = withSetupLock(monitor) {
        write(listOf(FORMAT, "RUN") + fields(actual))
        check((read() as? Read.Running)?.matches(actual) == true)
    }

    /** Persist negative intent before acknowledging an intentional local service stop. */
    public fun retainStop(): Unit = withSetupLock(monitor) {
        write(listOf(FORMAT, "STOP"))
        check(read() == Read.Stopped)
    }

    private fun write(lines: List<String>) {
        val bytes = lines.joinToString("\n", postfix = "\n").encodeToByteArray()
        try { storage.replaceAndForce(bytes) } finally { bytes.fill(0) }
    }

    public companion object {
        /** Storage bound, independent of the native database's formats or authority. */
        public const val MAXIMUM_BYTES: Int = 1024
        private const val FORMAT = "ukkera.local-master-launch/v1"
        private fun fields(value: SelfOwnedAllocationCoordinatesV1): List<String> = listOf(value.allocationId,
            value.scope.organizationId, value.scope.companyId, value.scope.branchId, value.installationId,
            value.assignmentId, value.deviceId, value.storageInstanceId, value.storageKeyId,
            value.pepperKeyId, value.targetSigningKeyId, value.firstActorId).map { it.value }
    }
}
