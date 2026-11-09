package com.ukkera.pos.security.independent.authstoragev1

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.ProtectedLocalBinding
import com.ukkera.pos.security.ProtectedLocalRecordKey
import com.ukkera.pos.security.ProtectedLocalStorageFormat
import com.ukkera.pos.security.ProtectedLocalTable
import com.ukkera.pos.security.ProtectedStorageKeyReference
import com.ukkera.pos.security.ThrottleDimension
import com.ukkera.pos.security.ThrottleKey
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Direct shared-contract slices of frozen LAS-002, below its required SQLite layer.
 *
 * These assert public profile metadata, typed lookup coordinates, size rejection and AAD
 * coordinate distinction. They do not provision/read/write a protected record, test schema-valid
 * boundary payloads or open a damaged envelope. There is no authentication, native-key, history,
 * restart or RPC claim. No whole LAS case or original gate is satisfied by these slices.
 *
 * Original cases/inputs remain frozen. BQ-01/02/07 and the genuine current owned-database setup
 * remain prerequisites for the omitted production persistence/service assertions.
 */
class AuthStorageV1ProfileContractTest {
    @Test
    fun las002_requiredWireProfilesEnvelopeAndTypedCoordinatesRemainDistinct() {
        val frozen = FrozenAuthStorageV1Inputs.load()
        val tables = ProtectedLocalTable.entries
        assertEquals(tables.size, tables.map { it.wireName }.toSet().size,
            "LAS-002: distinct profiles must not alias a wire identity")
        val keys = frozenProfileKeys(frozen)
        for (profile in frozen.profiles) {
            val key = assertNotNull(keys[profile.wireIdentity], "LAS-002: required typed lookup key")
            val binding = ProtectedLocalBinding(key, Revision(0))
            assertEquals(profile.wireIdentity, binding.table.wireName, "LAS-002: explicit profile identity")
            assertEquals(profile.schema, binding.schemaVersion, "LAS-002: retained schema")
            assertEquals(profile.maximumPlaintextBytes, binding.table.maximumPlaintextBytes,
                "LAS-002: retained profile byte budget")
            assertEquals(frozen.scope("A"), key.scope, "LAS-002: full branch coordinates")
        }
        assertEquals(frozen.envelope.getValue("envelopeFormat").jsonPrimitive.int,
            ProtectedLocalStorageFormat.FORMAT_VERSION)
        assertEquals(frozen.envelope.getValue("algorithm").jsonPrimitive.content,
            ProtectedLocalStorageFormat.ALGORITHM)
        assertEquals(frozen.envelope.getValue("nonceBytes").jsonPrimitive.int,
            ProtectedLocalStorageFormat.NONCE_BYTES)
        assertEquals(frozen.envelope.getValue("tagBytes").jsonPrimitive.int,
            ProtectedLocalStorageFormat.TAG_BYTES)

        val account = ProtectedLocalRecordKey.Throttle(
            ThrottleKey(frozen.scope("A"), frozen.actor("A1"), ThrottleDimension.Account))
        val device = ProtectedLocalRecordKey.Throttle(
            ThrottleKey(frozen.scope("A"), frozen.actor("A1"), ThrottleDimension.Device(frozen.device("D1"))))
        assertEquals("account", account.dimensionKey)
        assertEquals("device:${frozen.device("D1").value}", device.dimensionKey)
        assertEquals(frozen.actor("A1"), account.recordId)
        assertEquals(frozen.actor("A1"), device.recordId)
        assertFalse(account == device, "LAS-002: account and device coordinates remain distinct")
    }

    @Test
    fun las002_emptyAndFrozenOversizedLengthsAreRejectedByPublicGuard() {
        val frozen = FrozenAuthStorageV1Inputs.load()
        val keys = frozenProfileKeys(frozen)
        for (profile in frozen.profiles) {
            val binding = ProtectedLocalBinding(checkNotNull(keys[profile.wireIdentity]), Revision(0))
            assertFails("LAS-002: empty length must be rejected for ${profile.wireIdentity}") {
                binding.requirePlaintextSize(profile.emptyRejectedBytes)
            }
            assertFails("LAS-002: oversized length must be rejected for ${profile.wireIdentity}") {
                binding.requirePlaintextSize(profile.oversizedRejectedBytes)
            }
        }
        // This is length validation only. No payload grammar, valid exact-maximum write,
        // successful-replacement count or truncation observation is inferred from it.
    }

