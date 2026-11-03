#!/usr/bin/env python3
"""
Customers on WhatsApp: the steps of docs/WHATSAPP_JOIN.md, one at a time, each on the owner's yes.

Step 1, balance-bot (owner's yes, 3 Nov 2026): Odoo's "WhatsApp: reply to ZZ Cash balance questions"
(automation 27, server action 1391) answers "balance", "points", "kitna cash" … with the ZZ Cash balance
of the number that wrote. It answered only the numbers in the system parameter zz.balancebot.allowlist
(one test number since 22 Oct). Removing the parameter lets it answer every customer. The action only
ever answers the number that wrote, about that number's own cards.

Dry run by default. --apply removes the parameter, only when action 1391 still has the code reviewed on
3 Nov (its SHA-256 below), and first saves the old value in .env.zz-backup.json next to the .env file
(git-ignored, as .env.*). --rollback puts the saved value back.
Usage: python3 -I tools/odoo/whatsapp_join.py <path to .env> balance-bot [--apply | --rollback]
"""
import datetime
import hashlib
import json
import os
import re
import ssl
import sys
import urllib.request

AUTOMATION, ACTION, PARAM = 27, 1391, "zz.balancebot.allowlist"
REVIEWED = "d561e007771c3184b43d29f3be9000faddab3b375896dd16e320544973f4cc47"  # action 1391's code, 3 Nov 2026


def main():
    if len(sys.argv) < 3 or sys.argv[2] != "balance-bot":
        sys.exit(__doc__)
    env = {}
    for line in open(sys.argv[1]):
        if "=" in line and not line.startswith("#"):
            k, v = line.strip().split("=", 1)
            env[k] = v.strip().strip('"')
    backup = os.path.join(os.path.dirname(os.path.abspath(sys.argv[1])), ".env.zz-backup.json")
    ctx = ssl.create_default_context(cafile="/etc/ssl/cert.pem" if os.path.exists("/etc/ssl/cert.pem") else None)

    def call(model, method, **kw):
        req = urllib.request.Request(
            f"{env['ODOO_URL'].rstrip('/')}/json/2/{model}/{method}", data=json.dumps(kw).encode(), method="POST",
            headers={"Authorization": f"bearer {env['ODOO_API_KEY']}", "X-Odoo-Database": env["ODOO_DB"],
                     "Content-Type": "application/json", "User-Agent": "ZenZebraTill-setup/0.10.0"})
        return json.load(urllib.request.urlopen(req, timeout=60, context=ctx))

    def allowlist():
        return call("ir.config_parameter", "get_param", key=PARAM) or ""

    masked = lambda v: [("••••" + re.sub(r"\D", "", x)[-4:]) for x in v.split(",") if x.strip()]

    auto = call("base.automation", "read", ids=[AUTOMATION], fields=["name", "active", "action_server_ids"])[0]
    code = call("ir.actions.server", "read", ids=[ACTION], fields=["code"])[0]["code"] or ""
    same = hashlib.sha256(code.encode()).hexdigest() == REVIEWED
    print(f"Automation {AUTOMATION} ({auto['name']}): {'active' if auto['active'] else 'INACTIVE'}, actions {auto['action_server_ids']}")
    print(f"Action {ACTION}: {'the code reviewed on 3 Nov' if same else 'CHANGED since 3 Nov'}")
    now = allowlist()
    print(f"{PARAM}: {masked(now) if now else 'not set (the bot answers every customer)'}")

    if "--rollback" in sys.argv:
        if not os.path.exists(backup) or PARAM not in json.load(open(backup)):
            sys.exit(f"No saved value in {backup}: nothing to put back.")
        old = json.load(open(backup))[PARAM]["value"]
        call("ir.config_parameter", "set_param", key=PARAM, value=old)
        print("Put back:", masked(allowlist()))
        return
    if not now:
        print("Already as wanted: nothing to do.")
        return
    if not (same and auto["active"] and ACTION in auto["action_server_ids"]):
        sys.exit("The bot is not as reviewed on 3 Nov: not touched. Review action 1391 again first.")
    if "--apply" not in sys.argv:
        print("Dry run: nothing written. With --apply the parameter is removed and the bot answers every customer.")
        return
    saved = json.load(open(backup)) if os.path.exists(backup) else {}
    saved[PARAM] = {"value": now, "saved_at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")}
    with open(backup, "w") as f:
        json.dump(saved, f, indent=1)
    os.chmod(backup, 0o600)
    call("ir.config_parameter", "set_param", key=PARAM, value=False)
    print("Removed and read back:", "OK, the bot answers every customer" if not allowlist() else "STILL SET, check by hand")


if __name__ == "__main__":
    main()
