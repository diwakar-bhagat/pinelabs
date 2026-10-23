---
name: uat-e700
description: Run and record the 10-case Pine Labs UAT script for the Bohemian POS on the UAT E700 (SN 1630005644), and turn results into fixes or resolved open items. Use when preparing for, running, or reporting on UAT, or when the user pastes on-device results or logcat.
---

# UAT on the E700

Prereqs: UAT flavor build installed via PAXSTORE (or ADB for dev), UAT Pine App ID, an open
session on the E700's `pos.config`, payment method 22 "Pine Labs E700 (A2A)" on that config,
E700 config id added to the WhatsApp / ZZ Cash automation filters.

The cashier runs the cases; Claude prepares the sheet, then reads results and logcat.

| # | Case | Expected | Record |
|---|---|---|---|
| 1 | Card sale ₹1 | SUCCESS, Odoo order paid, receipt printed, WhatsApp bill | order name, RRN |
| 2 | Card declined | DECLINED, order open, retry allowed | |
| 3 | UPI, pay in time | SUCCESS | |
| 4 | UPI, let QR expire | GET_STATUS → declined/expired, order open | |
| 5 | UPI, pay, kill app before result | Reconciler → GET_STATUS → SUCCESS, synced | |
| 6 | Bharat QR | SUCCESS | |
| 7 | Wi-Fi off after success | outbox, red badge, syncs on reconnect | |
| 8 | Void card sale same day | void accepted | **which field gave `bank_code`** |
| 9 | Printer out of paper | payment SUCCESS, reprint offered | |
| 10 | Double-tap Pay | second tap ignored | |

After each run, check the Odoo side read-only with the `odoo-json2` MCP tools
(`pos.order` by `pos_reference`, its `payment_ids`). Collect logcat with
`adb logcat -s pine_billing PosLog` and **redact** phones/PANs before pasting.

UAT closes these CLAUDE.md open items: `bank_code` (8), printer width in chars (any receipt),
`GET_STATUS` relevance for card (2/5), `charge_slip_print_data` vs MasterApp auto-print (1).
Update CLAUDE.md §13 when one is resolved and say so in the commit message.
