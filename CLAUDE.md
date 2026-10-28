# CLAUDE.md — Bohemian Odoo POS (Pine Labs E700)

> **Read this file fully before touching code.** It is the single source of truth for
> what this project is, where it stands, where it's headed, and the rules that protect
> real money. If you change a rule here, say so in the commit message.

---

## 0. Role & working model

**Claude is the lead senior engineer and architect on this project.** That means:

- You own the technical direction. Make decisions; don't ask permission for obvious ones.
- Every code change must trace to a plan phase, an invariant, or a verified fact below.
  Never invent SDK methods, Odoo fields, or API endpoints — everything comes from Pine
  Labs docs/`classes.jar`, Odoo 19 source, or a captured live request.
- When something is ambiguous, state your assumption and keep moving. Flag it as an open
  item at the bottom.

**Repository:** [`diwakar-bhagat/pinelabs`](https://github.com/diwakar-bhagat/pinelabs) (public)

---

## 1. What this app is

A **native Android (Kotlin) POS till** that runs on the **Pine Labs E700** terminal and
uses **Odoo 19 (Odoo Online, `zenzebra1.odoo.com`) as the system of record**.

**Ownership split:**

| Odoo owns | This app owns |
|---|---|
| Products, prices, taxes, customers | The till screen and cart UI |
| POS sessions, orders, stock | Talking to the **Pine Labs payment app (App-to-App)** |
| ZZ Cash loyalty program | The printed receipt |
| WhatsApp bills (server-side automations) | **Never losing a payment** |

### Identity

| Item | Value |
|---|---|
| App name | ZenZebra (launcher label since 0.1.1; written "ZenZebra" in the app, never upper-cased) |
| Package (applicationId) | `com.bohemian.odoo.pos` — **never change** (registered with Pine Labs) |
| Company | Bohemian Curations Pvt Ltd (brand: ZenZebra) |
| Device | PAX E700 ("Aries6"), PayDroid **Android 7.1.2 (API 25)**, firmware Scorpio V10.1.32 |
| UAT terminal | PAX E700 **SN 1340055807**; payments app installed: Pine **Plutus Plus** (`com.pinelabs.plutusplus`); App-to-App MasterApp **not yet provisioned** |
| Printer | Pine SDK `print`, line width **24 / 32 / 48 chars** (configurable) |
| Pine SDK | `com.pinelabs.billing:pine-billing-sdk-android` **2.0.0** (latest stable, 2026-10-24; 1.0.0 kept in `app/libs` for rollback) |
| Pine App ID (UAT) | `771aabd360b64cd59d11806bd88d87b4` |
| Pine App ID (PROD) | `d0b34dd115e8487cb1e20b6706d90186` (issued by Pine 2026-10-27) |
| Odoo | 19.0 Enterprise, Odoo Online (no custom Python modules possible; Odoo.sh is ruled out) |
| Odoo API | **JSON-2** (`POST /json/2/<model>/<method>`, `Authorization: bearer <api key>`) |

### Why native Android (decision record — do not relitigate without new facts)

1. E700 is Android 7.1.2; stock WebView is too old for Odoo 19 POS JS (needs ~Chrome 93+).
2. Pine Labs confirmed: **"The browser will not be supported on the device."** No WebView
   upgrade via PAXSTORE. WebView/PWA wrappers are out.
3. App-to-App SDK requires a native Android app with the registered package name.
4. Odoo's XML-RPC / JSON-RPC are **deprecated in Odoo 19, removed in Odoo 22** → JSON-2 only.

### Final goal

Replace the existing Odoo web POS at ZenZebra's physical store with this native till app
running on Pine Labs E700 terminals. The app handles the full sale lifecycle — catalogue
browse/search, cart, payment via card/UPI/Cash, receipt print, customer lookup, and
ZZ Cash loyalty — while Odoo remains the single system of record. WhatsApp bill delivery
and loyalty automations fire server-side when `pos.order` is synced.

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

### Package layout (`app/src/main/java/com/bohemian/odoo/pos/`)

| Package | Responsibility | May depend on |
|---|---|---|
| `core` | `Paise` money type, `BillingRef`, clock, `Redact`, logging, `CrashLog` | nothing |
| `odoo` | JSON-2 client, DTOs, catalog/pricing/order/session/customer/refund services, `CatalogCache`, `SaleFinalizer`, ZZ Cash balance + redemption | core |
| `gst` | `GstTreatment`, `InvoiceDecision` — fiscal positions & GSTIN | core, odoo |
| `pine` | `PaymentGateway` interface + `PineSdkGateway` + `FakePaymentGateway` | core |
| `payment` | `PaymentCoordinator`, `Reconciler`, `PaymentAttempt` states, `PaymentSplit` rules | core, pine, data |
| `receipt` | `ReceiptFormatter` (pure), `TicketQr` (Odoo e-bill QR) | core |
| `data` | Room DB (payment journal, order outbox), `DeviceSettings` | core |
| `ui` | Compose screens (`MainActivity`), `PosViewModel`, `Theme.kt` (ZenZebra brand palette) | everything above |
| `di` | `AppContainer` (manual DI; no Hilt — fewer moving parts on API 25) | everything |

**Only `pine/PineSdkGateway.kt` may import Pine SDK types** (`com.pinelabs.billing.sdk.*`,
`uniffi.pine_billing.*`). Everything else speaks domain types. When the SDK version changes,
that is the one file to fix.

### Source sets (Pine SDK presence detection)

The Pine SDK is a local Maven bundle (not on Maven Central), git-ignored. The build detects
its presence at Gradle sync time:

```kotlin
// app/build.gradle.kts
val pineSdkDir = rootProject.file("app/libs/pine-billing-sdk-android-maven")
val pineSdkPresent = pineSdkDir.resolve("com/pinelabs/billing").exists()
```

- **`src/pineSdk/java`** — real `PineSdkGateway` (282 lines), compiled only when SDK is present.
- **`src/pineStub/java`** — stub gateway (23 lines), compiled when SDK is absent (CI, clean clones).

This lets the project build and test everywhere without distributing the proprietary SDK.

### Codebase stats (2026-10-24, after Phase 5)

- **61 Kotlin files**, **6,529 lines** total (40 in `main`, 19 tests, real + stub gateway)
- **24 unit test files, 171 tests run** (all pass on the dev Mac, 2026-10-27) covering: `Paise`, `CrashLog`, `CatalogCache`,
  `BillingRef`, `PaymentCoordinator`, `PaymentSplit`, `ReceiptFormatter`, `TicketQr`,
  `ZReportFormatter`, `OrderPayload`, `CustomerService`, `SessionService`, `RefundService`, `GstFoundation`,
  `ZzCashService`, `ZzCashRedemption`, `ZzCashRewardCatalog`, `ZzCashPayload`

---

## 3. HARD RULES — money safety

> Violating any of these loses or double-charges real money.

1. **Money is `Paise` (Long). Never `Double`/`Float` for amounts.** Odoo returns floats;
   convert once at the boundary: `Paise.fromRupees(BigDecimal(value.toString()))`, HALF_UP.
2. **Odoo computes the total; we charge exactly that.** Line totals from
   `account.tax.compute_all` (taxes include groups, CESS, `code`-type — never reimplement
   locally). Amount sent to Pine = Odoo total.
3. **One payment in flight per device.** Coordinator holds a Mutex; Pine SDK enforces a
   single slot (`OperationInProgress`).
4. **Persist before you act.** `PaymentAttempt` row (`STARTING`) written to Room *before*
   calling the SDK. `event_id` saved in `onStarted` — return immediately, no blocking.
5. **Never replay after ambiguous outcome.**
   - UPI / BQR: `GetStatus` (same `billing_reference_id`) → decide.
   - Card: mark `NEEDS_REVIEW`, block new charges for that order until human resolves.
6. **Card decline = `onResult(status=FAILED)`, not `onFailure`.** Only `onFailure` is
   infrastructure trouble.
7. **Paid-but-not-synced is first-class.** Pine SUCCESS + Odoo sync fail → Room outbox,
   retried forever with backoff, red UI badge. Never discard.
8. **Unique `billing_reference_id` per attempt.** Format: `<CFG4><yyMMddHHmmss><rand3>`
   (alnum, ≤ 20 chars). Never reuse for a new charge.
9. **All SDK calls off main thread** (`Dispatchers.IO`). Callbacks marshal to Main.
10. **Pine `applicationId` must be non-empty.** Empty id → MasterApp silently drops →
    every payment times out.
11. **Never log** API keys, Pine tokens, full card PAN, customer phone numbers. Use
    `redact()` helpers. Release builds strip debug logs.
12. **Only use Pine APIs documented in the Pine MCP corpus.** No ghost/internal APIs.
    Use the facade `PineBillingSdk.init(context, …)`.

---

## 4. Invariants (enforced in code — see `docs/INVARIANTS.md`)

| # | Invariant | Enforced in |
|---|---|---|
| I1 | All money is `Long` paise — `Float`/`Double` banned in money paths | `core/Paise.kt` |
| I2 | Odoo is sole price/tax authority — no local tax math | `odoo/PricingService.kt` |
| I3 | One payment in flight per device — Mutex lock | `payment/PaymentCoordinator.kt` |
| I4 | Persist before acting — `STARTING` row before SDK call | `payment/PaymentCoordinator.kt` |
| I5 | No auto-retry on ambiguous card — `NEEDS_REVIEW` locks the order | `payment/PaymentCoordinator.kt` |
| I6 | Amount equality check — terminal amount must match requested | `payment/PaymentCoordinator.kt` |
| I7 | Durable Outbox — paid orders in Room, idempotent via Odoo UUID dedup | `odoo/OrderSyncService.kt` |
| I8 | Decoupled Printing — print failure never un-pays an order | `pine/PineSdkGateway.kt` |
| I9 | Session Close Protection — can't close with unresolved attempts/outbox/open voids/unbooked ZZ Cash | `odoo/SessionService.kt` |
| I10 | Zero Secrets Leakage — no keys/passwords/PANs/phones in source or logs | `core/Redact.kt` |

---

## 5. Build plan & current phase

### Status

| Phase | Scope | Status |
|---|---|---|
| **0** | Skeleton, flavors, signing, SDK wiring, CLAUDE.md | ✅ **in code** |
| **1** | Setup screen, catalogue from Odoo, cart, Odoo-priced totals | ✅ **in code** |
| **2** | Payments: Card / UPI / BQR / Cash, journal, GetStatus, reconciliation, Odoo sync + outbox, receipt | ✅ **in code — needs on-device UAT** |
| **3** | Customer lookup by phone, ZZ Cash balance + redeem | ✅ **in code — needs on-device UAT** (3a balance + 3b redeem; see `docs/ZZ_CASH.md`) |
| **4** | Session open/close from the till, Z-report print, Void | ✅ **in code — needs on-device UAT** — session close (I9) + Z-report; card Void on the terminal + automatic Odoo refund order. Refund of *settled* sales (Pine `REFUND`) not built |
| **5** | Hardening: offline cache, kiosk mode, crash reporting, infosec checklist | ✅ **in code — needs on-device UAT** (offline *selling* deliberately not built, see Phase 5) |

**Where we are (2026-10-24):** all planned phases (0–5) are in code. Nothing has been run
on the E700 yet, so the real gate is **UAT on device** (§10): build the signed UAT APK,
send it to Pine with `docs/INFOSEC.md`, push via PAXSTORE, run the UAT script.

**Built beyond the original plan:** split payment and Odoo 19 POS UI parity
(`docs/UI_PARITY.md`), Odoo e-bill QR
on the printed receipt.

### What's been built (commits, newest first, merges omitted)

```
59f5ba6 receipt: print the Odoo e-bill QR on the E700 receipt
95e8dd6 payment: UPI ID toggle on the cart and UPI IDs on the Setup screen
ae9debb payment: UPI payee (primary + fallback) and UPI deep link
ad58c9e payment: split payment rules and UI parity spec (Odoo 19 POS)
ad33cb1 payment: apply ZZ Cash reward on sale, sent as Odoo reward line (Phase 3b)
5055f62 tools: redact captured POS payloads before diffing (Phase 3b prep)
699de7e chore: ignore local .env (Odoo credentials) on this branch
c489f69 payment: show ZZ Cash balance for the attached customer (Phase 3a)
64c2948 chore: add run-tests.sh build script
ed59dc4 tooling: add bohemian-pos Claude Code plugin and resolve SDK doc contradictions
f107db4 docs: rewrite CLAUDE.md as comprehensive end-to-end project context
040fc72 fix: PineSdkGateway UniFFI type mapping and MainActivity customer binding
4167f21 customer: add partial phone search with live dropdown in cart
09db0a6 fix(pine): correct the SDK adapter against the official documentation
e7d511d feat(payment): enforce Invariant I6 amount match, payload diff tool, and invariants doc
004debc chore: stop tracking .kotlin compiler session files
16877bc feat(gst): fiscal positions, GSTIN customers and to_invoice (G1, G2)
fc05957 Initial commit
f119bd6 chore: initial commit of Bohemian Odoo POS (E700)
```

### Phase details

**Phase 0 — Skeleton** ✅
Gradle (AGP 8, Kotlin 2, Compose), `uat`/`prod` flavors, `BuildConfig` for Pine App ID +
Odoo URL/DB. Manifest with `<queries>` for MasterApp. Release keystore created. SDK wiring
with dual source sets.

**Phase 1 — Catalogue & cart** ✅
Setup screen (Odoo API key encrypted, POS config selection, printer width). Product pull
from `product.product` (available_in_pos). Cart with qty +/−. Every line priced by
`account.tax.compute_all` in `Paise`. Barcode via USB/keyboard-wedge scanner input.

**Phase 2 — Payments** ✅ (needs on-device UAT)
`PaymentCoordinator` state machine:
`CREATED → STARTING → IN_FLIGHT(eventId) → {SUCCESS | DECLINED | UNKNOWN → STATUS_CHECK → SUCCESS/DECLINED/NEEDS_REVIEW}`.
UPI/BQR: GetStatus on failure. Card: immediate result, ambiguous → NEEDS_REVIEW.
On SUCCESS: `sync_from_ui(state=paid)` with payment line (transaction id, RRN, auth code, last-4).
Failure → Room outbox with backoff. Receipt via SDK `print`. `Reconciler` on app start.
GST fiscal positions and GSTIN customer handling.

**Phase 3 — Customer & ZZ Cash** ✅ (needs on-device UAT)
Customer phone lookup coded (partial search via `=ilike`, debounced dropdown in cart view).
**3a (done):** `ZzCashService` reads the card balance live (`loyalty.card`, program 4); the
cart shows the whole-rupee redeemable amount. No Odoo writes.
**3b (built):** cashier taps **Use ₹N ZZ Cash**; `ZzCashRedemption` adds a reward line on
`sync_from_ui` (`is_reward_line`, `reward_id`, `coupon_id`, `points_cost`,
`reward_identifier_code`) — Odoo's own POS loyalty flow, not a custom server action.
Diffed field-by-field against a captured web POS sale (`tools/payload-ref`). Mixed-rate
carts are refused. `validate_coupon_programs` is not called. Not yet run on the E700, nor
an outbox retry of a redeemed sale. Details and open items: `docs/ZZ_CASH.md`.

**Phase 4 — Session & Void** ✅ (needs on-device UAT)
**4a (built):** `SessionService.open()` (`pos.session.create` + `set_opening_control`).
`SessionService.close()` follows the Odoo 19 web POS closing popup (`closing_popup.js`):
flush the outbox → I9 check (no unresolved attempt, empty outbox, no open void; blocks
before any Odoo call) → `post_closing_cash_details(counted_cash)` only when `pos.config.cash_control` →
`update_closing_control_state_session(notes)` → `close_session_from_ui(bank_payment_method_diff_pairs=[])`.
`{'successful': False}` answers are shown to the cashier, never retried. Closing figures and
the Z-report come from `get_closing_control_data`; `ZReportFormatter` prints them after the
close (print failure → "Print again", never blocks).
**4b (built):** `PaymentCoordinator.void(saleRef)` — paid **card** sales only, sharing the
payment mutex. Persists a `void_attempt` row (Room v2, migration 1→2) before the SDK call;
sends a new `billing_reference_id`, the sale amount and `bank_code = PINE_VOID_BANK_CODE`
("01"); `invoice_number` null (SDK 2.0 removed `original_transaction_id`; the AppToApp source of
the sale's InvoiceNo is undocumented — confirm at UAT case 8).
I6 amount check; ambiguous → `NEEDS_REVIEW`, never replayed; declined (e.g. settled) →
`FAILED`, sale stands, retry allowed; one live void per sale. Reconciler sends voids
interrupted by a crash to review. A `VOIDED` void stays open (red bar, blocks close via I9)
until its Odoo refund exists. UI: top-bar "Void" lists today's card sales.
**4c (built):** right after a void, `RefundService` builds a full refund order and sends it
through `sync_from_ui` + the outbox, the way the Odoo 19 web POS ticket screen does
(`ticket_screen.js`): per line `qty` negated and `refunded_orderline_id` = original line id;
order `is_refund`, same partner / fiscal position / `to_invoice`; one negative payment on
the E700 method. Lines are read from Odoo's own `pos.order.line` records, not the cart (I2).
The refund uuid is derived from the void ref, so retries are deduplicated by Odoo. Refused
(supervisor does it in Odoo, "Supervisor: refunded in Odoo"): split bills (Odoo total ≠
voided card amount), ZZ Cash redemptions, partly refunded bills, non-whole qty, sale not yet
in Odoo, no open session. Why not `pos.order.refund()` + `pos.make.payment`: that wizard is
POS-manager only (`ir.model.access.csv`) and the E700 user is POS User (§12).
**Not built:** refund of a settled card sale (Pine `REFUND` + the same Odoo refund order).

**Phase 5 — Hardening** ✅ (needs on-device UAT)
**Offline catalogue:** `CatalogCache` (JSON in private files, atomic write) saves config +
products after each successful load; on `OdooException.Network` the till shows the cached
catalogue with a yellow "No connection to Odoo" bar and Retry. **Offline selling is not
built, on purpose:** every line must be priced by Odoo (I2, HARD RULE 2) and the server
rejects paid ≠ total, so a cash sale priced offline would need local tax math. Revisit only
with a verified way to reproduce `compute_all` (`round_per_line`) exactly.
**Kiosk mode** (Setup switch, off by default): `BootReceiver` starts the till after boot;
`Kiosk.apply` calls `startLockTask()` on resume only when `isLockTaskPermitted` — never the
screen-pinning prompt. PAXSTORE must whitelist this app **and** `com.pinelabs.masterapp`
for lock task, or payments break (`docs/INFOSEC.md` §6). Screen stays on (`FLAG_KEEP_SCREEN_ON`).
**Crash reporting:** `CrashLog` — local only (no third-party SDK, no new endpoint), redacted
before writing (10+ digit runs → last 4, bearer / api_key values removed), newest 10 kept,
shown on Setup with Clear. Payment recovery after a crash stays with the Reconciler.
**Infosec pack:** `docs/INFOSEC.md` — permissions, the single network endpoint, exported
components, data at rest, logging policy, kiosk requirement, money-safety summary.

---

### Decisions 2026-10-27 (Odoo POS parity, round 1)

- **P0 fixed: ZZ Cash was never booked for E700 sales.** See §6 and `docs/ZZ_CASH.md`. New Room table
  `loyalty_confirm` (v3); I9 now also waits for it.
- **Menu from Odoo:** `pos.category` tabs with Odoo colours and child rows (`odoo/MenuTree.kt`),
  product photos from `image_256` cached on disk by `write_date` (`ui/ProductImages.kt`), refreshed on
  start, on the reload icon, and on resume after 30 min.
- **Cart like the web POS:** order panel on the left (`ui/SellPanels.kt`), line selection, Qty keypad,
  `pos.note` tags sent as `pos.order.line.note` JSON, the config's default preset (`preset_id`, Pickup),
  points row `balance +earned −spent = after`, full rewards dialog. % and Price keys are disabled
  (config `manual_discount=false`). Payment screen and customer create/edit: next round.
- **Theme:** Material `primary` is rich black; blood red only on pay buttons and real alarms.
  Icons are Material Design Icons vector drawables (`res/drawable/mdi_*`, Apache-2.0). No new library.

### Decisions 2026-10-27 (production prep)

- **UPI ID comes from Pine only.** The merchant UPI ID for the dynamic QR is configured on Pine's
  side for the terminal; `SaleRequest` never carried a VPA, so the till's UPI ID fields were cosmetic.
  Removed the Setup fields, the cart toggle, `UpiPayee`/`UpiUri` and the `upi.properties` pre-fill.
- **Brand UI:** `ui/Theme.kt`, palette read from zenzebra.in. Rich black #0A0A0A (top bar, total
  bar, CASH), cream #F5F2EC (working areas), blood red #E0000F (CARD/UPI, alerts), gold
  #E6A525 (warnings, non-PROD badge). Headings heavy, all caps (site: Montserrat 900; the E700 has
  no Montserrat, so system sans at Black weight). No hard-coded colours outside `Theme.kt`.
  **In the app the brand name is always "ZenZebra"** (header, Setup, launcher, Z-report), never upper-cased.
  The paper bill is the exception: it follows Odoo's "ZenZebra Bill" template, whose wordmark is "ZENZEBRA".
