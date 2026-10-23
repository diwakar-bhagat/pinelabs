#!/bin/bash
# One-shot build + test script for Bohemian Odoo POS
set -e

cd "$(dirname "$0")"

echo "=== Checking environment ==="
echo "JAVA_HOME: ${JAVA_HOME:-not set}"
echo "SDK dir (local.properties): $(grep sdk.dir local.properties 2>/dev/null || echo 'missing')"

# Verify SDK
SDK_DIR=$(grep sdk.dir local.properties 2>/dev/null | cut -d= -f2)
if [ ! -d "$SDK_DIR/platforms" ]; then
    echo "ERROR: Android SDK not found at $SDK_DIR"
    exit 1
fi
echo "SDK OK: $(ls "$SDK_DIR/platforms/")"

# Verify JDK
GRADLE_JAVA=$(grep org.gradle.java.home gradle.properties 2>/dev/null | cut -d= -f2)
if [ -n "$GRADLE_JAVA" ] && [ ! -d "$GRADLE_JAVA" ]; then
    echo "WARNING: gradle.properties points to missing JDK: $GRADLE_JAVA"
    echo "Removing org.gradle.java.home to use system Java..."
    sed -i.bak '/org.gradle.java.home/d' gradle.properties
fi

echo ""
echo "=== Running unit tests ==="
./gradlew testUatDebugUnitTest --no-daemon

echo ""
echo "=== Building UAT debug APK ==="
./gradlew assembleUatDebug --no-daemon

echo ""
echo "=== DONE ==="
echo "APK: $(find app/build -name '*uat-debug*.apk' 2>/dev/null | head -1)"
