---
name: money-safety-review
description: Review any change to the Bohemian Odoo POS payment, pricing, Odoo sync, Pine gateway or receipt code against the 12 HARD RULES and invariants I1–I10. Use before committing changes under payment/, odoo/, pine/, core/Paise, data/, or when asked "is this safe", "review this diff", or before a release build.
---

# Money-safety review

This app moves real money. Check the diff (`git diff` / `git diff --staged`, or the named files)
against every item below. Report each violation as `file:line — rule — what breaks — fix`.
A change that violates any item is **rejected**, not "noted".

## Checklist

| # | Check | How to spot a violation |
|---|---|---|
| I1 | Money is `Paise` (Long) | `Double`/`Float`/`toDouble()` on amounts; Odoo floats not converted once via `Paise.fromRupees(BigDecimal(v.toString()))` HALF_UP |
| I2 | Odoo is the price/tax authority | any local tax %/rate math; totals not from `account.tax.compute_all`; amount sent to Pine ≠ Odoo total |
| I3 | One payment in flight | SDK call path that bypasses `PaymentCoordinator` Mutex or `PineSdkGateway.sdkSlot` (incl. `print`, `GET_STATUS`) |
| I4 | Persist before acting | SDK call before the `STARTING` row is written; blocking work inside `onStarted` |
| I5 | No replay after ambiguity | auto-retry of a card sale after timeout/unknown; card ambiguity not → `NEEDS_REVIEW`; UPI/BQR not resolved via `GET_STATUS` with the **same** billing ref |
| I6 | Amount equality | marking paid without comparing terminal `amount` (string paise → `toLongOrNull()`) to requested |
| I7 | Durable outbox | paid order discarded / not queued on sync failure; outbox retry not idempotent (UUID) |
| I8 | Print decoupled | print failure that changes payment state |
| I9 | Session close protection | session close allowed with unresolved attempts or outbox rows |
| I10 | No secrets | API keys, keystore passwords, PAN, phone numbers in source/logs without `Redact` |
| R6 | Decline ≠ failure | card decline handled in `onFailure` instead of `onResult(status=FAILED)` |
| R8 | Unique billing ref | reused `billing_reference_id` for a new charge; format not `<CFG4><yyMMddHHmmss><rand3>` ≤ 20 alnum |
| R9 | Threading | SDK call on Main; callbacks not marshalled back to Main |
| R10 | App ID | empty Pine `applicationId` reachable |
| R12 | No ghost APIs | Pine SDK types imported outside `pine/PineSdkGateway.kt`; any SDK method not in the AAR (verify with the `pine-sdk` skill) |

## Also check

- New public function in `payment/` or `odoo/OrderSyncService` has a unit test.
- `applicationId` `com.bohemian.odoo.pos` unchanged.
- Nothing git-ignored (SDK bundle, keystore, `.env`, `local.properties`) got staged:
  `git diff --staged --name-only | grep -E 'libs/pine|\.jks|\.keystore|keystore\.properties|\.env|local\.properties'` must print nothing.

Finish with `./gradlew testUatDebugUnitTest` (see the `build-test` skill) and state the result.
