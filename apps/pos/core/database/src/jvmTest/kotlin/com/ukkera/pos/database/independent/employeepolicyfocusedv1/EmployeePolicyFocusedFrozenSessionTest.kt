package com.ukkera.pos.database.independent.employeepolicyfocusedv1

import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.LocalAccountSessionState
import com.ukkera.pos.security.LocalSessions
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.SessionStatus
import com.ukkera.pos.security.SessionTransition
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Direct production LocalSessions reducers, with frozen session/device inputs. No actual
 * credential verification, device admission, stored issuance, cap activation, survivor choice,
 * cash custody, token rotation, deadline or current authorization is established.
 */
class EmployeePolicyFocusedFrozenSessionTest {
    private val fixture = EmployeePolicyFocusedFrozenFixtures

    @Test
    fun epf03_configuredCapThreeAdmitsTheThreeFrozenDistinctPureSessionInputs() {
        val state = threeSessionInputs()
        assertEquals(3, state.sessions.count { it.status == SessionStatus.ACTIVE })
        assertEquals(fixture.originalSessionIds, state.sessions.map { it.sessionId })
        assertEquals(fixture.devices, state.sessions.map { it.deviceId })
        assertTrue(state.sessions.all {
            it.actorId == fixture.subject && it.scope == fixture.scopeA &&
                it.authenticationPurpose == AuthenticationPurpose.INTERACTIVE
        })
        // This is only the pure cap 3 input construction, not three genuinely issued lineages.
    }

    @Test
    fun epf03_configuredCapOneCannotAddASecondIndependentPureSession() {
        val cap = LocalSessionPolicy(fixture.subject12.sessionCap)
        val first = assertIs<SessionTransition.Applied>(LocalSessions.signIn(
            emptyInput(), fixture.originalSessionIds[0], fixture.devices[0], cap,
        )).state
        assertIs<SessionTransition.Denied>(LocalSessions.signIn(
            first, fixture.originalSessionIds[1], fixture.devices[1], cap,
        ))
        assertEquals(1, first.sessions.count { it.status == SessionStatus.ACTIVE })
        assertEquals(listOf(fixture.originalSessionIds[0]), first.sessions.map { it.sessionId })
        // The cap 1 reducer input was built independently; no survivor was chosen from the cap 3
        // fixture, and no claim is made that policy activation reduced a persisted count 3 to 1.
    }

    @Test
    fun epf06_explicitDisableReducerLeavesNoActiveSubjectSessionAndNoSignIn() {
        val disabled = assertIs<SessionTransition.Applied>(LocalSessions.disable(threeSessionInputs())).state
        assertFalse(disabled.enabled)
        assertEquals(0, disabled.sessions.count { it.status == SessionStatus.ACTIVE })
        assertEquals(fixture.originalSessionIds, disabled.sessions.map { it.sessionId })
        assertEquals(fixture.devices, disabled.sessions.map { it.deviceId })
        assertIs<SessionTransition.Denied>(LocalSessions.signIn(disabled,
            fixture.originalSessionIds[0], fixture.devices[0], LocalSessionPolicy(1)))
        // This does not establish who may disable, local commit, or stale central non-reenable.
    }

    private fun emptyInput() = LocalAccountSessionState(
        fixture.scopeA, fixture.subject, true, Revision(0), SessionGeneration(0), emptyList(),
    )

    private fun threeSessionInputs(): LocalAccountSessionState {
        var state = emptyInput()
        for (index in fixture.originalSessionIds.indices) {
            state = assertIs<SessionTransition.Applied>(LocalSessions.signIn(state,
                fixture.originalSessionIds[index], fixture.devices[index],
                LocalSessionPolicy(fixture.subject11.sessionCap))).state
        }
        return state
    }
}
