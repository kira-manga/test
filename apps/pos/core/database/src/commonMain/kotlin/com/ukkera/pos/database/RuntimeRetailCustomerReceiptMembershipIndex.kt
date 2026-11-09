package com.ukkera.pos.database

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.OriginalNativeHistorySnapshot
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SelfOwnedNativeHistoryCustody
import com.ukkera.pos.security.SelfOwnedNativeHistoryReadSession
import com.ukkera.pos.security.SelfOwnedNativeReceiptMembershipData
import com.ukkera.pos.security.SelfOwnedNativeRoomSettlement
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** Main-scan projection only. Its caller closes the independent immutable EVENT census,
 * current census and every genuine main original before finishing. DATA additions are not
 * history proofs; a borrowed earlier-original replay must never receive this builder. */
internal class RuntimeRetailCustomerReceiptMembershipBuilder private constructor(
    private val index: RuntimeRetailCustomerReceiptMembershipIndex,
    private val classifier: RuntimeRetailCustomerReceiptEventClassifier,
) {
    private var finished = false
    val count: Long get() = index.count

    internal fun requireClassifier(actual: RuntimeRetailCustomerReceiptEventClassifier) {
        check(classifier === actual)
    }

    suspend fun append(saleId: DomainId, locator: RuntimeRetailCustomerReceiptEventLocator) {
        check(!finished)
        index.appendBuilding(saleId, locator)
    }

    /** Actually closes every independently owned DATA handle before the original read can
     * close. The root remains live DATA; it is never reconstructed from a parked file. */
    suspend fun finish(): RuntimeRetailCustomerReceiptMembershipSeed {
        check(!finished)
        index.parkBuiltProjection()
        finished = true
        return RuntimeRetailCustomerReceiptMembershipSeed(classifier, index.scope, index.sourceRecordDigest, index)
    }

    /** Also owns a finished candidate when scan cleanup fails before delivery. Native
     * lifetime ownership, not this wrapper, retains final artifact/key disposal. */
    suspend fun abort() { index.abort() }

    companion object {
        suspend fun create(
            custody: SelfOwnedNativeHistoryCustody,
            session: SelfOwnedNativeHistoryReadSession,
            classifier: RuntimeRetailCustomerReceiptEventClassifier,
            sharedMemory: RuntimeHistoryColdMemory,
        ): RuntimeRetailCustomerReceiptMembershipBuilder {
            val index = RuntimeRetailCustomerReceiptMembershipIndex.createBuilding(custody, session, sharedMemory)
            return try { RuntimeRetailCustomerReceiptMembershipBuilder(index, classifier) }
            catch (failure: Throwable) {
                try { index.abort() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }
}

/** One bounded scalar result loan, retained by the actual Room frame until it closes.
 * Locators are inert immutable values, not native or complete-membership capabilities. */
internal class RuntimeRetailCustomerReceiptMembershipSelection internal constructor(
    entries: List<RuntimeRetailCustomerReceiptEventLocator>,
    private val memory: RuntimeHistoryColdMemory,
    private val reserved: Long,
) {
    private var retained: List<RuntimeRetailCustomerReceiptEventLocator>? = entries
    val entries: List<RuntimeRetailCustomerReceiptEventLocator> get() = checkNotNull(retained)
    fun close() {
        if (retained == null) return
        // Drop our loan, without claiming erasure of immutable values delivered as DATA.
        retained = null
        memory.release(reserved)
    }
}

/** Inert copied DATA blocks only. A writer copies the borrowed bytes before return;
 * append transfers a fresh opaque ID. Read borrows the unchanged ID and transfers a buffer
 * that its caller clears; preflight checks availability, never history completeness.
 * This port creates no custody, historical reader, membership admission or usable root. */
internal interface RuntimeRetailCustomerReceiptMembershipBlockStore {
    suspend fun append(bytes: ByteArray): ByteArray
    suspend fun read(blockId: ByteArray): ByteArray
    suspend fun preflight()

    companion object {
        const val MAX_BLOCK_BYTES = 65_536
        const val BLOCK_ID_BYTES = 32
    }
}

/** Genuine native lifetime/origin owner. Its DATA tree has no origin or admission API;
 * only this privately acquired owner can park, prepare and bind the exact native resource. */
internal class RuntimeRetailCustomerReceiptMembershipIndex private constructor(
    internal val nativeData: SelfOwnedNativeReceiptMembershipData,
    internal val sharedMemory: RuntimeHistoryColdMemory,
    val scope: BranchScope,
    val sourceRecordDigest: PayloadDigest,
    private val lifetimeId: DomainId,
    private val allocationDigest: PayloadDigest,
    private val tree: RuntimeRetailCustomerReceiptMembershipTree,
    initialState: State,
) {
    private enum class State { BUILDING, PARKED, PREPARING, PREPARED, LIVE, RELEASED }
    private var state = initialState
    private val denied = MutableStateFlow(false)
    private var operation = false
    private var parkStarted = false
    val count: Long get() = tree.count

    internal fun matchesClosedSeed(selectedScope: BranchScope, digest: PayloadDigest): Boolean =
        state == State.PARKED && !denied.value && !operation && tree.isIdle &&
            scope == selectedScope && sourceRecordDigest == digest

    internal fun matchesPreparedAdmission(original: OriginalNativeHistorySnapshot): Boolean =
        state == State.PREPARED && !denied.value && !operation && tree.isIdle &&
            scope == original.record.allocation.coordinates.scope && lifetimeId == original.lifetimeId &&
            allocationDigest == original.record.allocation.digest && sourceRecordDigest == original.recordDigest

    /** Startup alone calls this after the exact actual original read close/reobservation.
     * Mark the reopen attempt before native IO, so partial preparation still has its own
     * cleanup park rather than being confused with the earlier completed build park. */
    internal suspend fun prepareAfterClosedRead(custody: SelfOwnedNativeHistoryCustody,
        original: OriginalNativeHistorySnapshot) {
        check(matchesClosedSeed(original.record.allocation.coordinates.scope, original.recordDigest) &&
            lifetimeId == original.lifetimeId && allocationDigest == original.record.allocation.digest)
        currentCoroutineContext().ensureActive()
        sharedMemory.reserve(WORK_RESERVATION)
        operation = true
        parkStarted = false; state = State.PREPARING
        try {
            custody.prepareReceiptMembershipDataAfterClosedRead(original, nativeData)
            currentCoroutineContext().ensureActive(); check(!denied.value)
            state = State.PREPARED
        } catch (failure: Throwable) { denyUse(); throw failure }
        finally { operation = false; sharedMemory.release(WORK_RESERVATION) }
    }

    internal fun bindAdmitted(original: OriginalNativeHistorySnapshot) {
        check(matchesPreparedAdmission(original))
        state = State.LIVE
    }

    internal fun denyUse() { denied.value = true; tree.denyUse() }

    internal suspend fun parkBuiltProjection() {
        check(state == State.BUILDING)
        nativeWork {
            currentCoroutineContext().ensureActive(); check(!denied.value)
            parkStarted = true
            nativeData.park()
            state = State.PARKED
        }
    }

    /** Cleanup does not reopen an original source or retry an uncertain consumed close. */
    internal suspend fun abort(): Unit = withContext(NonCancellable) {
        denyUse()
        if (state == State.RELEASED) return@withContext
        check(!operation && tree.isIdle)
        try {
            if (!parkStarted) {
                parkStarted = true
                nativeData.park()
            }
        } finally { releaseOwnedState() }
    }

    /** Only after actual terminal native cleanup, while no Room frame or operation remains. */
    internal fun releaseAfterNativeClose() {
        denyUse(); check(!operation && tree.isIdle); releaseOwnedState()
    }

    private fun releaseOwnedState() {
        if (state == State.RELEASED) return
        tree.closeData()
        state = State.RELEASED
        sharedMemory.release(INDEX_RESERVATION)
    }

    internal suspend fun select(saleId: DomainId): RuntimeRetailCustomerReceiptMembershipSelection =
        dataWork(State.LIVE) { tree.select(saleId) }

    internal suspend fun canAppend(saleId: DomainId, locator: RuntimeRetailCustomerReceiptEventLocator): Boolean =
        dataWork(State.LIVE) { tree.canAppend(saleId, locator) }

    internal suspend fun appendBuilding(saleId: DomainId, locator: RuntimeRetailCustomerReceiptEventLocator) {
        dataWork(State.BUILDING) { tree.append(saleId, locator) }
    }

    /** Called only by the Room owner after actual COMMITTED, normal outer completion and
     * its exact genuine frame-image/original commit crosswalk. No proposal publishes DATA. */
    internal suspend fun appendCommitted(saleId: DomainId, locator: RuntimeRetailCustomerReceiptEventLocator) {
        dataWork(State.LIVE) { tree.append(saleId, locator) }
    }

    /** Tree work reserves the shared operation allowance itself; this wrapper never adds
     * a concurrent second operation reservation around the same native DATA operation. */
    private suspend fun <T> dataWork(requiredState: State, use: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        check(state == requiredState && !denied.value && !operation && tree.isIdle)
        operation = true
        try { return use() }
        catch (failure: Throwable) { denyUse(); throw failure }
        finally { operation = false }
    }

    private suspend fun <T> nativeWork(use: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        check(state != State.RELEASED && !denied.value && !operation && tree.isIdle)
        sharedMemory.reserve(WORK_RESERVATION)
        operation = true
        try {
            nativeData.preflight()
            currentCoroutineContext().ensureActive(); check(!denied.value)
            val result = use()
            currentCoroutineContext().ensureActive(); check(!denied.value)
            return result
        } catch (failure: Throwable) { denyUse(); throw failure }
        finally { operation = false; sharedMemory.release(WORK_RESERVATION) }
    }

    private class NativeBlocks(private val data: SelfOwnedNativeReceiptMembershipData):
        RuntimeRetailCustomerReceiptMembershipBlockStore {
        init {
            check(RuntimeRetailCustomerReceiptMembershipBlockStore.MAX_BLOCK_BYTES ==
                SelfOwnedNativeReceiptMembershipData.MAX_BLOCK_BYTES &&
                RuntimeRetailCustomerReceiptMembershipBlockStore.BLOCK_ID_BYTES ==
                SelfOwnedNativeReceiptMembershipData.BLOCK_ID_BYTES)
        }
        override suspend fun append(bytes: ByteArray): ByteArray = data.append(bytes)
        override suspend fun read(blockId: ByteArray): ByteArray = data.read(blockId)
        override suspend fun preflight() { data.preflight() }
    }

    companion object {
        private const val INDEX_RESERVATION = 8192L
        private const val WORK_RESERVATION = 2L * 1024 * 1024

        internal suspend fun createBuilding(custody: SelfOwnedNativeHistoryCustody,
            session: SelfOwnedNativeHistoryReadSession, memory: RuntimeHistoryColdMemory): RuntimeRetailCustomerReceiptMembershipIndex =
            acquire(memory, session.original, State.BUILDING) { custody.openReceiptMembershipData(session) }

        /** The caller has already closed the actual initializer's full empty image census
         * and exact native COMMITTED installation crosswalk. Missing parent is not evidence. */
        internal suspend fun afterCommittedInstallation(custody: SelfOwnedNativeHistoryCustody,
            settlement: SelfOwnedNativeRoomSettlement, memory: RuntimeHistoryColdMemory): RuntimeRetailCustomerReceiptMembershipIndex =
            acquire(memory, settlement.original, State.LIVE) { custody.openEmptyReceiptMembershipDataAfterCommittedInstallation(settlement) }

        private suspend fun acquire(memory: RuntimeHistoryColdMemory, original: OriginalNativeHistorySnapshot,
            state: State, open: suspend () -> SelfOwnedNativeReceiptMembershipData): RuntimeRetailCustomerReceiptMembershipIndex {
            memory.reserve(INDEX_RESERVATION)
            var transferred = false
            var data: SelfOwnedNativeReceiptMembershipData? = null
            var tree: RuntimeRetailCustomerReceiptMembershipTree? = null
            var workReserved = false
            try {
                memory.reserve(WORK_RESERVATION); workReserved = true
                data = open()
                currentCoroutineContext().ensureActive()
                tree = RuntimeRetailCustomerReceiptMembershipTree.create(NativeBlocks(checkNotNull(data)), memory)
                val result = RuntimeRetailCustomerReceiptMembershipIndex(checkNotNull(data), memory,
                    original.record.allocation.coordinates.scope, original.recordDigest, original.lifetimeId,
                    original.record.allocation.digest, checkNotNull(tree), state)
                currentCoroutineContext().ensureActive()
                transferred = true
                return result
            } catch (failure: Throwable) {
                tree?.denyUse()
                withContext(NonCancellable) {
                    try { data?.park() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                    try { tree?.closeData() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                }
                throw failure
            } finally {
                if (workReserved) memory.release(WORK_RESERVATION)
                if (!transferred) memory.release(INDEX_RESERVATION)
            }
        }
    }
}

/** One private live authenticated DATA root, created empty and never imported from disk.
 * The compressed binary tree uses (SALE/EVENT namespace bit, canonical UUID), so a path
 * has at most 129 branches. A SALE leaf commits its entire <=64-entry bucket; EVENT leaves
 * establish copied identity uniqueness. Missing blocks fail, never shorten a bucket.
 * Creating or querying this DATA tree establishes no native existence or completeness. */
internal class RuntimeRetailCustomerReceiptMembershipTree private constructor(
    private val blocks: RuntimeRetailCustomerReceiptMembershipBlockStore,
    private val memory: RuntimeHistoryColdMemory,
) {
    private val denied = MutableStateFlow(false)
    private var closed = false
    private var operation = false
    private var root: NodeRef? = null
    private var eventCount = 0L
    val count: Long get() = eventCount
    val isIdle: Boolean get() = !operation

    fun denyUse() { denied.value = true }

    /** Clears only owned live DATA/accounting after operations finish. Independent result
     * loans keep their own reservations; no native handle is closed or retried here. */
    fun closeData() {
        denyUse(); check(!operation)
        if (closed) return
        root?.clear(); root = null; eventCount = 0L; closed = true
        memory.release(TREE_RESERVATION)
    }

    suspend fun select(saleId: DomainId): RuntimeRetailCustomerReceiptMembershipSelection {
        var candidate: RuntimeRetailCustomerReceiptMembershipSelection? = null
        return try {
            val result = work {
                val key = TreeKey.of(SALE, saleId)
                var node: Node? = null
                try {
                    node = lookup(root, key)
                    val sale = node?.let { it as? Sale ?: malformed() }
                    val size = sale?.events?.size ?: 0
                    val reservation = SELECTION_BASE + size.toLong() * SELECTION_MEMBER
                    memory.reserve(reservation)
                    var transferred = false
                    try {
                        // buildList freezes its backing builder, so a retained DATA loan
                        // cannot be cast and extended beyond its scalar reservation.
                        val entries = buildList(size) {
                            sale?.events?.forEach { event ->
                                add(RuntimeRetailCustomerReceiptEventLocator(
                                    AggregateKey(RuntimeRetailCustomerReceiptMembershipProfile.EVENT_KIND, event.id),
                                    Revision(1), RuntimeRetailCustomerReceiptMembershipProfile.EVENT_SCHEMA, event.digest))
                            }
                        }
                        RuntimeRetailCustomerReceiptMembershipSelection(entries, memory, reservation).also {
                            candidate = it; transferred = true
                        }
                    } finally { if (!transferred) memory.release(reservation) }
                } finally { node?.clear(); key.clear() }
            }
            candidate = null
            result
        } catch (failure: Throwable) {
            try { candidate?.close() }
            catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    suspend fun canAppend(saleId: DomainId, locator: RuntimeRetailCustomerReceiptEventLocator): Boolean =
        work { inspectAppend(saleId, locator) }

    private suspend fun inspectAppend(saleId: DomainId, locator: RuntimeRetailCustomerReceiptEventLocator): Boolean {
        if (!locatorShape(locator)) return false
        val saleKey = TreeKey.of(SALE, saleId)
        val eventKey = TreeKey.of(EVENT, locator.key.id)
        var sale: Node? = null; var event: Node? = null
        try {
            event = lookup(root, eventKey)
            if (event != null) { historyShapeV2(event is Event); return false }
            sale = lookup(root, saleKey)
            val entries = sale?.let { (it as? Sale ?: malformed()).events }
            historyShapeV2(entries?.none { it.id == locator.key.id } != false)
            return (entries?.size ?: 0) < RuntimeRetailCustomerReceiptMembershipProfile.MAX_EVENTS
        } finally { sale?.clear(); event?.clear(); saleKey.clear(); eventKey.clear() }
    }

    suspend fun append(saleId: DomainId, locator: RuntimeRetailCustomerReceiptEventLocator): Unit = work {
        historyShapeV2(locatorShape(locator))
        val nextCount = historyAddV2(eventCount, 1L, RuntimeHistoryProfileV2.MAX_EVENTS)
        val saleKey = TreeKey.of(SALE, saleId)
        val eventKey = TreeKey.of(EVENT, locator.key.id)
        var previousSale: Node? = null; var previousEvent: Node? = null
        var updatedSale: Sale? = null; var newEvent: Event? = null
        var unfinishedEvents: MutableList<Member>? = null
        var stagedSaleRoot: NodeRef? = null; var stagedRoot: NodeRef? = null
        try {
            previousEvent = lookup(root, eventKey)
            historyShapeV2(previousEvent == null)
            previousSale = lookup(root, saleKey)
            val old = previousSale?.let { it as? Sale ?: malformed() }
            historyCapacityV2((old?.events?.size ?: 0) < RuntimeRetailCustomerReceiptMembershipProfile.MAX_EVENTS)
            historyShapeV2(old?.events?.none { it.id == locator.key.id } != false)
            val events = ArrayList<Member>((old?.events?.size ?: 0) + 1); unfinishedEvents = events
            old?.events?.let(events::addAll)
            events += Member(locator.key.id, locator.digest)
            events.sortBy { it.id.value }
            updatedSale = Sale(saleKey.copy(), events); unfinishedEvents = null
            newEvent = Event(eventKey.copy(), saleId, locator.digest)
            stagedSaleRoot = insert(root, updatedSale, replace = old != null)
            stagedRoot = insert(stagedSaleRoot, newEvent, replace = false)
            blocks.preflight()
            currentCoroutineContext().ensureActive(); check(!denied.value)
            val before = root
            root = stagedRoot; stagedRoot = null; eventCount = nextCount
            before?.clear()
        } finally {
            stagedRoot?.clear(); stagedSaleRoot?.clear()
            unfinishedEvents?.clear()
            updatedSale?.clear(); newEvent?.clear(); previousSale?.clear(); previousEvent?.clear()
            saleKey.clear(); eventKey.clear()
        }
    }

    private suspend fun <T> work(use: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        check(!closed && !denied.value && !operation)
        historyShapeV2((root == null) == (eventCount == 0L))
        memory.reserve(WORK_RESERVATION)
        operation = true
        try {
            blocks.preflight()
            currentCoroutineContext().ensureActive(); check(!denied.value)
            val result = use()
            currentCoroutineContext().ensureActive(); check(!denied.value)
            return result
        } catch (failure: Throwable) { denyUse(); throw failure }
        finally { operation = false; memory.release(WORK_RESERVATION) }
    }

    private class NodeRef(val id: ByteArray, val digest: PayloadDigest, val bytes: Int) {
        init {
            historyShapeV2(id.size == RuntimeRetailCustomerReceiptMembershipBlockStore.BLOCK_ID_BYTES)
            historyCapacityV2(bytes in 1..RuntimeRetailCustomerReceiptMembershipBlockStore.MAX_BLOCK_BYTES)
        }
        fun copy(): NodeRef = NodeRef(id.copyOf(), digest, bytes)
        fun clear() { id.fill(0) }
    }

    private class TreeKey private constructor(val bytes: ByteArray) {
        init { historyShapeV2(bytes.size == KEY_BYTES && bytes[0].toInt() in SALE..EVENT) }
        fun bit(at: Int): Int {
            historyShapeV2(at in 0 until KEY_BITS)
            return if (at == 0) bytes[0].toInt() else
                (bytes[1 + (at - 1) / 8].toInt() ushr (7 - (at - 1) % 8)) and 1
        }
        fun common(other: TreeKey): Int {
            for (at in 0 until KEY_BITS) if (bit(at) != other.bit(at)) return at
            return KEY_BITS
        }
        fun matches(prefix: TreeKey, bits: Int): Boolean = common(prefix) >= bits
        fun prefix(bits: Int): TreeKey {
            historyShapeV2(bits in 0 until KEY_BITS)
            val result = copy()
            for (at in bits until KEY_BITS) {
                if (at == 0) result.bytes[0] = 0 else {
                    val position = 1 + (at - 1) / 8
                    result.bytes[position] = (result.bytes[position].toInt() and (1 shl (7 - (at - 1) % 8)).inv()).toByte()
                }
            }
            return result
        }
        fun masked(bits: Int): Boolean = (bits until KEY_BITS).all { bit(it) == 0 }
        fun copy(): TreeKey = TreeKey(bytes.copyOf())
        fun clear() { bytes.fill(0) }
        companion object {
            fun of(namespace: Int, id: DomainId): TreeKey {
                historyShapeV2(namespace in SALE..EVENT)
                val bytes = ByteArray(KEY_BYTES); bytes[0] = namespace.toByte()
                var at = 1; var high = -1
                for (character in id.value) if (character != '-') {
                    val value = character.digitToInt(16)
                    if (high < 0) high = value else { bytes[at++] = ((high shl 4) or value).toByte(); high = -1 }
                }
                historyShapeV2(at == KEY_BYTES && high == -1)
                return TreeKey(bytes)
            }
            fun decode(bytes: ByteArray): TreeKey = try { TreeKey(bytes) }
            catch (failure: Throwable) { bytes.fill(0); throw failure }
        }
    }

    private sealed class Node(val key: TreeKey) {
        abstract fun clear()
    }
    private class Branch(key: TreeKey, val split: Int, val left: NodeRef, val right: NodeRef): Node(key) {
        init { historyShapeV2(split in 0 until KEY_BITS && key.masked(split)) }
        fun child(bit: Int): NodeRef = if (bit == 0) left else right
        override fun clear() { key.clear(); left.clear(); right.clear() }
    }
    private class Member(val id: DomainId, val digest: PayloadDigest)
    private class Sale(key: TreeKey, val events: MutableList<Member>): Node(key) {
        init {
            historyShapeV2(key.bytes[0].toInt() == SALE)
            historyCapacityV2(events.size in 1..RuntimeRetailCustomerReceiptMembershipProfile.MAX_EVENTS)
            for (at in 1 until events.size) historyShapeV2(events[at - 1].id.value < events[at].id.value)
        }
        override fun clear() { key.clear(); events.clear() }
    }
    private class Event(key: TreeKey, val saleId: DomainId, val digest: PayloadDigest): Node(key) {
        init { historyShapeV2(key.bytes[0].toInt() == EVENT) }
        override fun clear() { key.clear() }
    }

    private fun requireChild(node: Node, parent: Branch?, direction: Int) {
        if (parent == null) return
        historyShapeV2(node.key.matches(parent.key, parent.split) && node.key.bit(parent.split) == direction &&
            (node !is Branch || node.split > parent.split))
    }

    /** A prefix divergence or different authenticated leaf proves only copied-key absence
     * in this live DATA root. Missing blocks cannot enter either branch: exact length and
     * authenticated parent/root digest must match before any decoded key is used. */
    private suspend fun lookup(start: NodeRef?, key: TreeKey): Node? {
        var selected = start?.copy() ?: return null
        var parent: Branch? = null
        var direction = 0; var depth = 0
        try {
            while (true) {
                currentCoroutineContext().ensureActive(); check(!denied.value)
                historyCapacityV2(depth++ <= KEY_BITS)
                val node = readNode(selected)
                var transferred = false
                try {
                    requireChild(node, parent, direction)
                    if (node !is Branch) {
                        if (node.key.common(key) != KEY_BITS) return null
                        transferred = true; return node
                    }
                    if (!key.matches(node.key, node.split)) return null
                    direction = key.bit(node.split)
                    val next = node.child(direction).copy()
                    selected.clear(); selected = next
                    parent?.clear(); parent = node; transferred = true
                } finally { if (!transferred) node.clear() }
            }
        } finally { selected.clear(); parent?.clear() }
    }

    private class Ancestor(val branch: Branch, val direction: Int)

    /** Path-copy immutable nodes; intermediate roots never become an accepted cut. */
    private suspend fun insert(start: NodeRef?, leaf: Node, replace: Boolean): NodeRef {
        historyShapeV2(leaf !is Branch && (!replace || start != null))
        val path = ArrayList<Ancestor>(KEY_BITS)
        var selected = start?.copy()
        var replacement: NodeRef? = null
        try {
            replacement = writeNode(leaf)
            var direction = 0
            while (selected != null) {
                currentCoroutineContext().ensureActive(); check(!denied.value)
                // A legal full-depth path has 129 branches followed by its terminal leaf.
                historyCapacityV2(path.size <= KEY_BITS)
                val node = readNode(checkNotNull(selected))
                var retained = false
                try {
                    requireChild(node, path.lastOrNull()?.branch, direction)
                    val difference = node.key.common(leaf.key)
                    if (node is Branch && difference >= node.split) {
                        historyCapacityV2(path.size < KEY_BITS)
                        direction = leaf.key.bit(node.split)
                        val next = node.child(direction).copy()
                        path += Ancestor(node, direction); retained = true
                        selected.clear(); selected = next
                        continue
                    }
                    if (node !is Branch && difference == KEY_BITS) {
                        historyShapeV2(replace && node is Sale && leaf is Sale)
                    } else {
                        // A replacement must reach the exact already selected SALE leaf.
                        // Divergence cannot repair a missing or inconsistent membership.
                        historyShapeV2(!replace && difference < KEY_BITS)
                        val fresh = checkNotNull(replacement)
                        val branch = if (leaf.key.bit(difference) == 0)
                            Branch(leaf.key.prefix(difference), difference, fresh.copy(), selected.copy())
                        else Branch(leaf.key.prefix(difference), difference, selected.copy(), fresh.copy())
                        val next = try { writeNode(branch) } finally { branch.clear() }
                        fresh.clear(); replacement = next
                    }
                    break
                } finally { if (!retained) node.clear() }
            }
            for (at in path.lastIndex downTo 0) {
                val ancestor = path[at]
                val fresh = checkNotNull(replacement)
                val branch = if (ancestor.direction == 0)
                    Branch(ancestor.branch.key.copy(), ancestor.branch.split, fresh.copy(), ancestor.branch.right.copy())
                else Branch(ancestor.branch.key.copy(), ancestor.branch.split, ancestor.branch.left.copy(), fresh.copy())
                val next = try { writeNode(branch) } finally { branch.clear() }
                fresh.clear(); replacement = next
            }
            return checkNotNull(replacement).also { replacement = null }
        } finally { selected?.clear(); replacement?.clear(); path.forEach { it.branch.clear() }; path.clear() }
    }

    private suspend fun readNode(ref: NodeRef): Node {
        val bytes = blocks.read(ref.id)
        try {
            historyShapeV2(bytes.size == ref.bytes && sha256(bytes) == ref.digest)
            return decodeNode(bytes)
        } finally { bytes.fill(0) }
    }

    private suspend fun writeNode(node: Node): NodeRef {
        val bytes = encodeNode(node)
        var id: ByteArray? = null
        try {
            val digest = sha256(bytes)
            id = blocks.append(bytes)
            return NodeRef(checkNotNull(id), digest, bytes.size).also { id = null }
        } finally { id?.fill(0); bytes.fill(0) }
    }

    private fun writeRef(out: HistoryBinaryWriterV2, ref: NodeRef) {
        out.bytes(ref.id, RuntimeRetailCustomerReceiptMembershipBlockStore.BLOCK_ID_BYTES); out.digest(ref.digest); out.int(ref.bytes)
    }
    private fun readRef(input: HistoryBinaryReaderV2): NodeRef {
        val id = input.bytes(RuntimeRetailCustomerReceiptMembershipBlockStore.BLOCK_ID_BYTES)
        return try { NodeRef(id, input.digest(), input.int()) }
        catch (failure: Throwable) { id.fill(0); throw failure }
    }
    private fun encodeNode(node: Node): ByteArray {
        val out = HistoryBinaryWriterV2(RuntimeRetailCustomerReceiptMembershipBlockStore.MAX_BLOCK_BYTES)
        return try {
            out.int(1)
            out.byte(when (node) { is Branch -> 1; is Sale -> 2; is Event -> 3 })
            out.bytes(node.key.bytes, KEY_BYTES)
            when (node) {
                is Branch -> { out.int(node.split); writeRef(out, node.left); writeRef(out, node.right) }
                is Sale -> {
                    out.int(node.events.size)
                    for (event in node.events) { out.id(event.id); out.digest(event.digest) }
                }
                is Event -> { out.id(node.saleId); out.digest(node.digest) }
            }
            out.finish()
        } finally { out.clear() }
    }
    private fun decodeNode(bytes: ByteArray): Node {
        val input = HistoryBinaryReaderV2(bytes, RuntimeRetailCustomerReceiptMembershipBlockStore.MAX_BLOCK_BYTES)
        var key: TreeKey? = null; var left: NodeRef? = null; var right: NodeRef? = null; var node: Node? = null
        var members: MutableList<Member>? = null
        try {
            historyShapeV2(input.int() == 1)
            val type = input.byte()
            key = TreeKey.decode(input.bytes(KEY_BYTES))
            node = when (type) {
                1 -> {
                    val split = input.int(); left = readRef(input); right = readRef(input)
                    Branch(checkNotNull(key), split, checkNotNull(left), checkNotNull(right)).also { left = null; right = null }
                }
                2 -> {
                    val size = input.int()
                    historyCapacityV2(size in 1..RuntimeRetailCustomerReceiptMembershipProfile.MAX_EVENTS)
                    val entries = ArrayList<Member>(size); members = entries
                    repeat(size) { entries += Member(input.id(), input.digest()) }
                    Sale(checkNotNull(key), entries).also { members = null }
                }
                3 -> Event(checkNotNull(key), input.id(), input.digest())
                else -> malformed()
            }
            key = null
            input.end()
            return checkNotNull(node).also { node = null }
        } finally { node?.clear(); members?.clear(); key?.clear(); left?.clear(); right?.clear(); input.clear() }
    }

    companion object {
        private const val SALE = 0
        private const val EVENT = 1
        private const val KEY_BYTES = 17
        private const val KEY_BITS = 129
        private const val TREE_RESERVATION = 8192L
        // Includes ancestor objects, bounded leaf/scalar copies, codec growth and native
        // encryption/input/output overlap. It is shared, not an additional memory envelope.
        private const val WORK_RESERVATION = 2L * 1024 * 1024
        private const val SELECTION_BASE = 4096L
        private const val SELECTION_MEMBER = 1024L

        private fun locatorShape(locator: RuntimeRetailCustomerReceiptEventLocator): Boolean =
            locator.key.kind == RuntimeRetailCustomerReceiptMembershipProfile.EVENT_KIND && locator.revision == Revision(1) &&
                locator.schema == RuntimeRetailCustomerReceiptMembershipProfile.EVENT_SCHEMA
        private fun malformed(): Nothing = throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)

        fun create(blocks: RuntimeRetailCustomerReceiptMembershipBlockStore,
            memory: RuntimeHistoryColdMemory): RuntimeRetailCustomerReceiptMembershipTree {
            memory.reserve(TREE_RESERVATION)
            return try { RuntimeRetailCustomerReceiptMembershipTree(blocks, memory) }
            catch (failure: Throwable) { memory.release(TREE_RESERVATION); throw failure }
        }
    }
}
