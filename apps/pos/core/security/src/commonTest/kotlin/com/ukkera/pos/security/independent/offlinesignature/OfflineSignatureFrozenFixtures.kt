package com.ukkera.pos.security.independent.offlinesignature

/** Literal/recipe transcription of frozen AOSV v1; no signing or substituted verifier. */
internal data class OfflineSignatureFrozenInvocation(
    val id: String,
    val expected: Boolean,
    val publicKey: ByteArray,
    val message: ByteArray,
    val signature: ByteArray,
)

internal data class OfflineSignatureFrozenRole(
    val id: String,
    val name: String,
    val frameHex: String,
    val signatureHex: String,
)

internal object OfflineSignatureFrozenFixtures {
    private data class RfcHex(val key: String, val message: String, val signature: String)
    private val rfc = mapOf(
        "F-RFC-2" to RfcHex(
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
            "72",
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
        ),
        "F-RFC-3" to RfcHex(
            "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
            "af82",
            "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
        ),
        "F-RFC-1023" to RfcHex(
            "278117fc144c72340f67d0f2316e8386ceffbf2b2428c9c51fef7c597f1d426e",
            """
            08b8b2b733424243760fe426a4b54908632110a66c2f6591eabd3345e3e4eb98
            fa6e264bf09efe12ee50f8f54e9f77b1e355f6c50544e23fb1433ddf73be84d8
            79de7c0046dc4996d9e773f4bc9efe5738829adb26c81b37c93a1b270b20329d
            658675fc6ea534e0810a4432826bf58c941efb65d57a338bbd2e26640f89ffbc
            1a858efcb8550ee3a5e1998bd177e93a7363c344fe6b199ee5d02e82d522c4fe
            ba15452f80288a821a579116ec6dad2b3b310da903401aa62100ab5d1a36553e
            06203b33890cc9b832f79ef80560ccb9a39ce767967ed628c6ad573cb116dbef
            efd75499da96bd68a8a97b928a8bbc103b6621fcde2beca1231d206be6cd9ec7
            aff6f6c94fcd7204ed3455c68c83f4a41da4af2b74ef5c53f1d8ac70bdcb7ed1
            85ce81bd84359d44254d95629e9855a94a7c1958d1f8ada5d0532ed8a5aa3fb2
            d17ba70eb6248e594e1a2297acbbb39d502f1a8c6eb6f1ce22b3de1a1f40cc24
            554119a831a9aad6079cad88425de6bde1a9187ebb6092cf67bf2b13fd65f270
            88d78b7e883c8759d2c4f5c65adb7553878ad575f9fad878e80a0c9ba63bcbcc
            2732e69485bbc9c90bfbd62481d9089beccf80cfe2df16a2cf65bd92dd597b07
            07e0917af48bbb75fed413d238f5555a7a569d80c3414a8d0859dc65a46128ba
            b27af87a71314f318c782b23ebfe808b82b0ce26401d2e22f04d83d1255dc51a
            ddd3b75a2b1ae0784504df543af8969be3ea7082ff7fc9888c144da2af58429e
            c96031dbcad3dad9af0dcbaaaf268cb8fcffead94f3c7ca495e056a9b47acdb7
            51fb73e666c6c655ade8297297d07ad1ba5e43f1bca32301651339e22904cc8c
            42f58c30c04aafdb038dda0847dd988dcda6f3bfd15c4b4c4525004aa06eeff8
            ca61783aacec57fb3d1f92b0fe2fd1a85f6724517b65e614ad6808d6f6ee34df
            f7310fdc82aebfd904b01e1dc54b2927094b2db68d6f903b68401adebf5a7e08
            d78ff4ef5d63653a65040cf9bfd4aca7984a74d37145986780fc0b16ac451649
            de6188a7dbdf191f64b5fc5e2ab47b57f7f7276cd419c17a3ca8e1b939ae49e4
            88acba6b965610b5480109c8b17b80e1b7b750dfc7598d5d5011fd2dcc5600a3
            2ef5b52a1ecc820e308aa342721aac0943bf6686b64b2579376504ccc493d97e
            6aed3fb0f9cd71a43dd497f01f17c0e2cb3797aa2a2f256656168e6c496afc5f
            b93246f6b1116398a346f1a641f3b041e989f7914f90cc2c7fff357876e506b5
            0d334ba77c225bc307ba537152f3f1610e4eafe595f6d9d90d11faa933a15ef1
            369546868a7f3a45a96768d40fd9d03412c091c6315cf4fde7cb68606937380d
            b2eaaa707b4c4185c32eddcdd306705e4dc1ffc872eeee475a64dfac86aba41c
            0618983f8741c5ef68d3a101e8a3b8cac60c905c15fc910840b94c00a0b9d0
            """,
            "0aab4c900501b3e24d7cdf4663326a3a87df5e4843b2cbdb67cbf6e460fec350aa5371b1508f9f4528ecea23c436d94b5e8fcd4f681e30a6ac00a9704a188a03",
        ),
        "F-RFC-SHA" to RfcHex(
            "ec172b93ad5e563bf4932c70e1245034c35467ef2efd4d64ebf819683467e2bf",
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            "dc2a4459e7369633a52b1bf277839a00201009a3efbf3ecb69bea2186c26b58909351fc9ac90b3ecfdfbc7c66431e0303dca179c138ac17ad9bef1177331a704",
        ),
        "F-RFC-EMPTY" to RfcHex(
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            "",
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        ),
    )

