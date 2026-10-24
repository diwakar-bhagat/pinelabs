# Prompt: run the App-to-App flow on the E700 (for the Android Studio AI agent)

Paste everything below the line into the Android Studio AI agent on the Windows laptop that
has the E700 connected by USB (adb).

---

You are testing the **Bohemian Odoo POS** Android app (`com.bohemian.odoo.pos`) on a
**PAX E700 payment terminal (SN 1340055807)** connected to this PC by USB/adb. The app takes
card/UPI payments **App-to-App** through Pine Labs' payment app using the **Pine Billing SDK
2.0.0**. Odoo is the system of record. Your job is to run the flow, collect evidence, and
report. You are **not** fixing code in this task.

Project: `C:\Users\Admin\StudioProjects\pinelabs`, branch `claude/release-rc1`.
Read `CLAUDE.md` §3 (HARD RULES), §10 (UAT script) and `docs/PINE_LABS_VERIFIED.md` §6 first.

## Safety rules (follow exactly)

1. **Real money.** This is a live payment terminal. Use **₹5 or less** per test. Never run
   two payments for the same bill.
2. **Never repeat a payment whose outcome is unclear** (timeout, "status not confirmed",
   app closed mid-payment). Stop and report. The app's own "Check status" button and the
   supervisor buttons are for a human.
3. **Do not install, uninstall, clear data of, disable or force-stop any Pine Labs or PAX app**
   (`com.pinelabs.*`, `com.pax.*`). Never sideload a MasterApp APK. Only Pine provisions it.
4. **Do not change system or security settings**, permissions, or device policy on the E700.
5. **Do not open the Setup screen's API key field** or print any API key, card number or
   customer phone number. Logs from the app are already redacted; don't paste raw values from
   other apps' logs.
6. A **human** must tap/insert the card or scan the UPI QR. When the terminal asks for that,
   pause, tell the user exactly what to do, and wait for them to confirm.
