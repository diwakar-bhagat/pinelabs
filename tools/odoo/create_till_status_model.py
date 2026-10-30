#!/usr/bin/env python3
"""
Create the E700 till list in Odoo (F5, 0.7.0): custom model x_e700_till, its access rights, a list
view and a menu "E700 Tills" under Point of Sale > Reporting. Each till writes its own row every
15 minutes (odoo/TillStatus.kt).

Dry run by default: prints what it would create. Writes only with --apply.
Usage: python3 -I tools/odoo/create_till_status_model.py <path to .env> [--apply]
The .env holds ODOO_URL, ODOO_DB, ODOO_API_KEY (an administrator's key: creating models needs it).
"""
import json
import sys
import urllib.request

MODEL = "x_e700_till"
FIELDS = [
    ("x_name", "Terminal serial", "char"),
    ("x_store", "Store (POS)", "char"),
    ("x_version", "Till version", "char"),
    ("x_last_seen", "Last seen", "datetime"),
    ("x_outbox", "Orders not in Odoo yet", "integer"),
    ("x_rejected", "Orders refused by Odoo", "integer"),
    ("x_unresolved", "Payments to check", "integer"),
    ("x_offline_sales", "Sales not sent at once today", "integer"),
    ("x_calls_per_min", "Odoo calls per minute", "float"),
    ("x_throttled_today", "Odoo busy (429) today", "integer"),
    ("x_last_error", "Last error", "char"),
    ("x_key_expires", "API key expires", "date"),
]
LIST_ARCH = (
    "<list decoration-danger=\"x_rejected &gt; 0 or x_unresolved &gt; 0\" decoration-warning=\"x_outbox &gt; 0\">"
    + "".join(f"<field name=\"{f}\"/>" for f, _, _ in FIELDS)
    + "</list>"
)


def main():
    env = {}
    for line in open(sys.argv[1]):
        if "=" in line:
            k, v = line.strip().split("=", 1)
            env[k] = v.strip().strip('"')
    apply = "--apply" in sys.argv

    def call(model, method, **kw):
        req = urllib.request.Request(
            f"{env['ODOO_URL'].rstrip('/')}/json/2/{model}/{method}", data=json.dumps(kw).encode(), method="POST",
            headers={"Authorization": f"bearer {env['ODOO_API_KEY']}", "X-Odoo-Database": env["ODOO_DB"],
                     "Content-Type": "application/json", "User-Agent": "ZenZebraTill-setup/0.7.0"})
        return json.load(urllib.request.urlopen(req, timeout=60))

    def xmlid(module, name):
        r = call("ir.model.data", "search_read", domain=[["module", "=", module], ["name", "=", name]], fields=["res_id"], limit=1)
        return r[0]["res_id"] if r else None

    if call("ir.model", "search_read", domain=[["model", "=", MODEL]], fields=["id"]):
        print(f"{MODEL} already exists: nothing to do.")
        return
    menu_parent = xmlid("point_of_sale", "menu_point_rep")
    groups = {g: xmlid(*g.split(".")) for g in ("base.group_system", "point_of_sale.group_pos_user", "point_of_sale.group_pos_manager")}
    print(f"Will create model {MODEL} with {len(FIELDS)} fields, access for {groups}, a list view and menu under {menu_parent}.")
    if not apply:
        print("Dry run: nothing written. Run again with --apply to create it.")
        return

    model_id = call("ir.model", "create", vals_list=[{
        "name": "E700 Till", "model": MODEL, "state": "manual",
        "field_id": [[0, 0, {"name": f, "field_description": label, "ttype": t, "required": f == "x_name"}] for f, label, t in FIELDS],
    }])[0]
    # Tills (POS users or an administrator's key) read and write their own row; only administrators delete.
    for g, gid in groups.items():
        if gid:
            call("ir.model.access", "create", vals_list=[{
                "name": f"{MODEL} {g}", "model_id": model_id, "group_id": gid,
                "perm_read": True, "perm_write": True, "perm_create": True, "perm_unlink": g == "base.group_system",
            }])
    call("ir.ui.view", "create", vals_list=[{"name": f"{MODEL}.list", "model": MODEL, "type": "list", "arch": LIST_ARCH}])
    action_id = call("ir.actions.act_window", "create", vals_list=[{"name": "E700 Tills", "res_model": MODEL, "view_mode": "list,form"}])[0]
    if menu_parent:
        call("ir.ui.menu", "create", vals_list=[{"name": "E700 Tills", "parent_id": menu_parent,
                                                  "action": f"ir.actions.act_window,{action_id}", "sequence": 99}])
    print(f"Created {MODEL} (ir.model {model_id}), action {action_id}; menu Point of Sale > Reporting > E700 Tills.")


if __name__ == "__main__":
    main()