- **Logo:** the ZenZebra pixel-zebra on its rich-black square (official artwork, 2026-10-27) is the
  launcher icon (rounded tile, mipmap mdpi→xxxhdpi) and sits in the header and Setup title.
- **Security:** debug builds are no longer signed with the release key, since a debuggable build
  could otherwise install over the production app. Android 12+ backup and device-transfer are excluded
  (`res/xml/data_extraction_rules.xml`). Android lint (`lintProdRelease`): 0 errors; remaining
  warnings are accepted (targetSdk 34, fixed landscape, no x86_64, package
  visibility in `PineCheck` on Android 11+, which doesn't affect the E700's Android 7.1).

### Decisions 2026-10-27 (production review, 0.1.1)

Reviewed against the web POS (config 1 "ZenZebra") and the live DB.
- **Every SKU on the till.** Odoo has 2,421 POS products; only 59 have a `pos_categ_ids`. The till
  opened on one café tab, hid untagged products and capped every view at 200 tiles. Now:
  `CatalogService.products(cfg)` uses the Odoo 19 POS domain (available in POS, sellable, company or
  none, `child_of` the config's categories only when `limit_categories`), checks the result against
  `search_count`, pages by `id`, merges by id, re-runs once if short and otherwise throws
  `CatalogIncomplete`, so a partial catalogue is never shown or cached. The grid (`odoo/ProductGrid.kt`)
  opens on **All**, filters the full list per tab and searches name / barcode / internal ref across
  everything, with no caps. Logs: `CATALOG_START/PAGE/DONE/INCOMPLETE`, `CATALOG_SHOWN`, `GRID_FILTER`.
- **ZZ Cash counts the points this cart earns** (`ZzCashRules.available`), as Odoo's
  `_getRealCouponPoints` does for an `applies_on='both'` program, and `validate_coupon_programs` gets the
  **net** change (earned − spent). The old `−cost` was refused by Odoo for any card under the reward
  (live: card 535, 34.7 points, ₹100 → "not enough points"; net → accepted). "Use reward" sits on the
  points row; loading / no card / failed + Retry are shown.
- **Customers are saved explicitly**: Find (exact phone) or Save (name + mobile → `res.partner.create`,
  looked up first so nobody is created twice). Payment never creates a customer; an unsaved typed
  number blocks the charge with a message.
- **BQR removed from the till** (CARD · UPI · CASH). `PayMode.BHARAT_QR` stays for old journal rows and
  the Reconciler.
- **The paper bill is Odoo's bill.** The customer's PDF on WhatsApp is the QWeb report "ZenZebra Bill"
  (`x_zenzebra.pos_bill`, ir.ui.view 3800). After sync the till reads the order back
  (`odoo/BillService.kt`: name, pos_reference, date, partner + phone, cashier, lines with
  `full_product_name` / qty / `price_subtotal_incl` / discount / tax names, payments, `access_token`,
  and the custom `x_zz_*` ZZ Cash texts, which are readable but not searchable) and prints it in the
  template's order (`receipt/ReceiptFormatter.kt`; fixed words in `ZenZebraBill`, so they must change
  when the template changes). The header wordmark is "ZENZEBRA" because the template prints it that way.
  The date is local time: Odoo's PDF prints UTC (12:04 pm for a 17:34 sale), which is an Odoo user
  time zone setting. While an order waits in the outbox the till prints its own copy, marked "Odoo bill
  follows on WhatsApp.". The E700 card or UPI details print under "Paid via". The company logo is
  behind a Setup switch (off), because Pine image printing is unproven; if the printer refuses it, the
  bill prints again without the logo.