    val roles: List<OfflineSignatureFrozenRole> = listOf(
        OfflineSignatureFrozenRole(
            "AOSV-100", "TRUST_STATE",
            """
            0000000300000031756b6b6572612e6f66666c696e652d696e7374616c6c6174
            696f6e2f646f63756d656e742d7369676e61747572652f76310000000b545255
            53545f53544154450000002f7b2266697874757265223a226f66666c696e652d
            7369676e61747572652d7631222c2273657175656e6365223a317d
            """,
            "8780ed92f931d03ac81044e5350341808bf911f9e254372ce1eae7f6a4ff026e423f83eafaca34d32493c343d2d9db6d809fa860e26e6a45b15611b90a123a0a",
        ),
        OfflineSignatureFrozenRole(
            "AOSV-101", "SOURCE",
            """
            0000000300000031756b6b6572612e6f66666c696e652d696e7374616c6c6174
            696f6e2f646f63756d656e742d7369676e61747572652f763100000006534f55
            5243450000002f7b2266697874757265223a226f66666c696e652d7369676e61
            747572652d7631222c2273657175656e6365223a317d
            """,
            "02a62b46aa02b17a1290b836d2cbbbac5cb4596729c7d831be0cbc33a0131af6865cd05ed1916e04d5de579d1ac656704d0a701e632cf00e0bc05c50e2a92b0f",
        ),
        OfflineSignatureFrozenRole(
            "AOSV-102", "HUMAN_DELEGATION",
            """
            0000000300000031756b6b6572612e6f66666c696e652d696e7374616c6c6174
            696f6e2f646f63756d656e742d7369676e61747572652f76310000001048554d
            414e5f44454c45474154494f4e0000002f7b2266697874757265223a226f6666
            6c696e652d7369676e61747572652d7631222c2273657175656e6365223a317d
            """,
            "7f61be90a1ec8f4fa4ea4c37362dbaa4178cee79d692dca0828906c6f9da5b928303e7bdf782857695dafe73c95b2fd04377a47156e205ad1c089f9313693f01",
        ),
        OfflineSignatureFrozenRole(
            "AOSV-103", "EXACT_REQUEST_APPROVAL",
            """
            0000000300000031756b6b6572612e6f66666c696e652d696e7374616c6c6174
            696f6e2f646f63756d656e742d7369676e61747572652f763100000016455841
            43545f524551554553545f415050524f56414c0000002f7b2266697874757265
            223a226f66666c696e652d7369676e61747572652d7631222c2273657175656e
            6365223a317d
            """,
            "a806b393499c24c58c5ea93f3f52e9cb73d0ed39edaeac2a94f34c6a109863c2bb35ea98cccbb1e8a1efe0a4d717ea62d91179818d79f4a87c9dd7aa78abe50f",
        ),
        OfflineSignatureFrozenRole(
            "AOSV-104", "CUSTODY_BINDING",
            """
            0000000300000031756b6b6572612e6f66666c696e652d696e7374616c6c6174
            696f6e2f646f63756d656e742d7369676e61747572652f76310000000f435553
            544f44595f42494e44494e470000002f7b2266697874757265223a226f66666c
            696e652d7369676e61747572652d7631222c2273657175656e6365223a317d
            """,
            "121339638bd77da77ab49b991a932f81558f0e02fd3e4e625a6704e17c1087b56dd42e07dec43cad5cf35b0a84fc485dedfad5536a8d26ba6e3729d04fd8850b",
        ),
        OfflineSignatureFrozenRole(
            "AOSV-105", "CUSTODY_LEASE",
            """
            0000000300000031756b6b6572612e6f66666c696e652d696e7374616c6c6174
            696f6e2f646f63756d656e742d7369676e61747572652f76310000000d435553
            544f44595f4c454153450000002f7b2266697874757265223a226f66666c696e
            652d7369676e61747572652d7631222c2273657175656e6365223a317d
            """,
            "63ba1ff228eb6454abbd51c5a0f720117d9100bb7c3f2b8122bdec32d8a04fab84ccc5216cd2799063b748a8f27c8a52d9a5fac375e67d0168da1adaa8dfc308",
        ),
    )

