package com.ukkera.pos.catalog.store.independent.openingobligationv1

import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.*

/** Exact frozen OSD-BND v1 input bytes; source fragments are not presentation part oracles. */
internal class OpeningDocumentaryBoundaryCodecOriginal(
    val caseId: String, val fixtureId: String, val operation: OperationId, val obligation: DomainId,
    val expectedByteSize: Int, val assetSha256: String, input: ByteArray,
) {
    private val frozen = input.copyOf()
    val raw: ByteArray get() = frozen.copyOf()
    fun text(): String = frozen.decodeToString(throwOnInvalidSequence = true)
}

internal object OpeningSupplierDocumentaryBoundaryCodecInputs {
    val scope = BranchScope(DomainId("e567a673-2e7e-4a0f-9d60-911849d2d99d"),
        DomainId("01cb8257-2c15-46be-a77d-4351bde5a20f"), DomainId("b48cbef5-12df-475c-8246-f0a075fe0d9d"))
    val actor = DomainId("1f292f7a-706f-40d4-bbe0-523b0306d0dc")
    val supplier = DomainId("6f6358e2-55b2-41d3-8f08-4e6074952918")
    val evidence = DomainId("a13be7b9-d480-4855-9167-1d1994a82803")
    const val source = "original-opening-ledger-2026-A"
    const val reference = "SYN-OPEN-A-2026-010"
    const val date = "2026-10-20"
    const val terms = "Payable on 2026-11-30; no opening payment."
    const val reason = "opening.synthetic.boundary"
    const val minor = 125075L

    val accepted = listOf(
        OpeningDocumentaryBoundaryCodecOriginal(
            caseId = "OSD-BND-01", fixtureId = "ascii-1",
            operation = OperationId("bb9bf447-ab7c-4bc5-a1ee-c1ce53f155db"), obligation = DomainId("87a73df0-52f9-4ac7-a80a-fb987508a072"),
            expectedByteSize = 1, assetSha256 = "4b68ab3847feda7d6c62c1fbcbeebfa35eab7351ed5e78f4ddadea5df64b8015",
            input = ("X").encodeToByteArray(throwOnInvalidSequence = true),
        ),
        OpeningDocumentaryBoundaryCodecOriginal(
            caseId = "OSD-BND-01", fixtureId = "ascii-4096",
            operation = OperationId("5a0c5d06-c111-4be6-b745-cd54a3585993"), obligation = DomainId("5244b7c7-eb53-4f19-b862-a530fc0696a8"),
            expectedByteSize = 4096, assetSha256 = "ab32f483dd05f61413c31130f0dc6e7231830a1bee54092b6a8d7a1bd66354ee",
            input = ("SUPPLIER ORIGINAL STATEMENT\n" +
            "Reference: SYN-OPEN-A-2026-010\n" +
            "Amount: EGP 1250.75\n" +
            "Terms: due 2026-11-30\n" +
            "Details:\n" +
            "001 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "002 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "003 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "004 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "005 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "006 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "007 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "008 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "009 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "010 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "011 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "012 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "013 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "014 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "015 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "016 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "017 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "018 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "019 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "020 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "021 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "022 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "023 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "024 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "025 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "026 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "027 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "028 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "029 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "030 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "031 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "032 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "033 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "034 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "035 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "036 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "Retained original spacing:                                                                                       \n" +
            "END ORIGINAL 000010\n").encodeToByteArray(throwOnInvalidSequence = true),
        ),
        OpeningDocumentaryBoundaryCodecOriginal(
            caseId = "OSD-BND-02", fixtureId = "unicode-4096",
            operation = OperationId("9e046d3c-75ff-42df-b92e-37db4f5ef7c0"), obligation = DomainId("4c1e20be-b9ff-47d4-b580-a9f9b08a2389"),
            expectedByteSize = 4096, assetSha256 = "b44aa3abdb56fc9e5b6c3c54a5d3c84c277e14f1a4bb05c2ff3ba92c1ac3a593",
            input = ("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA🙂BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB" +
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB" +
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB" +
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB" +
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB" +
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB" +
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB🚀CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC" +
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC" +
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC" +
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC" +
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC" +
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC" +
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC" +
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCC😀DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD" +
            "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD" +
            "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD" +
            "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD" +
            "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD" +
            "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD" +
            "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDموردéé\n").encodeToByteArray(throwOnInvalidSequence = true),
        ),
        OpeningDocumentaryBoundaryCodecOriginal(
            caseId = "OSD-BND-03", fixtureId = "whitespace-newlines",
            operation = OperationId("8282bca6-fe1c-4aab-97bc-88a3dc392283"), obligation = DomainId("9a4f0a7b-9f52-4a9c-9d4f-846b2e7fba69"),
            expectedByteSize = 148, assetSha256 = "50e40b4c041fdfd9b08f7a2feeebbe67421d45ad2b8dac85dc0343bc6c630b57",
            input = (" \t\r\n" +
            "  ORIGINAL SOURCE 0007 \t\n" +
            "Supplier: مورد\r" +
            "Reference: 000007\r\n" +
            "Amount: EGP 1250.75\n" +
            "Terms: due 2026-11-30\r\n" +
            "Spelling: café | café\r\n" +
            "\r\n" +
            "End.\t  \n" +
            " \t").encodeToByteArray(throwOnInvalidSequence = true),
        )
    )

