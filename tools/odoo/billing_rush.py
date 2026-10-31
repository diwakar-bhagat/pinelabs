#!/usr/bin/env python3
#!/usr/bin/env python3
"""
Read-only billing rush against an Odoo database, on a till's key: how many stores one key carries
when every store is at its peak at once (docs/API_ENGINEERING.md, "Live rush").

Simulated stores bill the way the till does (same models, methods, domains and pacing), at KLJ's
busiest hour each (35 bills an hour), all at once. A bill's two writes (sync_from_ui and
confirm_coupon_programs) are replaced by one read each, so Odoo counts the same number of calls and
nothing changes in it. Stores are added step by step; the run stops at Odoo's first 429, at signs of
strain (slow answers, failed calls) or at the time cap. Every store's calls go from this one machine:
if Odoo limits per network, real stores (each on its own) have more room than this shows.

  --model 0.8  customer search in Odoo, then the card read; live sync every 2 minutes (0.8.0)
  --model 0.9  customer search on the till; one card-and-customer read; live sync every 5 minutes
               in a rush; the customer index's delta every 15 minutes (0.9.0)

Run it only on the owner's yes, at a quiet hour: it pushes the key to Odoo's limit, and the stores'
tills share that limit for the few seconds after the first 429.
Usage: python3 -I tools/odoo/billing_rush.py <path to .env> <output dir> [--model 0.9] [--steps 5,10,20]
The .env holds ODOO_URL, ODOO_DB, ODOO_API_KEY. It writes summary.json and calls.csv to the output dir.
"""
import argparse, bisect, csv, datetime, http.client, json, os, random, ssl, sys, threading, time

ap = argparse.ArgumentParser()
ap.add_argument("env"); ap.add_argument("out")
ap.add_argument("--model", choices=["0.8", "0.9"], default="0.9")
ap.add_argument("--steps", default="5,10,15,20,25,30,40,50,60,80")
ap.add_argument("--step-seconds", type=int, default=60)
ap.add_argument("--bills-per-hour", type=float, default=35)
args = ap.parse_args()
ENV, OUT, MODEL = args.env, args.out, args.model
STEPS = [int(x) for x in args.steps.split(",")]   # simulated stores, cumulative
STEP_S = args.step_seconds
HARD_CAP_S = 13 * 60
BILLS_PER_HOUR = args.bills_per_hour  # 35 = KLJ's busiest hour in 60 days (the busiest store-hour, 31 Oct)
LINES = [(1, 1556), (2, 740), (3, 268), (4, 169), (5, 71), (6, 37), (7, 18), (8, 6)]  # live, 14 days
PRICE_MISS = 0.153           # lines the till has not priced before (live, 7 days after a 7-day warm-up)
WITH_CUSTOMER = 0.87         # live, 30 days
PACE_RATE, PACE_BURST, PER_TILL_IN_FLIGHT = 1.0, 2.0, 2   # OdooGate
LOADER_EVERY = 120 if MODEL == "0.8" else 300   # 0.9.0: a selling till is in a rush (LivePace)
SELLABLE_EVERY, STATUS_EVERY, CUSTOMERS_EVERY = 900, 900, 900
STOP_P95_S, STOP_ERRORS = 8.0, 5
UA = f"ZenZebraTill-rush-test/{MODEL} (read-only)"
MODELS = ["pos.config", "pos.session", "pos.preset", "pos.payment.method", "pos.category", "res.company",
          "product.template", "product.product", "account.tax", "product.pricelist", "product.pricelist.item",
          "account.fiscal.position", "pos.note", "loyalty.program", "loyalty.rule", "loyalty.reward"]
PARTNER_FIELDS = ["name", "phone", "vat", "l10n_in_gst_treatment", "state_id", "property_account_position_id"]
CARD_SPEC = {"points": {}, "code": {}, "partner_id": {"fields": {"name": {}, "phone": {}, "phone_sanitized": {}, "vat": {},
             "l10n_in_gst_treatment": {}, "state_id": {"fields": {"display_name": {}}}, "property_account_position_id": {}}}}

env = {}
for line in open(ENV):
    if "=" in line and not line.startswith("#"):
        k, v = line.strip().split("=", 1); env[k] = v.strip().strip('"')
HOST = env["ODOO_URL"].split("://", 1)[1].rstrip("/")
CTX = ssl.create_default_context(cafile="/etc/ssl/cert.pem" if os.path.exists("/etc/ssl/cert.pem") else None)
HEADERS = {"Authorization": f"bearer {env['ODOO_API_KEY']}", "X-Odoo-Database": env["ODOO_DB"],
           "Content-Type": "application/json", "User-Agent": UA}

