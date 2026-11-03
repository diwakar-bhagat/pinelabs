#!/usr/bin/env python3
"""
Repair the till customers Odoo's Brand rule emptied (3 Nov 2026, docs/ZZ_CASH.md "Brand rule"), and
re-send today's WhatsApp bills that never went out.

Until tools/odoo/fix_brand_rule.py, every customer the till saved under a Sales-team member's key became a
Brand: x_contact_kind 'brand', x_first_name emptied, the number moved from phone to x_brand_phone. With no
phone the WhatsApp bill never went and the till could not find them by number, so some were saved twice.

Repaired: contacts a Sales-team member created that the rule made Brands, with a +91 mobile kept in
x_brand_phone, no email, no company, no parent, and at least one POS order (the rule's own test for a store
customer). They get phone back, x_contact_kind 'customer', and Odoo's own "ZZ: fill first name from name"
(action 1392) runs on them. Listed, not changed: such contacts without orders, and every number held by
more than one contact (with their orders and ZZ Cash cards) for an admin to merge by hand.

--resend-today: today's (IST) paid orders with a +91 customer and no WhatsApp message, older than 30 min,
get the bill the automation would have sent: action 1283 for a first ZZ Cash bill, else 1277.

Dry run by default: prints what it would do. Writes and sends only with --apply. Safe to run again.
Usage: python3 -I tools/odoo/repair_till_customers.py <path to .env> [--resend-today] [--apply]
"""
import collections
import datetime
import json
import os
import re
import ssl
import sys
import time
import urllib.request

SALES_TEAM, FIRST_NAME_ACTION, BILL_ACTION, FIRST_BILL_ACTION = 1, 1392, 1277, 1283
IST = datetime.timezone(datetime.timedelta(hours=5, minutes=30))


