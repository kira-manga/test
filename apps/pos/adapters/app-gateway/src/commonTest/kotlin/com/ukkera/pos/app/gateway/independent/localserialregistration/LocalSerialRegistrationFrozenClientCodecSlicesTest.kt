package com.ukkera.pos.app.gateway.independent.localserialregistration

import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.LocalSerialRegistrationReferenceV1
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.*
import kotlin.test.*

/** Frozen LSR-12/14/21 client format/refusal slices, without native qualification. */
class LocalSerialRegistrationFrozenClientCodecSlicesTest {
    private val f = LsrGatewayInputs

    @Test fun lsr06And21_commandNormalizationKeepsExactStringIdentity() {
        val original = f.request()
        assertEquals(original.payload, normalizeLocalRequest(original.command, original.payload))
        assertTrue(actionMatches(LocalAction.SERIAL_REGISTRATION_ACTIVATE, original))
        assertFalse(actionMatches(LocalAction.CATALOG_ACTIVATE, original))
        assertEquals(f.code, LocalSerialRegistrationCodecV1.decode(original.payload).publication.definition.serialCode)
        assertEquals(Revision(4), LocalSerialRegistrationCodecV1.decode(original.payload).publication.definition.revision)
    }

    @Test fun lsr21_numericCodeAndFractionalRevisionAreNotCoercedByClientNormalization() {
        for ((field, value) in listOf("serialCode" to JsonPrimitive(12345), "revision" to JsonPrimitive("3.5"))) {
            assertFails(field) { normalizeLocalRequest(LocalSerialRegistrationProfileV1.ACTIVATE, changedDefinition(field, value)) }
        }
    }

    @Test fun lsr04And21_emptyOrMissingSerialIdentityAndMalformedIdentifierFailBeforeSavedEntryCreation() {
        val variants = listOf(
            "serialCode" to JsonPrimitive(""), "serialCode" to null,
            "serialId" to JsonPrimitive("NOT_A_VALID_DOMAIN_ID"), "serialId" to JsonNull,
            "registrationId" to null)
        for ((field, value) in variants) {
            assertFails(field) { normalizeLocalRequest(LocalSerialRegistrationProfileV1.ACTIVATE, changedDefinition(field, value)) }
        }
    }

    @Test fun lsr21_unknownFieldsTruncationAndInvalidUtf8NeverBecomeSavedCommands() {
        val original = f.request().payload.copyBytes()
        try {
            val unknown = changedDefinition("inventedNativeAuthority", JsonPrimitive(true))
            assertFails { normalizeLocalRequest(LocalSerialRegistrationProfileV1.ACTIVATE, unknown) }
            assertFails { normalizeLocalRequest(LocalSerialRegistrationProfileV1.ACTIVATE, StoredBytes(original.copyOf(original.size - 1))) }
            assertFails { normalizeLocalRequest(LocalSerialRegistrationProfileV1.ACTIVATE, StoredBytes(byteArrayOf(0xc3.toByte(), 0x28))) }
        } finally { original.fill(0) }
    }

    @Test fun lsr21_duplicateSemanticFieldIsNotAnExactCanonicalSavedCommand() {
        val duplicate = duplicateSerialCodePayload()
        val request = MutationRequest(f.operation, LocalSerialRegistrationProfileV1.ACTIVATE, duplicate)
        assertFailsWith<IllegalArgumentException> {
            require(actionMatches(LocalAction.SERIAL_REGISTRATION_ACTIVATE, request))
        }
        val entry = f.entry().copy(payloadHex = duplicate.copyBytes().let { bytes ->
            try { encodeHex(bytes) } finally { bytes.fill(0) }
        })
        assertFailsWith<IllegalArgumentException> { entry.validate(f.owner) }
        // Frozen LSR-21.e: exact saved-command refusal stays distinct from the
        // direct decoder checks below. Transport framing/depth remain UNBOUND.
    }

