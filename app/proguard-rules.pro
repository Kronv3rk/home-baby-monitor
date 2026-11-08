-keep class org.webrtc.** { *; }
-keep class org.webrtc.audio.** { *; }
-dontwarn org.webrtc.**

# WebRTC's JNI_OnLoad looks up org.jni_zero classes by their original names.
# Without this keep rule R8 may strip JniInit and abort native initialization.
-keep class org.jni_zero.** { *; }
# The optional generated stub is not shipped inside the WebRTC AAR.
-dontwarn org.jni_zero.JniZeroJni
