// Copyright (c) 2025 TikTok Pte. Ltd.
// Licensed under the Apache License Version 2.0 that can be found in the
// LICENSE file in the root directory of this source tree.
package com.tiktok.sparkling.method.protocol.utils

import com.tiktok.sparkling.method.registry.api.SparklingBridge
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [LogUtils]. The actual logging implementation is provided by
 * android.util.Log (Robolectric), so these tests verify that all levels are
 * reachable both when isDebugEnv is true and false.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ProtocolUtilsTest {
    @After
    fun tearDown() {
        SparklingBridge.isDebugEnv = false
    }

    @Test
    fun logUtilsExecutesAllLevelsWhenDebugEnabled() {
        SparklingBridge.isDebugEnv = true
        LogUtils.e("tag", "err")
        LogUtils.d("tag", "dbg")
        LogUtils.w("tag", "warn")
        LogUtils.i("tag", "info")
        LogUtils.v("tag", "verbose")
    }

    @Test
    fun logUtilsIsNoopWhenDebugDisabled() {
        SparklingBridge.isDebugEnv = false
        LogUtils.e("tag", "err")
        LogUtils.d("tag", "dbg")
        LogUtils.w("tag", "warn")
        LogUtils.i("tag", "info")
        LogUtils.v("tag", "verbose")
    }
}
