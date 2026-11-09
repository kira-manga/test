package com.ukkera.pos.security.independent.localserialregistration

import com.ukkera.pos.security.CompanyBusinessPublicationKind
import com.ukkera.pos.security.LocalPublishedSerialRegistrationV1
import com.ukkera.pos.security.LocalSerialAuthorityPublicationCodecV1
import com.ukkera.pos.security.LocalSerialRegistrationPublicationCodecV1
import com.ukkera.pos.security.sameSerialRegistrationIdentity
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Frozen content/input assertions only; every native and Master-time clause stays UNBOUND. */
class LocalSerialRegistrationContentSlicesTest {
    private val input = LocalSerialRegistrationInertInputs

    @Test
    fun inertPublicationCodecPreservesTheExactRequestedIdentityAndCode() {
        // LSR-06.g: serialization slice only, not private persistence, UI or native completion.
        val before = input.historyThroughDesiredRevisionThree().last()
        val decoded = roundTrip(before)
        assertEquals(input.registrationId, decoded.registrationId)
        assertEquals(input.serialId, decoded.serialId)
        assertEquals("000AbC-07", decoded.serialCode)
        assertEquals(input.skuId, decoded.skuId)
        assertEquals(before.scope, decoded.scope)
        assertEquals(before.serialAuthority, decoded.serialAuthority)
        assertEquals(before.previous, decoded.previous)
    }

    @Test
    fun boundedAndOpenIntervalInputsRoundTripWithoutBecomingAnAuthoritativeClock() {
        // LSR-08.a-f: exact interval INPUTS only. No evaluate([from, until)) stand-in.
        val value = input.historyThroughDesiredRevisionThree().last()
        val bounded = roundTrip(value)
        assertEquals("2031-04-05T12:00:00Z", bounded.validFrom.canonicalString())
        assertEquals("2031-04-05T13:00:00Z", bounded.validUntil?.canonicalString())
        val open = roundTrip(value.copy(validUntil = null))
        assertEquals("2031-04-05T12:00:00Z", open.validFrom.canonicalString())
        assertNull(open.validUntil)
    }

    @Test
    fun successorContentRequiresRevisionFourAndAFreshPublicationForPredecessorThree() {
        // LSR-07.c/f: declared 3 + 1 = 4 and fresh publication syntax; no native successor.
        val before = input.historyThroughDesiredRevisionThree().last()
        assertEquals(Revision(3), before.revision)
        val request = input.successor(before)
        assertEquals(Revision(4), roundTrip(request).revision)
        assertEquals(before.registrationId, request.registrationId)
        assertNotEquals(before.publicationId, request.publicationId)
        assertEquals(LocalSerialRegistrationPublicationCodecV1.reference(before), request.previous)
        for (attempted in listOf(Revision(3), Revision(5))) {
            assertFails("LSR-07.f: attempted revision ${attempted.value}") { request.copy(revision = attempted) }
        }
        assertFails("LSR-07.c: predecessor publication reuse") { request.copy(publicationId = before.publicationId) }
    }

    @Test
    fun changedStableRegistrationCannotUseTheOldPredecessorCoordinates() {
        // LSR-06.a / LSR-07: a changed registration cannot become this identity's successor.
        val request = input.successor(input.historyThroughDesiredRevisionThree().last())
        assertFails { request.copy(registrationId = input.freshRegistrationId) }
        assertEquals(input.registrationId, request.registrationId)
        assertEquals(input.registrationId, request.previous?.registrationId)
    }

    @Test
    fun caseZeroSerialSkuAndNamespaceChangesAreNotTheSamePermanentIdentity() {
        // LSR-06.b-e: identity comparison only; it supplies no genuine original membership.
        val before = input.historyThroughDesiredRevisionThree().last()
        val changed = listOf(
            before.copy(serialId = input.freshSerialId),
            before.copy(serialCode = "000abc-07"),
            before.copy(serialCode = "0AbC-07"),
            before.copy(skuId = input.otherSkuId),
            before.copy(serialAuthority = before.serialAuthority.copy(contentDigest = input.differentInertDigest)),
        )
        for (candidate in changed) assertFalse(sameSerialRegistrationIdentity(before, candidate))
        assertEquals("000AbC-07", before.serialCode)
        assertEquals(input.serialId, before.serialId)
        assertEquals(input.skuId, before.skuId)
    }

    @Test
    fun oldContentCoordinatesDoNotMatchEditedPermanentPredecessorOrIntervalInputs() {
        // LSR-12.a-f: content-reference separation, not an old-proof native activation test.
        val before = input.historyThroughDesiredRevisionThree().last()
        val oldReference = LocalSerialRegistrationPublicationCodecV1.reference(before)
        val changed = listOf(
            before.copy(serialCode = input.caseChangedCode),
            before.copy(serialId = input.freshSerialId),
            before.copy(skuId = input.otherSkuId),
            before.copy(serialAuthority = before.serialAuthority.copy(contentDigest = input.differentInertDigest)),
            before.copy(publicationId = input.successorPublicationId),
            before.copy(previous = requireNotNull(before.previous).copy(contentDigest = input.differentInertDigest)),
            before.copy(validFrom = input.beforeFrom),
            before.copy(validUntil = input.afterUntil),
            before.copy(skuPublicationId = input.laterSkuPublicationId),
        )
        for (candidate in changed) assertNotEquals(oldReference, LocalSerialRegistrationPublicationCodecV1.reference(candidate))
        assertEquals(oldReference, LocalSerialRegistrationPublicationCodecV1.reference(before))
    }

