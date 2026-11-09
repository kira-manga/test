package com.ukkera.pos.database.independent.androidlifecycleresumev1

import com.ukkera.pos.database.LocalAuthenticationCodec
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalAuthEvent
import com.ukkera.pos.security.LocalAuthEventKind
import com.ukkera.pos.security.LocalMasterLifecycleAction
import com.ukkera.pos.security.LocalMasterLifecycleAudit
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.ProtectedLocalBinding
import com.ukkera.pos.security.ProtectedLocalRecordKey
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Supplemental ALR-S05 expectations over the real production protected event codec. The bytes are
 * plainly encoded only inside this test; the production store seals them through its injected
 * protected-storage provider. This is not a Room row, native history scan or device result.
 */
internal class LocalMasterLifecycleEventCodecTest {
    @Test
    fun separatelyVersionedLifecycleEventRoundTripsAllImmutableFields() {
        val event = event()
        val binding = binding(event)
        val bytes = LocalAuthenticationCodec.encodeEvent(event)
        val text = bytes.decodeToString()
        assertTrue(text.contains("ukkera.local-master-lifecycle-event/v1"), "ALR-S05 separate profile")
        val decoded = LocalAuthenticationCodec.decodeEvent(binding, bytes)
        assertEquals(event.scope, decoded.scope, "ALR-S05 scope")
        assertEquals(event.eventId, decoded.eventId, "ALR-S05 event ID")
        assertEquals(LocalAuthEventKind.MASTER_LIFECYCLE, decoded.kind, "ALR-S05 kind")
        assertEquals(event.targetId, decoded.targetId, "ALR-S05 installation target")
        assertEquals(event.operatorId, decoded.operatorId, "ALR-S05 actor")
        assertEquals(event.sessionId, decoded.sessionId, "ALR-S05 session")
        assertEquals(event.accountRevision, decoded.accountRevision, "ALR-S05 account revision")
        assertEquals(event.at, decoded.at, "ALR-S05 observation")
        assertEquals(event.contentDigest, decoded.contentDigest, "ALR-S05 content digest")
        val audit = checkNotNull(decoded.lifecycle)
        assertEquals(LocalMasterLifecycleAction.STOP, audit.action, "ALR-S05 action")
        assertEquals(event.targetId, audit.installationId, "ALR-S05 audit installation")
        assertEquals(COORDINATES_DIGEST, audit.coordinatesDigest, "ALR-S05 coordinates digest")
        assertEquals(REQUEST_DIGEST, audit.requestDigest, "ALR-S05 request digest")
        assertEquals(Revision(7), audit.policyRevision, "ALR-S05 policy revision")
        assertContentEquals(bytes, LocalAuthenticationCodec.encodeEvent(decoded),
            "ALR-S05 canonical re-encode")
    }

    @Test
    fun wrongBindingWrongProfileAndNonCanonicalBytesAreRefused() {
        val event = event()
        val bytes = LocalAuthenticationCodec.encodeEvent(event)
        assertFailsWith<Throwable>("ALR-S05 wrong record key") {
            LocalAuthenticationCodec.decodeEvent(
                ProtectedLocalBinding(ProtectedLocalRecordKey.AuthEvent(event.scope, changed(99)), Revision(0)), bytes)
        }
        assertFailsWith<Throwable>("ALR-S05 wrong revision") {
            LocalAuthenticationCodec.decodeEvent(
                ProtectedLocalBinding(ProtectedLocalRecordKey.AuthEvent(event.scope, event.eventId), Revision(1)), bytes)
        }
        val wrongProfile = bytes.decodeToString().replace(
            "ukkera.local-master-lifecycle-event/v1", "ukkera.local-master-lifecycle-event/v2").encodeToByteArray()
        assertFailsWith<Throwable>("ALR-S05 wrong profile") {
            LocalAuthenticationCodec.decodeEvent(binding(event), wrongProfile)
        }
        val nonCanonical = bytes.decodeToString().replace("{\"schema\":", "{ \"schema\":").encodeToByteArray()
        assertFailsWith<Throwable>("ALR-S05 non-canonical JSON") {
            LocalAuthenticationCodec.decodeEvent(binding(event), nonCanonical)
        }
    }

    @Test
    fun lifecycleAuditCannotBeAttachedToAnotherEventKindOrMissItsInstallation() {
        val base = event()
        assertFailsWith<Throwable>("ALR-S05 lifecycle without audit") {
            LocalAuthEvent(base.scope, base.eventId, LocalAuthEventKind.MASTER_LIFECYCLE,
                base.targetId, base.operatorId, base.sessionId, base.accountRevision, base.at, base.contentDigest)
        }
        assertFailsWith<Throwable>("ALR-S05 audit on ordinary event") {
            LocalAuthEvent(base.scope, base.eventId, LocalAuthEventKind.SIGNED_IN,
                base.targetId, base.operatorId, base.sessionId, base.accountRevision, base.at, null,
                lifecycle = base.lifecycle)
        }
        assertFailsWith<Throwable>("ALR-S05 audit target mismatch") {
            LocalAuthEvent(base.scope, base.eventId, LocalAuthEventKind.MASTER_LIFECYCLE,
                changed(98), base.operatorId, base.sessionId, base.accountRevision, base.at, base.contentDigest,
                lifecycle = LocalMasterLifecycleAudit(LocalMasterLifecycleAction.STOP, base.targetId,
                    COORDINATES_DIGEST, REQUEST_DIGEST, Revision(7)))
        }
        assertFailsWith<Throwable>("ALR-S05 audit request mismatch") {
            LocalAuthEvent(base.scope, base.eventId, LocalAuthEventKind.MASTER_LIFECYCLE,
                base.targetId, base.operatorId, base.sessionId, base.accountRevision, base.at, base.contentDigest,
                lifecycle = LocalMasterLifecycleAudit(LocalMasterLifecycleAction.STOP, base.targetId,
                    COORDINATES_DIGEST, PayloadDigest("c".repeat(64)), Revision(7)))
        }
    }

    private fun event(): LocalAuthEvent = LocalAuthEvent(
        BranchScope(changed(2), changed(3), changed(4)), changed(20), LocalAuthEventKind.MASTER_LIFECYCLE,
        changed(5), changed(12), changed(13), Revision(4), MonotonicMoment(changed(90), 1500),
        REQUEST_DIGEST, lifecycle = LocalMasterLifecycleAudit(LocalMasterLifecycleAction.STOP,
            changed(5), COORDINATES_DIGEST, REQUEST_DIGEST, Revision(7)),
    )

    private fun binding(value: LocalAuthEvent) = ProtectedLocalBinding(
        ProtectedLocalRecordKey.AuthEvent(value.scope, value.eventId), Revision(0))

    private fun changed(value: Int): DomainId =
        DomainId("00000000-0000-4000-8000-${value.toString().padStart(12, '0')}")

    private companion object {
        private val COORDINATES_DIGEST = PayloadDigest("a".repeat(64))
        private val REQUEST_DIGEST = PayloadDigest("b".repeat(64))
    }
}
