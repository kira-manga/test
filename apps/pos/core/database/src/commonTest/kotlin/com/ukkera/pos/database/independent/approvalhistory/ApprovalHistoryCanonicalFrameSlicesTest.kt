package com.ukkera.pos.database

import com.ukkera.pos.security.ProtectedLocalBinding
import com.ukkera.pos.security.ProtectedLocalRecordKey
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** OH-06/07 canonical-data slices, not a historical wire golden or an accepting history verifier. */
class ApprovalHistoryCanonicalFrameSlicesTest {
    private val inputs = ApprovalHistoryRepresentationInputs

    @Test fun additiveEnvelopeRetainsExactExistingV1CommandStringBytesAndOriginalPolicyFields() {
        for (kind in RuntimeActionApprovalUseKindV1.entries) {
            val originalCommand = inputs.command(kind)
            val originalBytes = RuntimeCommandAuthorizationEvidenceCodec.encode(originalCommand)
            val envelope = inputs.envelope(kind)
            val bytes = RuntimeActionApprovalEvidenceCodec.encode(envelope)
            try {
                val decoded = RuntimeActionApprovalEvidenceCodec.decodeCommand(bytes)
                assertContentEquals(originalBytes, decoded.commandEvidence.encodeToByteArray())
                assertEquals(originalCommand, decoded.command())
                assertEquals(inputs.requestor, decoded.command().authorization.actorId)
                assertEquals(inputs.manager, decoded.approvalUse.approver.actorId)
                assertEquals(inputs.separatePolicy, decoded.command().authorization.policyRevision)
                assertEquals(inputs.separatePolicy, decoded.approvalUse.approver.policyRevision)
                val encodedAgain = RuntimeActionApprovalEvidenceCodec.encode(decoded)
                try { assertContentEquals(bytes, encodedAgain) } finally { encodedAgain.fill(0) }
            } finally { originalBytes.fill(0); bytes.fill(0) }
        }
    }

    @Test fun directCommandV1KeepsItsOwnProfileAndCannotBeDecodedAsAnApprovedEnvelope() {
        val direct = inputs.command().copy(actionApprovalId = null)
        val bytes = RuntimeCommandAuthorizationEvidenceCodec.encode(direct)
        val approvedBytes = RuntimeActionApprovalEvidenceCodec.encode(inputs.envelope())
        try {
            assertTrue(RuntimeCommandAuthorizationEvidenceCodec.hasProfile(bytes))
            assertFalse(RuntimeActionApprovalEvidenceCodec.hasCommandProfile(bytes))
            assertFalse(RuntimeActionApprovalEvidenceCodec.hasIssuanceProfile(bytes))
            assertEquals(direct, RuntimeCommandAuthorizationEvidenceCodec.decode(bytes))
            assertCommandRejected(bytes)
            assertTrue(RuntimeActionApprovalEvidenceCodec.hasCommandProfile(approvedBytes))
            assertFalse(RuntimeCommandAuthorizationEvidenceCodec.hasProfile(approvedBytes))
            assertFailsWith<RuntimeHistoryFormatRejectedV2> { RuntimeCommandAuthorizationEvidenceCodec.decode(approvedBytes) }
        } finally { bytes.fill(0); approvedBytes.fill(0) }
    }

    @Test fun unsupportedOuterAndRetainedCommandSchemasAreRejectedRatherThanDefaulted() {
        val original = body()
        assertCommandRejected(original.changed { this["schema"] = JsonPrimitive("ukkera.runtime-approved-command-evidence/v999") })
        assertFailsWith<RuntimeHistoryFormatRejectedV2> {
            RuntimeActionApprovalEvidenceCodec.encode(inputs.envelope().copy(schema = "ukkera.runtime-approved-command-evidence/v999"))
        }
        val command = original.getValue("commandEvidence").jsonPrimitive.content
        val unsupported = Json.parseToJsonElement(command).jsonObject.changed {
            this["schema"] = JsonPrimitive("ukkera.runtime-command-authorization-evidence/v999")
        }.toString()
        assertCommandRejected(original.changed { this["commandEvidence"] = JsonPrimitive(unsupported) })
    }

