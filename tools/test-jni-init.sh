#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT
javac -d "$workdir" app/src/main/java/org/jni_zero/JniInit.java tools/JniInitSmokeTest.java
java -cp "$workdir" JniInitSmokeTest
