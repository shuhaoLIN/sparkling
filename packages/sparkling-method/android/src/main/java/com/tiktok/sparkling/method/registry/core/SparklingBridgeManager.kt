// Copyright (c) 2022 TikTok Pte. Ltd.
// Licensed under the Apache License Version 2.0 that can be found in the
// LICENSE file in the root directory of this source tree.

package com.tiktok.sparkling.method.registry.core

/**
 * The process-wide registration entry: where a method registered without a container goes, and what
 * a container falls back to when it has no local registration of its own.
 *
 * The registrations live in a [LocalBridge], not here. A host whose method classes need their own
 * naming or their own platform keys installs its own [LocalBridge] subclass through [installHolder],
 * so that lookups arriving here read the table the host's own entry registers into. A host that
 * needs neither gets a plain [LocalBridge] and does not have to call anything.
 */
object SparklingBridgeManager {

    const val DEFAULT_NAMESPACE = "DEFAULT"

    @Volatile
    private var holder: LocalBridge? = null

    /** Whether [holder] came from [installHolder] rather than being the fallback created here. */
    @Volatile
    private var holderInstalled: Boolean = false

    /**
     * Whether a registration has landed in the fallback holder.
     *
     * Nothing outside this object ever holds the fallback, so registration methods on this object
     * are the only way anything gets into it, which is what makes this answer exact.
     */
    @Volatile
    private var fallbackHasRegistrations: Boolean = false

    /** The installed holder, or a plain [LocalBridge] created here for a host that installs none. */
    private val localBridge: LocalBridge
        get() = holder ?: synchronized(this) {
            holder ?: LocalBridge().also { holder = it }
        }

    /**
     * Installs the holder that every registration and lookup made through this object goes to, and
     * returns whether [bridge] became that holder.
     *
     * A host can still install after a lookup has created the fallback: a fallback nobody registered
     * into holds nothing the host's own holder would be missing. It can no longer install once
     * something has registered through the fallback, because those registrations would be stranded
     * in a table nobody reads afterwards.
     */
    fun installHolder(bridge: LocalBridge): Boolean = synchronized(this) {
        if (holder != null && (holderInstalled || fallbackHasRegistrations)) {
            false
        } else {
            holder = bridge
            holderInstalled = true
            true
        }
    }

    /** Registers a prebuilt [IDLMethodRegistry] in the process-wide holder. */
    fun registerRegistry(
        registry: IDLMethodRegistry,
    ) {
        registrationHolder().registerRegistry(registry)
    }

    /** Finds a globally registered method class for [platformType] and [name]. */
    @JvmStatic
    @JvmOverloads
    fun findIDLMethodClass(
        platformType: IBridgePlatformType,
        name: String,
        namespace: String = DEFAULT_NAMESPACE,
    ): Class<out IDLBridgeMethod>? = localBridge.findIDLMethodClass(platformType, name, namespace)

    /** Finds a globally registered method factory for [platformType] and [name]. */
    @JvmStatic
    @JvmOverloads
    fun findIDLMethodProvider(
        platformType: IBridgePlatformType,
        name: String,
        namespace: String = DEFAULT_NAMESPACE,
    ): IDLMethodProvider? = localBridge.findIDLMethodProvider(platformType, name, namespace)

    /** Registers a method class globally under [scope] and [namespace]. */
    @JvmStatic
    @JvmOverloads
    fun registerIDLMethod(
        clazz: Class<out IDLBridgeMethod>?,
        scope: IBridgePlatformType = BridgePlatformType.ALL,
        namespace: String = DEFAULT_NAMESPACE,
    ) {
        registrationHolder().registerIDLMethod(clazz, scope, namespace)
    }

    /** Registers a method provider globally under [name], [scope], and [namespace]. */
    @JvmStatic
    @JvmOverloads
    fun registerIDLMethod(
        name: String,
        scope: IBridgePlatformType = BridgePlatformType.ALL,
        namespace: String = DEFAULT_NAMESPACE,
        factory: IDLMethodProvider,
    ) {
        registrationHolder().registerIDLMethod(name, factory, scope, namespace)
    }

    /** Registers a Kotlin factory lambda globally under [name], [scope], and [namespace]. */
    fun registerIDLMethod(
        name: String,
        scope: IBridgePlatformType = BridgePlatformType.ALL,
        namespace: String = DEFAULT_NAMESPACE,
        clazz: Class<out IDLBridgeMethod>? = null,
        factory: () -> IDLBridgeMethod,
    ) {
        registrationHolder().registerIDLMethod(name, IDLMethodProvider { factory() }, scope, namespace, clazz)
    }

    /** Returns all globally registered method classes for [platformType] and [namespace]. */
    @JvmStatic
    @JvmOverloads
    fun getIDLMethodList(
        platformType: IBridgePlatformType,
        namespace: String = DEFAULT_NAMESPACE,
    ): Map<String, Class<out IDLBridgeMethod>>? = localBridge.getIDLMethodList(platformType, namespace)

    /**
     * The holder a registration goes to, pinning the fallback against replacement on the way.
     *
     * Only the first registration pays for the lock. After it, either a host is installed or the
     * fallback is already pinned, and both of those are readable without one.
     */
    private fun registrationHolder(): LocalBridge {
        if (holderInstalled || fallbackHasRegistrations) {
            return localBridge
        }

        return synchronized(this) {
            val bridge = localBridge
            if (!holderInstalled) {
                fallbackHasRegistrations = true
            }
            bridge
        }
    }
}