    @Test fun profilePrefixRecognitionDoesNotValidateIncompleteCommandOrIssuanceEvidence() {
        val commandPrefix = prefix(RuntimeApprovedCommandEvidenceV1.PROFILE)
        val issuancePrefix = prefix(RuntimeActionApprovalIssuanceEvidenceV1.PROFILE)
        assertTrue(RuntimeActionApprovalEvidenceCodec.hasCommandProfile(commandPrefix))
        assertTrue(RuntimeActionApprovalEvidenceCodec.hasIssuanceProfile(issuancePrefix))
        assertFalse(RuntimeActionApprovalEvidenceCodec.hasIssuanceProfile(commandPrefix))
        assertFalse(RuntimeActionApprovalEvidenceCodec.hasCommandProfile(issuancePrefix))
        assertCommandRejected(commandPrefix)
        assertFailsWith<RuntimeHistoryFormatRejectedV2> { RuntimeActionApprovalEvidenceCodec.decodeIssuance(issuancePrefix) }
        assertFalse(RuntimeActionApprovalEvidenceCodec.hasCommandProfile(prefix("ukkera.runtime-approved-command-evidence/v999")))
        assertFalse(RuntimeActionApprovalEvidenceCodec.hasIssuanceProfile(prefix("ukkera.runtime-action-approval-issuance/v999")))
    }

    @Test fun requiredEnvelopeFieldsAndExplicitRejectedConsumptionNullsCannotBeOmitted() {
        val original = body(RuntimeActionApprovalUseKindV1.REJECTED_UNCONSUMED)
        for (field in listOf("schema", "commandEvidence", "approvalUse")) {
            assertCommandRejected(original.changed { remove(field) })
        }
        for (field in listOf("kind", "approvalId", "issuedApproval", "approver", "observed", "consumedApproval",
            "consumptionEventId", "consumptionEvent", "images")) {
            assertCommandRejected(original.withUse { remove(field) })
        }
    }

    @Test fun unknownOuterUseApproverObservationImageAndRetainedCommandFieldsAreRejected() {
        val original = body()
        val changes = listOf(original.changed { this["invented"] = JsonPrimitive(true) },
            original.withUse { this["invented"] = JsonPrimitive(true) },
            original.withUse { this["approver"] = getValue("approver").jsonObject.changed { this["invented"] = JsonPrimitive(true) } },
            original.withUse { this["observed"] = getValue("observed").jsonObject.changed { this["invented"] = JsonPrimitive(true) } },
            original.withUse {
                val images = getValue("images").jsonArray.toMutableList()
                images[0] = images[0].jsonObject.changed { this["invented"] = JsonPrimitive(true) }
                this["images"] = JsonArray(images)
            })
        for (changed in changes) assertCommandRejected(changed)
        val command = Json.parseToJsonElement(original.getValue("commandEvidence").jsonPrimitive.content).jsonObject
            .changed { this["invented"] = JsonPrimitive(true) }.toString()
        assertCommandRejected(original.changed { this["commandEvidence"] = JsonPrimitive(command) })
    }

    @Test fun unknownUseKindsUnsupportedImageTagsAndNoncanonicalScalarTypesFailClosed() {
        val original = body()
        for (kind in listOf("DIRECT", "APPLIED", "CURRENT_APPROVAL")) {
            assertCommandRejected(original.withUse { this["kind"] = JsonPrimitive(kind) })
        }
        assertCommandRejected(original.withUse { this["approvalId"] = JsonNull })
        assertCommandRejected(original.withUse { this["images"] = JsonObject(emptyMap()) })
        for ((field, value) in listOf("tableTag" to JsonPrimitive(999), "mutationTag" to JsonPrimitive(999),
            "tableTag" to JsonPrimitive("17"), "revision" to JsonPrimitive(1))) {
            assertCommandRejected(original.withUse {
                val images = getValue("images").jsonArray.toMutableList()
                images[0] = images[0].jsonObject.changed { this[field] = value }
                this["images"] = JsonArray(images)
            })
        }
        val command = Json.parseToJsonElement(original.getValue("commandEvidence").jsonPrimitive.content).jsonObject
            .changed { this["requirement"] = getValue("requirement").jsonObject.changed { this["verb"] = JsonPrimitive("APPROVE") } }
        assertCommandRejected(original.changed { this["commandEvidence"] = JsonPrimitive(command.toString()) })
    }

    @Test fun emptyMalformedUtf8TruncatedAndTrailingFramesNeverBecomeCompleteEvidence() {
        val bytes = RuntimeActionApprovalEvidenceCodec.encode(inputs.envelope())
        try {
            for (bad in listOf(byteArrayOf(), byteArrayOf(0xc3.toByte(), 0x28), bytes.copyOf(1),
                bytes.copyOf(bytes.size / 2), bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(0),
                prefix(RuntimeApprovedCommandEvidenceV1.PROFILE) + byteArrayOf(0xc3.toByte(), 0x28))) {
                assertCommandRejected(bad)
            }
            for (bad in listOf(byteArrayOf(), byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf('{'.code.toByte()))) {
                assertFailsWith<RuntimeHistoryFormatRejectedV2> { RuntimeActionApprovalEvidenceCodec.decodeIssuance(bad) }
            }
        } finally { bytes.fill(0) }
    }

