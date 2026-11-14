package com.ukkera.pos.security.frozen.androidv1baseline

import com.ukkera.pos.security.LocalCompanyAuthorityWireV1
import com.ukkera.pos.security.LocalMasterSdkOriginal
import com.ukkera.pos.security.LocalMasterSdkOriginalV1
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.security.SelfOwnedInstallationWireV1
import com.ukkera.pos.security.SelfOwnedNativeInstallationFormatV1
import com.ukkera.pos.security.SelfOwnedNativeInstallationWireV1
import com.ukkera.pos.security.SelfOwnedRegistryEnvelopeV1
import com.ukkera.pos.security.SelfOwnedTargetDescriptorV1
import com.ukkera.pos.security.SelfOwnedTargetKeyRoleV1
import com.ukkera.pos.security.frozen.androidv1baseline.AndroidV1BaselineFixture as F
import com.ukkera.pos.security.independent.masterboundpairing.MasterBoundPairingDataFixture
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Captured pre-refactor baseline: SHA-256 of the exact bytes produced by the CURRENT Android V1
 * codecs (source revision 3c6537e8) for the synthetic fixture. These were captured from the code,
 * not derived independently; the companion layout assertions below are the independent derivation.
 */
internal object CapturedAndroidV1Baseline {
    const val ROOT_DOCUMENT = "b8f9bfb6958dd2dabdd74baf336489f4be4c99d32f299b46a893f45eccf0864a"
    const val PREPARATION_DOCUMENT = "761b1571ae9ec8f48c1f873c1b8188fc3a79488c7ab94a52ff590b161025a8ce"
    const val PARTIAL_INTENT = "bd358fb092fa53f275f5d42c5ea7ab83001477909971f0f252417a97f62c5ab6"
    const val COMPLETE_INTENT = "5c1f52b4f67c57ae46401495d501426696bccd4a15a410e217efdb5ed42b27cb"
    const val KEY_BINDING_STORAGE = "511321a708aa54a52dfad0537928f7b96686c8e9d7533176bfade35941444d05"
    const val BINDING_BODY = "1dcc42169f7aba079ec0c01afa85d7b4e3ce87f482db9264985644c36f191078"
    const val BINDING_SIGNING_MESSAGE = "623a28c0d1bc1ae3a9e174d843f047ac4eb08369f79f5f589c68affbf880bb22"
    const val BINDING = "c01a24f8c339a051194645eb9cefa16f97c0f3ade7a46b83f482391ca701f8a0"
    const val JOURNAL_STATE = "89492082f119085773d4385c86e7d8c91f75957c7190ae1bd6e28c89bc250ed4"
    const val JOURNAL_AAD = "0457d6950fcddd1dc335bc0b80d63bf61bd5e1b8818efb282b79e08a1a98e308"
    const val JOURNAL_SIGNATURE = "dc2237215f003046b4e28c6e34afb05f66eb614036b72901e6a2698d64972b3a"
    const val JOURNAL_ENVELOPE = "3c03bd06a83b437f19c044e03aabca383b6d324e072ae794d64ec5fbf265bea9"
    const val PARTIAL_BOOTSTRAP = "d1de7cbf5e3d66f2f714b04712caf76dc55eb11b18a49cec8a41520e9b6ed765"
    const val COMPLETE_BOOTSTRAP = "3705840a1e22ecaa175f8c380f0bd64c81cf2e0ceb64a9c5cc8688b97ff07de1"
    const val REGISTRY_AAD = "21ad6df66b5626f09b597cd67d0d314e65b29a76621d0fdba05eee6d4624ac89"
    const val REGISTRY_SIGNATURE = "9cc49700a804a8754464ce51d8db11600968c79f29b7863b0563342844a4f23e"
    const val REGISTRY_ENVELOPE = "41c913b967617ac1d993f5ea3cf9e07211262f173289da1e9cc6bf7fcafd5447"
    const val REGISTRY_STATE = "b481df2bd17f7f6b2879a61ae865147b557e20969ed8c8c6b5c29dd45cc35da6"
    const val COMPANY_BOOTSTRAP = "9c2b8976a21333e0325dfdd725c7bc6bc631f797f785bddfed9ae064e59417b4"
    const val COMPANY_REGISTRY_AAD = "b842cc3a1a2ea6cbf7dece36da64c90362f8c4d2fda079917760cb44f4fb329d"
    const val COMPANY_ENVELOPE = "047e25c1cdeb2d90e745c18910a66757fd04880bb98debe86d655dea4bfbb64d"
    const val MASTER_SDK_ORIGINAL_V1 = "680861689a691d77956a875674bb702ab580af841279323004c8637ed70027f6"
}

