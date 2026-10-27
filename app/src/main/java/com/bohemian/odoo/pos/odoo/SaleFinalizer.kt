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
    val notes: List<SnapshotNote> = emptyList(),
)

@Serializable
data class SnapshotNote(val text: String, val colorIndex: Int)

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
    /** loyalty.program found for the config at payment. Absent in older snapshots: their points are not booked. */
    val loyaltyProgramId: Int? = null,
    /** pos.config.default_preset_id at payment time (Pickup). */
    val presetId: Int? = null,
    /**
     * Paid in several parts from the split screen. Such an order is finalized only from that screen
     * (cash is counted at Validate) or, after a crash, once its terminal parts alone cover the total.
     */
    val split: Boolean = false,
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
            Paise(it.totalInclPaise), Paise(it.totalExclPaise), note = it.notes.map { n -> LineNote(n.text, n.colorIndex) })
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
            loyaltyProgramId: Int? = null,
            split: Boolean = false,
        ) = SaleSnapshot(
            orderUuid = orderUuid,
            sessionId = cfg.currentSessionId ?: error("no open session"),
            configName = cfg.name, companyName = cfg.companyName,
            lines = lines.map {
                SnapshotLine(it.uuid, it.product.id, it.product.name, it.product.price.value, it.product.barcode,
                    it.product.taxIds, it.qty, it.totalIncl!!.value, it.totalExcl!!.value,
                    it.note.map { n -> SnapshotNote(n.text, n.colorIndex) })
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
            loyaltyProgramId = loyaltyProgramId,
            presetId = cfg.defaultPresetId,
            split = split,
        )
    }
}

/** A split bill with terminal parts paid but not yet the whole total: it must be finished or voided. */
data class PartlyPaid(val orderUuid: String, val paid: Paise, val total: Paise) {
    val due: Paise get() = total - paid
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
    /** Paid terminal attempts of an order (the payment journal); a split bill has several. */
    private val paidAttempts: suspend (orderUuid: String) -> List<PaymentAttempt> = { emptyList() },
    private val cashPaymentMethodId: () -> Int = { 0 },
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun save(s: SaleSnapshot) = snapshots.upsert(SaleSnapshotEntity(s.orderUuid, json.encodeToString(s), clock()))

    suspend fun load(orderUuid: String): SaleSnapshot? = snapshots.get(orderUuid)?.let { json.decodeFromString<SaleSnapshot>(it.json) }

    /**
     * Card/UPI success (live or reconciled). Returns the sale for printing, or null if no snapshot.
     * A split bill is finalized here only when its terminal parts cover the whole total; while it is
     * partly paid this returns null and the split screen (or the red bar) finishes it.
     */
    suspend fun finalizeTerminal(a: PaymentAttempt): PaidSale? {
        val snap = load(a.orderUuid) ?: run {
            PosLog.e("FINALIZE_NO_SNAPSHOT", null, "order" to a.orderUuid, "ref" to a.billingRef)
            return null
        }
        if (snap.split) {
            val parts = paidAttempts(a.orderUuid)
            val paid = parts.fold(Paise.ZERO) { acc, p -> acc + p.amount }
            if (paid != snap.totals().totalIncl) {
                PosLog.i("SPLIT_PARTLY_PAID", "order" to a.orderUuid, "paid" to paid, "total" to snap.totals().totalIncl)
                return null
            }
            return finalize(snap, parts.map { it.paymentLine() })
        }
        return finalize(snap, a.paymentLine())
    }

    /**
     * Validate on the split screen: every paid terminal part, plus the cash that makes up the rest.
     * Cash is only what is still due (the change is handed back, not booked), so the payments add
     * up to Odoo's total exactly (the server refuses anything else).
     */
    suspend fun finalizeSplit(snap: SaleSnapshot): PaidSale {
        val parts = paidAttempts(snap.orderUuid)
        val terminal = parts.fold(Paise.ZERO) { acc, p -> acc + p.amount }
        val cash = snap.totals().totalIncl - terminal
        require(cash.value >= 0) { "terminal parts exceed the bill" }
        val cashLine = if (cash.isPositive) listOf(PaymentLineData(cashPaymentMethodId(), cash, null, null, null)) else emptyList()
        return finalize(snap, parts.map { it.paymentLine() } + cashLine)
    }

    /** Split bills with some terminal parts paid but not the total (red bar; they block session close, I9). */
    suspend fun partlyPaid(): List<PartlyPaid> = snapshots.all().mapNotNull { e ->
        val snap = runCatching { json.decodeFromString<SaleSnapshot>(e.json) }.getOrNull() ?: return@mapNotNull null
        val paid = paidAttempts(snap.orderUuid).fold(Paise.ZERO) { acc, p -> acc + p.amount }
        if (paid.isPositive && paid < snap.totals().totalIncl) PartlyPaid(snap.orderUuid, paid, snap.totals().totalIncl) else null
    }

    /**
     * App start: a sale the terminal fully paid whose order never reached the outbox (the app died
     * in between) is finalized now, so a payment taken is never lost (HARD RULE 7).
     */
    suspend fun recoverPaid(): Int {
        var recovered = 0
        for (e in snapshots.all()) {
            val snap = runCatching { json.decodeFromString<SaleSnapshot>(e.json) }.getOrNull() ?: continue
            val parts = paidAttempts(snap.orderUuid)
            if (parts.isEmpty() || parts.fold(Paise.ZERO) { acc, p -> acc + p.amount } != snap.totals().totalIncl) continue
            PosLog.w("RECOVER_PAID_ORDER", "order" to snap.orderUuid, "parts" to parts.size)
            finalize(snap, parts.map { it.paymentLine() })
            recovered++
        }
        return recovered
    }

    private fun PaymentAttempt.paymentLine() = PaymentLineData(terminalPaymentMethodId(), amount, mode, billingRef, details)

    suspend fun finalize(snap: SaleSnapshot, payment: PaymentLineData): PaidSale = finalize(snap, listOf(payment))

    suspend fun finalize(snap: SaleSnapshot, payments: List<PaymentLineData>): PaidSale {
        val sale = PaidSale(
            orderUuid = snap.orderUuid, sessionId = snap.sessionId, lines = snap.cartLines(),
            totals = snap.totals(), customer = snap.customer(), payments = payments,
            paidAtMillis = clock(), fiscalPositionId = snap.fiscalPositionId,
            redemption = snap.zzRedemption(),
            loyaltyEarned = Paise(snap.loyaltyEarnedPaise),
            loyaltyCardId = snap.loyaltyCardId,
            loyaltyProgramId = snap.loyaltyProgramId,
            presetId = snap.presetId,
        )
        orderSync.submit(sale)          // outbox row written first inside submit()
        snapshots.delete(snap.orderUuid)
        return sale
    }
}
