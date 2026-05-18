package com.devbrowser.devtools.cdp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Chrome DevTools Protocol message envelopes. Method-specific params are kept
 * as JsonObject so each panel decodes its own slice without exhaustive
 * codegen of the whole protocol (~thousands of types).
 */
@Serializable
data class CdpRequest(
    val id: Long,
    val method: String,
    val params: JsonObject? = null,
    val sessionId: String? = null,
)

@Serializable
data class CdpResponse(
    val id: Long? = null,
    val method: String? = null,
    val params: JsonObject? = null,
    val result: JsonObject? = null,
    val error: CdpError? = null,
    val sessionId: String? = null,
)

@Serializable
data class CdpError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

@Serializable
data class CdpTarget(
    val id: String,
    val type: String,
    val title: String? = null,
    val url: String? = null,
    val webSocketDebuggerUrl: String? = null,
    val devtoolsFrontendUrl: String? = null,
)