/**
 * Frozen Android V1 regression golden vectors for WP-A of the desktop Master port, written before
 * the version-neutral seam refactor (WP-C). Expected bytes are composed from the DOCUMENTED layout
 * by [DocumentedLayout]; the SHA-256 constants are the captured pre-refactor baseline. Synthetic
 * DATA only. NOT independent (same author, owner instruction). Android V1 bytes must never change.
 */
class AndroidV1GoldenVectorsTest {
    private val nonce12 = F.bytes(12, 0x01)
    private val ciphertext = F.bytes(48, 0x02)
    private val signature = F.bytes(64, 0x03)

    private fun v1(domain: String) = DocumentedLayout().string(F.PROFILE).string(domain)
    private fun company(domain: String) = DocumentedLayout().string(F.COMPANY_PROFILE).string(domain).int(1)

    private fun DocumentedLayout.coordinates(value: SelfOwnedAllocationCoordinatesV1) = apply {
        id(value.allocationId); id(value.scope.organizationId); id(value.scope.companyId); id(value.scope.branchId)
        id(value.installationId); id(value.assignmentId); id(value.deviceId); id(value.storageInstanceId)
        id(value.storageKeyId); id(value.pepperKeyId); id(value.targetSigningKeyId); id(value.firstActorId)
    }
    private fun DocumentedLayout.target(value: SelfOwnedTargetDescriptorV1) = apply {
        string(value.applicationId); int(value.applicationUid); string(value.apkSignerSha256); string(value.androidIdCommitment)
    }
    private fun DocumentedLayout.targetNamespace() = apply {
        string(F.TARGET_PATH); string(F.VOLUME); int(F.target.applicationUid); long(11); long(12); long(13)
    }

    private fun pinned(expectedLayout: ByteArray, actual: ByteArray, captured: String) {
        assertContentEquals(expectedLayout, actual)
        assertEquals(captured, F.sha256Hex(actual))
    }

    @Test
    fun signedRootAndPreparationDocumentsKeepTheDocumentedV1Layout() {
        val rootBody = v1("root-announcement/v1").id(F.root).id(F.rootKey).id(F.organization).id(F.company)
            .id(F.installer).string("Synthetic Company").string("Synthetic Person").string("installer.one")
            .field(F.point(0x11)).int(0).id(F.bootstrapAction).bytes()
        assertContentEquals(rootBody, SelfOwnedInstallationWireV1.encodeRoot(F.rootBody))
        val root = F.rootDocument().copyWireBytes()
        pinned(v1("signed-document/v1").int(1).field(rootBody).field(F.bytes(64, 0x21)).bytes(), root,
            CapturedAndroidV1Baseline.ROOT_DOCUMENT)

        val preparationBody = v1("native-preparation/v1").id(F.root).string(F.sha256Hex(root)).id(F.issuer)
            .string(F.sha256Hex(F.delegationDocument().copyWireBytes())).id(F.installer).long(0).long(0)
            .coordinates(F.coordinates).long(1).field(F.bytes(32, 0x51)).target(F.target).string("Main Branch").int(0)
            .string("PREPARE_ORIGINAL_NATIVE_KEYS_ONLY_NO_ROOM_AUTHORITY").bytes()
        assertContentEquals(preparationBody, SelfOwnedInstallationWireV1.encodePreparation(F.preparationBody()))
        pinned(v1("signed-document/v1").int(3).field(preparationBody).field(F.bytes(64, 0x61)).bytes(),
            F.preparationDocument().copyWireBytes(), CapturedAndroidV1Baseline.PREPARATION_DOCUMENT)
        val decoded = SelfOwnedInstallationWireV1.decodeSigned(root)!!
        assertContentEquals(root, decoded.copyWireBytes())
    }