def main():
    env = {}
    for line in open(sys.argv[1]):
        if "=" in line and not line.startswith("#"):
            k, v = line.strip().split("=", 1)
            env[k] = v.strip().strip('"')
    apply, resend = "--apply" in sys.argv, "--resend-today" in sys.argv
    ctx = ssl.create_default_context(cafile="/etc/ssl/cert.pem" if os.path.exists("/etc/ssl/cert.pem") else None)

    def call(model, method, **kw):
        req = urllib.request.Request(
            f"{env['ODOO_URL'].rstrip('/')}/json/2/{model}/{method}", data=json.dumps(kw).encode(), method="POST",
            headers={"Authorization": f"bearer {env['ODOO_API_KEY']}", "X-Odoo-Database": env["ODOO_DB"],
                     "Content-Type": "application/json", "User-Agent": "ZenZebraTill-setup/0.10.0"})
        return json.load(urllib.request.urlopen(req, timeout=60, context=ctx))

    digits = lambda p: re.sub(r"\D", "", p or "")[-10:]
    masked = lambda p: "••••" + digits(p)[-4:]

    team = call("crm.team", "read", ids=[SALES_TEAM], fields=["member_ids", "user_id"], load=None)[0]
    creators = sorted(set(team["member_ids"]) | ({team["user_id"]} if team["user_id"] else set()))
    found = call("res.partner", "search_read", domain=[
        ["create_uid", "in", creators], ["x_contact_kind", "=", "brand"], ["phone", "=", False],
        ["x_brand_phone", "=like", "+91%"], ["email", "=", False], ["is_company", "=", False], ["parent_id", "=", False],
    ], fields=["x_brand_phone", "pos_order_count", "create_date"], order="id", load=None)
    found = [p for p in found if re.fullmatch(r"[6-9]\d{9}", digits(p["x_brand_phone"]))]
    repair = [p for p in found if p["pos_order_count"] > 0]
    by_hand = [p for p in found if p["pos_order_count"] == 0]
    print(f"Brand contacts with a store customer's number: {len(found)}; to repair (have POS orders): {len(repair)}; "
          f"without orders, check by hand: {len(by_hand)}")
    for p in by_hand:
        print(f"  by hand: contact {p['id']} {masked(p['x_brand_phone'])} created {p['create_date']}")

    # Every number held by more than one contact: these Brands and the store customers that already have it.
    nums = sorted({digits(p["x_brand_phone"]) for p in found})
    holders = collections.defaultdict(list)
    for p in found:
        holders[digits(p["x_brand_phone"])].append(p["id"])
    for i in range(0, len(nums), 80):
        chunk = nums[i:i + 80]
        dom = ["|"] * (len(chunk) - 1) + [["phone_sanitized", "=", "+91" + n] for n in chunk]
        for q in call("res.partner", "search_read", domain=dom, fields=["phone_sanitized"], load=None):
            holders[digits(q["phone_sanitized"])].append(q["id"])
    shared = {n: sorted(set(ids)) for n, ids in holders.items() if len(set(ids)) > 1}
    if shared:
        ids = sorted({i for v in shared.values() for i in v})
        info = {p["id"]: p for p in call("res.partner", "read", ids=ids, fields=["x_contact_kind", "pos_order_count", "create_date"], load=None)}
        cards = collections.defaultdict(list)
        for c in call("loyalty.card", "search_read", domain=[["partner_id", "in", ids]], fields=["partner_id", "program_id", "points"], load=None):
            cards[c["partner_id"]].append(f"card {c['id']} (program {c['program_id']}) {c['points']:g} pts")
        print(f"Numbers held by more than one contact, to merge by hand (Contacts → Action → Merge): {len(shared)}")
        for n, ids in sorted(shared.items()):
            print(f"  ••••{n[-4:]}:")
            for i in ids:
                p = info[i]
                print(f"    contact {i} {p['x_contact_kind']} created {p['create_date'][:10]}, {p['pos_order_count']} orders, "
                      + (", ".join(cards[i]) or "no ZZ Cash card"))

    if apply and repair:
        for p in repair:
            call("res.partner", "write", ids=[p["id"]],
                 vals={"phone": p["x_brand_phone"], "x_brand_phone": False, "x_contact_kind": "customer"})
            time.sleep(0.3)  # stay well under Odoo's request limit; the stores share it
        ids = [p["id"] for p in repair]
        call("ir.actions.server", "run", ids=[FIRST_NAME_ACTION],
             context={"active_model": "res.partner", "active_ids": ids, "active_id": ids[0]})
        left = call("res.partner", "search_count", domain=[["id", "in", ids], ["x_contact_kind", "=", "brand"]])
        print(f"Repaired {len(ids)} contacts (phone back, Store customer, first name by action {FIRST_NAME_ACTION}); "
              f"still Brand after the write: {left}")

    if not resend:
        if not apply:
            print("Dry run: nothing written. Run again with --apply to repair.")
        return

    now = datetime.datetime.now(IST)
    start = now.replace(hour=0, minute=0, second=0, microsecond=0).astimezone(datetime.UTC)
    settled = (now - datetime.timedelta(minutes=30)).astimezone(datetime.UTC)  # the automation sends after a minute
    fmt = lambda t: t.strftime("%Y-%m-%d %H:%M:%S")
    orders = call("pos.order", "search_read", domain=[
        ["date_order", ">=", fmt(start)], ["create_date", "<=", fmt(settled)], ["state", "in", ["paid", "done", "invoiced"]],
        ["partner_id", "!=", False], ["source", "not in", ["mobile", "kiosk"]],
    ], fields=["name", "partner_id", "x_zz_first_bill", "date_order"], order="id", load=None)
    pending = {p["id"] for p in repair} if not apply else set()  # in a dry run their phone is not back yet
    phones = {p["id"]: p["phone"] for p in call("res.partner", "read", ids=sorted({o["partner_id"] for o in orders}), fields=["phone"], load=None)} if orders else {}
    sent = {m["res_id"] for m in call("mail.message", "search_read", domain=[
        ["model", "=", "pos.order"], ["res_id", "in", [o["id"] for o in orders]], ["message_type", "=", "whatsapp_message"],
    ], fields=["res_id"], load=None)} if orders else set()
    missed = [o for o in orders if o["id"] not in sent
              and ("+91" in (phones.get(o["partner_id"]) or "") or o["partner_id"] in pending)]
    print(f"Today's paid orders with a customer: {len(orders)}; WhatsApp bill missing: {len(missed)}")
    for o in missed:
        action = FIRST_BILL_ACTION if o["x_zz_first_bill"] else BILL_ACTION
        print(f"  {o['name']} at {o['date_order']} UTC: action {action}")
        if apply:
            call("ir.actions.server", "run", ids=[action],
                 context={"active_model": "pos.order", "active_ids": [o["id"]], "active_id": o["id"]})
            time.sleep(0.5)
    if apply and missed:
        got = {m["res_id"] for m in call("mail.message", "search_read", domain=[
            ["model", "=", "pos.order"], ["res_id", "in", [o["id"] for o in missed]], ["message_type", "=", "whatsapp_message"],
        ], fields=["res_id"], load=None)}
        print(f"Bills sent: {len(got)} of {len(missed)}")
    if not apply:
        print("Dry run: nothing written or sent. Run again with --apply.")


if __name__ == "__main__":
    main()
