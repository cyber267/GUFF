package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.AppSettingsEntity
import com.example.data.model.ConversationEntity
import com.example.data.model.FriendEntity
import com.example.data.model.MessageEntity
import com.example.data.model.MessageStatus
import com.example.data.model.UserAccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    // --- User Accounts ---
    @Query("SELECT * FROM user_accounts WHERE isCurrentUser = 1 LIMIT 1")
    fun getCurrentUserFlow(): Flow<UserAccountEntity?>

    @Query("SELECT * FROM user_accounts WHERE isCurrentUser = 1 LIMIT 1")
    suspend fun getCurrentUser(): UserAccountEntity?

    @Query("SELECT * FROM user_accounts WHERE username = :username LIMIT 1")
    suspend fun getUserByUsername(username: String): UserAccountEntity?

    @Query("SELECT * FROM user_accounts ORDER BY createdAt DESC")
    fun getAllUsersFlow(): Flow<List<UserAccountEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserAccountEntity): Long

    @Update
    suspend fun updateUser(user: UserAccountEntity)

    @Query("UPDATE user_accounts SET isCurrentUser = 0")
    suspend fun clearCurrentUserFlags()

    @Query("UPDATE user_accounts SET isCurrentUser = 1 WHERE id = :userId")
    suspend fun setCurrentUser(userId: Long)

    // --- Conversations ---
    @Query("SELECT * FROM conversations ORDER BY isPinned DESC, lastMessageTime DESC")
    fun getConversationsFlow(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    fun getConversationFlow(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE participantUsername = :username LIMIT 1")
    suspend fun getConversationByParticipantUsername(username: String): ConversationEntity?

    @Query("UPDATE messages SET conversationId = :newId WHERE conversationId = :oldId")
    suspend fun updateMessagesConversationId(oldId: String, newId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversations(conversations: List<ConversationEntity>)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET safetyNumberVerified = :verified WHERE id = :id")
    suspend fun setSafetyNumberVerified(id: String, verified: Boolean)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    // --- Messages ---
    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY timestamp ASC")
    fun getMessagesFlow(convId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("UPDATE messages SET isHighResDownloaded = 1 WHERE id = :id")
    suspend fun markMediaDownloaded(id: String)

    @Query("UPDATE messages SET translatedText = :translation WHERE id = :id")
    suspend fun updateMessageTranslation(id: String, translation: String)

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateMessageStatus(id: String, status: MessageStatus)

    @Query("SELECT * FROM messages WHERE content LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchMessagesFlow(query: String): Flow<List<MessageEntity>>

    @Query("DELETE FROM messages WHERE conversationId = :convId")
    suspend fun deleteMessagesForConversation(convId: String)

    // --- App Settings ---
    @Query("SELECT * FROM app_settings WHERE id = 1 LIMIT 1")
    fun getSettingsFlow(): Flow<AppSettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettings(settings: AppSettingsEntity)

    @Update
    suspend fun updateSettings(settings: AppSettingsEntity)

    // --- Friends & Following ---
    @Query("SELECT * FROM friends ORDER BY addedAt DESC")
    fun getAllFriendsFlow(): Flow<List<FriendEntity>>

    @Query("SELECT * FROM friends WHERE username = :username LIMIT 1")
    suspend fun getFriendByUsername(username: String): FriendEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFriend(friend: FriendEntity)

    @Query("DELETE FROM friends WHERE username = :username")
    suspend fun deleteFriend(username: String)
}
