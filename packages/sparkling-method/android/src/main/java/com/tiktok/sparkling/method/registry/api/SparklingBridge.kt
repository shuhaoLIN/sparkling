// Copyright (c) 2022 TikTok Pte. Ltd.
// Licensed under the Apache License Version 2.0 that can be found in the
// LICENSE file in the root directory of this source tree.

package com.tiktok.sparkling.method.registry.api

import android.content.Context
import android.view.View
import com.lynx.tasm.LynxBackgroundRuntime
import com.lynx.tasm.LynxBackgroundRuntimeOptions
import com.lynx.tasm.LynxViewBuilder
import com.tiktok.sparkling.method.protocol.BridgeContext
import com.tiktok.sparkling.method.protocol.InnerBridge
import com.tiktok.sparkling.method.protocol.entity.BridgeCall
import com.tiktok.sparkling.method.protocol.entity.BridgeResult
import com.tiktok.sparkling.method.protocol.entity.IPlatform
import com.tiktok.sparkling.method.protocol.entity.Platform
import com.tiktok.sparkling.method.protocol.handler.BridgeFactoryManager
import com.tiktok.sparkling.method.protocol.handler.BridgeThreadDispatcher
import com.tiktok.sparkling.method.protocol.impl.interceptor.BridgeMockInterceptor
import com.tiktok.sparkling.method.protocol.impl.monitor.IBridgeMonitor
import com.tiktok.sparkling.method.protocol.interfaces.IBridgeCallback
import com.tiktok.sparkling.method.protocol.interfaces.IBridgeLifeClient
import com.tiktok.sparkling.method.protocol.utils.LogUtils
import com.tiktok.sparkling.method.registry.api.util.BridgeProtocolConstants
import com.tiktok.sparkling.method.registry.api.util.log
import com.tiktok.sparkling.method.registry.core.BridgeContextWrapper
import com.tiktok.sparkling.method.registry.core.BridgePlatformType
import com.tiktok.sparkling.method.registry.core.IBridgeContext
import com.tiktok.sparkling.method.registry.core.IBridgePlatformType
import com.tiktok.sparkling.method.registry.core.IDLBridgeMethod
import com.tiktok.sparkling.method.registry.core.IDLMethodRegistryCacheManager
import com.tiktok.sparkling.method.registry.core.JSEventDelegate
import com.tiktok.sparkling.method.registry.core.SparklingBridgeManager
import com.tiktok.sparkling.method.registry.core.SparklingBridgeManager.DEFAULT_NAMESPACE
import com.tiktok.sparkling.method.registry.core.interfaces.IContainerIDProvider
import com.tiktok.sparkling.method.registry.core.interfaces.IReleasable
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Container-level JSB entry point that wires protocol initialization, method registration,
 * native-to-JS event dispatch, monitoring, and container teardown.
 */
