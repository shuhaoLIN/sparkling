// Copyright (c) 2022 TikTok Pte. Ltd.
// Licensed under the Apache License Version 2.0 that can be found in the
// LICENSE file in the root directory of this source tree.

package com.tiktok.sparkling.method.registry.core

import android.app.Activity
import android.content.Context
import android.view.View
import com.tiktok.sparkling.method.registry.api.SparklingBridge
import com.tiktok.sparkling.method.registry.core.model.context.ContextHolder
import com.tiktok.sparkling.method.registry.core.model.context.IContextProvider
import com.tiktok.sparkling.method.registry.core.model.context.WeakContextHolder
import com.tiktok.sparkling.method.registry.core.utils.IDLMethodHelper
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * Container context a registered method can read while handling a call: the bridge instance, the
 * view, and host objects bound onto the container.
 */
interface IBridgeContext {
    val bridge: SparklingBridge?
    val view: View?
    val ownerActivity: Activity?
    val containerID: String?
    val context: Context?

    /** Sends an event to the JS runtime attached to this container. */
    fun sendEvent(
        name: String,
        obj: JSONObject,
    )

    /** Looks up a registered host object by its class. */
    fun <T> getObject(clazz: Class<T>): T?
}

/** Delegate that dispatches native-to-JS events for a container context. */
interface JSEventDelegate {
    /** Sends a JS event with optional JSON payload. */
    fun sendJSEvent(
        eventName: String,
        params: JSONObject?,
    )
}

/** Default [IBridgeContext] implementation holding weak container references and host providers. */
open class BridgeContextWrapper : IBridgeContext {
    private val providers: MutableMap<Class<*>, IContextProvider<*>> = mutableMapOf()
    private var _view: WeakReference<View?>? = null
    private var _activity: WeakReference<Activity?>? = null
    private var _containerID: String? = null
    private var _jsEvent: JSEventDelegate? = null
    private var __bridge: WeakReference<SparklingBridge>? = null

    /**
     * register object to JSBSDKContext, you can use getSDKContext().getObject(clazz) in JSB method to get it.
     * there is two-ways to register object to JSBSDKContext: registerObject & registerWeakObject
     * 1. registerObject register an object to JSBSDKContext. It uses strong references to ensure that objects exist when used.
     * 2. registerWeakObject register a weakObject to JSBSDKContext.
     *    It uses weak references to store the object, so when the object is released, maybe you will get a null when you use it.
     * 3. registerWeakObject has higher priority than registerObject,
     *    so if you register the same class with object and weakObject, you only can get the weakObject.
     */
    fun <T> registerWeakObject(
        clazz: Class<T>,
        t: T?,
    ) {
        providers[clazz] = WeakContextHolder(t)
    }

    /**
     * register object to JSBSDKContext, you can use getSDKContext().getObject(clazz) in JSB method to get it.
     * there is two-ways to register object to JSBSDKContext: registerObject & registerWeakObject
     * 1. registerObject register an object to JSBSDKContext. It uses strong references to ensure that objects exist when used.
     * 2. registerWeakObject register a weakObject to JSBSDKContext.
     *    It uses weak references to store the object, so when the object is released, maybe you will get a null when you use it.
     * 3. registerWeakObject has higher priority than registerObject,
     *    so if you register the same class with object and weakObject, you only can get the weakObject.
     */
    fun <T> registerObject(
        clazz: Class<T>,
        t: T?,
    ) {
        providers[clazz] = ContextHolder(t)
    }

    /**
     * register object to JSBSDKContext, you can use getSDKContext().getObject(clazz) in JSB method to get it.
     * there is two-ways to register object to JSBSDKContext: registerObject & registerWeakObject
     * 1. registerObject register an object to JSBSDKContext. It uses strong references to ensure that objects exist when used.
     * 2. registerWeakObject register a weakObject to JSBSDKContext.
     *    It uses weak references to store the object, so when the object is released, maybe you will get a null when you use it.
     * 3. registerWeakObject has higher priority than registerObject,
     *    so if you register the same class with object and weakObject, you only can get the weakObject.
     */
    fun registerWeakObjects(map: Map<Class<*>, WeakContextHolder<*>>) {
        providers.putAll(map)
    }

    /**
     * register object to JSBSDKContext, you can use getSDKContext().getObject(clazz) in JSB method to get it.
     * there is two-ways to register object to JSBSDKContext: registerObject & registerWeakObject
     * 1. registerObject register an object to JSBSDKContext. It uses strong references to ensure that objects exist when used.
     * 2. registerWeakObject register a weakObject to JSBSDKContext.
     *    It uses weak references to store the object, so when the object is released, maybe you will get a null when you use it.
     * 3. registerWeakObject has higher priority than registerObject,
     *    so if you register the same class with object and weakObject, you only can get the weakObject.
     */
    fun registerObjects(map: Map<Class<*>, ContextHolder<*>>) {
        providers.putAll(map)
    }

    /** Sets the JS event delegate once if not already set. */
    fun setJSEventDelegate(delegate: JSEventDelegate) {
        if (_jsEvent == null) {
            _jsEvent = delegate
        }
    }

    /** Binds the container view weakly if not already set. */
    fun setView(view: View?) {
        if (_view == null) {
            _view = WeakReference(view)
        }
    }

    /** Sets the container identifier. */
    fun setContainerID(containerID: String?) {
        _containerID = containerID
    }

    /** Binds the owner activity weakly if none is currently alive. */
    fun setOwnerActivity(activity: Activity?) {
        if (_activity?.get() == null) {
            _activity = WeakReference(activity)
        }
    }

    /** Binds the [SparklingBridge] weakly if none is currently alive. */
    fun setBridge(bridge: SparklingBridge) {
        if (__bridge?.get() == null) {
            __bridge = WeakReference(bridge)
        }
    }

    override val bridge: SparklingBridge?
        get() = __bridge?.get()

    override val view: View?
        get() = _view?.get() ?: getObject(View::class.java)
    override val ownerActivity: Activity?
        get() = WeakReference(IDLMethodHelper.getActivity(context)).get()
    override val context: Context?
        get() = _view?.get()?.context ?: getObject(Context::class.java)

    /** Dispatches an event through the configured [JSEventDelegate]. */
    override fun sendEvent(
        name: String,
        obj: JSONObject,
    ) {
        _jsEvent?.sendJSEvent(name, obj)
    }

    override val containerID: String?
        get() = _containerID

    /** Looks up a registered host object by its class. */
    override fun <T> getObject(clazz: Class<T>): T? = providers[clazz]?.provideInstance() as T?
}
