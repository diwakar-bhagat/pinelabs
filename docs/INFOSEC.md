# Infosec pack — Bohemian Odoo POS (for Pine Labs review)

App `com.bohemian.odoo.pos`, native Android (Kotlin), minSdk 25 (PAX E700, Android 7.1.2).
Facts below are taken from the signed production APK (`prodRelease`, versionCode 1 / 0.1.0) and the source, 2026-10-27.

## 1. Permissions

| Permission | Why |
|---|---|
| `android.permission.INTERNET` | HTTPS calls to the merchant's Odoo server (§2) |
| `android.permission.ACCESS_NETWORK_STATE` | Tell the cashier when the terminal is offline |
| `android.permission.RECEIVE_BOOT_COMPLETED` | Kiosk mode only: start the till after a reboot. Does nothing unless kiosk mode is switched on in Setup |
| `com.bohemian.odoo.pos.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Added by AndroidX (core) for its own non-exported dynamic receivers; signature-level, app-internal |

No camera, storage, location, phone, SMS, contacts or accessibility permissions.

## 2. Network endpoints

| Endpoint | Protocol | Data |
|---|---|---|
| `https://zenzebra1.odoo.com/json/2/<model>/<method>` | HTTPS (TLS, system trust store), Odoo External JSON-2 API, `Authorization: bearer <API key>` | Catalogue, prices/taxes, customers, POS sessions, orders and payment lines (transaction id, RRN, auth code, last 4 digits, card brand) |

That is the only network destination. Cleartext traffic is disabled
(`usesCleartextTraffic="false"` and `network_security_config.xml` with
`cleartextTrafficPermitted="false"`). No analytics, ads, crash-reporting or other
third-party SDKs. OkHttp does not retry POSTs automatically (`retryOnConnectionFailure(false)`).

**Payments** never touch the network from this app: they go App-to-App over Android IPC to
the resident Pine Labs MasterApp (`com.pinelabs.masterapp`, declared in `<queries>`) through
the Pine Billing SDK 2.0.0. The app never sees or handles card data. It receives only the
masked PAN, from which it keeps the last 4 digits.

## 3. Exported components

| Component | Exported | Protection |
|---|---|---|
| `ui.MainActivity` | yes | Launcher activity (MAIN/LAUNCHER) |
| `ui.BootReceiver` | yes | Listens only to `BOOT_COMPLETED`, a protected broadcast only the system can send |
| `androidx.profileinstaller.ProfileInstallReceiver` | yes | Requires `android.permission.DUMP` (shell/system only) |
| `androidx.startup.InitializationProvider`, `androidx.room.MultiInstanceInvalidationService` | no | — |

## 4. Data at rest (all in the app's private storage)

| Data | Where | Protection |
|---|---|---|
| Odoo API key, POS config, payment method ids, printer width, kiosk flag | `EncryptedSharedPreferences` | AES-256-GCM values, AES-256-SIV keys, Android Keystore master key |
| Payment journal: billing ref, amount, mode, state, terminal transaction id, RRN, auth code, last 4, card brand | Room DB `bohemian-pos.db` | App-private; **no full PAN, no CVV, no track data ever stored** |
| Void journal (same fields for reversals) | Room DB | App-private |
| Order outbox: `sync_from_ui` payloads waiting for Odoo (lines, totals, partner id, payment lines as above) | Room DB | App-private; deleted once Odoo confirms |
| Sale snapshot (cart at payment start, incl. customer name and phone, for crash recovery) | Room DB | App-private; deleted once the sale is finalised |
| Catalogue cache (products, prices, barcodes, POS config) | `files/catalog/catalog.json` | App-private; not sensitive |
| Crash reports (stack traces) | `files/crash/` | App-private; redacted before writing (§5); newest 10 kept |

`android:allowBackup="false"`, `fullBackupContent="false"` and `dataExtractionRules` (Android 12+, everything excluded): nothing goes to a device or cloud backup or device transfer.
Debug builds use the debug key, never the release key.
Debug builds only (never release): `sync_from_ui` payloads are also written to app-specific
external storage for diffing.

## 5. Logging policy

- One tag (`BohemianPOS`), structured `[EVENT] key=value` lines (`core/Log.kt`).
- Never logged: API keys, keystore passwords, full card PAN, customer phone numbers
  (HARD RULE 11). Phones go through `Redact.phone` (last 4), PANs through `Redact.last4`.
