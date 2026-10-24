# Pine Labs: verified answers

Every row below is from the official Pine Billing SDK documentation server, with the
document route cited. **This file supersedes the answers drafted in earlier agent
sessions** — several of those were wrong and are corrected here.

Cross-checked against a working Pine Labs integration on SDK `0.19.0-rc.1`
(`github.com/upendra7866-hub/-biscomansales`, `android/app/src/main/kotlin/.../PineLabsService.kt`).
`TransactionResult` fields are unchanged from 0.19 to 1.0.0 [migration/0.19-to-1.0.0],
so that reference is valid for our 1.0.0 target.

---

## 1. Corrections to earlier claims

| Earlier claim | Verdict | What the docs say |
|---|---|---|
| "`original_transaction_id` maps to the original sale's `billing_reference_id` or RRN." | **Wrong** | `billing_reference_id` on a Void is a **new** reference for the void operation and "may differ from the original sale's reference" [flows/void]. `original_transaction_id` is a separate required field [models/TransactionRequest]. |
| "`bank_code` maps to the `bank_code` / `issuer_bank_code` returned in the sale result." | **Wrong** | It is not derived from the sale result at all — there is no such field on `TransactionResult`. It is an acquirer code constant: the docs give `"01"` as the example [flows/void], and Pine Labs' own Flutter reference app hardcodes `payload['Detail']['BankCode'] = '01'` for both VOID and REFUND (`plutusapp_new/lib/providers/terminal_provider.dart`). Confirm `01` is right for ZenZebra's acquirer, then make it a config constant. |
| "`GetStatus` works terminal-side; for card, responses arrive immediately." | **Correct, and now precise** | `GetStatus` is a `TransactionType` on `do_transaction`, not the `check_status` API. On AppToApp, `check_status` only reads the SDK's in-memory registry and never touches the terminal [flows/get-status]. |
| "E700 printer is 384 dots / 32 chars." | **Not in the docs** | `PrintItem.printer_width` is an `i32?` in dots, vendor-specific [models/PrintItem]. The SDK docs state no E700 figure. Measure it on the device or ask Pine Labs. |
| "Pine Labs confirmed WebView updates are not supported via PAXSTORE." | **Not in the docs** | Out of scope for the SDK docs. Get it in writing from Pine Labs. |
| "Use GA 1.0.0, not 0.16.0-rc.1." | **Correct** | 1.0.0 is the first stable release and the API surface is now frozen under SemVer [migration/0.19-to-1.0.0]. |

## 2. Answers

**Amount units.** `TransactionOptions.amount` is the lowest currency unit — paise for INR
[models/TransactionOptions]. `TransactionResult.amount` is `string?`, "stringified paise"
[models/TransactionResult]. So invariant **I6** compares like with like, and
`amount?.toLongOrNull()` is the right parse.

**Charge slip.** `TransactionResult.charge_slip_print_data` is "the print-ready receipt body
from the terminal" [models/TransactionResult]. We now capture it. The terminal does not print
it for us — `print()` is ours to call — so the slip and our GST tax invoice are two separate
print jobs off one payment.

**Void.** `TransactionType.Void` via `do_transaction`, never `cancel` (`cancel` is Cloud-only
and returns `NotSupported` on AppToApp) [flows/void, migration/0.19-to-1.0.0]. AppToApp needs
`billing_reference_id` (new, for the void), `amount` (must equal the original sale), and
`bank_code` for card voids. Voiding an already-settled transaction fails at the terminal —
use Refund instead.

**GetStatus.** `allowed_payment_modes` is **optional** and only narrows the query
[flows/get-status]. We now send `null` rather than guessing `Upi` for Bharat QR.

**One in-flight operation.** "GetStatus is a listener-style operation — it consumes the single
in-flight op slot. Do not call it while another `do_transaction` / `print` / `scan` /
`perform_operation` is in flight" [flows/get-status]. This applies to **printing too**, which
`PaymentCoordinator`'s mutex does not cover. `PineSdkGateway.sdkSlot` now serialises every SDK
entry point.

**`PrintItem` has nine fields and UniFFI generates no defaults** — all nine must be passed
[models/PrintItem]. Our adapter already does.