    @Test
    fun las002_publicAadDistinguishesProfileKeyScopeRecordAndThrottleDimension() {
        val frozen = FrozenAuthStorageV1Inputs.load()
        val keys = frozenProfileKeys(frozen)
        val reference = ProtectedStorageKeyReference(frozen.lookupCoordinate(1))
        val changedReference = ProtectedStorageKeyReference(frozen.lookupCoordinate(2))
        val scope = frozen.scope("A")
        val changedScopes = listOf(
            BranchScope(frozen.scope("E").organizationId, scope.companyId, scope.branchId),
            BranchScope(scope.organizationId, frozen.scope("R").companyId, scope.branchId),
            frozen.scope("B"),
        )
        val original = keys.mapValues { (_, key) -> ProtectedLocalBinding(key, Revision(0)).aad(reference) }
        try {
            val profiles = frozen.profiles.map { it.wireIdentity }
            for ((index, profile) in profiles.withIndex()) {
                val key = checkNotNull(keys[profile])
                val aad = checkNotNull(original[profile])
                for (other in profiles.drop(index + 1)) {
                    assertFalse(aad.contentEquals(checkNotNull(original[other])),
                        "LAS-002: distinct profiles must have distinct authenticated coordinates")
                }
                assertDistinctAad(aad, ProtectedLocalBinding(key, Revision(0)), changedReference,
                    "LAS-002: storage-key reference")
                for (changedScope in changedScopes) {
                    assertDistinctAad(aad, ProtectedLocalBinding(keyInScope(key, changedScope), Revision(0)), reference,
                        "LAS-002: organization/company/branch")
                }
                assertDistinctAad(aad, ProtectedLocalBinding(keyWithRecordId(key, frozen.actor("B1")), Revision(0)),
                    reference, "LAS-002: actor/device/session/event/approval lookup coordinate")
            }
            val accountKey = checkNotNull(keys["throttle"]) as ProtectedLocalRecordKey.Throttle
            val deviceKey = ProtectedLocalRecordKey.Throttle(accountKey.key.copy(
                dimension = ThrottleDimension.Device(frozen.device("D1"))))
            assertDistinctAad(checkNotNull(original["throttle"]), ProtectedLocalBinding(deviceKey, Revision(0)),
                reference, "LAS-002: complete throttle dimension")
            val deviceAad = ProtectedLocalBinding(deviceKey, Revision(0)).aad(reference)
            try {
                val otherDevice = ProtectedLocalRecordKey.Throttle(accountKey.key.copy(
                    dimension = ThrottleDimension.Device(frozen.device("D2"))))
                assertDistinctAad(deviceAad, ProtectedLocalBinding(otherDevice, Revision(0)), reference,
                    "LAS-002: distinct admitted-device coordinates")
            } finally { deviceAad.fill(0) }
        } finally { original.values.forEach { it.fill(0) } }
        // Distinct AAD bytes are a necessary formatting property, not evidence that an intact
        // envelope opens, a substituted envelope fails, or a v1 compatibility vector matches.
    }
}

private fun frozenProfileKeys(frozen: FrozenAuthStorageV1Inputs): Map<String, ProtectedLocalRecordKey> {
    val scope = frozen.scope("A")
    val actor = frozen.actor("A1")
    val device = frozen.device("D1")
    // Issuance/event/approval coordinates below are inert typed lookups. No runtime secret or
    // issued identity is frozen, provisioned or asserted by these shared-format tests.
    val coordinate = frozen.lookupCoordinate(0)
    return linkedMapOf(
        "account" to ProtectedLocalRecordKey.Account(scope, actor),
        "effective_policy" to ProtectedLocalRecordKey.EffectivePolicy(scope, actor),
        "admission" to ProtectedLocalRecordKey.Admission(scope, device),
        "throttle" to ProtectedLocalRecordKey.Throttle(ThrottleKey(scope, actor, ThrottleDimension.Account)),
        "local_session_issuance" to ProtectedLocalRecordKey.SessionIssuance(scope, coordinate),
        "local_auth_configuration" to ProtectedLocalRecordKey.AuthConfiguration(scope, actor),
        "local_refresh_issuance" to ProtectedLocalRecordKey.RefreshIssuance(scope, coordinate),
        "local_auth_event" to ProtectedLocalRecordKey.AuthEvent(scope, coordinate),
        "local_session_archive" to ProtectedLocalRecordKey.SessionArchive(scope, coordinate),
        "local_action_approval" to ProtectedLocalRecordKey.ActionApproval(scope, coordinate),
    )
}

private fun keyInScope(key: ProtectedLocalRecordKey, scope: BranchScope): ProtectedLocalRecordKey = when (key) {
    is ProtectedLocalRecordKey.Account -> key.copy(scope = scope)
    is ProtectedLocalRecordKey.EffectivePolicy -> key.copy(scope = scope)
    is ProtectedLocalRecordKey.Admission -> key.copy(scope = scope)
    is ProtectedLocalRecordKey.Throttle -> key.copy(key = key.key.copy(scope = scope))
    is ProtectedLocalRecordKey.SessionIssuance -> key.copy(scope = scope)
    is ProtectedLocalRecordKey.AuthConfiguration -> key.copy(scope = scope)
    is ProtectedLocalRecordKey.RefreshIssuance -> key.copy(scope = scope)
    is ProtectedLocalRecordKey.AuthEvent -> key.copy(scope = scope)
    is ProtectedLocalRecordKey.SessionArchive -> key.copy(scope = scope)
    is ProtectedLocalRecordKey.ActionApproval -> key.copy(scope = scope)
}

private fun keyWithRecordId(key: ProtectedLocalRecordKey, id: DomainId): ProtectedLocalRecordKey = when (key) {
    is ProtectedLocalRecordKey.Account -> key.copy(actorId = id)
    is ProtectedLocalRecordKey.EffectivePolicy -> key.copy(actorId = id)
    is ProtectedLocalRecordKey.Admission -> key.copy(deviceId = id)
    is ProtectedLocalRecordKey.Throttle -> key.copy(key = key.key.copy(actorId = id))
    is ProtectedLocalRecordKey.SessionIssuance -> key.copy(sessionId = id)
    is ProtectedLocalRecordKey.AuthConfiguration -> key.copy(actorId = id)
    is ProtectedLocalRecordKey.RefreshIssuance -> key.copy(sessionId = id)
    is ProtectedLocalRecordKey.AuthEvent -> key.copy(eventId = id)
    is ProtectedLocalRecordKey.SessionArchive -> key.copy(sessionId = id)
    is ProtectedLocalRecordKey.ActionApproval -> key.copy(approvalId = id)
}

private fun assertDistinctAad(original: ByteArray, candidate: ProtectedLocalBinding,
    reference: ProtectedStorageKeyReference, message: String) {
    val changed = candidate.aad(reference)
    try { assertFalse(original.contentEquals(changed), message) }
    finally { changed.fill(0) }
}