    @Test fun lsr21e_directActivationDecoderRejectsExistingDuplicateSemanticFieldInput() {
        val duplicate = duplicateSerialCodePayload()
        assertFails { LocalSerialRegistrationCodecV1.decode(duplicate) }
    }

    @Test fun lsr21e_directQueryDecoderRejectsDuplicateRegistrationIdentityField() {
        val expected = LocalSerialRegistrationQueryV1(f.registration, f.publication)
        val ordinary = LocalSerialRegistrationCodecV1.query(expected).payload
        assertEquals(expected, LocalSerialRegistrationCodecV1.decodeQuery(ordinary))
        // Ordinary JSON whitespace/member order changes no typed identity and is
        // not a duplicate-field input. This is syntax, not native qualification.
        val reorderedWithWhitespace = StoredBytes("""
            {
                "publicationId" : "${f.publication.value}",
                "registrationId" : "${f.registration.value}"
            }
        """.trimIndent().encodeToByteArray())
        assertEquals(expected, LocalSerialRegistrationCodecV1.decodeQuery(reorderedWithWhitespace))
        val raw = ordinary.copyBytes()
        try {
            val member = "\"registrationId\":\"${f.registration.value}\""
            val literal = raw.decodeToString().replace(member, "$member,$member")
            assertNotEquals(raw.decodeToString(), literal)
            val duplicate = StoredBytes(literal.encodeToByteArray())
            assertFails { LocalSerialRegistrationCodecV1.decodeQuery(duplicate) }
        } finally { raw.fill(0) }
    }

    @Test fun lsr21e_directActivationDecoderRejectsEscapedEquivalentDuplicateMemberNames() {
        val expected = LocalActivateSerialRegistrationV1(f.signedShape(), null)
        assertDuplicateMemberRefusal(expected, f.request().payload, "serialCode", JsonPrimitive(f.code),
            LocalSerialRegistrationCodecV1::decode, JsonPrimitive("000AbC-08"))
    }

    @Test fun lsr21e_directQueryDecoderRejectsEscapedEquivalentDuplicateMemberNames() {
        val expected = LocalSerialRegistrationQueryV1(f.registration, f.publication)
        assertDuplicateMemberRefusal(expected, LocalSerialRegistrationCodecV1.query(expected).payload,
            "registrationId", JsonPrimitive(f.registration.value), LocalSerialRegistrationCodecV1::decodeQuery)
    }

    @Test fun lsr21e_directResultDecoderRejectsDuplicateMemberNames() {
        val expected = resultReplyShape()
        assertDuplicateMemberRefusal(expected, LocalSerialRegistrationCodecV1.encodeResult(expected),
            "registrationId", JsonPrimitive(f.registration.value), LocalSerialRegistrationCodecV1::decodeResult)
    }

    @Test fun lsr21e_directReadDecoderRejectsDuplicateMemberNamesAtEachObjectScope() {
        val expected = readReplyShape()
        val ordinary = LocalSerialRegistrationCodecV1.encodeRead(expected)
        assertDuplicateMemberRefusal(expected, ordinary, "registrationId", JsonPrimitive(f.registration.value),
            LocalSerialRegistrationCodecV1::decodeRead)
        // The first serialCode belongs to one nested definition. Its duplicate
        // is malformed there; an equal member in another object is not.
        assertDuplicateMemberRefusal(expected, ordinary, "serialCode", JsonPrimitive(f.code),
            LocalSerialRegistrationCodecV1::decodeRead)
    }

