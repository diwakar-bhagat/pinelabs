# Odoo API engineering: 20 stores on one Odoo Online database (0.6.0)

How the E700 till talks to Odoo so that 20 stores can share one database (and one API key) without
HTTP 429 "Too Many Requests". Written 2026-10-30, after SWN's till got 429s on 29 Oct.

## The rule we live under

- **Odoo's guidance:** Odoo's acceptable use policy (odoo.com/acceptable-use, updated 7 May 2025)
  treats about one call a second as acceptable, unsustained and with no parallel calls. Heavier
  unthrottled API use can get the subscription suspended. Odoo.sh dedicated hosting is the way to
  lift the limit.
- **What the limit counts: not published.** Odoo's policy speaks of calls to its systems, so we budget
  as if every till on the database shared one limit (the worst case). Whether a 429 is counted per
  database, per key or per address is not documented.
  - **How we'll find out:** the E700 Tills list in Odoo shows each till's 429s. If they drop once tills
    have their own keys (0.7.0 enrollment), the limit is per key.
- **How Odoo enforces it:** it answers 429, usually with a `Retry-After`, and does not run the
  request.
- **Unknowns:** Odoo publishes no exact threshold, so we budget for the guidance and measure on the
  till (see "Counters").

## Why the till got 429s (0.5.2 and earlier)

| Source | Calls | Why it hurt |
|---|---|---|
| Live sync, every 60 s per till | 13 a minute, 12 of them at once | 20 idle tills ≈ 260 calls a minute (4.3 a second), more than all the selling |
| Catalogue load (start, resume after 30 min, Reload, Retry) | 31–40 within seconds | A chain switched on together ≈ 700 calls at once |
| A 429 during a load | +31–40 every minute | The 429 counted as "offline", and offline made live sync reload everything every minute |
| Every sale | ~13, 7 of them fixed | Bill read back with 4 calls, loyalty searched before booking |
| Retries | — | No jitter: 20 tills came back at the same moment; Retry-After cut to 30 s |
| One queue | — | A sale's `sync_from_ui` waited behind photos and live sync |

## How the till calls Odoo now

### Lanes (`odoo/OdooGate.kt`, `odoo/OdooJson2Client.kt`)

Every call waits in one of three lanes, set by the caller with `withContext(OdooLane.X)`:

| Lane | What | Gives up after a 429 |
|---|---|---|
| `SALE` | `sync_from_ui`, loyalty booking, session open/close, refunds | 20 s, then the order waits in the outbox (never lost, HARD RULE 7) |
| `TAP` (default) | Pricing, customer search, codes, ZZ Cash, the catalogue load | 10 s: "Odoo is busy, try again" |
| `BACKGROUND` | Live sync, the SALES refresh, photos, outbox retries | 2 min; the next pass tries again |

- **In flight:** at most 2 calls per till.
- **Order:** sales first, then the cashier, then background, first-come within a lane.
- **Background calls:** run alone, at most one a second, never while a sale or the cashier waits.

### 429 and 503

- **429:** wait what `Retry-After` says, in seconds or as an HTTP date, plus up to 25%. Without the
  header, wait 2, 4, 8 … s (at most a minute), ±25%.
- **Every other call waits too:** while one call is cooling down, every call from this till waits
  instead of adding to the burst. A wait longer than a lane's budget fails at once, without calling
  Odoo again.
- **503:** sent again only for calls that are harmless to repeat. Those are reads, `compute_all`,
  `load_data`, `get_closing_control_data`, `validate_coupon_programs`, and the uuid-deduplicated
  `sync_from_ui` / `confirm_coupon_programs`. Never a create (`res.partner.create`,
  `pos.session.create`).
- **Not offline:** a 429 never marks the till offline. After a real failure, full reloads back off
  1, 2, 4, 8, then 15 min.

### Live sync (`odoo/LiveSync.kt`)

