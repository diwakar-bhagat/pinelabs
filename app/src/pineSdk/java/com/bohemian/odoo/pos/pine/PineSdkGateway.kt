package com.bohemian.odoo.pos.pine

// ============================================================================
//  THE ONLY FILE THAT MAY IMPORT PINE SDK TYPES (CLAUDE.md §2).
//  Written against the Pine MCP docs (UDL v0.19 shapes). SDK 1.0.0 may drift:
//  open classes.jar → uniffi.pine_billing in Android Studio and fix compile
//  errors HERE only. Every constructor uses named args so drift fails loudly.
// ============================================================================

import android.content.Context
import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.core.PosLog
import com.bohemian.odoo.pos.core.Redact
import com.pinelabs.billing.sdk.PineBillingSdk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.pine_billing.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object PineSdkBootstrap {
    /** Call once from Application.onCreate. HARD RULE 10: applicationId must be non-empty. */
    fun init(context: Context, applicationId: String) {
        require(applicationId.isNotBlank()) { "Pine applicationId is empty — MasterApp would drop every request" }
        if (isInitialized()) return
        try {
            PineBillingSdk.init(
                context.applicationContext,
                SdkConfig(
                    logLevel = LogLevel.INFO,
                    transport = TransportType.APP_TO_APP,
                    appToApp = AppToAppConfig(applicationId = applicationId),
                ),
                null, // default Logcat logger (tag "pine_billing")
            )
            PosLog.i("PINE_INIT", "transport" to "APP_TO_APP", "appId" to Redact.secret(applicationId))
        } catch (e: SdkException) {
            PosLog.e("PINE_INIT_FAILED", e)
        }
    }
}

class PineSdkGateway : PaymentGateway {
    override val name = "PINE_A2A"

    /**
     * [flows/get-status]: "GetStatus is a listener-style operation — it consumes the
     * single in-flight op slot. Do not call it while another do_transaction / print /
     * scan / perform_operation is in flight."
     *
     * PaymentCoordinator's mutex only serialises payments; a reprint fired from the UI
     * while a GetStatus loop is running would hit that slot and come back
     * OperationInProgress. This mutex covers every SDK entry point in this class.
     *
     * It is never held across a call into PaymentCoordinator, so it cannot deadlock
     * with the coordinator's own mutex.
     */
    private val sdkSlot = Mutex()

    private fun sdk(): PineBillingSdk = PineBillingSdk.shared()

    override suspend fun sale(request: SaleRequest, onStarted: (String) -> Unit): GatewayResult =
        transact(
            type = TransactionType.SALE,
            modes = listOf(request.mode.toSdk()),
            options = options(
                billingRef = request.billingRef,
                amount = request.amount,
                customerMobile = request.customerMobile,
            ),
            onStarted = onStarted,
        )

    override suspend fun getStatus(billingRef: String, amount: Paise, mode: PayMode, onStarted: (String) -> Unit): GatewayResult =
        // [flows/get-status]: GetStatus is a TransactionType on do_transaction, NOT the
        // check_status API — on AppToApp check_status only reads the SDK's in-memory
        // registry and never touches the terminal.
        // allowed_payment_modes is optional there and merely narrows the query, so we
        // send null: narrowing to Upi would have been a guess for Bharat QR.
        // A status query that the MasterApp never answers must not hold the till: after
        // GET_STATUS_TIMEOUT_MS it counts as "unknown", and the caller checks again or asks a supervisor.
        withTimeoutOrNull(GET_STATUS_TIMEOUT_MS) {
            transact(
                type = TransactionType.GET_STATUS,
                modes = null,
                options = options(billingRef = billingRef, amount = amount),
                onStarted = onStarted,
            )
        } ?: GatewayResult.Ambiguous("Timeout", "no status from the terminal in ${GET_STATUS_TIMEOUT_MS / 1000} s")
            .also { PosLog.w("PINE_STATUS_TIMEOUT", "ref" to billingRef) }

    override suspend fun void(request: VoidRequest, onStarted: (String) -> Unit): GatewayResult =
        transact(
            type = TransactionType.VOID,
            modes = null,
            options = options(
                billingRef = request.billingRef,
                amount = request.amount,
                bankCode = request.bankCode,
                invoiceNumber = request.invoiceNumber,
                isSwipe = false,
            ),
            onStarted = onStarted,
        )

    override suspend fun print(lines: List<PrintLine>, printRef: String, widthChars: Int): PrintResult =
        // A printer that never answers must not hold the SDK slot (and with it every payment).
        withTimeoutOrNull(PRINT_TIMEOUT_MS) { printNow(lines, printRef, widthChars) }
            ?: PrintResult.Failed("Printer did not answer in ${PRINT_TIMEOUT_MS / 1000} s")
                .also { PosLog.w("PINE_PRINT_TIMEOUT", "ref" to printRef) }

