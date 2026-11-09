package com.ukkera.pos.database.independent.admitteddevicewithdrawal

import com.ukkera.pos.database.ProtectedLocalSecurityCodec
import com.ukkera.pos.database.RoomLocalAuthorizationFacts
import com.ukkera.pos.database.RuntimeDeviceRevocationEvidenceCodec
import com.ukkera.pos.database.RuntimeDeviceRevocationEvidenceCodecV2
import com.ukkera.pos.database.RuntimeDeviceRevocationEvidenceV1
import com.ukkera.pos.database.RuntimeHistoryFormatRejectedV2
import com.ukkera.pos.database.RuntimeHistoryTableV2
import com.ukkera.pos.database.deviceRevocationAccountAfterV1
import com.ukkera.pos.database.deviceRevocationAdmissionAfterV1
import com.ukkera.pos.database.deviceRevocationRequirementV1
import com.ukkera.pos.security.LocalAccountSessionState
import com.ukkera.pos.security.LocalDeviceAdmission
import com.ukkera.pos.security.ProtectedLocalBinding
import com.ukkera.pos.security.ProtectedLocalRecordKey
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SessionStatus
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Controlled reducer, declaration and profile slices only; see README for frozen mapping and limits. */
class AdmittedDeviceWithdrawalV2SupplementaryTest {
    @Test
    fun adah01_02_noTargetSessionsPreservesBothWholeProtectedAccountInputs() {
        val data = AdmittedDeviceWithdrawalV2SupplementaryData()
        assertEquals(listOf(data.foundingAccount, data.staffAccount), data.accounts.map { it.state.actorId })
        assertEquals(listOf(data.session), data.operatorAccount.state.sessions.map { it.sessionId })
        assertTrue(data.enrolledAccount.state.sessions.isEmpty())
        for (account in data.accounts) {
            assertNotNull(account.credential)
            assertTrue(account.state.sessions.none { it.deviceId == data.target })
            val before = ProtectedLocalSecurityCodec.encodeAccount(account)
            val after = deviceRevocationAccountAfterV1(account, data.target)
            val afterBytes = ProtectedLocalSecurityCodec.encodeAccount(after)
            try {
                assertContentEquals(before, afterBytes)
                assertEquals(account.state.revision, after.state.revision)
                assertEquals(account.state.generation, after.state.generation)
                assertEquals(account.credentialRevision, after.credentialRevision)
                assertEquals(account.state.sessions, after.state.sessions)
                assertTrue(after.state.sessions.none { it.deviceId == data.target })
            } finally { before.fill(0); afterBytes.fill(0) }
        }
    }

    @Test
    fun adah02_admissionReducerPreservesTheDeclaredBeforeAndTargetDescriptors() {
        val data = AdmittedDeviceWithdrawalV2SupplementaryData()
        val before = data.targetAdmission
        val originalBytes = ProtectedLocalSecurityCodec.encodeAdmission(before)
        val after = deviceRevocationAdmissionAfterV1(before)
        val retainedBytes = ProtectedLocalSecurityCodec.encodeAdmission(before)
        try {
            assertContentEquals(originalBytes, retainedBytes)
            assertTrue(before.admitted)
            assertFalse(after.admitted)
            assertEquals(before.scope, after.scope)
            assertEquals(before.deviceId, after.deviceId)
            assertEquals(before.publicKeyIdentifier, after.publicKeyIdentifier)
            assertEquals(before.publicFingerprint, after.publicFingerprint)
            assertEquals(before.capabilities, after.capabilities)
        } finally { originalBytes.fill(0); retainedBytes.fill(0) }
        // This preserves an input value, not R's genuine admission history or current Room trust.
        // Withdrawal revision increments are intentionally not asserted as frozen requirements.
    }

