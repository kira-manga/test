package com.ukkera.pos.database.independent.androidlifecycleresumev1

import com.ukkera.pos.database.LocalAuthenticationCodec
import com.ukkera.pos.database.RuntimeMasterLifecycleEvidenceCodec
import com.ukkera.pos.database.RuntimeMasterLifecycleEvidenceV1
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalAuthEvent
import com.ukkera.pos.security.LocalAuthEventKind
import com.ukkera.pos.security.LocalMasterLifecycleAction
import com.ukkera.pos.security.LocalMasterLifecycleAudit
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.ProtectedLocalBinding
import com.ukkera.pos.security.ProtectedLocalRecordKey
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ALR2-S04/S05 over the real production runtime-history lifecycle evidence codec and the
 * real protected auth-event codec. No native history row, scanner admission or Android run
 * is claimed; the evidence bytes are plainly encoded only inside this test.
 */
internal class RuntimeMasterLifecycleEvidenceV2Test {
    @Test
    fun exactEvidenceRoundTripsAndReconstructsTheCanonicalLifecycleEvent() {
        val evidence = evidence()
        val bytes = RuntimeMasterLifecycleEvidenceCodec.encode(evidence)
        val text = bytes.decodeToString()
        assertTrue(text.contains("ukkera.runtime-master-lifecycle-evidence/v1"),
            "ALR2-S04 separate evidence profile")
        assertTrue(text.contains("\"sessionGeneration\":\"5\""),
            "ALR2-S04 canonical generation scalar")
        assertTrue(text.contains("\"admissionRevision\":\"6\""),
            "ALR2-S04 canonical admission scalar")
        assertTrue(RuntimeMasterLifecycleEvidenceCodec.hasProfile(bytes),
            "ALR2-S04 profile detection")

        val decoded = RuntimeMasterLifecycleEvidenceCodec.decode(bytes)
        assertEquals(evidence.scope, decoded.scope, "ALR2-S04 scope")
        assertEquals(evidence.eventId, decoded.eventId, "ALR2-S04 event ID")
        assertEquals(evidence.actorId, decoded.actorId, "ALR2-S04 actor")
        assertEquals(evidence.sessionId, decoded.sessionId, "ALR2-S04 session")
        assertEquals(evidence.accountRevision, decoded.accountRevision, "ALR2-S04 account revision")
        assertEquals(LocalMasterLifecycleAction.STOP, decoded.action, "ALR2-S04 action")
        assertEquals(evidence.installationId, decoded.installationId, "ALR2-S04 installation")
        assertEquals(evidence.coordinatesDigest, decoded.coordinatesDigest, "ALR2-S04 coordinates digest")
        assertEquals(evidence.requestDigest, decoded.requestDigest, "ALR2-S04 request digest")
        assertEquals(evidence.policyRevision, decoded.policyRevision, "ALR2-S04 policy revision")
        assertEquals(evidence.deviceId, decoded.deviceId, "ALR2-S04 device")
        assertEquals(evidence.sessionGeneration, decoded.sessionGeneration, "ALR2-S04 generation")
        assertEquals(evidence.admissionRevision, decoded.admissionRevision, "ALR2-S04 admission")
        assertEquals(evidence.issuerContextId, decoded.issuerContextId, "ALR2-S04 issuer")
        assertEquals(evidence.at, decoded.at, "ALR2-S04 observation")
        assertContentEquals(bytes, RuntimeMasterLifecycleEvidenceCodec.encode(decoded),
            "ALR2-S04 canonical re-encode")

        val event = decoded.event()
        assertEquals(evidence.scope, event.scope, "ALR2-S04 event scope")
        assertEquals(evidence.eventId, event.eventId, "ALR2-S04 event ID")
        assertEquals(LocalAuthEventKind.MASTER_LIFECYCLE, event.kind, "ALR2-S04 event kind")
        assertEquals(evidence.installationId, event.targetId, "ALR2-S04 event installation")
        assertEquals(evidence.actorId, event.operatorId, "ALR2-S04 event actor")
        assertEquals(evidence.sessionId, event.sessionId, "ALR2-S04 event session")
        assertEquals(evidence.accountRevision, event.accountRevision, "ALR2-S04 event account revision")
        assertEquals(evidence.requestDigest, event.contentDigest, "ALR2-S04 event content digest")
        val audit = checkNotNull(event.lifecycle)
        assertEquals(evidence.action, audit.action, "ALR2-S04 audit action")
        assertEquals(evidence.installationId, audit.installationId, "ALR2-S04 audit installation")
        assertEquals(evidence.coordinatesDigest, audit.coordinatesDigest, "ALR2-S04 audit coordinates")
        assertEquals(evidence.requestDigest, audit.requestDigest, "ALR2-S04 audit request")
        assertEquals(evidence.policyRevision, audit.policyRevision, "ALR2-S04 audit policy")
        assertEquals(evidence.deviceId, audit.deviceId, "ALR2-S04 audit device")
        assertEquals(evidence.sessionGeneration, audit.sessionGeneration, "ALR2-S04 audit generation")
        assertEquals(evidence.admissionRevision, audit.admissionRevision, "ALR2-S04 audit admission")
        assertEquals(evidence.issuerContextId, audit.issuerContextId, "ALR2-S04 audit issuer")
        assertTrue(decoded.matchesEvent(event), "ALR2-S04 exact event match")
    }