    private val rawBoundarySignatures: Map<Int, String> = mapOf(
        65535 to "589af4ac0ceaf16c00b6c57221219d1949d54061efca7bdb93f78d0543e3387e1ca5762b75134056ec531ff7311f9a9122f39e54afe381afba110220d456a80d",
        65536 to "13c24ab1b097efd18785d561d700e392619a9fd9da8c298bd7d4c943a57e170da5b489157b0d9332a87c88b010f811b7625bdf76062447b369bbc7cfe45b870b",
        65537 to "b44cc3ad9a3cad1cdb4cde97ec586a52025273de6eddf0fe8696fcfb1c3fc0f2d4732cb7175e603303c0604a51c11dd7bcd10bd7d8985babedc0bd038dbba406",
    )
    private val roleBoundarySignatures: Map<Int, String> = mapOf(
        65535 to "573d420e7ce676513b4a6f4b07954454aabd51f32064351275b89df4b419fb5d48a1508c3eabd91c0192b9d19478ea200d54e5e1ca3e531b9c1aad2e71c79106",
        65536 to "c90d86559cebe788c62b9a8a1d7345fac2c83c178964c1323cebd14f9b9ab74083838304b27e24f2d5f0ace2ed85f33578f5e7640a83318ae8f4d9de5dff9006",
        65537 to "0b2336e635831fda78b725824ac6806936cac9f83bc5fe408a04c20dc6e9a178491366d086d3333adb5e291b790fe5069e082ef943f7cc1be8c5a9a843132a0d",
    )
    private val malformedEncodings: Map<String, String> = mapOf(
        "K-IDENTITY" to "0100000000000000000000000000000000000000000000000000000000000000",
        "K-ORDER2" to "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "K-ORDER4" to "0000000000000000000000000000000000000000000000000000000000000000",
        "K-Y-MAX" to "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "K-Y-P" to "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "K-Y-P1" to "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "S-L" to "edd3f55c1a631258d69cf7a2def9de1400000000000000000000000000000010",
        "S-L1" to "eed3f55c1a631258d69cf7a2def9de1400000000000000000000000000000010",
    )
    private const val identitySignatureHex: String = "01000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
    private const val scalarPlusLSignatureHex: String = "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69daf52db7415978abc61b2c2eb6aeebfca0387b2eaeb4302aeeb00d291612bb0c10"
    private const val abcSignatureHex: String = "c9446c82f4235087613364b1e2c31aed0ae9f11a6982859914bdd8565e119d26cd47423c26c2a49d5f60d847f75120f4bada1a01063aafef8fecb9a07e32e707"
    private val matchingB2FrameHex: String = """
        0000000300000031756b6b6572612e6f66666c696e652d696e7374616c6c6174
        696f6e2f646f63756d656e742d7369676e61747572652f76310000000b545255
        53545f53544154450000002f7b2266697874757265223a226f66666c696e652d
        7369676e61747572652d7631222c2273657175656e6365223a327d
        """
    private const val matchingB2SignatureHex: String = "760ffc2417802c0f7ed8ee467a44e7da4f9368a9adb3f4d810d6e65624e24ffd6c308062a5cc757b10691c863fe8096ee7cf2675328adcf4ed2a1bfa3722ef05"

