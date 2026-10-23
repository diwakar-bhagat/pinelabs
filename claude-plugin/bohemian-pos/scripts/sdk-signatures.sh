#!/usr/bin/env bash
# Print the Pine SDK signatures CLAUDE.md depends on, straight from classes.jar in the AAR.
# Usage: sdk-signatures.sh [repo-root]   (needs JDK 17+; uses JAVA_HOME or temurin-21)
set -euo pipefail
ROOT="${1:-${CLAUDE_PROJECT_DIR:-$(pwd)}}"
AAR=$(find "$ROOT/app/libs/pine-billing-sdk-android-maven" -name '*.aar' | head -1)
[ -n "$AAR" ] || { echo "Pine SDK AAR not found under app/libs/ — see build-test skill"; exit 1; }
JAVAP="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}/bin/javap"
W=$(mktemp -d "${TMPDIR:-/tmp}/pine-sdk.XXXXXX"); trap 'rm -rf "$W"' EXIT
unzip -q -o "$AAR" classes.jar -d "$W" && unzip -q -o "$W/classes.jar" -d "$W/cls"
echo "AAR: $AAR"
for c in 'com.pinelabs.billing.sdk.PineBillingSdk$Companion' com.pinelabs.billing.sdk.PineBillingSdk \
         uniffi.pine_billing.TransactionOptions uniffi.pine_billing.TransactionResult \
         uniffi.pine_billing.TransactionType uniffi.pine_billing.PrintItem ${EXTRA_CLASSES:-}; do
  echo; echo "== $c"; "$JAVAP" -cp "$W/cls" "$c" 2>/dev/null | grep -vE 'FfiConverter|\$default|component[0-9]|copy' || echo "(not found)"
done
