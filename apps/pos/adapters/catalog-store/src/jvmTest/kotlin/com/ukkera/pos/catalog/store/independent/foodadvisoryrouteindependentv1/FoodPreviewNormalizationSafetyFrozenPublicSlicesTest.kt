package com.ukkera.pos.catalog.store.independent.foodadvisoryrouteindependentv1

import com.ukkera.pos.catalog.store.CompanyPublicationSignatureVerifier
import com.ukkera.pos.catalog.store.LocalCatalogFoodSource
import com.ukkera.pos.catalog.store.LocalCatalogFoodStockSource
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.sales.store.LocalFoodCodecV1
import com.ukkera.pos.sales.store.LocalFoodPortionSelectionV1
import com.ukkera.pos.sales.store.LocalFoodRequestV1
import com.ukkera.pos.sales.store.LocalFoodSelectionPreviewReadHandler
import com.ukkera.pos.sales.store.LocalFoodServiceV1
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.sales.store.TransactionFoodRecipeSourceV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Bounded public wiring of food-preview-normalization-safety-v1: 22 variants.
 * Each raw transform is built independently from untouched existing codec bytes,
 * never from the preview normalizer or a repaired malformed input. Both production
 * catalog source compositions exercise only the preview normalizer.
 *
 * HISTORY-FORMATTING-UNBOUND has no invented accepting/rejecting test. The durable
 * saved/pending/history preservation part of CANONICAL-COMPATIBILITY is UNBOUND,
 * as are real Available digests, protected raw ingress, authorization, source
 * preparation, Master, native persistence, RPC and all durable business deltas.
 */
class FoodPreviewNormalizationSafetyFrozenPublicSlicesTest {
    private val f = FrozenFoodPreviewNormalizationInputs

    @Test
    fun fpn01CreateJsonWhitespaceKeepsCanonicalBytesValuesAndReferenceDigest() {
        assertFormattingPreserves(f.create())
    }

    @Test
    fun fpn01AddJsonWhitespaceKeepsCanonicalBytesValuesAndReferenceDigest() {
        assertFormattingPreserves(f.add())
    }

    @Test
    fun fpn02SplitNumberIsRejectedWithoutTokenRepair() {
        assertPreviewRejects(rejectedInput("SPLIT-NUMBER"))
    }

    @Test
    fun fpn02SplitNullIsRejectedWithoutTokenRepair() {
        assertPreviewRejects(rejectedInput("SPLIT-NULL"))
    }

    @Test
    fun fpn02TrailingCommaIsRejected() {
        assertPreviewRejects(rejectedInput("TRAILING-COMMA"))
    }

    @Test
    fun fpn02MissingPortionCommaIsRejected() {
        assertPreviewRejects(rejectedInput("MISSING-COMMA"))
    }

    @Test
    fun fpn02UnterminatedItemNoteIsRejected() {
        assertPreviewRejects(rejectedInput("UNTERMINATED-STRING"))
    }

    @Test
    fun fpn02IllegalBackslashQEscapeIsRejected() {
        assertPreviewRejects(rejectedInput("INVALID-ESCAPE"))
    }

    @Test
    fun fpn02BadUtf8ContinuationIsRejectedWithoutReplacement() {
        assertPreviewRejects(rejectedInput("UTF8-BAD-CONTINUATION"))
    }

    @Test
    fun fpn02OverlongUtf8IsRejectedWithoutReplacement() {
        assertPreviewRejects(rejectedInput("UTF8-OVERLONG"))
    }

    @Test
    fun fpn02NonJsonNbspIsRejected() {
        assertPreviewRejects(rejectedInput("NONJSON-NBSP"))
    }

    @Test
    fun fpn02NonJsonVerticalTabIsRejected() {
        assertPreviewRejects(rejectedInput("NONJSON-VT"))
    }

