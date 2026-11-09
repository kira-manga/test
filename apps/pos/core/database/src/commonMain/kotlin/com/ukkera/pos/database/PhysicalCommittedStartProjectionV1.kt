package com.ukkera.pos.database

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/**
 * Bounded decoded DATA from the actual 18-field committed-Start projection.
 * Parsing is not authentication, custody, a current instruction or complete-history authority.
 * It does not construct a committed Start, source receipt, service lifetime, lease or principal.
 * Historical authentication/policy are absent from the wire and are deliberately not inferred.
 */
public class PhysicalCommittedStartProjectionV1 private constructor(
    public val scope: BranchScope,
    public val originalActorId: DomainId,
    public val intentOperationId: OperationId,
    public val command: CommandKey,
    public val intentSemanticDigest: PayloadDigest,
    public val domainProfile: String,
    public val startKind: PhysicalRecordingStartKind,
    public val assignmentId: DomainId,
    public val masterDeviceId: DomainId,
    public val epoch: Revision,
    public val sequence: JournalSequence,
    public val journalHash: PayloadDigest,
    public val resultDigest: PayloadDigest,
    public val recordedAt: UtcInstant,
    public val requirementBytes: StoredBytes,
    sourceStamps: List<SourceStamp>,
) {
    private val sourceValues: List<SourceStamp> = sourceStamps.toList()
    /** Encoded order is preserved. Role mapping belongs to genuine source rows, not this parser. */
    public val sourceStamps: List<SourceStamp> get() = sourceValues.toList()

    init {
        requireProtocolName(domainProfile)
        requireNativeOriginalFormatDataV1(sequence.value > 0 && recordedAt.canonicalString().length in 20..30)
        requireNativeOriginalFormatDataV1(requirementBytes.size in 1..MAX_REQUIREMENT_BYTES)
        requireNativeOriginalFormatDataV1(sourceValues.size == 2 && sourceValues[0].key != sourceValues[1].key)
    }

    /** Scalar projection of a source stamp, never the native/kernel source-stamp capability. */
    public data class SourceStamp(
        public val key: AggregateKey,
        public val schema: String,
        public val generation: Revision,
        public val payloadDigest: PayloadDigest,
    ) {
        init {
            requireNativeOriginalFormatDataV1(key.kind == PhysicalRecordingSourceProfileV1.KIND)
            requireNativeOriginalFormatDataV1(schema == PhysicalRecordingSourceProfileV1.SCHEMA && generation.value == 1L)
        }

        override public fun toString(): String = "PhysicalCommittedStartSourceProjectionV1([REDACTED])"
    }

    override public fun toString(): String = "PhysicalCommittedStartProjectionV1([REDACTED])"

    public companion object {
        public const val TAG: String = "ukkera.physical-recording-committed-start/v1"
        /** Format rejection bounds only; not a qualified native/SQL/runtime capacity profile. */
        public const val MAX_BYTES: Int = 16_384
        public const val MAX_REQUIREMENT_BYTES: Int = 8_192

        public fun parse(encoded: StoredBytes): PhysicalCommittedStartProjectionV1 = try {
            requireNativeOriginalFormatDataV1(encoded.size in 1..MAX_BYTES)
            val stampMaximum = 5 * 4 + PhysicalRecordingSourceProfileV1.KIND.length + 36 +
                PhysicalRecordingSourceProfileV1.SCHEMA.length + 1 + 64
            val fields = NativeOriginalFrameSliceV1(encoded.copyBytes()).fields(intArrayOf(
                TAG.length, 110, 36, 36, 96, 4, 64, 96, 7, 36, 36, 19, 19, 64, 64, 30,
                MAX_REQUIREMENT_BYTES, 2 * (4 + stampMaximum),
            ))
            requireNativeOriginalFormatDataV1(fields[0].text(TAG.length) == TAG)
            val sources = fields[17].fields(intArrayOf(stampMaximum, stampMaximum)).map { source ->
                val values = source.fields(intArrayOf(
                    PhysicalRecordingSourceProfileV1.KIND.length, 36,
                    PhysicalRecordingSourceProfileV1.SCHEMA.length, 1, 64,
                ))
                SourceStamp(
                    key = AggregateKey(values[0].text(96), values[1].id()),
                    schema = values[2].text(96),
                    generation = Revision(values[3].counter(1, 1)),
                    payloadDigest = values[4].digest(),
                )
            }
            val kind = when (fields[8].text(7)) {
                "PAYMENT" -> PhysicalRecordingStartKind.PAYMENT
                "REFUND" -> PhysicalRecordingStartKind.REFUND
                else -> throw IllegalArgumentException("Invalid committed Start projection")
            }
            PhysicalCommittedStartProjectionV1(
                scope = fields[1].scope(),
                originalActorId = fields[2].id(),
                intentOperationId = fields[3].operation(),
                command = CommandKey(fields[4].text(96), fields[5].counter(1, 1000).toInt()),
                intentSemanticDigest = fields[6].digest(),
                domainProfile = fields[7].text(96),
                startKind = kind,
                assignmentId = fields[9].id(),
                masterDeviceId = fields[10].id(),
                epoch = fields[11].revision(),
                sequence = fields[12].sequence(),
                journalHash = fields[13].digest(),
                resultDigest = fields[14].digest(),
                recordedAt = fields[15].time(),
                requirementBytes = fields[16].stored(MAX_REQUIREMENT_BYTES),
                sourceStamps = sources,
            )
        } catch (_: Exception) {
            // Fixed redacted failure, with no scalar-parser/input cause attached.
            throw IllegalArgumentException("Invalid committed Start projection")
        }
    }
}
