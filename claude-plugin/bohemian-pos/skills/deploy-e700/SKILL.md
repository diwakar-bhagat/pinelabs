---
name: deploy-e700
description: Release pipeline for the Bohemian POS — signed APK, Pine Labs infosec review, PAXSTORE UAT then PROD, and the signing-key rules. Use when asked to ship, release, send the APK to Pine, bump versions, or switch to the PROD App ID. Details live in docs/DEPLOY_E700.md.
---

# Deploy to the E700

```
assembleUatRelease → email APK to Pine → infosec review → PAXSTORE → UAT E700 (SN 1340055807)
  → 10-case UAT (uat-e700 skill) → sign-off → PROD App ID issued → set pineProdAppId → assembleProdRelease → PAXSTORE PROD
```

Read `docs/DEPLOY_E700.md` for the full steps.

## Rules

- **Same signing key forever.** Key loss = no app updates ever. Never regenerate; never commit `.jks` or `keystore.properties`.
- Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts` for every APK sent to Pine.
- `applicationId` stays `com.bohemian.odoo.pos`.
- PROD build refuses an empty `pineProdAppId` (HARD RULE 10) — don't ship prod until Pine issues it.
- Release builds use the real gateway only and strip debug logs.
- Before building a release run the `money-safety-review` skill and the unit tests.

This is an Android APK for PAXSTORE — it is **not** a Vercel/web deployment.
Claude never emails Pine or uploads to PAXSTORE; it prepares the APK, checksum and infosec notes for the owner to send.
