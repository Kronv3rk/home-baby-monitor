#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
testdir="$(mktemp -d)"
trap 'rm -rf "$testdir"' EXIT
kotlinc \
  app/src/main/java/com/homebabymonitor/core/*.kt \
  app/src/main/java/com/homebabymonitor/net/*.kt \
  tools/jvm-stubs/android/content/*.kt \
  tools/jvm-stubs/android/net/*.kt \
  tools/jvm-stubs/android/util/*.kt \
  tools/jvm-stubs/android/os/*.kt \
  tools/CoreSmokeTest.kt \
  tools/LocalSocketSmokeTest.kt \
  tools/SessionWatchdogSmokeTest.kt \
  tools/ProtocolStressTest.kt \
  tools/SecureChannelSmokeTest.kt \
  tools/ServerIntegrationSmokeTest.kt \
  tools/InteractionSmokeTest.kt \
  tools/WifiEndpointSmokeTest.kt \
  tools/InitializationGateSmokeTest.kt \
  tools/MediaSettingsSmokeTest.kt \
  -include-runtime -d "$testdir/tests.jar"
for test in CoreSmokeTest LocalSocketSmokeTest SessionWatchdogSmokeTest ProtocolStressTest SecureChannelSmokeTest ServerIntegrationSmokeTest InteractionSmokeTest WifiEndpointSmokeTest InitializationGateSmokeTest MediaSettingsSmokeTest; do
  echo "Running $test"
  java -cp "$testdir/tests.jar" "${test}Kt"
done