    @Test fun noncanonicalWhitespaceFieldOrderAndDuplicateNamesCannotBeSilentlyNormalized() {
        val original = body()
        val text = original.toString()
        val reordered = JsonObject(linkedMapOf("commandEvidence" to original.getValue("commandEvidence"),
            "schema" to original.getValue("schema"), "approvalUse" to original.getValue("approvalUse"))).toString()
        val duplicateSchema = "{\"schema\":\"" + RuntimeApprovedCommandEvidenceV1.PROFILE + "\"," + text.removePrefix("{")
        for (changed in listOf(" " + text, text + "\n", text.replaceFirst("{", "{ "), reordered, duplicateSchema)) {
            assertCommandRejected(changed.encodeToByteArray())
        }
    }

    @Test fun embeddedV1CommandBytesCannotBeReformattedBeforeJoiningTheApproval() {
        val original = body()
        val text = original.getValue("commandEvidence").jsonPrimitive.content
        val reordered = Json.parseToJsonElement(text).jsonObject.changed {
            val schema = remove("schema")!!
            this["schema"] = schema
        }.toString()
        for (changed in listOf(" " + text, text + "\n", text.replaceFirst("{", "{ "), reordered)) {
            assertCommandRejected(original.changed { this["commandEvidence"] = JsonPrimitive(changed) })
        }
    }

    @Test fun issuedConsumedAndEventStringsMustKeepTheirExactCanonicalSupportedProfiles() {
        val original = body()
        val use = original.getValue("approvalUse").jsonObject
        for (field in listOf("issuedApproval", "consumedApproval", "consumptionEvent")) {
            val text = use.getValue(field).jsonPrimitive.content
            for (changed in listOf(" " + text, text + "\n", text.replaceFirst("{", "{ "))) {
                assertCommandRejected(original.withUse { this[field] = JsonPrimitive(changed) })
            }
            val unsupported = Json.parseToJsonElement(text).jsonObject.changed {
                this["schema"] = JsonPrimitive("unsupported.original-profile/v999")
            }.toString()
            assertCommandRejected(original.withUse { this[field] = JsonPrimitive(unsupported) })
        }
    }

    @Test fun completeUseCannotOmitOrDefaultMandatoryV2ApproverIncarnationFields() {
        val original = body()
        val approval = Json.parseToJsonElement(original.getValue("approvalUse").jsonObject
            .getValue("issuedApproval").jsonPrimitive.content).jsonObject
        for (field in listOf("approverAccountRevision", "approverGeneration", "issueRequestDigest")) {
            for (changed in listOf(approval.changed { remove(field) }, approval.changed { this[field] = JsonNull })) {
                assertCommandRejected(original.withUse { this["issuedApproval"] = JsonPrimitive(changed.toString()) })
            }
        }
        val base = approval.getValue("baseV1").jsonPrimitive.content
        val missingOriginal = Json.parseToJsonElement(base).jsonObject.changed { remove("approverId") }.toString()
        val changed = approval.changed { this["baseV1"] = JsonPrimitive(missingOriginal) }
        assertCommandRejected(original.withUse { this["issuedApproval"] = JsonPrimitive(changed.toString()) })
    }

    @Test fun legacyApprovalV1RemainsReadableHistoryButCannotCompleteEitherNewUseKind() {
        val original = inputs.issuedCarrier()
        val legacy = inputs.copyApproval(original, approverAccountRevision = null, approverGeneration = null, issueRequestDigest = null)
        val bytes = LocalActionApprovalCodec.encode(legacy)
        try {
            val binding = ProtectedLocalBinding(ProtectedLocalRecordKey.ActionApproval(inputs.scope, inputs.inertApprovalId), Revision(0))
            val decoded = LocalActionApprovalCodec.decode(binding, bytes)
            assertFalse(decoded.hasApproverIncarnation)
            assertEquals(original.record, decoded.record)
            val encodedAgain = LocalActionApprovalCodec.encode(decoded)
            try { assertContentEquals(bytes, encodedAgain) } finally { encodedAgain.fill(0) }
            for (kind in RuntimeActionApprovalUseKindV1.entries) {
                val envelope = inputs.envelope(kind)
                assertFailsWith<RuntimeHistoryFormatRejectedV2> {
                    RuntimeActionApprovalEvidenceCodec.encode(envelope.copy(approvalUse = envelope.approvalUse.copy(
                        issuedApproval = bytes.decodeToString(throwOnInvalidSequence = true))))
                }
            }
            val envelope = inputs.envelope()
            val legacyConsumed = inputs.consumedCarrier(legacy)
            assertFailsWith<RuntimeHistoryFormatRejectedV2> {
                RuntimeActionApprovalEvidenceCodec.encode(envelope.copy(approvalUse = envelope.approvalUse.copy(
                    consumedApproval = inputs.approvalText(legacyConsumed))))
            }
        } finally { bytes.fill(0) }
    }

