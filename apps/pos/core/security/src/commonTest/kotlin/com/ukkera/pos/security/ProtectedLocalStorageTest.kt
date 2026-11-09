package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Synthetic data only. These tests do not implement or qualify an accepting AEAD/OS provider. */
class ProtectedLocalStorageTest {
    private val storageKey = ProtectedStorageKeyReference(id(800))

    // Frozen LAS-002 protectedProfiles literals; profile additions retain existing identities.
    // Authority: qa/pos/designs/protected-auth-storage-contract-v1/fixtures.json.
    private val requiredProfilePlaintextBudgets: Map<String, Int> = linkedMapOf(
        "account" to 262144,
        "effective_policy" to 1048576,
        "admission" to 16384,
        "throttle" to 4096,
        "local_session_issuance" to 4096,
        "local_auth_configuration" to 4096,
        "local_refresh_issuance" to 4096,
        "local_auth_event" to 4096,
        "local_session_archive" to 4096,
        "local_action_approval" to 16384,
    )

    @Test
    fun tableProfilesKeepVersionedWireIdentitiesAndIndependentBudgets() {
        val tables = ProtectedLocalTable.entries.toList()
        assertEquals(tables.size, tables.map { it.wireName }.toSet().size)
        for (listing in listOf(tables, tables.reversed())) {
            for ((wireIdentity, maximumPlaintextBytes) in requiredProfilePlaintextBudgets) {
                val table = listing.single { it.wireName == wireIdentity }
                assertEquals(maximumPlaintextBytes, table.maximumPlaintextBytes, wireIdentity)
                assertEquals(1, table.schemaVersion, wireIdentity)
            }
        }
        assertTrue(ProtectedLocalTable.entries.all { it.schemaVersion == 1 })
        assertEquals(1, ProtectedLocalStorageFormat.FORMAT_VERSION)
        assertEquals("AES-256-GCM", ProtectedLocalStorageFormat.ALGORITHM)
        assertEquals(12, ProtectedLocalStorageFormat.NONCE_BYTES)
        assertEquals(16, ProtectedLocalStorageFormat.TAG_BYTES)
        assertEquals(512, ProtectedLocalStorageFormat.MAX_ADMISSION_DESCRIPTOR_BYTES)
        assertEquals(64, ProtectedLocalStorageFormat.MAX_DEVICE_CAPABILITIES)
        assertEquals(64, ProtectedLocalStorageFormat.MAX_DEVICE_CAPABILITY_BYTES)
    }

    @Test
    fun typedKeysDeriveTheirTableRecordIdAndCompleteThrottleDimension() {
        val keys = storageBindings().map { it.key }
        assertEquals(requiredProfilePlaintextBudgets.keys.toList(), keys.map { it.table.wireName })
        assertTrue(keys.all { it.scope == branch && it.recordId == id(4) })
        val accountBucket = keys.filterIsInstance<ProtectedLocalRecordKey.Throttle>().single()
        assertEquals("account", accountBucket.dimensionKey)
        val deviceBucket = ProtectedLocalRecordKey.Throttle(ThrottleKey(branch, id(4), ThrottleDimension.Device(id(6))))
        assertEquals("device:${id(6).value}", deviceBucket.dimensionKey)
        assertEquals(id(4), deviceBucket.recordId)
        assertEquals(branch, deviceBucket.scope)
    }

    @Test
    fun aadHasPinnedTwelveFieldOrderAndBigEndianFramingWithoutLossyCounters() {
        val binding = ProtectedLocalBinding(ProtectedLocalRecordKey.Account(branch, id(4)), Revision(9_007_199_254_740_993))
        val aad = binding.aad(storageKey)
        // 12 fields, then the 27-byte domain separator; this pins four-byte BE framing.
        assertContentEquals(byteArrayOf(0, 0, 0, 12, 0, 0, 0, 27), aad.copyOfRange(0, 8))
        assertEquals(listOf(
            "ukkera.local-storage/aad/v1", "1", "AES-256-GCM", id(800).value,
            "account", "1", id(1).value, id(2).value, id(3).value, id(4).value, "", "9007199254740993",
        ), readAadFields(aad))
        assertEquals("9223372036854775807", readAadFields(binding.copy(revision = Revision(Long.MAX_VALUE)).aad(storageKey)).last())
        assertEquals("0", readAadFields(binding.copy(revision = Revision(0)).aad(storageKey)).last())
    }

