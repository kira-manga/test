package com.ukkera.pos.app.gateway

import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Exact retained file identity (platform stamp, compared by equality) plus content digest. */
internal data class ClientJournalMark(val stamp: Any, val digest: String)
internal class ClientJournalSnapshot(val mark: ClientJournalMark, val bytes: ByteArray) { fun clear() { bytes.fill(0) } }

/** Facts of the actual journal binding the shared engine needs; platform bindings implement this. */
internal interface ClientJournalBindingFacts {
    fun isCurrent(): Boolean
    fun permitsEnrollment(): Boolean
    fun requireCoordinates(owner: LocalClientDeviceIdentity, actor: DomainId?, journalId: DomainId, keyId: DomainId,
        protection: SelfOwnedKeyProtectionV1)
    val rootName: String
    val keyAliasNamespace: String
    val headAliasNamespace: String
    val aeadDomain: String
    val headDigestDomain: String
    val fixedKind: ClientJournalKind?
    val employeeEnrollmentCommitment: String?
    val pairedDeviceEnrollmentCommitment: String?
    val pairedActorEnrollmentCommitment: String?
}

/**
 * Platform custody seam of the shared client journal engine. Names are journal-directory relative
 * ([ARCHIVE] for archive entries). Every operation observes or acts on genuine native state.
 */
