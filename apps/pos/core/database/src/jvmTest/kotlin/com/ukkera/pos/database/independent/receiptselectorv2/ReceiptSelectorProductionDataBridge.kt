package com.ukkera.pos.database.independent.receiptselectorv2

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.RuntimeRetailCustomerReceiptEventClassifier
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptEventViewV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptHeadViewV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptHistoryProducer
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptPortionV1
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Test-only access to actual sales DATA serializers, canonical classifier, selected-cut
 * validator. The database test source set has a reverse dependency
 * on sales-store, not friendship with its internal declarations.
 *
 * Reflection is confined to ordinary DATA and these functions. It never constructs
 * native owners, history frames/seeds, grants, principals, writer loans or admission.
 * Canonical serialization formats independently supplied frozen values; no expected
 * quantity, time, membership or outcome is derived from a production proposal.
 */
internal object ReceiptSelectorProductionDataBridge {
    internal val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        coerceInputValues = false
        isLenient = false
    }
    private const val PACKAGE = "com.ukkera.pos.sales.store."
    private val storage = Class.forName(PACKAGE + "LocalRetailCustomerReceiptStorageV1Kt")
    private val production = Class.forName(PACKAGE + "LocalRetailCustomerReceiptProductionKt")
    private val sourceType = type("LocalRetailCustomerReceiptSaleSourceV1")
    private val originalType = type("LocalRetailCustomerReceiptOriginalSource")
    private val headType = type("LocalRetailCustomerReceiptHeadV1")
    private val eventType = type("LocalRetailCustomerReceiptEventV1")
    private val serializers = mutableMapOf<String, KSerializer<Any>>()

    internal val classifier: RuntimeRetailCustomerReceiptEventClassifier =
        LocalRetailCustomerReceiptHistoryProducer().retailCustomerReceiptEventClassifier

    internal fun canonical(typeName: String, literal: JsonObject): StoredBytes {
        val serializer = serializer(typeName)
        val value = json.decodeFromJsonElement(serializer, literal)
        return StoredBytes(json.encodeToString(serializer, value).encodeToByteArray())
    }

    internal fun original(source: JsonObject, portions: List<LocalRetailCustomerReceiptPortionV1>): Any {
        val coordinates = json.decodeFromJsonElement(serializer("LocalRetailCustomerReceiptSaleSourceV1"), source)
        return originalType.getDeclaredConstructor(sourceType, List::class.java, Set::class.java)
            .newInstance(coordinates, portions, emptySet<Any>())
    }

    internal suspend fun findHead(view: CommandReadView, original: Any): Any? {
        val method = storage.getDeclaredMethod("findRetailCustomerReceiptHead",
            CommandReadView::class.java, originalType, kotlin.coroutines.Continuation::class.java)
        return suspendCoroutine { continuation ->
            try {
                val value = method.invoke(null, view, original, continuation)
                if (value !== COROUTINE_SUSPENDED) continuation.resume(value)
            } catch (failure: InvocationTargetException) {
                continuation.resumeWithException(failure.targetException)
            } catch (failure: Throwable) {
                continuation.resumeWithException(failure)
            }
        }
    }

    internal fun headImage(cut: Any): Any = getter(cut, "getHead")

    internal fun headView(original: Any, cut: Any): LocalRetailCustomerReceiptHeadViewV1 {
        val value = getter(headImage(cut), "getValue")
        return invoke(production.getDeclaredMethod("retailCustomerReceiptHeadView", originalType, headType),
            null, original, value) as LocalRetailCustomerReceiptHeadViewV1
    }

    internal fun eventViews(cut: Any): List<LocalRetailCustomerReceiptEventViewV1> {
        val rows = getter(cut, "getEvents") as List<*>
        val method = production.getDeclaredMethod("retailCustomerReceiptEventView", eventType)
        return rows.map { row ->
            invoke(method, null, getter(checkNotNull(row), "getValue")) as LocalRetailCustomerReceiptEventViewV1
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun serializer(typeName: String): KSerializer<Any> = serializers.getOrPut(typeName) {
        val companion = type(typeName).getField("Companion").get(null)
        companion.javaClass.getDeclaredMethod("serializer").invoke(companion) as KSerializer<Any>
    }

    private fun getter(value: Any, name: String): Any =
        checkNotNull(invoke(value.javaClass.getDeclaredMethod(name), value))

    private fun invoke(method: Method, receiver: Any?, vararg arguments: Any?): Any? = try {
        method.invoke(receiver, *arguments)
    } catch (failure: InvocationTargetException) {
        throw failure.targetException
    }

    private fun type(name: String): Class<*> = Class.forName(PACKAGE + name)
}