    @Test
    fun aadSeparatesAllLogicalTablesEvenWhenScopeIdAndRevisionCoincide() {
        val aadValues = storageBindings().map { it.aad(storageKey).toList() }
        assertEquals(5, aadValues.take(5).toSet().size)
        assertEquals(10, aadValues.toSet().size)
    }

    @Test
    fun aadSeparatesEachScopeComponentRecordIdentityAndRevision() {
        val key = ProtectedLocalRecordKey.Account(branch, id(4))
        val original = ProtectedLocalBinding(key, Revision(7)).aad(storageKey)
        val alternatives = listOf(
            ProtectedLocalBinding(key.copy(scope = branch.copy(organizationId = id(11))), Revision(7)),
            ProtectedLocalBinding(key.copy(scope = branch.copy(companyId = id(12))), Revision(7)),
            ProtectedLocalBinding(key.copy(scope = branch.copy(branchId = id(13))), Revision(7)),
            ProtectedLocalBinding(key.copy(actorId = id(14)), Revision(7)),
            ProtectedLocalBinding(key, Revision(8)),
        )
        for (alternative in alternatives) assertFalse(original.contentEquals(alternative.aad(storageKey)))
        assertEquals(alternatives.size, alternatives.map { it.aad(storageKey).toList() }.toSet().size)
    }

    @Test
    fun aadSeparatesThrottleAccountDeviceAndOwnerAndBindsTheDimension() {
        val account = ThrottleKey(branch, id(4), ThrottleDimension.Account)
        val device = account.copy(dimension = ThrottleDimension.Device(id(6)))
        val alternatives = listOf(account, device,
            device.copy(dimension = ThrottleDimension.Device(id(16))), device.copy(actorId = id(14)))
        val aadValues = alternatives.map { ProtectedLocalBinding(ProtectedLocalRecordKey.Throttle(it), Revision(2)).aad(storageKey) }
        assertEquals(4, aadValues.map { it.toList() }.toSet().size)
        assertEquals("account", readAadFields(aadValues[0])[10])
        assertEquals("device:${id(6).value}", readAadFields(aadValues[1])[10])
    }

    @Test
    fun aadSeparatesPermittedKeyReferencesAndOwnsEachReturnedByteArray() {
        val binding = storageBindings().first()
        val original = binding.aad(storageKey)
        assertFalse(original.contentEquals(binding.aad(ProtectedStorageKeyReference(id(801)))))
        val disposable = binding.aad(storageKey)
        disposable.fill(0)
        assertContentEquals(original, binding.aad(storageKey))
    }

    @Test
    fun plaintextBudgetsAreInclusiveAndRejectEmptyNegativeOrExcessSizes() {
        for (binding in storageBindings()) {
            val maximumPlaintextBytes = requiredProfilePlaintextBudgets.getValue(binding.table.wireName)
            binding.requirePlaintextSize(1)
            binding.requirePlaintextSize(maximumPlaintextBytes)
            for (invalid in listOf(-1, 0, maximumPlaintextBytes + 1)) {
                assertFailsWith<IllegalArgumentException> { binding.requirePlaintextSize(invalid) }
            }
        }
    }

    @Test
    fun envelopesEnforcePerTableCiphertextBudgetFullTagAndExactNonceLength() {
        for (binding in storageBindings()) {
            val maximumPlaintextBytes = requiredProfilePlaintextBudgets.getValue(binding.table.wireName)
            for (size in listOf(17, maximumPlaintextBytes + 16)) {
                val sealed = SealedLocalRecord(binding, storageKey, ByteArray(12), ByteArray(size))
                assertEquals(size, sealed.ciphertextAndTagSize)
                assertEquals(1, sealed.formatVersion)
                assertEquals("AES-256-GCM", sealed.algorithm)
            }
            for (size in listOf(0, 15, 16, maximumPlaintextBytes + 17)) {
                assertFailsWith<IllegalArgumentException> {
                    SealedLocalRecord(binding, storageKey, ByteArray(12), ByteArray(size))
                }
            }
            for (size in listOf(0, 11, 13)) {
                assertFailsWith<IllegalArgumentException> {
                    SealedLocalRecord(binding, storageKey, ByteArray(size), ByteArray(17))
                }
            }
        }
    }