    @Test
    fun fpn02Exactly16385RawBytesAreRejectedBeforeTrimming() {
        val raw = rejectedInput("RAW-OVER-BOUND")
        assertEquals(16_385, raw.size)
        assertPreviewRejects(raw)
    }

    @Test
    fun fpn02Exactly241AsciiItemNoteCharactersAreRejectedWithoutClipping() {
        assertPreviewRejects(rejectedInput("ITEM-NOTE-OVER-BOUND"))
    }

    @Test
    fun fpn02EscapedControlItemNoteIsRejectedWithoutDeletion() {
        assertPreviewRejects(rejectedInput("ESCAPED-CONTROL-NOTE"))
    }

    @Test
    fun fpn02RevisionContentSpaceIsRejectedWithoutTrimming() {
        assertPreviewRejects(rejectedInput("REVISION-CONTENT-SPACE"))
    }

    @Test
    fun fpn03UnknownActorFieldIsRejectedWithoutDropping() {
        assertPreviewRejects(rejectedInput("UNKNOWN-FIELD"))
    }

    @Test
    fun fpn03MissingRequiredRevisionIsRejectedWithoutDefault() {
        assertPreviewRejects(rejectedInput("REQUIRED-FIELD-MISSING"))
    }

    @Test
    fun fpn03ExactQuotesBackslashUnicodeAndSpacesSurviveFormatting() {
        val request = f.add().let { original ->
            original.copy(portions = original.portions.map { it.copy(itemNote = f.contentNote) })
        }
        assertFormattingPreserves(request)
    }

    @Test
    fun fpn03ReversedPortionListAndChangedReferenceDigestSurviveFormatting() {
        val original = f.add()
        val reversed = original.copy(portions = original.portions.reversed())
        assertFormattingPreserves(reversed)
        assertNotEquals(referenceSha256(LocalFoodCodecV1.encode(original)),
            referenceSha256(LocalFoodCodecV1.encode(reversed)))
    }

    @Test
    fun fpn03ChangedRevisionAndQuantityValuesSurviveFormatting() {
        val original = f.add()
        val changed = original.copy(expectedOrderRevision = Revision(8),
            portions = original.portions.map { it.copy(quantity = Quantity(f.unitId, 2, 0)) })
        assertFormattingPreserves(changed)
        assertNotEquals(referenceSha256(LocalFoodCodecV1.encode(original)),
            referenceSha256(LocalFoodCodecV1.encode(changed)))
        // Typed 8/2 preservation does not grant a current revision or source price.
    }

    @Test
    fun fpn04UntouchedCanonicalStrictCompatibilityAndMalformedRejectionStayStable() {
        for (request in listOf(f.create(), f.add())) {
            val canonical = LocalFoodCodecV1.encode(request)
            val decoded = LocalFoodCodecV1.decode(canonical)
            assertEquals(request, decoded)
            assertEquals(canonical, LocalFoodCodecV1.encode(decoded))
            assertEquals(canonical, LocalFoodCodecV1.normalize(canonical))
        }
        for (variant in REJECTED_VARIANTS) {
            val raw = rejectedInput(variant)
            assertFailsWith<IllegalArgumentException>(variant) { LocalFoodCodecV1.decode(raw) }
            assertFailsWith<IllegalArgumentException>(variant) { LocalFoodCodecV1.normalize(raw) }
        }
        // No formatting-only history policy or durable identity preservation asserted.
    }

    private fun assertFormattingPreserves(request: LocalFoodRequestV1) {
        val canonical = LocalFoodCodecV1.encode(request)
        assertTrue(canonical.size <= 16_384)
        val expanded = StoredBytes(jsonWhitespace(canonical.copyBytes()))
        assertTrue(expanded.size <= 16_384)
        assertNotEquals(canonical, expanded)
        val expectedDigest = referenceSha256(canonical)
        assertNotEquals(expectedDigest, referenceSha256(expanded))
        for (handler in previewNormalizers()) {
            val normalized = handler.normalize(expanded)
            assertEquals(canonical, normalized)
            assertEquals(request, LocalFoodCodecV1.decode(normalized))
            assertEquals(expectedDigest, referenceSha256(normalized))
            assertEquals(canonical, handler.normalize(normalized))
        }
    }