    @Test
    fun adah01_02_captureKeepsDistinctTargetAndTheUnchangedTwoAccountDeclaration() = runTest {
        val data = AdmittedDeviceWithdrawalV2SupplementaryData()
        val capture = data.capture()
        assertEquals(data.scope, capture.scope)
        assertEquals(data.foundingAccount, capture.actorId)
        assertEquals(data.master, capture.operatorDeviceId)
        assertEquals(data.target, capture.targetDeviceId)
        assertNotEquals(capture.operatorDeviceId, capture.targetDeviceId)
        assertEquals(data.accounts.size, capture.accountCount)
        assertEquals(data.facts.runtimeCommandDependencies().admission, capture.operatorAdmissionBeforeDigest)
        assertTrue(capture.images.isEmpty()) // Predicted images did not manufacture submitted ones.
        data.expectedImages.forEach { data.submitDeclaredImage(capture, it) }
        val declaration = capture.evidence()
        assertEquals(data.census, declaration.accounts)
        assertEquals(listOf(data.foundingAccount, data.staffAccount), declaration.accounts.map { it.actorId })
        assertTrue(declaration.affectedAccounts.isEmpty())
        for (account in data.accounts) {
            val declared = declaration.accounts.single { it.actorId == account.state.actorId }
            assertFalse(declared.affected)
            assertEquals(account.state.sessions.size, declared.retainedSessions)
            assertEquals(data.accountDigest(account), declared.accountBeforeDigest)
            assertEquals(data.accountDigest(account), capture.expectedAccount(account.state.actorId))
        }
        assertNull(capture.expectedAccount(data.foreignTarget))
        assertEquals(data.expectedImages, declaration.images)
        assertTrue(declaration.images.none { it.table == RuntimeHistoryTableV2.ACCOUNT })
        assertNotEquals(capture.operatorAdmissionBeforeDigest, declaration.admissionBeforeDigest)
        // Complete declaration shape is not proof of a genuine complete account census or admission origin.
    }

    @Test
    fun adah04_completeDeclarationRetainsOriginalWithdrawalAndUnextendedAccessContext() = runTest {
        val data = AdmittedDeviceWithdrawalV2SupplementaryData()
        val declaration = data.completedDeclaration()
        val final = declaration.protectedFinalDeclaration()
        assertEquals(data.command, declaration.command)
        assertEquals(deviceRevocationRequirementV1(data.scope, data.foundingAccount, data.command),
            declaration.requirement.value())
        assertEquals(data.withdrawalRequest, final.originalRequestId)
        assertNotEquals(data.admissionRequest, final.originalRequestId)
        assertEquals(data.event.contentDigest, final.originalRequestDigest)
        assertEquals(data.foundingAccount, final.actorId)
        assertEquals(data.session, final.sessionId)
        assertEquals(data.issuer, final.issuerContextId)
        assertEquals(data.issuance.issuedAt, final.issuedAt)
        assertEquals(data.observed.moment, final.lastObservedAt)
        assertEquals(data.issuance.expiresAtElapsedMillis, final.expiresAtElapsedMillis)
        assertEquals(data.target, declaration.command.targetId)
        assertEquals(final, declaration.protectedFinalDeclaration())
        // No final native decision, settlement, retry dispatch or authorized outcome lookup is invoked.
    }

    @Test
    fun adah04_changedTargetCannotReuseTheOriginalWithdrawalRequirement() = runTest {
        val data = AdmittedDeviceWithdrawalV2SupplementaryData()
        val changed = data.command.copy(targetId = data.foreignTarget)
        assertEquals(data.command.requestId, changed.requestId)
        assertNotEquals(deviceRevocationRequirementV1(data.scope, data.foundingAccount, data.command),
            deviceRevocationRequirementV1(data.scope, data.foundingAccount, changed))
        assertFailsWith<IllegalArgumentException> { data.capture(selectedCommand = changed) }
        val declaration = data.completedDeclaration()
        assertFailsWith<IllegalArgumentException> { declaration.copy(command = changed).validate() }
        assertEquals(data.command, declaration.command)
    }

    @Test
    fun adah04_changedRequestOrExpectedBeforeCannotReuseTheOriginalDeclaration() = runTest {
        val data = AdmittedDeviceWithdrawalV2SupplementaryData()
        val declaration = data.completedDeclaration()
        val changedCommands = listOf(
            data.command.copy(requestId = data.admissionRequest),
            data.command.copy(expectedRevision = data.command.expectedRevision.next()),
        )
        for (changed in changedCommands) {
            assertFailsWith<IllegalArgumentException> { data.capture(selectedCommand = changed) }
            assertFailsWith<IllegalArgumentException> { declaration.copy(command = changed).validate() }
        }
        assertEquals(data.command, declaration.command)
        assertEquals(data.expectedImages, declaration.images)
    }

