---
name: odoo-json2
description: How the Bohemian POS talks to Odoo 19 (zenzebra1.odoo.com) over the JSON-2 API, the verified live-DB facts, and how to check a model/field/method before coding against it with the odoo-json2 MCP tools. Use for any change to odoo/ services, sync_from_ui payloads, sessions, payment methods, customers, ZZ Cash, or when asked to look something up in Odoo.
---

# Odoo 19 JSON-2

```
POST https://zenzebra1.odoo.com/json/2/<model>/<method>
Authorization: bearer <api key>      X-Odoo-Database: zenzebra1
Body: named args — ids, domain, fields, limit, context, vals, …
```
XML-RPC / JSON-RPC are deprecated in 19 and removed in 22 — **never use them**. Private `_x` methods are not callable.
Odoo Online: no custom Python modules; server-side logic goes in **server actions / automations**.

## Verify before you code

Use the plugin's MCP tools (read-only unless `ODOO_MCP_ALLOW_WRITE=1`; this is the **production** DB):
- `odoo_fields(model, filter)` — confirm a field exists and its type.
- `odoo_search_read(model, domain, fields, limit)` / `odoo_search_count`.
- `odoo_call(model, method, args)` — e.g. `account.tax.compute_all`.

Never test `sync_from_ui`, `create`, `write` or session open/close against production from Claude.
Capture a real web-POS request instead and compare with `tools/payload_diff.py`.

## Live facts (checked 2026-10-23)

| Item | Value |
|---|---|
| API user | Ankur (res.users 28, internal user) — CLAUDE.md wants a POS-User-only user per terminal |
| `pos.config` | 1 ZenZebra, 2 KLJ, 3 SWN, 4 HQ27GGN, 8 Club 125, 9 ZZ Café HO, 10 ZZ Café KLJ |
| E700 payment method | **22 "Pine Labs E700 (A2A)"**, type bank, `use_payment_terminal` empty — on config 1 only |
| Cash (`is_cash_count`) | ids 2, 11, 14 (id 4 "Cash" is type bank) |
| Other Pine methods | 10, 18, 19, 20 use the `pine_labs` terminal integration (web POS) — not ours |
| ZZ Cash | `loyalty.program` 4, loyalty, active |
| Sellable POS products | 2,396 (`available_in_pos` and `sale_ok`) |

## Rules carried from CLAUDE.md

- Taxes: `round_per_line`, INR, mostly price-included GST groups; totals only from `account.tax.compute_all`.
- Orders: `pos.order.sync_from_ui(orders=[…])`, lines/payments as `[0, 0, {...}]`; server rejects paid ≠ total;
  `state: "paid"` only after Pine confirms; idempotent by order UUID.
- Sessions: `pos.session.create({config_id})` → `set_opening_control(cashbox_value, notes)`; close `close_session_from_ui()`.
- Automations (WhatsApp bill, ZZ Cash expiry, dashboard webhook) must include the E700's `pos.config` in their filters.