    private fun assertPreviewRejects(raw: StoredBytes) {
        for (handler in previewNormalizers()) {
            assertFailsWith<IllegalArgumentException> { handler.normalize(raw) }
        }
    }

    /** Token locations belong only to an untouched valid C_ADD, never mutated input. */
    private fun rejectedInput(variant: String): StoredBytes {
        val canonical = LocalFoodCodecV1.encode(f.add()).copyBytes()
        assertTrue(canonical.size <= 16_384)
        val tokens = CanonicalFixtureTokenView(canonical)
        fun replaceValue(path: String, raw: String): ByteArray =
            replaceSpan(canonical, tokens.value(path), raw.encodeToByteArray())
        fun corruptFirstNoteN(raw: ByteArray): ByteArray {
            val note = tokens.value("$.portions[0].itemNote")
            val index = (note.start + 1 until note.endExclusive - 1).first { canonical[it] == 'n'.code.toByte() }
            return replaceSpan(canonical, FixtureByteSpan(index, index + 1), raw)
        }
        val raw = when (variant) {
            "SPLIT-NUMBER" -> replaceValue("$.expectedOrderRevision", "1 0")
            "SPLIT-NULL" -> replaceValue("$.portions[1].lineId", "n ull")
            "TRAILING-COMMA" -> {
                val close = tokens.value("$").endExclusive - 1
                check(canonical[close] == '}'.code.toByte())
                replaceSpan(canonical, FixtureByteSpan(close, close), byteArrayOf(','.code.toByte()))
            }
            "MISSING-COMMA" -> {
                val comma = tokens.value("$.portions[0]").endExclusive
                check(canonical[comma] == ','.code.toByte() &&
                    tokens.value("$.portions[1]").start == comma + 1)
                replaceSpan(canonical, FixtureByteSpan(comma, comma + 1), byteArrayOf())
            }
            "UNTERMINATED-STRING" -> {
                val close = tokens.value("$.portions[0].itemNote").endExclusive - 1
                check(canonical[close] == '"'.code.toByte())
                replaceSpan(canonical, FixtureByteSpan(close, close + 1), byteArrayOf())
            }
            "INVALID-ESCAPE" -> replaceValue("$.portions[0].itemNote", "\"bad\\q\"")
            "UTF8-BAD-CONTINUATION" -> corruptFirstNoteN(byteArrayOf(0xc3.toByte(), 0x28))
            "UTF8-OVERLONG" -> corruptFirstNoteN(byteArrayOf(0xc0.toByte(), 0xaf.toByte()))
            "NONJSON-NBSP" -> byteArrayOf(0xc2.toByte(), 0xa0.toByte()) + canonical
            "NONJSON-VT" -> byteArrayOf(0x0b) + canonical
            "RAW-OVER-BOUND" -> ByteArray(16_385 - canonical.size) { 0x20 } + canonical
            "ITEM-NOTE-OVER-BOUND" -> replaceValue("$.portions[0].itemNote", "\"" + "x".repeat(241) + "\"")
            "ESCAPED-CONTROL-NOTE" -> replaceValue("$.portions[0].itemNote", "\"keep\\u0001\"")
            "REVISION-CONTENT-SPACE" -> replaceValue("$.expectedOrderRevision", "\"7 \"")
            "UNKNOWN-FIELD" -> {
                check(canonical[0] == '{'.code.toByte())
                replaceSpan(canonical, FixtureByteSpan(1, 1),
                    "\"forgedActorId\":\"b0000000-0000-4000-8000-000000000001\",".encodeToByteArray())
            }
            "REQUIRED-FIELD-MISSING" -> replaceSpan(canonical,
                tokens.memberWithAdjacentComma("$.expectedOrderRevision"), byteArrayOf())
            else -> error("No frozen rejected variant: $variant")
        }
        return StoredBytes(raw)
    }