- **Keypad is resizable**: drag the grip (snaps 0 / 36 / 48 dp per key), tap to fold; kept in
  `DeviceSettings.keypadKeyDp`. The cart scrolls to the selected line.

### Decisions 2026-10-27 (field test, 0.3.0)

The terminal turned out to run an older build: the BQR tile and the balance-only reward rule were in
0.1.0, `main` and `release-rc1`, and three builds shared versionCode 2. So the build is now **0.3.0 (3)**,
and the version shows on the top bar and on Setup.
- **No more "ZenZebra has stopped" after a restart.**
  - **The crash:** the Reconciler held the payment mutex through a whole UPI status loop (3 × 5 s plus
    GetStatus with no app timeout). "Supervisor: NOT paid" queued behind it, then hit
    `check(!isFinal)` in an uncaught `viewModelScope.launch`.
  - **Mutex only per call:** the status loop now takes the mutex per GetStatus only. A supervisor
    decision wins at once and stops the loop. `resolveManually` / `checkAgain` are idempotent and
    never throw.
  - **No crash on a bug:** every ViewModel coroutine runs through `launchSafe` (a
    `CoroutineExceptionHandler`), giving a local crash report plus a message, never a process kill.
    The app scope has the same handler.
  - **Bar and Back:** the bar shows "Checking with the terminal…" and accepts one tap per ref. Back is
    swallowed during a payment.
