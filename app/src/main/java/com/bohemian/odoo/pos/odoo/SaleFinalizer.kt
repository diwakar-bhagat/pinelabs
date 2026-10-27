package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.core.PosLog
import com.bohemian.odoo.pos.data.SaleSnapshotDao
import com.bohemian.odoo.pos.data.SaleSnapshotEntity
import com.bohemian.odoo.pos.payment.PaymentAttempt
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One priced reward line of a stored redemption. */
@Serializable
data class SnapshotRewardPart(val taxIds: List<Int>, val amountPaise: Long, val totalInclPaise: Long, val totalExclPaise: Long)

/** ZZ Cash reward as stored with the sale, so a crash or retry sends the same reward and code. */
@Serializable
data class SnapshotRedemption(
    val cardId: Int, val rewardId: Int, val description: String,
    val pointsPaise: Long, val amountPaise: Long, val discountProductId: Int,
    val rewardCode: String,
    /** Single-rate snapshots written before the per-tax split carry one part in these fields. */
    val taxIds: List<Int> = emptyList(),
    val totalInclPaise: Long = 0, val totalExclPaise: Long = 0,
    val parts: List<SnapshotRewardPart> = emptyList(),
)

@Serializable
data class SnapshotLine(
    val uuid: String, val productId: Int, val name: String, val pricePaise: Long,
    val barcode: String? = null, val taxIds: List<Int>, val qty: Int,
    val totalInclPaise: Long, val totalExclPaise: Long,
)

@Serializable
data class SaleSnapshot(
    val orderUuid: String,
    val sessionId: Int,
    val configName: String,
    val companyName: String,
    val lines: List<SnapshotLine>,
    val totalInclPaise: Long,
    val totalExclPaise: Long,
    val customerId: Int? = null,
    val customerName: String? = null,
    val customerPhone: String? = null,
    val customerGstin: String? = null,
    val customerGstTreatment: String? = null,
    val customerStateId: Int? = null,
    val fiscalPositionId: Int? = null,
    /** Absent in snapshots written before ZZ Cash redemption existed. */
    val redemption: SnapshotRedemption? = null,
    /** ZZ Cash earned by this cart (LoyaltyPoints.earned), booked after sync. Absent in older snapshots. */
    val loyaltyEarnedPaise: Long = 0,
    val loyaltyCardId: Int? = null,
) {
    fun zzRedemption(): ZzCashRedemption? = redemption?.let {
        ZzCashRedemption(
            cardId = it.cardId,
            reward = ZzCashReward(it.rewardId, it.description, Paise(it.pointsPaise), Paise(it.amountPaise), it.discountProductId),
            rewardCode = it.rewardCode,
            parts = it.parts.map { p -> RewardPart(p.taxIds, Paise(p.amountPaise), Paise(p.totalInclPaise), Paise(p.totalExclPaise)) }
                .ifEmpty { listOf(RewardPart(it.taxIds, Paise(it.amountPaise), Paise(it.totalInclPaise), Paise(it.totalExclPaise))) },
        )
    }

    fun cartLines() = lines.map {
        CartLine(it.uuid, Product(it.productId, it.name, Paise(it.pricePaise), it.barcode, null, it.taxIds), it.qty,
            Paise(it.totalInclPaise), Paise(it.totalExclPaise))
    }
    fun totals() = CartTotals(Paise(totalInclPaise), Paise(totalExclPaise))
    fun customer() = customerId?.let {
        Customer(
            id = it, name = customerName ?: "", phone = customerPhone,
            gstin = customerGstin,
            gstTreatment = com.bohemian.odoo.pos.gst.GstTreatment.fromOdoo(customerGstTreatment),
            stateId = customerStateId,
        )
    }

    companion object {
        fun of(
            orderUuid: String,
            cfg: PosConfig,
            lines: List<CartLine>,
            totals: CartTotals,
            customer: Customer?,
            fiscalPositionId: Int? = null,
            redemption: ZzCashRedemption? = null,
            loyaltyEarned: Paise = Paise.ZERO,
            loyaltyCardId: Int? = null,
        ) = SaleSnapshot(
            orderUuid = orderUuid,
            sessionId = cfg.currentSessionId ?: error("no open session"),
            configName = cfg.name, companyName = cfg.companyName,
            lines = lines.map {
                SnapshotLine(it.uuid, it.product.id, it.product.name, it.product.price.value, it.product.barcode,
                    it.product.taxIds, it.qty, it.totalIncl!!.value, it.totalExcl!!.value)
            },
            totalInclPaise = totals.totalIncl.value, totalExclPaise = totals.totalExcl.value,
            customerId = customer?.id, customerName = customer?.name, customerPhone = customer?.phone,
            customerGstin = customer?.gstin,
            customerGstTreatment = customer?.gstTreatment?.odoo,
            customerStateId = customer?.stateId,
            fiscalPositionId = fiscalPositionId,
            redemption = redemption?.let {
                SnapshotRedemption(
                    cardId = it.cardId, rewardId = it.reward.rewardId, description = it.reward.description,
                    pointsPaise = it.reward.points.value, amountPaise = it.reward.amount.value,
                    discountProductId = it.reward.discountProductId, rewardCode = it.rewardCode,
                    parts = it.parts.map { p ->
                        SnapshotRewardPart(p.taxIds, p.amount.value,
                            requireNotNull(p.totalIncl) { "ZZ Cash reward must be priced" }.value,
                            requireNotNull(p.totalExcl) { "ZZ Cash reward must be priced" }.value)
                    },
                )
            },
            loyaltyEarnedPaise = loyaltyEarned.value,
            loyaltyCardId = loyaltyCardId,
        )
    }
}

/**
 * Turns "terminal says paid" into "Odoo has the order", whether that happens live or
 * hours later via the Reconciler. The snapshot is deleted only once the order is safely
 * in the outbox (which itself retries until Odoo confirms).
 */
class SaleFinalizer(
    private val snapshots: SaleSnapshotDao,
    private val orderSync: OrderSyncService,
    private val terminalPaymentMethodId: () -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun save(s: SaleSnapshot) = snapshots.upsert(SaleSnapshotEntity(s.orderUuid, json.encodeToString(s), clock()))

    suspend fun load(orderUuid: String): SaleSnapshot? = snapshots.get(orderUuid)?.let { json.decodeFromString<SaleSnapshot>(it.json) }

    /** Card/UPI/BQR success (live or reconciled). Returns the sale for printing, or null if no snapshot. */
    suspend fun finalizeTerminal(a: PaymentAttempt): PaidSale? {
        val snap = load(a.orderUuid) ?: run {
            PosLog.e("FINALIZE_NO_SNAPSHOT", null, "order" to a.orderUuid, "ref" to a.billingRef)
            return null
        }
        return finalize(snap, PaymentLineData(terminalPaymentMethodId(), a.amount, a.mode, a.billingRef, a.details))
    }

    suspend fun finalize(snap: SaleSnapshot, payment: PaymentLineData): PaidSale {
        val sale = PaidSale(
            orderUuid = snap.orderUuid, sessionId = snap.sessionId, lines = snap.cartLines(),
            totals = snap.totals(), customer = snap.customer(), payments = listOf(payment),
            paidAtMillis = clock(), fiscalPositionId = snap.fiscalPositionId,
            redemption = snap.zzRedemption(),
            loyaltyEarned = Paise(snap.loyaltyEarnedPaise),
            loyaltyCardId = snap.loyaltyCardId,
        )
        orderSync.submit(sale)          // outbox row written first inside submit()
        snapshots.delete(snap.orderUuid)
        return sale
    }
}
