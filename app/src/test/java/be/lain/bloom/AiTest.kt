package be.lain.bloom

import be.lain.bloom.data.ai.AiJson
import be.lain.bloom.data.ai.StreamAccumulator
import be.lain.bloom.data.db.AiActionEntity
import be.lain.bloom.data.db.AiActionKind
import be.lain.bloom.data.db.AiActionStatus
import be.lain.bloom.data.db.ChatMessageEntity
import be.lain.bloom.data.db.DiaryEntryEntity
import be.lain.bloom.domain.EntrySource
import be.lain.bloom.domain.Meal
import be.lain.bloom.domain.Nutrients
import be.lain.bloom.feature.coach.ChatItem
import be.lain.bloom.feature.coach.buildItems
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class AiTest {
    private fun chunk(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun accumulatesStreamedToolCallFragments() {
        val acc = StreamAccumulator()
        acc.accept(chunk("""{"choices":[{"delta":{"reasoning_content":"User ate "}}]}"""))
        acc.accept(chunk("""{"choices":[{"delta":{"reasoning_content":"toast."}}]}"""))
        acc.accept(chunk("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"log_food","arguments":"{\"entries\":"}}]}}]}"""))
        acc.accept(chunk("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"[]}"}}]}}]}"""))
        acc.accept(chunk("""{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_2","function":{"name":"get_diary","arguments":"{}"}}]}}]}"""))
        acc.accept(chunk("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":120,"completion_tokens":30}}"""))
        val t = acc.turn()
        assertEquals("User ate toast.", t.reasoning)
        assertEquals(2, t.toolCalls.size)
        assertEquals("log_food", t.toolCalls[0].name)
        assertEquals("""{"entries":[]}""", t.toolCalls[0].arguments)
        assertEquals("call_2", t.toolCalls[1].id)
        assertEquals("tool_calls", t.finishReason)
        assertEquals(120, t.promptTokens)
    }

    @Test fun contentStreamReportsChanges() {
        val acc = StreamAccumulator()
        assertTrue(acc.accept(chunk("""{"choices":[{"delta":{"content":"Hi"}}]}""")))
        acc.accept(chunk("""{"choices":[{"delta":{"content":" there"}}]}"""))
        assertEquals("Hi there", acc.turn().content)
    }

    @Test fun diaryEntrySnapshotRoundTrips() {
        val e = DiaryEntryEntity(
            id = 7, date = LocalDate.of(2026, 11, 3), meal = Meal.LUNCH, at = Instant.ofEpochMilli(1234), foodId = 3, name = "Oats",
            brand = null, grams = 60.0, amount = 60.0, unit = "g", nutrients = Nutrients(kcal = 230.0, protein = 8.0, fiber = null),
            source = EntrySource.AI, aiActionId = 9,
        )
        assertEquals(e, AiJson.entryFrom(AiJson.entry(e)))
    }

    @Test fun chatItemsGroupToolCallsAndAttachActions() {
        val now = Instant.EPOCH
        val msgs = listOf(
            ChatMessageEntity(1, 1, "user", "I had oats", createdAt = now),
            ChatMessageEntity(2, 1, "assistant", "", toolCalls = """[{"id":"a","name":"search_foods","arguments":"{}"}]""", createdAt = now),
            ChatMessageEntity(3, 1, "tool", "{}", toolCallId = "a", createdAt = now),
            ChatMessageEntity(4, 1, "assistant", "", toolCalls = """[{"id":"b","name":"log_food","arguments":"{}"}]""", createdAt = now),
            ChatMessageEntity(5, 1, "tool", "{}", toolCallId = "b", createdAt = now),
            ChatMessageEntity(6, 1, "assistant", "Logged your oats!", createdAt = now),
        )
        val action = AiActionEntity(1, 1, 6, AiActionKind.LOG_FOOD, AiActionStatus.APPLIED, "Added 1 item", "{}", now)
        val items = buildItems(msgs, listOf(action))
        assertEquals(listOf("u1", "t2", "a6", "x1"), items.map { it.key })
        assertEquals(listOf("search_foods", "log_food"), (items[1] as ChatItem.Tools).names)
    }
}
