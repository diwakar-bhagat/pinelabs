# ZZ Cash — Phase 3 notes

ZZ Cash is Odoo's loyalty program **id 4** (INR, 30 rules, 10 rewards, `trigger: auto`,
`applies_on: both`, not portal-visible, no expiry dates, no usage limit). About 2,860
loyalty cards exist.

## What the app does now (Phase 3a — shipped)

- `odoo/ZzCashService.kt` finds the program for the till's config (`loyalty.program`
  `search_read`: `pos_ok`, `program_type = loyalty`, company, `pos_config_ids` empty or
  containing the config; exactly one match, else ZZ Cash is hidden), then reads the
  customer's card (`loyalty.card` on `partner_id` + that `program_id`) in paise.
- `redeemable` is the balance floored to whole rupees: rewards are whole rupees.
- The cart shows `ZZ Cash ₹X` next to the customer, only for the customer on screen.
- A failed lookup never blocks a sale; the badge is just hidden.

Read-only. No write to Odoo.

## What the Odoo POS already does (verified in Odoo 19 source)

Source: `addons/pos_loyalty/models/pos_order.py`, branch `19.0`.

- `pos.order.validate_coupon_programs(point_changes, new_codes)` — checks that every
  card in `point_changes` still exists and is in an active program, that its balance
  covers the negative change (redemption), and that new codes are unused. It returns
  `{ message, removed_coupons, updated_points }` on failure.
- `pos.order.confirm_coupon_programs(coupon_data)` — creates newly awarded cards, applies
  point changes, and writes `loyalty.history` lines. Gift-card and e-wallet programs are
  handled separately.
- Reward lines on an order carry `is_reward_line`, `reward_id`, `coupon_id`,
  `reward_identifier_code`, `points_cost` (`_get_fields_for_order_line`).

So **the server already enforces the balance**. Our app must still send the correct
payload so the server can apply it.

## Decision: no custom server action

PLAN.md Phase 3 proposed custom server actions (`zz_cash_quote`, `zz_cash_apply`).
Odoo 19 already provides the quote (the card balance) and the redemption check
(`validate_coupon_programs`), so custom actions would duplicate that logic. CLAUDE.md
says loyalty rules must not be reimplemented in Kotlin, and this keeps them in Odoo.
The plan wording should be updated to match.

Existing ZZ Cash server actions in production (read-only check, 2026-10-23):
- `1391 Reply with ZZ Cash balance` (WhatsApp bot, `code`)
- `1277 Auto WhatsApp bill (ZZ Cash stores)` and `1283 Auto WhatsApp first ZZ Cash bill`
  (state `whatsapp`)

These must not be changed by the POS work.

## Phase 3b — redemption (built, not yet run on a device)

- Cashier taps **Use ₹N ZZ Cash** in the cart (only rewards the card can pay for, and that
  stay below the bill). The reward is priced by Odoo's compute_all as a negative line with the
  cart's single tax rate. Mixed-rate carts are refused.
- The sale sends a reward line exactly as the captured web POS sale did: `is_reward_line`,
  `reward_id`, `coupon_id` (card), `points_cost`, and a random `reward_identifier_code`.
  The code is stored in the sale snapshot, so a retry sends the same code.
- Diffed against the captured sale (`tools/payload-ref`): every reward-line field matches.
  Remaining differences are present for ordinary lines too (tax-id format, web-only fields).
- Tests: `ZzCashRedemptionTest`, `ZzCashRewardCatalogTest`, `ZzCashPayloadTest` (the captured sale).
- Not yet verified: a redeemed sale through the E700 (Pine) payment path, and a redemption
  that syncs after a crash (outbox retry with the same code).

## Open before redemption can ship (Phase 3b)

1. **Captured payload.** Confirm the exact `sync_from_ui` order JSON for a redeemed
   reward: the reward line's price and tax, `point_changes` / `coupon_data` keys, and
   the `reward_identifier_code` format. Method: run one redeemed sale in a web POS on
   the **UAT** config and capture the request. Do not guess these keys (CLAUDE.md §0).
2. **Totals.** The reward is a per-order discount. Odoo recomputes totals, and the
   server rejects paid ≠ total (HARD RULE 2). The app must show the Odoo-computed total
   after the reward, not a locally computed one.
3. **Idempotency.** A redeemed sale must be retried from the outbox without double
   deducting points. Odoo dedupes by order UUID; check that the coupon change is part of
   the same transaction.
4. **Ambiguous outcome.** Card payments follow HARD RULE 5 (`NEEDS_REVIEW`, no retry).
   Points are only deducted when the order syncs, so a failed sync never deducts them.
5. **UAT case.** Add a UAT case: redeem ₹50 on a card with ≥ ₹50, then sync, then check
   the card balance dropped by exactly ₹50 and `loyalty.history` has one `spent` line.

## Not yet verified (needs live data or a test customer)

- Whether `validate_coupon_programs` is called over JSON-2 from outside the POS
  front end, and with what argument names.
- Behaviour when a customer has two ZZ Cash cards (the service takes the first).