- **PENDING is not declined.** SDK 2.0.0 `TransactionStatus` is SUCCESS / FAILED / PENDING. PENDING
  is `Ambiguous` (checked again, then review), never DECLINED, so a QR that is still payable is never
  charged twice.
- **Time limits.** GetStatus is limited to 45 s, then counted as unknown; print to 30 s. The sale keeps
  the SDK's own timeout. Odoo calls are cancellable (`enqueue` + cancel) with a 40 s `callTimeout`,
  and a non-JSON reply becomes `OdooException.Server("BadResponse")`.
- **Latency:**
  - pricing waits 150 ms after the last tap, cancels the old pass and drops stale results by a
    generation counter (no stale reward or totals), and prices lines in parallel (4 at a time); its
    cache is cleared per catalogue load;
  - catalogue reads after `config` run together (live: 1.42 s → 0.42 s for 7 reads), the saved
    catalogue shows first (browse only, no selling until refreshed), and mapping, sorting and the cache
    write run off Main;
  - "Paid" shows as soon as Odoo has the order and the bill prints behind it, with "Print again" if it
    fails;
  - bill reads run in parallel, with tax names cached and the phone taken from the customer;
  - the outbox never sends one order twice at once;
  - search waits for a pause in typing;
  - photos are fetched 40 per read, and the photo cache is capped at 24 MB;
  - settings are read into memory once.
  - Product pages are still fetched one after another, which keeps the completeness check
    deterministic.