    @Test
    fun envelopeDefensivelyOwnsBothInputsAndEveryReturnedCopy() {
        val nonce = ByteArray(12) { it.toByte() }
        val ciphertext = ByteArray(33) { (it + 1).toByte() }
        val expectedNonce = nonce.copyOf()
        val expectedCiphertext = ciphertext.copyOf()
        val binding = storageBindings().first()
        val sealed = SealedLocalRecord(binding, storageKey, nonce, ciphertext)
        nonce.fill(0)
        ciphertext.fill(0)
        sealed.copyNonce().fill(9)
        sealed.copyCiphertextAndTag().fill(9)
        assertContentEquals(expectedNonce, sealed.copyNonce())
        assertContentEquals(expectedCiphertext, sealed.copyCiphertextAndTag())
        assertEquals(binding, sealed.binding)
        assertEquals(storageKey, sealed.keyReference)
    }

    @Test
    fun openedBytesHaveDefensiveOwnershipAndAnAbsoluteBound() {
        val plaintext = "SYNTHETIC-PRIVATE-PLAINTEXT".encodeToByteArray()
        val expected = plaintext.copyOf()
        val opened = LocalOpenResult.Opened(plaintext)
        plaintext.fill(0)
        opened.copyPlaintext().fill(0)
        assertContentEquals(expected, opened.copyPlaintext())
        assertEquals(expected.size, opened.size)
        assertEquals(1, LocalOpenResult.Opened(byteArrayOf(1)).size)
        val maximum = requiredProfilePlaintextBudgets.getValue("effective_policy")
        assertEquals(maximum, LocalOpenResult.Opened(ByteArray(maximum)).size)
        for (size in listOf(0, maximum + 1)) {
            assertFailsWith<IllegalArgumentException> { LocalOpenResult.Opened(ByteArray(size)) }
        }
    }

    @Test
    fun byteResultDiagnosticsRedactContentsAndRecordCoordinates() {
        val privateBytes = "SYNTHETIC-PRIVATE-PLAINTEXT".encodeToByteArray()
        val record = SealedLocalRecord(storageBindings().first(), storageKey, ByteArray(12), privateBytes)
        for (text in listOf(record.toString(), LocalSealResult.Sealed(record).toString(), LocalOpenResult.Opened(privateBytes).toString())) {
            assertTrue("[REDACTED]" in text)
            assertFalse("SYNTHETIC" in text)
            assertFalse(id(4).value in text)
            assertFalse(id(800).value in text)
        }
    }

    @Test
    fun unavailableDefaultDeniesEveryTableWithoutReturningPlaintextOrAnEnvelope() = runTest {
        val provider = UnavailableDeviceProtectedLocalStorage
        assertEquals(KeyProtectionStatus.UNSUPPORTED, provider.protectionStatus)
        for (binding in storageBindings()) {
            val sealed = SealedLocalRecord(binding, storageKey, ByteArray(12), ByteArray(17))
            for (input in listOf(byteArrayOf(1), byteArrayOf())) {
                assertEquals(ProtectedKeyFailure.CONTEXT_NOT_QUALIFIED,
                    assertIs<LocalSealResult.Unavailable>(provider.seal(storageKey, binding, input)).reason)
            }
            assertEquals(ProtectedKeyFailure.CONTEXT_NOT_QUALIFIED,
                assertIs<LocalOpenResult.Unavailable>(provider.open(storageKey, binding, sealed)).reason)
            val mismatchedBinding = when (val key = binding.key) {
                is ProtectedLocalRecordKey.SessionIssuance -> binding.copy(key = key.copy(sessionId = id(14)))
                is ProtectedLocalRecordKey.RefreshIssuance -> binding.copy(key = key.copy(sessionId = id(14)))
                is ProtectedLocalRecordKey.AuthEvent -> binding.copy(key = key.copy(eventId = id(14)))
                is ProtectedLocalRecordKey.SessionArchive -> binding.copy(key = key.copy(sessionId = id(14)))
                else -> binding.copy(revision = Revision(1))
            }
            assertEquals(ProtectedKeyFailure.CONTEXT_NOT_QUALIFIED,
                assertIs<LocalOpenResult.Unavailable>(provider.open(ProtectedStorageKeyReference(id(801)), mismatchedBinding, sealed)).reason)
        }
    }