stop = threading.Event()
lock = threading.Lock()
calls = []        # (t0, t1, store, lane, kind, status, wait)
bills = []        # (t_done, store, after_payment_s)
in_flight = 0
in_flight_max = 0
first_429 = None
local = threading.local()


class Stopped(Exception):
    pass


class Bucket:
    def __init__(self):
        self.tokens, self.at, self.lock = PACE_BURST, time.monotonic(), threading.Lock()

    def take(self):
        while True:
            with self.lock:
                now = time.monotonic()
                self.tokens = min(PACE_BURST, self.tokens + (now - self.at) * PACE_RATE); self.at = now
                if self.tokens >= 1:
                    self.tokens -= 1; return
                need = (1 - self.tokens) / PACE_RATE
            if stop.wait(need):
                raise Stopped


def conn():
    c = getattr(local, "c", None)
    if c is None:
        c = local.c = http.client.HTTPSConnection(HOST, timeout=40, context=CTX)
    return c


def post(model, method, body):
    """One JSON-2 call on this thread's kept-alive connection: (status, headers, raw body)."""
    data = json.dumps(body).encode()
    for attempt in (0, 1):
        try:
            c = conn()
            c.request("POST", f"/json/2/{model}/{method}", body=data, headers=HEADERS)
            r = c.getresponse()
            return r.status, r.headers, r.read()
        except (http.client.HTTPException, OSError):
            local.c = None
            if attempt:
                return -1, {}, b""


def call(store, lane, kind, model, method, **body):
    global in_flight, in_flight_max, first_429
    if stop.is_set():
        raise Stopped
    w0 = time.monotonic()
    store.bucket.take()
    with store.gate:
        wait = time.monotonic() - w0
        with lock:
            in_flight += 1; in_flight_max = max(in_flight_max, in_flight)
        t0 = time.time()
        try:
            status, headers, raw = post(model, method, body)
        finally:
            t1 = time.time()
            with lock:
                in_flight -= 1
        with lock:
            calls.append((t0, t1, store.n, lane, kind, status, wait))
            if status == 429 and first_429 is None:
                recent = [c for c in calls if c[0] >= t1 - 60]
                first_429 = {
                    "at": datetime.datetime.now().strftime("%H:%M:%S"), "stores": len(stores), "kind": kind,
                    "retry_after": headers.get("Retry-After"), "in_flight": in_flight + 1,
                    "calls_last_1s": sum(1 for c in recent if c[0] >= t1 - 1),
                    "calls_last_10s": sum(1 for c in recent if c[0] >= t1 - 10),
                    "calls_last_60s": len(recent), "body": raw[:300].decode("utf-8", "replace"),
                }
        if status == 429:
            stop.set(); raise Stopped
        if status != 200:
            return None
        return json.loads(raw)


