package us.wangxy.voicebook.server.model

import kotlinx.serialization.Serializable

/** 统一错误响应体：{ "error": "...", "message": "..." } */
@Serializable
data class ErrorResponse(
    val error: String,
    val message: String,
)