    @Test
    fun everyBoundEvidenceFieldChangesTheExactEventMatch() {
        val base = evidence()
        val event = base.event()
        val changed = linkedMapOf(
            "scope organization" to base.copy(scope = base.scope.copy(organizationId = changed(2102))),
            "scope company" to base.copy(scope = base.scope.copy(companyId = changed(2103))),
            "scope branch" to base.copy(scope = base.scope.copy(branchId = changed(2104))),
            "event ID" to base.copy(eventId = changed(2030)),
            "actor" to base.copy(actorId = changed(2031)),
            "session" to base.copy(sessionId = changed(2032)),
            "account revision" to base.copy(accountRevision = Revision(8)),
            "action" to base.copy(action = LocalMasterLifecycleAction.RESUME),
            "installation" to base.copy(installationId = changed(2033)),
            "coordinates digest" to base.copy(coordinatesDigest = PayloadDigest("c".repeat(64))),
            "request digest" to base.copy(requestDigest = PayloadDigest("d".repeat(64))),
            "policy revision" to base.copy(policyRevision = Revision(9)),
            "device" to base.copy(deviceId = changed(2034)),
            "generation" to base.copy(sessionGeneration = SessionGeneration(6)),
            "admission revision" to base.copy(admissionRevision = Revision(10)),
            "issuer" to base.copy(issuerContextId = changed(2035)),
            "boot" to base.copy(at = MonotonicMoment(changed(2091), 1500)),
            "elapsed" to base.copy(at = MonotonicMoment(base.at.bootId, 1501)),
        )
        for ((name, value) in changed) {
            assertFalse(value.matchesEvent(event), "ALR2-S04 $name must not match the original event")
        }
    }

    @Test
    fun codecRefusesForeignNonCanonicalUnknownOversizeAndInvalidEvidence() {
        val bytes = RuntimeMasterLifecycleEvidenceCodec.encode(evidence())
        val text = bytes.decodeToString()
        val cases = linkedMapOf<String, ByteArray>(
            "empty" to ByteArray(0),
            "oversize" to ByteArray(RuntimeMasterLifecycleEvidenceV1.MAXIMUM_BYTES + 1) { 'x'.code.toByte() },
            "foreign profile" to text.replace("ukkera.runtime-master-lifecycle-evidence/v1",
                "ukkera.runtime-master-lifecycle-evidence/v2").encodeToByteArray(),
            "non-canonical whitespace" to text.replaceFirst("{\"schema\"", "{ \"schema\"").encodeToByteArray(),
            "unknown field" to text.replaceFirst("{\"schema\"", "{\"extra\":0,\"schema\"").encodeToByteArray(),
            "wrong scalar type" to text.replace("\"accountRevision\":\"4\"", "\"accountRevision\":4").encodeToByteArray(),
            "truncated" to bytes.copyOf(bytes.size - 1),
        )
        for ((name, candidate) in cases) {
            assertFailsWith<Throwable>("ALR2-S04 $name must be refused") {
                RuntimeMasterLifecycleEvidenceCodec.decode(candidate)
            }
        }
        for (name in listOf("empty", "oversize", "foreign profile", "unknown field",
            "wrong scalar type", "truncated")) {
            assertFalse(RuntimeMasterLifecycleEvidenceCodec.hasProfile(checkNotNull(cases[name])),
                "ALR2-S04 $name must not be detected as the profile")
        }
        // Profile detection is a bounded prefilter; canonical decoding remains the rejecting
        // boundary for whitespace/non-canonical JSON.
        assertTrue(RuntimeMasterLifecycleEvidenceCodec.hasProfile(checkNotNull(cases["non-canonical whitespace"])),
            "ALR2-S04 profile prefilter does not claim canonicality")
        assertFailsWith<Throwable>("ALR2-S04 invalid schema cannot be encoded") {
            RuntimeMasterLifecycleEvidenceCodec.encode(evidence().copy(schema = "foreign"))
        }
    }