    @Test
    fun numericCodeDoesNotDecodeAsAnOriginalString() {
        // Exact LSR-21.a code_json_value, without selecting an error subtype.
        val value = input.historyThroughDesiredRevisionThree().last()
        val bytes = changedPublicationBytes(value) { this["serialCode"] = JsonPrimitive(12345) }
        try { assertFails { LocalSerialRegistrationPublicationCodecV1.decodeCanonicalPublication(bytes) } }
        finally { bytes.fill(0) }
        assertEquals("000AbC-07", value.serialCode)
    }

    @Test
    fun frozenFractionalRevisionAndMalformedSerialIdFailWithoutInvalidityPrecedence() {
        // Exact combined LSR-21.b inputs; no claim about which invalid field wins.
        val value = input.historyThroughDesiredRevisionThree().last()
        val bytes = changedPublicationBytes(value) {
            this["revision"] = JsonPrimitive("3.5")
            this["serialId"] = JsonPrimitive("NOT_A_VALID_DOMAIN_ID")
        }
        try { assertFails { LocalSerialRegistrationPublicationCodecV1.decodeCanonicalPublication(bytes) } }
        finally { bytes.fill(0) }
        assertEquals(Revision(3), value.revision)
        assertEquals(input.serialId, value.serialId)
    }

    @Test
    fun missingPermanentAndNamespaceFieldsAreNotAcceptingDefaults() {
        // LSR-21.d: serialAuthority is the content-coordinate mapping, not native namespace proof.
        val value = input.historyThroughDesiredRevisionThree().last()
        val bytes = changedPublicationBytes(value) {
            this["registrationId"] = JsonNull
            this["serialId"] = JsonNull
            this["serialAuthority"] = JsonNull
        }
        try { assertFails { LocalSerialRegistrationPublicationCodecV1.decodeCanonicalPublication(bytes) } }
        finally { bytes.fill(0) }
        assertEquals(input.registrationId, value.registrationId)
        assertEquals(input.serialId, value.serialId)
    }

    @Test
    fun emptyRequiredCodeIsRefusedWithoutClaimingQuantityOrCardinalityEnforcement() {
        // LSR-04.g: only the exact empty-code field, not the quantity/receiving boundary.
        val value = input.historyThroughDesiredRevisionThree().last()
        assertFails { value.copy(serialCode = "") }
        assertEquals("000AbC-07", value.serialCode)
    }

    @Test
    fun truncatedRegistrationAndAuthorityContentDoNotDecodeAsOriginalDefaults() {
        // LSR-19.a/c / LSR-21.e: invalid-frame slice; no guessed size/depth limits.
        val registration = input.historyThroughDesiredRevisionThree().last()
        val authority = input.authority()
        val registrationBytes = LocalSerialRegistrationPublicationCodecV1.canonicalPublication(registration)
        val authorityBytes = LocalSerialAuthorityPublicationCodecV1.canonicalPublication(authority)
        val brokenRegistration = registrationBytes.copyOf(registrationBytes.size - 1)
        val brokenAuthority = authorityBytes.copyOf(authorityBytes.size - 1)
        try {
            assertFails { LocalSerialRegistrationPublicationCodecV1.decodeCanonicalPublication(brokenRegistration) }
            assertFails { LocalSerialAuthorityPublicationCodecV1.decodeCanonicalPublication(brokenAuthority) }
            val retainedRegistration = LocalSerialRegistrationPublicationCodecV1.canonicalPublication(registration)
            val retainedAuthority = LocalSerialAuthorityPublicationCodecV1.canonicalPublication(authority)
            try {
                assertContentEquals(registrationBytes, retainedRegistration)
                assertContentEquals(authorityBytes, retainedAuthority)
            } finally { retainedRegistration.fill(0); retainedAuthority.fill(0) }
        } finally {
            registrationBytes.fill(0); authorityBytes.fill(0)
            brokenRegistration.fill(0); brokenAuthority.fill(0)
        }
    }

    private fun roundTrip(value: LocalPublishedSerialRegistrationV1): LocalPublishedSerialRegistrationV1 {
        val bytes = LocalSerialRegistrationPublicationCodecV1.canonicalPublication(value)
        return try { LocalSerialRegistrationPublicationCodecV1.decodeCanonicalPublication(bytes) }
        finally { bytes.fill(0) }
    }

    private fun changedPublicationBytes(
        value: LocalPublishedSerialRegistrationV1,
        change: MutableMap<String, JsonElement>.() -> Unit,
    ): ByteArray {
        val bytes = LocalSerialRegistrationPublicationCodecV1.canonicalPublication(value)
        val prefix = CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1.domain
        return try {
            val text = bytes.decodeToString()
            val fields = Json.parseToJsonElement(text.removePrefix(prefix)).jsonObject.toMutableMap()
            // A no-op conversion is byte-identical: refusals are not incidental harness formatting.
            val unchanged = (prefix + JsonObject(fields).toString()).encodeToByteArray()
            try { assertContentEquals(bytes, unchanged) } finally { unchanged.fill(0) }
            fields.change()
            (prefix + JsonObject(fields).toString()).encodeToByteArray()
        } finally { bytes.fill(0) }
    }
}