- **One call after another, in the background lane:**

  | What | How often |
  |---|---|
  | The config and session | Every 2 min |
  | Product and template stamps (prices) | Every 5 min |
  | Menu, taxes, fiscal positions, offers, notes, company, bill text, pricelist | Every 15 min |
- **Outside the sales day** (Setup hours, 06:00–24:00 by default): the config only, every 15 min.
- **Spread:** each till starts at its own random moment. A first load in the 5 minutes after boot
  waits 0–20 s, with the saved catalogue on screen.
- **Resume:** reloads only when Odoo has not been heard from for 30 minutes.
- **The trade-off:** a price changed in Odoo reaches the till within 5 minutes, not 1.

### Each sale

| Call | When | Lane |
|---|---|---|
| `account.tax.compute_all` | Per line not priced since the last catalogue load (cached by taxes, price, qty and fiscal position) | TAP |
| `res.partner.search_read` | Per pause in typing (400 ms), 4+ digits or 2+ letters | TAP |
| `loyalty.card` (+ rewards) | When a customer is attached | TAP |
| `validate_coupon_programs` | Before the charge, only with a ZZ Cash reward or scanned coupon | TAP |
| `pos.order.sync_from_ui` | Once paid | SALE |
| `confirm_coupon_programs` | With a customer earning or spending points; the order id comes from the sync reply | SALE |
| `pos.order.read` (`x_zz_*`) | Customer bills only, after booking; everything else on the bill comes from the sync reply | TAP |

- **Fixed calls per sale:** 1 for a walk-in, 3 with a customer (was 7).
- **Typical sale:** about 4–6 calls.

### Outbox (`odoo/OrderSyncService.kt`)

- **Pacing:** retries every 30 s in the background lane, at most 10 orders per pass, one at a time.
- **Backoff:** 30 s, 1, 2, 4, 8, then 15 min, ±25%.
- **Session close:** sends everything at once, in the sale lane.

### Accidental taps (`ui/Clicks.kt` and guards in `ui/PosViewModel.kt`)

- **In the ViewModel:** every action that calls Odoo or the terminal sets its busy flag before
  anything waits, so a second tap returns.
- **On screen:** a 600 ms throttle on the buttons as a second layer.
- **Split Charge:** also checks what was paid when it was tapped. A second tap queued behind a paid
  first one is refused, never sent to the terminal again.

## The budget at 20 stores

Assumptions:
- ₹25,000 per store per day, about 170 bills at a ₹150 ticket, so 3,400 bills a day;
- 06:00–24:00;
- a peak hour of about 25 bills per store.

| | 0.5.2 | 0.6.0 |
|---|---|---|
| Idle traffic | 13 a minute per till, about 281,000 calls a day | About 1.6 a minute per till, about 35,000 a day |
| Selling | About 13 a bill, about 44,000 a day | About 6 a bill, about 20,000 a day |
| Average | About 5 calls a second, bursts of 12 per till | About 0.85 calls a second, at most 2 in flight per till |
| Peak hour | About 10 a second | About 1.4 a second |

## Counters

- **On Setup:** "Odoo calls: N a minute (sale, cashier, background in the last 10 min) · Odoo busy
  (429) today: N, last at hh:mm".
- **In the log:** an `ODOO_RATE` line every 10 minutes.
- **Targets per till:**
  - idle, about 2 calls a minute or fewer;
  - selling, under 10 a minute;
  - no 429s on a normal day.
- **Check this** on every store visit for the first weeks.

## If the counters show 429s anyway

In this order:

1. **One Odoo server action** (Settings → Technical, no module needed). It answers the minute check
   in one call (config plus every stamp) and prices a whole cart in one call. It is still Odoo's own
   `compute_all`, so I2 holds. This takes the till to about 0.3 calls a minute idle and 1 per cart
   change.
2. **A ZenZebra hub:** one small server between the tills and Odoo. It caches the catalogue, takes
   Odoo's webhooks, queues orders and holds the Odoo key instead of the tills.
3. **Odoo.sh:** the dedicated hosting the policy names to lift the limit. It costs more, and was
   ruled out earlier for other reasons (CLAUDE.md §1).

