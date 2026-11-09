package com.ukkera.pos.rpc.independent.factorylifecycle

import com.ukkera.pos.rpc.LocalRpcFactoryPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Supporting RFIR-SDK-02/03/04 and RFLC-PROFILE-02 assertions only. Actual official factories are
 * exercised through the retained production wrapper, without network entry points. The wrapper
 * does not expose the raw SDK post-close getter or independent owned-Kit/native cleanup evidence;
 * these tests cannot promote any complete frozen case, POS admission or global zero-effect result.
 */
class LocalRpcFactoryReadbackSupportingTest {
    @Test
    fun hostExactUnicodeNamesRetainActualSnapshotAfterWrapperClose() = runBlocking {
        exactNamesAndRetainedReadback(FactoryRole.HOST)
    }

    @Test
    fun clientExactUnicodeNamesRetainActualSnapshotAfterWrapperClose() = runBlocking {
        exactNamesAndRetainedReadback(FactoryRole.CLIENT)
    }

    @Test
    fun hostSuccessfulReconstructionReopensIntactProtectedStore() = runBlocking {
        successfulProtectedReconstruction(FactoryRole.HOST)
    }

    @Test
    fun clientSuccessfulReconstructionReopensIntactProtectedStore() = runBlocking {
        successfulProtectedReconstruction(FactoryRole.CLIENT)
    }

    @Test
    fun hostSeparatelyMissingProtectedOriginalCannotAdoptReplacement() = runBlocking {
        separatelyMissingOriginalRefuses(FactoryRole.HOST)
    }

    @Test
    fun clientSeparatelyMissingProtectedOriginalCannotAdoptReplacement() = runBlocking {
        separatelyMissingOriginalRefuses(FactoryRole.CLIENT)
    }

    private suspend fun exactNamesAndRetainedReadback(role: FactoryRole) {
        val returnedNames = mutableListOf<String>()
        for (name in FactoryReadbackInputs.names) {
            val fixture = ProtectedFactoryFixture.create()
            try {
                val owner = fixture.capture(role, name)
                val s0 = observe { owner.acquire() }
                val original = ActualOriginal(s0)
                assertEquals(FactoryReadbackInputs.appId, s0.appId)
                assertEquals(name, s0.deviceName, "The exact supplied Unicode text must be retained")
                returnedNames.add(s0.deviceName)
                assertEquals(LocalRpcFactoryPhase.READY, owner.phase.value)
                original.assertUnchanged(observe { owner.acquire() })
                original.assertUnchanged(s0)
                val providerBeforeClose = fixture.profile.counts()

                fixture.closeAndObserve(owner)
                assertEquals(LocalRpcFactoryPhase.CLOSED, owner.phase.value)
                original.assertUnchanged(s0)
                assertEquals(name, s0.deviceName)
                fixture.closeAndObserve(owner)
                assertEquals(LocalRpcFactoryPhase.CLOSED, owner.phase.value)
                original.assertUnchanged(s0)
                assertFailsWith<IllegalStateException> { observe { owner.acquire() } }
                assertEquals(providerBeforeClose, fixture.profile.counts(),
                    "Repeated wrapper close/read refusal cannot create another identity through these providers")
            } finally { fixture.finish() }
        }
        assertEquals(FactoryReadbackInputs.names, returnedNames)
        assertNotEquals(returnedNames[2], returnedNames[3], "NFC and decomposed labels remain distinct")
    }

    /** Success-path slice; an explicit SDK refusal is never relabeled as successful reconstruction. */
    private suspend fun successfulProtectedReconstruction(role: FactoryRole) {
        for (name in listOf(FactoryReadbackInputs.english, FactoryReadbackInputs.arabic)) {
            val fixture = ProtectedFactoryFixture.create()
            try {
                val first = fixture.capture(role, name)
                val s0 = observe { first.acquire() }
                val original = ActualOriginal(s0)
                fixture.closeAndObserve(first)
                assertEquals(LocalRpcFactoryPhase.CLOSED, first.phase.value)
                val intactCiphertexts = fixture.profile.protectedEvidence()

                // New genuine JVM platform/store instances reopen the same encrypted files and key.
                // This is same-process SDK persistence, not durable native original association.
                val reopened = fixture.reopenProtectedProfile()
                val reconstructed = fixture.reconstruct(role, s0, reopened)
                val s1 = observe { reconstructed.acquire() }
                original.assertUnchanged(s1)
                original.assertUnchanged(s0)
                assertEquals(name, s1.deviceName)
                assertEquals(LocalRpcFactoryPhase.READY, reconstructed.phase.value)
                original.assertUnchanged(observe { reconstructed.acquire() })
                assertEquals(intactCiphertexts, reopened.protectedEvidence(),
                    "Intact original protected records must not be overwritten")

                fixture.closeAndObserve(reconstructed)
                fixture.closeAndObserve(reconstructed)
                assertEquals(LocalRpcFactoryPhase.CLOSED, reconstructed.phase.value)
                original.assertUnchanged(s0)
                original.assertUnchanged(s1)
                assertEquals(intactCiphertexts, reopened.protectedEvidence())
            } finally { fixture.finish() }
        }
    }

    private suspend fun separatelyMissingOriginalRefuses(role: FactoryRole) {
        val originalFixture = ProtectedFactoryFixture.create()
        var missingFixture: ProtectedFactoryFixture? = null
        try {
            val first = originalFixture.capture(role, FactoryReadbackInputs.english)
            val s0 = observe { first.acquire() }
            val original = ActualOriginal(s0)
            originalFixture.closeAndObserve(first)
            val originalEvidence = originalFixture.profile.protectedEvidence()

            // A separately prepared, genuinely protected empty profile is the frozen missing-state
            // negative input. The intact original is not erased and no native expected tuple is made.
            val separatelyPrepared = ProtectedFactoryFixture.create().also { missingFixture = it }
            val refused = separatelyPrepared.reconstruct(role, s0)
            assertTrue(separatelyPrepared.applicationJob.isActive)
            assertFailsWith<IllegalStateException> { observe { refused.acquire() } }
            awaitPhase(refused, LocalRpcFactoryPhase.CLOSED)
            separatelyPrepared.closeAndObserve(refused)
            assertEquals(LocalRpcFactoryPhase.CLOSED, refused.phase.value)
            val refusedCounts = separatelyPrepared.profile.counts()
            separatelyPrepared.closeAndObserve(refused)
            assertFailsWith<IllegalStateException> { observe { refused.acquire() } }
            assertEquals(refusedCounts, separatelyPrepared.profile.counts())
            original.assertUnchanged(s0)
            assertEquals(originalEvidence, originalFixture.profile.protectedEvidence(),
                "A missing-state candidate cannot overwrite the intact original protected evidence")
        } finally {
            missingFixture?.finish()
            originalFixture.finish()
        }
    }
}
