#!/usr/bin/env python3
"""
Read-only: the busiest hour of a window (28 Oct 2026, 12:00–17:00 IST, as set below) as bills with timings
and counts only, for PeakReplaySimulation. Per bill: the store, seconds into the hour, lines, lines Odoo had
not priced on that store's till in the 7 days before (the till's saved prices), a customer or not, a new
customer or not. No names, numbers or ids leave Odoo.
Usage: python3 -I tools/odoo/peak_extract.py <path to .env> <out.json>
"""
import json, sys, urllib.request, datetime, collections
env = {}
for line in open(sys.argv[1]):
    if "=" in line and not line.startswith("#"):
        k, v = line.strip().split("=", 1); env[k] = v.strip().strip('"')
OUT = sys.argv[2]
def call(model, method, **kw):
    req = urllib.request.Request(f"{env['ODOO_URL'].rstrip('/')}/json/2/{model}/{method}", data=json.dumps(kw).encode(), method="POST",
        headers={"Authorization": f"bearer {env['ODOO_API_KEY']}", "X-Odoo-Database": env["ODOO_DB"], "Content-Type": "application/json",
                 "User-Agent": "ZenZebraTill-capacity-check/0.9.0 (read-only)"})
    return json.loads(urllib.request.urlopen(req, timeout=90).read())
IST = datetime.timezone(datetime.timedelta(hours=5, minutes=30))
utc = lambda t: t.astimezone(datetime.UTC).strftime("%Y-%m-%d %H:%M:%S")
w0 = datetime.datetime(2026, 10, 28, 12, 0, tzinfo=IST); w1 = datetime.datetime(2026, 10, 28, 17, 0, tzinfo=IST)
orders = call("pos.order", "search_read", domain=[["date_order", ">=", utc(w0)], ["date_order", "<", utc(w1)]],
              fields=["date_order", "config_id", "partner_id", "lines", "session_id"], order="date_order", load=None)
parse = lambda s: datetime.datetime.strptime(s, "%Y-%m-%d %H:%M:%S").replace(tzinfo=datetime.UTC)
per_min = collections.Counter(int((parse(o["date_order"]) - w0).total_seconds() // 60) for o in orders)
# busiest 60 minutes in the window
best = max(range(0, 300 - 59), key=lambda m: sum(per_min[x] for x in range(m, m + 60)))
h0 = w0 + datetime.timedelta(minutes=best)
peak = [o for o in orders if h0 <= parse(o["date_order"]) < h0 + datetime.timedelta(hours=1)]
names = {c["id"]: c["name"] for c in call("pos.config", "search_read", domain=[], fields=["name"])}
sessions = sorted({(names[o["config_id"]], o["session_id"]) for o in peak})
# lines of the peak bills and of the 7 days before, per store: a line Odoo has not priced on that till needs compute_all
lines = call("pos.order.line", "search_read", domain=[["order_id.date_order", ">=", utc(h0 - datetime.timedelta(days=7))], ["order_id.date_order", "<", utc(h0 + datetime.timedelta(hours=1))]],
             fields=["order_id", "qty", "price_unit", "tax_ids"], load=None)
odate = {o["id"]: (o["date_order"], o["config_id"]) for o in call("pos.order", "search_read", domain=[["id", "in", list({l["order_id"] for l in lines})]], fields=["date_order", "config_id"], load=None)}
seen, misses = set(), collections.Counter()
peak_ids = {o["id"] for o in peak}
for l in sorted(lines, key=lambda l: odate[l["order_id"]][0]):
    key = (odate[l["order_id"]][1], tuple(sorted(l["tax_ids"])), round(l["price_unit"], 2), l["qty"])
    if l["order_id"] in peak_ids and key not in seen: misses[l["order_id"]] += 1
    seen.add(key)
partners = {p["id"]: p["create_date"] for p in call("res.partner", "read", ids=list({o["partner_id"] for o in peak if o["partner_id"]}), fields=["create_date"])}
bills = []
for o in peak:
    t = parse(o["date_order"])
    created = partners.get(o["partner_id"]) if o["partner_id"] else None
    new = bool(created and t - datetime.timedelta(minutes=20) <= parse(created) <= t)
    bills.append({"store": names[o["config_id"]], "t": int((t - h0).total_seconds()), "lines": len(o["lines"]),
                  "misses": misses[o["id"]], "customer": bool(o["partner_id"]), "new_customer": new})
fx = {"window_start_ist": h0.strftime("%Y-%m-%d %H:%M"), "bills": bills}
json.dump(fx, open(OUT, "w"), indent=0)
by = collections.Counter(b["store"] for b in bills)
print("peak hour:", fx["window_start_ist"], "IST,", len(bills), "bills;", dict(by.most_common()))
print("sessions:", sessions)
print(f"lines/bill {sum(b['lines'] for b in bills)/len(bills):.2f}, priced by Odoo {sum(b['misses'] for b in bills)/max(1,sum(b['lines'] for b in bills)):.0%}, "
      f"customer {sum(b['customer'] for b in bills)/len(bills):.0%}, new customer {sum(b['new_customer'] for b in bills)}")
busiest10 = max(range(0, 51), key=lambda m: sum(1 for b in bills if m*60 <= b['t'] < (m+10)*60))
print("busiest 10 min starts at minute", busiest10, "with", sum(1 for b in bills if busiest10*60 <= b['t'] < (busiest10+10)*60), "bills")