    @Test fun approvalImageGrammarIsClosedToRevisionZeroInsertsAndOneConsumptionUpdate() {
        val consumed = inputs.consumedCarrier(inputs.issuedCarrier())
        val event = inputs.eventCarrier(consumed)
        val expected = inputs.expectedImages(consumed, event)
        val inserted = expected[0].copy(mutationTag = RuntimeHistoryMutationKindV2.INSERT.tag, revision = Revision(0))
        for (allowed in listOf(inserted, expected[0], expected[1])) allowed.validate()
        for (table in RuntimeHistoryTableV2.entries.filter { it != RuntimeHistoryTableV2.APPROVAL && it != RuntimeHistoryTableV2.AUTH_EVENT }) {
            assertFails { inserted.copy(tableTag = table.tag).validate() }
        }
        val invalid = listOf(inserted.copy(revision = Revision(1)), inserted.copy(mutationTag = RuntimeHistoryMutationKindV2.DELETE.tag),
            expected[0].copy(revision = Revision(0)), expected[0].copy(revision = Revision(2)),
            expected[1].copy(mutationTag = RuntimeHistoryMutationKindV2.UPDATE.tag), expected[1].copy(revision = Revision(1)),
            expected[1].copy(mutationTag = RuntimeHistoryMutationKindV2.DELETE.tag), inserted.copy(tableTag = 999), inserted.copy(mutationTag = 999))
        for (image in invalid) assertFails { image.validate() }
        val plaintext = LocalActionApprovalCodec.encode(consumed)
        val digest = sha256(plaintext)
        val captured = RuntimeApprovalImageV1.capture(ProtectedLocalRecordKey.ActionApproval(inputs.scope, inputs.inertApprovalId), Revision(1), plaintext)
        assertEquals(expected[0].copy(plaintextDigest = digest), captured)
        assertTrue(plaintext.all { it == 0.toByte() })
    }

    @Test fun oversizedOuterFramesAndEmbeddedV1CommandBytesAreRejectedWithoutTruncation() {
        val command = ByteArray(64 * 1024 + 1) { ' '.code.toByte() }
        prefix(RuntimeApprovedCommandEvidenceV1.PROFILE).copyInto(command)
        assertFalse(RuntimeActionApprovalEvidenceCodec.hasCommandProfile(command))
        assertCommandRejected(command)
        val issuance = ByteArray(64 * 1024 + 1) { ' '.code.toByte() }
        prefix(RuntimeActionApprovalIssuanceEvidenceV1.PROFILE).copyInto(issuance)
        assertFalse(RuntimeActionApprovalEvidenceCodec.hasIssuanceProfile(issuance))
        assertFailsWith<RuntimeHistoryFormatRejectedV2> { RuntimeActionApprovalEvidenceCodec.decodeIssuance(issuance) }
        val original = body()
        val text = original.getValue("commandEvidence").jsonPrimitive.content
        val oversizedV1 = text + " ".repeat(16 * 1024 + 1 - text.encodeToByteArray().size)
        assertCommandRejected(original.changed { this["commandEvidence"] = JsonPrimitive(oversizedV1) })
    }

    private fun body(kind: RuntimeActionApprovalUseKindV1 = RuntimeActionApprovalUseKindV1.APPLIED_CONSUMED): JsonObject {
        val bytes = RuntimeActionApprovalEvidenceCodec.encode(inputs.envelope(kind))
        return try {
            val parsed = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
            assertContentEquals(bytes, parsed.toString().encodeToByteArray())
            parsed
        } finally { bytes.fill(0) }
    }

    private fun JsonObject.changed(edit: MutableMap<String, JsonElement>.() -> Unit): JsonObject =
        JsonObject(toMutableMap().apply(edit))
    private fun JsonObject.withUse(edit: MutableMap<String, JsonElement>.() -> Unit): JsonObject =
        changed { this["approvalUse"] = getValue("approvalUse").jsonObject.changed(edit) }
    private fun prefix(profile: String): ByteArray = ("{\"schema\":\"" + profile + "\",").encodeToByteArray()
    private fun assertCommandRejected(body: JsonObject) { assertCommandRejected(body.toString().encodeToByteArray()) }
    private fun assertCommandRejected(bytes: ByteArray) {
        assertFailsWith<RuntimeHistoryFormatRejectedV2> { RuntimeActionApprovalEvidenceCodec.decodeCommand(bytes) }
    }
}
