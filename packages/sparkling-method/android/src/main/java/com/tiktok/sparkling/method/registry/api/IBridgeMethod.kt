// Copyright (c) 2022 TikTok Pte. Ltd.
// Licensed under the Apache License Version 2.0 that can be found in the
// LICENSE file in the root directory of this source tree.

package com.tiktok.sparkling.method.registry.api

import com.lynx.react.bridge.JavaOnlyMap
import com.lynx.react.bridge.ReadableMap
import com.tiktok.sparkling.method.protocol.entity.IPlatform
import com.tiktok.sparkling.method.protocol.entity.Platform
import com.tiktok.sparkling.method.protocol.interfaces.IBridgeMethodCallback
import com.tiktok.sparkling.method.registry.api.processor.LynxPlatformDataProcessor
import com.tiktok.sparkling.method.registry.api.processor.WebPlatformDataProcessor
import com.tiktok.sparkling.method.registry.api.util.printStackString
import com.tiktok.sparkling.method.registry.core.BridgePlatformType
import com.tiktok.sparkling.method.registry.core.IBridgeContext
import com.tiktok.sparkling.method.registry.core.IBridgePlatformType
import com.tiktok.sparkling.method.registry.core.IDLBridgeMethod
import com.tiktok.sparkling.method.registry.core.exception.IllegalInputParamException
import com.tiktok.sparkling.method.registry.core.exception.IllegalOperationException
import com.tiktok.sparkling.method.registry.core.exception.IllegalOutputParamException
import com.tiktok.sparkling.method.registry.core.interfaces.IPlatformDataProcessor
import org.json.JSONObject

/**
 * Runs a Web call: the carrier is a [JSONObject] and [WebPlatformDataProcessor] converts it.
 */
class JsonProcessor @JvmOverloads constructor(
    bridge: IDLBridgeMethod,
    data: JSONObject,
    val platForm: IPlatform = Platform.Web,
) : BaseProcessor<JSONObject>(bridge, data) {
    var context: IBridgeContext? = null

    override fun getPlatformType() = BridgePlatformType.WEB

    override val processor = WebPlatformDataProcessor().apply {
        this@apply.context = this@JsonProcessor.context
    }

    override fun onError(code: Int, message: String): JSONObject {
        return JSONObject().apply {
            put("code", code)
            put("msg", message)
        }
    }
}

/**
 * Runs a Lynx call: the carrier is a [ReadableMap] and [LynxPlatformDataProcessor] converts it.
 */
class ReadableMapProcessor(
    bridge: IDLBridgeMethod,
    data: ReadableMap,
) : BaseProcessor<ReadableMap>(bridge, data) {
    var context: IBridgeContext? = null

    override fun getPlatformType() = BridgePlatformType.LYNX

    override val processor = LynxPlatformDataProcessor().apply {
        this@apply.context = this@ReadableMapProcessor.context
    }

    override fun onError(code: Int, message: String): ReadableMap {
        return JavaOnlyMap.from(hashMapOf<String, Any>().apply {
            put("code", code)
            put("msg", message)
        })
    }
}

/**
 * Runs one call of one method: binds the carrier the container handed over into a map, gives that to
 * the method, and renders whatever comes back — a result or a thrown failure — in the carrier the
 * container expects.
 *
 * [Input] is that carrier, and a subclass supplies the three things that depend on it: which
 * platform it belongs to ([getPlatformType]), what converts it ([processor]), and how an error reads
 * in it ([onError]). Everything else, including which failures become which code, is fixed here so
 * that two containers cannot disagree about it.
 */
abstract class BaseProcessor<Input>(
    val bridge: IDLBridgeMethod,
    val data: Input,
) {

    abstract fun getPlatformType(): IBridgePlatformType

    abstract val processor: IPlatformDataProcessor<Input>

    fun handle(callback: IBridgeMethodCallback) {
        try {
            val map = processor.transformPlatformDataToMap(data, bridge::class.java)
            if (map == null) {
                callback.onBridgeResult(
                    onError(
                        IDLBridgeMethod.ANNOTATION_ERROR,
                        "processor.transformPlatformDataToMap failed",
                    )!!,
                )
            } else {
                bridge.realHandle(map, object : IDLBridgeMethod.Callback {
                    override fun invoke(data: Map<String, Any?>) {
                        val code = (data[IDLBridgeMethod.PARAM_CODE] as? Int) ?: IDLBridgeMethod.SUCCESS
                        if (code != IDLBridgeMethod.SUCCESS) {
                            callback.onBridgeResult(processor.transformMapToPlatformData(data, bridge::class.java)!!)
                        } else {
                            callback.onBridgeResult(processor.transformMapToPlatformData(data, bridge::class.java)!!)
                        }
                    }
                }, getPlatformType())
            }
        } catch (e: IllegalInputParamException) {
            callback.onBridgeResult(onError(IDLBridgeMethod.INVALID_PARAM, e.toString())!!)
        } catch (e: IllegalOutputParamException) {
            callback.onBridgeResult(onError(IDLBridgeMethod.INVALID_RESULT, e.toString())!!)
        } catch (e: IllegalOperationException) {
            callback.onBridgeResult(onError(IDLBridgeMethod.ILLEGAL_OPERATION_ERROR, e.toString())!!)
        } catch (e: Throwable) {
            callback.onBridgeResult(onError(IDLBridgeMethod.UNKNOWN_ERROR, e.printStackString())!!)
        }
    }

    abstract fun onError(code: Int, message: String): Input
}