- **Rewards** load the earning rules with the customer, so this bill's points count before payment.
  The ⋮ Reward is lit when a reward fits, and a missing rule set says so with a Retry.
- **Split payment (Odoo's payment screen).** SPLIT is the 4th tile and opens `ui/PaymentScreen.kt`
  (`PaymentSplit` rules).
  - **Charging:** each Card / UPI line is its own attempt, capped at what is still due
    (`pay(…, orderTotal)`). An unresolved part blocks the next.
  - **Validate:** sends terminal parts plus cash for the rest; the change is handed back, not booked.
  - **Part-paid bills:** the cart is locked (new bill, edits, customer, reward) and a red bar offers
    FINISH. Session close counts them (I9).
  - **Odoo side:** a part-paid order is never sent to Odoo. `recoverPaid` on start finalizes a fully
    paid sale whose order never reached the outbox.
- **Setup PIN** `core/SetupPin.kt`: 1234 until changed in Setup. Only a salted SHA-256 is stored in the
  encrypted settings. 5 wrong tries lock it for 60 s, and the lock survives a restart. A till without
  an Odoo key / POS opens Setup without the PIN.

### Decisions 2026-10-28 (round 3, 0.4.0)

- **Offers per store, as Odoo gives them** (`odoo/Offers.kt`, `odoo/OffersService.kt`).
  - **Where they come from:** programs, rules and rewards come from Odoo's own POS loader for the till's
    session (`pos.session.load_data`). A program limited to other stores, or out of its dates, never
    reaches this one, and ZZ Cash is used only where Odoo gives it to the store.
  - **Live lists, 28 Oct:**
    - ZenZebra: ZZ Cash, ZZ Notes, Sampling, "test" coupons;
    - KLJ: + Fugeno, its combo, the KLJ coffee code, Artiscents;
    - HQ27GGN: + its two combos, Artiscents;
    - Club 125: + Amity, its combo, the free Peping can, Artiscents.

    A program with no Point of Sale set in Odoo reaches every store (the ZZ Notes do). Restrict it in
    Odoo and the till follows.
  - **The engine** is a pure port of Odoo 19 pos_loyalty (`pos_order.js` / `pos_store.js`):
    - **Points:** order / money / unit rules with minimums.
    - **Claimable rewards** and Odoo's auto-apply: a program with one reward applies by itself; a
      free item that offers several is the line the cashier is on, or is picked under Reward.
    - **Discounts:** on the order, specific products or the cheapest unit; percent / per order /
      per point; split per tax group, each part priced by `compute_all` (I2).
    - **Free items** come off at their own price.
    - **Global discounts:** one whole-bill discount per bill, and the larger wins (ZZ Cash vs a ZZ
      Note, exactly as the web POS).
    - **Products a cheapest / specific discount covers:** the web POS works them out from the
      reward's `reward_product_domain` itself; the till asks Odoo (`product.product.search`).
      `[[1,"=",1]]` means every product.
  - **Enter Code** (⋮, or a scanned code that is no product):
    - A program code is matched exactly, then in any case, with Odoo's refusals (not yet valid,
      expired, already activated).
    - Other codes go to `pos.config.use_coupon_code`.
    - Gift cards and eWallets stay with the web POS.
  - **Booking:** reward lines go to Odoo as the web POS sends them. `confirm_coupon_programs` books
    each program's coupon (issued 1 / used 1, linked by `line_codes`) in the same call as ZZ Cash.
  - **Saved with the sale:** offers are stored with the sale before the charge (I4). A bill with a
    paid part is never repriced.
  - **Verified:** the HQ27GGN-000601 (ZZ Note ₹200) and -000613 (₹89 combo) lines come out exactly as
    Odoo stored them from the live load.
  - **Deliberate deviation:** a program's own free item does not count in its own minimum. Odoo's
    does, so "free can on bills over ₹199" flips on and off with each tap.
- **There are no ZZCASH200 / ZZCASH400 codes.** "₹200 / ₹400 off" are ZZ Cash point rewards (Reward,
  with a customer). The codes are the ZZ Notes, the sampling code and the store codes, all in Odoo.
- **Live Odoo data, nothing to reload** (`odoo/LiveSync.kt`). Every minute, never during a payment or
  a split bill, the till reads:
  - its `pos.config`;
  - the newest `write_date` of each model it uses (products, menu, taxes and fiscal positions,
    loyalty, note tags, company, bill template; archived records count).

  Then:
  - **Products:** only changed products are merged, and the id list drops archived ones.
  - **Taxes:** a tax change clears the price cache.
  - **Offers:** reload on a loyalty change, a session change, or a new day (daily combos).
  - **Session or offline:** a session opened elsewhere updates the session only. Being back online
    runs the full load.
  - **Open cart:** repriced, with "Prices updated from Odoo at …".
  - **Bill text:** the fixed words come from Odoo's "ZenZebra Bill" template by their place in it.
  - **Header:** shows the company name from Odoo.
