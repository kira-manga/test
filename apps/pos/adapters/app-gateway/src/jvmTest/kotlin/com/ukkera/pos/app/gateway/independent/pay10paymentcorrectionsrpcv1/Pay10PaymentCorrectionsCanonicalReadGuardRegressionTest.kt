package com.ukkera.pos.app.gateway.independent.pay10paymentcorrectionsrpcv1

import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.gateway.GatewayFailure
import com.ukkera.pos.app.gateway.LocalWorkflowInstallation
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalPaymentCorrectionCodec
import com.ukkera.pos.sales.store.LocalPaymentCorrectionQueryV1
import com.ukkera.pos.types.DomainId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame

/**
 * PCUI-04–07 canonical read-guard regression only. The real bounded installation
 * classifies requests without any endpoint, data port or native service. This
 * does not qualify current Master permissions, RPC transport or durable history.
 */
class Pay10PaymentCorrectionsCanonicalReadGuardRegressionTest {
    private val saleId = DomainId("81000000-0000-4000-8000-000000000001")
    private val paymentId = DomainId("81000000-0000-4000-8000-000000000002")
    private val beforeId = DomainId("81000000-0000-4000-8000-000000000003")

    @Test
    fun pcui04To06CanonicalSourceAndHistoryReadsUseValuesNotRequestIdentity() {
        val installation = paymentCorrectionsInstallation()
        val queries = listOf(
            LocalPaymentCorrectionQueryV1.Source(saleId),
            LocalPaymentCorrectionQueryV1.History(saleId, paymentId),
            LocalPaymentCorrectionQueryV1.History(saleId, paymentId, limit = 1),
            LocalPaymentCorrectionQueryV1.History(saleId, paymentId, before = beforeId, limit = 1),
            LocalPaymentCorrectionQueryV1.History(saleId, paymentId, before = beforeId, limit = 5),
        )

        queries.forEach { query ->
            val canonical = LocalPaymentCorrectionCodec.request(query)
            val rebuilt = ReadRequest(
                ReadKey(canonical.query.name, canonical.query.version),
                StoredBytes(canonical.payload.copyBytes()),
            )
            assertNotSame(canonical, rebuilt)
            assertNotSame(canonical.query, rebuilt.query)
            assertNotSame(canonical.payload, rebuilt.payload)
            assertEquals(canonical.query, rebuilt.query)
            assertEquals(canonical.payload, rebuilt.payload)
            assertEquals(query, LocalPaymentCorrectionCodec.decodeQuery(rebuilt.payload))

            installation.requireRead(canonical)
            installation.requireRead(rebuilt)
        }
    }

    @Test
    fun pcui07MalformedNoncanonicalAndMixedReadSelectionsAreInvalid() {
        val installation = paymentCorrectionsInstallation()
        val source = queryText(LocalPaymentCorrectionQueryV1.Source(saleId))
        val history = queryText(LocalPaymentCorrectionQueryV1.History(saleId, paymentId))
        val malformed = listOf(
            "empty body" to "",
            "truncated source" to source.dropLast(1),
            "leading whitespace" to " $source",
            "trailing whitespace" to "$history\n",
            "unknown source field" to source.withFields("\"ignored\":true"),
            "source search plus reference" to source.withFields(
                "\"search\":\"payment-correction:${saleId.value}\",\"reference\":{" +
                    "\"kind\":\"PAYMENT_CORRECTION_SOURCE\",\"id\":\"${saleId.value}\"}",
            ),
            "source parent coordinate" to source.withFields("\"parentId\":\"${saleId.value}\""),
            "source revision coordinate" to source.withFields("\"revision\":4"),
            "source with history coordinates" to source.withFields(
                "\"paymentAttemptId\":\"${paymentId.value}\",\"before\":null,\"limit\":5",
            ),
            "history search plus reference" to history.withFields(
                "\"search\":\"payment-correction:${saleId.value}\",\"reference\":{" +
                    "\"kind\":\"PAYMENT_CORRECTION_HISTORY\",\"id\":\"${paymentId.value}\"," +
                    "\"parentId\":\"${saleId.value}\"}",
            ),
            "history revision coordinate" to history.withFields("\"revision\":4"),
            "history missing original sale" to history.replace("\"saleId\":\"${saleId.value}\",", ""),
            "history missing original payment" to history.replace("\"paymentAttemptId\":\"${paymentId.value}\",", ""),
            "history omitted canonical defaults" to history.replace(",\"before\":null,\"limit\":5", ""),
            "history limit below bound" to history.replace("\"limit\":5", "\"limit\":0"),
            "history limit above bound" to history.replace("\"limit\":5", "\"limit\":6"),
            "unrelated workflow body" to "{\"workflow\":\"CHECKOUT\",\"orderId\":\"${saleId.value}\"}",
        )

        malformed.forEach { (description, text) ->
            val failure = assertFailsWith<GatewayFailure>(description) {
                installation.requireRead(ReadRequest(
                    LocalPaymentCorrectionCodec.QUERY,
                    StoredBytes(text.encodeToByteArray()),
                ))
            }
            assertEquals(LocalServiceProblem.INVALID_INPUT, failure.problem, description)
        }
    }

    @Test
    fun pcui07WrongReadNamesAndVersionsStayUninstalled() {
        val installation = paymentCorrectionsInstallation()
        val payloads = listOf(
            LocalPaymentCorrectionCodec.request(LocalPaymentCorrectionQueryV1.Source(saleId)).payload,
            LocalPaymentCorrectionCodec.request(LocalPaymentCorrectionQueryV1.History(saleId, paymentId)).payload,
        )
        val wrongKeys = listOf(
            ReadKey("sales.checkout.read", 1),
            ReadKey(LocalPaymentCorrectionCodec.COMMAND.name, LocalPaymentCorrectionCodec.COMMAND.version),
            ReadKey(LocalPaymentCorrectionCodec.QUERY.name, LocalPaymentCorrectionCodec.QUERY.version + 1),
        )

        wrongKeys.forEach { key ->
            payloads.forEach { payload ->
                val failure = assertFailsWith<GatewayFailure>("uninstalled read key $key") {
                    installation.requireRead(ReadRequest(key, StoredBytes(payload.copyBytes())))
                }
                assertEquals(LocalServiceProblem.NOT_IMPLEMENTED, failure.problem, "uninstalled read key $key")
            }
        }
    }

    private fun paymentCorrectionsInstallation(): LocalWorkflowInstallation =
        LocalWorkflowInstallation.directoryOnly(LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS)

    private fun queryText(query: LocalPaymentCorrectionQueryV1): String =
        LocalPaymentCorrectionCodec.request(query).payload.copyBytes().decodeToString()

    private fun String.withFields(fields: String): String {
        require(endsWith("}"))
        return dropLast(1) + "," + fields + "}"
    }
}
