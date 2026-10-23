---
name: build-test
description: Set up, build and unit-test the Bohemian Odoo POS Android app on the dev Mac (JDK, Android SDK, Pine SDK bundle, Gradle tasks). Use when asked to build, run tests, assemble an APK, or when a fresh clone needs setting up.
---

# Build & test (Mac only)

Cloud containers can't reach `dl.google.com` / `maven.google.com` / `services.gradle.org` — build on the Mac.
In a Claude Code sandbox, Gradle needs network access to those hosts.

## One-time setup in a fresh clone

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
# Pine SDK bundle (git-ignored). Existing copy on this Mac:
cp -R ~/Downloads/pine-billing-sdk-android-1.0.0-prod/"bohemian-odoo-pos 2"/app/libs/pine-billing-sdk-android-maven app/libs/
```
No bundle → Gradle warns and compiles `src/pineStub` (no real payments). With it → `src/pineSdk`.

## Tasks

| Goal | Command |
|---|---|
| Unit tests (JVM) | `./gradlew testUatDebugUnitTest` — results in `app/build/test-results/` |
| Debug APK | `./gradlew assembleUatDebug` |
| Install on E700 over ADB | `./gradlew installUatDebug` (needs ADB access, see deploy-e700) |
| Signed release for Pine | `./gradlew assembleUatRelease` (needs `keystore.properties` + `.jks`) |

Count results:
```bash
find app/build/test-results -name '*.xml' -exec grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' {} \;
```
Baseline 2026-10-23: **47 tests, 0 failures**, compiled against the real SDK 1.0.0.

Debug builds can use **Setup → Use FAKE payment gateway**; fake outcome by last two paise digits:
`..51` decline, `..91` timeout→paid, `..92` timeout→not paid, `..93` timeout→unknown.
