package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MessageStatus {
    SENDING,
    SENT,
    DELIVERED,
    READ
}

@Entity(tableName = "user_accounts")
data class UserAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val username: String,
    val displayName: String,
    val passwordHash: String,
    val salt: String,
    val country: String,
    val countryCode: String,
    val countryFlag: String,
    val avatarEmoji: String,
    val syncPhrase: String,
    val isCurrentUser: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val participantUsername: String,
    val participantName: String,
    val participantCountry: String,
    val countryFlag: String,
    val avatarEmoji: String,
    val isAiAssistant: Boolean = false,
    val isGroup: Boolean = false,
    val groupMembersJson: String = "",
    val lastMessageText: String = "",
    val lastMessageTime: Long = System.currentTimeMillis(),
    val unreadCount: Int = 0,
    val safetyNumberVerified: Boolean = false,
    val safetyNumbers: String = "",
    val isPinned: Boolean = false
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderUsername: String,
    val senderDisplayName: String = "",
    val isFromMe: Boolean,
    val content: String,
    val cipherTextBase64: String = "",
    val ivBase64: String = "",
    val mediaUri: String? = null,
    val mediaType: String? = null, // "IMAGE", "DOCUMENT"
    val mediaSizeKb: Int = 0,
    val isHighResDownloaded: Boolean = true,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.READ,
    val isE2eeVerified: Boolean = true,
    val translatedText: String? = null,
    val replyToMessageId: String? = null,
    val replyToSenderName: String? = null,
    val replyToText: String? = null
)

@Entity(tableName = "friends")
data class FriendEntity(
    @PrimaryKey val username: String,
    val displayName: String,
    val country: String,
    val countryFlag: String,
    val avatarEmoji: String,
    val bio: String = "",
    val language: String = "",
    val isFollowing: Boolean = true,
    val isMutualFriend: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val isDarkMode: Boolean = true,
    val isDataSaverEnabled: Boolean = false,
    val isCrossPlatformSyncEnabled: Boolean = true,
    val syncStatusText: String = "Synced & Connected",
    val lastSyncTimestamp: Long = System.currentTimeMillis()
)