    private fun intentLayout(revision: Long, keys: DocumentedLayout.() -> Unit, started: Boolean): ByteArray {
        val source = F.source()
        return v1("native-target-original-intent/v1").long(revision).field(source.root.copyWireBytes())
            .field(source.delegation.copyWireBytes()).field(source.preparation.copyWireBytes()).targetNamespace()
            .field(F.bytes(32, 0x81)).bool(started).int(3).apply(keys).bytes()
    }

    @Test
    fun nativeIntentAndTargetKeyIntentsKeepTheDocumentedV1Layout() {
        val partial = SelfOwnedNativeInstallationWireV1.encodeIntent(F.partialIntent())
        pinned(intentLayout(3, {
            int(1).id(F.coordinates.targetSigningKeyId).int(2).bool(true).int(7).bool(true).int(1).field(F.bytes(65, 0x71))
            int(2).id(F.coordinates.storageKeyId).int(1).bool(true).int(8).bool(false).field(ByteArray(0))
            int(3).id(F.coordinates.pepperKeyId).int(0).bool(false).bool(false).field(ByteArray(0))
        }, false), partial, CapturedAndroidV1Baseline.PARTIAL_INTENT)
        val complete = SelfOwnedNativeInstallationWireV1.encodeIntent(F.completeIntent())
        pinned(intentLayout(6, {
            int(1).id(F.coordinates.targetSigningKeyId).int(2).bool(true).int(7).bool(true).int(1).field(F.bytes(65, 0x71))
            int(2).id(F.coordinates.storageKeyId).int(2).bool(true).int(7).bool(true).int(2).field(F.bytes(61, 0x72))
            int(3).id(F.coordinates.pepperKeyId).int(2).bool(true).int(7).bool(true).int(3).field(F.bytes(32, 0x73))
        }, true), complete, CapturedAndroidV1Baseline.COMPLETE_INTENT)
        for (bytes in listOf(partial, complete)) {
            assertContentEquals(bytes, SelfOwnedNativeInstallationWireV1.encodeIntent(SelfOwnedNativeInstallationWireV1.decodeIntent(bytes)))
            assertFails { SelfOwnedNativeInstallationWireV1.decodeIntent(bytes + byteArrayOf(0)) }
            assertFails { SelfOwnedNativeInstallationWireV1.decodeIntent(bytes.copyOf(bytes.size - 1)) }
        }
        val source = F.source()
        val keyBinding = SelfOwnedNativeInstallationWireV1.keyBinding(F.partialIntent(), SelfOwnedTargetKeyRoleV1.STORAGE)
        pinned(v1("native-target-original-key/v1").coordinates(F.coordinates).target(F.target)
            .field(source.root.copyWireBytes()).field(source.delegation.copyWireBytes()).field(source.preparation.copyWireBytes())
            .targetNamespace().field(F.bytes(32, 0x81)).int(2).id(F.coordinates.storageKeyId).int(0).bytes(),
            keyBinding, CapturedAndroidV1Baseline.KEY_BINDING_STORAGE)
    }

