package com.ukkera.pos.app.gateway.independent.applicationauthenticationattachment

import com.ukkera.pos.app.gateway.LocalClientAuthenticationBinding
import com.ukkera.pos.app.gateway.StoredClientCredentials
import com.ukkera.pos.app.gateway.StoredLoginAttemptV1
import com.ukkera.pos.app.gateway.credentialBindingV3
import com.ukkera.pos.app.gateway.credentialIdV3
import com.ukkera.pos.app.gateway.credentialTagV3
import com.ukkera.pos.app.gateway.setup.LocalSetupLanSettings
import com.ukkera.pos.app.setup.LocalSetupLanInput
import com.ukkera.pos.security.LocalPairedDeviceCredentialJournalAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Supporting semantic binding/profile and immutable disposition DATA only. No native credential
 * owner, enrollment/recovery, protected encrypted journal, raw target transfer, approved-Master
 * capability or coordinator-positive publication/cleanup is manufactured or qualified.
 */
class ApplicationAuthenticationBindingAndDispositionSupportingTest {
    @Test
    fun appAuth002LocalCorrelationNeverBecomesHistoricalMasterIncarnationByScalarEquality() {
        val id = AppAuthenticationFrozen.connectionId
        val remote = LocalClientAuthenticationBinding.RemoteOriginalAttachment(id)
        val historical = LocalClientAuthenticationBinding.MasterServiceIncarnation(id)
        assertNotEquals<LocalClientAuthenticationBinding>(remote, historical)
        assertEquals(remote, credentialBindingV3(remote.credentialTagV3(), remote.credentialIdV3()))
        assertEquals(historical, credentialBindingV3(historical.credentialTagV3(), historical.credentialIdV3()))
    }

    @Test
    fun appAuth003RemoteDispatchRecordCannotExposeCorrelationAsMasterIncarnation() {
        val remote = LocalClientAuthenticationBinding.RemoteOriginalAttachment(AppAuthenticationFrozen.connectionId)
        val attempt = StoredLoginAttemptV1(AppAuthenticationFrozen.exchange("LOGIN_1"), AppAuthenticationFrozen.login, remote, false)
        assertEquals(remote, attempt.binding)
        assertEquals(AppAuthenticationFrozen.login, attempt.login)
        assertFails { attempt.serviceIncarnationId }
        // LOGIN_1 here is a model DATA coordinate only, not actual gateway/RPC correlation evidence.
    }

    @Test
    fun appAuth002And030AddingRemoteBindingPreservesExistingHistoricalBindingSemantics() {
        val old = StoredLoginAttemptV1(AppAuthenticationFrozen.exchange("LOGIN_1"), AppAuthenticationFrozen.login,
            AppAuthenticationFrozen.slotId("COHOST_D0_OWNER:historical-incarnation"), false)
        val remote = StoredLoginAttemptV1(AppAuthenticationFrozen.exchange("LOGIN_RELOGIN"), AppAuthenticationFrozen.login,
            LocalClientAuthenticationBinding.RemoteOriginalAttachment(AppAuthenticationFrozen.connectionId), false)
        val historical = StoredClientCredentials(attempts = listOf(old))
        val next = historical.next(attempts = historical.attempts + remote)
        try {
            next.validate(AppAuthenticationFrozen.owner)
            assertEquals(old.binding, next.attempts.first().binding)
            assertEquals(old.serviceIncarnationId, next.attempts.first().serviceIncarnationId)
            assertEquals(remote.binding, next.attempts.last().binding)
            assertFails { next.attempts.last().serviceIncarnationId }
        } finally { next.destroy(); historical.destroy() }
        // Actual v1/v2/v3 ciphertext/binary compatibility and genuine existing-owner reopen are unbound.
    }

    @Test
    fun appAuth028AbsentDispositionRemainsAbsentInsteadOfSilentlySelectingEnrollment() {
        val settings = LocalSetupLanSettings.from(input(null))
        assertEquals(null, settings.credentialAction)
        for (action in LocalPairedDeviceCredentialJournalAction.entries)
            assertFalse(settings.sameSelection(LocalSetupLanSettings.from(input(action))))
        // This is the actual selection mapper only; NONE's native transport-only path is not run.
    }

    @Test
    fun appAuth028SameChoiceRemainsEquivalentAndConflictingChoiceCannotRetargetSelection() {
        for (chosen in LocalPairedDeviceCredentialJournalAction.entries) {
            val retained = LocalSetupLanSettings.from(input(chosen))
            assertEquals(chosen, retained.credentialAction)
            assertTrue(retained.sameSelection(LocalSetupLanSettings.from(input(chosen))))
            for (conflict in LocalPairedDeviceCredentialJournalAction.entries.filterNot { it == chosen })
                assertFalse(retained.sameSelection(LocalSetupLanSettings.from(input(conflict))))
            assertEquals(chosen, retained.credentialAction)
        }
        // No same-choice native retry, ENROLL pristine proof, RECOVER fence or late transfer occurs.
    }

    @Test
    fun appAuth028RetainedDispositionSettingsDoNotFollowCallerSubnetMutation() {
        val caller = mutableListOf("192.168.44.0/24")
        val selectedInput = LocalSetupLanInput(caller, "test0", "192.168.44.10", 0, LocalPairedDeviceCredentialJournalAction.REOPEN)
        val retained = LocalSetupLanSettings.from(selectedInput)
        caller.clear()
        assertEquals(LocalPairedDeviceCredentialJournalAction.REOPEN, retained.credentialAction)
        assertTrue(retained.sameSelection(LocalSetupLanSettings.from(input(LocalPairedDeviceCredentialJournalAction.REOPEN))))
    }

    private fun input(action: LocalPairedDeviceCredentialJournalAction?): LocalSetupLanInput =
        LocalSetupLanInput(listOf("192.168.44.0/24"), "test0", "192.168.44.10", 0, action)
    // Numeric subnet/interface fields are explicit parser DATA, not an observed native LAN/OS.
}