    val refused = listOf(
        OpeningDocumentaryBoundaryCodecOriginal(
            caseId = "OSD-BND-04", fixtureId = "empty",
            operation = OperationId("8de36cb1-09b0-460c-a86a-df6f6b851790"), obligation = DomainId("3dd62818-e6fe-47ff-949f-3171b7b215bb"),
            expectedByteSize = 0, assetSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            input = ByteArray(0),
        ),
        OpeningDocumentaryBoundaryCodecOriginal(
            caseId = "OSD-BND-04", fixtureId = "utf8-isolated-continuation",
            operation = OperationId("68fc346f-9f9a-4292-a152-93a87f173307"), obligation = DomainId("0f105fda-a64f-4bce-908f-76da0c777c87"),
            expectedByteSize = 14, assetSha256 = "cc2c02eb43bb2a40b354eafe5f4d7a453767969fdcec8c91b5250cb4f9daacbd",
            input = hexBytes("4f726967696e616c0a800a456e64"),
        ),
        OpeningDocumentaryBoundaryCodecOriginal(
            caseId = "OSD-BND-04", fixtureId = "utf8-overlong",
            operation = OperationId("1149e41f-539e-4220-9bf3-6fcb4af2cb4f"), obligation = DomainId("183fae83-bb2e-4820-bf93-abc2a9d8da1b"),
            expectedByteSize = 15, assetSha256 = "12da79b50921edddc6fea159fbba84dfa87ac11d050b28d6a49560522c6a9388",
            input = hexBytes("4f726967696e616c0ac0af0a456e64"),
        ),
        OpeningDocumentaryBoundaryCodecOriginal(
            caseId = "OSD-BND-04", fixtureId = "utf8-truncated-supplementary",
            operation = OperationId("3ad60273-83fc-410f-9289-1f316bd34013"), obligation = DomainId("408fa6bc-7201-407f-9e65-a6119b201ba2"),
            expectedByteSize = 12, assetSha256 = "644381bcf4a7f8a41b93cd9429dcb23817113d3cd0c32d8deeae62581a251632",
            input = hexBytes("4f726967696e616c0af09f99"),
        ),
        OpeningDocumentaryBoundaryCodecOriginal(
            caseId = "OSD-BND-04", fixtureId = "utf8-surrogate-encoding",
            operation = OperationId("33820558-083c-46fa-86a7-cbb74a377fce"), obligation = DomainId("95800abe-4091-4097-8583-334165c3ec81"),
            expectedByteSize = 19, assetSha256 = "764d3ad65f5544dfc700f626261e7ff35ed24af147aa3edddaa39f06f30b2d0a",
            input = hexBytes("4f726967696e616c0aeda0bdedb8800a456e64"),
        ),
        OpeningDocumentaryBoundaryCodecOriginal(
            caseId = "OSD-BND-05", fixtureId = "ascii-4097",
            operation = OperationId("75b62dcf-0907-4c81-887f-643f5ff23eb2"), obligation = DomainId("94d45477-d15f-4c53-af54-c1cdcbc39c79"),
            expectedByteSize = 4097, assetSha256 = "f4ca786c13288ba06f815a0d04a96e8f4250edb164414c2176837b27221e9bed",
            input = ("SUPPLIER ORIGINAL STATEMENT\n" +
            "Reference: SYN-OPEN-A-2026-010\n" +
            "Amount: EGP 1250.75\n" +
            "Terms: due 2026-11-30\n" +
            "Details:\n" +
            "001 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "002 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "003 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "004 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "005 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "006 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "007 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "008 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "009 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "010 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "011 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "012 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "013 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "014 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "015 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "016 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "017 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "018 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "019 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "020 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "021 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "022 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "023 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "024 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "025 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "026 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "027 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "028 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "029 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "030 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "031 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "032 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "033 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "034 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "035 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "036 | Prior-period balance detail retained in this original statement; no current receipt or cash payment.\n" +
            "Retained original spacing:                                                                                       \n" +
            "END ORIGINAL 000010\n" +
            "!").encodeToByteArray(throwOnInvalidSequence = true),
        ),
        OpeningDocumentaryBoundaryCodecOriginal(
            caseId = "OSD-BND-05", fixtureId = "unicode-4097",
            operation = OperationId("c4efe65d-a0de-40b9-8de4-8763d7167b6a"), obligation = DomainId("7c566294-f770-4ef4-af42-61f094c1fed1"),
            expectedByteSize = 4097, assetSha256 = "e80b09d3e87ea7d204ee7e45bb2621f8534796ff1f06e7aec6f39861d69b9b02",
            input = ("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA🙂BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB" +
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB" +
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB" +
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB" +
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB" +
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB" +
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB🚀CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC" +
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC" +
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC" +
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC" +
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC" +
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC" +
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC" +
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCC😀DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD" +
            "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD" +
            "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD" +
            "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD" +
            "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD" +
            "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD" +
            "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDموردéé\n" +
            "!").encodeToByteArray(throwOnInvalidSequence = true),
        )
    )

