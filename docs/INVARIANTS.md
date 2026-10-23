# INVARIANTS — Bohemian Odoo POS (Pine Labs E700)

Every phase and feature modification in the application must preserve these invariants. A change that violates any invariant is rejected.

| # | Invariant | Enforced In |
|---|---|---|
| **I1** | **All money is `Long` paise.** Banned: `Float` and `Double` for amounts anywhere in money/pricing paths. | `core/Paise.kt` |
| **I2** | **Odoo is the sole authority on price and tax.** The app never computes tax rates locally; line totals come from `account.tax.compute_all`. | `odoo/PricingService.kt` |
| **I3** | **One payment in flight per device.** Enforced via thread-safe Mutex lock. | `payment/PaymentCoordinator.kt` |
| **I4** | **Persist before acting.** A `PaymentAttempt` row (`STARTING`) is saved to Room DB *before* invoking the terminal SDK. | `payment/PaymentCoordinator.kt` |
| **I5** | **No auto-retry on ambiguous card sales.** An ambiguous card sale transitions to `NEEDS_REVIEW` and locks the order against new charges. | `payment/PaymentCoordinator.kt` |
| **I6** | **Amount equality check.** An attempt is marked paid ONLY if the amount returned by the terminal matches the requested `attempt.amount`. Mismatches transition to `NEEDS_REVIEW`. | `payment/PaymentCoordinator.kt` |
| **I7** | **Durable Outbox.** Paid orders are written to a Room Outbox table first. Retries are idempotent because Odoo ignores duplicate order UUIDs. | `odoo/OrderSyncService.kt` |
| **I8** | **Decoupled Printing.** Printing is separate from payment state; print failure never un-pays an order. | `pine/PineSdkGateway.kt` |
| **I9** | **Session Close Protection.** A POS session cannot close with unresolved payment attempts, pending outbox items, or open voids (in review, or voided on the terminal with the Odoo refund not yet recorded). | `odoo/SessionService.kt` |
| **I10** | **Zero Secrets Leakage.** No API keys, keystore passwords, full card PANs, or customer phone numbers in source, committed files, or Logcat. | `core/Redact.kt` |
