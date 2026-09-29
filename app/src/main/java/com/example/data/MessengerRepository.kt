package com.example.data

import android.content.Context
import com.example.ai.GeminiAssistant
import com.example.crypto.CryptoEngine
import com.example.data.firebase.FirebaseSyncManager
import com.example.data.firebase.FirestoreMessage
import com.example.data.local.AppDao
import com.example.data.local.AppDatabase
import com.example.data.model.AppSettingsEntity
import com.example.data.model.ConversationEntity
import com.example.data.model.FriendEntity
import com.example.data.model.MessageEntity
import com.example.data.model.MessageStatus
import com.example.data.model.UserAccountEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.UUID

class MessengerRepository(
    private val appDao: AppDao,
    private val scope: CoroutineScope
) {
    companion object {
        @Volatile
        private var INSTANCE: MessengerRepository? = null

        fun getInstance(context: Context, scope: CoroutineScope): MessengerRepository {
            return INSTANCE ?: synchronized(this) {
                val db = AppDatabase.getDatabase(context)
                val instance = MessengerRepository(db.appDao(), scope)
                INSTANCE = instance
                instance
            }
        }
    }

    val currentUserFlow: Flow<UserAccountEntity?> = appDao.getCurrentUserFlow()
    val allUsersFlow: Flow<List<UserAccountEntity>> = appDao.getAllUsersFlow()
    val conversationsFlow: Flow<List<ConversationEntity>> = appDao.getConversationsFlow()
    val friendsFlow: Flow<List<FriendEntity>> = appDao.getAllFriendsFlow()
    val settingsFlow: Flow<AppSettingsEntity?> = appDao.getSettingsFlow()
    val typingStatusFlow = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String?>>(emptyMap())

    private val activeCloudListeners = mutableMapOf<String, kotlinx.coroutines.Job>()
    private var userInboxJob: kotlinx.coroutines.Job? = null
    private var userConversationsJob: kotlinx.coroutines.Job? = null
    private var userFriendsJob: kotlinx.coroutines.Job? = null

    fun startCloudSyncForConversation(conversationId: String) {
        if (activeCloudListeners.containsKey(conversationId)) return

        val job = scope.launch(Dispatchers.IO) {
            FirebaseSyncManager.instance.listenToConversationMessages(conversationId)
                .collect { cloudMessages ->
                    val currentUser = appDao.getCurrentUser()
                    val myUsername = currentUser?.username?.lowercase() ?: ""

                    cloudMessages.forEach { cloudMsg ->
                        val existingMsg = appDao.getMessageById(cloudMsg.id)
                        if (existingMsg == null) {
                            // If sender is myUsername, this message was sent from another device logged into this same account!
                            val isSentByMe = cloudMsg.senderUsername.lowercase() == myUsername
                            var peerUsername = if (isSentByMe) cloudMsg.recipientUsername else cloudMsg.senderUsername
                            if (peerUsername.isBlank() || peerUsername.lowercase() == myUsername) {
                                if (conversationId.startsWith("dm_")) {
                                    val parts = conversationId.removePrefix("dm_").split("_")
                                    if (parts.size == 2) {
                                        peerUsername = if (parts[0].lowercase() == myUsername) parts[1] else parts[0]
                                    }
                                }
                            }

                            val messageEntity = MessageEntity(
                                id = cloudMsg.id,
                                conversationId = cloudMsg.conversationId.ifBlank { conversationId },
                                senderUsername = cloudMsg.senderUsername,
                                senderDisplayName = cloudMsg.senderDisplayName,
                                isFromMe = isSentByMe,
                                content = cloudMsg.content,
                                cipherTextBase64 = cloudMsg.cipherTextBase64,
                                ivBase64 = cloudMsg.ivBase64,
                                mediaUri = cloudMsg.mediaUri,
                                mediaType = cloudMsg.mediaType,
                                mediaSizeKb = cloudMsg.mediaSizeKb,
                                isHighResDownloaded = true,
                                timestamp = cloudMsg.timestamp,
                                status = MessageStatus.READ,
                                isE2eeVerified = true,
                                replyToMessageId = cloudMsg.replyToMessageId,
                                replyToSenderName = cloudMsg.replyToSenderName,
                                replyToText = cloudMsg.replyToText
                            )
                            appDao.insertMessage(messageEntity)

                            val conv = appDao.getConversationById(conversationId)
                                ?: appDao.getConversationByParticipantUsername(peerUsername)
                            if (conv != null) {
                                val healed = if (conv.participantUsername.isBlank() || conv.participantUsername.lowercase() == myUsername) {
                                    conv.copy(
                                        participantUsername = peerUsername,
                                        lastMessageText = cloudMsg.content,
                                        lastMessageTime = cloudMsg.timestamp,
                                        unreadCount = if (isSentByMe) conv.unreadCount else (conv.unreadCount + 1)
                                    )
                                } else {
                                    conv.copy(
                                        lastMessageText = cloudMsg.content,
                                        lastMessageTime = cloudMsg.timestamp,
                                        unreadCount = if (isSentByMe) conv.unreadCount else (conv.unreadCount + 1)
                                    )
                                }
                                appDao.updateConversation(healed)
                            } else {
                                if (peerUsername.isNotBlank() && peerUsername != myUsername) {
                                    val newConv = ConversationEntity(
                                        id = conversationId,
                                        participantUsername = peerUsername,
                                        participantName = if (isSentByMe) peerUsername.replaceFirstChar { it.uppercase() } else (cloudMsg.senderDisplayName.ifBlank { peerUsername }),
                                        participantCountry = "Global",
                                        countryFlag = if (isSentByMe) "🌐" else cloudMsg.senderCountryFlag,
                                        avatarEmoji = if (isSentByMe) "👤" else cloudMsg.senderAvatarEmoji,
                                        isAiAssistant = false,
                                        lastMessageText = cloudMsg.content,
                                        lastMessageTime = cloudMsg.timestamp,
                                        unreadCount = if (isSentByMe) 0 else 1,
                                        safetyNumberVerified = true,
                                        safetyNumbers = CryptoEngine.generateSafetyNumbers(myUsername, peerUsername),
                                        isPinned = false
                                    )
                                    appDao.insertConversation(newConv)
                                }
                            }
                        }
                    }
                }
        }
        activeCloudListeners[conversationId] = job
    }

    fun startUserInboxSync(username: String) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return
        userInboxJob?.cancel()
        userInboxJob = scope.launch(Dispatchers.IO) {
            FirebaseSyncManager.instance.listenToUserInbox(clean).collect { inboxMsgs ->
                val currentUser = appDao.getCurrentUser()
                val myUsername = currentUser?.username?.lowercase() ?: clean
                inboxMsgs.forEach { cloudMsg ->
                    val existingMsg = appDao.getMessageById(cloudMsg.id)
                    if (existingMsg == null) {
                        val isSentByMe = cloudMsg.senderUsername.lowercase() == myUsername
                        var peerUsername = if (isSentByMe) cloudMsg.recipientUsername else cloudMsg.senderUsername
                        val targetConvId = if (cloudMsg.conversationId.isNotBlank()) {
                            cloudMsg.conversationId
                        } else {
                            FirebaseSyncManager.getCanonicalChatId(myUsername, peerUsername)
                        }

                        if (peerUsername.isBlank() || peerUsername.lowercase() == myUsername) {
                            if (targetConvId.startsWith("dm_")) {
                                val parts = targetConvId.removePrefix("dm_").split("_")
                                if (parts.size == 2) {
                                    peerUsername = if (parts[0].lowercase() == myUsername) parts[1] else parts[0]
                                }
                            }
                        }

                        val messageEntity = MessageEntity(
                            id = cloudMsg.id,
                            conversationId = targetConvId,
                            senderUsername = cloudMsg.senderUsername,
                            senderDisplayName = cloudMsg.senderDisplayName,
                            isFromMe = isSentByMe,
                            content = cloudMsg.content,
                            cipherTextBase64 = cloudMsg.cipherTextBase64,
                            ivBase64 = cloudMsg.ivBase64,
                            mediaUri = cloudMsg.mediaUri,
                            mediaType = cloudMsg.mediaType,
                            mediaSizeKb = cloudMsg.mediaSizeKb,
                            isHighResDownloaded = true,
                            timestamp = cloudMsg.timestamp,
                            status = MessageStatus.READ,
                            isE2eeVerified = true,
                            replyToMessageId = cloudMsg.replyToMessageId,
                            replyToSenderName = cloudMsg.replyToSenderName,
                            replyToText = cloudMsg.replyToText
                        )
                        appDao.insertMessage(messageEntity)

                        val conv = appDao.getConversationById(targetConvId)
                            ?: appDao.getConversationByParticipantUsername(peerUsername)
                        if (conv != null) {
                            val healed = if (conv.participantUsername.isBlank() || conv.participantUsername.lowercase() == myUsername) {
                                conv.copy(
                                    participantUsername = peerUsername,
                                    lastMessageText = cloudMsg.content,
                                    lastMessageTime = cloudMsg.timestamp,
                                    unreadCount = if (isSentByMe) conv.unreadCount else (conv.unreadCount + 1)
                                )
                            } else {
                                conv.copy(
                                    lastMessageText = cloudMsg.content,
                                    lastMessageTime = cloudMsg.timestamp,
                                    unreadCount = if (isSentByMe) conv.unreadCount else (conv.unreadCount + 1)
                                )
                            }
                            appDao.updateConversation(healed)
                        } else if (peerUsername.isNotBlank() && peerUsername != myUsername) {
                            val newConv = ConversationEntity(
                                id = targetConvId,
                                participantUsername = peerUsername,
                                participantName = if (isSentByMe) peerUsername.replaceFirstChar { it.uppercase() } else (cloudMsg.senderDisplayName.ifBlank { peerUsername }),
                                participantCountry = "Global",
                                countryFlag = if (isSentByMe) "🌐" else cloudMsg.senderCountryFlag,
                                avatarEmoji = if (isSentByMe) "👤" else cloudMsg.senderAvatarEmoji,
                                isAiAssistant = false,
                                lastMessageText = cloudMsg.content,
                                lastMessageTime = cloudMsg.timestamp,
                                unreadCount = if (isSentByMe) 0 else 1,
                                safetyNumberVerified = true,
                                safetyNumbers = CryptoEngine.generateSafetyNumbers(myUsername, peerUsername),
                                isPinned = false
                            )
                            appDao.insertConversation(newConv)
                        }
                        startCloudSyncForConversation(targetConvId)
                    }
                }
            }
        }
    }

    /**
     * Listens to cloud-level conversations and friends to mirror changes across all phones
     * logged into the same account in real time.
     */
    fun startUserCloudSync(username: String) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return

        userConversationsJob?.cancel()
        userConversationsJob = scope.launch(Dispatchers.IO) {
            FirebaseSyncManager.instance.listenToUserConversations(clean).collect { cloudConvs ->
                cloudConvs.forEach { cloudConv ->
                    val existing = appDao.getConversationById(cloudConv.id)
                    if (existing == null) {
                        val entity = ConversationEntity(
                            id = cloudConv.id,
                            participantUsername = cloudConv.participantUsername,
                            participantName = cloudConv.participantName,
                            participantCountry = cloudConv.participantCountry,
                            countryFlag = cloudConv.countryFlag,
                            avatarEmoji = cloudConv.avatarEmoji,
                            isAiAssistant = cloudConv.isAiAssistant,
                            isGroup = cloudConv.isGroup,
                            groupMembersJson = cloudConv.groupMembersJson,
                            lastMessageText = cloudConv.lastMessageText,
                            lastMessageTime = cloudConv.lastMessageTime,
                            safetyNumbers = cloudConv.safetyNumbers.ifBlank {
                                CryptoEngine.generateSafetyNumbers(clean, cloudConv.participantUsername)
                            },
                            safetyNumberVerified = true
                        )
                        appDao.insertConversation(entity)
                    } else if (cloudConv.lastMessageTime > existing.lastMessageTime) {
                        appDao.updateConversation(
                            existing.copy(
                                lastMessageText = cloudConv.lastMessageText,
                                lastMessageTime = cloudConv.lastMessageTime
                            )
                        )
                    }
                    startCloudSyncForConversation(cloudConv.id)
                }
            }
        }

        userFriendsJob?.cancel()
        userFriendsJob = scope.launch(Dispatchers.IO) {
            FirebaseSyncManager.instance.listenToUserFriends(clean).collect { cloudFriends ->
                cloudFriends.forEach { cloudFriend ->
                    val existing = appDao.getFriendByUsername(cloudFriend.username)
                    if (existing == null) {
                        val entity = FriendEntity(
                            username = cloudFriend.username,
                            displayName = cloudFriend.displayName,
                            country = cloudFriend.country,
                            countryFlag = cloudFriend.countryFlag,
                            avatarEmoji = cloudFriend.avatarEmoji,
                            addedAt = cloudFriend.addedAt
                        )
                        appDao.insertFriend(entity)
                    }
                }
            }
        }
    }

    /**
     * Pulls full cloud history (conversations, friends, message history) from Firestore
     * so a newly logged-in second phone instantly has identical chats and messages.
     */
    suspend fun syncAllCloudDataForUser(username: String) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return

        // 1. Pull cloud friends
        val cloudFriends = FirebaseSyncManager.instance.fetchUserFriends(clean)
        cloudFriends.forEach { cf ->
            if (appDao.getFriendByUsername(cf.username) == null) {
                appDao.insertFriend(
                    FriendEntity(
                        username = cf.username,
                        displayName = cf.displayName,
                        country = cf.country,
                        countryFlag = cf.countryFlag,
                        avatarEmoji = cf.avatarEmoji,
                        addedAt = cf.addedAt
                    )
                )
            }
        }

        // 2. Pull cloud conversations
        val cloudConvs = FirebaseSyncManager.instance.fetchUserConversations(clean)
        cloudConvs.forEach { cc ->
            val existing = appDao.getConversationById(cc.id)
            if (existing == null) {
                appDao.insertConversation(
                    ConversationEntity(
                        id = cc.id,
                        participantUsername = cc.participantUsername,
                        participantName = cc.participantName,
                        participantCountry = cc.participantCountry,
                        countryFlag = cc.countryFlag,
                        avatarEmoji = cc.avatarEmoji,
                        isAiAssistant = cc.isAiAssistant,
                        isGroup = cc.isGroup,
                        groupMembersJson = cc.groupMembersJson,
                        lastMessageText = cc.lastMessageText,
                        lastMessageTime = cc.lastMessageTime,
                        safetyNumbers = cc.safetyNumbers.ifBlank {
                            CryptoEngine.generateSafetyNumbers(clean, cc.participantUsername)
                        },
                        safetyNumberVerified = true
                    )
                )
            }
            startCloudSyncForConversation(cc.id)

            // 3. Pull message history for each conversation
            val messages = FirebaseSyncManager.instance.fetchConversationMessages(cc.id)
            messages.forEach { msg ->
                if (appDao.getMessageById(msg.id) == null) {
                    val isSentByMe = msg.senderUsername.lowercase() == clean
                    appDao.insertMessage(
                        MessageEntity(
                            id = msg.id,
                            conversationId = cc.id,
                            senderUsername = msg.senderUsername,
                            senderDisplayName = msg.senderDisplayName,
                            isFromMe = isSentByMe,
                            content = msg.content,
                            cipherTextBase64 = msg.cipherTextBase64,
                            ivBase64 = msg.ivBase64,
                            mediaUri = msg.mediaUri,
                            mediaType = msg.mediaType,
                            mediaSizeKb = msg.mediaSizeKb,
                            isHighResDownloaded = true,
                            timestamp = msg.timestamp,
                            status = MessageStatus.READ,
                            isE2eeVerified = true,
                            replyToMessageId = msg.replyToMessageId,
                            replyToSenderName = msg.replyToSenderName,
                            replyToText = msg.replyToText
                        )
                    )
                }
            }
        }
    }

    fun getMessagesFlow(conversationId: String): Flow<List<MessageEntity>> {
        startCloudSyncForConversation(conversationId)
        return appDao.getMessagesFlow(conversationId)
    }

    fun getConversationFlow(conversationId: String): Flow<ConversationEntity?> {
        return appDao.getConversationFlow(conversationId)
    }

    fun searchMessagesFlow(query: String): Flow<List<MessageEntity>> {
        return appDao.searchMessagesFlow(query)
    }

    init {
        scope.launch(Dispatchers.IO) {
            initDefaultDataIfEmpty()

            // Continuously listen to current user's personal inbox and cloud state
            currentUserFlow.collect { user ->
                if (user != null) {
                    startUserInboxSync(user.username)
                    startUserCloudSync(user.username)
                    syncAllCloudDataForUser(user.username)
                }
            }
        }

        // Continuously ensure all active conversations are synced with Firestore
        scope.launch(Dispatchers.IO) {
            conversationsFlow.collect { convs ->
                convs.forEach { conv ->
                    startCloudSyncForConversation(conv.id)
                }
            }
        }
    }

    private suspend fun initDefaultDataIfEmpty() {
        val existingUser = appDao.getCurrentUser()
        if (existingUser == null) {
            // Create default user Alex
            val salt = CryptoEngine.generateSalt()
            val passHash = CryptoEngine.hashPassword("privacy123", salt)
            val syncPhrase = CryptoEngine.generateSyncPhrase()

            val defaultUser = UserAccountEntity(
                username = "alex_plain",
                displayName = "Alex Rivera",
                passwordHash = passHash,
                salt = salt,
                country = "United States",
                countryCode = "US",
                countryFlag = "🇺🇸",
                avatarEmoji = "🛡️",
                syncPhrase = syncPhrase,
                isCurrentUser = true
            )
            val userId = appDao.insertUser(defaultUser)
            appDao.setCurrentUser(userId)
        }

        val existingSettings = appDao.getSettingsFlow().firstOrNull()
        if (existingSettings == null) {
            appDao.insertSettings(
                AppSettingsEntity(
                    id = 1,
                    isDarkMode = true,
                    isDataSaverEnabled = false,
                    isCrossPlatformSyncEnabled = true,
                    syncStatusText = "Synced & Connected",
                    lastSyncTimestamp = System.currentTimeMillis()
                )
            )
        }

        val conversations = appDao.getConversationsFlow().firstOrNull()
        if (conversations.isNullOrEmpty()) {
            seedInitialConversations()
        }
    }

    private suspend fun seedInitialConversations() {
        val now = System.currentTimeMillis()
        val defaultSecretKey = CryptoEngine.deriveKey("plain_seed_key", "plain_salt")

        // 1. Meta AI / Gemini Assistant
        val geminiSafety = CryptoEngine.generateSafetyNumbers("alex_plain", "gemini_meta_ai")
        val geminiConv = ConversationEntity(
            id = "chat_gemini",
            participantUsername = "gemini_ai",
            participantName = "Meta AI (Gemini)",
            participantCountry = "Global AI",
            countryFlag = "🌐",
            avatarEmoji = "🤖",
            isAiAssistant = true,
            lastMessageText = "Hello! I am your AI assistant for instant translation and conversational help.",
            lastMessageTime = now - 60_000,
            unreadCount = 0,
            safetyNumberVerified = true,
            safetyNumbers = geminiSafety,
            isPinned = true
        )

        val geminiGreeting = "Hello! I am Gemini Meta AI. I can translate messages between global users, explain encrypted security protocols, summarize long conversations, or draft quick replies. How can I help you today?"
        val encGemini = CryptoEngine.encrypt(geminiGreeting, defaultSecretKey)
        val geminiMsg = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = "chat_gemini",
            senderUsername = "gemini_ai",
            isFromMe = false,
            content = geminiGreeting,
            cipherTextBase64 = encGemini.cipherTextBase64,
            ivBase64 = encGemini.ivBase64,
            timestamp = now - 60_000,
            status = MessageStatus.READ,
            isE2eeVerified = true
        )

        // 2. Yuki Tanaka from Japan
        val yukiSafety = CryptoEngine.generateSafetyNumbers("alex_plain", "yuki_jp")
        val yukiConv = ConversationEntity(
            id = "chat_yuki",
            participantUsername = "yuki_jp",
            participantName = "Yuki Tanaka",
            participantCountry = "Japan",
            countryFlag = "🇯🇵",
            avatarEmoji = "🌸",
            isAiAssistant = false,
            lastMessageText = "こんにちは！ The latency across Tokyo and your region is only 38ms with E2EE.",
            lastMessageTime = now - 180_000,
            unreadCount = 1,
            safetyNumberVerified = true,
            safetyNumbers = yukiSafety,
            isPinned = false
        )

        val yukiMsg1Text = "Konnichiwa Alex! Sending greetings from Tokyo."
        val encYuki1 = CryptoEngine.encrypt(yukiMsg1Text, defaultSecretKey)
        val yukiMsg1 = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = "chat_yuki",
            senderUsername = "yuki_jp",
            isFromMe = false,
            content = yukiMsg1Text,
            cipherTextBase64 = encYuki1.cipherTextBase64,
            ivBase64 = encYuki1.ivBase64,
            timestamp = now - 300_000,
            status = MessageStatus.READ,
            isE2eeVerified = true
        )

        val yukiMsg2Text = "こんにちは！ The latency across Tokyo and your region is only 38ms with E2EE."
        val encYuki2 = CryptoEngine.encrypt(yukiMsg2Text, defaultSecretKey)
        val yukiMsg2 = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = "chat_yuki",
            senderUsername = "yuki_jp",
            isFromMe = false,
            content = yukiMsg2Text,
            cipherTextBase64 = encYuki2.cipherTextBase64,
            ivBase64 = encYuki2.ivBase64,
            timestamp = now - 180_000,
            status = MessageStatus.READ,
            isE2eeVerified = true
        )

        // 3. Mateo Silva from Brazil (High-res media demonstration)
        val mateoSafety = CryptoEngine.generateSafetyNumbers("alex_plain", "mateo_br")
        val mateoConv = ConversationEntity(
            id = "chat_mateo",
            participantUsername = "mateo_br",
            participantName = "Mateo Silva",
            participantCountry = "Brazil",
            countryFlag = "🇧🇷",
            avatarEmoji = "🌿",
            isAiAssistant = false,
            lastMessageText = "Here is the raw high-res photo from São Paulo (4032x3024 px).",
            lastMessageTime = now - 600_000,
            unreadCount = 0,
            safetyNumberVerified = false,
            safetyNumbers = mateoSafety,
            isPinned = false
        )

        val mateoMsg1Text = "Olá Alex! Checking out this encrypted messenger. It works amazingly fast even on lower bandwidth!"
        val encMateo1 = CryptoEngine.encrypt(mateoMsg1Text, defaultSecretKey)
        val mateoMsg1 = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = "chat_mateo",
            senderUsername = "mateo_br",
            isFromMe = false,
            content = mateoMsg1Text,
            cipherTextBase64 = encMateo1.cipherTextBase64,
            ivBase64 = encMateo1.ivBase64,
            timestamp = now - 720_000,
            status = MessageStatus.READ,
            isE2eeVerified = true
        )

        val mateoMsg2Text = "Here is the raw high-res photo from São Paulo (4032x3024 px)."
        val encMateo2 = CryptoEngine.encrypt(mateoMsg2Text, defaultSecretKey)
        val mateoMsg2 = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = "chat_mateo",
            senderUsername = "mateo_br",
            isFromMe = false,
            content = mateoMsg2Text,
            cipherTextBase64 = encMateo2.cipherTextBase64,
            ivBase64 = encMateo2.ivBase64,
            mediaUri = "https://images.unsplash.com/photo-1483728642387-6c3bdd6c93e5?w=1600&q=90",
            mediaType = "IMAGE",
            mediaSizeKb = 3420, // 3.4 MB High-Res
            isHighResDownloaded = true,
            timestamp = now - 600_000,
            status = MessageStatus.READ,
            isE2eeVerified = true
        )

        // 4. Elena Rostova from Germany
        val elenaSafety = CryptoEngine.generateSafetyNumbers("alex_plain", "elena_de")
        val elenaConv = ConversationEntity(
            id = "chat_elena",
            participantUsername = "elena_de",
            participantName = "Elena Rostova",
            participantCountry = "Germany",
            countryFlag = "🇩🇪",
            avatarEmoji = "⚡",
            isAiAssistant = false,
            lastMessageText = "Verified our safety fingerprint! Zero leaks, pure privacy.",
            lastMessageTime = now - 1200_000,
            unreadCount = 0,
            safetyNumberVerified = true,
            safetyNumbers = elenaSafety,
            isPinned = false
        )

        val elenaMsgText = "Verified our safety fingerprint! Zero leaks, pure privacy."
        val encElena = CryptoEngine.encrypt(elenaMsgText, defaultSecretKey)
        val elenaMsg = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = "chat_elena",
            senderUsername = "elena_de",
            isFromMe = false,
            content = elenaMsgText,
            cipherTextBase64 = encElena.cipherTextBase64,
            ivBase64 = encElena.ivBase64,
            timestamp = now - 1200_000,
            status = MessageStatus.READ,
            isE2eeVerified = true
        )

        // 5. Seeded Group Chat: "Global Privacy Circle"
        val groupSafety = CryptoEngine.generateSafetyNumbers("alex_plain", "group_privacy_circle")
        val groupConv = ConversationEntity(
            id = "group_privacy_circle",
            participantUsername = "group",
            participantName = "Global Privacy Circle",
            participantCountry = "4 members",
            countryFlag = "👥",
            avatarEmoji = "🛡️",
            isAiAssistant = false,
            isGroup = true,
            groupMembersJson = "yuki_jp,mateo_br,elena_de",
            lastMessageText = "Yuki: Welcome to the encrypted group! Zero phone numbers required.",
            lastMessageTime = now - 50_000,
            unreadCount = 0,
            safetyNumberVerified = true,
            safetyNumbers = groupSafety,
            isPinned = false
        )

        val groupMsg1Text = "Welcome to our group chat! Messages sent here reach all members with multi-recipient E2EE."
        val encGroup1 = CryptoEngine.encrypt(groupMsg1Text, defaultSecretKey)
        val groupMsg1 = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = "group_privacy_circle",
            senderUsername = "elena_de",
            senderDisplayName = "Elena Rostova (🇩🇪)",
            isFromMe = false,
            content = groupMsg1Text,
            cipherTextBase64 = encGroup1.cipherTextBase64,
            ivBase64 = encGroup1.ivBase64,
            timestamp = now - 90_000,
            status = MessageStatus.READ,
            isE2eeVerified = true
        )

        val groupMsg2Text = "Yuki: Welcome to the encrypted group! Zero phone numbers required."
        val encGroup2 = CryptoEngine.encrypt(groupMsg2Text, defaultSecretKey)
        val groupMsg2 = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = "group_privacy_circle",
            senderUsername = "yuki_jp",
            senderDisplayName = "Yuki Tanaka (🇯🇵)",
            isFromMe = false,
            content = groupMsg2Text,
            cipherTextBase64 = encGroup2.cipherTextBase64,
            ivBase64 = encGroup2.ivBase64,
            timestamp = now - 50_000,
            status = MessageStatus.READ,
            isE2eeVerified = true
        )

        appDao.insertConversations(listOf(geminiConv, yukiConv, mateoConv, elenaConv, groupConv))
        appDao.insertMessages(listOf(geminiMsg, yukiMsg1, yukiMsg2, mateoMsg1, mateoMsg2, elenaMsg, groupMsg1, groupMsg2))
    }

    suspend fun signupUser(
        username: String,
        password: String,
        displayName: String,
        country: String,
        countryFlag: String
    ): Result<UserAccountEntity> {
        val cleanUsername = username.trim().lowercase().removePrefix("@")
        if (cleanUsername.length < 3) {
            return Result.failure(IllegalArgumentException("Username must be at least 3 characters."))
        }
        if (password.length < 6) {
            return Result.failure(IllegalArgumentException("Password must be at least 6 characters."))
        }

        val existingLocal = appDao.getUserByUsername(cleanUsername)
        if (existingLocal != null) {
            return Result.failure(IllegalArgumentException("Username @$cleanUsername is already registered on this device."))
        }

        // Check if account already exists in cloud so user knows to log in
        val existingCloud = FirebaseSyncManager.instance.fetchUserFromCloud(cleanUsername)
        if (existingCloud != null) {
            return Result.failure(IllegalArgumentException("Account @$cleanUsername already exists! Switch to Login tab to use your account on this phone."))
        }

        val salt = CryptoEngine.generateSalt()
        val passHash = CryptoEngine.hashPassword(password, salt)
        val syncPhrase = CryptoEngine.generateSyncPhrase()

        val newUser = UserAccountEntity(
            username = cleanUsername,
            displayName = displayName.ifBlank { cleanUsername.replaceFirstChar { it.uppercase() } },
            passwordHash = passHash,
            salt = salt,
            country = country,
            countryCode = country.take(2).uppercase(),
            countryFlag = countryFlag,
            avatarEmoji = listOf("🔒", "⚡", "🌿", "🌸", "🌌", "🛡️", "💫").random(),
            syncPhrase = syncPhrase,
            isCurrentUser = true
        )

        appDao.clearCurrentUserFlags()
        val id = appDao.insertUser(newUser)
        val inserted = newUser.copy(id = id)
        appDao.setCurrentUser(id)

        // Save to cloud so the account can be accessed on any other phone
        scope.launch(Dispatchers.IO) {
            FirebaseSyncManager.instance.saveUserToCloud(inserted)
        }

        startUserInboxSync(cleanUsername)
        startUserCloudSync(cleanUsername)
        return Result.success(inserted)
    }

    suspend fun loginUser(username: String, password: String): Result<UserAccountEntity> {
        val cleanUsername = username.trim().lowercase().removePrefix("@")
        var user = appDao.getUserByUsername(cleanUsername)

        if (user == null) {
            // Check Firestore cloud to allow logging into the exact same account on a second phone
            val cloudUser = FirebaseSyncManager.instance.fetchUserFromCloud(cleanUsername)
            if (cloudUser != null) {
                val hash = CryptoEngine.hashPassword(password, cloudUser.salt)
                if (hash != cloudUser.passwordHash) {
                    return Result.failure(IllegalArgumentException("Incorrect password."))
                }

                val restoredUser = UserAccountEntity(
                    username = cleanUsername,
                    displayName = cloudUser.displayName.ifBlank { cleanUsername.replaceFirstChar { it.uppercase() } },
                    passwordHash = cloudUser.passwordHash,
                    salt = cloudUser.salt,
                    country = cloudUser.country,
                    countryCode = cloudUser.countryCode,
                    countryFlag = cloudUser.countryFlag,
                    avatarEmoji = cloudUser.avatarEmoji,
                    syncPhrase = cloudUser.syncPhrase,
                    isCurrentUser = true,
                    createdAt = cloudUser.createdAt
                )

                appDao.clearCurrentUserFlags()
                val id = appDao.insertUser(restoredUser)
                user = restoredUser.copy(id = id)
                appDao.setCurrentUser(id)

                // Sync all messages, chats, and friends from the cloud
                scope.launch(Dispatchers.IO) {
                    syncAllCloudDataForUser(cleanUsername)
                }

                startUserInboxSync(cleanUsername)
                startUserCloudSync(cleanUsername)
                return Result.success(user)
            } else {
                return Result.failure(IllegalArgumentException("User @$cleanUsername not found on device or cloud."))
            }
        }

        val hash = CryptoEngine.hashPassword(password, user.salt)
        if (hash != user.passwordHash) {
            return Result.failure(IllegalArgumentException("Incorrect password."))
        }

        appDao.clearCurrentUserFlags()
        appDao.setCurrentUser(user.id)

        // Ensure cloud copy of user profile is up-to-date and sync all cloud messages
        val loggedInUser = user.copy(isCurrentUser = true)
        scope.launch(Dispatchers.IO) {
            FirebaseSyncManager.instance.saveUserToCloud(loggedInUser)
            syncAllCloudDataForUser(cleanUsername)
        }

        startUserInboxSync(cleanUsername)
        startUserCloudSync(cleanUsername)
        return Result.success(loggedInUser)
    }

    suspend fun switchUser(userId: Long) {
        appDao.clearCurrentUserFlags()
        appDao.setCurrentUser(userId)
    }

    suspend fun updateProfile(
        displayName: String,
        avatarEmoji: String,
        country: String,
        countryFlag: String
    ) {
        val current = appDao.getCurrentUser() ?: return
        val updated = current.copy(
            displayName = displayName.ifBlank { current.displayName },
            avatarEmoji = avatarEmoji.ifBlank { current.avatarEmoji },
            country = country.ifBlank { current.country },
            countryFlag = countryFlag.ifBlank { current.countryFlag }
        )
        appDao.updateUser(updated)
        scope.launch(Dispatchers.IO) {
            FirebaseSyncManager.instance.saveUserToCloud(updated)
        }
    }

    suspend fun logoutUser() {
        appDao.clearCurrentUserFlags()
    }

    suspend fun sendMessage(
        conversationId: String,
        content: String,
        mediaUri: String? = null,
        mediaType: String? = null,
        mediaSizeKb: Int = 0,
        replyToMessageId: String? = null,
        replyToSenderName: String? = null,
        replyToText: String? = null
    ) {
        val currentUser = appDao.getCurrentUser() ?: return
        val conversation = appDao.getConversationById(conversationId) ?: return

        val secretKey = CryptoEngine.deriveKey(currentUser.username + conversationId, currentUser.salt)
        val encrypted = CryptoEngine.encrypt(content, secretKey)

        val messageId = UUID.randomUUID().toString()
        val message = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            senderUsername = currentUser.username,
            senderDisplayName = currentUser.displayName,
            isFromMe = true,
            content = content,
            cipherTextBase64 = encrypted.cipherTextBase64,
            ivBase64 = encrypted.ivBase64,
            mediaUri = mediaUri,
            mediaType = mediaType,
            mediaSizeKb = mediaSizeKb,
            isHighResDownloaded = true,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENDING,
            isE2eeVerified = true,
            replyToMessageId = replyToMessageId,
            replyToSenderName = replyToSenderName,
            replyToText = replyToText
        )

        appDao.insertMessage(message)
        val updatedConv = conversation.copy(
            lastMessageText = if (mediaType == "IMAGE") "📷 Photo: $content" else content,
            lastMessageTime = message.timestamp
        )
        appDao.updateConversation(updatedConv)

        val recipientUsername = if (conversation.isGroup) {
            ""
        } else {
            val myUsername = currentUser.username.trim().lowercase().removePrefix("@")
            val peer = conversation.participantUsername.trim().lowercase().removePrefix("@")
            if (peer.isNotBlank() && peer != myUsername) {
                peer
            } else if (conversation.id.startsWith("dm_")) {
                val parts = conversation.id.removePrefix("dm_").split("_")
                if (parts.size == 2) {
                    val p1 = parts[0].lowercase()
                    val p2 = parts[1].lowercase()
                    if (p1 == myUsername) p2 else p1
                } else peer
            } else {
                peer
            }
        }

        // Auto-heal participant username in Room if it was wrongly pointing to self
        if (!conversation.isGroup && recipientUsername.isNotBlank() && conversation.participantUsername != recipientUsername) {
            val healed = conversation.copy(
                participantUsername = recipientUsername,
                participantName = if (conversation.participantName == currentUser.displayName || conversation.participantName == currentUser.username) {
                    recipientUsername.replaceFirstChar { it.uppercase() }
                } else conversation.participantName
            )
            appDao.updateConversation(healed)
        }

        // Upload to live Cloud Firestore (real-time cross-device sync)
        scope.launch(Dispatchers.IO) {
            val firestoreMessage = FirestoreMessage(
                id = message.id,
                conversationId = message.conversationId,
                senderUsername = message.senderUsername,
                senderDisplayName = message.senderDisplayName,
                recipientUsername = recipientUsername,
                content = message.content,
                cipherTextBase64 = message.cipherTextBase64,
                ivBase64 = message.ivBase64,
                mediaUri = message.mediaUri,
                mediaType = message.mediaType,
                mediaSizeKb = message.mediaSizeKb,
                timestamp = message.timestamp,
                replyToMessageId = message.replyToMessageId,
                replyToSenderName = message.replyToSenderName,
                replyToText = message.replyToText,
                senderCountryFlag = currentUser.countryFlag,
                senderAvatarEmoji = currentUser.avatarEmoji
            )
            FirebaseSyncManager.instance.sendFirestoreMessage(firestoreMessage)

            // Sync conversation state to cloud for this user (and recipient)
            FirebaseSyncManager.instance.saveConversationToCloud(currentUser.username, updatedConv)
            if (recipientUsername.isNotBlank()) {
                val peerConv = updatedConv.copy(
                    participantUsername = currentUser.username,
                    participantName = currentUser.displayName,
                    participantCountry = currentUser.country,
                    countryFlag = currentUser.countryFlag,
                    avatarEmoji = currentUser.avatarEmoji
                )
                FirebaseSyncManager.instance.saveConversationToCloud(recipientUsername, peerConv)
            }
        }

        // Asynchronous delivery pipeline: SENDING -> SENT -> DELIVERED -> READ
        scope.launch(Dispatchers.IO) {
            delay(250)
            appDao.updateMessageStatus(messageId, MessageStatus.SENT)
            delay(500)
            appDao.updateMessageStatus(messageId, MessageStatus.DELIVERED)
            delay(700)
            appDao.updateMessageStatus(messageId, MessageStatus.READ)
        }

        // Handle auto responses / AI assistant logic / Group responses
        if (conversation.isAiAssistant) {
            handleAiResponse(conversationId, content, secretKey)
        } else if (conversation.isGroup) {
            if (conversation.id == "group_privacy_circle") {
                handleGroupChatResponse(conversation, content, secretKey)
            }
        } else {
            // ONLY simulate mock persona auto responses if it is one of the built-in demo catalog personas
            // AND user has NOT explicitly added them as a real customized friend
            val isDemoPersona = GlobalDirectory.GLOBAL_USERS.any { it.username == conversation.participantUsername }
            val isCustomFriend = appDao.getFriendByUsername(conversation.participantUsername) != null
            if (isDemoPersona && !isCustomFriend) {
                handleGlobalUserResponse(conversation, content, secretKey)
            }
        }
    }

    private fun handleAiResponse(conversationId: String, prompt: String, secretKey: javax.crypto.SecretKey) {
        scope.launch(Dispatchers.IO) {
            delay(400)
            // Show typing indicator
            typingStatusFlow.value = typingStatusFlow.value + (conversationId to "Gemini Meta AI is typing...")
            val aiResponseText = GeminiAssistant.generateResponse(prompt)
            delay(900) // Realistic typing duration
            typingStatusFlow.value = typingStatusFlow.value + (conversationId to null)

            val encAi = CryptoEngine.encrypt(aiResponseText, secretKey)
            val aiMessage = MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                senderUsername = "gemini_ai",
                senderDisplayName = "Gemini Meta AI",
                isFromMe = false,
                content = aiResponseText,
                cipherTextBase64 = encAi.cipherTextBase64,
                ivBase64 = encAi.ivBase64,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.READ,
                isE2eeVerified = true
            )

            appDao.insertMessage(aiMessage)
            val conv = appDao.getConversationById(conversationId)
            if (conv != null) {
                appDao.updateConversation(
                    conv.copy(
                        lastMessageText = aiResponseText.take(60) + if (aiResponseText.length > 60) "..." else "",
                        lastMessageTime = aiMessage.timestamp
                    )
                )
            }
        }
    }

    private fun handleGroupChatResponse(
        conversation: ConversationEntity,
        userMessage: String,
        secretKey: javax.crypto.SecretKey
    ) {
        scope.launch(Dispatchers.IO) {
            val memberUsernames = conversation.groupMembersJson.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val chosenUsername = if (memberUsernames.isNotEmpty()) memberUsernames.random() else "yuki_jp"
            val globalUser = GlobalDirectory.GLOBAL_USERS.firstOrNull { it.username == chosenUsername }
            val responderName = globalUser?.displayName ?: "Group Member"

            delay(700)
            typingStatusFlow.value = typingStatusFlow.value + (conversation.id to "$responderName is typing...")
            delay(1600)
            typingStatusFlow.value = typingStatusFlow.value + (conversation.id to null)

            val replyText = if (globalUser != null && globalUser.autoReplies.isNotEmpty()) {
                globalUser.autoReplies.random()
            } else {
                "Got your message in the group! All encrypted."
            }

            val encReply = CryptoEngine.encrypt(replyText, secretKey)
            val incomingMsg = MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversation.id,
                senderUsername = chosenUsername,
                senderDisplayName = "$responderName (${globalUser?.countryFlag ?: "🌐"})",
                isFromMe = false,
                content = replyText,
                cipherTextBase64 = encReply.cipherTextBase64,
                ivBase64 = encReply.ivBase64,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.READ,
                isE2eeVerified = true
            )

            appDao.insertMessage(incomingMsg)
            appDao.updateConversation(
                conversation.copy(
                    lastMessageText = "$responderName: $replyText",
                    lastMessageTime = incomingMsg.timestamp
                )
            )
        }
    }

    private fun handleGlobalUserResponse(
        conversation: ConversationEntity,
        userMessage: String,
        secretKey: javax.crypto.SecretKey
    ) {
        scope.launch(Dispatchers.IO) {
            val globalUser = GlobalDirectory.GLOBAL_USERS.firstOrNull { it.username == conversation.participantUsername }
            val participantName = conversation.participantName

            delay(700)
            typingStatusFlow.value = typingStatusFlow.value + (conversation.id to "$participantName is typing...")
            delay(1700)
            typingStatusFlow.value = typingStatusFlow.value + (conversation.id to null)

            val replyText = if (globalUser != null) {
                val candidate = globalUser.autoReplies.random()
                candidate
            } else {
                "Thanks for your message! Our end-to-end encrypted channel is active."
            }

            val encReply = CryptoEngine.encrypt(replyText, secretKey)
            val incomingMsg = MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversation.id,
                senderUsername = conversation.participantUsername,
                senderDisplayName = conversation.participantName,
                isFromMe = false,
                content = replyText,
                cipherTextBase64 = encReply.cipherTextBase64,
                ivBase64 = encReply.ivBase64,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.READ,
                isE2eeVerified = true
            )

            appDao.insertMessage(incomingMsg)
            appDao.updateConversation(
                conversation.copy(
                    lastMessageText = replyText,
                    lastMessageTime = incomingMsg.timestamp
                )
            )
        }
    }

    suspend fun createGroupChat(groupName: String, groupEmoji: String, memberUsernames: List<String>): String {
        val currentUser = appDao.getCurrentUser()
        val currentUsername = currentUser?.username ?: "alex_plain"
        val convId = "group_${UUID.randomUUID().toString().take(8)}"
        val defaultSecretKey = CryptoEngine.deriveKey(currentUsername + convId, currentUser?.salt ?: "default_salt")

        val groupSafety = CryptoEngine.generateSafetyNumbers(currentUsername, groupName)
        val memberCount = memberUsernames.size + 1 // including current user
        val groupConv = ConversationEntity(
            id = convId,
            participantUsername = "group",
            participantName = groupName,
            participantCountry = "$memberCount members",
            countryFlag = "👥",
            avatarEmoji = groupEmoji.ifEmpty { "👥" },
            isAiAssistant = false,
            isGroup = true,
            groupMembersJson = memberUsernames.joinToString(","),
            lastMessageText = "Group created with $memberCount members. End-to-end encrypted.",
            lastMessageTime = System.currentTimeMillis(),
            unreadCount = 0,
            safetyNumberVerified = true,
            safetyNumbers = groupSafety,
            isPinned = false
        )

        val welcomeText = "Welcome to $groupName! Messages in this group are end-to-end encrypted across all $memberCount participants."
        val encWelcome = CryptoEngine.encrypt(welcomeText, defaultSecretKey)
        val welcomeMsg = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = convId,
            senderUsername = "system",
            senderDisplayName = "Security Relay",
            isFromMe = false,
            content = welcomeText,
            cipherTextBase64 = encWelcome.cipherTextBase64,
            ivBase64 = encWelcome.ivBase64,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.READ,
            isE2eeVerified = true
        )

        appDao.insertConversation(groupConv)
        appDao.insertMessage(welcomeMsg)
        return convId
    }

    suspend fun getOrCreateDirectConversation(
        peerUsername: String,
        displayName: String = "",
        country: String = "Nepal",
        countryFlag: String = "🇳🇵",
        avatarEmoji: String = "👤"
    ): String {
        val cleanPeer = peerUsername.trim().lowercase().removePrefix("@")
        val currentUser = appDao.getCurrentUser()
        val myUsername = currentUser?.username?.lowercase() ?: "me"
        val canonicalId = FirebaseSyncManager.getCanonicalChatId(myUsername, cleanPeer)

        // 1. Check if canonical conversation exists
        val existingCanonical = appDao.getConversationById(canonicalId)
        if (existingCanonical != null) {
            if (existingCanonical.participantUsername.isBlank() || existingCanonical.participantUsername.lowercase() == myUsername) {
                val healed = existingCanonical.copy(
                    participantUsername = cleanPeer,
                    participantName = if (displayName.isNotBlank()) displayName else cleanPeer.replaceFirstChar { it.uppercase() }
                )
                appDao.updateConversation(healed)
            }
            startCloudSyncForConversation(canonicalId)
            return canonicalId
        }

        // 2. Check if legacy "chat_..." exists
        val legacyId = "chat_$cleanPeer"
        val legacyConv = appDao.getConversationById(legacyId)
        if (legacyConv != null) {
            // Migrate conversation and its messages to the canonical ID
            appDao.deleteConversation(legacyId)
            val updated = legacyConv.copy(id = canonicalId, participantUsername = cleanPeer)
            appDao.insertConversation(updated)
            appDao.updateMessagesConversationId(legacyId, canonicalId)
            startCloudSyncForConversation(canonicalId)
            return canonicalId
        }

        // 3. Check by participant username
        val byParticipant = appDao.getConversationByParticipantUsername(cleanPeer)
        if (byParticipant != null) {
            startCloudSyncForConversation(byParticipant.id)
            return byParticipant.id
        }

        // 4. Create new conversation with canonical ID
        val name = displayName.ifBlank { cleanPeer.replaceFirstChar { it.uppercase() } }
        val safety = CryptoEngine.generateSafetyNumbers(myUsername, cleanPeer)
        val newConv = ConversationEntity(
            id = canonicalId,
            participantUsername = cleanPeer,
            participantName = name,
            participantCountry = country.ifBlank { "Nepal" },
            countryFlag = countryFlag.ifBlank { "🇳🇵" },
            avatarEmoji = avatarEmoji.ifBlank { "👤" },
            isAiAssistant = false,
            lastMessageText = "End-to-end encrypted direct channel opened.",
            lastMessageTime = System.currentTimeMillis(),
            unreadCount = 0,
            safetyNumberVerified = true,
            safetyNumbers = safety,
            isPinned = false
        )
        appDao.insertConversation(newConv)
        startCloudSyncForConversation(canonicalId)
        scope.launch(Dispatchers.IO) {
            currentUser?.let { me ->
                FirebaseSyncManager.instance.saveConversationToCloud(me.username, newConv)
            }
        }
        return canonicalId
    }

    suspend fun startChatWithGlobalUser(globalUser: GlobalUser): String {
        val currentUser = appDao.getCurrentUser()
        val currentUsername = currentUser?.username ?: "alex_plain"
        val convId = getOrCreateDirectConversation(
            peerUsername = globalUser.username,
            displayName = globalUser.displayName,
            country = globalUser.country,
            countryFlag = globalUser.countryFlag,
            avatarEmoji = globalUser.avatarEmoji
        )

        val messageCount = appDao.getMessageById(convId)
        if (messageCount == null) {
            val secretKey = CryptoEngine.deriveKey(currentUsername + convId, currentUser?.salt ?: "salt")
            val encGreeting = CryptoEngine.encrypt(globalUser.nativeGreeting, secretKey)

            val initialMsg = MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = convId,
                senderUsername = globalUser.username,
                isFromMe = false,
                content = globalUser.nativeGreeting,
                cipherTextBase64 = encGreeting.cipherTextBase64,
                ivBase64 = encGreeting.ivBase64,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.READ,
                isE2eeVerified = true
            )
            appDao.insertMessage(initialMsg)
        }
        return convId
    }

    suspend fun toggleDarkMode(isDarkMode: Boolean) {
        val current = appDao.getSettingsFlow().firstOrNull() ?: AppSettingsEntity()
        appDao.updateSettings(current.copy(isDarkMode = isDarkMode))
    }

    suspend fun toggleDataSaver(isDataSaver: Boolean) {
        val current = appDao.getSettingsFlow().firstOrNull() ?: AppSettingsEntity()
        appDao.updateSettings(
            current.copy(
                isDataSaverEnabled = isDataSaver,
                syncStatusText = if (isDataSaver) "Low-Bandwidth Mode Active" else "Synced & Connected"
            )
        )
    }

    suspend fun markMediaDownloaded(messageId: String) {
        appDao.markMediaDownloaded(messageId)
    }

    suspend fun translateMessage(messageId: String, text: String, targetLanguage: String = "English") {
        val translation = GeminiAssistant.translateMessage(text, targetLanguage)
        appDao.updateMessageTranslation(messageId, translation)
    }

    suspend fun verifySafetyNumber(conversationId: String, verified: Boolean) {
        appDao.setSafetyNumberVerified(conversationId, verified)
    }

    suspend fun triggerCrossPlatformSync(): String {
        val currentUser = appDao.getCurrentUser()
        if (currentUser != null) {
            FirebaseSyncManager.instance.saveUserToCloud(currentUser)
            syncAllCloudDataForUser(currentUser.username)
            startUserInboxSync(currentUser.username)
            startUserCloudSync(currentUser.username)
        }
        val current = appDao.getSettingsFlow().firstOrNull() ?: AppSettingsEntity()
        val now = System.currentTimeMillis()
        appDao.updateSettings(
            current.copy(
                syncStatusText = "Synchronized across all linked phones",
                lastSyncTimestamp = now
            )
        )
        return "Synchronized across all linked phones"
    }

    suspend fun deleteConversation(conversationId: String) {
        appDao.deleteConversation(conversationId)
        appDao.deleteMessagesForConversation(conversationId)
    }

    suspend fun toggleFollowFriend(
        username: String,
        displayName: String,
        country: String,
        countryFlag: String,
        avatarEmoji: String,
        bio: String = "",
        language: String = ""
    ): Boolean {
        val cleanUsername = username.trim().lowercase().removePrefix("@")
        val existing = appDao.getFriendByUsername(cleanUsername)
        return if (existing != null) {
            appDao.deleteFriend(cleanUsername)
            false // unfollowed
        } else {
            val newFriend = FriendEntity(
                username = cleanUsername,
                displayName = displayName.ifBlank { cleanUsername },
                country = country.ifBlank { "Nepal" },
                countryFlag = countryFlag.ifBlank { "🇳🇵" },
                avatarEmoji = avatarEmoji.ifBlank { "👤" },
                bio = bio,
                language = language,
                isFollowing = true,
                isMutualFriend = true
            )
            appDao.insertFriend(newFriend)
            scope.launch(Dispatchers.IO) {
                val currentUser = appDao.getCurrentUser()
                if (currentUser != null) {
                    FirebaseSyncManager.instance.saveFriendToCloud(currentUser.username, newFriend)
                }
            }
            true // followed
        }
    }

    suspend fun addFriendByUsername(
        username: String,
        displayName: String = "",
        country: String = "Nepal",
        countryFlag: String = "🇳🇵"
    ): Result<FriendEntity> {
        val cleanUsername = username.trim().lowercase().removePrefix("@")
        if (cleanUsername.length < 2) {
            return Result.failure(IllegalArgumentException("Username must be at least 2 characters."))
        }

        // Check if user exists in local registered accounts
        val localUser = appDao.getUserByUsername(cleanUsername)
        // Check if user exists in GlobalDirectory
        val globalUser = GlobalDirectory.GLOBAL_USERS.firstOrNull { it.username == cleanUsername }

        val name = when {
            displayName.isNotBlank() -> displayName
            localUser != null -> localUser.displayName
            globalUser != null -> globalUser.displayName
            else -> cleanUsername.replaceFirstChar { it.uppercase() }
        }

        val flag = when {
            localUser != null -> localUser.countryFlag
            globalUser != null -> globalUser.countryFlag
            else -> countryFlag
        }

        val cntry = when {
            localUser != null -> localUser.country
            globalUser != null -> globalUser.country
            else -> country
        }

        val emoji = when {
            localUser != null -> localUser.avatarEmoji
            globalUser != null -> globalUser.avatarEmoji
            else -> listOf("🇳🇵", "👤", "⚡", "🌸", "🏔️", "🛡️").random()
        }

        val bioText = when {
            globalUser != null -> globalUser.bio
            else -> "Connected via Guff Secure Network"
        }

        val lang = globalUser?.language ?: "Nepali / English"

        val friend = FriendEntity(
            username = cleanUsername,
            displayName = name,
            country = cntry,
            countryFlag = flag,
            avatarEmoji = emoji,
            bio = bioText,
            language = lang,
            isFollowing = true,
            isMutualFriend = true
        )
        appDao.insertFriend(friend)

        scope.launch(Dispatchers.IO) {
            val currentUser = appDao.getCurrentUser()
            if (currentUser != null) {
                FirebaseSyncManager.instance.saveFriendToCloud(currentUser.username, friend)
            }
        }

        // Also ensure a direct conversation is initialized with canonical ID
        getOrCreateDirectConversation(
            peerUsername = cleanUsername,
            displayName = name,
            country = cntry,
            countryFlag = flag,
            avatarEmoji = emoji
        )

        return Result.success(friend)
    }

    suspend fun removeFriend(username: String) {
        val clean = username.trim().lowercase().removePrefix("@")
        appDao.deleteFriend(clean)
    }

    suspend fun runDiagnostics(): com.example.data.firebase.CloudDiagnostics {
        val user = appDao.getCurrentUser()
        return FirebaseSyncManager.instance.runDiagnostics(user?.username ?: "")
    }
}
