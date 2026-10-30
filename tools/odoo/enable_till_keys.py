#!/usr/bin/env python3
"""
Let tills make and renew their own API keys (F4, 0.7.0): Odoo 19's res.users.apikeys.generate needs
the Settings right, or the system parameter base.enable_programmatic_api_keys; each user may hold
base.programmatic_api_keys_limit keys (10 by default, too few for 20 tills plus renewals).

Dry run by default: prints the current values. Writes only with --apply.
Usage: python3 -I tools/odoo/enable_till_keys.py <path to .env> [--apply]
"""
import json
import sys
import urllib.request

WANT = {"base.enable_programmatic_api_keys": "True", "base.programmatic_api_keys_limit": "50"}


def main():
    env = {}
    for line in open(sys.argv[1]):
        if "=" in line:
            k, v = line.strip().split("=", 1)
            env[k] = v.strip().strip('"')

    def call(model, method, **kw):
        req = urllib.request.Request(
            f"{env['ODOO_URL'].rstrip('/')}/json/2/{model}/{method}", data=json.dumps(kw).encode(), method="POST",
            headers={"Authorization": f"bearer {env['ODOO_API_KEY']}", "X-Odoo-Database": env["ODOO_DB"],
                     "Content-Type": "application/json", "User-Agent": "ZenZebraTill-setup/0.7.0"})
        return json.load(urllib.request.urlopen(req, timeout=60))

    for key, value in WANT.items():
        now = call("ir.config_parameter", "get_param", key=key)
        print(f"{key}: now {now!r}, wanted {value!r}")
        if "--apply" in sys.argv and str(now) != value:
            call("ir.config_parameter", "set_param", key=key, value=value)
            print(f"  set to {value!r}")
    if "--apply" not in sys.argv:
        print("Dry run: nothing written. Run again with --apply to set them.")


if __name__ == "__main__":
    main()