    private suspend fun printNow(lines: List<PrintLine>, printRef: String, widthChars: Int): PrintResult = withContext(Dispatchers.IO) { sdkSlot.withLock {
        // Values follow Pine's own MasterApp print sample (email, 2026-10-24): every text item is
        // PrintDataType 0 with PrinterWidth = characters per line; only left/centre alignment
        // exists, right alignment is done with spaces (ReceiptFormatter.twoCol already does).
        // Images: Pine says PrintDataType 2 with a hex-byte image. The SDK's PrintItem takes
        // base64 image_data and documents no type code for it, so images keep printDataType null
        // and are verified on the E700 (UAT case 9).
        val req = PrintRequest(
            data = lines.map { l ->
                val isImage = l.imageBase64 != null && l.qrText == null
                PrintItem(
                    text = l.qrText ?: if (isImage) null else l.text,
                    imagePath = null,
                    imageData = l.imageBase64,
                    isCenterAligned = l.center,
                    printerWidth = if (isImage) null else widthChars,
                    printDataType = when {
                        l.qrText != null -> PRINT_TYPE_QR
                        isImage -> null
                        else -> PRINT_TYPE_TEXT
                    },
                    lineSpacing = null,
                    textStyleType = null, // vendor-specific bold code unknown — pre-formatted text instead
                    fontSize = null,
                )
            },
            fontFilePath = null,
            printRefNo = printRef,
            savePrintData = true,
            additionalInfo = null,
        )
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            fun finish(r: PrintResult) { if (done.compareAndSet(false, true)) cont.resume(r) }
            try {
                sdk().print(req, object : PrintListener {
                    override fun onStarted(eventId: String) {}
                    override fun onCompleted(eventId: String) = finish(PrintResult.Printed)
                    override fun onFailure(error: SdkException) {
                        PosLog.w("PINE_PRINT_FAILED", "ref" to printRef, "err" to (error.kind() + ": " + error.message))
                        finish(PrintResult.Failed(error.kind() + ": " + error.message))
                    }
                })
            } catch (e: SdkException) {
                finish(PrintResult.Failed(e.kind() + ": " + e.message))
            }
        }
    } }

    // ---------------------------------------------------------------- internals

    private suspend fun transact(
        type: TransactionType,
        modes: List<PaymentMode>?,
        options: TransactionOptions,
        onStarted: (String) -> Unit,
    ): GatewayResult = withContext(Dispatchers.IO) { sdkSlot.withLock { // HARD RULE 9: never on main
        val request = TransactionRequest(
            transactionType = type,
            allowedPaymentModes = modes,
            transactionOptions = options,
            transportOptions = null, // AppToApp needs no transport extras
        )
        // Exactly what we hand the SDK (all other TransactionOptions fields are null). No PII here.
        PosLog.i("PINE_REQUEST", "type" to type, "modes" to modes, "ref" to options.billingReferenceId,
            "amountPaise" to options.amount, "currency" to options.currency,
            "bankCode" to options.bankCode, "invoiceNo" to options.invoiceNumber)
        suspendCancellableCoroutine { cont ->
            val started = AtomicBoolean(false)
            val done = AtomicBoolean(false)
            fun finish(r: GatewayResult) { if (done.compareAndSet(false, true)) cont.resume(r) }

            try {
                sdk().doTransaction(request, object : TransactionListener {
                    override fun onStarted(eventId: String) {
                        started.set(true)
                        onStarted(eventId) // must return fast
                    }

                    override fun onResult(result: TransactionResult) {
                        // HARD RULE 6: declines arrive here with status FAILED.
                        val details = result.toDetails()
                        // What the terminal answered. Card number only as last 4 (HARD RULE 11).
                        PosLog.i("PINE_RESULT", "event" to result.eventId, "status" to result.status,
                            "code" to result.responseCode, "msg" to result.responseMessage,
                            "ref" to result.billingReferenceId, "amountPaise" to result.amount,
                            "txnId" to result.transactionId, "rrn" to result.rrn, "auth" to result.authCode,
                            "last4" to details.last4, "cardType" to result.cardType, "acquirer" to result.acquirer,
                            "tid" to result.terminalId, "batch" to result.batchNo, "simulated" to result.simulated)
                        // UAT aid: which extra fields this terminal returns (keys only — values may hold PII).
                        result.metadata?.keys?.takeIf { it.isNotEmpty() }?.let { PosLog.i("PINE_RESULT_META_KEYS", "keys" to it.sorted()) }
                        finish(
                            when (result.status) {
                                TransactionStatus.SUCCESS -> GatewayResult.Approved(details)
                                TransactionStatus.FAILED -> GatewayResult.Declined(result.responseCode, result.responseMessage, details)
                                // PENDING is not a decline: a UPI QR can still be paid. Treat it as
                                // unknown, so it is checked again and never marked "not paid" (HARD RULE 5).
                                TransactionStatus.PENDING -> GatewayResult.Ambiguous("Pending", result.responseMessage)
                            }
                        )
                    }

                    override fun onFailure(error: SdkException) {
                        PosLog.w("PINE_FAILURE", "kind" to error.kind(), "detail" to error.message)
                        finish(mapAsyncFailure(error))
                    }
                })
            } catch (e: SdkException) {
                // Synchronous throw: nothing reached the terminal.
                PosLog.w("PINE_REJECTED", "kind" to e.kind(), "detail" to e.message)
                finish(GatewayResult.Rejected(e.kind(), e.message))
            } catch (e: IllegalStateException) {
                finish(GatewayResult.Rejected("IllegalState", e.message))
            }
        }
    } }

    /** Async failures happen after onStarted: the terminal may have taken money. */
    private fun mapAsyncFailure(e: SdkException): GatewayResult = when (e) {
        is SdkException.Cancelled -> GatewayResult.Declined("CANCELLED", "Cancelled on terminal")
        is SdkException.AdminOperationFailed -> GatewayResult.Declined(e.terminalResponseCode.toString(), e.detail)
        is SdkException.InvalidInput,
        is SdkException.NotSupported,
        is SdkException.OperationInProgress,
        is SdkException.TransportUnavailable -> GatewayResult.Rejected(e.kind(), e.message)
        else -> GatewayResult.Ambiguous(e.kind(), e.message) // Timeout, TransportException, Internal, …
    }

    private fun SdkException.kind(): String = this::class.simpleName ?: "SdkException"

    private companion object {
        /** Pine MasterApp print sample: PrintDataType 0 = text. */
        const val PRINT_TYPE_TEXT = 0
        /** Pine reference app `plutusapp_new` print demo: PrintDataType 4 = QR code of DataToPrint. */
        const val PRINT_TYPE_QR = 4
        /** A GetStatus the MasterApp has not answered by then counts as "unknown" (checked again). */
        const val GET_STATUS_TIMEOUT_MS = 45_000L
        /** A print not finished by then is reported failed; the sale stands and "Print again" is offered (I8). */
        const val PRINT_TIMEOUT_MS = 30_000L
    }

    private fun PayMode.toSdk(): PaymentMode = when (this) {
        PayMode.CARD -> PaymentMode.CARD
        PayMode.UPI -> PaymentMode.UPI
        PayMode.BHARAT_QR -> PaymentMode.BHARAT_QR
    }

    /** Field names verified against [models/TransactionResult] (unchanged 0.19 -> 1.0.0). */
    private fun TransactionResult.toDetails() = TxnDetails(
        transactionId = transactionId,
        rrn = rrn,
        authCode = authCode,
        last4 = Redact.last4(maskedPan),
        cardType = cardType,
        acquirer = acquirer,
        batchNo = batchNo,
        terminalId = terminalId,
        responseCode = responseCode,
        responseMessage = responseMessage,
        // UDL: `string? amount` — stringified lowest currency unit, i.e. paise for INR.
        amountPaise = amount?.toLongOrNull(),
        simulated = simulated,
        chargeSlipPrintData = chargeSlipPrintData,
        cardHolderName = cardHolderName,
        merchantId = merchantId,
        date = date,
        time = time,
        billingReferenceId = billingReferenceId,
    )

    /** Every TransactionOptions field passed by name (UniFFI: no defaults on older SDKs). */
    private fun options(
        billingRef: String,
        amount: Paise,
        customerMobile: String? = null,
        bankCode: String? = null,
        invoiceNumber: String? = null,
        isSwipe: Boolean? = null,
    ) = TransactionOptions(
        // SDK 2.0.0 [migration/1.0-to-2.0]: ISO-4217 string, not the CurrencyCode enum.
        currency = "INR",
        billingReferenceId = billingRef,
        // `i64? amount` → Long (checked against classes.jar 2026-10-23).
        amount = amount.value,
        // SDK 2.0.0 removed original_transaction_id; invoice_number maps to upstream InvoiceNo.
        invoiceNumber = invoiceNumber,
        bankCode = bankCode,
        originalBatchNumber = null,
        originalRoc = null,
        transactionLogId = null,
        rewardAmount = null,
        cardNumber = null,
        cardExpiry = null,
        isSwipe = isSwipe,
        metadata = null,
        merchantId = null,
        terminalId = null,
        field0 = null,
        field1 = null,
        field2 = null,
        field3 = null,
        operatorId = null,
        customerMobile = customerMobile,
        customerEmail = null,
        merchantMobile = null,
        merchantEmail = null,
        consentCustomerMobile = null,
        consentCustomerEmail = null,
        consentMerchantMobile = null,
        consentMerchantEmail = null,
        mobileForChargeSlip = null,
        walletProgramId = null,
        gstNumber = null,
        gstBreakup = null,
        invoiceDate = null,
        additionalInfo = null,
        transitData = null,
        payerName = null,
        isTransitMode = null,
        ncmcServiceId = null,
        ncmcServiceMi = null,
        ncmcAccountType = null,
        ncmcServiceData = null,
        emi = null,
    )
}
