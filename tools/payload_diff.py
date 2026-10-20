#!/usr/bin/env python3
"""
Payload Diff Tool for Odoo pos.order.sync_from_ui payloads.
Compares reference web POS payload dumps vs Android app payload dumps.
Drops volatile keys (uuid, dates, IDs) before diffing.
"""

import sys
import json

VOLATILE_KEYS = {
    "uuid", "date_order", "payment_date", "session_id", "name",
    "pos_reference", "access_token", "id", "tracking_number", "sequence_number"
}

def clean_dict(data):
    if isinstance(data, dict):
        cleaned = {}
        for k, v in data.items():
            if k in VOLATILE_KEYS:
                continue
            cleaned[k] = clean_dict(v)
        return cleaned
    elif isinstance(data, list):
        return [clean_dict(item) for item in data]
    else:
        return data

def unwrap_web_payload(data):
    """Unwraps /web/dataset/call_kw/pos.order/sync_from_ui format to list of orders."""
    if isinstance(data, dict) and "params" in data:
        args = data["params"].get("args", [])
        if args and isinstance(args[0], list):
            return args[0]
    if isinstance(data, dict) and "orders" in data:
        return data["orders"]
    if isinstance(data, list):
        return data
    return [data]

def compare_orders(web_order, app_order):
    missing_in_app = []
    extra_in_app = []
    value_mismatches = []

    web_keys = set(web_order.keys())
    app_keys = set(app_order.keys())

    for k in web_keys - app_keys:
        missing_in_app.append(k)

    for k in app_keys - web_keys:
        extra_in_app.append(k)

    for k in web_keys & app_keys:
        if web_order[k] != app_order[k]:
            value_mismatches.append((k, web_order[k], app_order[k]))

    return missing_in_app, extra_in_app, value_mismatches

def main():
    if len(sys.argv) < 3:
        print("Usage: python3 payload_diff.py <web_ref.json> <app_ref.json>")
        sys.exit(1)

    web_file = sys.argv[1]
    app_file = sys.argv[2]

    with open(web_file, 'r') as f:
        web_raw = json.load(f)

    with open(app_file, 'r') as f:
        app_raw = json.load(f)

    web_orders = unwrap_web_payload(clean_dict(web_raw))
    app_orders = unwrap_web_payload(clean_dict(app_raw))

    print(f"Comparing {web_file} vs {app_file}")
    print("=" * 60)

    has_error = False

    for idx, (web_o, app_o) in enumerate(zip(web_orders, app_orders)):
        print(f"\n--- Order Index #{idx} ---")
        missing, extra, mismatches = compare_orders(web_o, app_o)

        if missing:
            has_error = True
            print("  [MUST ADD] Keys present in Web POS but missing in App:")
            for k in missing:
                print(f"    - {k}: Web value = {web_o[k]}")

        if extra:
            print("  [EXTRA] Keys present in App but not in Web POS:")
            for k in extra:
                print(f"    - {k}: App value = {app_o[k]}")

        if mismatches:
            has_error = True
            print("  [MUST FIX] Value Mismatches:")
            for k, wv, av in mismatches:
                print(f"    - {k}: Web = {wv} | App = {av}")

        if not (missing or extra or mismatches):
            print("  ✅ Order payload structure matches perfectly!")

    if has_error:
        sys.exit(1)
    else:
        sys.exit(0)

if __name__ == '__main__':
    main()