    /** Exact JSON_WS transform: 20/09/0a/0d around C and after structural tokens. */
    private fun jsonWhitespace(canonical: ByteArray): ByteArray {
        val whitespace = byteArrayOf(0x20, 0x09, 0x0a, 0x0d).toList()
        val result = ArrayList<Byte>()
        result.addAll(whitespace)
        var quoted = false
        var escaped = false
        for (byte in canonical) {
            result.add(byte)
            if (quoted) {
                if (escaped) escaped = false
                else if (byte == '\\'.code.toByte()) escaped = true
                else if (byte == '"'.code.toByte()) quoted = false
            } else when (byte.toInt()) {
                '"'.code -> quoted = true
                '{'.code, '['.code, ':'.code, ','.code -> result.addAll(whitespace)
            }
        }
        check(!quoted && !escaped)
        result.addAll(whitespace)
        return result.toByteArray()
    }

    private fun previewNormalizers(): List<MasterReadHandler> {
        val unreadSignatures = CompanyPublicationSignatureVerifier { _, _, _, _ ->
            error("Public normalization must not reach source signature verification")
        }
        return listOf<TransactionFoodRecipeSourceV1>(
            LocalCatalogFoodSource(unreadSignatures),
            LocalCatalogFoodStockSource(unreadSignatures),
        ).map { source -> LocalFoodSelectionPreviewReadHandler.withStock(LocalStockRepository(), source) }
    }

    private fun referenceSha256(bytes: StoredBytes): List<Byte> =
        MessageDigest.getInstance("SHA-256").digest(bytes.copyBytes()).toList()

    private companion object {
        val REJECTED_VARIANTS = listOf("SPLIT-NUMBER", "SPLIT-NULL", "TRAILING-COMMA", "MISSING-COMMA",
            "UNTERMINATED-STRING", "INVALID-ESCAPE", "UTF8-BAD-CONTINUATION", "UTF8-OVERLONG",
            "NONJSON-NBSP", "NONJSON-VT", "RAW-OVER-BOUND", "ITEM-NOTE-OVER-BOUND",
            "ESCAPED-CONTROL-NOTE", "REVISION-CONTENT-SPACE", "UNKNOWN-FIELD", "REQUIRED-FIELD-MISSING")
    }
}

private data class FixtureByteSpan(val start: Int, val endExclusive: Int)

private fun replaceSpan(base: ByteArray, span: FixtureByteSpan, replacement: ByteArray): ByteArray =
    base.copyOfRange(0, span.start) + replacement + base.copyOfRange(span.endExclusive, base.size)

/** Independent location view over an untouched canonical fixture; not a validity/result oracle. */
private class CanonicalFixtureTokenView(private val canonical: ByteArray) {
    private var position = 0
    private val values = mutableMapOf<String, FixtureByteSpan>()
    private val members = mutableMapOf<String, FixtureByteSpan>()

    init {
        readValue("$")
        check(position == canonical.size)
    }

    fun value(path: String): FixtureByteSpan = checkNotNull(values[path])

    fun memberWithAdjacentComma(path: String): FixtureByteSpan {
        val member = checkNotNull(members[path])
        return when {
            canonical[member.endExclusive] == ','.code.toByte() ->
                FixtureByteSpan(member.start, member.endExclusive + 1)
            member.start > 0 && canonical[member.start - 1] == ','.code.toByte() ->
                FixtureByteSpan(member.start - 1, member.endExclusive)
            else -> error("Frozen member has no adjacent comma")
        }
    }

