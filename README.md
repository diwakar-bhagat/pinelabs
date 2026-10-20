# Bohemian Odoo POS — Pine Labs E700

Native Android till for the Pine Labs E700 (PayDroid Android 7.1.2) using **Odoo 19** as the
system of record and **Pine Labs App-to-App** for Card / UPI / Bharat QR on the same device.

- **Start here:** [`CLAUDE.md`](CLAUDE.md) — architecture, hard money-safety rules, verified Odoo/Pine facts.
- **Plan & UAT script:** [`docs/PLAN.md`](docs/PLAN.md)
- **Getting it onto the E700:** [`docs/DEPLOY_E700.md`](docs/DEPLOY_E700.md)

## Quick start (Mac)

```bash
# 1. Pine SDK (link from the Pine MCP; expires ~1h)
mkdir -p app/libs/pine-billing-sdk-android-maven
unzip pine-billing-sdk-android-1.0.0-prod.zip -d app/libs/pine-billing-sdk-android-maven

# 2. Open in Android Studio, or:
./gradlew testUatDebugUnitTest     # money, receipt, payment state machine, Odoo payload
./gradlew installUatDebug          # needs ADB access to the UAT E700 (see DEPLOY doc)
```

No SDK yet? The project still builds with a stub gateway; in a debug build turn on
**Setup → Use FAKE payment gateway** to exercise the whole flow on any Android 7+ tablet.
Fake outcomes by amount (last two paise digits): `..51` decline, `..91` timeout→paid,
`..92` timeout→not paid, `..93` timeout→unknown.