## Fail-safe architecture (0.7.0)

Built on Odoo's external API docs (18.0, 19.0 JSON-2) and the live database.

**Rules:**
1. **Never lose, never double:** the journal, the outbox and the snapshot are unchanged (I3–I10).
2. **Fail closed for money, open for selling:** Odoo changed under us → stop charging; Odoo only out of
   reach → keep selling what Odoo already priced.
3. **Every failure gets a kind and a behaviour.** Nothing is retried blindly forever without telling
   anyone.
4. **One write = one idempotent call:** JSON-2 gives each call its own transaction and cannot chain
   them.
   - `sync_from_ui` is deduplicated by the order uuid.
   - Refunds use a uuid derived from the void.
   - Loyalty booking is deduplicated by Odoo.
   - A customer is looked up before being created.
5. **Each till is known to Odoo:** its own key, its `User-Agent`, its status row.

### Error kinds (`odoo/OdooErrors.kt`)

| Odoo 19 answer | Kind | What the till does |
|---|---|---|
| Network, 408, 429, 502–504, `LockError` 409, serialization, a Wi-Fi login page | TRANSIENT | Backoff with jitter |
| `UserError` "No open session available" (422) | NO_SESSION | Order waits, no attempt counted; sent when a session opens; gold bar |
| 401, `AccessDenied` (403) | AUTH | Red bar; background calls probe every 5 min; sales stay on the till |
| `UserError` / `ValidationError` 422, `AccessError` 403, `MissingError` / `NotFound` 404, Python errors 500 (`builtins.ValueError` for an unknown field) | REJECTED | Kept, retried hourly; red bar with Odoo's message and "Retry now"; blocks session close |

### Contract check (`odoo/OdooContract.kt`)

- **The list:** every field the sync payloads write (the payload tests fail if one is not listed) and
  the ones selling reads.
- **When:** once a day the till reads `GET /web/version`; a new version (Odoo Online upgrades in place)
  triggers `fields_get` on 7 models.
- **If a field or model is gone:** payments pause with a red bar naming it; browsing goes on.
- **Today:** all 78 fields are present (30 Oct).

### Selling while Odoo is out of reach

- **The cache:** Odoo's `compute_all` answers are kept in Room (`price_cache`), keyed by applied taxes,
  unit price, qty and fiscal position, and tagged with Odoo's tax stamp (newest `account.tax` and
  `account.fiscal.position` `write_date`).
- **When it clears:** only when the stamp changes.
- **When Odoo is out of reach** (no answer for 3 min, or the saved catalogue on screen), a cart Odoo
  priced before sells by cash, card or UPI. The order waits in the outbox and the till prints its own
  bill.
- **Refused offline:** taxes not confirmed for 24 h; the pricelist unread since start; an item Odoo never
  priced; ZZ Cash redemptions and coupons; new customers.
- **A tax changed during the outage** makes Odoo refuse the total: REJECTED, for a supervisor to book the
  difference.

### A key per till (`odoo/KeyRotation.kt`)

- **Accounts:** one bot user (one Odoo seat), one key per till, named "E700 <store> <serial>".
- **Enrollment:** Setup turns an enrollment key into the till's own key with
  `res.users.apikeys.generate(scope="rpc", expiration_date=+85 days)`, checks it, and keeps only that one.
- **Renewal:** 14 days before expiry: generate → save as pending → verify → switch → `revoke(old)` with
  the new key. Any failed step leaves a working key.
- **Odoo side:** `base.enable_programmatic_api_keys = True` and `base.programmatic_api_keys_limit = 50`
  (`tools/odoo/enable_till_keys.py`).
- **A lost till:** delete its key; the others keep working.

### Status record (`odoo/TillStatus.kt`)

- **What is written:** every 15 min, each till writes its row in `x_e700_till`:
  - version and last seen;
  - unsent and refused orders, payments to check, sales not sent at once;
  - calls per minute, 429s today;
  - last error and key expiry.