    @Test fun lsr21e_separateObjectsMayRepeatMemberNamesWithoutRejectingOrdinaryJsonSyntax() {
        val activation = LocalActivateSerialRegistrationV1(f.signedShape(), null)
        assertEquals(activation, LocalSerialRegistrationCodecV1.decode(reorderedWithWhitespace(f.request().payload)))
        val query = LocalSerialRegistrationQueryV1(f.registration, f.publication)
        assertEquals(query, LocalSerialRegistrationCodecV1.decodeQuery(reorderedWithWhitespace(
            LocalSerialRegistrationCodecV1.query(query).payload)))
        val result = resultReplyShape()
        assertEquals(result, LocalSerialRegistrationCodecV1.decodeResult(reorderedWithWhitespace(
            LocalSerialRegistrationCodecV1.encodeResult(result))))
        val read = readReplyShape()
        // Root, active definition, predecessor and sibling publication repeat
        // registrationId in separate objects, with unchanged synthetic values.
        assertEquals(read, LocalSerialRegistrationCodecV1.decodeRead(LocalSerialRegistrationCodecV1.encodeRead(read)))
        assertEquals(read, LocalSerialRegistrationCodecV1.decodeRead(reorderedWithWhitespace(
            LocalSerialRegistrationCodecV1.encodeRead(read))))
    }

    @Test fun lsr12And14_editedCodeIntervalOrPublicationCannotReuseOldSavedSemanticIdentity() {
        val original = f.entry()
        val changed = listOf(
            changedDefinition("serialCode", JsonPrimitive("000AbC-08")),
            changedDefinition("validUntil", JsonPrimitive("2031-04-05T13:00:01Z")),
            changedDefinition("publicationId", JsonPrimitive("b1000000-0000-4000-8000-000000000005")))
        changed.forEach { payload ->
            val bytes = payload.copyBytes()
            val encoded = try { encodeHex(bytes) } finally { bytes.fill(0) }
            assertFails { original.copy(payloadHex = encoded).validate(f.owner) }
        }
        assertEquals(f.request().payload, original.request().payload)
        assertEquals(f.actor, original.owner.actorId)
        assertEquals(f.operation, original.operationId)
    }

    @Test fun lsr07And21_declaredRevisionExhaustionCannotWrapOrReuseAnIdentity() {
        val definition = f.signedShape().definition
        assertFailsWith<IllegalArgumentException> { definition.copy(revision = Revision(Long.MAX_VALUE)) }
        assertFailsWith<IllegalArgumentException> {
            LocalSerialRegistrationReferenceV1(definition.registrationId, definition.previous!!.publicationId,
                Revision(Long.MAX_VALUE), f.inertDigest)
        }
        val exhausted = definition.previous!!.copy(revision = Revision(Long.MAX_VALUE - 1))
        assertFailsWith<IllegalArgumentException> { definition.copy(previous = exhausted, revision = Revision(Long.MAX_VALUE)) }
        assertEquals(Revision(4), definition.revision)
        assertEquals(f.publication, definition.publicationId)
        assertEquals(f.registration, definition.registrationId)
        // The type's public range is 1 until Long.MAX_VALUE; a real completed
        // maximum predecessor and native overflow command remain UNBOUND.
    }

    @Test fun lsr21_unsupportedActivationVersionCannotValidateOrResetTheSavedOriginal() {
        val original = f.entry()
        assertFailsWith<IllegalArgumentException> { original.copy(commandVersion = 2).validate(f.owner) }
        assertFailsWith<IllegalArgumentException> {
            normalizeLocalRequest(CommandKey(LocalSerialRegistrationProfileV1.ACTIVATE.name, 2), original.request().payload)
        }
        assertEquals(1, original.commandVersion)
        assertEquals(f.request().payload, original.request().payload)
        assertEquals(f.operation, original.operationId)
    }

    private fun duplicateSerialCodePayload(): StoredBytes {
        val raw = f.request().payload.copyBytes()
        return try {
            val literal = raw.decodeToString().replace("\"serialCode\":\"000AbC-07\"",
                "\"serialCode\":\"000AbC-07\",\"serialCode\":\"000AbC-08\"")
            assertNotEquals(raw.decodeToString(), literal)
            StoredBytes(literal.encodeToByteArray())
        } finally { raw.fill(0) }
    }

