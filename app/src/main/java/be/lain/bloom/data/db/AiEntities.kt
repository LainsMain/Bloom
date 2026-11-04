package be.lain.bloom.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Entity(tableName = "chat")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** "coach" (normal), "quicklog" (food logging), "review" (weekly review). */
    val kind: String = "coach",
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Entity(tableName = "chat_message", indices = [Index("chatId")])
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    /** user | assistant | tool */
    val role: String,
    val content: String,
    /** Model reasoning; never shown, but must be sent back with tool conversations. */
    val reasoning: String? = null,
    /** JSON array of {id,name,arguments} for assistant tool calls. */
    val toolCalls: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    /** Newline-separated local image file paths. */
    val images: String? = null,
    val createdAt: Instant,
)

enum class AiActionKind {
    LOG_FOOD, CREATE_FOOD, UPDATE_ENTRY, DELETE_ENTRY, LOG_WEIGHT, PLAN_PROPOSAL, TRAINING_PROPOSAL,
    CREATE_RECIPE, LOG_WORKOUT, LOG_RUN, LOG_MEASUREMENT, GOAL_PROPOSAL, REMINDER,
}
enum class AiActionStatus { APPLIED, UNDONE, PENDING, ACCEPTED, DECLINED }

/** Undo journal / proposal record for everything the AI does. */
@Entity(tableName = "ai_action", indices = [Index("chatId"), Index("messageId")])
data class AiActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val messageId: Long? = null,
    val kind: AiActionKind,
    val status: AiActionStatus,
    val summary: String,
    /** Kind-specific JSON (entry ids, previous snapshots, proposal values). */
    val payload: String,
    val createdAt: Instant,
)

@Dao
interface ChatDao {
    @Insert suspend fun insertChat(c: ChatEntity): Long
    @Update suspend fun updateChat(c: ChatEntity)
    @Query("SELECT * FROM chat WHERE id = :id") suspend fun chat(id: Long): ChatEntity?
    @Query("SELECT * FROM chat WHERE kind = :kind ORDER BY updatedAt DESC") fun observeChats(kind: String = "coach"): Flow<List<ChatEntity>>
    @Query("SELECT * FROM chat WHERE kind = :kind ORDER BY updatedAt DESC LIMIT 1") suspend fun latest(kind: String): ChatEntity?
    @Query("DELETE FROM chat WHERE id = :id") suspend fun deleteChat(id: Long)
    @Query("DELETE FROM chat_message WHERE chatId = :id") suspend fun deleteMessages(id: Long)

    @Insert suspend fun insertMessage(m: ChatMessageEntity): Long
    @Query("SELECT * FROM chat_message WHERE chatId = :chatId ORDER BY id") fun observeMessages(chatId: Long): Flow<List<ChatMessageEntity>>
    @Query("SELECT * FROM chat_message WHERE chatId = :chatId ORDER BY id") suspend fun messages(chatId: Long): List<ChatMessageEntity>

    @Insert suspend fun insertAction(a: AiActionEntity): Long
    @Update suspend fun updateAction(a: AiActionEntity)
    @Query("SELECT * FROM ai_action WHERE id = :id") suspend fun action(id: Long): AiActionEntity?
    @Query("SELECT * FROM ai_action WHERE chatId = :chatId ORDER BY id") fun observeActions(chatId: Long): Flow<List<AiActionEntity>>
    @Query("UPDATE ai_action SET messageId = :messageId WHERE chatId = :chatId AND messageId IS NULL")
    suspend fun attachPending(chatId: Long, messageId: Long)
}