class Store(threading.Thread):
    def __init__(self, n, session_id, config_id):
        super().__init__(daemon=True)
        self.n, self.session_id, self.config_id = n, session_id, config_id
        self.bucket, self.gate = Bucket(), threading.Semaphore(PER_TILL_IN_FLIGHT)

    def nap(self, a, b):
        if stop.wait(random.uniform(a, b)):
            raise Stopped

    def bill(self):
        n = random.choices([l for l, _ in LINES], weights=[w for _, w in LINES])[0]
        for _ in range(n):
            self.nap(1, 3)                                   # the cashier taps an item
            if random.random() < PRICE_MISS:
                p = random.choice(PRODUCTS)
                call(self, "tap", "compute_all", "account.tax", "compute_all",
                     ids=p["taxes_id"], price_unit=p["lst_price"], quantity=random.choice([1, 1, 1, 2]))
        partner = None
        if random.random() < WITH_CUSTOMER:
            self.nap(3, 6)                                   # typing the mobile
            partner = random.choice(CARD_PARTNERS)
            if MODEL == "0.8":
                for i in range(random.choice([1, 2])):       # the dropdown searches at a pause, then the full number
                    digits = "".join(random.choice("0123456789") for _ in range(4 if i == 0 else 10))
                    call(self, "tap", "customer_search", "res.partner", "search_read",
                         domain=["|", ["phone_sanitized", "=ilike", f"%{digits}"], ["phone_sanitized", "=ilike", f"+91{digits}%"]],
                         fields=PARTNER_FIELDS, offset=0, limit=20, order="name, id")
                call(self, "tap", "zz_balance", "loyalty.card", "search_read",
                     domain=[["partner_id", "=", partner], ["program_id", "=", ZZ_PROGRAM]], fields=["points", "code"], order="id", limit=2)
            else:                                            # found on the till; one read for the card and the customer
                call(self, "tap", "zz_card", "loyalty.card", "web_search_read",
                     domain=[["partner_id", "=", partner], ["program_id", "=", ZZ_PROGRAM]], specification=CARD_SPEC, order="id", limit=2)
        self.nap(15, 30)                                     # the customer pays on the Pine screen
        paid = time.time()
        # sync_from_ui stands in as one read of this store's last order (the same one call for Odoo to count)
        call(self, "sale", "sync_order", "pos.order", "search_read",
             domain=[["config_id", "=", self.config_id]], fields=["name", "amount_total", "state", "partner_id"], order="id desc", limit=1)
        if partner:  # confirm_coupon_programs stands in as one read of the card
            call(self, "sale", "confirm_points", "loyalty.card", "search_read",
                 domain=[["partner_id", "=", partner], ["program_id", "=", ZZ_PROGRAM]], fields=["points"], limit=1)
        with lock:
            bills.append((time.time(), self.n, time.time() - paid))

    def run(self):
        bg = threading.Thread(target=self.background, daemon=True); bg.start()
        try:
            self.nap(0, 3600 / BILLS_PER_HOUR)
            while True:
                self.bill()
                gap = random.expovariate(1 / max(5.0, 3600 / BILLS_PER_HOUR - 34))   # a bill takes ~34 s at the till
                if stop.wait(gap):
                    raise Stopped
        except Stopped:
            pass

    def background(self):
        now = time.time()
        due = {"loader": now + random.uniform(0, LOADER_EVERY), "sellable": now + random.uniform(0, SELLABLE_EVERY),
               "status": now + random.uniform(0, STATUS_EVERY)}
        if MODEL == "0.9":
            due["customers"] = now + random.uniform(0, CUSTOMERS_EVERY)
        try:
            while True:
                what = min(due, key=due.get)
                if stop.wait(max(0.0, due[what] - time.time())):
                    raise Stopped
                if what == "loader":
                    since = (datetime.datetime.now(datetime.UTC) - datetime.timedelta(seconds=LOADER_EVERY + 300)).strftime("%Y-%m-%d %H:%M:%S")
                    call(self, "background", "loader_since", "pos.session", "load_data", ids=[self.session_id], models_to_load=MODELS,
                         context={"pos_last_server_date": since, "pos_limited_loading": True})
                    due[what] += LOADER_EVERY
                elif what == "sellable":
                    call(self, "background", "sellable_ids", "product.product", "search",
                         domain=[["available_in_pos", "=", True], ["sale_ok", "=", True], "|", ["company_id", "=", False], ["company_id", "parent_of", [1]]])
                    due[what] += SELLABLE_EVERY
                elif what == "customers":
                    since = (datetime.datetime.now(datetime.UTC) - datetime.timedelta(seconds=CUSTOMERS_EVERY + 300)).strftime("%Y-%m-%d %H:%M:%S")
                    call(self, "background", "customer_delta", "res.partner", "search_read", domain=[["write_date", ">", since]],
                         fields=PARTNER_FIELDS + ["phone_sanitized", "active"], order="id", limit=2000, context={"active_test": False})
                    due[what] += CUSTOMERS_EVERY
                else:
                    call(self, "background", "till_status", "x_e700_till", "search_read", domain=[], fields=["x_name"], limit=5)
                    due[what] += STATUS_EVERY
        except Stopped:
            pass


def plain(model, method, **body):
    status, _, raw = post(model, method, body)
    if status != 200:
        raise SystemExit(f"setup call {model}.{method} answered {status}: {raw[:200]!r}")
    return json.loads(raw)


def pct(v, q):
    v = sorted(v)
    return v[min(len(v) - 1, int(round(q * (len(v) - 1))))] if v else 0.0


# ---- setup (a handful of plain calls before the clock starts)
SESSIONS = [(s["id"], s["config_id"]) for s in plain("pos.session", "search_read", domain=[["state", "=", "opened"]], fields=["config_id"], load=None)]
if not SESSIONS:  # at night every session may be closed; Odoo's loader reads a closed one just the same
    SESSIONS = [(s["id"], s["config_id"]) for s in plain("pos.session", "search_read", domain=[], fields=["config_id"], order="id desc", limit=2, load=None)]
PRODUCTS = [p for p in plain("product.product", "search_read", domain=[["available_in_pos", "=", True], ["sale_ok", "=", True], ["taxes_id", "!=", False]],
                             fields=["lst_price", "taxes_id"], limit=300, load=None)]
ZZ = plain("loyalty.program", "search_read", domain=[["program_type", "=", "loyalty"], ["pos_ok", "=", True]], fields=["id"], limit=1)
ZZ_PROGRAM = ZZ[0]["id"]
CARD_PARTNERS = [c["partner_id"] for c in plain("loyalty.card", "search_read", domain=[["program_id", "=", ZZ_PROGRAM], ["partner_id", "!=", False]],
                                                   fields=["partner_id"], limit=300, load=None)]