open class SparklingBridge internal constructor(
    internal open val innerBridge: InnerBridge,
    protected open val bridgeSdkContext: BridgeContextWrapper,
) : IReleasable {
    private val closedSubscribers = CopyOnWriteArrayList<Pair<String, JSONObject?>>()
    private val tag = "SparklingBridge"

    /** Creates a bridge backed by the default [InnerBridge] and [BridgeContextWrapper]. */
    constructor() : this(InnerBridge(), BridgeContextWrapper())

    companion object {
        /** Host hook that registers IDL methods. Invoked once before the first dispatch. */
        var bridgeRegister: BridgeRegister? = null

        /** Host hook that lazily creates a method on the current container. */
        var bridgeFactoryManager: BridgeFactoryManager? = null

        /** Controls which thread handles bridge calls for each runtime engine. */
        var bridgeThreadDispatcher: BridgeThreadDispatcher = BridgeThreadDispatcher()

        /** Whether the host is a debug environment. */
        var isDebugEnv = false

        /** Controls which bridge callbacks may be skipped. Disabled by default. */
        val cancelCallbackConfig: CancelCallbackConfig =
            CancelCallbackConfig().apply { enable = false }

        /** Registers a listener invoked whenever a bridge is initialized for a view. */
        fun attachInitListener(listener: BridgeInitListener) {
            BridgeManager.attachInitListener(listener)
        }
    }

    /**
     * Prepares a standalone Lynx background JS runtime before a [LynxBackgroundRuntime] instance exists.
     */
    fun prepareLynxJSRuntime(
        containerId: String,
        options: LynxBackgroundRuntimeOptions,
        context: Context,
    ) {
        BridgeManager.insert(containerId, this)
        innerBridge.initLynxJSRuntime(containerId, options, context, this)
        configureContainerSdkContext(containerId, view = null) { eventName, params ->
            sendJSRuntimeEvent(eventName, params)
        }
    }

    /** Attaches the live [LynxBackgroundRuntime] to the container context. */
    fun bindLynxJSRuntime(lynxBackgroundRuntime: LynxBackgroundRuntime) {
        innerBridge.bindLynxJSRuntime(lynxBackgroundRuntime)
    }

    /** Sends an event to the bound Lynx background JS runtime. */
    fun sendJSRuntimeEvent(
        eventName: String,
        jsonObject: JSONObject?,
    ) {
        innerBridge.sendJSRuntimeEvent(eventName, jsonObject)
    }

    /**
     * Initializes the protocol layer for [view] and wires this container's SDK context and handler.
     *
     * @see BridgeProtocolConstants
     */
    fun init(
        view: View,
        containerId: String?,
        jsBridgeProtocols: Int,
    ) {
        BridgeManager.insert(view, this)
        innerBridge.init(view, containerId, jsBridgeProtocols, this)
        configureContainerSdkContext(containerId, view = view) { eventName, params ->
            sendEvent(eventName, params)
        }
    }

    /** Binds this container to a dedicated business [namespace] handler. */
    fun bindWithBusinessNamespace(namespace: String) {
        getBridgeContext().businessCallHandler =
            createBusinessCallHandler(namespace).apply {
                setBridgeContext(bridgeSdkContext)
            }
    }

    /** Registers a business-namespace method class after [bindWithBusinessNamespace]. */
    fun registerBusinessIDLMethod(
        clazz: Class<out IDLBridgeMethod>,
        scope: IBridgePlatformType = BridgePlatformType.ALL,
    ) {
        getBridgeContext().getNamespace()?.let {
            getBridgeContext().businessCallHandler?.registerMethod(clazz, scope)
        }
    }

    /** Registers a business-namespace method factory after [bindWithBusinessNamespace]. */
    fun registerBusinessIDLMethod(
        name: String,
        scope: IBridgePlatformType = BridgePlatformType.ALL,
        clazz: Class<out IDLBridgeMethod>? = null,
        factory: () -> IDLBridgeMethod,
    ) {
        getBridgeContext().getNamespace()?.let {
            getBridgeContext().businessCallHandler?.registerMethod(name, scope, clazz, factory)
        }
    }

    /** Registers a method class globally via [SparklingBridgeManager]. */
    fun registerIDLMethod(
        clazz: Class<out IDLBridgeMethod>?,
        scope: IBridgePlatformType = BridgePlatformType.ALL,
        namespace: String = DEFAULT_NAMESPACE,
    ) {
        SparklingBridgeManager.registerIDLMethod(clazz, scope, namespace)
    }

    /** Registers a method factory globally via [SparklingBridgeManager]. */
    fun registerIDLMethod(
        name: String,
        scope: IBridgePlatformType = BridgePlatformType.ALL,
        namespace: String = DEFAULT_NAMESPACE,
        clazz: Class<out IDLBridgeMethod>? = null,
        factory: () -> IDLBridgeMethod,
    ) {
        SparklingBridgeManager.registerIDLMethod(name, scope, namespace, clazz, factory)
    }

    /** Returns whether [name] is registered in this container's bound business namespace. */
    fun isBusinessIDLMethodExists(
        name: String,
        platformType: IBridgePlatformType = BridgePlatformType.ALL,
    ): Boolean {
        if (getBridgeContext().getNamespace().isNullOrEmpty()) {
            return false
        }
        val businessHandler = getBridgeContext().businessCallHandler ?: return false
        return businessHandler.isMethodExists(name, platformType)
    }

    /** Registers a container-local method class on this container's default handler. */
    fun registerLocalIDLMethod(
        clazz: Class<out IDLBridgeMethod>?,
        scope: IBridgePlatformType = BridgePlatformType.ALL,
    ) {
        innerBridge.getBridgeContext().defaultCallHandler.registerLocalIDLMethod(clazz, scope)
    }

    /** Registers a container-local method factory on this container's default handler. */
    fun registerLocalIDLMethod(
        name: String,
        scope: IBridgePlatformType = BridgePlatformType.ALL,
        clazz: Class<out IDLBridgeMethod>? = null,
        factory: () -> IDLBridgeMethod,
    ) {
        innerBridge.getBridgeContext().defaultCallHandler.registerLocalIDLMethod(name, scope, clazz, factory)
    }

    /** Returns the SDK context bound to this container. */
    open fun getBridgeSDKContext(): IBridgeContext = bridgeSdkContext

    /** Returns the underlying protocol [BridgeContext] for this container. */
    open fun getBridgeContext(): BridgeContext = innerBridge.getBridgeContext()

    /** Registers an observer for this container's finished calls and native-to-JS events. */
    open fun registerMonitor(monitor: IBridgeMonitor) {
        innerBridge.registerMonitor(monitor)
    }

    /** Installs a debug mock interceptor on this container's [BridgeContext]. */
    fun registerJSBMockInterceptor(interceptor: BridgeMockInterceptor) {
        innerBridge.getBridgeContext().jsbMockInterceptor = interceptor
    }

    /** Registers Lynx bridge modules onto [builder] for [containerId]. */
    fun registerLynxModule(
        builder: LynxViewBuilder,
        containerId: String?,
    ) {
        innerBridge.registerLynxModule(builder, containerId)
    }

    /** Registers a lifecycle observer on this container's [BridgeContext]. */
    fun registerIBridgeLifeClient(bridgeLifeClient: IBridgeLifeClient) {
        innerBridge.registerIBridgeLifeClient(bridgeLifeClient)
    }

    /** Dispatches a native-to-JS event to all protocols attached to this container. */
    fun sendEvent(
        event: String,
        data: JSONObject?,
    ) {
        innerBridge.sendEvent(event, data)
    }

    /** Subscribes bridge calls to be dispatched when [release] tears down this container. */
    fun addClosedEventObserver(
        bridgeNames: List<String>,
        params: List<JSONObject?>,
    ) {
        bridgeNames.forEachIndexed { index, _ ->
            this.closedSubscribers.add(Pair(bridgeNames[index], params[index]))
        }
    }

    /** Dispatches close observers, releases handlers, and unregisters container-scoped state. */
    override fun release() {
        dealWithCloseEvent()
        innerBridge.getBridgeContext().defaultCallHandler.onRelease()
        innerBridge.getBridgeContext().businessCallHandler?.onRelease()
        onReleaseExtra()
        BridgeManager.remove(this)
        bridgeSdkContext.containerID?.let {
            IDLMethodRegistryCacheManager.unregisterIDLMethodRegistryCache(it)
        }
    }

    /** Wires [bridgeSdkContext], event delegates, container ID provider, and default call handler. */
    protected fun configureContainerSdkContext(
        containerId: String?,
        view: View?,
        eventSender: (String, JSONObject?) -> Unit,
    ) {
        registerBridgeContextOnSdkContext()
        bridgeSdkContext.setContainerID(containerId)
        view?.let { bridgeSdkContext.setView(it) }
        bindBridgeOnContext()
        bridgeSdkContext.setJSEventDelegate(createJSEventDelegate(eventSender))
        bridgeSdkContext.registerObject(
            IDLBridgeMethod.JSEventDelegate::class.java,
            object : IDLBridgeMethod.JSEventDelegate {
                override fun sendJSEvent(
                    eventName: String,
                    params: Map<String, Any?>?,
                ) {
                    eventSender(eventName, params?.let { Utils.mapToJSON(params) } ?: JSONObject())
                }
            },
        )
        bridgeSdkContext.registerObject(
            IContainerIDProvider::class.java,
            object : IContainerIDProvider {
                override fun provideContainerID(): String? = containerId
            },
        )
        val handler = getBridgeContext().defaultCallHandler
        handler.setBridgeContext(bridgeSdkContext)
        onDefaultCallHandlerReady(handler)
        innerBridge.registerHandler(handler)
    }

    /** Registers the protocol [BridgeContext] weakly inside [bridgeSdkContext]. */
    protected open fun registerBridgeContextOnSdkContext() {
        bridgeSdkContext.registerWeakObject(
            BridgeContext::class.java,
            innerBridge.getBridgeContext(),
        )
    }

    /** Binds this bridge instance onto [bridgeSdkContext]. */
    protected open fun bindBridgeOnContext() {
        bridgeSdkContext.setBridge(this)
    }

    /** Creates the [JSEventDelegate] registered on [bridgeSdkContext]. */
    protected open fun createJSEventDelegate(
        eventSender: (String, JSONObject?) -> Unit,
    ): JSEventDelegate =
        object : JSEventDelegate {
            override fun sendJSEvent(
                eventName: String,
                params: JSONObject?,
            ) {
                eventSender(eventName, params)
            }
        }

    /** Host hook invoked after [handler] receives [bridgeSdkContext] and before registration. */
    protected open fun onDefaultCallHandlerReady(handler: DefaultCallHandler) {}

    /** Creates the [BusinessCallHandler] for [bindWithBusinessNamespace]. */
    protected open fun createBusinessCallHandler(namespace: String): BusinessCallHandler =
        BusinessCallHandler(namespace)

    /** Resolves the call platform used when dispatching close observers in [release]. */
    protected open fun resolveCloseEventPlatform(bridgeContext: BridgeContext): IPlatform =
        when (bridgeContext.platformType) {
            BridgePlatformType.LYNX -> Platform.Lynx
            BridgePlatformType.WEB -> Platform.Web
            else -> Platform.Other
        }

    /** Creates the [BridgeCall] dispatched for a close observer. */
    protected open fun createCloseEventCall(bridgeContext: BridgeContext): BridgeCall =
        BridgeCall(bridgeContext)

    /** Resolves the URL attached to close observer bridge calls. */
    protected open fun resolveCloseEventUrl(bridgeContext: BridgeContext): String = ""

    /** Host hook invoked during [release] after releasing handlers and before removing container records. */
    protected open fun onReleaseExtra() {}

    private fun dealWithCloseEvent() {
        closedSubscribers.forEach { pair ->
            val bridgeContext = getBridgeContext()
            val curPlatform = resolveCloseEventPlatform(bridgeContext)
            bridgeContext.dispatcher?.onDispatchBridgeMethod(
                createCloseEventCall(bridgeContext).apply {
                    bridgeName = pair.first
                    params = pair.second
                    nameSpace = "DEFAULT"
                    platform = curPlatform
                    url = resolveCloseEventUrl(bridgeContext)
                },
                object : IBridgeCallback {
                    override fun onBridgeResult(
                        result: BridgeResult,
                        call: BridgeCall?,
                    ) {
                        LogUtils.d(
                            tag,
                            "dealWithCloseEvent, bridgeName: ${pair.first}}, result: $result",
                        )
                    }
                },
                bridgeContext,
            )
        }
    }
}