- **Today's sales** (SALES in the top bar, `odoo/SalesService.kt`, `ui/SalesScreen.kt`):
  - **What it shows:** the store's total, bills, average, first and last bill, payment methods, and
    hour by hour.
  - **Source:** Odoo's own grouping (`formatted_read_group` on `pos.order` / `pos.payment`), net of
    refunds.
  - **The day:** runs 06:00–24:00 in the Odoo user's time zone and resets by itself (hours on Setup).
  - **Refresh:** every minute while open.
- **Customer search by name** (2+ letters) or mobile (4+ digits), A→Z, 20 at a time, scroll for more.
  Product search lists names starting with the query first.
- **Crash reports** open in full on Setup and record the build that crashed.

---

## 6. Odoo integration (verified against live DB, 2026-09-21)

### API shape

```
POST https://zenzebra1.odoo.com/json/2/<model>/<method>
Headers: Authorization: bearer <key>, X-Odoo-Database: zenzebra1
Body: named args (ids, context, domain, fields, …)
```

Private (`_x`) methods are not callable.

### Key facts

- **Taxes:** `tax_calculation_rounding_method = round_per_line`, INR. Most POS taxes are
  price-included GST groups (e.g. id 30 "18% GST S" = 9% SGST + 9% CGST).
  `account.tax.compute_all(ids, price_unit, quantity)` returns `total_included`/`total_excluded`.
- **Products:** `product.product` with `available_in_pos=True, sale_ok=True` (2,396 items on 2026-10-23).
  Fields: `lst_price, barcode, taxes_id, pos_categ_ids, display_name`.
- **Orders:** `pos.order.sync_from_ui(orders=[...])` — same method the web POS uses. Lines
  and payments as ORM commands `[0, 0, {...}]`. Server recomputes and **rejects** if
  paid ≠ total. Send `state: "paid"` only after Pine confirms.
- **Sessions:** `pos.session.create({config_id})` → `set_opening_control(cashbox_value, notes)`;
  `close_session_from_ui()` closes. Check via `pos.config.current_session_id/state`.
- **Payment methods:** Dedicated "Pine Labs E700 (A2A)" method (type bank,
  `use_payment_terminal` empty — app drives terminal). Cash = config's `is_cash_count` method.
- **ZZ Cash:** `loyalty.program` id 4 (30 rules, 10 rewards) on the live DB, but the app never
  hard-codes it: `ZzCashService.program(config)` finds the one `pos_ok` + `program_type=loyalty`
  program open to the till's config (none or 2+ → ZZ Cash hidden), and the sale snapshot keeps
  that id. Balance read from
  `loyalty.card`; the reward goes as reward line(s) on `sync_from_ui` (split per tax group), and the
  points (earned − spent) are booked right after with `pos.order.confirm_coupon_programs` —
  `sync_from_ui` alone never moves them (`docs/ZZ_CASH.md`, 2026-10-27).
- **Automations:** WhatsApp bill, ZZ Cash expiry, dashboard webhook fire server-side on
  `sync_from_ui` orders. E700 `pos.config` id must be added to their filters.

---

## 7. Pine SDK (verified — see `docs/PINE_LABS_VERIFIED.md`)

### Transport

- **AppToApp** (`TransportType.APP_TO_APP`), MasterApp `com.pinelabs.masterapp`.
- Manifest: `<queries><package android:name="com.pinelabs.masterapp"/></queries>`.
- SDK is a local Maven bundle (not on Maven Central). Download via the Pine POS MCP (`https://mcp.pos.pinelabs.com/mcp`) tool
  `get_pinelabs_sdk_download_link(sdk_version, binding="android")` (links expire ~1 h). Unzip into
  `app/libs/pine-billing-sdk-android-maven/`.

### Key verified facts

- `TransactionOptions.amount` is in **paise** (lowest currency unit for INR).
- `TransactionResult.amount` is `string?` — "stringified paise". Parse: `amount?.toLongOrNull()`.
- `do_transaction` → `onStarted(eventId)` then exactly one of `onResult` / `onFailure`.
- `cancel` and `check_status`(network) are **not supported** on AppToApp. Use
  `TransactionType.GET_STATUS` via `do_transaction` for terminal-side status.
- `GetStatus` consumes the single in-flight op slot — don't call while another op runs.
  This includes `print`. `PineSdkGateway.sdkSlot` serialises all SDK entry points.
- `charge_slip_print_data` on `TransactionResult`: print-ready receipt body from terminal.
  Terminal does not auto-print — `print()` is ours to call.
- Void: `TransactionType.Void`, new `billing_reference_id`, `bank_code` for card voids.
  Evidence from Pine reference apps: `bank_code = "01"` — confirm for ZenZebra's acquirer.
- `PrintItem` has 9 fields; UniFFI generates no defaults — all must be passed.
- `application_id` optional but if provided must be non-empty (HARD RULE 10).
- GST fields (`gst_number`, `gst_breakup`, `invoice_date`) exist on `TransactionOptions`.

### Two doc contradictions — resolved against `classes.jar` (2026-10-23)

1. `TransactionOptions.amount` is `java.lang.Long?` (signed i64) — we pass `Long`. ✅
2. `init` takes a Context: `init(Context, SdkConfig, LoggerCallback)` and `init(Context, SdkConfig)`. ✅
   Re-check any time with the plugin script `claude-plugin/bohemian-pos/scripts/sdk-signatures.sh`.

---

## 8. Build & run

### Toolchain

- JDK 17+ (JDK 21 temurin confirmed on dev Mac). Not pinned in the repo's `gradle.properties`:
  Android Studio uses its bundled JDK; on the Mac CLI run
  `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home` first.
- Android Gradle Plugin 8.x, Kotlin 2.x
- `compileSdk 35`, **`minSdk 25`** (matches E700 Android 7.1.2)
- Gradle wrapper 8.11.1

### Flavors & build types

| Dimension | Values |
|---|---|
| Flavor | `uat` (UAT Pine App ID), `prod` (PROD Pine App ID) |
| Build type | `debug` (may use `FakePaymentGateway`), `release` (real gateway only) |

### Commands

```bash
./gradlew testUatDebugUnitTest      # Pure-JVM unit tests
./gradlew assembleUatRelease        # Signed APK for Pine (needs keystore.properties)
```

### What's git-ignored (never commit these)

- `app/libs/pine-billing-sdk-android-maven/` (proprietary SDK bundle)
- `keystore.properties`, `*.jks`, `*.keystore` (signing keys)
- `local.properties` (SDK paths)
- `*.apk`, `*.zip`, `.kotlin/`