- **Where to see it:** Odoo → Point of Sale → Reporting → E700 Tills (the model is created by
  `tools/odoo/create_till_status_model.py`). Red marks refused orders or payments to check; amber marks
  unsent orders.

## Pacing and the catalogue load (0.7.1)

A rush-hour simulation (`PeakHourSimulation.kt`, run only with `PEAK_SIM=1`) used a fake Odoo
modelled as about 1 call a second with bursts of 3 per database. That limit is an estimate. It showed
the refusals came from short bursts, not overall volume.
- **Pacing:** each till now starts about one Odoo call a second, two at once after a quiet spell
  (`OdooGate`). A short planned wait costs less than a 429 and its 2 s backoff.
- **The fallback bill read:** one call at a time.
- **The cost:** a full catalogue load (~35 calls) would keep the till from selling for ~35 s. So the
  load comes in two steps:
  - **First, what selling needs:** config, products in pages of 1000, taxes and fiscal positions,
    offers, the pricelist hold in one combined search, and the payment methods. The till sells once
    these are in.
  - **Then, in the background:** menu tabs, notes, address, bill text, tax names and ZZ Cash rules.
- **Tuning:** the pace is a constant (`Throttle.perSecond` / `burst`). Raise it once the E700 Tills list
  shows real Odoo answers without 429s.

## Odoo's POS loader (0.8.0)

The web POS hardly calls Odoo: it loads everything once with `pos.session.load_data`, then asks only for
what changed (`pos_last_server_date`). Since 0.8.0 the till does the same whenever a session is open
(`odoo/PosLoad.kt`).

**Measured live** (31 Oct, read-only, ZenZebra session):

| Call | Size | Time |
|---|---|---|
| Full load, 16 models | 3.4 MB | 2.0 s |
| Changes in the last 24 h | 138 KB | 0.4 s |
| A quiet 2-minute poll | 30 KB | 0.3 s |

- **Same products as before:** built from the loader, the till's products (2,510 at ZenZebra and SWN) equal
  its own reads exactly: ids, prices, taxes, tabs, HSN, barcodes, codes and names
  (`LiveLoaderCheck`, run by hand with `LIVE_ODOO_ENV`).
- **A catalogue load:** one loader call gives config, products, taxes, fiscal positions, offers, ZZ Cash
  rules, menu, notes and payment methods.
  - **Extra calls:** the pricelist hold adds 2 when the store has a pricelist. The company's second
    address line and the bill template follow in the background.
  - **The result:** 2–4 calls instead of ~25.
- **Live sync:** one loader call per pass, every 2 minutes (every 15 outside the sales day).
  - **What it replaces:** the config read and every change stamp.
  - **Within 2 minutes:** prices, taxes, offers, menu and payment methods (was 5–15).
  - **Every 15 minutes:** the sellable product ids drop products archived or taken out of the POS. The
    loader never sends those.
  - **Every hour:** the bill template.
- **The "since":** Odoo's clock (HTTP `Date`) less 5 minutes. Odoo echoes the date sent instead of moving
  `_data_server_date`. Records seen twice merge by id, and a record equal to the one held is no change.
- **Taxes:** a fingerprint of Odoo's tax definitions keeps the saved prices (F3) valid.
- **A new Odoo day:** a full load, which brings in today's dated offers.
- **No open session, or the loader refuses:** the separate reads of 0.7.x take over, unchanged. After a
  refusal (anything but a network error, a rejected key or HTTP 429) they serve for an hour.
- **Idle budget per till:** about 0.6 calls a minute (0.7.x: ~1.6; 0.5.x: 13), about 0.9 MB an hour of data.

## Live rush (31 Oct, 0.8.0, read-only)

How many stores one key carries when every store is at its peak at once, measured on the live database with
the till's key (`tools/odoo/billing_rush.py --model 0.8`, 12:52–13:02 IST, owner's yes).

- **The rush:** simulated stores billed like the till (same calls, domains and pacing), each at KLJ's busiest
  hour in 60 days (35 bills an hour). Stores were added every minute until Odoo refused a call.
