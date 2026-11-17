package com.ukkera.pos.database

import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.PayloadDigest

public const val LOCAL_MASTER_CHANGES_BATCH_MAX_SELECTORS_V1: Int = 32
public const val LOCAL_MASTER_CHANGES_BATCH_MAX_SELECTION_BYTES_V1: Int = 65_536
public const val LOCAL_MASTER_CHANGES_BATCH_MAX_PROJECTION_BYTES_V1: Int = 65_536
public const val LOCAL_MASTER_CHANGES_BATCH_MAX_REQUIREMENTS_V1: Int = 1_024

/**
 * Inert outcome of one exact current changes authorization check, never a reusable grant.
 * CAPACITY_ONLY and MALFORMED_ONLY permit only their fixed no-data refusal in an immutable
 * RPC scope; neither may authorize any projection, cursor or other private response.
 */
public enum class LocalMasterChangesRpcAccessResultV1 { ALLOWED, DENIED, CAPACITY_ONLY, UNAVAILABLE, MALFORMED_ONLY }

/**
 * One current exact-query observation. Only ALLOWED carries the bounded ordered projection
 * binding; a later DATA disclosure must freshly reproduce it. This DTO is never a grant.
 */
public class LocalMasterChangesRpcAuthorizationV1(
    public val result: LocalMasterChangesRpcAccessResultV1,
    public val binding: PayloadDigest?,
    /** Only a complete authorized single-selector observation supplies this READ binding.
     * Null never authorizes the ordinary READ route; strict cursor routes keep [binding]. */
    public val readProjectionBinding: PayloadDigest? = null,
) {
    init {
        require((result == LocalMasterChangesRpcAccessResultV1.ALLOWED) == (binding != null))
        require(readProjectionBinding == null || result == LocalMasterChangesRpcAccessResultV1.ALLOWED)
    }
    override fun toString(): String = "LocalMasterChangesRpcAuthorizationV1([REDACTED])"
}

/** Every selection succeeds at one authorized writer/time cut, or no projection is returned. */
public sealed interface LocalMasterChangesBatchReplyV1 {
    /** Exact request order; the list is defensively copied and is never an access grant. */
    public class Snapshot(snapshots: List<LocalMasterChangesReplyV1.Snapshot>) : LocalMasterChangesBatchReplyV1 {
        public val snapshots: List<LocalMasterChangesReplyV1.Snapshot> = snapshots.toList()
    }

    public class Unavailable(public val reason: LocalMasterChangesFailureV1) : LocalMasterChangesBatchReplyV1
}

/**
 * Additive coherent visible-view capability for the fixed LAN_BUSINESS profile only. Each
 * selector retains its own typed current permissions and projection digest. All results use
 * one original writer/view, the same committed cut and one Master projection time, followed
 * by the original final protected dependency/eligibility checks. This is not raw replication.
 */
public interface LocalMasterChangesBatchClient {
    /**
     * 1..32 distinct normalized selectors; aggregate input/output bytes and requirements are
     * bounded by the constants above. Invalid input is MALFORMED_REQUEST; aggregate output or
     * requirements exceeding capacity returns CAPACITY_EXCEEDED, never a truncated batch.
     */
    public suspend fun snapshotBatch(
        credentials: LocalGatewayCredentials,
        profile: LocalMasterRpcWorkflowProfile,
        selections: List<ReadRequest>,
    ): LocalMasterChangesBatchReplyV1
}
