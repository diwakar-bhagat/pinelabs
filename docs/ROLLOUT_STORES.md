# Rolling the E700 till out to every store

Checked read-only against the live Odoo DB on 2026-10-28, against till 0.4.0 (code 4). Nothing in
Odoo was changed. The till needs no code change to run at several stores: each terminal keeps its
own Odoo key, store, payment methods and PIN (`data/DeviceSettings.kt`). Odoo has to be set up
first, though.

## The stores in Odoo today

| Config | Store | Company | E700 Card/UPI method | Cash-type method (`is_cash_count`) | Web POS Pine method |
|---|---|---|---|---|---|
| 1 | ZenZebra | ZenZebra (Delhi) | 22 "Pine Labs E700 (A2A)" | 2 "Cash" | 10 "Pine labs" |
| 2 | KLJ | ZenZebra (Delhi) | **none** | **none** (4 "Cash" is type Bank) | 20 "Pine Labs KLJ" |
| 3 | SWN | ZenZebra (Delhi) | **none** | **none** (4) | 19 "Pine Labs SWN" |
| 4 | HQ27GGN | ZenZebra (Delhi) | **none** | **none** (4) | 10 "Pine labs" |
| 8 | Club 125 | ZenZebra (Delhi) | **none** | **none** (4) | 10 and 18 "Pine Labs Club 125" |

- Configs 9 and 10 (ZZ Café HO / KLJ) take only Razorpay and are not E700 stores.
- Configs 5, 6 and 7 are archived copies of KLJ, SWN and HQ27 under the "ZenZebra Noida" and
  "ZenZebra Haryana" companies.
- Every Card / UPI / Bank method posts to the same journal (13 "Bank").

## Before a store goes live

1. **One Odoo user and API key per store, never one shared key.**
   - **Today:** every E700 uses the key of res.users 28. That user is Administrator in Accounting,
     Inventory, Point of Sale and Sales.
   - **Why it matters:**
     - A lost or stolen terminal would give full admin access to Odoo.
     - One store's key can't be revoked without stopping every store.
     - Odoo records the key's owner as the order's `user_id`, so every E700 bill shows that user as
       cashier. All 7 E700 orders so far do. The web POS records each store's own login instead.
   - **Do:** create one user per store (for example "E700 KLJ") with **Point of Sale / User** rights
     only, issue its API key, and enter it in that terminal's Setup.
   - **Don't** reuse the store logins of SWN (res.users 13) or HQ27GGN (res.users 21). They are also
     Accounting and Inventory administrators.
2. **Put the E700 method on the store's POS.** Add method 22 "Pine Labs E700 (A2A)" to the store's
   payment methods in Odoo. A separate E700 method per store also works if the accountant wants one.
   - **Why:** Setup step 3 pre-selects only a method named "E700". Without one, it lists every other
     non-cash method of the store, so a wrong pick is easy.
   - **Wrong picks:**
     - "Pine Labs <store>": the web POS's own Pine terminal link (`use_payment_terminal = pine_labs`).
     - Razorpay.
     - "Cash" (method 4): it is type Bank, so it shows up as a Card / UPI choice.
3. **Give the store a real Cash method.**
   - **The gap:** Setup step 4 lists only methods with `is_cash_count`. KLJ, SWN, HQ27GGN and Club
     125 have none, so the E700 there refuses every cash sale and the cash part of a split bill
     ("No cash method configured").
   - **Who decides:** the owner and the accountant. A Cash-type method changes cash accounting and
     lets Odoo hand back change (`docs/CASE_2026-10-28_PINE_OVERPAY.md`).
4. **Decide who closes the day.**
   - **Shared session:** the web POS and the E700 (or two E700s) on one store's POS share one Odoo
     session.
   - **What the close check sees:** the till's own check (I9) covers only its own pending payments,
     outbox and ZZ Cash bookings, not the other device's.
   - **The rule:** close from one device, after every device shows nothing pending.
   - **If the other device closes first:** a late E700 order is sent to a closed session. Odoo then
     files it under another session of that store; this has not been tested on this DB.