    @Test
    fun adah03_disabledOrRevokedSuppliedOperatorFactsCannotUseTheEarlierCapability() = runTest {
        val data = AdmittedDeviceWithdrawalV2SupplementaryData()
        val original = data.operatorAccount.state
        val revokedSessions = original.sessions.map { it.copy(status = SessionStatus.REVOKED) }
        val changedStates = listOf(
            LocalAccountSessionState(original.scope, original.actorId, true, original.revision,
                original.generation, revokedSessions),
            LocalAccountSessionState(original.scope, original.actorId, false, original.revision,
                original.generation, revokedSessions),
        )
        val dependencies = data.facts.runtimeCommandDependencies()
        for (state in changedStates) {
            val changedFacts = RoomLocalAuthorizationFacts(state, data.facts.credentialRevision,
                data.facts.credentialPresent, data.facts.policy, data.facts.admission, data.facts.issuance,
                listOf(dependencies.account, dependencies.policy, dependencies.admission, dependencies.issuance))
            assertFailsWith<IllegalArgumentException> { data.capture(selectedFacts = changedFacts) }
        }
        // These are substituted status values, not durable revocation/disablement or permission activation.
        assertTrue(data.operatorAccount.state.enabled)
        assertTrue(data.operatorAccount.state.sessions.all { it.status == SessionStatus.ACTIVE })
    }

    @Test
    fun adah05_foreignTargetScopeOrOperatorDeviceCannotSupplyTheTargetBefore() = runTest {
        val data = AdmittedDeviceWithdrawalV2SupplementaryData()
        val before = data.targetAdmission
        val foreign = LocalDeviceAdmission(data.foreignScope, data.target, before.revision, true,
            before.publicKeyIdentifier, before.publicFingerprint, before.capabilities)
        for (changed in listOf(foreign, data.operatorAdmission)) {
            assertFailsWith<IllegalArgumentException> { data.capture(selectedBefore = changed) }
        }
        assertTrue(data.targetAdmission.admitted)
        assertTrue(data.accounts.all { it.state.sessions.none { session -> session.deviceId == data.target } })
    }

    @Test
    fun adah05_foreignScopeOrTargetImageIsRefusedWithoutAppendingIt() = runTest {
        val data = AdmittedDeviceWithdrawalV2SupplementaryData()
        val image = data.expectedImages.first()
        val foreignBindings = listOf(
            ProtectedLocalBinding(ProtectedLocalRecordKey.Admission(data.foreignScope, data.target), image.revision),
            ProtectedLocalBinding(ProtectedLocalRecordKey.Admission(data.scope, data.foreignTarget), image.revision),
        )
        for (binding in foreignBindings) {
            val capture = data.capture()
            assertFailsWith<IllegalArgumentException> { capture.captureImage(binding, image.plaintextDigest) }
            assertTrue(capture.images.isEmpty())
            assertFailsWith<IllegalArgumentException> { capture.evidence() }
        }
    }

    @Test
    fun adah06_missingSubmittedImagesNeverProduceACompletedDeclaration() = runTest {
        val data = AdmittedDeviceWithdrawalV2SupplementaryData()
        for (size in data.expectedImages.indices) {
            val capture = data.capture()
            val prefix = data.expectedImages.take(size)
            prefix.forEach { data.submitDeclaredImage(capture, it) }
            assertFailsWith<IllegalArgumentException> { capture.evidence() }
            assertEquals(prefix, capture.images)
            assertEquals(data.expectedImages.size, capture.expectedWrites)
        }
        // Missing DATA is not filled from predictions; no actual uncertain writer is settled here.
    }

    @Test
    fun adah06_changedCommitmentCannotReplaceAnEarlierSubmittedImage() = runTest {
        val data = AdmittedDeviceWithdrawalV2SupplementaryData()
        val capture = data.capture()
        data.submitDeclaredImage(capture, data.expectedImages.first())
        val last = data.expectedImages.last()
        val changed = last.copy(plaintextDigest = PayloadDigest("f".repeat(64)))
        assertNotEquals(last.plaintextDigest, changed.plaintextDigest)
        assertFailsWith<IllegalArgumentException> { data.submitDeclaredImage(capture, changed) }
        assertEquals(listOf(data.expectedImages.first()), capture.images)
        assertFailsWith<IllegalArgumentException> { capture.evidence() }
    }