- **The bill mix, from 60 days of live orders:** 1.86 lines a bill, 87% with a customer. 15% of lines were
  not priced before on that till, so they need `compute_all`. That makes about 4.4 calls a bill, and a fifth
  of all calls were background.
- **Nothing changed in Odoo:** a bill's two writes (`sync_from_ui`, `confirm_coupon_programs`) were sent as
  one read each, so Odoo counted the same number of calls. About 1,050 calls in 10 minutes.

| Stores at peak together | Calls a second | Odoo |
|---|---|---|
| 5 – 25 | 0.3 – 1.4 | all answered |
| 30 | 2.0 | all answered |
| 50 | 2.3 | all answered |
| 60 | 3.2 | all answered |
| 80 | 4.3 | first 429 after 47 s |

- **Answers stayed fast:** 95% of calls within about 0.23 s at every step. Bursts of 15 calls in one
  second were accepted.
- **Where the limit sits:** the first 429 came at about 3.5–3.8 calls a second kept up for 1–2 minutes.
  No simple shape (fixed window, sliding window, token bucket) fits only the rush's own calls; other callers
  on the key or database likely count too.
- **What the 429 looks like:** a plain HTML page with no `Retry-After`. The till backs off 2 / 4 / 8 s.
- **One machine:** every simulated store called from one machine. If Odoo limits per network, each real store
  has its own allowance, and the key is not the bottleneck.
- **Capacity on 0.8.0:** about 56 stores at full peak together before the limit; plan for about 40, keeping
  30% for other callers and for the heavier writes.
- **Real traffic:** the busiest hour across all stores so far was 66 bills (28 Oct, 14:00), about 5% of the
  limit.
- **Not measured:** real writes. The limit counts requests, but Odoo's load under real `sync_from_ui` calls
  needs a duplicate of the database.

## Fewer calls per bill (0.9.0)

The rush showed where the calls go: the customer search plus the card read were 43% of them, and live sync a
fifth. Data structures help only where they remove a call; the till's own work takes microseconds.

- **Customer index** (`odoo/CustomerIndex.kt`): every customer with a mobile, in memory on the till (3,281
  on 31 Oct, read in 1.8 s in 2 calls).
  - **The structure:** two sorted arrays, the Indian numbers by their 10 digits and every number by its
    digits reversed. The first or last digits typed are a binary search, then a scan over the matches.
  - **Same answers as Odoo:** checked live on 20 digit queries, 119 matches (`LiveCustomerIndexCheck`).
  - **Kept up to date:** read in full once a day; otherwise customers changed in Odoo since the last read
    (`write_date`, archived ones too) every 15 minutes. A miss asks Odoo, as before (a customer saved at
    another store since).
  - **Saving a customer:** still checked against Odoo first, so nobody is created twice.
- **One call per picked customer:** `loyalty.card.web_search_read` returns the card's points and the
  customer, nested, as Odoo has them now (`ZzCashService.card`).
  - **Changed details:** a GST detail changed since the index was read reprices the bill under its new
    fiscal position.
  - **Typed but never picked:** such a number is still read from Odoo at payment.
- **Live sync in a rush** (`odoo/LivePace.kt`): every 5 minutes instead of 2 while the till has sold 3
  bills within 10 minutes, or Odoo answered 429 within them.
- **The result:** about 3.1 calls a bill instead of 4.4, and about 37% fewer calls per store at peak.
  That is about 90 stores at full peak together before the limit, or about 65 to plan for.

## One shared key

- **The limit:** keeping one key for every store is the owner's decision. If Odoo counts per key, a
  shared key also means a shared limit; if per database, it makes no difference (see above).
- **Still open:** an administrator key on every till, and every bill showing the same cashier. See
  CLAUDE.md §12–13 and `docs/ROLLOUT_STORES.md`.
- **Since 0.7.0:** a key per till of one bot user replaces the shared key (Setup → "Enroll this till");
  "Use this key" keeps a shared key working.
