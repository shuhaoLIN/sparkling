// Copyright (c) 2022 TikTok Pte. Ltd.
// Licensed under the Apache License Version 2.0 that can be found in the
// LICENSE file in the root directory of this source tree.

package com.tiktok.sparkling.method.registry.core

import java.util.concurrent.ConcurrentHashMap

/**
 * Registers method classes and providers by platform and name.
 *
 * A registry is either global or bound to one container: [isLocalBridgeRegistry] picks which, and
 * [bridgeContext] carries the container it belongs to. Hosts can customize name resolution
 * ([resolveMethodName]), platform key normalization ([normalizePlatform]), `NONE` handling
 * ([isNonePlatform]), scope expansion ([expandScope]), and local cache creation
 * ([createLocalRegistryCache]) while sharing registration and lookup behavior.
 */
open class IDLMethodRegistry(
    protected val isLocalBridgeRegistry: Boolean = false,
    protected val bridgeContext: IBridgeContext? = null,
    private val inferMethodNameFromInstance: Boolean = isLocalBridgeRegistry,
) {
    companion object {
        /** Copies the namespace and registrations into a new global registry. */
        @JvmStatic
        fun copyWith(bridgeRegistry: IDLMethodRegistry): IDLMethodRegistry =
            IDLMethodRegistry().apply {
                copyFrom(bridgeRegistry)
            }
    }

    var namespace: String = "DEFAULT"

    private val methodMap: ConcurrentHashMap<IBridgePlatformType, ConcurrentHashMap<String, IDLMethodProvider>> =
        ConcurrentHashMap()

    /**
     * platform -> method name -> method class.
     *
     * Protected so host subclasses can expose or inspect the backing table directly.
     */
    protected val methodClassMap: ConcurrentHashMap<IBridgePlatformType, ConcurrentHashMap<String, Class<out IDLBridgeMethod>>> =
        ConcurrentHashMap()

    protected var methodRegistryCache: IDLMethodRegistryCache? = null
        private set

    init {
        if (isLocalBridgeRegistry) {
            methodRegistryCache =
                createLocalRegistryCache().apply {
                    bridgeContext?.containerID?.let {
                        IDLMethodRegistryCacheManager.registerIDLMethodRegistryCache(
                            it,
                            this,
                        )
                    }
                }
        }
    }

    /** Registers [clazz] under every platform [scope] covers after resolving its method name. */
    fun registerMethod(
        clazz: Class<out IDLBridgeMethod>,
        scope: IBridgePlatformType = BridgePlatformType.ALL,
    ) {
        val name = resolveMethodName(clazz)
        if (!name.isNullOrEmpty()) {
            val provider = methodProviderFor(clazz)
            expandScope(scope).forEach { putMethod(name, provider, it, clazz) }
        }
    }

    /** Registers a factory, optionally associating a class for metadata and class lookups. */
    fun registerMethodProvider(
        name: String,
        methodProvider: IDLMethodProvider,
        scope: IBridgePlatformType = BridgePlatformType.ALL,
        clazz: Class<out IDLBridgeMethod>? = null,
    ) {
        if (name.isEmpty()) return
        clazz?.let {
            if (!isLocalBridgeRegistry) {
                IDLMethodRegistryCacheManager.provideIDLMethodRegistryCache(null)?.cacheMethodClass(it)
            } else {
                methodRegistryCache?.cacheMethodClass(it)
            }
        }
        expandScope(scope).forEach { putMethod(name, methodProvider, it, clazz) }
    }

    /** Returns the registered class, or null for an unknown method or the NONE platform. */
    fun findMethodClass(
        platformType: IBridgePlatformType,
        name: String,
    ): Class<out IDLBridgeMethod>? {
        if (isNonePlatform(platformType)) {
            return null
        }
        return methodClassMap[normalizePlatform(platformType)]?.get(name)
    }

    /** Returns the registered factory, or null for an unknown method or the NONE platform. */
    open fun findMethodProvider(
        platformType: IBridgePlatformType,
        name: String,
    ): IDLMethodProvider? {
        if (isNonePlatform(platformType)) {
            return null
        }
        return methodMap[normalizePlatform(platformType)]?.get(name)
    }

    /** Whether either a provider or a class is registered for this name and platform. */
    fun isMethodExists(
        name: String,
        platformType: IBridgePlatformType = BridgePlatformType.ALL,
    ): Boolean = findMethodProvider(platformType, name) != null || findMethodClass(platformType, name) != null

    /** Returns the live class table for a platform, excluding NONE. */
    fun getMethodList(
        platformType: IBridgePlatformType,
    ): MutableMap<String, Class<out IDLBridgeMethod>>? {
        if (isNonePlatform(platformType)) {
            return null
        }
        return methodClassMap[normalizePlatform(platformType)]
    }

    /**
     * Copies the namespace and replaces both tables on every platform present in [other].
     * Platforms absent from the source are retained. The new maps share their provider objects
     * and class values with the source.
     */
    open fun copyFrom(other: IDLMethodRegistry) {
        namespace = other.namespace
        (other.methodMap.keys + other.methodClassMap.keys).forEach { platform ->
            val providers = other.methodMap[platform]
            val classes = other.methodClassMap[platform]
            if (providers != null) {
                methodMap[platform] = ConcurrentHashMap(providers)
            } else {
                methodMap.remove(platform)
            }
            if (classes != null) {
                methodClassMap[platform] = ConcurrentHashMap(classes)
            } else {
                methodClassMap.remove(platform)
            }
        }
    }

    /** Stores one already-named method entry under one platform. */
    protected open fun putMethod(
        name: String,
        methodProvider: IDLMethodProvider,
        scope: IBridgePlatformType,
        clazz: Class<out IDLBridgeMethod>?,
    ) {
        (methodMap[scope] ?: ConcurrentHashMap()).also {
            it[name] = methodProvider
            methodMap[scope] = it
        }
        if (clazz != null) {
            (methodClassMap[scope] ?: ConcurrentHashMap()).also {
                it[name] = clazz
                methodClassMap[scope] = it
            }
        }
    }

    /** Creates the container-local annotation cache when [isLocalBridgeRegistry] is true. */
    protected open fun createLocalRegistryCache(): IDLMethodRegistryCache = IDLMethodRegistryCache()

    /** Resolves cached annotations first, then uses an instance only when configured to do so. */
    protected open fun resolveMethodName(clazz: Class<out IDLBridgeMethod>): String? {
        val cache =
            if (!isLocalBridgeRegistry) {
                IDLMethodRegistryCacheManager.provideIDLMethodRegistryCache(null)
            } else {
                methodRegistryCache
            }
        val cachedName = cache?.find(clazz)
        if (!cachedName.isNullOrEmpty()) {
            return cachedName
        }
        if (!inferMethodNameFromInstance) {
            return null
        }
        return runCatching { methodProviderFor(clazz).provideMethod().name }.getOrNull()
    }

    /** The key [scope] is stored and looked up under. */
    protected open fun normalizePlatform(scope: IBridgePlatformType): IBridgePlatformType = scope

    /** Whether [platformType] represents the NONE platform, which never resolves any method. */
    protected open fun isNonePlatform(platformType: IBridgePlatformType): Boolean =
        normalizePlatform(platformType) == BridgePlatformType.NONE

    /** The platforms one registration covers. */
    protected open fun expandScope(scope: IBridgePlatformType): List<IBridgePlatformType> {
        val platform = normalizePlatform(scope)
        return if (platform == BridgePlatformType.ALL) {
            listOf(
                BridgePlatformType.ALL,
                BridgePlatformType.WEB,
                BridgePlatformType.LYNX,
            )
        } else {
            listOf(platform)
        }
    }

    @Suppress("DEPRECATION")
    private fun methodProviderFor(clazz: Class<out IDLBridgeMethod>): IDLMethodProvider =
        IDLMethodProvider { clazz.newInstance() }
}
