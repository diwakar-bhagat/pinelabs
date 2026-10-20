# CLAUDE.md — Bohemian Odoo POS (Pine Labs E700)

Read this file fully before touching code. It is the source of truth for *why* the
code looks the way it does. If you change a rule here, say so in the commit message.

---

## 1. What this app is

A **native Android (Kotlin) POS till** that runs on the **Pine Labs E700** terminal and
uses **Odoo 19 (Odoo Online, `zenzebra1.odoo.com`) as the system of record**.

- Odoo owns: products, prices, taxes, customers, POS sessions, orders, stock,
  ZZ Cash loyalty, WhatsApp bills (server-side automations fire on paid `pos.order`).
- This app owns: the till screen, the cart, talking to the **Pine Labs payment app on
  the same device (App-to-App)**, the printed receipt, and never losing a payment.

| Item | Value |
|---|---|
| App name | Bohemian Odoo POS |
| Package (applicationId) | `com.bohemian.odoo.pos` — **never change** (registered with Pine Labs) |
| Company | Bohemian Curations Pvt Ltd (brand: ZenZebra) |
| Device | PAX E700 ("Aries6"), PayDroid **Android 7.1.2 (API 25)**, firmware Scorpio V10.1.32 |
| Printer | Pine SDK `print`, line width **24 / 32 / 48 chars** (configurable) |
| Pine SDK | `com.pinelabs.billing:pine-billing-sdk-android` — **use latest GA** (1.0.0 as of 2026-09-21) |
| Pine App ID (UAT) | `771aabd360b64cd59d11806bd88d87b4` |
| Pine App ID (PROD) | _not issued yet_ — Pine registers UAT and PROD separately |
| Odoo | 19.0 Enterprise, Odoo Online (no custom Python modules possible) |
| Odoo API | **JSON-2** (`POST /json/2/<model>/<method>`, `Authorization: bearer <api key>`) |

### Why native (decision record — do not relitigate without new facts)
- E700 is Android 7.1.2; stock WebView is too old for Odoo 19 POS JS (needs ~Chrome 93+).
- Pine Labs confirmed: **"The browser will not be supported on the device."** No WebView
  upgrade via PAXSTORE. So WebView/PWA wrappers are out.
- App-to-App needs a native Android app with the registered package name.
- Odoo's XML-RPC / JSON-RPC are **deprecated in Odoo 19, removed in Odoo 22** → JSON-2 only.

---

## 2. Architecture

```
ui (Compose)  ──►  viewmodels  ──►  domain services  ──►  adapters
                                     │                    ├─ odoo/   OdooJson2Client (OkHttp)
                                     │                    ├─ pine/   PaymentGateway (SDK adapter | fake)
                                     │                    └─ data/   Room (journal + outbox), settings
                                     ├─ PricingService     (Odoo account.tax.compute_all = authoritative)
                                     ├─ PaymentCoordinator (the payment state machine)
                                     ├─ Reconciler         (startup / after ambiguous failures)
                                     ├─ OrderSyncService   (pos.order.sync_from_ui + outbox retry)
                                     └─ ReceiptFormatter   (pure text layout)
```

Package layout (`app/src/main/java/com/bohemian/odoo/pos/`):

| Package | Responsibility | May depend on |
|---|---|---|
| `core` | `Paise` money type, ids, clock, `Outcome` | nothing |
| `odoo` | JSON-2 client, DTOs, catalog/pricing/order/session services | core |
| `pine` | `PaymentGateway` interface + `PineSdkGateway` + `FakePaymentGateway` | core |
| `payment` | `PaymentCoordinator`, `Reconciler`, attempt states | core, pine, data |
| `receipt` | `ReceiptFormatter` (pure) | core |
| `data` | Room DB (payment journal, order outbox), `DeviceSettings` | core |
| `ui` | Compose screens + ViewModels | everything above |
| `di` | `AppContainer` (manual DI; no Hilt on purpose — fewer moving parts) | everything |