print(f"model {MODEL}, setup: {len(SESSIONS)} open sessions, {len(PRODUCTS)} products, ZZ program {ZZ_PROGRAM}, {len(CARD_PARTNERS)} card holders", flush=True)
time.sleep(5)

stores = []
steps = []
start = time.time()
for target in STEPS:
    while len(stores) < target:
        sid, cfg = SESSIONS[len(stores) % len(SESSIONS)]
        s = Store(len(stores) + 1, sid, cfg); stores.append(s); s.start()
    t_step = time.time()
    with lock:
        in_flight_max = in_flight
    reason = None
    while time.time() - t_step < STEP_S and not stop.is_set():
        time.sleep(1)
        with lock:
            last30 = [c for c in calls if c[1] >= time.time() - 30 and c[5] == 200]
            errs = sum(1 for c in calls if c[0] >= t_step and c[5] not in (200, 429))
        if len(last30) >= 10 and pct([c[1] - c[0] for c in last30], 0.95) > STOP_P95_S:
            reason = "strain: p95 latency over 8 s"; stop.set()
        if errs >= STOP_ERRORS:
            reason = f"strain: {errs} failed calls"; stop.set()
        if time.time() - start > HARD_CAP_S:
            reason = "time cap"; stop.set()
    t_end = time.time()
    with lock:
        rows = [c for c in calls if t_step <= c[0] < t_end]
        done = [b for b in bills if t_step <= b[0] < t_end]
        mx = in_flight_max
    lat = [c[1] - c[0] for c in rows if c[5] == 200]
    waits = [c[6] for c in rows]
    step = {
        "stores": len(stores), "seconds": round(t_end - t_step), "calls": len(rows),
        "calls_per_s": round(len(rows) / max(1, t_end - t_step), 3), "bills": len(done),
        "ok": sum(1 for c in rows if c[5] == 200), "429": sum(1 for c in rows if c[5] == 429),
        "errors": sum(1 for c in rows if c[5] not in (200, 429)),
        "lat_p50": round(pct(lat, 0.5), 3), "lat_p95": round(pct(lat, 0.95), 3), "lat_max": round(max(lat, default=0), 3),
        "pacing_wait_p95": round(pct(waits, 0.95), 3), "in_flight_max": mx,
        "after_payment_p95": round(pct([b[2] for b in done], 0.95), 3),
        "by_kind": {k: sum(1 for c in rows if c[4] == k) for k in sorted({c[4] for c in rows})},
    }
    steps.append(step)
    print(json.dumps(step), flush=True)
    if stop.is_set():
        if reason:
            print("stopped:", reason, flush=True)
        break
stop.set()
time.sleep(2)

total_s = time.time() - start
with lock:
    allc = list(calls); allb = list(bills)
per_kind = {}
for k in sorted({c[4] for c in allc}):
    lat = [c[1] - c[0] for c in allc if c[4] == k and c[5] == 200]
    per_kind[k] = {"calls": sum(1 for c in allc if c[4] == k), "p50": round(pct(lat, 0.5), 3), "p95": round(pct(lat, 0.95), 3)}
summary = {"first_429": first_429, "total_s": round(total_s), "calls": len(allc), "bills": len(allb), "per_kind": per_kind, "steps": steps}
os.makedirs(OUT, exist_ok=True)
json.dump(summary, open(os.path.join(OUT, "summary.json"), "w"), indent=1)
with open(os.path.join(OUT, "calls.csv"), "w", newline="") as f:
    w = csv.writer(f); w.writerow(["t0", "t1", "store", "lane", "kind", "status", "pacing_wait"]); w.writerows(allc)
print("first_429:", json.dumps(first_429), flush=True)
print("per_kind:", json.dumps(per_kind), flush=True)

# The busiest stretch Odoo accepted, per window: where its limit sits, whatever shape it has.
t = sorted(c[0] for c in allc if c[5] != 429)
for w in (1, 10, 60, 120):
    best = max((i - bisect.bisect_left(t, t[i] - w, 0, i + 1) + 1 for i in range(len(t))), default=0)
    print(f"busiest {w:>3}s accepted: {best} calls ({best / w:.2f}/s)", flush=True)
bills_done = sum(1 for c in allc if c[4] == "sync_order")
print(f"calls per bill: {sum(1 for c in allc if c[3] != 'background') / max(1, bills_done):.2f}, "
      f"background share: {sum(1 for c in allc if c[3] == 'background') / max(1, len(allc)):.0%}", flush=True)
