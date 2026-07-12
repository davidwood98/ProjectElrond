package ai.elrond.aibackend.anthropic

import ai.elrond.aibackend.AIException
import ai.elrond.aibackend.AIInput
import ai.elrond.aibackend.AIProvider
import ai.elrond.aibackend.AIRequest
import ai.elrond.aibackend.AIResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * [AIProvider] backed by the Anthropic Messages API.
 *
 * Raw HTTP via Ktor (rather than the official Anthropic Java SDK) is deliberate:
 * this module must stay free of JVM-only and Android-only dependencies so it can be
 * converted to Kotlin Multiplatform for the iOS port. Ktor + kotlinx-serialization
 * are both multiplatform.
 *
 * @param engine injectable for tests (ktor-client-mock) — never call the real API in tests.
 */
class AnthropicProvider(
    private val config: AnthropicConfig,
    engine: HttpClientEngine = CIO.create(),
) : AIProvider {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val client = HttpClient(engine) {
        install(ContentNegotiation) { json(this@AnthropicProvider.json) }
        install(HttpRequestRetry) {
            maxRetries = 2
            retryIf { _, response ->
                response.status.value == 429 || response.status.value >= 500
            }
            exponentialDelay()
        }
        expectSuccess = false
    }

    override suspend fun generate(request: AIRequest): Result<AIResponse> {
        val body = MessagesRequest(
            model = config.model,
            maxTokens = request.maxTokens,
            system = request.systemPrompt?.let { prompt ->
                // Stable system prompt first, marked cacheable (prefix caching).
                listOf(SystemBlockDto(text = prompt, cacheControl = CacheControlDto()))
            },
            messages = listOf(
                MessageDto(role = "user", content = request.input.toContentBlocks()),
            ),
        )
        return try {
            val response = client.post("${config.baseUrl}/v1/messages") {
                header("x-api-key", config.apiKey)
                header("anthropic-version", AnthropicConfig.ANTHROPIC_VERSION)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val responseText = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(parseSuccess(responseText))
            } else {
                Result.failure(parseError(response.status.value, responseText))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AIException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(AIException.Network(e))
        }
    }

    private fun parseSuccess(body: String): AIResponse {
        val parsed = try {
            json.decodeFromString<MessagesResponse>(body)
        } catch (e: Exception) {
            throw AIException.Parse("Failed to parse Messages API response", e)
        }
        val text = parsed.content
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString("")
        return AIResponse(
            text = text,
            inputTokens = parsed.usage?.inputTokens ?: 0,
            outputTokens = parsed.usage?.outputTokens ?: 0,
            stopReason = parsed.stopReason,
        )
    }

    private fun parseError(statusCode: Int, body: String): AIException {
        val detail = try {
            json.decodeFromString<ErrorResponse>(body).error
        } catch (_: Exception) {
            null
        }
        return AIException.Api(
            statusCode = statusCode,
            message = detail?.message?.ifBlank { null } ?: "HTTP $statusCode",
            errorType = detail?.type?.ifBlank { null },
        )
    }

    fun close() {
        client.close()
    }
}

private fun AIInput.toContentBlocks(): List<ContentBlockDto> = when (this) {
    is AIInput.Text -> listOf(ContentBlockDto.text(text))
    is AIInput.Image -> buildList {
        add(ContentBlockDto.image(mediaType = mediaType, base64Data = base64Data))
        prompt?.let { add(ContentBlockDto.text(it)) }
    }
}