7. **Never run `adb uninstall com.bohemian.odoo.pos` or clear its data without asking the user
   first.** It deletes the payment journal, the unsynced-order outbox and the saved Odoo API key.
   An `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (signature mismatch) error means: stop and ask.
8. **Do not edit any project file** (Gradle files, `gradle.properties`, source). Report build
   errors instead.
9. When a step says **STOP**, stop: run nothing further except collecting the report.
10. If anything in this list would be needed to continue, **stop and report**. Don't work around it.

## Step 0: Device and Pine payment service (gate)

```bat
adb devices
adb shell getprop ro.serialno
adb shell pm list packages | findstr /i "pinelabs masterapp bohemian"
adb shell dumpsys package com.pinelabs.masterapp | findstr /i "versionName versionCode"
```

- Expect `1340055807` as the device in `adb devices`.
- **If `com.pinelabs.masterapp` is NOT listed: STOP here.** Report the package list and say
  "MasterApp not provisioned; Pine must install it". Card/UPI cannot work without it
  (`docs/PINE_LABS_VERIFIED.md` §6). Skip to the report.

## Step 1: Install the app (prebuilt APK preferred)

The Pine SDK is proprietary and git-ignored, so a fresh clone builds a **stub** with no
payments. Prefer the prebuilt APK the user received (built with Pine SDK 2.0.0):

```bat
adb install -r C:\Users\Admin\Downloads\bohemian-pos-uat-debug-sdk2.0.0.apk
```

Build from source only if `app\libs\pine-billing-sdk-android-maven\com\pinelabs\billing\pine-billing-sdk-android\2.0.0`
exists:

```bat
cd C:\Users\Admin\StudioProjects\pinelabs
git fetch origin
git checkout claude/release-rc1
git pull --ff-only
gradlew.bat installUatDebug
```

Confirm `adb shell pm list packages | findstr bohemian`. If the install fails with a signature
mismatch, **stop and ask** (rule 7).

## Step 2: Start logs, open the app, run the built-in check

```bat
adb logcat -c
adb shell am start -n com.bohemian.odoo.pos/.ui.MainActivity
```

Find buttons by their text with `adb shell uiautomator dump /sdcard/ui.xml` + `adb pull /sdcard/ui.xml`
(screen is 1920×1008 landscape), then `adb shell input tap X Y` at the centre of the element.

1. The top bar / Setup title must **not** say `NO PINE SDK`. If it does, the installed build
   has no payments: **STOP** and report.
2. Tap **Setup**, then **Check Pine payment app**. Record the full text it shows
   (SDK present, App ID, MasterApp installed/version, payment service exported/permission).
3. If it says `Pine SDK in this build: NO`, MasterApp NOT INSTALLED, or the payment service
   NOT FOUND: **STOP** and report.
4. Go back to selling (**Start selling** if shown, without changing any choice). If Setup asks
   for the Odoo API key, **ask the user to type it themselves**. Never type or read it.

## Step 3: Session

If the screen shows "No open session", tap **Open session** and confirm the bar disappears.

## Step 4: ₹5 card sale (UAT case 1)

1. Search a product priced **₹5.00** (e.g. type `go desi`), tap it once. Wait until the cart
   shows a TOTAL (prices come from Odoo; "Getting prices from Odoo…" must finish).
2. Record the TOTAL. It must be ₹5.00 or less. If not, remove items until it is.
3. Tap **CARD**. The Pine payment screen should open. **Ask the user to tap/insert a card**
   and wait for their confirmation.
4. Record the dialog the app shows afterwards (title + text). Expected: "✓ PAYMENT SUCCESSFUL"
   with RRN and card last 4, and a printed receipt.
5. If it shows **"⚠ Status not confirmed"** or anything unclear: **stop**, do not retry, report.
6. Tap **NEW BILL**.

## Step 5: Collect logs

```bat
adb logcat -d -s BohemianPOS pine_billing PineBillingSdk AndroidRuntime > e700-a2a-log.txt
findstr /i "PAYMENT_ VOID_ PINE_ ORDER_SYNC KIOSK CRASH" e700-a2a-log.txt
```

**Payload evidence = log lines only.** The app logs exactly what it sends and receives:
`PINE_REQUEST` (type, modes, ref, amountPaise, currency, bankCode, invoiceNo), `PINE_RESULT`
(status, code, msg, ref, amountPaise, txnId, rrn, auth, last4, …), `PINE_FAILURE` / `PINE_REJECTED`
(SDK error kind + detail) and `PINE_PRINT_FAILED`. When asked for "request/response payloads",
**copy these lines verbatim. Never reconstruct, reformat into JSON, or fill in values that are not
in the log.** If a line is missing, say it is missing.

Look especially for: `PAYMENT_START`, `PAYMENT_RESPONSE`, `PAYMENT_SUCCESS` / `PAYMENT_NEEDS_REVIEW`,
`ORDER_SYNCED` or `ORDER_SYNC_FAILED`, and **`PINE_RESULT_META_KEYS`** (the extra field names the
terminal returns; needed to settle the Void invoice-number question).

## Step 6 (only if Step 4 succeeded and the user agrees): void that sale (UAT case 8)

1. Tap **Void**, pick the ₹5 sale just made, tap **VOID ON TERMINAL**. Follow any terminal prompt
   with the user.
2. Record the result text. Expected: "✓ Voided ₹5.00 on the terminal" and "Refund recorded in Odoo."
3. If it says "Not voided" with a message, record the exact message. That tells us whether
   Pine needs the sale's invoice number (`docs/PINE_LABS_VERIFIED.md` §6). Do not try again.
4. Collect logs again as in Step 5 (`VOID_START`, `VOID_RESPONSE`, `VOID_SUCCESS`).

## Report (reply in this exact shape)

| Step | Result | Evidence |
|---|---|---|
| 0 Device + MasterApp | pass/stop | package list, MasterApp version |
| 1 Build + install | pass/fail | last build line |
| 2 Pine check (Setup) | … | full check text |
| 3 Session | … | |
| 4 ₹5 card sale | … | dialog text, receipt printed y/n |
| 5 Logs | … | the matching log lines (no API keys/PAN/phones) |
| 6 Void | … / skipped | dialog text, log lines |

Then list: anything unexpected, the exact text of any error, and the value of
`PINE_RESULT_META_KEYS` if it appeared. Attach `e700-a2a-log.txt`.