**Only `pine/PineSdkGateway.kt` may import Pine SDK types** (`com.pinelabs.billing.sdk.*`,
`uniffi.pine_billing.*`). Everything else speaks our own domain types. When the SDK
version changes, that is the one file to fix.

---

## 3. HARD RULES (money safety — violating these loses or double-charges real money)

1. **Money is `Paise` (Long). Never `Double`/`Float` for amounts.** Odoo returns floats;
   convert once at the boundary with `Paise.fromRupees(BigDecimal(value.toString()))`
   and HALF_UP rounding. (Odoo's own Pine module does `amount * 100` on floats — that
   is a known bug we do not copy.)
2. **Odoo computes the total; we charge exactly that.** Line totals come from
   `account.tax.compute_all` (taxes here include groups, CESS and `code`-type taxes —
   do not reimplement tax math locally). The amount sent to Pine = Odoo total.
3. **One payment in flight per device.** The coordinator holds a mutex; the Pine SDK
   also enforces a single slot (`OperationInProgress`).
4. **Persist before you act.** A `PaymentAttempt` row (billing ref, amount, mode,
   state=`STARTING`) is written to Room *before* calling the SDK. `event_id` is saved in
   `onStarted` (return immediately, no blocking in callbacks).
5. **Never replay a sale after `Timeout` / `TransportError` / unknown outcome.**
   - **UPI / Bharat QR**: on failure or session-expired → `GetStatus` (same
     `billing_reference_id`, `allowedPaymentModes=[UPI]`) → decide. (Pine Labs, 2026-09-21.)
   - **Card**: Pine says the card response is immediate. If we still get an ambiguous
     failure, mark `NEEDS_REVIEW` and block a new charge for that order until a human
     resolves it. Never auto-retry.
6. **A card decline arrives as `onResult(status=FAILED)`, not `onFailure`.** Only
   `onFailure` is infrastructure trouble.
7. **Paid-but-not-synced is a first-class state.** If Pine says SUCCESS but Odoo sync
   fails, the order goes to the Room outbox and is retried forever with backoff; the
   UI shows a red badge until it syncs. Never discard it.
8. **Unique `billing_reference_id` per attempt**, never reused for a new charge.
   Format: `<CFG4><yyMMddHHmmss><rand3>` (alnum, ≤ 20 chars).
9. **All SDK calls off the main thread** (`Dispatchers.IO`). Callbacks marshal to Main.
10. **Pine `applicationId` must be non-empty** — an empty id makes MasterApp silently
    drop requests (every payment times out).
11. **Never log** API keys, Pine tokens, full card numbers, customer phone numbers in
    full. Use `redact()` helpers. Release builds strip debug logs.
12. **Only use Pine APIs documented in the Pine MCP corpus.** No "ghost"/internal APIs
    (`PlatformAppToAppBridge` etc.). Use the facade `PineBillingSdk.init(context, …)`.

---

## 4. Odoo integration facts (verified against the live DB, 2026-09-21)

- JSON-2 call shape: `POST https://zenzebra1.odoo.com/json/2/<model>/<method>`
  headers `Authorization: bearer <key>`, `X-Odoo-Database: zenzebra1`,
  body = named args (`ids`, `context`, `domain`, `fields`, …). Private (`_x`) methods
  are not callable.
- Taxes: company `tax_calculation_rounding_method = round_per_line`, INR. Most POS
  taxes are **price-included GST groups** (e.g. id 30 "18% GST S" = 9% SGST + 9% CGST).
  `account.tax.compute_all(ids=[...], price_unit, quantity)` works over JSON-2 and
  returns `total_included` / `total_excluded`.
- Products for POS: `product.product` with `available_in_pos=True, sale_ok=True`
  (~2,044 today). Read `lst_price, barcode, taxes_id, pos_categ_ids, display_name`.
- Orders: `pos.order.sync_from_ui(orders=[...])` — the same method the web POS uses.
  Lines/payments are ORM commands `[0, 0, {...}]`. Server recomputes on payment write
  and **rejects** if paid ≠ total ("The paid amount is different…") — good safety net.
  Send `state: "paid"` only after Pine confirms.