    @Test
    fun credentialRevisionZeroIsValidAndNullIsExplicitlyDistinctFromARecord() {
        val credential = testPasswordRecord()
        val account = ProtectedLocalAccount(accountState(), Revision(0), credential)
        assertSame(credential, account.credential)
        assertEquals(Revision(0), account.credentialRevision)
        for (revision in listOf(Revision(0), Revision(7), Revision(Long.MAX_VALUE))) {
            val withoutCredential = ProtectedLocalAccount(accountState(), revision, null)
            assertNull(withoutCredential.credential)
            assertEquals(revision, withoutCredential.credentialRevision)
        }
        credential.copySalt().fill(0)
        credential.copyVerifier().fill(0)
        assertContentEquals(ByteArray(16) { 1 }, account.credential!!.copySalt())
        assertContentEquals(ByteArray(32) { 2 }, account.credential!!.copyVerifier())
    }

    @Test
    fun credentialBindingMustMatchEveryTenantActorAndCredentialRevisionComponent() {
        val expected = CredentialBinding(branch, id(4), Revision(0))
        val badBindings = listOf(
            expected.copy(scope = branch.copy(organizationId = id(11))),
            expected.copy(scope = branch.copy(companyId = id(12))),
            expected.copy(scope = branch.copy(branchId = id(13))),
            expected.copy(actorId = id(14)), expected.copy(credentialRevision = Revision(1)),
        )
        for (bad in badBindings) {
            assertFailsWith<IllegalArgumentException> { ProtectedLocalAccount(accountState(), Revision(0), testPasswordRecord(bad)) }
        }
        // Account revision is separate: session-only changes do not retag a password verifier.
        assertEquals(Revision(4), ProtectedLocalAccount(accountState(revision = 4), Revision(0), testPasswordRecord()).state.revision)
    }

    @Test
    fun accountKeepsAll256SessionsAndOwnsInputAndOutputStateCollections() {
        val sessions = List(256) { sessionRecord(it) }
        val input = accountState(revision = 4, generation = 256, sessions = sessions)
        val account = ProtectedLocalAccount(input, Revision(0), null)
        (input.sessions as MutableList<LocalSessionRecord>).clear()
        val disposable = account.state
        (disposable.sessions as MutableList<LocalSessionRecord>).clear()
        assertEquals(sessions, account.state.sessions)
        assertEquals(256, account.state.sessions.size)
        assertEquals(Revision(4), account.state.revision)
        assertEquals(SessionGeneration(256), account.state.generation)
    }

    @Test
    fun accountRevalidatesHistoryAndIdentityEvenIfAnInputReadOnlyListWasMutated() {
        val overfull = accountState(generation = 300, sessions = List(256) { sessionRecord(it) })
        (overfull.sessions as MutableList<LocalSessionRecord>).add(sessionRecord(256))
        assertFailsWith<IllegalArgumentException> { ProtectedLocalAccount(overfull, Revision(0), null) }
        val wrongScope = accountState(generation = 2, sessions = List(2) { sessionRecord(it) })
        (wrongScope.sessions as MutableList<LocalSessionRecord>)[0] = sessionRecord(0).copy(scope = branch.copy(branchId = id(13)))
        assertFailsWith<IllegalArgumentException> { ProtectedLocalAccount(wrongScope, Revision(0), null) }
    }

    @Test
    fun exhaustedAccountAcceptsOnlyBothMaxDisabledAndEntirelyRevoked() {
        val revoked = listOf(sessionRecord(0).copy(status = SessionStatus.REVOKED))
        val badStates = listOf(
            accountState(revision = Long.MAX_VALUE, generation = 2, enabled = false, sessions = revoked),
            accountState(revision = 2, generation = Long.MAX_VALUE, enabled = false, sessions = revoked),
            accountState(revision = Long.MAX_VALUE, generation = Long.MAX_VALUE, sessions = revoked),
            accountState(revision = Long.MAX_VALUE, generation = Long.MAX_VALUE, sessions = listOf(sessionRecord(0))),
        )
        for (bad in badStates) {
            assertFailsWith<IllegalArgumentException> { ProtectedLocalAccount(bad, Revision(0), null) }
        }
        val terminal = ProtectedLocalAccount(
            accountState(revision = Long.MAX_VALUE, generation = Long.MAX_VALUE, enabled = false, sessions = revoked), Revision(0), null,
        )
        assertFalse(terminal.state.enabled)
        assertEquals(revoked, terminal.state.sessions)
    }

