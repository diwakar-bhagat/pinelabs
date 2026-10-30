package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.gst.GstTreatment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal

/** One line of an open bill: the product and what the cashier set on it, never a price. */
data class ParkedLine(val uuid: String, val productId: Int, val qty: Int, val note: List<LineNote> = emptyList())

/**
 * An open bill in the till's queue (the web POS's "+" and order tabs): only what the cashier did.
 * Prices, offer lines, the ZZ Cash balance and the fiscal position are worked out again when it is
 * opened, so a waiting bill never keeps an old price (I2). Kept on the till only (`parked_bill`);
 * Odoo sees the bill once it is paid, under the same [orderUuid].
 */
data class ParkedBill(
    val orderUuid: String,
    /** "Bill 3" when it has no customer. */
    val number: Int,
    val createdAt: Long,
    val lines: List<ParkedLine>,
    val customer: Customer? = null,
    val offers: OfferState = OfferState(),
    /** The ZZ Cash reward the cashier chose; applied again once the card's balance is read. */
    val zzReward: ZzCashReward? = null,
) {
    val itemCount: Int get() = lines.sumOf { it.qty }

    /** The chip's name: the customer's first name, else "Bill 3". */
    val label: String get() = labelFor(customer, number)

    /** Nothing on it worth keeping. */
    val isEmpty: Boolean get() = lines.isEmpty() && customer == null

    /** The cart lines again, from today's catalogue; lines whose product is gone or held come back in [Restored.dropped]. */
    fun restore(products: Map<Int, Product>, held: (Int) -> Boolean): Restored {
        val kept = lines.mapNotNull { l -> products[l.productId]?.takeIf { !held(it.id) }?.let { CartLine(l.uuid, it, l.qty, note = l.note) } }
        return Restored(kept, lines.size - kept.size)
    }

    data class Restored(val lines: List<CartLine>, val dropped: Int)

    fun toJson(): String = jsonOf(
        "v" to 1,
        "orderUuid" to orderUuid,
        "number" to number,
        "createdAt" to createdAt,
        "lines" to lines.map { l ->
            mapOf("uuid" to l.uuid, "productId" to l.productId, "qty" to l.qty,
                "note" to l.note.map { mapOf("text" to it.text, "colorIndex" to it.colorIndex) })
        },
        "customer" to customer?.let { cu ->
            mapOf("id" to cu.id, "name" to cu.name, "phone" to cu.phone, "gstin" to cu.gstin, "gstTreatment" to cu.gstTreatment?.odoo,
                "stateId" to cu.stateId, "stateName" to cu.stateName, "fiscalPositionId" to cu.fiscalPositionId)
        },
        "offers" to mapOf(
            "codes" to offers.codes.toList(),
            "claims" to offers.claims.map { mapOf("rewardId" to it.rewardId, "couponKey" to it.couponKey, "productId" to it.productId,
                "code" to it.code, "auto" to it.auto) },
            "disabled" to offers.disabled.toList(),
            "scanned" to offers.scanned.map { mapOf("cardId" to it.cardId, "programId" to it.programId,
                "points" to it.points.toPlainString(), "code" to it.code) },
        ),
        "zzReward" to zzReward?.let { r ->
            mapOf("rewardId" to r.rewardId, "description" to r.description, "points" to r.points.value,
                "amount" to r.amount.value, "discountProductId" to r.discountProductId)
        },
    ).toString()

    companion object {
        fun labelFor(customer: Customer?, number: Int): String =
            customer?.name?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() } ?: "Bill $number"

        /** Null when the text is not a bill this build can read (it is then dropped, never half-restored). */
        fun fromJson(text: String): ParkedBill? = runCatching {
            val o = Json.parseToJsonElement(text).jsonObject
            ParkedBill(
                orderUuid = o.str("orderUuid")!!,
                number = o.int("number") ?: 0,
                createdAt = o.long("createdAt"),
                lines = (o["lines"] as JsonArray).map { e ->
                    val l = e.jsonObject
                    ParkedLine(l.str("uuid")!!, l.int("productId")!!, l.int("qty")!!,
                        (l["note"] as? JsonArray)?.map { n -> n.jsonObject.let { LineNote(it.str("text") ?: "", it.int("colorIndex") ?: 0) } }.orEmpty())
                },
                customer = (o["customer"] as? JsonObject)?.let { cu ->
                    Customer(cu.int("id")!!, cu.str("name") ?: "", cu.str("phone"), cu.str("gstin"),
                        cu.str("gstTreatment")?.let { t -> GstTreatment.entries.firstOrNull { it.odoo == t } },
                        cu.int("stateId"), cu.str("stateName"), cu.int("fiscalPositionId"))
                },
                offers = (o["offers"] as? JsonObject)?.let { f ->
                    OfferState(
                        codes = f.intList("codes").toSet(),
                        claims = (f["claims"] as? JsonArray)?.map { e ->
                            e.jsonObject.let { Claim(it.int("rewardId")!!, it.int("couponKey")!!, it.int("productId"), it.str("code")!!, it.bool("auto")) }
                        }.orEmpty(),
                        disabled = f.intList("disabled").toSet(),
                        scanned = (f["scanned"] as? JsonArray)?.map { e ->
                            e.jsonObject.let { ScannedCoupon(it.int("cardId")!!, it.int("programId")!!, BigDecimal(it.str("points")!!), it.str("code")!!) }
                        }.orEmpty(),
                    )
                } ?: OfferState(),
                zzReward = (o["zzReward"] as? JsonObject)?.let { r ->
                    ZzCashReward(r.int("rewardId")!!, r.str("description") ?: "", Paise(r.long("points")), Paise(r.long("amount")),
                        r.int("discountProductId")!!)
                },
            )
        }.getOrNull()

        private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
        private fun JsonObject.intList(key: String): List<Int> = ids(key)
    }
}