**`application_id`** is optional, but if provided must be non-empty: "MasterService silently
drops requests with an empty ApplicationId" [recipes/apptoapp-android]. Our HARD RULE 10 is
correct and load-bearing.

**minSdk 21** or higher [recipes/apptoapp-android]. We build at 25.

**GST fields exist on the request.** `gst_number`, `gst_breakup` and `invoice_date` are
`TransactionOptions` fields [models/TransactionOptions]. Worth sending once the GST work lands,
so the terminal's own slip carries the merchant GSTIN.

**`payer_name`** is "used by UPI and EMI flows to print the customer name on the charge slip".

## 3. Two contradictions in the docs — resolve against `classes.jar` before trusting either

1. **`amount` type.** `models/TransactionOptions` shows `i64? amount` in the UDL, but its
   "Kotlin/JVM type mapping" table lists `amount` under `ULong?`. `models/TransactionRequest`
   says it "remains signed `i64?`, accepts zero and negative values". Two of three say signed,
   and we pass a `Long`. **Open `classes.jar` → `uniffi.pine_billing` and confirm.**
2. **`init` arity.** `recipes/apptoapp-android` shows `PineBillingSdk.init(config, logger = null)`
   with no context, while the working 0.19.0-rc.1 integration calls
   `init(context = …, config = …, logger = null)`. `migration/0.19-to-1.0.0` reclassifies
   `init_with_platform_bridges` as internal, which may be why. **Confirm before building.**

## 4. Evidence from Pine Labs' own reference apps

Two Pine Labs integrations were read:

| Source | SDK / protocol | What it confirms |
|---|---|---|
| `upendra7866-hub/-biscomansales`, `PineLabsService.kt` | Pine Billing SDK `0.19.0-rc.1`, AppToApp | `TransactionResult` field names; `(amount * 100).toLong()` — the SDK takes **paise**; `TransactionResult.transactionId` is labelled "PlutusTransactionLogID". |
| `plutusapp_new` (package `com.pl.plutusapp_new`) | Legacy Plutus Smart Intent protocol, `com.pinelabs.masterapp.HYBRID_REQUEST` | The wire format the SDK wraps: `Header` (ApplicationId, MethodId, UserId, VersionNo) + `Detail` (BillingRefNo, PaymentAmount **in paise**, TransactionType). MethodId `1001` transaction, `1002` print, `1003` settle, `1014` scan. VOID sets `InvoiceNo` and `BankCode='01'`; REFUND sets `BankCode='01'`. |

We stay on the Pine Billing SDK. The legacy protocol is useful only as evidence of what the
SDK fields mean on the wire.

## 5. Still open — ask Pine Labs or test on UAT

1. Confirm `bank_code = "01"` is correct for ZenZebra's acquirer.
2. The E700's real printer width in dots and characters per line.
3. Whether `GetStatus` is meaningful for a card sale, or UPI/BQR only.
4. Whether `charge_slip_print_data` duplicates anything MasterApp prints on its own.
5. Written confirmation on the WebView/PAXSTORE position.

## 6. Pine POS MCP answers (2026-10-24, `https://mcp.pos.pinelabs.com/mcp`, server 1.3.0)

The official Pine Billing SDK documentation server. No login needed. Tools: `getDocumentationList`,
`getDocumentation`, `resolveSdkVersion`, `get_pinelabs_sdk_download_link`.

**Which transport for our app** (`concepts/transports`, `concepts/integration-landscape`):
- Billing app on the same Android terminal as the payments app → **AppToApp**.
- Billing app on a separate device → **PadController** (wired/USB/serial/Bluetooth are PadController UI modes).
- Remote or backend billing → **Cloud**.
So ours stays **AppToApp**. Prerequisite (`recipes/apptoapp-android`): "the compatible **Pinelabs payments
application** installed on the same device". AppToApp goes through a Pine-managed **Master service**
(the SDK binds `com.pinelabs.masterapp` / `.SERVER`; see `classes.jar`). The corpus names no package for the payments
application and says to confirm anything undocumented with Pinelabs integration support.

