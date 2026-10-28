# Case study: Odoo web POS took ₹42 by Pine Labs on a ₹20 bill (HQ27GGN, 2026-10-28)

This happened in **Odoo's own web POS** at HQ27GGN (pos.config 4), with Odoo's Pine Labs Cloud
integration (`pos_pine_labs`). It is not the E700 app. It is kept here because each step that went
wrong is something our till must never allow.

Sources: live Odoo records (read-only, 15:43 IST) and the Odoo 19 source of `pos_pine_labs` and
`point_of_sale`. Staff and customer names, and full Pine references, are left out (public repo).

## What the cashier saw

Order 14630 on the payment screen:
- total **₹20.00**;
- a "Pine labs" line of **₹42.00**, "Payment Successful";
- "Change −₹22.00".

Validate did nothing but show "Cannot return change without a cash payment method".

## State in Odoo

| | |
|---|---|
| Order | pos.order 16019, ticket 14630 (`2614-4-000630`), session HQ27GGN/00227 (open) |
| State | `draft`: total ₹20, paid ₹42, return −₹22, no customer |
| Lines | 1 × Dairy Milk 20.2g, ₹20 |
| Payment | Pine labs (method 10), ₹42, done, UPI SALE, approval ••••0926, Plutus ref ••••4400 |
| Effect | ₹42 reached ZenZebra through Pine, and Odoo has booked nothing yet |
| Session | Odoo refuses to close a session with draft orders ("Pay or cancel the following orders") |

## Timeline (IST)

| Time | Event | Evidence |
|---|---|---|
| 15:00:55 | The previous bill is paid by Pine and reaches Odoo 4 s later | normal flow |
| ~15:05 | Odoo sends **₹42** to the terminal | `payment_ref_no` ends `/INR/42/1`; Odoo writes the line amount into it at send time |
| 15:06:26 | Pine approves ₹42 by UPI | `payment_date`, approval number |
| 15:06–15:23 | The order is not completed | 16 of the 18 earlier Pine sales that day reached Odoo 2–7 s after approval; this one first reached Odoo at 15:23:53, as a draft |
| 15:25:28 | The bill changes to a single ₹20 Dairy Milk line | line `create_date` |
| after | Validate is refused: paid ₹42 is more than the ₹20 total, and there is no cash method to give change | `order_payment_validation.js` |

**The bill was ₹42 when the payment was sent; nobody typed ₹42 by hand.** HQ27GGN has no Cash-type
method, and in that case Odoo will not let a card or UPI line go above the amount due
(`payment_screen.js`, `updateSelectedPaymentline`). So the ₹42 was the amount due at that moment.

## Why: four Odoo behaviours together

1. **Leaving the payment screen stops the terminal check.**
   - `payment_pine_labs.js` stops polling Pine once the screen is no longer PaymentScreen.
   - Back has no guard (`pos_store.onClickBackButton`).
   - The payment stays live on the terminal, the customer pays, and Odoo never hears about it.
2. **A bill can be edited after the terminal has taken money.** Odoo 19 does not lock the order lines
   when a terminal payment is approved or still in progress.
3. **The approved amount is never compared with the bill.**
   - When the cashier comes back to the payment screen, the module's `onMounted` patch starts polling
     again and receives `TXN APPROVED`.
   - It marks the old line "done" without checking it against the new total.
   - This path does not auto-validate.
4. **"Cash" at HQ27GGN is not a cash method.**
   - Payment method 4 "Cash" is type Bank with `is_cash_count` false. KLJ, SWN, HQ27GGN and Club 125
     all share it.
   - With no cash method, Odoo cannot give change, so an overpaid bill can never be validated.

Odoo keeps no log of the buttons pressed, so the most likely sequence is this:
- The ₹42 was sent, and the cashier pressed Back while the customer was paying by UPI (1).
- The bill was cut to ₹20 (2). This was either a correction or the open order being reused for the
  next customer.
- Back on the payment screen, the old approval landed on the new bill (3), and it could not be
  finished (4).

Two facts are certain: ₹42 was sent and approved, and the bill was edited to ₹20 afterwards.

## Fix for this order (at the till, before the session closes)

Do not cancel the order: that would lose the record of the ₹42 that was received. The fix depends on
what the customer actually took:
- **The customer took ₹42 of goods:**
  - Add back the items that were removed, so the total is ₹42.
  - Validate. Paid equals the total, and nobody is owed anything.
- **The customer took only the ₹20 item and is still there:**
  - Give back ₹22 in cash.
  - On the payment screen, add **Cash** −₹22 (type 22, then +/-), then Validate.
  - Odoo books Pine +₹42 and Cash −₹22, which matches the money.
- **The customer took only the ₹20 item and has left:**
  - Refund ₹22 on the UPI transaction from the Pine Labs merchant portal. A UPI refund goes back to
    the payer's account.
  - Record −₹22 on **UPI**. A Pine line cannot take a negative amount.
  - Tell the accountant that the Pine journal shows +₹42 while Pine settles ₹20.

## Prevention

- **Cashiers:**
  - While a Pine line says "Waiting for card", do not press Back and do not change the bill.
  - If the customer changes their mind after paying, finish the bill and refund it from Orders.
- **Odoo settings (owner and accountant to decide):**
  - Give each store a real Cash-type method so Odoo can hand back change instead of getting stuck.
    The trade-off is that an overpayment would then pass silently.
  - Take the shared "Pine labs" method 10 off ZenZebra (config 1) and Club 125 (config 8). A payment
    started at either one rings HQ27GGN's terminal. Club 125 has its own method 18.
- **Odoo support (Enterprise):**
  - Report that the Pine Labs integration lets a bill be edited after an approved payment and never
    compares the approved amount with the bill.
  - On Odoo Online we cannot patch that module ourselves.

## Also found: a ₹50 sale marked paid without approval

HQ27GGN-000616 (₹50, 14:01) has a Pine line that is "done" but has no approval number and no payment
mode, which means it was forced done by hand. Six minutes later, 000617 is the same ₹50 item, and
that one was approved.

Look up the first Plutus reference in the Pine portal:
- if it was never approved, Odoo shows ₹50 from Pine that never arrived;
- if it was approved, check whether the same customer paid twice.

## How the E700 till prevents this

| Odoo web POS | E700 till |
|---|---|
| Back leaves a live terminal payment unwatched | Back is swallowed while a payment runs (`ui/MainActivity.kt`, `BackHandler`) |
| The bill can be edited after money is taken | The cart is frozen while paying and while part-paid (`PosViewModel.editCart`) |
| The approved amount is never checked | Paid only if the terminal's amount equals the amount asked (I6, `PaymentCoordinator`) |
| The order is rebuilt from the edited cart | Odoo receives the snapshot taken before charging (I4, `SaleSnapshot`) |
| A card or UPI line can exceed the bill when a Cash-type method exists | A card or UPI line is always capped at what is due (`PaymentSplit`) |

**Lesson for the rollout:** the till treats a method as cash only when `is_cash_count` is set. At KLJ,
SWN, HQ27GGN and Club 125, "Cash" is a Bank method, so an E700 there could take exact cash only and
give no change. This needs a decision before the E700 goes to those stores (CLAUDE.md §13).
