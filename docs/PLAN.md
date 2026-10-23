# Build plan — Bohemian Odoo POS

## Status

| Phase | Scope | Status |
|---|---|---|
| 0 | Project skeleton, flavors, signing, SDK wiring, CLAUDE.md | **in code (this drop)** |
| 1 | Setup screen, catalogue from Odoo, cart, Odoo-priced totals | **in code (this drop)** |
| 2 | Payments: Card / UPI / BQR / Cash via App-to-App, journal, GetStatus, reconciliation, Odoo sync + outbox, receipt print | **in code (this drop) — needs on-device UAT** |
| 3 | Customer lookup by phone, ZZ Cash balance + redeem (Odoo server action) | next |
| 4 | Session open/close from the till, Z-report print, Void | next |
| 5 | Hardening: offline catalogue cache, kiosk mode, crash reporting, infosec checklist | before PROD |

## Phase details

### 0 — Skeleton (day 1)
- Gradle (AGP 8, Kotlin 2, Compose), `uat` / `prod` flavors, `BuildConfig` for Pine App ID + Odoo URL/DB.
- Manifest: `<queries>` for MasterApp, INTERNET, no cleartext, backups disabled, landscape.
- `PosApp` inits the Pine SDK once (AppToApp + App ID).
- Release keystore created and backed up (see DEPLOY_E700.md §2).

### 1 — Catalogue & cart (days 2–4)
- Setup screen: Odoo API key (stored encrypted), pick POS config, printer width.
- Pull `product.product` (available in POS), taxes, payment methods, current session.
- Cart with qty +/−; **every line priced by `account.tax.compute_all`**; totals in `Paise`.
- Barcode: USB/keyboard-wedge scanner input field (E700 camera scan via SDK is phase 5).

### 2 — Payments (days 5–10)
- `PaymentCoordinator` state machine:
  `CREATED → STARTING → IN_FLIGHT(eventId) → {SUCCESS | DECLINED | UNKNOWN → STATUS_CHECK → SUCCESS/DECLINED/NEEDS_REVIEW}`.
- UPI/BQR: GetStatus on failure/session-expired (bounded retries: 3 × 5 s, configurable).
- Card: immediate result; ambiguous → `NEEDS_REVIEW`, no auto retry.
- On SUCCESS: `sync_from_ui(state=paid)` with payment line (transaction id, RRN, auth code, last-4).
  Failure → Room outbox, retried with backoff, red badge on UI.
- Receipt printed via SDK `print` (text, 24/32/48 cols). WhatsApp bill still sent by Odoo.
- `Reconciler` runs on app start: resolves any attempt not in a final state.

### 3 — Customer & ZZ Cash (days 11–14)
- Phone lookup / quick-create `res.partner` (needed for WhatsApp bill + ZZ Cash).
- Server action in Odoo: "zz_cash_quote" (balance, max redeemable) and "zz_cash_apply"
  (adds reward line); app calls it via JSON-2 `ir.actions.server.run` with context.

### 4 — Session & Void (days 15–17)
- Open session (`pos.session.create` + `set_opening_control`), close (`close_session_from_ui`).
  **4a built:** close with I9 guard + Z-report from `get_closing_control_data` (see CLAUDE.md §5).
- Void last card sale (UAT test to confirm `bank_code` source), refund via Odoo order refund.

### 5 — Hardening (days 18–21)
- Offline catalogue cache in Room; sell offline for Cash only; card/UPI need network anyway.
- Lock-task (kiosk) mode, auto-start on boot, screen always on.
- Infosec pack for Pine: permissions list, network endpoints, data-at-rest, logging policy.

## UAT test script (run on the E700 with the UAT App ID)

| # | Case | Expected |
|---|---|---|
| 1 | Card sale ₹1 | SUCCESS, Odoo order paid, receipt printed, WhatsApp bill |
| 2 | Card declined | DECLINED, order stays open, retry allowed |
| 3 | UPI sale, pay in time | SUCCESS |
| 4 | UPI sale, let QR expire | GetStatus → DECLINED/expired, order open |
| 5 | UPI sale, pay, then kill app before result | On restart Reconciler → GetStatus → SUCCESS, order synced |
| 6 | Bharat QR sale | SUCCESS |
| 7 | Wi-Fi off after payment success | Order in outbox, red badge, syncs when Wi-Fi returns |
| 8 | Void a card sale (same day) | Void accepted; note which field gave `bank_code` |
| 9 | Printer out of paper | Payment still SUCCESS; "reprint" offered |
| 10 | Two taps on Pay | Second tap ignored (single in-flight) |
| 11 | Close session with an unconfirmed payment, then resolve it and close | First close blocked (I9, nothing sent to Odoo); second closes, Z-report matches Odoo's session report |

## Open items

- PROD Pine Application ID (Pine issues after UAT sign-off).
- `bank_code` source for Void (UAT case 8).
- Confirm SDK 1.0.0 Kotlin signatures against the adapter (open the AAR in Android Studio).
- Add the E700 `pos.config` id to the filters of the existing WhatsApp / ZZ Cash automations.
