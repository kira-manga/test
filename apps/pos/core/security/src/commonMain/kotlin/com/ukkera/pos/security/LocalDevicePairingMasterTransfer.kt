package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/** Bounded public transfer DATA. Parsing and review do not verify signatures or select a
 * trusted RPC Master. The actual target independently authenticates its retained C/P joins. */
public class LocalDevicePairingMasterTransfer private constructor(canonical: ByteArray) {
    private val bytes = canonical.copyOf()
    private val fields = decodeMasterTransfer(bytes)
    private val record = NativeDevicePairingWireV1.decodeApprovedCertificate(fields.first)
    public val originalActionId: DomainId get() = record.body.originalActionId
    public val originalChallenge: LocalDevicePairingChallenge get() = LocalDevicePairingChallenge.fromNative(record.body)
    public val masterSdkOriginal: LocalMasterSdkOriginal get() = checkNotNull(record.body.requested().masterSdkOriginal)
    public val transferDigest: PayloadDigest = sha256(bytes)
    public val authorityCertificateDigest: PayloadDigest = sha256(fields.first)
    public val rootDocumentDigest: PayloadDigest = sha256(fields.second)
    public fun copyAuthorityCertificate(): ByteArray = fields.first.copyOf()
    public fun copyRootAnnouncement(): ByteArray = fields.second.copyOf()
    public fun copyWireBytes(): ByteArray = bytes.copyOf()
    override fun toString(): String = "LocalDevicePairingMasterTransfer([REDACTED])"

    public companion object {
        public const val MAXIMUM_CERTIFICATE_BYTES: Int = LocalDevicePairingProfileV1.MAXIMUM_CHALLENGE_BYTES +
            LocalDevicePairingProfileV1.COMPLETION_RESERVATION_BYTES + 1024
        public const val MAXIMUM_BYTES: Int = MAXIMUM_CERTIFICATE_BYTES +
            SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES + 1024

        /** Export from the actual original producer; retains both original documents verbatim. */
        public fun capture(approval: LocalDevicePairingApproval): LocalDevicePairingMasterTransfer {
            val certificate = approval.copyAuthorityCertificate()
            try {
                val root = approval.copyRootAnnouncement()
                try {
                    val canonical = encodeMasterTransfer(certificate, root)
                    return try { decode(canonical) } finally { canonical.fill(0) }
                } finally { root.fill(0) }
            } finally { certificate.fill(0) }
        }

        /** Canonical DATA only; this cannot construct the qualified native readback capability. */
        public fun decode(canonical: ByteArray): LocalDevicePairingMasterTransfer {
            require(canonical.size in 1..MAXIMUM_BYTES)
            val value = LocalDevicePairingMasterTransfer(canonical)
            requireNotNull(value.record.body.requested().masterSdkOriginal)
            val root = requireNotNull(SelfOwnedInstallationWireV1.decodeSigned(value.fields.second))
            require(root.role == SelfOwnedDocumentRoleV1.ROOT_ANNOUNCEMENT &&
                sha256(value.fields.second) == value.record.body.origin.rootDocumentDigest)
            val encoded = encodeMasterTransfer(value.fields.first, value.fields.second)
            try { require(encoded.contentEquals(canonical)) } finally { encoded.fill(0) }
            return value
        }
    }
}

private const val MASTER_TRANSFER_DOMAIN = "original-master-approved-device-pairing-transfer/v1"
private fun encodeMasterTransfer(certificate: ByteArray, root: ByteArray): ByteArray = selfOwnedEncode(
    MASTER_TRANSFER_DOMAIN, LocalDevicePairingMasterTransfer.MAXIMUM_BYTES) {
    require(certificate.size in 1..LocalDevicePairingMasterTransfer.MAXIMUM_CERTIFICATE_BYTES &&
        root.size in 1..SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES)
    field(certificate); field(root)
}
private fun decodeMasterTransfer(bytes: ByteArray): Pair<ByteArray, ByteArray> = selfOwnedDecode(bytes, MASTER_TRANSFER_DOMAIN) {
    field(LocalDevicePairingMasterTransfer.MAXIMUM_CERTIFICATE_BYTES) to
        field(SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES)
}
