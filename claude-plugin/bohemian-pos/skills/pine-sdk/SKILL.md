---
name: pine-sdk
description: Facts and verification steps for the Pine Labs Billing SDK 1.0.0 (App-to-App on the E700). Use before writing or changing any code that touches com.pinelabs.billing.sdk / uniffi.pine_billing, PineSdkGateway, payment options, print, void or GET_STATUS, or when a Pine doc claim needs confirming.
---

# Pine Labs SDK (App-to-App, E700)

**Never invent an SDK method or field.** Every call must exist in the AAR *and* be documented.
Sources in order of authority:
1. `classes.jar` in the AAR — run `${CLAUDE_PLUGIN_ROOT}/scripts/sdk-signatures.sh` (add classes with
   `EXTRA_CLASSES="uniffi.pine_billing.PrintRequest uniffi.pine_billing.SdkConfig"`).
2. The Pine Labs MCP connector (`getDocumentationList` → read `must_read_first` docs → `getDocumentation`).
3. `docs/PINE_LABS_VERIFIED.md` in the repo.

Only `app/src/pineSdk/java/.../pine/PineSdkGateway.kt` may import SDK types.

## Verified against classes.jar (SDK 1.0.0, 2026-10-23)

- `PineBillingSdk.init(Context, SdkConfig, LoggerCallback)` and `init(Context, SdkConfig)` — **Context is required**.
  `shared()`, `isInitialized()` on the companion.
- Instance: `doTransaction(TransactionRequest, TransactionListener)`, `print(PrintRequest, PrintListener)`,
  `scan`, `cancel`, `checkStatus`, `connect/disconnect`, `getTerminalInfo`, `runSelfTest`, `setTransport`, `performOperation`.
- `TransactionOptions.amount: Long?` (paise). `rewardAmount: ULong`.
- `TransactionResult.amount: String?` — stringified paise → `amount?.toLongOrNull()`.
- `TransactionType`: SALE, REFUND, VOID, PRE_AUTH, SALE_COMPLETE, TIP_ADJUST, ADJUST, AWARD, REDEEM,
  LOAD, BALANCE_INQUIRY, FETCH_LOYALTY_NUMBER, REPRINT, GET_STATUS.

## Behaviour rules (from Pine docs)

- Transport `APP_TO_APP`, MasterApp `com.pinelabs.masterapp` (manifest `<queries>`).
- `doTransaction` → `onStarted(eventId)` then exactly one of `onResult` / `onFailure`.
  Card decline arrives as `onResult(status=FAILED)`.
- `cancel` and network `checkStatus` are **not supported** on App-to-App → use `TransactionType.GET_STATUS`
  via `doTransaction`, same `billing_reference_id`.
- One op slot: `GET_STATUS` and `print` also occupy it → serialise via `sdkSlot`.
- Terminal does not auto-print; `charge_slip_print_data` on the result is ours to print.
- Void: `TransactionType.VOID`, new billing ref, `bank_code` (reference apps use `"01"` — confirm at UAT case 8).
- `PrintItem` has 9 fields, no defaults — pass all.
- `applicationId` must be non-empty (UAT: `771aabd360b64cd59d11806bd88d87b4`; PROD not issued).

## Getting the SDK bundle

Pine MCP `get_pinelabs_sdk_download_link(sdk_name="android")` (link expires ~1 h) → unzip into
`app/libs/pine-billing-sdk-android-maven/` (git-ignored). Without it the build uses the stub gateway.