    @Test
    fun legacyFiveArgumentAuditStaysDataOnlyAndNeverMatchesFullHistoryEvidence() {
        val evidence = evidence()
        val legacy = LocalAuthEvent(
            evidence.scope, evidence.eventId, LocalAuthEventKind.MASTER_LIFECYCLE,
            evidence.installationId, evidence.actorId, evidence.sessionId, evidence.accountRevision, evidence.at,
            evidence.requestDigest,
            lifecycle = LocalMasterLifecycleAudit(evidence.action, evidence.installationId,
                evidence.coordinatesDigest, evidence.requestDigest, evidence.policyRevision),
        )
        val legacyAudit = checkNotNull(legacy.lifecycle)
        assertNull(legacyAudit.deviceId, "ALR2-S05 legacy device is data-null")
        assertNull(legacyAudit.sessionGeneration, "ALR2-S05 legacy generation is data-null")
        assertNull(legacyAudit.admissionRevision, "ALR2-S05 legacy admission is data-null")
        assertNull(legacyAudit.issuerContextId, "ALR2-S05 legacy issuer is data-null")

        val binding = ProtectedLocalBinding(
            ProtectedLocalRecordKey.AuthEvent(legacy.scope, legacy.eventId), Revision(0))
        val bytes = LocalAuthenticationCodec.encodeEvent(legacy)
        val decoded = LocalAuthenticationCodec.decodeEvent(binding, bytes)
        assertNull(checkNotNull(decoded.lifecycle).deviceId, "ALR2-S05 decoded legacy device")
        assertContentEquals(bytes, LocalAuthenticationCodec.encodeEvent(decoded),
            "ALR2-S05 legacy data round-trip")
        assertFalse(evidence.matchesEvent(decoded),
            "ALR2-S05 a legacy null-field event must not match full history evidence")

        val fullBytes = LocalAuthenticationCodec.encodeEvent(evidence.event())
        assertFalse(fullBytes.contentEquals(bytes),
            "ALR2-S05 full evidence event differs from legacy null-field data")
    }

    private fun evidence() = RuntimeMasterLifecycleEvidenceV1(
        scope = BranchScope(changed(2002), changed(2003), changed(2004)),
        eventId = changed(2013),
        actorId = changed(2012),
        sessionId = changed(2020),
        accountRevision = Revision(4),
        action = LocalMasterLifecycleAction.STOP,
        installationId = changed(2005),
        coordinatesDigest = COORDINATES_DIGEST,
        requestDigest = REQUEST_DIGEST,
        policyRevision = Revision(7),
        deviceId = changed(2007),
        sessionGeneration = SessionGeneration(5),
        admissionRevision = Revision(6),
        issuerContextId = changed(2021),
        at = MonotonicMoment(changed(2090), 1500),
    )

    private fun changed(value: Int): DomainId =
        DomainId("00000000-0000-4000-8000-${value.toString().padStart(12, '0')}")

    private companion object {
        private val COORDINATES_DIGEST = PayloadDigest("e7d8d13b8c25557502d84e6d8dfead64f51ab6725154461cbe7bc0c98e03a174")
        private val REQUEST_DIGEST = PayloadDigest("39cff46dc003253e5b2d73c68ebb102e17ee0265f769876dad889a11508c2121")
    }
}