- Sessions: `pos.session.create({config_id})` → `set_opening_control(cashbox_value, notes)`
  opens it; `close_session_from_ui()` closes. `pos.config.current_session_id/state`
  tells us if one is open.
- Payment methods: create a dedicated **"Pine Labs E700 (A2A)"** method per terminal
  (type bank, `use_payment_terminal` empty — the app drives the terminal, not Odoo).
  Cash = the config's `is_cash_count` method.
- ZZ Cash = `loyalty.program` id 4 (30 rules, 10 rewards). **Phase 3** — applied by an
  Odoo server action (allowed on Odoo Online), not reimplemented in Kotlin.
- Existing automations on `pos.order` (WhatsApp bill, ZZ Cash expiry, dashboard webhook)
  fire server-side for orders created via `sync_from_ui` as long as the order's
  `config_id` matches their filter — the E700 config must be added to those filters.

## 5. Pine SDK facts (from Pine MCP docs + Pine Labs email, 2026-09-21)

- Transport = **AppToApp** (`TransportType.APP_TO_APP`), MasterApp `com.pinelabs.masterapp`
  → manifest needs `<queries><package android:name="com.pinelabs.masterapp"/></queries>`.
- SDK ships as a **local Maven bundle** (`*-maven.zip` / `*-prod.zip`), unzip into
  `app/libs/pine-billing-sdk-android-maven/`. Not on Maven Central. Get the link via the
  Pine MCP tool `get_pinelabs_sdk_download_link(sdk_name="android")` (links expire ~1 h).
- Kotlin: facade `com.pinelabs.billing.sdk.PineBillingSdk`; all types in `uniffi.pine_billing.*`.
  UniFFI classes have **no default args** in older versions — pass every param. Docs
  drift between versions (e.g. `PrintRequest.data` vs `items`) → **the generated
  Kotlin in the AAR wins**; open `classes.jar` in Android Studio before editing the adapter.
- `do_transaction` → `onStarted(eventId)` then exactly one of `onResult` / `onFailure`.
- AppToApp: `cancel` and `check_status`(network) are **not supported**; use
  `TransactionType.GET_STATUS` via `do_transaction` for terminal-side status.
- Void on AppToApp: `TransactionType.VOID`, `bank_code` required for card, amount must
  equal original. Which sale-result field feeds `bank_code` is **unconfirmed → UAT test**.
- Printer: text lines; width 24/32/48 chars.
- Distribution: we send the **signed APK** to Pine → infosec review → PAXSTORE. **Same
  signing key forever** (see docs/DEPLOY_E700.md).

---

## 6. Build & run

- Toolchain: JDK 17, Android Gradle Plugin 8.x, Kotlin 2.x, compileSdk 35, **minSdk 25**.
- Flavors: `uat` (UAT Pine App ID) and `prod` (PROD Pine App ID). Build types: `debug`
  (may use `FakePaymentGateway` via settings toggle) and `release` (real gateway only).
- `./gradlew testUatDebugUnitTest` — pure-JVM tests (money, receipt, coordinator).
- `./gradlew assembleUatRelease` — signed APK for Pine infosec (needs `keystore.properties`).
- The SDK bundle and `keystore.properties` / `*.jks` are **git-ignored**. Never commit them.

## 7. Conventions

- Kotlin official style, 4-space indent, no wildcard imports except `uniffi.pine_billing.*`
  inside `PineSdkGateway.kt`.
- Coroutines + `StateFlow`; no RxJava, no LiveData.
- Every public function in `payment/` and `odoo/OrderSyncService` gets a unit test.
- User-facing strings in `res/values/strings.xml`; cashier-facing errors are friendly,
  technical detail goes to the log.
- Commit style: `area: imperative summary` (e.g. `payment: block new sale while attempt NEEDS_REVIEW`).

## 8. Current phase

See `docs/PLAN.md`. Update the "Status" table there when a phase lands.
