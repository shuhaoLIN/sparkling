// Copyright (c) 2022 TikTok Pte. Ltd.
// Licensed under the Apache License Version 2.0 that can be found in the
// LICENSE file in the root directory of this source tree.

package com.tiktok.sparkling.method.registry.api

import com.lynx.react.bridge.ReadableMap
import com.tiktok.sparkling.method.protocol.BridgeContext
import com.tiktok.sparkling.method.protocol.entity.BridgeCall
import com.tiktok.sparkling.method.protocol.interfaces.IBridgeHandler
import com.tiktok.sparkling.method.protocol.interfaces.IBridgeMethodCallback
import com.tiktok.sparkling.method.registry.api.util.BridgeMethodCallbackHelper
import com.tiktok.sparkling.method.registry.core.BridgePlatformType
import com.tiktok.sparkling.method.registry.core.IBridgeContext
import com.tiktok.sparkling.method.registry.core.IBridgePlatformType
import com.tiktok.sparkling.method.registry.core.IDLBridgeMethod
import org.json.JSONObject

/**
 * Resolves calls through one container's [pool] and runs them with the processor matching the
 * call's parameter carrier. Host subclasses may supply their own [BridgeLocalPool] instance.
 */
open class DefaultCallHandler protected constructor(
    private val pool: BridgeLocalPool,
) : IBridgeHandler {
    private var context: IBridgeContext? = null
    private var isReleased = false

    /** Creates a handler with the default container-scoped pool. */
    constructor() : this(BridgeLocalPool())

    /** Dispatches the call through the matching parameter processor. */
    override fun handle(
        bridgeContext: BridgeContext,
        call: BridgeCall,
        callback: IBridgeMethodCallback,
    ) {
        val platform = BridgeContext.getPlatformByBridgeContext(bridgeContext)
        val bridge = pool.getBridge(call.bridgeName, platform)
            ?: return BridgeMethodCallbackHelper.bridgeNotFound(callback)

        context?.let {
            bridge.setBridgeContext(it)
        }

        when (val params = call.params) {
            is JSONObject -> {
                JsonProcessor(bridge, params, call.platform).apply { this@apply.context = this@DefaultCallHandler.context }
            }

            is ReadableMap -> {
                ReadableMapProcessor(bridge, unWrapperParams(params) ?: params).apply { this@apply.context = this@DefaultCallHandler.context }
            }

            else -> {
                return
            }
        }.handle(callback)
    }

    /** Registers a method class in this handler's pool. */
    fun registerLocalIDLMethod(
        clazz: Class<out IDLBridgeMethod>?,
        scope: IBridgePlatformType = BridgePlatformType.ALL,
    ) {
        pool.registerLocalIDLMethod(clazz, scope)
    }

    /** Registers a factory in this handler's pool. */
    fun registerLocalIDLMethod(
        name: String,
        scope: IBridgePlatformType = BridgePlatformType.ALL,
        clazz: Class<out IDLBridgeMethod>? = null,
        factory: () -> IDLBridgeMethod,
    ) {
        pool.registerLocalIDLMethod(name, scope, clazz, factory)
    }

    /** Supplies the SDK context to the pool and subsequently handled methods. */
    fun setBridgeContext(context: IBridgeContext) {
        this.context = context
        this.pool.setBridgeContext(context)
    }

    /** Finds a method in this handler's pool for the context's platform. */
    fun getBridge(
        bridgeContext: BridgeContext,
        bridgeName: String,
    ): IDLBridgeMethod? {
        val platform = BridgeContext.getPlatformByBridgeContext(bridgeContext)
        return pool.getBridge(bridgeName, platform)
    }

    /** Releases the pool before marking this handler released. */
    override fun onRelease() {
        pool.release()
        isReleased = true
    }

    /** Whether the pool has completed a release through this handler. */
    override fun isReleased(): Boolean = isReleased

    /** Shares the existing data-wrapper extraction with host handler subclasses. */
    protected fun unWrapperParams(params: ReadableMap?): ReadableMap? = params?.getMap("data") ?: params
}