### Dev environment

The dev Mac at `~/Downloads/pine-billing-sdk-android-1.0.0-prod/bohemian-odoo-pos 2/` has:
- Android SDK at `~/Library/Android/sdk` (android-35, build-tools 35.0.0)
- JDK 21 at `/Library/Java/JavaVirtualMachines/temurin-21.jdk`
- `local.properties` pointing to SDK
- Pine SDK bundle in `app/libs/`
- `run-tests.sh` convenience script at project root

**Note:** Builds must run on the Mac. Cloud containers cannot reach `dl.google.com`,
`maven.google.com`, or `services.gradle.org` through the proxy.

---

## 9. Deployment pipeline (see `docs/DEPLOY_E700.md`)

```
Build signed APK  →  Email to Pine Labs  →  Pine infosec review  →  PAXSTORE
                                                                      │
                                              Push to UAT E700 (SN 1340055807)
                                                                      │
                                              Run UAT test script (10 cases)
                                                                      │
                                              UAT sign-off → PROD App ID issued
                                                                      │
                                              Rebuild with PROD App ID → PAXSTORE PROD
```

**Critical:** Same signing key forever. Key loss = no updates ever. Key is backed up in
company password manager + encrypted drive.

---

## 10. UAT test script

| # | Case | Expected |
|---|---|---|
| 1 | Card sale ₹1 | SUCCESS, Odoo order paid, receipt printed, WhatsApp bill |
| 2 | Card declined | DECLINED, order stays open, retry allowed |
| 3 | UPI sale, pay in time | SUCCESS |
| 4 | UPI sale, let QR expire | GetStatus → DECLINED/expired, order open |
| 5 | UPI sale, pay, kill app before result | Reconciler → GetStatus → SUCCESS, order synced |
| 6 | ~~Bharat QR sale~~ | Removed from the till 2026-10-27 (CARD · UPI · CASH only) |
| 7 | Wi-Fi off after payment success | Outbox, red badge, syncs when Wi-Fi returns |
| 8 | Void a card sale (same day) | Void accepted with `bank_code` "01" and `original_transaction_id` = sale txn id; Odoo shows a "… REFUND" order linked to the sale, stock returned, no red bar |
| 9 | Printer out of paper | Payment still SUCCESS; "reprint" offered |
| 9b | Cash sale with a customer; compare the paper bill with the WhatsApp PDF | Same bill no., lines, taxes, totals, payment, ZZ Cash block and bill ref; date in local time. Then switch "Print the company logo" on in Setup and sell again: logo prints, or the bill prints without it |
| 10 | Two taps on Pay | Second tap ignored (single in-flight) |
| 11 | Close session with an unconfirmed payment, then resolve it and close | First close blocked (I9, nothing sent to Odoo); second closes, Z-report matches Odoo's session report |
| 12 | Kiosk on, reboot the E700, then take a card payment | Till starts by itself and is pinned; MasterApp payment screen opens normally; payment SUCCESS |
| 13 | Wi-Fi off, restart the app | Cached catalogue shown with the yellow offline bar; pricing/payment refused with a friendly error; Retry recovers when Wi-Fi is back |
| 14 | Open the till; scan or search a retail SKU (e.g. "Sea Breeze") | "All · 2421" tab selected; the SKU is found and added; café tabs filter the same list |
| 15 | Customer with < ₹50 ZZ Cash buys enough to earn ≥ 50 | "Use reward" offered; ₹50 off applied; paid; Odoo shows the reward lines and the card ends at balance + earned − 50 |
| 16 | New customer: type a new mobile, Find, enter a name, Save | "Saved: Name · ••••1234"; customer attached; partner exists once in Odoo; WhatsApp bill on payment |
| 17 | Long cart: drag the keypad grip down, then tap it | Keypad shrinks, then folds; every cart line reachable; size kept after restart |
| 18 | UPI sale, kill the app on the Pine screen, restart; tap "Supervisor: NOT paid" (twice) | Bar shows "Checking with the terminal…"; the tap works at once; no "has stopped"; cart can be charged again |
| 19 | UPI, then Back on the Pine screen without paying | No hang; outcome or the bar within about a minute; the till keeps working |
| 20 | SPLIT ₹500: UPI ₹300, then Cash ₹200 (tendered ₹500) | UPI charged for ₹300 only; Validate; change ₹300 shown; Odoo order has 2 payment lines = ₹500; bill shows both |
| 21 | SPLIT: UPI ₹200, then Card for the rest; close the screen after UPI | Red bar "partly paid"; cart and New bill locked; FINISH reopens it; Card charges only the rest |
| 22 | SETUP with 1234, change the PIN, wrong PIN 5 × | Setup opens; new PIN works, 1234 no longer; locked 60 s after 5 wrong tries |
| 23 | Top bar badge | Shows "PROD · 0.4.0", so the installed build is never a guess |
| 24 | ⋮ → Enter Code: a ZZ Note on a bill below its minimum, then add items past it | "Code accepted"; the ₹N off line appears by itself once the bill qualifies; paid; Odoo order has the reward line and the WhatsApp PDF shows it |
| 25 | Sampling code on a 2-item bill | One unit of the cheaper item comes off |
| 26 | At a store with a combo / Fugeno / free item (HQ27, KLJ, Club 125): the combo's two items; a free item tapped last | Discount line appears by itself; the free item comes off; ✕ takes an offer off and it stays off for this bill |
| 27 | ZZ Cash reward and a ZZ Note on one bill | Only the larger whole-bill discount stays; the cashier is told |
| 28 | Change a product's price (or a tax) in Odoo, wait a minute with the till open | Tile shows the new price; an open cart is repriced with "Prices updated from Odoo at …" |
| 29 | SALES in the top bar | Today from 6 AM: total, bills, first bill, payment methods, hour by hour; equals Odoo's Orders Analysis for the store |
| 30 | Customer: type 2+ letters of a name, scroll | Every match A→Z, more load while scrolling |

---

## 11. Conventions

- Kotlin official style, 4-space indent.
- No wildcard imports except `uniffi.pine_billing.*` inside `PineSdkGateway.kt`.
- Coroutines + `StateFlow`; no RxJava, no LiveData.
- Manual DI via `AppContainer` — no Hilt (fewer moving parts on API 25).
- Every public function in `payment/` and `odoo/OrderSyncService` gets a unit test.
- User-facing strings in `res/values/strings.xml`; cashier errors are friendly, detail goes to log.
- Commit style: `area: imperative summary` (e.g. `payment: block new sale while attempt NEEDS_REVIEW`).

---

## 12. Security