    // Reply shapes are inert codec carriers, not a committed result, qualified
    // activation, authentic native publication or currently authorized read.
    private fun resultReplyShape() = LocalSerialRegistrationResultV1("fixture-only", f.registration, f.publication, Revision(1))
    private fun readReplyShape() = LocalSerialRegistrationReadReplyV1(f.registration, Revision(1), f.signedShape(),
        LocalSerialRegistrationQualificationV1.TIME_UNAVAILABLE, f.signedShape())

    /** Derived JSON spelling subchecks of frozen LSR-21.e, not new whole variants. */
    private fun duplicateMemberNames(field: String): List<Pair<String, String>> {
        val plain = JsonPrimitive(field).toString()
        fun escapedAt(index: Int) = "\"${field.take(index)}\\u${field[index].code.toString(16).padStart(4, '0')}${field.drop(index + 1)}\""
        fun fullyEscaped(upperHex: Boolean) = "\"" + field.map { character ->
            val hex = character.code.toString(16).padStart(4, '0')
            "\\u${if (upperHex) hex.uppercase() else hex}"
        }.joinToString("") + "\""
        val firstEscaped = escapedAt(0)
        val lastEscaped = escapedAt(field.lastIndex)
        val allLower = fullyEscaped(false)
        val allUpper = fullyEscaped(true)
        return listOf(plain to plain, plain to firstEscaped, firstEscaped to plain,
            firstEscaped to lastEscaped, allLower to allUpper, allLower to allLower)
    }

    private fun <T> assertDuplicateMemberRefusal(expected: T, ordinary: StoredBytes, field: String,
        value: JsonElement, decode: (StoredBytes) -> T, secondValue: JsonElement = value) {
        assertEquals(expected, decode(ordinary))
        for ((index, names) in duplicateMemberNames(field).withIndex()) {
            // Each spelling alone is a valid representation of the same public
            // member, so refusal must not rely on treating an escape as unknown.
            assertEquals(expected, decode(rewrittenMember(ordinary, field, value, names.first)), "$field first spelling $index")
            assertEquals(expected, decode(rewrittenMember(ordinary, field, value, names.second)), "$field second spelling $index")
            val duplicate = rewrittenMember(ordinary, field, value, names.first, names.second, secondValue)
            assertFails("$field duplicate spelling $index") { decode(duplicate) }
        }
    }

    private fun rewrittenMember(ordinary: StoredBytes, field: String, value: JsonElement, firstName: String,
        secondName: String? = null, secondValue: JsonElement = value): StoredBytes {
        val raw = ordinary.copyBytes()
        return try {
            val text = raw.decodeToString()
            val member = "${JsonPrimitive(field)}:$value"
            val start = text.indexOf(member)
            assertTrue(start >= 0, "Missing public member $field")
            val replacement = "$firstName:$value" + (secondName?.let { ",$it:$secondValue" } ?: "")
            StoredBytes(text.replaceRange(start, start + member.length, replacement).encodeToByteArray())
        } finally { raw.fill(0) }
    }

    private fun reorderedWithWhitespace(ordinary: StoredBytes): StoredBytes {
        val raw = ordinary.copyBytes()
        return try {
            val root = Json.parseToJsonElement(raw.decodeToString()).jsonObject
            val reordered = JsonObject(root.entries.toList().asReversed().associate { it.key to it.value })
            StoredBytes("\r\n  $reordered\t\n".encodeToByteArray())
        } finally { raw.fill(0) }
    }

    private fun changedDefinition(field: String, value: JsonElement?): StoredBytes {
        val original = f.request().payload.copyBytes()
        return try {
            val root = Json.parseToJsonElement(original.decodeToString()).jsonObject
            val publication = root.getValue("publication").jsonObject
            val definition = publication.getValue("definition").jsonObject
            val replacement = JsonObject(definition.toMutableMap().apply {
                if (value == null) remove(field) else put(field, value)
            })
            StoredBytes(JsonObject(root.toMutableMap().apply {
                put("publication", JsonObject(publication.toMutableMap().apply { put("definition", replacement) }))
            }).toString().encodeToByteArray())
        } finally { original.fill(0) }
    }
}
