#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
rules="$root/app/proguard-rules.pro"
grep -Fqx -- '-keep class org.webrtc.** { *; }' "$rules" || { echo 'Missing org.webrtc keep rule' >&2; exit 1; }
grep -Fqx -- '-keep class org.jni_zero.** { *; }' "$rules" || { echo 'Missing org.jni_zero keep rule' >&2; exit 1; }
if (( $# == 1 )); then
  python3 - "$1" <<'PY'
import os
import sys
import zipfile

apk = sys.argv[1]
if not os.path.isfile(apk):
    sys.exit(f"APK not found: {apk}")
with zipfile.ZipFile(apk) as archive:
    dex_names = [entry for entry in archive.namelist()
                 if entry.startswith("classes") and entry.endswith(".dex")]
    if not dex_names:
        sys.exit("Release APK has no DEX files")
    descriptor = b"Lorg/jni_zero/JniInit;"
    if not any(descriptor in archive.read(name) for name in dex_names):
        sys.exit("WebRTC native bootstrap class org.jni_zero.JniInit is missing from release DEX")
PY
fi
echo 'WebRTC JNI keep rules and release bootstrap class verified.'