- **Release keystore** password was exposed in an earlier agent session. Rotate with
  `keytool -storepasswd`. The SHA-256 fingerprint is safe to share.
- API keys stored encrypted on device (Setup screen). One key per terminal.
- Odoo user for E700 has POS User rights only — minimal privilege.
- `.gitignore` excludes all secrets. **The repo is public** by the owner's choice.
- Never log: API keys, keystore passwords, card PAN, customer phone numbers.
- Release builds strip debug-level logs.

---

## 13. Open items

- [x] PROD Pine Application ID issued 2026-10-27 (`d0b34dd1…0186`); first signed prod APK built (v1+v2, cert SHA-256 `5062f7cf…cbda`)
- [ ] Confirm `bank_code = "01"` for ZenZebra's acquirer (UAT case 8)
- [ ] Void on SDK 2.0 sends `invoice_number = null`. Confirm at UAT case 8 whether MasterApp needs the sale's InvoiceNo, and where it comes from. `PINE_RESULT_META_KEYS` in logcat lists the extra result fields the terminal returns
- [ ] **Blocker:** UAT E700 has Plutus Plus (`com.pinelabs.plutusplus`) but no `com.pinelabs.masterapp`. Pine must provision the AppToApp payments application / Master service (see `docs/PINE_LABS_VERIFIED.md` §6)
- [x] Upgrade Pine SDK 1.0.0 → 2.0.0 (2026-10-24, from the Pine POS MCP; `currency` string, `invoice_number`)
- [ ] Capture a web POS refund `sync_from_ui` payload on the UAT config and diff it against `RefundService.buildPayload` (`tools/payload_diff.py`) — 4c was built from Odoo 19 source, not a capture
- [x] Confirm SDK 1.0.0 `init` arity and `amount` type against `classes.jar` in AAR (2026-10-23)
- [ ] Add E700 `pos.config` id to WhatsApp / ZZ Cash automation filters in Odoo
- [ ] E700 printer width in dots and chars per line (measure on device)
- [ ] Confirm `GetStatus` relevance for card sales (vs UPI/BQR only)
- [ ] Confirm `charge_slip_print_data` vs what MasterApp auto-prints
- [ ] Written confirmation from Pine Labs on WebView/PAXSTORE position
- [x] Run unit tests on Mac (`./gradlew testUatDebugUnitTest`) — 47 pass with real SDK (2026-10-23)
- [ ] Rotate exposed release keystore password
- [ ] Build and `apksigner`-verify the signed PROD APK at versionCode 2 / 0.1.1 (`docs/REVIEW.md` §6)
- [ ] PAXSTORE: lock-task whitelist for `com.bohemian.odoo.pos` **and** `com.pinelabs.masterapp` (kiosk mode, UAT case 12)
- [x] Setup screen behind a supervisor PIN (1234 by default, changeable; 0.3.0)
- [ ] On device: which callback MasterApp sends when Back is pressed on its payment screen (logcat `PINE_RESULT` / `PINE_FAILURE`)
- [ ] The SDK's own timeout for a sale is undocumented; the till does not cut a live payment short
- [ ] Abandoning a split bill after a UPI part: UPI cannot be voided from the till; a supervisor refunds it in Odoo
- [ ] A voided E700 sale keeps its earned ZZ Cash (Odoo's refund books negative points) — fix with the settled-sale refund
- [ ] Live check of `confirm_coupon_programs` from the till: one approved cash sale, then `loyalty.history` read back
- [ ] Odoo API key in use belongs to Ankur (internal user, res.users 28) — create a POS-User-only user for the E700 and issue its own key
- [ ] Under that POS-User key, confirm `res.partner.create` (Save customer) and `loyalty.card` reads are allowed
- [ ] Odoo's "ZenZebra Bill" PDF prints the order time in UTC (12:04 pm for a 17:34 sale): set Asia/Kolkata as the time zone of the user the WhatsApp automation runs as
- [ ] Pine image print type for the bill logo (Setup switch, UAT 9b)
- [ ] 2,362 of 2,421 POS products have no POS category: they show under All / search / barcode only. Tagging them into tabs is Odoo data work
- [ ] On device: first full catalogue load time and memory with 2,421 tiles on the E700 (UAT case 14)
- [ ] ZZ Note codes have no usage limit in Odoo (`limit_usage` off): anyone who learns one can reuse it. Odoo setting, not the app
- [ ] Offers not supported on the till: gift cards, eWallets, coupons for a next order, pricelist-limited programs (none in use)
- [ ] Under the POS-User key, confirm `pos.session.load_data`, `product.product.search` on reward domains, `use_coupon_code` and `formatted_read_group` on `pos.order` / `pos.payment` are allowed
- [ ] First live sale with an offer from the till (UAT 24–27), then read `loyalty.history` and the order's reward lines back

---

## 14. Claude Code plugin (`claude-plugin/bohemian-pos`)

Install once per machine: `/plugin marketplace add diwakar-bhagat/pinelabs` then `/plugin install bohemian-pos@pinelabs`.
Skills: `money-safety-review`, `pine-sdk`, `odoo-json2`, `build-test`, `uat-e700`, `deploy-e700`.
MCP `odoo-json2`: read-only JSON-2 tools against the live DB; credentials from the git-ignored `.env`
in the repo root (`ODOO_URL`, `ODOO_DB`, `ODOO_USER`, `ODOO_API_KEY`). Writes need `ODOO_MCP_ALLOW_WRITE=1`.

## 15. Reference docs (in `docs/`)

| File | Content |
|---|---|
| `PLAN.md` | Phase-by-phase build plan with UAT test script |
| `INVARIANTS.md` | The 10 invariants with enforcement locations |
| `PINE_LABS_VERIFIED.md` | Corrections to earlier assumptions, verified SDK facts, contradictions to resolve |
| `DEPLOY_E700.md` | Mac setup, signing key, development vs release deployment pipeline |
| `ZZ_CASH.md` | ZZ Cash balance (3a) and redemption (3b): payload, open items, UAT case |
| `UI_PARITY.md` | Screen-by-screen parity with Odoo 19 POS, split payment rules |
| `INFOSEC.md` | Infosec pack for Pine: permissions, endpoints, data at rest, logging, kiosk requirement |
| `PINE_CASE_2026-10-24.md` | Integration case for Pine: routes (AppToApp / Cloud / PadController), what works vs blocked, questions |
| `REVIEW.md` | External review checklist (2026-10-27): findings F1–F6 with fixes, endpoint map, identity/signing, commands run, blockers |
| `AGENT_PROMPT_E700_A2A.md` | Prompt for an on-device AI agent (adb) to run and report the App-to-App flow safely |
