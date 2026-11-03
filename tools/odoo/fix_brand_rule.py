#!/usr/bin/env python3
"""
Stop Odoo's "Contacts: created by Sales team is a Brand" rule (automation 23, server action 1384) from
turning the till's customers into Brands (3 Nov 2026, docs/ZZ_CASH.md "Brand rule").

The till saves customers under a Sales-team member's key, so the rule ran action 1393 on every new till
customer: a brand-new contact has no orders yet, so it became a Brand and its phone moved to x_brand_phone.
No phone means no WhatsApp bill (the bill automations need partner_id.phone ilike '+91') and no ZZ Cash
by number. The new code leaves walk-in store customers alone: an Indian mobile, no email, no company, no
parent. Brands still get flagged by the lead and shelf-rental rules (automations 18, 21, 22) when they
become a lead or a quotation contact. The till (0.10.0) also creates its customers with the context
zz_store_customer, which the rule honours.

Dry run by default: prints the action's code now. Writes only with --apply, and only over the code this
script expects (OLD), so a rule edited by hand in Odoo is never overwritten. --rollback puts OLD back.
Usage: python3 -I tools/odoo/fix_brand_rule.py <path to .env> [--apply | --rollback]
"""
import json
import os
import ssl
import sys
import urllib.request

ACTION = 1384

OLD = """team = env['crm.team'].browse(1)
if env.user in (team.member_ids | team.user_id): env['ir.actions.server'].browse(1393).with_context(active_model='res.partner', active_ids=records.ids).run()
"""

NEW = """team = env['crm.team'].browse(1)
if env.user in (team.member_ids | team.user_id) and not env.context.get('zz_store_customer'):
    # A walk-in store customer (an Indian mobile, no email, no company) is never a Brand: the E700 till
    # saves its customers under a Sales-team member's key. Brands still get flagged by the lead and
    # quotation rules (automations 18, 21, 22) when they become a lead or a shelf-rental contact.
    brands = records.filtered(lambda p: p.is_company or p.email or p.parent_id
                              or not (p.phone_sanitized or '').startswith('+91'))
    if brands:
        env['ir.actions.server'].browse(1393).with_context(active_model='res.partner', active_ids=brands.ids).run()
"""


def main():
    env = {}
    for line in open(sys.argv[1]):
        if "=" in line and not line.startswith("#"):
            k, v = line.strip().split("=", 1)
            env[k] = v.strip().strip('"')
    ctx = ssl.create_default_context(cafile="/etc/ssl/cert.pem" if os.path.exists("/etc/ssl/cert.pem") else None)

    def call(model, method, **kw):
        req = urllib.request.Request(
            f"{env['ODOO_URL'].rstrip('/')}/json/2/{model}/{method}", data=json.dumps(kw).encode(), method="POST",
            headers={"Authorization": f"bearer {env['ODOO_API_KEY']}", "X-Odoo-Database": env["ODOO_DB"],
                     "Content-Type": "application/json", "User-Agent": "ZenZebraTill-setup/0.10.0"})
        return json.load(urllib.request.urlopen(req, timeout=60, context=ctx))

    def code():
        return call("ir.actions.server", "read", ids=[ACTION], fields=["name", "code"])[0]

    now = code()
    print(f"Action {ACTION} ({now['name']}), code now:\n{now['code']}")
    want, over = (OLD, NEW) if "--rollback" in sys.argv else (NEW, OLD)
    if now["code"].strip() == want.strip():
        print("Already as wanted: nothing to do.")
        return
    if now["code"].strip() != over.strip():
        sys.exit("The code is neither the old nor the new version (edited by hand?): not touched.")
    if "--apply" not in sys.argv and "--rollback" not in sys.argv:
        print(f"Dry run: nothing written. With --apply the code becomes:\n{NEW}")
        return
    call("ir.actions.server", "write", ids=[ACTION], vals={"code": want})
    after = code()["code"]
    print("Written and read back:", "OK" if after.strip() == want.strip() else "DIFFERENT, check by hand")


if __name__ == "__main__":
    main()