    private fun readValue(path: String): FixtureByteSpan {
        val start = position
        when (canonical[position].toInt()) {
            '{'.code -> readObject(path)
            '['.code -> readArray(path)
            '"'.code -> readQuoted()
            else -> while (position < canonical.size &&
                canonical[position].toInt() !in listOf(','.code, ']'.code, '}'.code)) position++
        }
        check(position > start)
        return FixtureByteSpan(start, position).also { values[path] = it }
    }

    private fun readObject(path: String) {
        check(canonical[position++] == '{'.code.toByte())
        if (canonical[position] == '}'.code.toByte()) { position++; return }
        while (true) {
            val key = readQuoted()
            val name = canonical.copyOfRange(key.start + 1, key.endExclusive - 1).decodeToString()
            check('\\' !in name && canonical[position++] == ':'.code.toByte())
            val memberPath = "$path.$name"
            val value = readValue(memberPath)
            members[memberPath] = FixtureByteSpan(key.start, value.endExclusive)
            when (canonical[position++].toInt()) {
                ','.code -> Unit
                '}'.code -> return
                else -> error("Unexpected canonical object separator")
            }
        }
    }

    private fun readArray(path: String) {
        check(canonical[position++] == '['.code.toByte())
        if (canonical[position] == ']'.code.toByte()) { position++; return }
        var index = 0
        while (true) {
            readValue("$path[${index++}]")
            when (canonical[position++].toInt()) {
                ','.code -> Unit
                ']'.code -> return
                else -> error("Unexpected canonical array separator")
            }
        }
    }

    private fun readQuoted(): FixtureByteSpan {
        val start = position
        check(canonical[position++] == '"'.code.toByte())
        var escaped = false
        while (position < canonical.size) {
            val byte = canonical[position++]
            if (escaped) escaped = false
            else if (byte == '\\'.code.toByte()) escaped = true
            else if (byte == '"'.code.toByte()) return FixtureByteSpan(start, position)
        }
        error("Unterminated untouched canonical fixture string")
    }
}

/** Exact supplemental frozen typed facts, using a constructor-only synthetic scale-0 UOM. */
private object FrozenFoodPreviewNormalizationInputs {
    val orderId = DomainId("f0000000-0000-4000-8000-000000000001")
    val firstPortionId = DomainId("f3000000-0000-4000-8000-000000000001")
    val secondPortionId = DomainId("f3000000-0000-4000-8000-000000000002")
    val itemId = DomainId("f1000000-0000-4000-8000-000000000001")
    val recipeId = DomainId("f2000000-0000-4000-8000-000000000001")
    val choiceId = DomainId("f1000000-0000-4000-8000-000000000002")
    val cheeseId = DomainId("f1000000-0000-4000-8000-000000000003")
    // The literal quantity fixture supplies no signed/native UOM identity.
    val unitId = DomainId("f6000000-0000-4000-8000-000000000001")
    val contentNote = "say \"keep  spaces\" \\ no\u00a0salt / بدون ملح "
    val service = LocalFoodServiceV1(LocalSalesChannel.DINE_IN, "T-02", "")

    private fun portions(): List<LocalFoodPortionSelectionV1> = listOf(
        LocalFoodPortionSelectionV1(firstPortionId, itemId, recipeId, Revision(1), choiceId,
            listOf(cheeseId), Quantity(unitId, 1, 0), "no salt / بدون ملح", null, "Seat 02 / المقعد 02"),
        LocalFoodPortionSelectionV1(secondPortionId, itemId, recipeId, Revision(1), choiceId,
            listOf(cheeseId), Quantity(unitId, 1, 0), "no salt / بدون ملح", firstPortionId, "Seat 02 / المقعد 02"),
    )

    fun create(): LocalFoodRequestV1.Create = LocalFoodRequestV1.Create(orderId,
        LocalSalesActivity.RESTAURANT, service, Revision(5), portions())

    fun add(): LocalFoodRequestV1.Add = LocalFoodRequestV1.Add(orderId, Revision(7), Revision(5), portions())
}