/** Stores initialized bridges and notifies their initialization listeners. */
internal object BridgeManager {
    private val map: ConcurrentHashMap<Int, SparklingBridge> = ConcurrentHashMap()
    private val listeners = CopyOnWriteArrayList<BridgeInitListener>()

    fun attachInitListener(listener: BridgeInitListener) = listeners.add(listener)

    fun insert(
        webView: View,
        sparklingBridge: SparklingBridge,
    ) {
        map[webView.hashCode()] = sparklingBridge
        map.values.log()
        listeners.forEach {
            it.onInit(webView, sparklingBridge)
        }
    }

    fun getSparklingBridge(view: View): SparklingBridge? = map[view.hashCode()]

    fun remove(sparklingBridge: SparklingBridge) {
        val find = map.toList().find { it.second == sparklingBridge }
        if (find != null) {
            map.remove(find.first)
        }
        map.values.log()
    }

    fun insert(
        containerId: String,
        sparklingBridge: SparklingBridge,
    ) {}
}

/** Receives notifications when a bridge is initialized. */
interface BridgeInitListener {
    /** Called after the bridge is initialized for a view. */
    fun onInit(
        view: View,
        sparklingBridge: SparklingBridge,
    )

    /** Called after the bridge is initialized for a runtime container. */
    fun onInit(
        containerID: String,
        sparklingBridge: SparklingBridge,
    ) {}
}