**UAT E700 SN 1340055807 (2026-10-24, adb):** payments app is `com.pinelabs.plutusplus` (versionCode 250212),
plus `com.pinelabs.ecradapterservice`, `com.pinelabs.peripheralappservice`, `com.pinelabs.tmsstore`.
**`com.pinelabs.masterapp` is not installed** → the SDK fails with `MasterApp is not installed`. Pine must
provision the compatible payments application / Master service for AppToApp on this terminal.

**SDK 2.0.0 is the latest stable** (only production successor of 1.0.0; `releases/2.0.0`, `migration/1.0-to-2.0`):
- AppToApp reuses one MasterApp Messenger connection and reconnects automatically after binder death.
- `get_terminal_info` is supported on AppToApp in 2.0.0 (returns `NotSupported` on 1.0.0).
- **Breaking:** `TransactionOptions.original_transaction_id` is removed; `invoice_number` maps to upstream `InvoiceNo`.
  The 2.0.0 Void template sends `invoice_number` ("invoice number of the sale being voided", flow-dependent) plus
  `bank_code` "01". The 1.0.0 Void template sends neither `original_transaction_id` nor `invoice_number`.
  Our 1.0.0 void currently also sends `original_transaction_id` = sale `transaction_id`, which is an assumption.
- `currency` becomes an ISO-4217 string (`"INR"`) instead of the `CurrencyCode` enum.

**Payments app screens (photos, 2026-10-24):**
- **Live store E700:** the Pine Payments app lists every payment option. These include Card, Dynamic QR (UPI),
  Static QR, Bank/Brand EMI, Pay By Points, Pine 360 and **"Cloud Based Transaction"**.
- **UAT E700 SN 1340055807:** the Payments app shows only **Settle Batch**, with no payment methods. Its menu has
  **Activate**, Set/Test Connection and Device Details, and the header says "logged in: 1234". It carries the DEBUG
  badge. Reading: the UAT payments app is **not activated / not configured** with payment methods. Activation needs
  Pine (no public SDK doc covers it), so don't try **Activate** with guessed values.
- The "Cloud Based Transaction" option on the live terminal suggests these Plutus Plus terminals take bills from Pine
  Cloud (SDK `Cloud` transport). Ask Pine whether this terminal family supports AppToApp (MasterApp) at all, or
  whether Cloud is the intended route.

## 7. Pine's reference app `prpratheesh/plutusapp_new` vs our app (checked 2026-10-24, commit d043126)

| | Pine reference app | Our app |
|---|---|---|
| UI stack | **Flutter** (Dart UI + a Kotlin `MethodChannel` bridge), so hybrid | **Native Android**, Kotlin + Jetpack Compose |
| How it reaches Pine | Legacy direct protocol: binds `com.pinelabs.masterapp` / `.SERVER`, then `startActivityForResult(Intent("com.pinelabs.masterapp.HYBRID_REQUEST"), REQUEST_DATA = {Header{ApplicationId, UserId, MethodId 1001/1002, VersionNo}, Detail{BillingRefNo, PaymentAmount (paise), TransactionType 4001…}})` | **Pine Billing SDK 2.0.0** AppToApp, which binds the same `com.pinelabs.masterapp.SERVER` and builds that envelope internally |
| Needs MasterApp | Yes, the same package | Yes, the same package |
| Status per Pine docs | Legacy integration. The Pine MCP guardrail `guardrails/sdk-only-integration` says not to recreate `MethodId`/`Header`/`Detail` envelopes | The current, documented way |

**Verdict:** our approach is the correct and newer one. The reference app would fail on our UAT
E700 the same way ("MasterApp is not installed"), because both need MasterApp.

**Adopted from it** (print data values only, sent through SDK `PrintItem` fields, not the legacy envelope):
- `PrintDataType 0` = text, `PrinterWidth 24` (also in Pine's email sample). The app default width is now 24.
- `PrintDataType 4` = **QR code printed from text** (`DataToPrint` = URL). The e-bill QR is now sent as
  `text = <e-bill URL>, print_data_type = 4` instead of a base64 PNG. This avoids the undocumented image path.
  `3` appears for a barcode. Confirm both on the E700 (UAT case 9).

