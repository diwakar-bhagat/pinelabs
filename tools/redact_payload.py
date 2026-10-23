#!/usr/bin/env python3
"""
Redact a captured Odoo POS payload before it is saved next to the repo.

Removes customer contact details, session tokens and anything that looks like a
phone number, so a captured sync_from_ui request can be kept for diffing.
Prints the paths it redacted (never the values).

Usage: redact_payload.py <in.json> <out.json>
"""

import json
import re
import sys

SENSITIVE_KEYS = {
    "phone", "mobile", "email", "street", "street2", "vat", "access_token",
    "partner_name", "customer_name", "login", "password", "api_key", "session_id_token",
}
# Any run of 10+ digits (optionally with +, spaces or dashes) is treated as a phone number.
PHONE_LIKE = re.compile(r"\+?\d[\d\s-]{8,}\d")

redacted = []


def walk(node, path):
    if isinstance(node, dict):
        out = {}
        for k, v in node.items():
            p = f"{path}.{k}" if path else k
            if k.lower() in SENSITIVE_KEYS and v not in (None, False, ""):
                out[k] = "<redacted>"
                redacted.append(p)
            else:
                out[k] = walk(v, p)
        return out
    if isinstance(node, list):
        return [walk(v, f"{path}[{i}]") for i, v in enumerate(node)]
    if isinstance(node, str) and PHONE_LIKE.search(node):
        redacted.append(path)
        return PHONE_LIKE.sub("<redacted>", node)
    return node


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    with open(sys.argv[1], encoding="utf-8") as f:
        data = json.load(f)
    clean = walk(data, "")
    with open(sys.argv[2], "w", encoding="utf-8") as f:
        json.dump(clean, f, indent=2, ensure_ascii=False)
    print(f"Redacted {len(redacted)} field(s):")
    for p in redacted:
        print("  -", p)
    print(f"Wrote {sys.argv[2]}. Check it by eye before using it.")


if __name__ == "__main__":
    main()