internal interface ClientJournalNative {
    /** Parent/root/journal/archive directory custody; [enrollChecks] then [createDataKey] run before any creation. */
    fun openCustody(rootName: String, journalId: DomainId, archive: Boolean, enroll: Boolean,
        enrollChecks: () -> Unit, createDataKey: () -> Unit)
    fun acquireLock(enroll: Boolean)
    fun exists(name: String): Boolean
    fun journalNames(): Set<String>
    fun archiveNames(visit: (String) -> Unit)
    fun readSnapshot(name: String, archive: Boolean, maximum: Int, allowTwoLinks: Boolean = false, force: Boolean = false): ClientJournalSnapshot
    /** Archive entry linked exactly with the temporary (interrupted no-replace publication). */
    fun archiveLinkedWithTemporary(name: String): Boolean
    fun archiveHasTwoLinks(name: String): Boolean
    fun writeNewFile(name: String, bytes: ByteArray, maximum: Int): ClientJournalMark
    /** Completes the exact fenced ciphertext as a temporary prefix and publishes without replacement. */
    fun publishPrepared(name: String, archive: Boolean, ciphertext: ByteArray, maximum: Int, beforePublish: () -> Unit)
    /** Removes a proved duplicate temporary after publication. */
    fun removePublishedTemporary(name: String, archive: Boolean, ciphertext: ByteArray, maximum: Int, beforeRemove: () -> Unit)
    fun syncDirectories(archive: Boolean)
    fun removeWriteAhead()
    fun assertBase()
    /** Platform facts bound into every AEAD (same order Android always used). */
    fun writeAadFacts(text: (String) -> Unit, int: (Int) -> Unit, long: (Long) -> Unit)
    fun requireActive()
    // Key namespace (data key and anti-rollback heads share one OS namespace).
    fun keyNames(prefix: String): List<String>
    fun keyExists(name: String): Boolean
    fun createKey(name: String, protection: SelfOwnedKeyProtectionV1)
    fun deleteKey(name: String)
    /** Inspects one key's policy; returns an opaque security observation compared by equality. */
    fun inspectKey(name: String, protection: SelfOwnedKeyProtectionV1): Any
    fun encrypt(name: String, aad: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray>
    fun decrypt(name: String, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray
    fun release()
}

/**
 * Anti-rollback head: zero-use named keys `prefix + sequence/digest` in the OS key namespace. A
 * successor is created before the new file cut and the predecessor pruned only after it is durable.
 */
internal class ClientJournalHead(private val native: ClientJournalNative, private val prefix: String,
    private val protection: SelfOwnedKeyProtectionV1) {
    private data class Head(val sequence: Long, val digest: String, val alias: String)
    private var retained: List<Head>? = null

    fun requireAbsent() { check(readHeads().isEmpty()) }
    fun enroll(digest: String) {
        check(retained == null); requireAbsent()
        val initial = head(0, digest)
        native.requireActive(); native.createKey(initial.alias, protection)
        check(readHeads() == listOf(initial)); retained = listOf(initial)
    }
    fun open(digest: String) {
        check(retained == null)
        val actual = readHeads()
        check(actual.isNotEmpty() && actual.last().digest == digest); retained = actual
    }
    fun requireCurrent(digest: String) {
        val expected = checkNotNull(retained)
        check(readHeads() == expected && expected.last().digest == digest)
    }
    fun requireInitial(digest: String) { requireCurrent(digest); check(retained == listOf(head(0, digest))) }
    fun settledSequence(digest: String): Long { requireCurrent(digest); pruneOld(); return checkNotNull(retained).single().sequence }
    fun prepare(previousSequence: Long, previousDigest: String, nextDigest: String) {
        requireCurrent(previousDigest)
        val old = head(previousSequence, previousDigest)
        check(retained == listOf(old) && previousDigest != nextDigest)
        val next = head(Math.addExact(old.sequence, 1), nextDigest)
        native.requireActive(); native.createKey(next.alias, protection)
        val expected = listOf(old, next)
        check(readHeads() == expected); retained = expected
    }
    fun recoverPrepared(previousSequence: Long, previousDigest: String, nextDigest: String) {
        check(retained == null && previousDigest != nextDigest)
        val old = head(previousSequence, previousDigest)
        val next = head(Math.addExact(previousSequence, 1), nextDigest)
        val actual = readHeads()
        check(actual == listOf(next) || actual == listOf(old, next)); retained = actual
    }
    fun finishPrepared(nextDigest: String) { requireCurrent(nextDigest); pruneOld(); requireCurrent(nextDigest) }
    private fun pruneOld() {
        val actual = checkNotNull(retained)
        check(readHeads() == actual)
        if (actual.size == 1) return
        check(actual.size == 2)
        native.requireActive(); native.deleteKey(actual.first().alias)
        check(readHeads() == listOf(actual.last())); retained = listOf(actual.last())
    }
    private fun readHeads(): List<Head> {
        native.requireActive()
        val found = ArrayList<Head>(2)
        val journalPrefix = prefix.removeSuffix("/").substringBeforeLast("/") + "/"
        for (name in native.keyNames(journalPrefix)) {
            check(name.startsWith(prefix))
            check(found.size < 2)
            val suffix = name.removePrefix(prefix)
            check(suffix.length == 81 && suffix[16] == '/' && suffix.take(16).all { it in '0'..'9' || it in 'a'..'f' })
            val sequence = suffix.take(16).toLong(16)
            val value = head(sequence, suffix.drop(17))
            check(value.alias == name && found.none { it.sequence == sequence })
            native.inspectKey(name, protection)
            found.add(value)
        }
        native.requireActive()
        val result = found.sortedBy { it.sequence }
        if (result.size == 2) check(result[0].sequence < Long.MAX_VALUE && result[1].sequence == result[0].sequence + 1)
        return result
    }
    private fun head(sequence: Long, digest: String): Head {
        check(sequence >= 0 && digest.length == 64 && digest.all { it in '0'..'9' || it in 'a'..'f' })
        return Head(sequence, digest, prefix + sequence.toString(16).padStart(16, '0') + "/" + digest)
    }
}

/**
 * Shared same-lifetime client journal engine (Android and desktop): encrypted manifest, immutable
 * archives, write-ahead + anti-rollback head protocol, census and key witness. Platforms supply only
 * [ClientJournalNative] custody and the initial manifest. All native work runs in [work].
 */
internal abstract class ClientJournalFileCore(
    val deviceOwner: LocalClientDeviceIdentity,
    val actorId: DomainId?,
    val journalId: DomainId,
    val keyId: DomainId,
    private val protection: SelfOwnedKeyProtectionV1,
    protected val work: ClientJournalWork,
    private val binding: ClientJournalBindingFacts,
    private val native: ClientJournalNative,
) {
    init { binding.requireCoordinates(deviceOwner, actorId, journalId, keyId, protection) }
    val kind: ClientJournalKind = binding.fixedKind ?: if (actorId == null) ClientJournalKind.CREDENTIALS else ClientJournalKind.INTENTS
    private enum class Phase { NEW, OPENING, OPEN, CLOSE_STARTED, CLOSED }
    private var phase = Phase.NEW
    private var manifestStamp: ClientJournalMark? = null
    private var archiveStamps: Map<String, ClientJournalMark>? = null
    private var keySecurity: Any? = null
    private var keyWitness: ByteArray? = null
    private var keyWitnessDigest: ByteArray? = null
    private var head: ClientJournalHead? = null
    protected var headAdmitted: Boolean = false
        private set
    private var recoveredWriteHead = false
    private val alias: String get() = binding.keyAliasNamespace + journalId.value + "/" + keyId.value
    private val intents: Boolean get() = kind == ClientJournalKind.INTENTS
    private val maximumManifest: Int get() = when (kind) {
        ClientJournalKind.CREDENTIALS -> MAX_CLIENT_CREDENTIAL_BYTES
        ClientJournalKind.INTENTS -> MAX_CLIENT_INTENT_MANIFEST_BYTES
        ClientJournalKind.ACTOR_ALLOCATIONS -> MAX_PAIRED_ACTOR_ALLOCATION_BYTES
    }

    /** Platform/kind-specific original empty manifest for explicit first enrollment only. */
    protected abstract fun initialManifest(): ByteArray

    fun openNative(enroll: Boolean, recoverWrites: Boolean) {
        check(phase == Phase.NEW)
        known(!enroll || !recoverWrites)
        known(binding.isCurrent() && (!enroll || binding.permitsEnrollment()))
        phase = Phase.OPENING
        head = ClientJournalHead(native, "${binding.headAliasNamespace}${journalId.value}/${keyId.value}/", protection)
        native.openCustody(binding.rootName, journalId, intents, enroll, enrollChecks = {
            known(binding.permitsEnrollment())
            // The original fixed key alias is the once-only native enrollment intent, created BEFORE
            // filesystem creation. A partial/unknown intent is never replaced.
            known(!native.keyExists(alias))
            known(native.keyNames("${binding.keyAliasNamespace}${journalId.value}/").isEmpty())
            requireNotNull(head).requireAbsent()
        }, createDataKey = { native.createKey(alias, protection) })
        val hasWriteAhead = !enroll && native.exists(ClientJournalWriteCodecV1.FILE_NAME)
        val hasTemporary = !enroll && native.exists(TEMPORARY)
        if (!enroll) {
            known((!hasWriteAhead && !hasTemporary) || (recoverWrites && hasWriteAhead))
            checkNames(pending = hasTemporary, writeAhead = hasWriteAhead)
        }
        native.acquireLock(enroll)
        phase = Phase.OPEN
        native.assertBase()
        if (enroll) createInitialManifest()
        checkNames(pending = hasTemporary, writeAhead = hasWriteAhead)
        val snapshot = native.readSnapshot(MANIFEST, false, maximumManifest + ENVELOPE_BYTES)
        try {
            manifestStamp = snapshot.mark
            inspectDataKey()
            decrypt(snapshot.bytes, "manifest", maximumManifest).useWiped { plain -> keyWitnessDigest = digest(plain) }
            keyWitness = snapshot.bytes.copyOf()
        } finally { snapshot.clear() }
        checkedKey()
        if (hasWriteAhead) recoverPreparedWrite()
        checkNames()
    }

    fun admitNativeHead(enroll: Boolean) {
        known(!headAdmitted)
        val digest = completeDigest()
        when {
            enroll -> requireNotNull(head).enroll(digest)
            recoveredWriteHead -> requireNotNull(head).requireCurrent(digest)
            else -> requireNotNull(head).open(digest)
        }
        headAdmitted = true
    }

    /** Sequence-zero complete head: the FIRST journal never held work. */
    protected fun requireInitialHead() = requireNotNull(head).requireInitial(completeDigest())

    private fun createInitialManifest() {
        known(binding.permitsEnrollment() && !native.exists(MANIFEST))
        initialManifest().useWiped { plain ->
            // No witness exists yet: inspect (not witness-check) the newly created original key.
            encrypt(plain, "manifest", checked = false).useWiped { native.writeNewFile(MANIFEST, it, maximumManifest + ENVELOPE_BYTES) }
        }
    }

    private fun completeDigest(
        manifestDigest: String = requireNotNull(manifestStamp).digest,
        archiveDigests: Map<String, String> = if (intents) requireNotNull(archiveStamps).mapValues { it.value.digest } else emptyMap(),
    ): String {
        val hash = MessageDigest.getInstance("SHA-256")
        fun field(value: String) { (value + "\n").encodeToByteArray().useWiped(hash::update) }
        field(binding.headDigestDomain)
        binding.employeeEnrollmentCommitment?.let(::field)
        binding.pairedDeviceEnrollmentCommitment?.let(::field)
        binding.pairedActorEnrollmentCommitment?.let(::field)
        field(journalId.value); field(keyId.value); field(manifestDigest)
        field(archiveDigests.size.toString())
        for ((name, digest) in archiveDigests.toSortedMap()) { field(name); field(digest) }
        return hash.digest().useWiped(::encodeHex)
    }

    suspend fun <T> owned(prepare: () -> ClientJournalTask<T>): ClientJournalOutcome<T> =
        work.run {
            val task = prepare()
            try {
                object : ClientJournalTask<T> {
                    override fun execute(): T {
                        assertContinuity()
                        val value = task.execute()
                        try { assertContinuity(); return value }
                        catch (failure: Throwable) { task.discard(value); throw failure }
                    }
                    override fun discard(value: T) { task.discard(value) }
                    override fun clearInputs() { task.clearInputs() }
                }
            } catch (failure: Throwable) { task.clearInputs(); throw failure }
        }

    suspend fun closeView() { work.retireView().await() }
    suspend fun closeOwner() { work.requestClose { closeNative() }.await() }

    fun readManifest(maximumBytes: Int): ByteArray {
        known(maximumBytes == maximumManifest)
        native.assertBase()
        val snapshot = native.readSnapshot(MANIFEST, false, maximumBytes + ENVELOPE_BYTES)
        try {
            known(snapshot.mark == manifestStamp)
            checkedKey()
            val plain = decrypt(snapshot.bytes, "manifest", maximumBytes)
            try { native.assertBase(); checkedKey(); return plain }
            catch (failure: Throwable) { plain.fill(0); throw failure }
        } finally { snapshot.clear() }
    }

    fun replaceManifest(plaintext: ByteArray, maximumBytes: Int) {
        known(maximumBytes == maximumManifest && plaintext.size in 1..maximumBytes)
        assertContinuity()
        encrypt(plaintext, "manifest").useWiped { encrypted -> writeForced(MANIFEST, null, encrypted) }
        assertContinuity()
    }

    fun visitArchives(visit: (OperationId, ByteArray) -> Unit): Long {
        known(intents)
        native.assertBase()
        val previous = archiveStamps
        val observed = linkedMapOf<String, ClientJournalMark>()
        var count = 0L
        native.archiveNames { name ->
            val id = archiveId(name)
            known(name !in observed && observed.size < CLIENT_JOURNAL_MAX_ARCHIVES_V1)
            val snapshot = native.readSnapshot(name, true, MAX_CLIENT_ENTRY_BYTES + ENVELOPE_BYTES)
            try {
                if (previous != null) known(previous[name] == snapshot.mark)
                checkedKey()
                decrypt(snapshot.bytes, "archive/" + id.value, MAX_CLIENT_ENTRY_BYTES).useWiped { visit(id, it) }
                observed[name] = snapshot.mark
                count = Math.addExact(count, 1L)
            } finally { snapshot.clear() }
        }
        if (previous != null) known(previous.keys == observed.keys)
        native.assertBase(); checkedKey()
        archiveStamps = observed
        return count
    }

    fun appendArchive(operationId: OperationId, plaintext: ByteArray) {
        known(intents && plaintext.size in 1..MAX_CLIENT_ENTRY_BYTES)
        val name = archiveName(operationId)
        assertContinuity()
        val retained = requireNotNull(archiveStamps)
        known(name !in retained && retained.size < CLIENT_JOURNAL_MAX_ARCHIVES_V1)
        encrypt(plaintext, "archive/" + operationId.value).useWiped { encrypted -> writeForced(name, operationId, encrypted) }
        assertContinuity()
    }

    fun assertContinuity() {
        native.assertBase(); checkNames()
        val current = native.readSnapshot(MANIFEST, false, maximumManifest + ENVELOPE_BYTES)
        try { known(current.mark == manifestStamp) } finally { current.clear() }
        if (intents) checkArchiveMarks()
        checkedKey()
        if (headAdmitted) requireNotNull(head).requireCurrent(completeDigest())
        native.assertBase()
    }

    private fun checkNames(pending: Boolean = false, writeAhead: Boolean = false) {
        val allowed = mutableSetOf(LOCK, MANIFEST)
        if (intents) allowed.add(ARCHIVE)
        if (pending) allowed.add(TEMPORARY)
        if (writeAhead) allowed.add(ClientJournalWriteCodecV1.FILE_NAME)
        known(native.journalNames() == allowed)
    }

    private fun checkArchiveMarks() {
        val expected = requireNotNull(archiveStamps)
        val found = mutableSetOf<String>()
        native.archiveNames { name ->
            archiveId(name)
            known(found.size < CLIENT_JOURNAL_MAX_ARCHIVES_V1 && found.add(name))
            val actual = native.readSnapshot(name, true, MAX_CLIENT_ENTRY_BYTES + ENVELOPE_BYTES)
            try { known(expected[name] == actual.mark) } finally { actual.clear() }
        }
        known(found == expected.keys)
    }

    private fun writeForced(name: String, archiveId: OperationId?, bytes: ByteArray) {
        val maximum = if (archiveId != null) MAX_CLIENT_ENTRY_BYTES + ENVELOPE_BYTES else maximumManifest + ENVELOPE_BYTES
        known(bytes.size in 1..maximum)
        assertContinuity()
        known(headAdmitted)
        val beforeDigest = completeDigest()
        val sequence = requireNotNull(head).settledSequence(beforeDigest)
        known(name == (archiveId?.let(::archiveName) ?: MANIFEST))
        val write = ClientJournalWrite(sequence, beforeDigest, projectedDigest(archiveId, bytes), archiveId, bytes.copyOf())
        try {
            val ahead = ClientJournalWriteCodecV1.encode(write, maximum).useWiped { encoded ->
                native.writeNewFile(ClientJournalWriteCodecV1.FILE_NAME, encoded, maximumWriteAhead)
            }
            // Recheck the entire old cut and exact write-ahead bytes BEFORE advancing native authority.
            native.assertBase(); checkNames(writeAhead = true); requireWriteAhead(ahead)
            known(capturePreparedCut(write) == beforeDigest)
            checkedKey()
            requireNotNull(head).prepare(sequence, beforeDigest, write.nextDigest)
            finishPreparedWrite(write, ahead)
        } finally { write.clear() }
    }

    private val maximumWriteAhead: Int
        get() = maximumManifest + ENVELOPE_BYTES + ClientJournalWriteCodecV1.MAX_HEADER_BYTES

    /** Only the explicit recovery action reaches this; ordinary opening never changes files. */
    private fun recoverPreparedWrite() {
        known(!headAdmitted && !recoveredWriteHead)
        val ahead = native.readSnapshot(ClientJournalWriteCodecV1.FILE_NAME, false, maximumWriteAhead)
        try {
            val write = ClientJournalWriteCodecV1.decode(ahead.bytes, maximumManifest + ENVELOPE_BYTES)
            try {
                writeMaximum(write)
                requireNotNull(head).recoverPrepared(write.previousSequence, write.previousDigest, write.nextDigest)
                finishPreparedWrite(write, ahead.mark)
                recoveredWriteHead = true
            } finally { write.clear() }
        } finally { ahead.clear() }
    }

    private fun writeMaximum(write: ClientJournalWrite): Int {
        val maximum = if (write.archiveId == null) maximumManifest + ENVELOPE_BYTES else {
            known(intents); archiveName(write.archiveId); MAX_CLIENT_ENTRY_BYTES + ENVELOPE_BYTES
        }
        known(write.ciphertext.size in (ENVELOPE_BYTES + 1)..maximum)
        return maximum
    }

    private fun projectedDigest(archiveId: OperationId?, ciphertext: ByteArray): String {
        val next = digest(ciphertext).useWiped(::encodeHex)
        if (archiveId == null) return completeDigest(manifestDigest = next)
        known(intents)
        val old = requireNotNull(archiveStamps).mapValues { it.value.digest }
        val name = archiveName(archiveId)
        known(name !in old && old.size < CLIENT_JOURNAL_MAX_ARCHIVES_V1)
        return completeDigest(archiveDigests = old + (name to next))
    }

    private fun requireWriteAhead(expected: ClientJournalMark) {
        val actual = native.readSnapshot(ClientJournalWriteCodecV1.FILE_NAME, false, maximumWriteAhead)
        try { known(actual.mark == expected) } finally { actual.clear() }
    }

    /** Census every file; the only accepted two-link entry is the exact archive/temporary pair. */
    private fun capturePreparedCut(write: ClientJournalWrite): String {
        native.assertBase()
        val hasTemporary = native.exists(TEMPORARY)
        checkNames(pending = hasTemporary, writeAhead = true)
        val previousManifest = requireNotNull(manifestStamp)
        val manifest = native.readSnapshot(MANIFEST, false, maximumManifest + ENVELOPE_BYTES)
        try {
            if (write.archiveId != null || manifest.mark.digest == previousManifest.digest) known(manifest.mark == previousManifest)
            manifestStamp = manifest.mark
        } finally { manifest.clear() }
        if (intents) {
            val previous = archiveStamps
            val target = write.archiveId?.let(::archiveName)
            val observed = linkedMapOf<String, ClientJournalMark>()
            native.archiveNames { name ->
                archiveId(name)
                known(observed.size < CLIENT_JOURNAL_MAX_ARCHIVES_V1 && name !in observed)
                val linked = native.archiveHasTwoLinks(name)
                if (linked) known(hasTemporary && target == name && native.archiveLinkedWithTemporary(name))
                val actual = native.readSnapshot(name, true, MAX_CLIENT_ENTRY_BYTES + ENVELOPE_BYTES, allowTwoLinks = linked)
                try {
                    if (previous != null && name != target) known(previous[name] == actual.mark)
                    observed[name] = actual.mark
                } finally { actual.clear() }
            }
            if (previous != null) known(previous.keys.filterNot { it == target }.toSet() == observed.keys.filterNot { it == target }.toSet())
            archiveStamps = observed
        }
        checkedKey(); native.assertBase()
        return completeDigest()
    }

    /** Complete only the already native-fenced exact bytes; never retry a business command. */
    private fun finishPreparedWrite(write: ClientJournalWrite, ahead: ClientJournalMark) {
        val maximum = writeMaximum(write)
        requireWriteAhead(ahead)
        requireNotNull(head).requireCurrent(write.nextDigest)
        val purpose = write.archiveId?.let { "archive/" + it.value } ?: "manifest"
        checkedKey()
        decrypt(write.ciphertext, purpose, maximum - ENVELOPE_BYTES).useWiped { }
        val name = write.archiveId?.let(::archiveName) ?: MANIFEST
        val archive = write.archiveId != null
        when (capturePreparedCut(write)) {
            write.previousDigest -> {
                known(projectedDigest(write.archiveId, write.ciphertext) == write.nextDigest)
                native.publishPrepared(name, archive, write.ciphertext, maximum) {
                    requireWriteAhead(ahead)
                    known(capturePreparedCut(write) == write.previousDigest)
                    requireNotNull(head).requireCurrent(write.nextDigest)
                }
                native.assertBase(); checkNames(writeAhead = true)
            }
            write.nextDigest -> native.removePublishedTemporary(name, archive, write.ciphertext, maximum) {
                requireNotNull(head).requireCurrent(write.nextDigest)
            }
            else -> error("client.journal.write-cut-unresolved")
        }
        // Force the actual target and affected directory entries, then hash the complete cut.
        val actual = native.readSnapshot(name, archive, maximum, force = true)
        try { known(actual.bytes.contentEquals(write.ciphertext)) } finally { actual.clear() }
        native.syncDirectories(archive)
        known(capturePreparedCut(write) == write.nextDigest)
        requireWriteAhead(ahead)
        requireNotNull(head).finishPrepared(write.nextDigest)
        native.assertBase(); checkNames(writeAhead = true); requireWriteAhead(ahead)
        native.requireActive()
        native.removeWriteAhead()
        checkNames(); native.assertBase()
        requireNotNull(head).requireCurrent(completeDigest())
    }

    private fun encrypt(plaintext: ByteArray, purpose: String, checked: Boolean = true): ByteArray {
        if (checked) checkedKey() else inspectDataKey()
        val (nonce, ciphertext) = aad(purpose).useWiped { native.encrypt(alias, it, plaintext) }
        try {
            known(nonce.size == 12 && ciphertext.size == plaintext.size + 16)
            val encoded = ByteBuffer.allocate(ENVELOPE_BYTES + plaintext.size).putInt(ENVELOPE_MAGIC).put(nonce).put(ciphertext).array()
            try {
                if (checked) { checkedKey(); native.assertBase() }
                return encoded
            } catch (failure: Throwable) { encoded.fill(0); throw failure }
        } finally { nonce.fill(0); ciphertext.fill(0) }
    }

    private fun decrypt(encrypted: ByteArray, purpose: String, maximum: Int): ByteArray {
        known(encrypted.size in (ENVELOPE_BYTES + 1)..(maximum + ENVELOPE_BYTES))
        val input = ByteBuffer.wrap(encrypted)
        known(input.int == ENVELOPE_MAGIC)
        val nonce = ByteArray(12)
        try {
            input.get(nonce)
            val ciphertext = encrypted.copyOfRange(16, encrypted.size)
            try {
                val plain = aad(purpose).useWiped { native.decrypt(alias, nonce, ciphertext, it) }
                try { known(plain.size in 1..maximum); return plain } catch (failure: Throwable) { plain.fill(0); throw failure }
            } finally { ciphertext.fill(0) }
        } finally { nonce.fill(0) }
    }

    /** The same original data key: single key in its namespace, same policy, decrypts the witness. */
    private fun checkedKey() {
        inspectDataKey()
        decrypt(requireNotNull(keyWitness), "manifest", maximumManifest).useWiped { plain ->
            digest(plain).useWiped { known(MessageDigest.isEqual(it, requireNotNull(keyWitnessDigest))) }
        }
        work.requireActive()
    }

    private fun inspectDataKey() {
        work.requireActive()
        val names = native.keyNames(binding.keyAliasNamespace + journalId.value + "/")
        known(names == listOf(alias) && native.keyExists(alias))
        val security = native.inspectKey(alias, protection)
        known(keySecurity == null || keySecurity == security)
        keySecurity = security
        work.requireActive()
    }

    private fun aad(purpose: String): ByteArray {
        val out = ByteBuffer.allocate(8192)
        try {
            fun text(value: String) { value.encodeToByteArray(throwOnInvalidSequence = true).useWiped { out.putInt(it.size); out.put(it) } }
            text(binding.aeadDomain)
            text(deviceOwner.scope.organizationId.value); text(deviceOwner.scope.companyId.value)
            text(deviceOwner.scope.branchId.value); text(deviceOwner.deviceId.value)
            out.putInt(when (kind) { ClientJournalKind.CREDENTIALS -> 1; ClientJournalKind.INTENTS -> 2; ClientJournalKind.ACTOR_ALLOCATIONS -> 3 })
            text(actorId?.value.orEmpty()); text(journalId.value); text(keyId.value)
            binding.employeeEnrollmentCommitment?.let(::text)
            binding.pairedDeviceEnrollmentCommitment?.let(::text)
            binding.pairedActorEnrollmentCommitment?.let(::text)
            out.putInt(when (protection) {
                SelfOwnedKeyProtectionV1.OS_ISOLATED -> 1
                SelfOwnedKeyProtectionV1.SECURE_HARDWARE -> 2
                SelfOwnedKeyProtectionV1.STRONGBOX -> 3
            })
            native.writeAadFacts(::text, { out.putInt(it) }, { out.putLong(it) })
            text(purpose)
            return out.array().copyOf(out.position())
        } finally { out.array().fill(0) }
    }

    /** Only invoked by watched owned disposal/close. Unknown close is NEVER retried. */
    fun closeNative() {
        work.prepareNativeClose()
        if (phase == Phase.CLOSED) return
        if (phase == Phase.CLOSE_STARTED) { work.nativeClosureUnknown(); error("client.journal.close-unknown") }
        phase = Phase.CLOSE_STARTED
        try {
            native.release()
            keyWitness?.fill(0); keyWitnessDigest?.fill(0)
            keyWitness = null; keyWitnessDigest = null; keySecurity = null
            head = null; headAdmitted = false; recoveredWriteHead = false
            manifestStamp = null; archiveStamps = null
            phase = Phase.CLOSED
        } catch (failure: Throwable) { work.nativeClosureUnknown(); throw failure }
    }

    private fun archiveName(id: OperationId): String {
        known(CLIENT_JOURNAL_UUID_V1.matches(id.value))
        return id.value + ".v1.enc"
    }
    private fun archiveId(name: String): OperationId {
        known(name.length == 43 && name.endsWith(".v1.enc"))
        val id = name.removeSuffix(".v1.enc")
        known(CLIENT_JOURNAL_UUID_V1.matches(id))
        return OperationId(id)
    }
    protected fun known(value: Boolean) { check(value) { "client.journal.unavailable" } }

    companion object {
        const val LOCK = "client.lock"
        const val MANIFEST = "active.v1.enc"
        const val ARCHIVE = "archive"
        const val TEMPORARY = "write.pending"
        const val ENVELOPE_BYTES = 32 // explicit magic + 12-byte provider IV + 16-byte GCM tag
        const val ENVELOPE_MAGIC = 0x41434a31
        fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}

internal val CLIENT_JOURNAL_UUID_V1 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

internal const val MAX_PAIRED_ACTOR_ALLOCATION_BYTES: Int = 128 * 1024

/** Bounded retained-mark engineering profile; full census refuses excess, nothing is pruned. */
internal const val CLIENT_JOURNAL_MAX_ARCHIVES_V1: Int = 65_536
