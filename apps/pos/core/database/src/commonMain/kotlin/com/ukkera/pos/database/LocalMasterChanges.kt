package com.ukkera.pos.database

import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable

/**
 * Untrusted progress DATA for one currently authorized typed view. None of these fields is
 * a grant, authenticated identity, original-Master proof or permission to disclose old data.
 * The actual Master independently checks the complete binding and current protected access.
 */
@Serializable
public data class LocalMasterChangeCursorV1(
    public val scope: BranchScope,
    public val authority: AuthorityIdentity,
    public val branchRevision: Revision,
    public val sequence: JournalSequence,
    public val journalHash: PayloadDigest,
    public val actorId: DomainId,
    public val deviceId: DomainId,
    public val sessionId: DomainId,
    public val sessionGeneration: SessionGeneration,
    public val admissionRevision: Revision,
    public val policyRevision: Revision,
    public val profile: LocalMasterRpcWorkflowProfile,
    public val selectorDigest: PayloadDigest,
    /** Actual authorized payload, including any time-dependent projection at this observation. */
    public val projectionDigest: PayloadDigest,
) {
    override fun toString(): String = "LocalMasterChangeCursorV1([REDACTED])"
}

/** V1 supports bounded catch-up; an older cursor requires a new authorized snapshot. */
public const val LOCAL_MASTER_CHANGES_MAX_CURSOR_LAG_V1: Long = 4_096

/** Sum of actual retained envelope payload bytes; not an RPC/shared-buffer allocation count. */
public const val LOCAL_MASTER_CHANGES_MAX_CATCH_UP_BYTES_V1: Long = 4_194_304

public enum class LocalMasterChangesFailureV1 {
    AUTHORIZATION_DENIED, WRONG_SCOPE, UNKNOWN_QUERY, MALFORMED_REQUEST,
    WRITER_NOT_QUALIFIED, STORAGE_UNCERTAIN, SERVICE_BUSY, CURSOR_EXPIRED,
    CAPACITY_EXCEEDED,
}

public sealed interface LocalMasterChangesReplyV1 {
    /**
     * One bounded typed projection and its cursor at the SAME successful writer observation.
     * A Client must atomically retain both before publishing the replacement projection.
     */
    public class Snapshot(
        public val data: ReadReply.Data,
        public val cursor: LocalMasterChangeCursorV1,
    ) : LocalMasterChangesReplyV1

    /** Same committed cut AND actual projection; still reauthorized in full on every pull. */
    public class Unchanged(
        public val cursor: LocalMasterChangeCursorV1,
        public val observedAt: UtcInstant,
    ) : LocalMasterChangesReplyV1

    public class Unavailable(public val reason: LocalMasterChangesFailureV1) : LocalMasterChangesReplyV1
}

/**
 * Additive typed-view synchronization capability. The installed connection/route fixes the
 * profile; passing a profile here cannot broaden it or provide Master-side authority. Each
 * request still requires the exact current employee/session/device/policy and typed query.
 * Availability flows and persisted cursors are never authorization or commit evidence.
 */
public interface LocalMasterChangesClient {
    /**
     * A different page/selector may continue [expectedCut] only while its actual Master,
     * scope, authorization/profile and complete branch cut still match. A changed cut returns
     * CURSOR_EXPIRED with no partial data; reacquire the snapshot instead of mixing pages.
     */
    public suspend fun snapshot(
        credentials: LocalGatewayCredentials,
        profile: LocalMasterRpcWorkflowProfile,
        selection: ReadRequest,
        expectedCut: LocalMasterChangeCursorV1? = null,
    ): LocalMasterChangesReplyV1

    /**
     * Same normalized selector only. Later durable progress can replace its entire typed
     * page, repairing missed hints without replaying any financial/business command.
     */
    public suspend fun pull(
        credentials: LocalGatewayCredentials,
        profile: LocalMasterRpcWorkflowProfile,
        selection: ReadRequest,
        cursor: LocalMasterChangeCursorV1,
    ): LocalMasterChangesReplyV1
}

internal fun changesFailure(reason: ReadFailure): LocalMasterChangesFailureV1 = when (reason) {
    ReadFailure.AUTHORIZATION_DENIED -> LocalMasterChangesFailureV1.AUTHORIZATION_DENIED
    ReadFailure.WRONG_SCOPE -> LocalMasterChangesFailureV1.WRONG_SCOPE
    ReadFailure.UNKNOWN_QUERY -> LocalMasterChangesFailureV1.UNKNOWN_QUERY
    ReadFailure.MALFORMED_REQUEST -> LocalMasterChangesFailureV1.MALFORMED_REQUEST
    ReadFailure.WRITER_NOT_QUALIFIED -> LocalMasterChangesFailureV1.WRITER_NOT_QUALIFIED
    ReadFailure.STORAGE_UNCERTAIN -> LocalMasterChangesFailureV1.STORAGE_UNCERTAIN
    ReadFailure.SERVICE_BUSY -> LocalMasterChangesFailureV1.SERVICE_BUSY
}