    @Test
    fun existingReducerTerminalJumpFromLowRevisionIsAcceptedWithoutDroppingHistory() {
        val initial = accountState(revision = 4, generation = Long.MAX_VALUE - 1, sessions = List(256) { sessionRecord(it) })
        val transition = assertIs<SessionTransition.Applied>(LocalSessions.disable(initial))
        val terminal = ProtectedLocalAccount(transition.state, Revision(0), testPasswordRecord())
        assertEquals(Revision(Long.MAX_VALUE), terminal.state.revision)
        assertEquals(SessionGeneration(Long.MAX_VALUE), terminal.state.generation)
        assertFalse(terminal.state.enabled)
        assertEquals(initial.sessions.map { it.sessionId }, terminal.state.sessions.map { it.sessionId })
        assertEquals(256, terminal.state.sessions.size)
        assertTrue(terminal.state.sessions.all { it.status == SessionStatus.REVOKED })
    }

    @Test
    fun policyRetainsEveryGrantFieldOrderAndMeaningfulSameCompanyScope() {
        val companyGrant = PermissionGrant(Permission(SecurityAction.DISCOUNT, PermissionVerb.APPROVE), branch.company(),
            GrantEffect.ALLOW, PermissionLimits(money(90_071), 5_250), OwnerAccess.ANY_OWNER, id(4),
            linkedSetOf(id(21), id(20)), PermissionLimits(money(80_070), 4_250))
        val branchDeny = PermissionGrant(Permission(SecurityAction.ORDER_READ, PermissionVerb.EXPORT), branch, GrantEffect.DENY)
        val sessions = LocalSessionPolicy(3)
        val throttle = ThrottlePolicy(1, 3_000, 60_000, 3_000)
        val policy = ActivatedLocalPolicy(branch, id(4), Revision(7), listOf(companyGrant, branchDeny), sessions, throttle)
        assertEquals(branch, policy.scope)
        assertEquals(id(4), policy.actorId)
        assertEquals(Revision(7), policy.revision)
        assertEquals(sessions, policy.sessionPolicy)
        assertEquals(throttle, policy.throttlePolicy)
        assertEquals(2, policy.grants.size)
        assertGrantFields(companyGrant, policy.grants[0])
        assertGrantFields(branchDeny, policy.grants[1])
        assertIs<CompanyScope>(policy.grants[0].scope)
    }

