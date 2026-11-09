package com.ukkera.pos.database

import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** A typed, versioned query. Actor, branch and permission claims are never request fields. */
public data class ReadKey(public val name: String, public val version: Int) {
    init { requireProtocolName(name); require(version in 1..1000) }
}

public class ReadRequest(public val query: ReadKey, public val payload: StoredBytes)

/** Server-derived query identity; not an operation ID or a reusable authorization proof. */
public class ReadContext internal constructor(
    public val scope: BranchScope,
    public val actorId: DomainId,
    public val key: ReadKey,
    public val semanticDigest: PayloadDigest,
)

/**
 * Trusted projection adapter, installed by the process composition root. It must select only
 * the relevant rows/fields, express every required permission and owner constraint, and return
 * bounded presentation data, never storage rows, credentials or a reusable permission cache.
 * prepare/project must not perform external IO or retain the transaction-bound read view.
 */
public interface MasterReadHandler {
    public val key: ReadKey
    public fun normalize(input: StoredBytes): StoredBytes
    /** Installed typed classification, not caller-selected authority or a wildcard read. */
    public fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean = false
    public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead
}

public abstract class PreparedRead {
    /** All requirements are checked against current protected state in the same transaction. */
    public abstract val requirements: List<AuthorizationRequirement>
    /** Pure field-filtered result, after every requirement has been authorized. */
    public abstract fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes
}

/**
 * A trusted read adapter's additional current-access refusal. For example, an
 * employee-private pending transfer remains private even with an ANY_OWNER grant.
 * The store returns AUTHORIZATION_DENIED only after a successful rollback and
 * outer loan return. This exception carries no private projection or cause.
 * Corrupt or unavailable storage must not be classified as an access refusal.
 */
public class MasterReadAuthorizationDenied : Exception()

/** Known absent or out-of-range selection, not missing/corrupt committed evidence. */
public class MasterReadInvalidSelection : Exception()

public enum class ReadFailure {
    AUTHORIZATION_DENIED, WRONG_SCOPE, UNKNOWN_QUERY, MALFORMED_REQUEST,
    WRITER_NOT_QUALIFIED, STORAGE_UNCERTAIN, SERVICE_BUSY,
}

public sealed interface ReadReply {
    /** Published only after the complete outer Room loan returns normally. */
    public class Data internal constructor(
        public val payload: StoredBytes,
        public val branchRevision: Revision,
        public val observedAt: UtcInstant,
    ) : ReadReply
    /** No partial projection or sensitive underlying exception is returned on failure. */
    public class Unavailable(public val reason: ReadFailure) : ReadReply
}

/**
 * A bounded storage-key page for trusted handlers, not a user-facing list or snapshot token.
 * Every page belongs to the caller's current transaction. A later page may observe a newer
 * branch revision; the public read reply supplies that revision so adapters can detect a cut
 * change rather than present a paginated report as one coherent historical snapshot.
 */
public class AggregateKeyPage internal constructor(
    keys: List<AggregateKey>,
    public val nextAfter: DomainId?,
) {
    public val keys: List<AggregateKey> = keys.toList()
}
