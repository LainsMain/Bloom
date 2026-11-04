package be.lain.bloom.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

class AiException(message: String) : IOException(message)

data class ToolCall(val id: String, val name: String, val arguments: String)

data class AssistantTurn(
    val content: String,
    val reasoning: String,
    val toolCalls: List<ToolCall>,
    val finishReason: String?,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
)

data class SearchHit(val title: String, val url: String, val summary: String, val date: String?)

/** Accumulates streamed chunks (content, reasoning, tool-call fragments) into a complete turn. */
class StreamAccumulator {
    private class Partial(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

    val content = StringBuilder()
    val reasoning = StringBuilder()
    private val calls = sortedMapOf<Int, Partial>()
    var finish: String? = null
    var promptTokens = 0
    var completionTokens = 0

    /** Returns true if visible content changed. */
    fun accept(chunk: JsonObject): Boolean {
        chunk["usage"]?.let { u ->
            if (u is JsonObject) {
                promptTokens = u["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: promptTokens
                completionTokens = u["completion_tokens"]?.jsonPrimitive?.intOrNull ?: completionTokens
            }
        }
        val choice = (chunk["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return false
        choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { finish = it }
        val delta = choice["delta"] as? JsonObject ?: return false
        var changed = false
        delta["reasoning_content"]?.jsonPrimitive?.contentOrNull?.let { reasoning.append(it) }
        delta["content"]?.jsonPrimitive?.contentOrNull?.let { content.append(it); changed = true }
        (delta["tool_calls"] as? JsonArray)?.forEach { el ->
            val o = el.jsonObject
            val idx = o["index"]?.jsonPrimitive?.intOrNull ?: 0
            val p = calls.getOrPut(idx) { Partial() }
            o["id"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) p.id = it }
            (o["function"] as? JsonObject)?.let { f ->
                f["name"]?.jsonPrimitive?.contentOrNull?.let { p.name += it }
                f["arguments"]?.jsonPrimitive?.contentOrNull?.let { p.args.append(it) }
            }
        }
        return changed
    }

    fun turn() = AssistantTurn(
        content = content.toString(),
        reasoning = reasoning.toString(),
        toolCalls = calls.values.filter { it.name.isNotEmpty() }.mapIndexed { i, p -> ToolCall(p.id.ifEmpty { "call_$i" }, p.name, p.args.toString().ifBlank { "{}" }) },
        finishReason = finish,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
    )
}

/** Minimal DeepSeek client: streaming chat completions with tools, plus the Search/Fetch API. */
@Singleton
class DeepSeek @Inject constructor(
    private val http: OkHttpClient,
    private val secrets: SecretStore,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    suspend fun hasKey(): Boolean = secrets.deepSeekKey() != null

    suspend fun chat(
        messages: List<JsonObject>,
        tools: JsonArray? = null,
        effort: String = "high",
        thinking: Boolean = true,
        jsonOutput: Boolean = false,
        onText: (String) -> Unit = {},
    ): AssistantTurn = withContext(Dispatchers.IO) {
        val key = secrets.deepSeekKey() ?: throw AiException("Add your DeepSeek API key in Settings to use the coach.")
        val body = buildJsonObject {
            put("model", MODEL)
            put("messages", JsonArray(messages))
            put("stream", true)
            put("stream_options", buildJsonObject { put("include_usage", true) })
            put("thinking", buildJsonObject { put("type", if (thinking) "enabled" else "disabled") })
            if (thinking) put("reasoning_effort", effort)
            if (tools != null && tools.isNotEmpty()) put("tools", tools)
            if (jsonOutput) put("response_format", buildJsonObject { put("type", "json_object") })
        }
        val req = Request.Builder()
            .url("$BASE/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw AiException(errorMessage(resp.code, resp.body?.string()))
            val source = resp.body?.source() ?: throw AiException("Empty response from DeepSeek")
            val acc = StreamAccumulator()
            while (true) {
                coroutineContext.ensureActive()
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val chunk = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                if (acc.accept(chunk)) onText(acc.content.toString())
            }
            acc.turn()
        }
    }

    suspend fun search(query: String, count: Int = 8): List<SearchHit> = withContext(Dispatchers.IO) {
        val key = secrets.deepSeekKey() ?: throw AiException("No API key")
        val body = buildJsonObject {
            put("query", query.take(128))
            put("count", count)
            put("need_content", false)
        }
        val req = Request.Builder().url("$BASE/v1/tools/search").header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(jsonType)).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw AiException(errorMessage(r.code, text))
            val results = json.parseToJsonElement(text).jsonObject["results"]?.jsonArray ?: return@use emptyList()
            results.map {
                val o = it.jsonObject
                SearchHit(
                    title = o.str("title"), url = o.str("url"),
                    summary = (o.str("summary").ifEmpty { o.str("snippet") }).take(600), date = o["date"]?.jsonPrimitive?.contentOrNull,
                )
            }
        }
    }

    suspend fun fetch(url: String, maxChars: Int = 12_000): Pair<String, String> = withContext(Dispatchers.IO) {
        val key = secrets.deepSeekKey() ?: throw AiException("No API key")
        val body = buildJsonObject { put("url", url) }
        val req = Request.Builder().url("$BASE/v1/tools/fetch").header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(jsonType)).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw AiException(errorMessage(r.code, text))
            val o = json.parseToJsonElement(text).jsonObject
            o.str("title") to o.str("content").take(maxChars)
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun errorMessage(code: Int, body: String?): String {
        val detail = body?.let { runCatching { json.parseToJsonElement(it).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull }.getOrNull() }
        return when (code) {
            401 -> "DeepSeek rejected the API key. Check it in Settings."
            402 -> "Your DeepSeek balance is empty. Top up at platform.deepseek.com."
            429 -> "DeepSeek is rate-limiting requests. Try again in a moment."
            in 500..599 -> "DeepSeek is having trouble right now ($code). Try again shortly."
            else -> "DeepSeek error $code${detail?.let { ": $it" } ?: ""}"
        }
    }

    companion object {
        const val BASE = "https://api.deepseek.com"
        const val MODEL = "deepseek-flash"

        fun tool(name: String, description: String, properties: JsonObject, required: List<String> = emptyList()): JsonObject =
            buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", name)
                    put("description", description)
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("properties", properties)
                        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
                    })
                })
            }

        fun prop(type: String, description: String, extra: Map<String, JsonElement> = emptyMap()): JsonObject = buildJsonObject {
            put("type", type)
            put("description", description)
            extra.forEach { (k, v) -> put(k, v) }
        }
    }
}