    @Test
    fun nativeBindingBodyAndSignedBindingKeepTheDocumentedV1Layout() {
        val body = SelfOwnedNativeInstallationWireV1.encodeBindingBody(F.bindingBody())
        val complete = SelfOwnedNativeInstallationWireV1.encodeIntent(F.completeIntent())
        pinned(v1("native-target-binding-body/v1").coordinates(F.coordinates).target(F.target)
            .string(F.sha256Hex(F.rootDocument().copyWireBytes())).string(F.sha256Hex(F.delegationDocument().copyWireBytes()))
            .string(F.sha256Hex(F.preparationDocument().copyWireBytes())).string(F.sha256Hex(complete))
            .field(F.point(0x91)).int(0).int(1).int(2).int(0)
            .string(SelfOwnedNativeInstallationFormatV1.NATIVE_ONLY_PURPOSE).bytes(), body, CapturedAndroidV1Baseline.BINDING_BODY)
        assertEquals("ORIGINAL_NATIVE_TARGET_ONLY_NO_ROOM_HISTORY_OR_WRITER_AUTHORITY",
            SelfOwnedNativeInstallationFormatV1.NATIVE_ONLY_PURPOSE)
        assertContentEquals(body, SelfOwnedNativeInstallationWireV1.encodeBindingBody(SelfOwnedNativeInstallationWireV1.decodeBindingBody(body)))
        pinned(v1("native-target-binding-proof/v1").string(SelfOwnedNativeInstallationFormatV1.NATIVE_ONLY_PURPOSE).field(body).bytes(),
            SelfOwnedNativeInstallationWireV1.bindingSigningMessage(body), CapturedAndroidV1Baseline.BINDING_SIGNING_MESSAGE)
        val binding = F.binding().copyWireBytes()
        pinned(v1("native-target-binding-signed/v1").field(body).field(F.bytes(64, 0xa1)).bytes(), binding,
            CapturedAndroidV1Baseline.BINDING)
        assertContentEquals(binding, SelfOwnedNativeInstallationWireV1.decodeBinding(binding).copyWireBytes())
        assertEquals("SelfOwnedNativeBindingV1([REDACTED])", F.binding().toString())
    }

    @Test
    fun nativeJournalStateEnvelopeAndAadKeepTheDocumentedV1Layout() {
        val binding = F.binding().copyWireBytes()
        val state = SelfOwnedNativeInstallationWireV1.encodeState(F.journalState())
        pinned(v1("native-target-journal-state/v1").long(5).bool(true).field(binding).bytes(), state,
            CapturedAndroidV1Baseline.JOURNAL_STATE)
        val decoded = SelfOwnedNativeInstallationWireV1.decodeState(state)
        assertEquals(5L, decoded.revision); assertTrue(decoded.newCeremoniesAllowed)
        assertContentEquals(state, SelfOwnedNativeInstallationWireV1.encodeState(decoded))

        val aad = SelfOwnedNativeInstallationWireV1.stateAad(F.completeIntent())
        pinned(v1("native-target-journal-aad/v1").field(SelfOwnedNativeInstallationWireV1.encodeIntent(F.completeIntent())).bytes(),
            aad, CapturedAndroidV1Baseline.JOURNAL_AAD)
        assertFails { SelfOwnedNativeInstallationWireV1.stateAad(F.partialIntent()) }
        pinned(v1("native-target-journal-signature/v1").field(aad).field(nonce12).field(ciphertext).bytes(),
            SelfOwnedNativeInstallationWireV1.stateSignature(aad, nonce12, ciphertext), CapturedAndroidV1Baseline.JOURNAL_SIGNATURE)
        val envelope = SelfOwnedNativeInstallationWireV1.encodeEnvelope(SelfOwnedRegistryEnvelopeV1(nonce12, ciphertext, signature))
        pinned(v1("native-target-journal-envelope/v1").field(nonce12).field(ciphertext).field(signature).bytes(), envelope,
            CapturedAndroidV1Baseline.JOURNAL_ENVELOPE)
        val opened = SelfOwnedNativeInstallationWireV1.decodeEnvelope(envelope)
        assertContentEquals(nonce12, opened.nonce); assertContentEquals(ciphertext, opened.ciphertext)
        assertContentEquals(signature, opened.signature)
        assertFails { SelfOwnedNativeInstallationWireV1.decodeEnvelope(envelope + byteArrayOf(0)) }
        // The provisioner registry envelope domain is distinct from the target journal envelope.
        assertFails { SelfOwnedInstallationWireV1.decodeEnvelope(envelope) }
    }

    private fun bootstrapLayout(revision: Long, keys: DocumentedLayout.() -> Unit): ByteArray =
        v1("native-bootstrap-intent/v1").long(revision).id(F.bootstrapAction).id(F.root).id(F.organization).id(F.company)
            .id(F.issuer).id(F.installer).string("Synthetic Company").string("Synthetic Person").string("installer.one")
            .int(0).target(F.target)
            // SelfOwnedRegistryNamespaceV1: path, uid, volume UUID, directory inode, lock inode.
            .string(F.PROVISIONER_PATH).int(F.target.applicationUid).string(F.VOLUME).long(21).long(22)
            .bool(false).field(F.bytes(32, 0xc1)).int(4).apply(keys).bytes()

