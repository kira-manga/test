package com.ukkera.pos.database

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Native current-key locators only, not projection permission, producer proof or a physical
 * corruption census. Activation requires the recovery-owned same-generation D2/F05 typed
 * selector census/all-writer closure; a TEXT prefix cannot itself detect hidden BLOB aliases.
 */
public class ClaimKeyEntry internal constructor(public val key: ClaimKey, public val ownerId: DomainId)
public class ClaimKeyPage internal constructor(entries: List<ClaimKeyEntry>, public val nextAfter: String?) {
    public val entries: List<ClaimKeyEntry> = entries.toList()
}

/** SELECT returns bounded scalar columns for records actually selected by the TEXT range. */
internal data class CurrentClaimKey(val boundedKey: String?, val boundedOwnerId: String?)

internal suspend fun readCurrentClaimKeys(dao: MasterDao, scope: BranchScope, namespace: String,
    prefix: String, after: String?, limit: Int, checkActive: () -> Unit): ClaimKeyPage {
    requireProtocolName(namespace)
    require(prefix.length in 1..255 && prefix.all { it.code in 0x21..0x7e })
    require(limit in 1..100)
    require(after == null || after.startsWith(prefix) && after.length <= 256 && after.all { it.code in 0x21..0x7e })
    // ASCII prefix successor; inputs never become SQL identifiers/patterns. No LIKE escaping.
    val upper = prefix.dropLast(1) + (prefix.last().code + 1).toChar()
    currentCoroutineContext().ensureActive(); checkActive()
    val rows = dao.claimKeys(scope.storageKey(), namespace, prefix, upper, after, limit + 1)
    currentCoroutineContext().ensureActive(); checkActive()
    check(rows.size <= limit + 1)
    val entries = rows.map {
        val key = checkNotNull(it.boundedKey) { "Current claim range unavailable" }
        check(key.startsWith(prefix) && (after == null || key > after))
        ClaimKeyEntry(ClaimKey(namespace, key), DomainId(checkNotNull(it.boundedOwnerId) { "Current claim range unavailable" }))
    }
    check(entries.zipWithNext().all { (left, right) -> left.key.key < right.key.key })
    val selected = entries.take(limit)
    return ClaimKeyPage(selected, if (entries.size > limit) selected.last().key.key else null)
}
