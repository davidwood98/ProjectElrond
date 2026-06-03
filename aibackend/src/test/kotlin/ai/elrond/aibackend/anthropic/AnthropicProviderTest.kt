package ai.elrond.aibackend.anthropic

import ai.elrond.aibackend.AIException
import ai.elrond.aibackend.AIInput
import ai.elrond.aibackend.AIRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicProviderTest {

    private val config = AnthropicConfig(apiKey = "test-key", model = "test-model")

    private fun successEngine(onRequest: (HttpRequestData) -> Unit = {}): MockEngine =
        MockEngine { request ->
            onRequest(request)
            respond(
                content = SUCCESS_RESPONSE,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }

    private fun requestBodyJson(request: HttpRequestData): JsonObject {
        val text = (request.body as TextContent).text
        return Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `sends required headers and endpoint`() = runTest {
        var captured: HttpRequestData? = null
        val provider = AnthropicProvider(config, successEngine { captured = it })

        provider.generate(AIRequest(AIInput.Text("hello"))).getOrThrow()

        val request = checkNotNull(captured)
        assertEquals("https://api.anthropic.com/v1/messages", request.url.toString())
        assertEquals("test-key", request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
    }

    @Test
    fun `text input produces text content block with configured model`() = runTest {
        var captured: HttpRequestData? = null
        val provider = AnthropicProvider(config, successEngine { captured = it })

        provider.generate(AIRequest(AIInput.Text("what is 2+2?"), maxTokens = 256)).getOrThrow()

        val body = requestBodyJson(checkNotNull(captured))
        assertEquals("test-model", body["model"]?.jsonPrimitive?.content)
        assertEquals(256, body["max_tokens"]?.jsonPrimitive?.content?.toInt())
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("what is 2+2?", content[0].jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `image input produces base64 image block plus optional text prompt`() = runTest {
        var captured: HttpRequestData? = null
        val provider = AnthropicProvider(config, successEngine { captured = it })

        provider.generate(
            AIRequest(
                AIInput.Image(
                    base64Data = "aGVsbG8=",
                    mediaType = "image/png",
                    prompt = "Answer the handwritten question",
                ),
            ),
        ).getOrThrow()

        val content = requestBodyJson(checkNotNull(captured))["messages"]!!
            .jsonArray[0].jsonObject["content"]!!.jsonArray
        val imageBlock = content[0].jsonObject
        assertEquals("image", imageBlock["type"]?.jsonPrimitive?.content)
        val source = imageBlock["source"]!!.jsonObject
        assertEquals("base64", source["type"]?.jsonPrimitive?.content)
        assertEquals("image/png", source["media_type"]?.jsonPrimitive?.content)
        assertEquals("aGVsbG8=", source["data"]?.jsonPrimitive?.content)
        assertEquals("text", content[1].jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `system prompt is sent as cacheable block`() = runTest {
        var captured: HttpRequestData? = null
        val provider = AnthropicProvider(config, successEngine { captured = it })

        provider.generate(
            AIRequest(AIInput.Text("hi"), systemPrompt = "You are a note-taking assistant."),
        ).getOrThrow()

        val system = requestBodyJson(checkNotNull(captured))["system"]!!.jsonArray[0].jsonObject
        assertEquals("You are a note-taking assistant.", system["text"]?.jsonPrimitive?.content)
        assertEquals(
            "ephemeral",
            system["cache_control"]!!.jsonObject["type"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `omitted system prompt sends no system field`() = runTest {
        var captured: HttpRequestData? = null
        val provider = AnthropicProvider(config, successEngine { captured = it })

        provider.generate(AIRequest(AIInput.Text("hi"))).getOrThrow()

        assertNull(requestBodyJson(checkNotNull(captured))["system"])
    }

    @Test
    fun `parses text response and usage`() = runTest {
        val provider = AnthropicProvider(config, successEngine())

        val response = provider.generate(AIRequest(AIInput.Text("hello"))).getOrThrow()

        assertEquals("4", response.text)
        assertEquals(10, response.inputTokens)
        assertEquals(5, response.outputTokens)
        assertEquals("end_turn", response.stopReason)
    }

    @Test
    fun `api error maps to AIException Api with message`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"type":"error","error":{"type":"invalid_request_error","message":"max_tokens required"}}""",
                status = HttpStatusCode.BadRequest,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val provider = AnthropicProvider(config, engine)

        val result = provider.generate(AIRequest(AIInput.Text("hello")))

        val error = result.exceptionOrNull() as AIException.Api
        assertEquals(400, error.statusCode)
        assertEquals("max_tokens required", error.message)
    }

    @Test
    fun `network failure maps to AIException Network`() = runTest {
        val engine = MockEngine { throw IOException("connection refused") }
        val provider = AnthropicProvider(config, engine)

        val result = provider.generate(AIRequest(AIInput.Text("hello")))

        assertTrue(result.exceptionOrNull() is AIException.Network)
    }

    @Test
    fun `unparseable success body maps to AIException Parse`() = runTest {
        val engine = MockEngine {
            respond(
                content = "not json",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val provider = AnthropicProvider(config, engine)

        val result = provider.generate(AIRequest(AIInput.Text("hello")))

        assertTrue(result.exceptionOrNull() is AIException.Parse)
    }

    companion object {
        private const val SUCCESS_RESPONSE = """
            {
              "id": "msg_test",
              "type": "message",
              "model": "test-model",
              "content": [{"type": "text", "text": "4"}],
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 10, "output_tokens": 5}
            }
        """
    }
}
