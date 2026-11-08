#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
rules="$root/app/proguard-rules.pro"
grep -Fqx -- '-keep class org.webrtc.** { *; }' "$rules" || { echo 'Missing org.webrtc keep rule' >&2; exit 1; }
grep -Fqx -- '-keep class org.jni_zero.** { *; }' "$rules" || { echo 'Missing org.jni_zero keep rule' >&2; exit 1; }
if (( $# == 1 )); then
  test -f "$1" || { echo "Missing release APK: $1" >&2; exit 1; }
  if ! unzip -p "$1" 'classes*.dex' | LC_ALL=C grep -aF 'Lorg/jni_zero/JniInit;' >/dev/null; then
    echo 'JNI Zero JniInit is missing from release DEX' >&2
    exit 1
  fi
fi
echo 'WebRTC JNI keep rules verified.'
