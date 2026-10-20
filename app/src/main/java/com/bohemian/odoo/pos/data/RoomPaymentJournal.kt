package com.bohemian.odoo.pos.data

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.payment.AttemptState
import com.bohemian.odoo.pos.payment.PaymentAttempt
import com.bohemian.odoo.pos.payment.PaymentJournal
import com.bohemian.odoo.pos.pine.PayMode
import com.bohemian.odoo.pos.pine.TxnDetails
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class DetailsDto(
    val transactionId: String? = null, val rrn: String? = null, val authCode: String? = null,
    val last4: String? = null, val cardType: String? = null, val acquirer: String? = null,
    val batchNo: String? = null, val terminalId: String? = null, val responseCode: String? = null,
    val responseMessage: String? = null, val amountPaise: Long? = null, val simulated: Boolean = false,
)

private val json = Json { ignoreUnknownKeys = true }

private fun TxnDetails.encode(): String = json.encodeToString(
    DetailsDto(transactionId, rrn, authCode, last4, cardType, acquirer, batchNo, terminalId,
        responseCode, responseMessage, amountPaise, simulated)
)

private fun String.decodeDetails(): TxnDetails = json.decodeFromString<DetailsDto>(this).let {
    TxnDetails(it.transactionId, it.rrn, it.authCode, it.last4, it.cardType, it.acquirer, it.batchNo,
        it.terminalId, it.responseCode, it.responseMessage, it.amountPaise, it.simulated)
}

private fun PaymentAttemptEntity.toDomain() = PaymentAttempt(
    billingRef = billingRef, orderUuid = orderUuid, amount = Paise(amountPaise),
    mode = PayMode.valueOf(mode), state = AttemptState.valueOf(state), eventId = eventId,
    details = detailsJson?.decodeDetails(), statusChecks = statusChecks, note = note,
    createdAt = createdAt, updatedAt = updatedAt,
)

class RoomPaymentJournal(private val dao: PaymentAttemptDao) : PaymentJournal {
    override suspend fun insert(attempt: PaymentAttempt) = dao.insert(
        PaymentAttemptEntity(
            attempt.billingRef, attempt.orderUuid, attempt.amount.value, attempt.mode.name,
            attempt.state.name, attempt.eventId, attempt.details?.encode(), attempt.statusChecks,
            attempt.note, attempt.createdAt, attempt.updatedAt,
        )
    )

    override suspend fun get(billingRef: String) = dao.get(billingRef)?.toDomain()
    override suspend fun forOrder(orderUuid: String) = dao.forOrder(orderUuid).map { it.toDomain() }
    override suspend fun unresolved() = dao.unresolved().map { it.toDomain() }
    override suspend fun setEventId(billingRef: String, eventId: String, now: Long) = dao.setEventId(billingRef, eventId, now)

    override suspend fun transition(
        billingRef: String, state: AttemptState, now: Long, details: TxnDetails?, note: String?, incrementStatusChecks: Boolean,
    ) = dao.transition(billingRef, state.name, now, details?.encode(), note, if (incrementStatusChecks) 1 else 0)
}