    /** JSON transport escapes ASCII syntax/controls only. Never decode/repair malformed bytes. */
    fun rawRequest(original: OpeningDocumentaryBoundaryCodecOriginal): ByteArray {
        val prefix = """{"obligationId":"${original.obligation.value}","supplierId":"${supplier.value}","expectedSupplierRevision":"1","sourceEvidenceId":"${evidence.value}","sourceReference":"$reference","sourceDocument":{"sourceIdentity":"$source","text":""".encodeToByteArray()
        val suffix = """},"openingDate":"$date","amount":{"currency":"EGP","minor":"125075"},"paymentTerms":"$terms","reasonCode":"$reason"}""".encodeToByteArray()
        return prefix + jsonQuotedRaw(original.raw) + suffix
    }

    private fun jsonQuotedRaw(input: ByteArray): ByteArray {
        val escaped = ArrayList<Byte>(input.size + 2)
        escaped += 0x22.toByte()
        for (byte in input) {
            val code = byte.toInt() and 0xff
            val syntax = when (code) {
                0x22 -> "\\\""
                0x5c -> "\\\\"
                in 0..0x1f -> "\\u" + code.toString(16).padStart(4, '0')
                else -> null
            }
            if (syntax == null) escaped += byte else escaped.addAll(syntax.encodeToByteArray().toList())
        }
        escaped += 0x22.toByte()
        return escaped.toByteArray()
    }

    private fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) {
        hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}
