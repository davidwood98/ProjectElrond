package ai.elrond.aibackend.anthropic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire types for the Anthropic Messages API (POST /v1/messages).

@Serializable
internal data class MessagesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: List<SystemBlockDto>? = null,
    val messages: List<MessageDto>,
)

@Serializable
internal data class SystemBlockDto(
    val type: String = "text",
    val text: String,
    /** Prompt caching: the stable system prompt is marked cacheable. */
    @SerialName("cache_control") val cacheControl: CacheControlDto? = null,
)

@Serializable
internal data class CacheControlDto(val type: String = "ephemeral")

@Serializable
internal data class MessageDto(
    val role: String,
    val content: List<ContentBlockDto>,
)

@Serializable
internal data class ContentBlockDto(
    val type: String,
    val text: String? = null,
    val source: ImageSourceDto? = null,
) {
    companion object {
        fun text(text: String) = ContentBlockDto(type = "text", text = text)
        fun image(mediaType: String, base64Data: String) = ContentBlockDto(
            type = "image",
            source = ImageSourceDto(mediaType = mediaType, data = base64Data),
        )
    }
}

@Serializable
internal data class ImageSourceDto(
    val type: String = "base64",
    @SerialName("media_type") val mediaType: String,
    val data: String,
)

@Serializable
internal data class MessagesResponse(
    val id: String? = null,
    val model: String? = null,
    val content: List<ResponseContentDto> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
    val usage: UsageDto? = null,
)

@Serializable
internal data class ResponseContentDto(
    val type: String,
    val text: String? = null,
)

@Serializable
internal data class UsageDto(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
)

@Serializable
internal data class ErrorResponse(val error: ErrorDetailDto)

@Serializable
internal data class ErrorDetailDto(
    val type: String = "",
    val message: String = "",
)
