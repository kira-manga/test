package com.ukkera.pos.database.independent.approvalretirementnoadmission

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.LocalActionApprovalRetirementAttempt
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Frozen ARNA v1 context projected onto public immutable request/attempt types only.
 * The command key is an uninstalled fixture carrier. Its exact body retains the
 * frozen manual-discount context; it is NOT a supported manual-discount action.
 * No handler, server preview, approval receipt, principal or native owner is made.
 */
internal object ArnaV1Inputs {
    val frozen: JsonObject = readFrozenArnaV1()
    private val bindingA = frozen.getValue("binding_A").jsonObject
    private val bindingB = frozen.getValue("binding_B").jsonObject
    private val aliases = listOf("organization-A", "company-A", "branch-A", "admitted-client-A",
        "cashier-A", "manager-A", "session-A0", "drawer-A", "order-A", "line-A", "sku-A",
        "approval-A", "business-A", "I0", "I1", "I2", "order-B", "line-B", "approval-B",
        "business-B", "I3", "session-A1", "cashier-B", "drawer-B")

    fun id(alias: String): DomainId = carrier(aliases.indexOf(alias).also { require(it >= 0) } + 1)
    fun carrier(number: Int): DomainId =
        DomainId("b9a00000-0000-4000-8000-${number.toString().padStart(12, '0')}")

    val scope = BranchScope(id("organization-A"), id("company-A"), id("branch-A"))
    val command = CommandKey("qualification.arna.manual-discount", 1)

    fun commandBody(discountMinor: Long? = null, otherTarget: Boolean = false): StoredBytes {
        val fields = bindingA.getValue("unapproved_command").jsonObject.toMutableMap()
        discountMinor?.let { fields["requested_discount_minor"] = JsonPrimitive(it) }
        if (otherTarget) {
            fields["order_alias"] = bindingB.getValue("order_alias")
            fields["line_alias"] = bindingB.getValue("line_alias")
        }
        return StoredBytes(JsonObject(fields).toString().encodeToByteArray())
    }

    fun confirmation(orderRevision: Long? = null, netMinor: Long? = null,
        otherTarget: Boolean = false): PayloadDigest {
        val fields = bindingA.getValue("confirmed_preview").jsonObject.toMutableMap()
        orderRevision?.let { fields["order_revision"] = JsonPrimitive(it) }
        netMinor?.let { fields["prospective_net_minor"] = JsonPrimitive(it) }
        if (otherTarget) {
            fields["order_alias"] = bindingB.getValue("order_alias")
            fields["line_alias"] = bindingB.getValue("line_alias")
        }
        // A digest carrier of frozen confirmation context, not an authoritative preview.
        return sha256(JsonObject(fields).toString().encodeToByteArray())
    }

    fun request(otherBinding: Boolean = false): MutationRequest = MutationRequest(
        OperationId(id(if (otherBinding) "business-B" else "business-A").value), command,
        commandBody(otherTarget = otherBinding))

    fun attempt(alias: String = "I1", sequence: Long = 2,
        request: MutationRequest = request(), otherBinding: Boolean = false): LocalActionApprovalRetirementAttempt =
        LocalActionApprovalRetirementAttempt.capture(id(alias), sequence,
            id(if (otherBinding) "approval-B" else "approval-A"), request,
            confirmation(otherTarget = otherBinding))

    fun credentials(): LocalGatewayCredentials =
        LocalGatewayCredentials(carrier(101), OpaqueCredential(ByteArray(32) { 0x5a }))
}

private fun readFrozenArnaV1(): JsonObject {
    val relative = Path.of("qa", "pos", "designs", "approval-retirement-no-admission", "fixtures.json")
    var directory: Path? = Path.of("").toAbsolutePath()
    while (directory != null) {
        val candidate = directory.resolve(relative)
        if (Files.isRegularFile(candidate)) return Json.parseToJsonElement(Files.readString(candidate)).jsonObject
        directory = directory.parent
    }
    error("Missing frozen specification: $relative")
}
