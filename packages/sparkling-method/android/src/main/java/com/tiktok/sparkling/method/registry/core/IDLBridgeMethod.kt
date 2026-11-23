// Copyright (c) 2022 TikTok Pte. Ltd.
// Licensed under the Apache License Version 2.0 that can be found in the
// LICENSE file in the root directory of this source tree.

package com.tiktok.sparkling.method.registry.core

import com.tiktok.sparkling.method.registry.core.model.context.ContextProviderFactory
import com.tiktok.sparkling.method.registry.core.model.idl.IDLMethodBaseParamModel
import com.tiktok.sparkling.method.registry.core.model.idl.IDLMethodBaseResultModel

/**
 * Base contract of a bridge method invoked by the runtime: its name, parameter/result models,
 * execution entry point, and container context injection.
 */
interface IDLBridgeMethod {

    companion object {
        const val KEY_NOT_FOUND = 2
        const val SUCCESS = 1
        const val FAIL = 0 //error code for JSB method

        //error code for sdk
        const val UNAUTHORIZED = -1 // UnauthorizedInvocation, the current page URL isn't authorized to call this method, due to legacy error, sdk use BridgeConstants's ERROR_BRIDGE_NO_AUTHORITY
        const val UNREGISTERED = -2 // UnregisteredMethod, the called method isn't registered, due to legacy error, sdk use BridgeConstants's BRIDGE_NOT_FOUND
        const val INVALID_PARAM = -3 // InvalidParameter, the parameter passed by FE is invalid
        const val INVALID_RESULT = -5 // InvalidResult, the result passed by the method implementor is invalid
        const val BRIDGE_CALL_BE_INTERCEPTED = -10 // bridge call be intercepted
        const val BRIDGE_CALL_BE_INTERCEPTED_MSG = "intercepted by lifeClient"
        const val BRIDGE_HAS_BEEN_RELEASED = -13 //bridge has been released
        const val BRIDGE_NOT_INITIALIZED_YET = -14

        const val UNKNOWN_ERROR = -1000
        const val ANNOTATION_ERROR = -2000 // when annotation can't get
        const val ILLEGAL_OPERATION_ERROR = -2001 // when call a illegal method of proxy

        //other error codes
        const val UNAUTHORIZED_ACCESS = -6 // UnauthorizedAccess, unauthorized to access some resources, like camera, microphone
        const val CANCELLED = -7 // OperationCancelled, user cancelled certain operation
        const val OPERATION_TIMEOUT = -8 // OperationTimeout
        const val NOT_FOUND = -9 // NOT_FOUND

        const val NETWORK_UNREACHABLE = -1001
        const val NETWORK_TIMEOUT = -1002
        const val MALFORMEDRESPONSE_ERROR = -1003

        const val PARAM_CODE = "code"
        const val PARAM_MSG = "msg"
        const val PARAM_DATA = "data"
        const val ORIGINAL_RESULT = "originalResult"
    }

    interface Callback {
        // why do we need hashmap here
        fun invoke(data: Map<String, Any?>)
    }

    interface JSEventDelegate {
        fun sendJSEvent(eventName: String, params: Map<String, Any?>?)
    }

    val name: String

    fun provideParamModel(): Class<out IDLMethodBaseParamModel>? {
        return null
    }

    fun provideResultModel(): Class<out IDLMethodBaseResultModel>? {
        return null
    }

    /**
     * this just for old jsb change to idl with difference format result.
     * if you set useOriginalResult is true,
     * you can add your result as a Map<String, any> to the resultModel with key: IDLBridgeMethod.ORIGINAL_RESULT,
     * we will get it by the key : IDLBridgeMethod.ORIGINAL_RESULT,
     * or you can also pass the original map<String, any> to callbak, we will pass it to fe directly.
     */
    val useOriginalResult: Boolean
        get() = false

    /** Whether result fields should be merged directly into the top-level response map instead of nested under `data`. */
    fun shouldUseOriginalData(bridgeContext: IBridgeContext?): Boolean = false

    fun realHandle(params: Map<String, Any?>, callback: Callback, type: IBridgePlatformType)

    @Deprecated("will delete in future, use setBridgeContext instead")
    fun setProviderFactory(contextProviderFactory: ContextProviderFactory?)

    /** Supplies the container this method runs in. */
    fun setBridgeContext(bridgeContext: IBridgeContext)

    fun release() {}
}
