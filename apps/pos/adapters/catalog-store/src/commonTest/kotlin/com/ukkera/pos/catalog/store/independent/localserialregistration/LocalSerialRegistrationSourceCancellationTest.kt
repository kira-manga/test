@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.catalog.store.independent.localserialregistration

import com.ukkera.pos.catalog.store.LocalSerialRegistrationProfileV1
import com.ukkera.pos.catalog.store.LocalSerialRegistrationServices
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.AggregateKeyPage
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.ClaimKeyPage
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.QualifiedSerialRegistrationTime
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.NativeCompanyBusinessPublicationSnapshot
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Observable lifecycle subset of frozen supplemental LSRC-CANCEL-001.
 * Mutation evaluation/actions have no observer at this preparation entry point;
 * that required verification remains a gap, not an inferred zero or case pass.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalSerialRegistrationSourceCancellationTest {
    private val frozen = LocalSerialRegistrationFrozenMasterInputs

    @Test
    fun lsrcCancel001_suspendedSourceCancellationPropagatesWithoutNormalPreparation(): Unit = runTest {
        val activation = LocalSerialRegistrationServices(publications = null).commands
            .single { it.key == LocalSerialRegistrationProfileV1.ACTIVATE }
        val supplied = suppliedRequest()
        val snapshot = supplied.copyBytes()
        val fieldSnapshot = Json.parseToJsonElement(snapshot.decodeToString())
        val cancellation = CancellationException("SYNTH-LSRC-CANCEL-001")
        assertNull(cancellation.cause)
        val sources = SuspendedSerialSources(frozen.scope, cancellation)
        val context = CommandContext(frozen.scope, frozen.actorId, frozen.operationId, activation.key,
            semanticDigest(frozen.scope, frozen.actorId, activation.key, supplied))
        var thrownAtCallingBoundary: Throwable? = null
        var normalPreparationReturns = 0
        var admissionRestrictionReturns = 0

        // Capture at the immediate calling suspension boundary, not async.await().
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val prepared = activation.prepare(supplied, sources, context)
                normalPreparationReturns += 1
                if (prepared.admissionRestriction != null) admissionRestrictionReturns += 1
            } catch (failure: Throwable) {
                thrownAtCallingBoundary = failure
            }
        }
        val cancelledSource = sources.entered.await()
        val completedBeforeRelease = caller.isCompleted
        sources.release.complete(Unit)
        caller.join()
        testScheduler.advanceUntilIdle()

        assertFalse(completedBeforeRelease, "The entered source must remain suspended until release")
        assertSame(cancellation, thrownAtCallingBoundary)
        assertEquals("SYNTH-LSRC-CANCEL-001", thrownAtCallingBoundary?.message)
        assertNull(thrownAtCallingBoundary?.cause)
        assertEquals(0, normalPreparationReturns)
        assertEquals(0, admissionRestrictionReturns)
        assertEquals(0, sources.normalSourceReplies)
        for ((source, count) in sources.entries) {
            assertEquals(if (source == cancelledSource) 1 else 0, count, source)
        }
        val after = supplied.copyBytes()
        assertContentEquals(snapshot, after)
        assertEquals(fieldSnapshot, Json.parseToJsonElement(after.decodeToString()))
    }

    /** Fixed supplemental fixture; no original frozen request or identity is edited. */
    private fun suppliedRequest(): StoredBytes =
        frozen.changedDefinition("serialCode", JsonPrimitive("SYNTH-LSRC-001"))

    /** First public read suspends and throws C; all other replies are inert and unconsumed. */
    private class SuspendedSerialSources(
        override val scope: BranchScope,
        private val cancellation: CancellationException,
    ) : CommandReadView {
        val entered = CompletableDeferred<String>()
        val release = CompletableDeferred<Unit>()
        val entries = listOf("aggregate", "claim", "originalNativeBusinessPublications",
            "qualifiedSerialRegistrationTime", "aggregateKeys", "claimKeys", "retainedCommand")
            .associateWith { 0 }.toMutableMap()
        var normalSourceReplies = 0
            private set
        private var cancelledSource: String? = null

        override suspend fun aggregate(key: AggregateKey): StoredAggregate? = read("aggregate", null)
        override suspend fun claim(key: ClaimKey): DomainId? = read("claim", null)
        override suspend fun originalNativeBusinessPublications(): NativeCompanyBusinessPublicationSnapshot? =
            read("originalNativeBusinessPublications", null)
        override suspend fun qualifiedSerialRegistrationTime(): QualifiedSerialRegistrationTime? =
            read("qualifiedSerialRegistrationTime", null)
        override suspend fun aggregateKeys(kind: String, after: DomainId?, limit: Int): AggregateKeyPage =
            read("aggregateKeys", AggregateKeyPage(emptyList(), null))
        override suspend fun claimKeys(namespace: String, prefix: String, after: String?, limit: Int): ClaimKeyPage =
            read("claimKeys", ClaimKeyPage(emptyList(), null))
        override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? =
            read("retainedCommand", null)

        private suspend fun <T> read(source: String, inertReply: T): T {
            entries[source] = entries.getValue(source) + 1
            if (cancelledSource == null) {
                cancelledSource = source
                entered.complete(source)
                release.await()
                throw cancellation
            }
            if (source == cancelledSource) throw cancellation
            normalSourceReplies += 1
            return inertReply
        }
    }
}