    private const val domain: String = "ukkera.offline-installation/document-signature/v1"
    private const val changedDomain: String = "ukkera.offline-installation/document-signature/v2"
    private const val bodyB1Hex: String = "7b2266697874757265223a226f66666c696e652d7369676e61747572652d7631222c2273657175656e6365223a317d"
    private const val bodyB2Hex: String = "7b2266697874757265223a226f66666c696e652d7369676e61747572652d7631222c2273657175656e6365223a327d"

    fun bodyB1(): ByteArray = hex(bodyB1Hex)
    fun bodyB2(): ByteArray = hex(bodyB2Hex)

    fun rfc(name: String, id: String, expected: Boolean): OfflineSignatureFrozenInvocation {
        val fixture = rfc.getValue(name)
        return OfflineSignatureFrozenInvocation(id, expected, hex(fixture.key), hex(fixture.message), hex(fixture.signature))
    }

    fun compatiblePrimitiveCases(): List<OfflineSignatureFrozenInvocation> = listOf(
        rfc("F-RFC-2", "AOSV-001", true),
        rfc("F-RFC-3", "AOSV-002", true),
        rfc("F-RFC-1023", "AOSV-003", true),
        rfc("F-RFC-SHA", "AOSV-004", true),
        rfc("F-RFC-EMPTY", "AOSV-005", false),
        rfc("F-RFC-2", "AOSV-006", false).copy(publicKey = hex(rfc.getValue("F-RFC-3").key)),
        rfc("F-RFC-2", "AOSV-007", false).copy(message = hex("73")),
        rfc("F-RFC-2", "AOSV-008", false).copy(message = hex("7200")),
        rfc("F-RFC-3", "AOSV-009", false).copy(message = hex("af")),
        rfc("F-RFC-2", "AOSV-010", false).also { it.signature[0] = 0x93.toByte() },
        rfc("F-RFC-2", "AOSV-011", false).also { it.signature[32] = 0x09.toByte() },
        rfc("F-RFC-2", "AOSV-012", false).copy(signature = hex(rfc.getValue("F-RFC-3").signature)),
        rfc("F-RFC-SHA", "AOSV-013", false).copy(message = hex("616263")),
        rfc("F-RFC-SHA", "AOSV-014", true).copy(message = hex("616263"), signature = hex(abcSignatureHex)),
        rawBoundary("AOSV-015", 65_535, true),
        rawBoundary("AOSV-016", 65_536, true),
        rawBoundary("AOSV-017", 65_537, false),
        rawBoundary("AOSV-018", 65_536, false).copy(message = pattern(65_535)),
        rawBoundary("AOSV-019", 65_536, false).also { it.message[65_535] = 0xfe.toByte() },
        rfc("F-RFC-2", "AOSV-020", false).let { it.copy(publicKey = it.publicKey.copyOf(31)) },
        rfc("F-RFC-2", "AOSV-021", false).let { it.copy(publicKey = it.publicKey + byteArrayOf(0)) },
        rfc("F-RFC-2", "AOSV-022", false).copy(publicKey = byteArrayOf()),
        rfc("F-RFC-2", "AOSV-023", false).copy(publicKey = byteArrayOf(0)),
        rfc("F-RFC-2", "AOSV-024", false).let { it.copy(signature = it.signature.copyOf(63)) },
        rfc("F-RFC-2", "AOSV-025", false).let { it.copy(signature = it.signature + byteArrayOf(0)) },
        rfc("F-RFC-2", "AOSV-026", false).copy(signature = byteArrayOf()),
    )