- Free-text values (`err`, `detail`, `msg`: Odoo or terminal error text) go through the same
  redaction as crash reports before they are logged (`PosLogTest`). Release builds are minified with R8, and `Log.v` / `Log.d` calls are stripped
  (`proguard-rules.pro`, `-assumenosideeffects`).
- Crash reports go through `CrashLog.redact`: digit runs of 10 or more are masked to their
  last 4 (phones, PANs), `bearer` tokens and `api_key=` values are removed. Covered by
  `CrashLogTest`.

## 6. Kiosk mode — requirement on the device policy (PAXSTORE)

Kiosk mode is off by default. When it is on, the app calls `startLockTask()` **only if**
`DevicePolicyManager.isLockTaskPermitted(packageName)` is true. It never falls back to the
user-confirmed screen-pinning prompt. For it to work, the device policy controller must
whitelist **both** packages for lock task:

- `com.bohemian.odoo.pos`, and
- `com.pinelabs.masterapp`. Without it, Android blocks the MasterApp payment screen while the
  till is pinned, and every payment fails.

## 7. Money safety controls (summary)

Card data stays inside MasterApp. One terminal operation runs at a time. Every payment and
void is saved before the terminal is called. An unclear outcome is never sent again; it goes
to a supervisor. The amount the terminal reports must equal the amount requested. Paid
orders wait in a durable outbox until Odoo confirms them. A POS session cannot close while
any of that is unfinished. Full list: `CLAUDE.md` §3–§4 and `docs/INVARIANTS.md`.

## 8. Pre-release security audit (production APK, 2026-10-27)

Checked on `bohemian-odoo-pos-PROD-1-0.1.0-signed.apk` (6,067,647 bytes,
SHA-256 `23cbd48d6f7ed28d0ade76d7165c069a26db55b983548fb32ded768c506757b9`).

| Check | Result |
|---|---|
| Signing | v1 (JAR) + v2 verified (`apksigner verify --min-sdk-version 21`); cert SHA-256 `5062f7cff3f3cc2daaa5ab03c0543ee0ff0417bf7cad2eb34735cfc60fc2cbda` |
| `debuggable` | Absent (not debuggable) |
| Secrets in the APK (dex strings, assets, resources) | None. No API key, token, password, keystore or `.env` content. The only 32-hex strings are the PROD Pine Application ID and Room schema hashes. The UAT Application ID is not in the PROD build |
| Network | One host, `https://zenzebra1.odoo.com`; cleartext disabled; system trust store only; no HTTP body logging |
| Debug-only features | FAKE payment gateway and `sync_from_ui` payload dump are gated by `BuildConfig.DEBUG` (false in release); the Setup switch is hidden |
| `Log.d` / `Log.v` | 0 calls left in the release dex (R8 strip) |
| Exported components | Launcher activity, `BootReceiver` (protected `BOOT_COMPLETED` only), AndroidX `ProfileInstallReceiver` (needs `DUMP`). Provider and service not exported |
| Backup / device transfer | Disabled (`allowBackup=false`, `dataExtractionRules` exclude all) |
| Odoo API key on screen | Never shown again after saving; entry field is masked |
| Lint (`lintProdRelease`) | 0 errors; 4 warnings, none security-relevant (target API, package visibility of the Pine check, fixed landscape, ChromeOS ABI) |
| Unit tests | 139 pass |
| Native code | Only the Pine SDK's `libuniffi_pine_billing.so` and JNA `libjnidispatch.so` (arm64-v8a, armeabi-v7a) |

Known, accepted:

- The customer's mobile number (when a customer is attached to the sale) is passed to the Pine
  MasterApp as `customer_mobile` on the sale. It is never logged by this app; Pine's debug
  MasterApp build was seen logging request bodies — a Pine-side setting.
- `androidx.security:security-crypto` 1.1.0-alpha06 (EncryptedSharedPreferences) is deprecated
  upstream but has no known vulnerability. Replacing it would need a key-migration step.
- The Setup screen (Odoo API key, POS config, kiosk switch) has no supervisor PIN yet. Store
  procedure until then: kiosk mode on, and the device kept by the store manager.