    @Test
    fun bootstrapRegistryNamespaceAndRegistryAadKeepTheDocumentedV1Layout() {
        val partial = SelfOwnedInstallationWireV1.encodeBootstrap(F.partialBootstrap())
        pinned(bootstrapLayout(2) {
            int(1).id(F.rootSigningKey).int(2).bool(true).int(4).bool(true).int(0).field(F.bytes(65, 0xb1))
            int(2).id(F.issuerSigningKey).int(2).bool(true).int(4).bool(true).int(1).field(F.bytes(65, 0xb2))
            int(3).id(F.registryKey).int(1).bool(true).int(5).bool(false).field(ByteArray(0))
            int(4).id(F.installerPepperKey).int(0).bool(false).bool(false).field(ByteArray(0))
        }, partial, CapturedAndroidV1Baseline.PARTIAL_BOOTSTRAP)
        val complete = SelfOwnedInstallationWireV1.encodeBootstrap(F.completeBootstrap())
        pinned(bootstrapLayout(9) {
            for ((index, key) in listOf(F.rootSigningKey, F.issuerSigningKey, F.registryKey, F.installerPepperKey).withIndex())
                int(index + 1).id(key).int(2).bool(true).int(4).bool(true).int(index).field(F.bytes(65, 0xb1 + index))
        }, complete, CapturedAndroidV1Baseline.COMPLETE_BOOTSTRAP)
        for (bytes in listOf(partial, complete)) {
            assertContentEquals(bytes, SelfOwnedInstallationWireV1.encodeBootstrap(SelfOwnedInstallationWireV1.decodeBootstrap(bytes)))
            assertFails { SelfOwnedInstallationWireV1.decodeBootstrap(bytes + byteArrayOf(0)) }
            assertFails { SelfOwnedInstallationWireV1.decodeBootstrap(bytes.copyOf(bytes.size - 1)) }
        }
        assertEquals(F.registryNamespace, SelfOwnedInstallationWireV1.decodeBootstrap(partial).namespace)

        val aad = SelfOwnedInstallationWireV1.registryAad(F.partialBootstrap())
        pinned(v1("registry-aad/v1").id(F.root).id(F.registryKey).id(F.issuer).field(partial).bytes(), aad,
            CapturedAndroidV1Baseline.REGISTRY_AAD)
        pinned(v1("registry-signature/v1").field(aad).field(nonce12).field(ciphertext).bytes(),
            SelfOwnedInstallationWireV1.registrySignature(aad, nonce12, ciphertext), CapturedAndroidV1Baseline.REGISTRY_SIGNATURE)
        val envelope = SelfOwnedInstallationWireV1.encodeEnvelope(nonce12, ciphertext, signature)
        pinned(v1("encrypted-registry/v1").field(nonce12).field(ciphertext).field(signature).bytes(), envelope,
            CapturedAndroidV1Baseline.REGISTRY_ENVELOPE)
        assertContentEquals(ciphertext, SelfOwnedInstallationWireV1.decodeEnvelope(envelope).ciphertext)
    }

    @Test
    fun provisionerRegistryStateKeepsTheDocumentedV1Layout() {
        val state = SelfOwnedInstallationWireV1.encodeRegistry(F.registryState())
        val expected = v1("local-provisioner-registry/v1").long(4).field(F.rootDocument().copyWireBytes())
            .field(F.delegationDocument().copyWireBytes()).bool(true).long(2).int(1)
            .id(F.installer).string("Synthetic Installer").string("installer.one").long(0).long(0).bool(true)
            .int(600_000).field(F.bytes(32, 0xe1)).field(F.bytes(32, 0xe2)).int(0).bool(false).int(3).long(0).long(0)
            .int(1).field(SelfOwnedInstallationWireV1.encodePreparation(F.preparationBody())).int(2).bool(true)
            .field(F.preparationDocument().copyWireBytes()).bytes()
        pinned(expected, state, CapturedAndroidV1Baseline.REGISTRY_STATE)
        assertContentEquals(state, SelfOwnedInstallationWireV1.encodeRegistry(SelfOwnedInstallationWireV1.decodeRegistry(state)))
        assertFails { SelfOwnedInstallationWireV1.decodeRegistry(state + byteArrayOf(0)) }
    }