    fun strictAndroidPrimitiveCases(): List<OfflineSignatureFrozenInvocation> = listOf(
        lowOrder("AOSV-027", "K-IDENTITY"),
        lowOrder("AOSV-028", "K-ORDER2"),
        lowOrder("AOSV-029", "K-ORDER4"),
        lowOrder("AOSV-030", "K-Y-MAX"),
        lowOrder("AOSV-031", "K-Y-P"),
        lowOrder("AOSV-032", "K-Y-P1"),
        rfc("F-RFC-2", "AOSV-033", false).also { hex(malformedEncodings.getValue("K-Y-MAX")).copyInto(it.signature) },
        rfc("F-RFC-2", "AOSV-034", false).also { hex(malformedEncodings.getValue("S-L")).copyInto(it.signature, 32) },
        rfc("F-RFC-2", "AOSV-035", false).also { hex(malformedEncodings.getValue("S-L1")).copyInto(it.signature, 32) },
        rfc("F-RFC-2", "AOSV-036", false).also { ByteArray(32) { 0xff.toByte() }.copyInto(it.signature, 32) },
        rfc("F-RFC-2", "AOSV-037", false).copy(signature = hex(scalarPlusLSignatureHex)),
        rfc("F-RFC-2", "AOSV-038", false).copy(signature = ByteArray(64)),
    )

    fun roleInvocation(role: OfflineSignatureFrozenRole): OfflineSignatureFrozenInvocation =
        OfflineSignatureFrozenInvocation(role.id, true, hex(rfc.getValue("F-RFC-2").key), hex(role.frameHex), hex(role.signatureHex))

    fun roleSubstitution(signed: OfflineSignatureFrozenRole, presented: OfflineSignatureFrozenRole): OfflineSignatureFrozenInvocation =
        OfflineSignatureFrozenInvocation("AOSV-110.${signed.name}.${presented.name}", false,
            hex(rfc.getValue("F-RFC-2").key), hex(presented.frameHex), hex(signed.signatureHex))

    fun roleSubstitutions(): List<OfflineSignatureFrozenInvocation> =
        roles.flatMap { signed -> roles.filter { it.name != signed.name }.map { presented -> roleSubstitution(signed, presented) } }

    fun changedBody(role: OfflineSignatureFrozenRole): OfflineSignatureFrozenInvocation =
        OfflineSignatureFrozenInvocation("AOSV-111.${role.name}", false, hex(rfc.getValue("F-RFC-2").key),
            frame(listOf(domain.encodeToByteArray(), role.name.encodeToByteArray(), bodyB2())), hex(role.signatureHex))

    fun matchingB2(): OfflineSignatureFrozenInvocation =
        OfflineSignatureFrozenInvocation("AOSV-112", true, hex(rfc.getValue("F-RFC-2").key),
            hex(matchingB2FrameHex), hex(matchingB2SignatureHex))

    fun changedDomain(role: OfflineSignatureFrozenRole): OfflineSignatureFrozenInvocation =
        OfflineSignatureFrozenInvocation("AOSV-113.${role.name}", false, hex(rfc.getValue("F-RFC-2").key),
            frame(listOf(changedDomain.encodeToByteArray(), role.name.encodeToByteArray(), bodyB1())), hex(role.signatureHex))

    fun bareBody(role: OfflineSignatureFrozenRole): OfflineSignatureFrozenInvocation =
        OfflineSignatureFrozenInvocation("AOSV-114.${role.name}", false, hex(rfc.getValue("F-RFC-2").key), bodyB1(), hex(role.signatureHex))

    fun changedFramingCases(): List<OfflineSignatureFrozenInvocation> {
        val source = roles.single { it.name == "SOURCE" }
        val trust = roles.single { it.name == "TRUST_STATE" }
        return listOf(
            roleInvocation(source).copy(id = "AOSV-115", expected = false).also { it.message[3] = 0x02.toByte() },
            roleInvocation(source).copy(id = "AOSV-116", expected = false,
                message = frame(listOf(source.name.encodeToByteArray(), domain.encodeToByteArray(), bodyB1()))),
            roleInvocation(trust).copy(id = "AOSV-117", expected = false,
                message = frame(listOf(domain.encodeToByteArray(), "trust_state".encodeToByteArray(), bodyB1()))),
        )
    }

    fun roleBoundaryCases(): List<OfflineSignatureFrozenInvocation> = listOf(
        roleBoundary("AOSV-120", 65_535, 65_448, true),
        roleBoundary("AOSV-121", 65_536, 65_449, true),
        roleBoundary("AOSV-122", 65_537, 65_450, false),
    )

    fun compatibleCases(): List<OfflineSignatureFrozenInvocation> =
        compatiblePrimitiveCases() + roles.map(::roleInvocation) + roleSubstitutions() + roles.map(::changedBody) +
            listOf(matchingB2()) + roles.map(::changedDomain) + roles.map(::bareBody) + changedFramingCases() + roleBoundaryCases()