5. **The terminal itself.**
   - **Pine:**
     - Provision the App-to-App payments app on each terminal, the same way as on the first.
     - Push the signed PROD APK through PAXSTORE.
     - Whitelist `com.bohemian.odoo.pos` and `com.pinelabs.masterapp` for lock task if kiosk mode
       is used.
   - **App ID:** one PROD App ID for every terminal is fine.
6. **Setup on the terminal.**
   1. Enter the store user's key.
   2. Choose the store's POS.
   3. Choose "Pine Labs E700 (A2A)".
   4. Choose the store's Cash method.
   5. Set the printer width.
   6. **Change the Setup PIN from 1234**: anyone who knows it can change the key and the store.
7. **Test under the store user's key, before the first customer.** These calls have only ever run
   under an admin key:
   - catalogue load;
   - `pos.session.load_data` (offers);
   - `product.product.search` on reward domains;
   - `pos.config.use_coupon_code`;
   - `loyalty.card` reads;
   - `res.partner.create` (Save customer);
   - `formatted_read_group` on `pos.order` / `pos.payment` (SALES);
   - `sync_from_ui`, then `confirm_coupon_programs`;
   - session open and close.
8. **First day:**
   - one card, one UPI and one cash sale;
   - one sale with a customer, then check the WhatsApp bill;
   - SALES equals Odoo's Orders Analysis for the store;
   - the cashier on the bill is the store's E700 user.

## Checked and fine

- **What each store sees:** products, menu, offers and ZZ Cash come from that store's own POS. The
  offer lists already differ per store (`CLAUDE.md` round 3).
- **Bill numbers:** Odoo numbers the bills itself (`name` "ZenZebra - 000028", `pos_reference`
  "260-1-000028"). The till sends neither, so two tills can't make the same number.
- **WhatsApp bills:** both WhatsApp bill automations filter on state, a +91 phone and `source not in
  (mobile, kiosk)`, with no store filter. E700 orders (`source = pos`) from every store are covered.
- **Load on Odoo:** about 12 small reads a minute per idle till (the config plus 11 `write_date`
  stamps, `odoo/LiveSync.kt`). SALES adds 3 a minute while it is open.
- **Taxes:** all 51 sale taxes belong to company 1, and every active store is in company 1.
  No POS product carries another company's tax.
- **Prices:** pricelist 1 (on configs 1, 2, 3 and 9) has 8 rules, all on shelf-rental products that
  are not sold in POS. Till prices equal web POS prices.

## Limits to know

- **Pricelists:** the till charges the product's list price and ignores pricelists. A store-specific
  price set through a pricelist would not reach the till. Today there are none on POS products.
- **Companies:** KLJ, SWN and Club 125 are in Noida (UP) and HQ27 is in Gurugram (Haryana), yet all
  sell under company 1 (Delhi GSTIN).
  - **What exists:** "ZenZebra Noida" and "ZenZebra Haryana" have their own GSTINs, but their POS
    configs are archived.
  - **Why the till can't follow yet:** it sends no `allowed_company_ids` context and reads product
    taxes as the key's user sees them.
  - **Before moving stores:** that needs a code change first, so the till reads the store's company
    taxes. Moving stores to their state company is the accountant's call.

## How this was checked

These read-only JSON-2 calls used the key in `.env`:

| Model | Read |
|---|---|
| `pos.config` | stores, company, pricelist, payment methods, session |
| `pos.payment.method` | type, `is_cash_count`, journal, configs, terminal |
| `res.company` | company and state |
| `res.users.context_get` | the key's user |
| `res.groups` | that user's and the store logins' rights |
| `product.pricelist.item` | rules on pricelist 1 |
| `account.tax` | sale taxes by company |
| `pos.order` | E700 orders by store and user, naming |
| `base.automation` | WhatsApp filters |