    @Test
    fun policyAllowsExplicitEmptyGrantsButRejectsEveryOtherTenantWithoutRetagging() {
        assertTrue(policyRecord(emptyList()).grants.isEmpty())
        val scopes = listOf(branch.copy(branchId = id(13)), branch.copy(companyId = id(12)),
            branch.copy(organizationId = id(11)), branch.company().copy(companyId = id(12)),
            branch.company().copy(organizationId = id(11)))
        for (scope in scopes) {
            val grant = PermissionGrant(Permission(SecurityAction.ORDER_CREATE, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW)
            assertFailsWith<IllegalArgumentException> { policyRecord(listOf(grant)) }
            assertEquals(scope, grant.scope)
        }
    }

    @Test
    fun perActorPolicyRejectsAnotherNamedSubjectInsteadOfDroppingOrRetaggingTheGrant() {
        val grant = PermissionGrant(Permission(SecurityAction.RECOVERY_ACTIVATION, PermissionVerb.PERFORM),
            branch.company(), GrantEffect.ALLOW, namedSubjectId = id(14))
        assertFailsWith<IllegalArgumentException> { policyRecord(listOf(grant)) }
        assertEquals(id(14), grant.namedSubjectId)
        assertEquals(branch.company(), grant.scope)
    }

    @Test
    fun policyAppliesGrantAndNestedTargetBoundsInsteadOfDroppingEntries() {
        val grant = PermissionGrant(Permission(SecurityAction.ORDER_CREATE, PermissionVerb.PERFORM), branch, GrantEffect.ALLOW,
            targetIds = (1..256).map { id(2_000 + it) }.toSet())
        assertEquals(1024, policyRecord(List(1024) { grant }).grants.size)
        assertEquals(256, policyRecord(listOf(grant)).grants.single().targetIds!!.size)
        assertFailsWith<IllegalArgumentException> { policyRecord(List(1025) { grant }) }
        (grant.targetIds as MutableSet<DomainId>).add(id(9_999))
        assertFailsWith<IllegalArgumentException> { policyRecord(listOf(grant)) }
    }

    @Test
    fun policyOwnsInputListNestedTargetSetsAndEveryReturnedSnapshot() {
        val first = PermissionGrant(Permission(SecurityAction.ORDER_CREATE, PermissionVerb.PERFORM), branch, GrantEffect.ALLOW,
            targetIds = linkedSetOf(id(20), id(21)))
        val second = PermissionGrant(Permission(SecurityAction.ORDER_READ, PermissionVerb.EXPORT), branch.company(), GrantEffect.DENY)
        val input = mutableListOf(first, second)
        val policy = policyRecord(input)
        input.clear()
        (first.targetIds as MutableSet<DomainId>).clear()
        val disposable = policy.grants
        (disposable.first().targetIds as MutableSet<DomainId>).clear()
        (disposable as MutableList<PermissionGrant>).clear()
        assertEquals(2, policy.grants.size)
        assertEquals(setOf(id(20), id(21)), policy.grants.first().targetIds)
        assertEquals(second.permission, policy.grants.last().permission)
    }

    @Test
    fun admissionStoresOpaquePublicDescriptorsButRequiresOneForAnAdmittedRecord() {
        for ((key, fingerprint) in listOf("PUBLIC-KEY" to null, null to "opaque:PUBLIC-FINGERPRINT", "PUBLIC-KEY" to "PUBLIC-FINGERPRINT")) {
            val admission = admissionRecord(key = key, fingerprint = fingerprint)
            assertEquals(branch, admission.scope)
            assertEquals(id(6), admission.deviceId)
            assertEquals(Revision(0), admission.revision)
            assertTrue(admission.admitted)
            assertEquals(key, admission.publicKeyIdentifier)
            assertEquals(fingerprint, admission.publicFingerprint)
        }
        assertFalse(admissionRecord(admitted = false, key = null, fingerprint = null).admitted)
        assertFailsWith<IllegalArgumentException> { admissionRecord(key = null, fingerprint = null) }
    }

    @Test
    fun publicDeviceDescriptorBoundsRejectEmptyWhitespaceUnicodeAndOverlongValues() {
        for (bad in listOf("", " ", "key value", "\n", "\u0000", "\u007f", "\u03bb", "x".repeat(513))) {
            assertFailsWith<IllegalArgumentException> { admissionRecord(key = bad) }
            assertFailsWith<IllegalArgumentException> { admissionRecord(key = null, fingerprint = bad) }
        }
        val maximum = "x".repeat(512)
        assertEquals(maximum, admissionRecord(key = maximum, fingerprint = maximum).publicKeyIdentifier)
        assertEquals(maximum, admissionRecord(key = maximum, fingerprint = maximum).publicFingerprint)
    }

    @Test
    fun deviceCapabilitiesHaveIndependentCountAndAsciiCodeBounds() {
        assertTrue(admissionRecord(capabilities = emptySet()).capabilities.isEmpty())
        val maximumCount = (1..64).map { "cap.$it" }.toSet()
        assertEquals(maximumCount, admissionRecord(capabilities = maximumCount).capabilities)
        assertEquals(setOf("a".repeat(64)), admissionRecord(capabilities = setOf("a".repeat(64))).capabilities)
        assertEquals(setOf("Future.PROFILE-1_test"), admissionRecord(capabilities = setOf("Future.PROFILE-1_test")).capabilities)
        assertFailsWith<IllegalArgumentException> { admissionRecord(capabilities = maximumCount + "extra") }
        for (bad in listOf("", "a".repeat(65), "x y", "x/y", "x:y", "\u0000", "\u00e9")) {
            assertFailsWith<IllegalArgumentException> { admissionRecord(capabilities = setOf(bad)) }
        }
    }

    @Test
    fun admissionOwnsInputAndReturnedCapabilitySets() {
        val input = linkedSetOf("one", "two")
        val admission = admissionRecord(capabilities = input)
        input.clear()
        (admission.capabilities as MutableSet<String>).clear()
        assertEquals(setOf("one", "two"), admission.capabilities)
    }

    @Test
    fun protectedPayloadDiagnosticsNeverExposeCredentialGrantOrDeviceDescriptors() {
        val account = ProtectedLocalAccount(accountState(), Revision(0), testPasswordRecord())
        val grant = PermissionGrant(Permission(SecurityAction.DISCOUNT, PermissionVerb.APPROVE), branch, GrantEffect.ALLOW)
        val policy = policyRecord(listOf(grant))
        val admission = admissionRecord(key = "SYNTHETIC-PRIVATE-DESCRIPTOR", capabilities = setOf("SYNTHETIC-CAPABILITY"))
        for (text in listOf(account.toString(), policy.toString(), admission.toString())) {
            assertTrue("[REDACTED]" in text)
            assertFalse("SYNTHETIC" in text)
            assertFalse("DISCOUNT" in text)
            assertFalse("PBKDF2" in text)
            assertFalse(id(4).value in text)
        }
    }

    private fun storageBindings(): List<ProtectedLocalBinding> = listOf(
        ProtectedLocalRecordKey.Account(branch, id(4)), ProtectedLocalRecordKey.EffectivePolicy(branch, id(4)),
        ProtectedLocalRecordKey.Admission(branch, id(4)),
        ProtectedLocalRecordKey.Throttle(ThrottleKey(branch, id(4), ThrottleDimension.Account)),
        ProtectedLocalRecordKey.SessionIssuance(branch, id(4)),
        // Same inert lookup coordinate; these are not issued session/event/approval credentials.
        ProtectedLocalRecordKey.AuthConfiguration(branch, id(4)),
        ProtectedLocalRecordKey.RefreshIssuance(branch, id(4)),
        ProtectedLocalRecordKey.AuthEvent(branch, id(4)),
        ProtectedLocalRecordKey.SessionArchive(branch, id(4)),
        ProtectedLocalRecordKey.ActionApproval(branch, id(4)),
    ).map { ProtectedLocalBinding(it, Revision(0)) }

    private fun accountState(
        revision: Long = 0,
        generation: Long = 0,
        enabled: Boolean = true,
        sessions: List<LocalSessionRecord> = emptyList(),
    ): LocalAccountSessionState = LocalAccountSessionState(branch, id(4), enabled, Revision(revision), SessionGeneration(generation), sessions)

    private fun sessionRecord(index: Int): LocalSessionRecord = LocalSessionRecord(
        id(1_000 + index), branch, id(4), id(6), SessionGeneration(index.toLong() + 1), SessionStatus.ACTIVE, AuthenticationPurpose.INTERACTIVE,
    )

    private fun testPasswordRecord(binding: CredentialBinding = CredentialBinding(branch, id(4), Revision(0))): PasswordHashRecord =
        PasswordHashRecord(binding, PasswordHashParameters(), ProtectedPepperReference(id(880)), ByteArray(16) { 1 }, ByteArray(32) { 2 })

    private fun policyRecord(grants: List<PermissionGrant>): ActivatedLocalPolicy = ActivatedLocalPolicy(
        branch, id(4), Revision(0), grants, LocalSessionPolicy(), ThrottlePolicy(),
    )

    private fun admissionRecord(
        admitted: Boolean = true,
        key: String? = "PUBLIC-KEY",
        fingerprint: String? = null,
        capabilities: Set<String> = emptySet(),
    ): LocalDeviceAdmission = LocalDeviceAdmission(branch, id(6), Revision(0), admitted, key, fingerprint, capabilities)

    private fun assertGrantFields(expected: PermissionGrant, actual: PermissionGrant) {
        assertEquals(expected.permission, actual.permission)
        assertEquals(expected.scope, actual.scope)
        assertEquals(expected.effect, actual.effect)
        assertEquals(expected.limits, actual.limits)
        assertEquals(expected.ownerAccess, actual.ownerAccess)
        assertEquals(expected.namedSubjectId, actual.namedSubjectId)
        assertEquals(expected.targetIds, actual.targetIds)
        assertEquals(expected.delegationCeiling, actual.delegationCeiling)
    }

    /** Independent reader pins framing and rejects trailing or truncated output. */
    private fun readAadFields(bytes: ByteArray): List<String> {
        var offset = 0
        fun readUnsignedInt(): Int {
            assertTrue(offset + 4 <= bytes.size)
            var value = 0L
            repeat(4) { value = value * 256 + (bytes[offset++].toInt() and 255) }
            assertTrue(value <= Int.MAX_VALUE)
            return value.toInt()
        }
        val count = readUnsignedInt()
        assertEquals(12, count)
        val fields = List(count) {
            val length = readUnsignedInt()
            assertTrue(length <= bytes.size - offset)
            bytes.copyOfRange(offset, offset + length).decodeToString(throwOnInvalidSequence = true).also { offset += length }
        }
        assertEquals(bytes.size, offset)
        return fields
    }
}