    fun sequentialAndroidCases(): List<OfflineSignatureFrozenInvocation> {
        val source = roles.single { it.name == "SOURCE" }
        val trust = roles.single { it.name == "TRUST_STATE" }
        return listOf(
            rfc("F-RFC-2", "AOSV-039.1/AOSV-001", true),
            rfc("F-RFC-2", "AOSV-039.2/AOSV-007", false).copy(message = hex("73")),
            rfc("F-RFC-3", "AOSV-039.3/AOSV-002", true),
            lowOrder("AOSV-039.4/AOSV-027", "K-IDENTITY"),
            rawBoundary("AOSV-039.5/AOSV-017", 65_537, false),
            rawBoundary("AOSV-039.6/AOSV-016", 65_536, true),
            roleInvocation(source).copy(id = "AOSV-039.7/AOSV-101"),
            roleSubstitution(source, trust).copy(id = "AOSV-039.8/AOSV-110.SOURCE.TRUST_STATE"),
            rfc("F-RFC-SHA", "AOSV-039.9/AOSV-004", true),
        )
    }

    fun noOsRegistrationCases(): List<OfflineSignatureFrozenInvocation> =
        listOf(
            rfc("F-RFC-2", "AOSV-130/AOSV-001", true),
            rfc("F-RFC-3", "AOSV-130/AOSV-002", true),
            rfc("F-RFC-1023", "AOSV-130/AOSV-003", true),
            rfc("F-RFC-SHA", "AOSV-130/AOSV-004", true),
            rawBoundary("AOSV-130/AOSV-016", 65_536, true),
        ) + roles.map { roleInvocation(it).copy(id = "AOSV-130/${it.id}") }

    fun hex(value: String): ByteArray {
        val compact = value.filterNot { it.isWhitespace() }
        require(compact.length % 2 == 0)
        return ByteArray(compact.length / 2) { compact.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun keyHex(value: ByteArray): String = value.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun lowOrder(id: String, keyName: String): OfflineSignatureFrozenInvocation =
        OfflineSignatureFrozenInvocation(id, false, hex(malformedEncodings.getValue(keyName)), hex("72"), hex(identitySignatureHex))

    private fun rawBoundary(id: String, bytes: Int, expected: Boolean): OfflineSignatureFrozenInvocation =
        OfflineSignatureFrozenInvocation(id, expected, hex(rfc.getValue("F-RFC-2").key), pattern(bytes), hex(rawBoundarySignatures.getValue(bytes)))

    private fun roleBoundary(id: String, frameBytes: Int, bodyBytes: Int, expected: Boolean): OfflineSignatureFrozenInvocation {
        // Frozen J(n): 12-byte prefix + (n - 14) a bytes + 2-byte suffix.
        val prefix = "{\"fixture\":\"".encodeToByteArray()
        val suffix = "\"}".encodeToByteArray()
        val body = prefix + ByteArray(bodyBytes - 14) { 0x61.toByte() } + suffix
        val message = frame(listOf(domain.encodeToByteArray(), "EXACT_REQUEST_APPROVAL".encodeToByteArray(), body))
        require(body.size == bodyBytes && message.size == frameBytes) { "Frozen role-boundary input lengths must be exact" }
        return OfflineSignatureFrozenInvocation(id, expected, hex(rfc.getValue("F-RFC-2").key), message,
            hex(roleBoundarySignatures.getValue(frameBytes)))
    }

    private fun pattern(bytes: Int): ByteArray = ByteArray(bytes) { (it % 256).toByte() }

    /** Test-only transcription of the frozen U32/field recipe, never application framing. */
    private fun frame(fields: List<ByteArray>): ByteArray {
        val output = ByteArray(4 + fields.sumOf { 4 + it.size })
        var offset = 0
        fun u32(value: Int) {
            output[offset++] = (value ushr 24).toByte()
            output[offset++] = (value ushr 16).toByte()
            output[offset++] = (value ushr 8).toByte()
            output[offset++] = value.toByte()
        }
        u32(fields.size)
        fields.forEach { field ->
            u32(field.size)
            field.copyInto(output, offset)
            offset += field.size
        }
        return output
    }
}
