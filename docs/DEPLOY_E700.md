# Getting the app onto the E700

There are two tracks. **Development** is how you test builds quickly on your UAT
terminal. **Release** is the only way the app reaches store terminals: through Pine Labs.

---

## 1. One-time setup on your Mac

1. Install **Android Studio** (latest) — it brings JDK 17 and the Android SDK.
2. Download the Pine SDK (link from the Pine MCP; expires in ~1 hour):
   `pine-billing-sdk-android-1.0.0-prod.zip`.
3. Unzip it into the project:
   ```bash
   mkdir -p app/libs/pine-billing-sdk-android-maven
   unzip pine-billing-sdk-android-1.0.0-prod.zip -d app/libs/pine-billing-sdk-android-maven
   ```
   Check that `app/libs/pine-billing-sdk-android-maven/com/pinelabs/billing/...` exists.
   If the zip contains a nested `*-maven.zip`, unzip that one into the same folder.
4. Open the project folder in Android Studio → let Gradle sync.
   (First time only, if Studio asks: "Create Gradle wrapper" → yes.)
5. In Studio: **External Libraries → pine-billing-sdk-android → classes.jar →
   `uniffi.pine_billing`** — compare `SdkConfig`, `TransactionRequest`,
   `TransactionOptions`, `PrintRequest`, `PrintItem` constructors with
   `app/src/main/java/com/bohemian/odoo/pos/pine/PineSdkGateway.kt` and fix any drift.
   That file is the only place SDK types appear.

## 2. Create the release signing key — do this ONCE and guard it

Pine Labs: *"The same signing key should be used for all future releases."*
If this key is lost, **no update can ever be installed on the terminals again.**

```bash
keytool -genkeypair -v -keystore bohemian-pos-release.jks -alias bohemianpos \
  -keyalg RSA -keysize 4096 -validity 10000
```

Then create `keystore.properties` in the project root (git-ignored):
```
storeFile=/absolute/path/to/bohemian-pos-release.jks
storePassword=...
keyAlias=bohemianpos
keyPassword=...
```

Back up the `.jks` + passwords in **two** places (e.g. company password manager +
encrypted drive). Record the SHA-256 fingerprint:
```bash
keytool -list -v -keystore bohemian-pos-release.jks -alias bohemianpos | grep SHA256
```

## 3. Development track — install on your UAT E700

PAX terminals block unknown APKs in "production" mode. Ask Pine Labs for one of:

- **(a) Developer / debug mode** on the UAT unit (USB debugging enabled), then:
  ```bash
  adb devices                      # E700 via USB-C / USB cable
  ./gradlew installUatDebug        # or Run ▶ in Android Studio
  adb logcat -s BohemianPOS PineBillingSdk
  ```
- **(b) PAXSTORE test/UAT channel**: you upload the APK to their test store and push it
  to your UAT terminal serial **1340055807**.

Ask them: *"Please enable developer mode (ADB sideload) on UAT terminal SN 1340055807,
or tell us the PAXSTORE UAT channel process."*

**Payments app on this terminal (checked 2026-10-24):** Pine **Plutus Plus**
(`com.pinelabs.plutusplus`) is installed, but `com.pinelabs.masterapp` (the App-to-App
Master service the SDK binds to) is not. Pine must provision it before card/UPI payments
work from the till (`docs/PINE_LABS_VERIFIED.md` §6).

Without either, you can still develop the whole UI on any Android 7+ tablet/emulator
with **Settings → Payment gateway: Fake** (debug builds only). Real payments need the E700.

## 4. Release track — how it reaches the store terminals

```
./gradlew assembleUatRelease   →  app-uat-release.apk (signed with the release key)
        │
        ▼
Email/upload APK to Pine Labs  →  Pine infosec review  →  PAXSTORE (UAT)
        │                                                     │
        │                                              push to UAT E700, run UAT script
        ▼                                                     │
Pine issues PROD Application ID  ◄─────── UAT sign-off ───────┘
        │
./gradlew assembleProdRelease → app-prod-release.apk (SAME key)  →  infosec  →  PAXSTORE (PROD)
        │
        ▼
Pine pushes to store terminals (by serial / merchant group)
```

Each new version:
1. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`.
2. Build release with the **same** keystore.
3. Send APK + short changelog to Pine → infosec → PAXSTORE push.

## 5. Odoo side — before the first real sale

In Odoo (Point of Sale → Configuration):
1. Create POS **"E700 UAT"** (`pos.config`), pricelist Default (INR).
2. Create payment method **"Pine Labs E700 (A2A)"** — journal Bank, **no** payment
   terminal selected (the app drives the terminal). Add it + Cash to "E700 UAT".
3. Create an **API key** for a dedicated user "E700 Till UAT" (Settings → Users →
   that user → Account Security → New API Key). Give that user POS User rights only.
   Enter the key once in the app's Setup screen. One key per terminal.
4. Add the new `pos.config` id to the filters of the existing automations
   ("POS: auto-send WhatsApp bill when paid", ZZ Cash rules) so they fire for E700 orders.
