# Odoo API engineering: 20 stores on one Odoo Online database (0.6.0)

How the E700 till talks to Odoo so that 20 stores can share one database (and one API key) without
HTTP 429 "Too Many Requests". Written 2026-10-30, after SWN's till got 429s on 29 Oct.

## The rule we live under

- **Odoo's guidance:** Odoo's acceptable use policy (odoo.com/acceptable-use, updated 7 May 2025)
  treats about one call a second as acceptable, unsustained and with no parallel calls. Heavier
  unthrottled API use can get the subscription suspended. Odoo.sh dedicated hosting is the way to
  lift the limit.
- **What the limit counts:** the database, not the API key. One shared key or twenty keys make no
  difference to 429s; only the number of calls and how many arrive at once do.
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

## One shared key

- **The limit:** keeping one key for every store is the owner's decision. It does not change the rate
  limit (see above).
- **Still open:** an administrator key on every till, and every bill showing the same cashier. See
  CLAUDE.md §12–13 and `docs/ROLLOUT_STORES.md`.