    @Test
    fun declarationCompatibility_reorderedOrExtraSubmissionDoesNotChangeTheExactImageSequence() = runTest {
        val data = AdmittedDeviceWithdrawalV2SupplementaryData()
        val reordered = data.capture()
        assertFailsWith<IllegalArgumentException> { data.submitDeclaredImage(reordered, data.expectedImages.last()) }
        assertTrue(reordered.images.isEmpty())

        val complete = data.capture()
        data.expectedImages.forEach { data.submitDeclaredImage(complete, it) }
        val original = complete.evidence()
        assertFailsWith<IllegalArgumentException> { data.submitDeclaredImage(complete, data.expectedImages.last()) }
        assertEquals(data.expectedImages, complete.images)
        assertEquals(original, complete.evidence())
        // Sequence compatibility only, not evidence of duplicate-request fencing or settlement.
    }

    @Test
    fun declarationCompatibility_missingAccountProjectionOrDuplicateActorIsRefused() = runTest {
        val data = AdmittedDeviceWithdrawalV2SupplementaryData()
        assertFailsWith<IllegalArgumentException> {
            data.capture(selectedExpectedAccounts = data.expectedAccounts - data.staffAccount)
        }
        val duplicate = listOf(data.census.first(), data.census.first())
        assertFailsWith<IllegalArgumentException> {
            data.capture(selectedAccounts = duplicate,
                selectedExpectedAccounts = mapOf(data.foundingAccount to data.expectedAccounts.getValue(data.foundingAccount)))
        }
        val declaration = data.completedDeclaration()
        assertFailsWith<IllegalArgumentException> { declaration.copy(accounts = declaration.accounts.reversed()).validate() }
        assertEquals(data.census, declaration.accounts)
        // Consistent omission from BOTH projections still needs the lead-owned genuine census join.
    }

    @Test
    fun profileCompatibility_v2RoundTripPreservesDataAndTheV1ReaderRejectsIt() = runTest {
        val data = AdmittedDeviceWithdrawalV2SupplementaryData()
        val original = data.completedDeclaration()
        val bytes = RuntimeDeviceRevocationEvidenceCodecV2.encode(original)
        try {
            assertTrue(RuntimeDeviceRevocationEvidenceCodecV2.hasProfile(bytes))
            assertFalse(RuntimeDeviceRevocationEvidenceCodec.hasProfile(bytes))
            val decoded = RuntimeDeviceRevocationEvidenceCodecV2.decode(bytes)
            assertEquals(original, decoded)
            val roundTrip = RuntimeDeviceRevocationEvidenceCodecV2.encode(decoded)
            try { assertContentEquals(bytes, roundTrip) } finally { roundTrip.fill(0) }
            assertEquals(original.protectedFinalDeclaration(), decoded.protectedFinalDeclaration())
            assertFailsWith<RuntimeHistoryFormatRejectedV2> { RuntimeDeviceRevocationEvidenceCodec.decode(bytes) }
        } finally { bytes.fill(0) }
        // Codec success retains DATA; it does not qualify custody, history or a native final decision.
    }

    @Test
    fun profileCompatibility_nonCanonicalUnknownTruncatedOrV1MarkedV2DataIsRefused() = runTest {
        val data = AdmittedDeviceWithdrawalV2SupplementaryData()
        val original = data.completedDeclaration()
        val bytes = RuntimeDeviceRevocationEvidenceCodecV2.encode(original)
        try {
            val text = bytes.decodeToString()
            val variants = listOf(
                (text + "\n").encodeToByteArray(),
                (text.dropLast(1) + ",\"unrecognized\":true}").encodeToByteArray(),
                bytes.copyOf(bytes.size - 1),
                text.replace(original.schema, RuntimeDeviceRevocationEvidenceV1.PROFILE).encodeToByteArray(),
            )
            try {
                variants.forEach { variant ->
                    assertFailsWith<RuntimeHistoryFormatRejectedV2> {
                        RuntimeDeviceRevocationEvidenceCodecV2.decode(variant)
                    }
                }
            } finally { variants.forEach { it.fill(0) } }
            assertEquals(original, RuntimeDeviceRevocationEvidenceCodecV2.decode(bytes))
        } finally { bytes.fill(0) }
        // No normalization, accepting profile upgrade or native-input repair is performed.
    }
}
