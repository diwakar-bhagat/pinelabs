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
| App name | Bohemian Odoo POS |
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
browse/search, cart, payment via card/UPI/BQR/Cash, receipt print, customer lookup, and
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
  bar, CASH), cream #F5F2EC (working areas), blood red #E0000F (CARD/UPI/BQR, alerts), gold
  #E6A525 (warnings, non-PROD badge). Headings heavy, all caps (site: Montserrat 900; the E700 has
  no Montserrat, so system sans at Black weight). No hard-coded colours outside `Theme.kt`.
- **Logo:** the ZenZebra pixel-zebra on its rich-black square (official artwork, 2026-10-27) is the
  launcher icon (rounded tile, mipmap mdpi→xxxhdpi) and sits in the header and Setup title.
- **Security:** debug builds are no longer signed with the release key, since a debuggable build
  could otherwise install over the production app. Android 12+ backup and device-transfer are excluded
  (`res/xml/data_extraction_rules.xml`). Android lint (`lintProdRelease`): 0 errors; remaining
  warnings are accepted (targetSdk 34, fixed landscape, no x86_64, package
  visibility in `PineCheck` on Android 11+, which doesn't affect the E700's Android 7.1).

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
| 6 | Bharat QR sale | SUCCESS |
| 7 | Wi-Fi off after payment success | Outbox, red badge, syncs when Wi-Fi returns |
| 8 | Void a card sale (same day) | Void accepted with `bank_code` "01" and `original_transaction_id` = sale txn id; Odoo shows a "… REFUND" order linked to the sale, stock returned, no red bar |
| 9 | Printer out of paper | Payment still SUCCESS; "reprint" offered |
| 10 | Two taps on Pay | Second tap ignored (single in-flight) |
| 11 | Close session with an unconfirmed payment, then resolve it and close | First close blocked (I9, nothing sent to Odoo); second closes, Z-report matches Odoo's session report |
| 12 | Kiosk on, reboot the E700, then take a card payment | Till starts by itself and is pinned; MasterApp payment screen opens normally; payment SUCCESS |
| 13 | Wi-Fi off, restart the app | Cached catalogue shown with the yellow offline bar; pricing/payment refused with a friendly error; Retry recovers when Wi-Fi is back |

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
- [ ] Setup screen (kiosk switch, API key) has no supervisor PIN — any cashier can open it
- [ ] A voided E700 sale keeps its earned ZZ Cash (Odoo's refund books negative points) — fix with the settled-sale refund
- [ ] Live check of `confirm_coupon_programs` from the till: one approved cash sale, then `loyalty.history` read back
- [ ] Odoo API key in use belongs to Ankur (internal user, res.users 28) — create a POS-User-only user for the E700 and issue its own key

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
