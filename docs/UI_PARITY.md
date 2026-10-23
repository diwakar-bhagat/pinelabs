# UI parity with Odoo 19 POS — ZenZebra till

Goal: a cashier on the E700 should see the same screens, buttons and money flow as the Odoo
POS on the browser. This file lists each screen and the Odoo source it follows.

**Reference only.** The Odoo source is LGPL-3.0. We read it to learn the flow and rules and
write our own Kotlin. We do not copy files into this repo. The reference clone lives outside
the repo (sparse clone of `odoo/odoo` branch `19.0`: `addons/point_of_sale`, `addons/pos_loyalty`,
`addons/l10n_in`, `addons/l10n_in_pos`).

Sources: `addons/point_of_sale/static/src/app/screens/…`, `addons/pos_loyalty/…`.
Screens in the live till (screenshots from the browser POS, config 1 ZenZebra, 2026-10-23).

## 1. Product screen (`product_screen/`)

| Component | What it does | Odoo source | Status in app |
|---|---|---|---|
| Top bar: Register / Orders | Switch between the till and the order list | `product_screen.xml` | Not built |
| Order tab (+ and `4014`) | New order; the number is the order tab | `control_buttons` | Partly (one order at a time) |
| Search products | Filters the grid; "Search more" widens it | `product_screen.js` | Built (search) |
| Barcode icon | Scanner input | `barcode` service | Built (wedge input) |
| Category tiles | Filter grid by POS category (Chai & Coffee, Bites, WhatsApp Menu, WA HO, WA KLJ) | `product_screen` categories | Not built |
| Product grid | Image, name; coloured bar = category | `product_screen.xml` | Built (no images) |
| Order panel: lines | Name, qty, price | `order_summary/` | Built |
| Taxes / Total | GST shown inside the price | `order_summary/` | Built |
| Points | Loyalty balance `24.6 +149.93 = 174.53` (before · earned · after) | `pos_loyalty` | Balance only (Phase 3a) |
| Customer button (`Diwakar`) | Pick or change the customer | `partner_list/` | Built |
| Note | Order note | `control_buttons` | Not built |
| Pickup | Order type | `control_buttons` | Not built |
| Send icon | Send order to kitchen / print | `control_buttons` | Not built |
| ⋮ Actions menu | Note, Customer Note, Enter Code, Reward, Reset Programs, Quotation/Order, Default (INR), Refund, Cancel Order | `action_pad/` | Reward only (Phase 3b) |
| Numpad 1–9, 0, +/−, `.` , backspace | Edits qty or price of the selected line | `numpad` | Not built (needs line selection) |
| Qty / % / Price | Mode of the numpad | `numpad` | Not built |
| Payment | Opens the payment screen | `action_pad/` | Built |
| UPI (quick pay) | Opens payment with UPI preselected | `action_pad/` | Not built |

## 2. Rewards popup ("Available rewards")

Opens from ⋮ → Reward. Lists each reward the customer can use, as
`ZZ Cash — Add "₹50 off (50 ZZ Cash)"`, with a chevron. Matches `pos_loyalty`
`loyalty.reward` rows (the 10 ₹50–₹500 "per order" rewards for program 4).

Status: the app applies the reward from the cart. The popup list is in the cart's "Use ₹N ZZ Cash"
buttons (max 3). **Next:** show the full list in a dialog with the same wording.

## 3. Payment screen (`payment_screen/`)

Layout, top to bottom (from the screenshot):

1. **Method tiles** (2 columns): Razorpay, Cash, Cards, Razorpay Online, UPI, Pine labs,
   Pine Labs E700 (A2A). Each tile adds a payment line for that method.
2. **Payment line list**: one row per line — method name and amount, with ✕ to remove.
   The selected line is highlighted. Money is split across lines here.
3. **Change** row (green), shown when cash overpays.
4. **Customer** button and **Invoice** toggle.
5. **Numpad** with `+10`, `+20`, `+50` quick-add buttons (green). They add to the selected line.
6. **Back** and **Validate**.

Rules (`payment_screen.js`):
- Due = total − paid. A new line is pre-filled with the due amount.
- A non-cash line cannot exceed the due amount ("The amount cannot be higher than the due amount
  if you don't have a cash payment method configured").
- Only cash can overpay. The overpay is the change.
- Validate is enabled when paid ≥ total.
- Terminal lines (Pine E700) cannot be edited while a payment is running on the terminal.

Status: **split-payment rules built** (`payment/PaymentSplit.kt`, 13 tests). **Not built:** the
screen itself, method tiles, numpad, Invoice toggle, and the E700 terminal line.

## 4. Receipt (`receipt_screen/`)

Odoo shows "Payment Successful", the amount with **Edit Payment**, **Print Full Receipt**, an
email field with a send button, then the ticket. Ticket: "Tax Invoice <number>", date, "All prices
are inclusive of GST", served by, customer name and phone, lines with HSN code, taxable value,
SGST/CGST, total and payment lines, "Need an invoice?" with the ticket link and short **code**, the
HSN summary, the company block (name, address, GSTIN, phone, email, website).

The ticket link QR on the browser receipt is the e-bill link (`/pos/ticket?code=…`).
Status: **not built.** Printing on the E700 printer is a separate step (see `docs/PLAN.md` Phase 2).

## 5. Open items before this matches the browser

- Category tiles and product images.
- Numpad with line selection (qty, price, %).
- Payment screen (method tiles, line list, numpad, Invoice toggle, Validate).
- Full rewards dialog.
- Receipt screen and ticket content (HSN, SGST/CGST split, code, company block).
- E700 terminal line inside the split.
