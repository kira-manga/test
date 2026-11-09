package com.ukkera.pos.sales.store.independent.foodquotedaddv2

import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalFoodQuotationProfileQueryV1
import com.ukkera.pos.sales.store.LocalFoodQuotationProfileReadCodecV1
import com.ukkera.pos.sales.store.LocalFoodQuotationProfileReadResultV1
import com.ukkera.pos.sales.store.localSalesEncode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * FQADD2-001 L1 codec inputs from food-route-free-add-behavior-v2.
 * These methods exercise exact profile bytes only. They supply no protected
 * Master read, native installation, business dispatch or production settlement.
 */
public class FoodQuotedAddFrozenProfileCodecTest {
    @Test
    public fun fqadd2001CanonicalEmptyRequestHasTheExactReadKeyAndBytes(): Unit {
        val request = LocalFoodQuotationProfileReadCodecV1.read()
        assertEquals(ReadKey("sales.food.quotation.profile", 1), request.query)
        assertEquals("{}", request.payload.utf8())
        assertEquals(LocalFoodQuotationProfileQueryV1, LocalFoodQuotationProfileReadCodecV1.query(request.payload))
        assertEquals(request.payload, LocalFoodQuotationProfileReadCodecV1.encode())
    }

    @Test
    public fun fqadd2001InstalledTrueIsOneMandatoryBoolean(): Unit = assertValidResult(true, "{\"installed\":true}")

    @Test
    public fun fqadd2001InstalledFalseIsAnExplicitNegativeResult(): Unit = assertValidResult(false, "{\"installed\":false}")

    @Test
    public fun fqadd2001RequestRejectsInternalWhitespace(): Unit = rejectRequest("{ }")

    @Test
    public fun fqadd2001RequestRejectsLeadingWhitespace(): Unit = rejectRequest(" {}")

    @Test
    public fun fqadd2001RequestRejectsTrailingNewline(): Unit = rejectRequest("{}\n")

    @Test
    public fun fqadd2001RequestRejectsAnUnknownField(): Unit = rejectRequest("{\"extra\":1}")

    @Test
    public fun fqadd2001RequestRejectsAnArray(): Unit = rejectRequest("[]")

    @Test
    public fun fqadd2001RequestRejectsNull(): Unit = rejectRequest("null")

    @Test
    public fun fqadd2001RequestRejectsAnUnclosedObject(): Unit = rejectRequest("{")

    @Test
    public fun fqadd2001ResultRejectsTheMissingInstalledField(): Unit = rejectResult("{}")

    @Test
    public fun fqadd2001ResultRejectsAStringBoolean(): Unit = rejectResult("{\"installed\":\"true\"}")

    @Test
    public fun fqadd2001ResultRejectsNullInstalled(): Unit = rejectResult("{\"installed\":null}")

    @Test
    public fun fqadd2001ResultRejectsANumericBoolean(): Unit = rejectResult("{\"installed\":1}")

    @Test
    public fun fqadd2001ResultRejectsAnUnknownField(): Unit = rejectResult("{\"installed\":true,\"extra\":1}")

    @Test
    public fun fqadd2001ResultRejectsADuplicateInstalledField(): Unit =
        rejectResult("{\"installed\":true,\"installed\":false}")

    @Test
    public fun fqadd2001ResultRejectsTrailingBytes(): Unit = rejectResult("{\"installed\":true}x")

    @Test
    public fun fqadd2001ResultRejectsTheExactOversizedConstruction(): Unit {
        val text = "{\"installed\":true,\"extra\":\"" + "x".repeat(2_049) + "\"}"
        assertEquals(2_078, text.encodeToByteArray().size)
        assertEquals(2_048, LocalFoodQuotationProfileReadCodecV1.MAX_BYTES)
        rejectResult(text)
    }

    @Test
    public fun fqadd2001ResultRejectsTheExactInvalidUtf8Bytes(): Unit {
        val bytes = decodeFixtureHex("7b22696e7374616c6c6564223a747275657dff")
        assertFailsWith<IllegalArgumentException> { LocalFoodQuotationProfileReadCodecV1.result(StoredBytes(bytes)) }
    }

    @Test
    public fun fqadd2001ResultRejectsTheExactInvalidUnicodeEscape(): Unit =
        rejectResult("{\"installed\":true,\"extra\":\"\\ud800\"}")

    private fun assertValidResult(installed: Boolean, expectedBytes: String) {
        val result = LocalFoodQuotationProfileReadResultV1(installed)
        val encoded = localSalesEncode(result, LocalFoodQuotationProfileReadResultV1.serializer(), 2_048)
        assertEquals(expectedBytes, encoded.utf8())
        assertEquals(result, LocalFoodQuotationProfileReadCodecV1.result(StoredBytes(expectedBytes.encodeToByteArray())))
    }

    private fun rejectRequest(text: String) {
        assertFailsWith<IllegalArgumentException> {
            LocalFoodQuotationProfileReadCodecV1.query(StoredBytes(text.encodeToByteArray()))
        }
    }

    private fun rejectResult(text: String) {
        assertFailsWith<IllegalArgumentException> {
            LocalFoodQuotationProfileReadCodecV1.result(StoredBytes(text.encodeToByteArray()))
        }
    }

    private fun StoredBytes.utf8(): String = copyBytes().decodeToString(throwOnInvalidSequence = true)

    private fun decodeFixtureHex(hex: String): ByteArray = ByteArray(hex.length / 2) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
