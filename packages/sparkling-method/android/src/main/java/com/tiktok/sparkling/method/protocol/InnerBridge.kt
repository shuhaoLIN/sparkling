// Copyright (c) 2022 TikTok Pte. Ltd.
// Licensed under the Apache License Version 2.0 that can be found in the
// LICENSE file in the root directory of this source tree.

package com.tiktok.sparkling.method.protocol

import android.content.Context
import android.view.View
import com.lynx.tasm.LynxBackgroundRuntime
import com.lynx.tasm.LynxBackgroundRuntimeOptions
import com.lynx.tasm.LynxView
import com.lynx.tasm.LynxViewBuilder
import com.tiktok.sparkling.method.protocol.handler.BridgeDispatcher
import com.tiktok.sparkling.method.protocol.impl.lynx.LynxBridgeProtocol
import com.tiktok.sparkling.method.protocol.impl.lynx.LynxViewImpl
import com.tiktok.sparkling.method.protocol.impl.monitor.IBridgeMonitor
import com.tiktok.sparkling.method.protocol.interfaces.IBridgeHandler
import com.tiktok.sparkling.method.protocol.interfaces.IBridgeLifeClient
import com.tiktok.sparkling.method.protocol.interfaces.IBridgeProtocol
import com.tiktok.sparkling.method.registry.api.SparklingBridge
import com.tiktok.sparkling.method.registry.api.SparklingMethodInvocationCenter
import com.tiktok.sparkling.method.registry.core.BridgePlatformType
import org.json.JSONObject

/**
 * Binds one container to a [BridgeContext]: initializes its protocols, registers its Lynx modules
 * and forwards native events to it.
 *
 * Host subclasses may supply their own [BridgeContext] and [BridgeDispatcher] and override the
 * protected hooks to add container-specific protocols, modules, and event observers.
 */
internal open class InnerBridge protected constructor(
    private val mBridgeContext: BridgeContext,
    dispatcher: BridgeDispatcher,
) {
    constructor() : this(BridgeContext(), BridgeDispatcher())

    init {
        mBridgeContext.dispatcher = dispatcher
    }

    /**
     * Binds [bridge] to [mBridgeContext] before initializing protocols for [view].
     */
    fun init(
        view: View,
        containerId: String?,
        jsBridgeProtocols: Int? = null,
        bridge: SparklingBridge,
    ) {
        mBridgeContext.sparklingBridge = bridge
        init(view, containerId, jsBridgeProtocols)
    }

    /** Initializes container protocols for [view] and records [containerId]. */
    fun init(
        view: View,
        containerId: String?,
        jsBridgeProtocols: Int? = null,
    ) {
        if (view is LynxView) {
            mBridgeContext.lynxView = view
            onLynxPlatformBound()
            mBridgeContext.registerProtocol(createLynxBridgeProtocol())
            onLynxProtocolRegistered()
        } else {
            initNonLynxView(view, jsBridgeProtocols)
        }
        mBridgeContext.protocols.forEach {
            it.init()
        }
        mBridgeContext.containerId = containerId
    }

    /** Initializes protocols and JS runtime modules for a standalone Lynx background runtime. */
    fun initLynxJSRuntime(
        containerId: String,
        options: LynxBackgroundRuntimeOptions,
        context: Context,
        bridge: SparklingBridge,
    ) {
        mBridgeContext.sparklingBridge = bridge
        onLynxPlatformBound()
        mBridgeContext.registerProtocol(createLynxBridgeProtocol())
        mBridgeContext.protocols.forEach {
            it.init()
        }
        createLynxViewImpl().initJSRuntime(options)
        mBridgeContext.containerId = containerId
    }

    /** Registers a lifecycle observer on the underlying [BridgeContext]. */
    fun registerIBridgeLifeClient(bridgeLifeClient: IBridgeLifeClient) {
        mBridgeContext.registerIBridgeLifeClient(bridgeLifeClient)
    }

    /** Registers bridge modules on [builder] and records [containerId]. */
    fun registerLynxModule(
        builder: LynxViewBuilder,
        containerId: String?,
    ) {
        createLynxViewImpl().apply {
            init(builder)
            registerExtraLynxModules(this, builder)
        }
        mBridgeContext.containerId = containerId
    }

    /** Registers an observer for bridge calls and native-to-JS events. */
    fun registerMonitor(monitor: IBridgeMonitor) {
        mBridgeContext.bridgeMonitors.add(monitor)
    }

    /** Registers the call handler on the container's dispatcher. */
    fun registerHandler(handler: IBridgeHandler) {
        mBridgeContext.dispatcher?.registerHandler(handler)
    }

    /** Returns this container's [BridgeContext]. */
    open fun getBridgeContext(): BridgeContext = mBridgeContext

    /** Notifies observers and dispatches a native-to-JS event across registered protocols. */
    fun sendEvent(
        event: String,
        data: JSONObject?,
    ) {
        onBridgeEvent(event, data)
        mBridgeContext.protocols.forEach {
            it.sendEvent(event, data)
        }
    }

    /** Notifies observers and dispatches a native-to-JS event to the Lynx background runtime. */
    fun sendJSRuntimeEvent(
        event: String,
        data: JSONObject?,
    ) {
        onBridgeEvent(event, data)
        mBridgeContext.protocols.forEach {
            it.sendJSRuntimeEvent(event, data)
        }
    }

    /** Attaches the live [LynxBackgroundRuntime] to [mBridgeContext]. */
    fun bindLynxJSRuntime(lynxBackgroundRuntime: LynxBackgroundRuntime) {
        mBridgeContext.lynxBackgroundRuntime = lynxBackgroundRuntime
    }

    /** Marks the container as Lynx before its Lynx protocol is registered. */
    protected open fun onLynxPlatformBound() {
        mBridgeContext.platformType = BridgePlatformType.LYNX
    }

    /** Creates the Lynx protocol implementation bound to [mBridgeContext]. */
    protected open fun createLynxBridgeProtocol(): IBridgeProtocol = LynxBridgeProtocol(mBridgeContext)

    /** Runs right after the Lynx protocol of a LynxView container is registered. */
    protected open fun onLynxProtocolRegistered() {}

    /** Initializes a container whose view is not a LynxView. */
    protected open fun initNonLynxView(
        view: View,
        jsBridgeProtocols: Int?,
    ) {}

    /** Creates the [LynxViewImpl] wrapper used to register Lynx modules. */
    protected open fun createLynxViewImpl(): LynxViewImpl = LynxViewImpl(mBridgeContext)

    /** Registers host modules on [builder] after the bridge module. */
    protected open fun registerExtraLynxModules(
        lynxViewImpl: LynxViewImpl,
        builder: LynxViewBuilder,
    ) {}

    /** Observes every event before it is sent to the protocols. */
    protected open fun onBridgeEvent(
        event: String,
        data: JSONObject?,
    ) {
        SparklingMethodInvocationCenter.notifyNativeToJsEvent(
            name = event,
            params = data,
            containerId = mBridgeContext.containerId,
        )
        mBridgeContext.bridgeMonitors.forEach { monitor ->
            runCatching { monitor.onBridgeEvent(event, data) }
        }
    }
}