    @Test
    fun companyAuthorityBootstrapAndAadKeepTheDocumentedV1Layout() {
        val base = SelfOwnedInstallationWireV1.encodeBootstrap(F.completeBootstrap())
        val bytes = LocalCompanyAuthorityWireV1.encodeBootstrap(F.companyBootstrap())
        pinned(company("native-bootstrap").long(11).field(base).id(F.headAuthority).id(F.headEnrollment)
            .string("Synthetic Head").string("head.one").string("UNTIL_LOCAL_DISABLE").string("POLICY_PUBLISH")
            .string("FULL_D7_INSTALLATION_APPROVE").int(3)
            .string("HEAD_POLICY_SIGNING").id(F.headPolicyKey).long(0).string("COMPLETE").bool(true).int(6)
            .bool(true).string("STRONGBOX").field(F.bytes(65, 0xd0))
            .string("HEAD_INSTALLATION_SIGNING").id(F.headInstallationKey).long(0).string("STARTED").bool(true).int(6)
            .bool(false).field(ByteArray(0))
            .string("HEAD_PEPPER").id(F.headPepperKey).long(0).string("NOT_STARTED").bool(false).bool(false).field(ByteArray(0))
            .bytes(), bytes, CapturedAndroidV1Baseline.COMPANY_BOOTSTRAP)
        assertTrue(LocalCompanyAuthorityWireV1.isCompanyProfile(bytes))
        assertContentEquals(bytes, LocalCompanyAuthorityWireV1.encodeBootstrap(LocalCompanyAuthorityWireV1.decodeBootstrap(bytes)))
        assertFails { LocalCompanyAuthorityWireV1.decodeBootstrap(bytes + byteArrayOf(0)) }
        assertEquals("LocalCompanyAuthorityBootstrapV1([REDACTED])", F.companyBootstrap().toString())

        pinned(company("registry-aad").field(bytes).bytes(), LocalCompanyAuthorityWireV1.registryAad(F.companyBootstrap()),
            CapturedAndroidV1Baseline.COMPANY_REGISTRY_AAD)
        val envelope = LocalCompanyAuthorityWireV1.encodeEnvelope(SelfOwnedRegistryEnvelopeV1(nonce12, ciphertext, signature))
        pinned(company("encrypted-native-registry").field(nonce12).field(ciphertext).field(signature).bytes(), envelope,
            CapturedAndroidV1Baseline.COMPANY_ENVELOPE)
        assertContentEquals(signature, LocalCompanyAuthorityWireV1.decodeEnvelope(envelope).signature)
    }

    @Test
    fun localMasterSdkOriginalV1KeepsItsFramingAndCapturedBytes() {
        val original = MasterBoundPairingDataFixture.master()
        val bytes = original.copyWireBytes()
        val prefix = v1("original-native-master-sdk-identity-data/v1")
            .string("ORIGINAL_NATIVE_MASTER_SDK_X25519_IDENTITY_V1").id(MasterBoundPairingDataFixture.id(
                com.ukkera.pos.security.independent.masterboundpairing.SupportingPairingId.MASTER_ACQUISITION)).bytes()
        assertContentEquals(prefix, bytes.copyOf(prefix.size))
        assertEquals(CapturedAndroidV1Baseline.MASTER_SDK_ORIGINAL_V1, F.sha256Hex(bytes))
        assertEquals(F.sha256Hex(bytes), original.descriptorDigest.value)
        val decoded = LocalMasterSdkOriginal.decode(bytes)
        assertTrue(decoded is LocalMasterSdkOriginalV1)
        assertContentEquals(bytes, decoded.copyWireBytes())
        assertFails { LocalMasterSdkOriginalV1.decode(bytes + byteArrayOf(0)) }
    }
}
