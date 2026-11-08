// Copyright 2024 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
//
// WebRTC M150 JNI_OnLoad requires this bridge before the native library loads.
// The upstream WebRTC AAR distribution does not always bundle it.
package org.jni_zero;

import java.util.Collections;

/** Initialization values consumed by jni_zero.cc during native library load. */
public final class JniInit {
    private JniInit() {}

    // Called via GetStaticMethodID during JNI_OnLoad; keep method name and signature.
    @SuppressWarnings("unused")
    private static Object[] init() {
        return new Object[] {Collections.EMPTY_LIST, Collections.EMPTY_MAP};
    }
}
