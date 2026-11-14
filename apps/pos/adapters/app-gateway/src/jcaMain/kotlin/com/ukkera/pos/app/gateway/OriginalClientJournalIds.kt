package com.ukkera.pos.app.gateway

import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.types.DomainId
import java.security.MessageDigest

/**
 * Format-bound IDs for this exact, already durable signed installation, not random identities
 * chosen after missing files. No scalar reopening API exports these as custody. SHA-256's
 * first 128 bits are encoded as a UUID-shaped DomainId; these IDs are not credentials.
 */
internal fun originalClientJournalId(original: AndroidOriginalLocalClient, kind: ClientJournalKind,
    purpose: String, domain: String = "ukkera.android.original-client-enrollment/v1"): DomainId {
    val c = original.material.coordinates
    val body = listOf(domain, c.scope.organizationId.value,
        c.scope.companyId.value, c.scope.branchId.value, c.deviceId.value, c.installationId.value,
        c.firstActorId.value, original.receipt.core.originalRequestId.value, original.receipt.digest.value,
        if (kind == ClientJournalKind.CREDENTIALS) "credentials" else "intents", purpose).joinToString("\n")
    return body.encodeToByteArray().useWiped { bytes ->
        MessageDigest.getInstance("SHA-256").digest(bytes).useWiped { hash ->
            val hex = encodeHex(hash).take(32)
            DomainId("${hex.take(8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}")
        }
    }
}
