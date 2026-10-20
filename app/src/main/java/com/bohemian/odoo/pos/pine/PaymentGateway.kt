package com.bohemian.odoo.pos.pine

import com.bohemian.odoo.pos.core.Paise

/** Payment instruments the till offers. Cash never touches the gateway. */
enum class PayMode { CARD, UPI, BHARAT_QR;
    /** Pine says: UPI/BQR outcomes must be confirmed with GetStatus on failure/expiry. */
    val needsStatusCheckOnFailure: Boolean get() = this != CARD
}

data class SaleRequest(
    val billingRef: String,
    val amount: Paise,
    val mode: PayMode,
    val customerMobile: String? = null,
)

data class VoidRequest(
    val billingRef: String,       // new ref for the void operation
    val amount: Paise,            // must equal the original sale amount
    /**
     * Acquirer/bank code, e.g. "01". Required for card voids on AppToApp
     * [flows/void]. Source field in the sale result still to be confirmed on UAT.
     */
    val bankCode: String?,
    /**
     * Required by UDL validation for Void/TipAdjust/Adjust/SaleComplete
     * [models/TransactionRequest]. The AppToApp template in [flows/void] omits it,
     * so send it and let the terminal ignore it rather than risk InvalidInput.
     */
    val originalTransactionId: String?,
)

/** What the terminal told us about a transaction. All optional: terminals vary. */
data class TxnDetails(
    val transactionId: String? = null,
    val rrn: String? = null,
    val authCode: String? = null,
    val last4: String? = null,
    val cardType: String? = null,
    val acquirer: String? = null,
    val batchNo: String? = null,
    val terminalId: String? = null,
    val responseCode: String? = null,
    val responseMessage: String? = null,
    val amountPaise: Long? = null,
    val simulated: Boolean = false,
    /**
     * `TransactionResult.charge_slip_print_data` — the print-ready charge slip the
     * terminal composed. Printing this verbatim is what keeps our slip identical to
     * the acquirer's. Our own GST tax invoice is printed separately.
     */
    val chargeSlipPrintData: String? = null,
    val cardHolderName: String? = null,
    val merchantId: String? = null,
    /** Terminal-reported date/time; formats are terminal-specific. */
    val date: String? = null,
    val time: String? = null,
    /** Echoed back by the terminal; used to correlate a late result with our attempt. */
    val billingReferenceId: String? = null,
)

sealed interface GatewayResult {
    /** Money moved. */
    data class Approved(val details: TxnDetails) : GatewayResult

    /** Terminal answered: not paid (card decline, UPI rejected/expired, cancelled on terminal). */
    data class Declined(val code: String?, val message: String?, val details: TxnDetails? = null) : GatewayResult

    /**
     * The request was accepted by the SDK but we don't know the outcome
     * (Timeout, TransportError, IPC died…). HARD RULE 5: never replay a sale on this.
     */
    data class Ambiguous(val kind: String, val detail: String?) : GatewayResult

    /**
     * Refused before anything reached the terminal (InvalidInput, OperationInProgress,
     * TransportUnavailable, NotSupported, SDK missing). Safe to fix and retry.
     */
    data class Rejected(val kind: String, val detail: String?) : GatewayResult
}

data class PrintLine(val text: String, val center: Boolean = false, val bold: Boolean = false)

sealed interface PrintResult {
    data object Printed : PrintResult
    data class Failed(val reason: String) : PrintResult
}

/**
 * The only way the app talks to the payment terminal.
 * Implementations: PineSdkGateway (real, App-to-App), FakePaymentGateway (dev), stub (no SDK).
 *
 * Contract:
 * - Every call is a suspend function that returns exactly once, even if the caller's
 *   coroutine is cancelled (callers run it under NonCancellable).
 * - [onStarted] fires when the SDK accepted the request (event id allocated). It must
 *   return quickly; don't block in it.
 */
interface PaymentGateway {
    val name: String
    suspend fun sale(request: SaleRequest, onStarted: (eventId: String) -> Unit): GatewayResult
    suspend fun getStatus(billingRef: String, amount: Paise, mode: PayMode, onStarted: (String) -> Unit = {}): GatewayResult
    suspend fun void(request: VoidRequest, onStarted: (String) -> Unit = {}): GatewayResult
    suspend fun print(lines: List<PrintLine>, printRef: String): PrintResult
}
