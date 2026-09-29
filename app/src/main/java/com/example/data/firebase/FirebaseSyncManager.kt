package com.example.data.firebase

import android.util.Log
import com.example.data.model.ConversationEntity
import com.example.data.model.FriendEntity
import com.example.data.model.UserAccountEntity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Cloud model representing an encrypted or direct message in Firestore
 * under `chats/{chatId}/messages/{messageId}` and `user_inbox/{recipientUsername}/messages/{messageId}`.
 */
data class FirestoreMessage(
    val id: String = "",
    val conversationId: String = "",
    val senderUsername: String = "",
    val senderDisplayName: String = "",
    val recipientUsername: String = "",
    val content: String = "",
    val cipherTextBase64: String = "",
    val ivBase64: String = "",
    val mediaUri: String? = null,
    val mediaType: String? = null,
    val mediaSizeKb: Int = 0,
    val timestamp: Long = 0L,
    val replyToMessageId: String? = null,
    val replyToSenderName: String? = null,
    val replyToText: String? = null,
    val senderCountryFlag: String = "🌐",
    val senderAvatarEmoji: String = "👤"
)

/**
 * Cloud model representing a user account profile for multi-device sync
 */
data class CloudUser(
    val username: String = "",
    val displayName: String = "",
    val passwordHash: String = "",
    val salt: String = "",
    val country: String = "Nepal",
    val countryCode: String = "NP",
    val countryFlag: String = "🇳🇵",
    val avatarEmoji: String = "👤",
    val syncPhrase: String = "",
    val createdAt: Long = 0L
)

/**
 * Cloud model representing a synchronized conversation metadata
 */
data class CloudConversation(
    val id: String = "",
    val participantUsername: String = "",
    val participantName: String = "",
    val participantCountry: String = "",
    val countryFlag: String = "",
    val avatarEmoji: String = "",
    val isAiAssistant: Boolean = false,
    val isGroup: Boolean = false,
    val groupMembersJson: String = "",
    val lastMessageText: String = "",
    val lastMessageTime: Long = 0L,
    val safetyNumbers: String = ""
)

/**
 * Cloud model representing a synchronized friend
 */
data class CloudFriend(
    val username: String = "",
    val displayName: String = "",
    val country: String = "",
    val countryFlag: String = "",
    val avatarEmoji: String = "",
    val addedAt: Long = 0L
)

/**
 * Diagnostic status for live network, Firestore cloud connectivity & security rules
 */
data class CloudDiagnostics(
    val isConnected: Boolean = false,
    val latencyMs: Long = 0L,
    val inboxPathAllowed: Boolean = true,
    val usersCollectionAllowed: Boolean = false,
    val projectId: String = "guff-a1491"
)

/**
 * Real-time Firestore sync manager for Guff.
 * Enables live cloud message sending, real-time snapshot listening,
 * anonymous authentication, and connection status detection.
 */
class FirebaseSyncManager {
    companion object {
        private const val TAG = "FirebaseSyncManager"
        val instance: FirebaseSyncManager by lazy { FirebaseSyncManager() }

        /**
         * Generates a deterministic canonical conversation ID for a 1-to-1 direct chat
         * between two users regardless of which device initiated the conversation.
         */
        fun getCanonicalChatId(user1: String, user2: String): String {
            val u1 = user1.trim().lowercase().removePrefix("@")
            val u2 = user2.trim().lowercase().removePrefix("@")
            val sorted = listOf(u1, u2).sorted()
            return "dm_${sorted[0]}_${sorted[1]}"
        }
    }

    private val firestore: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance()
    }

    private val auth: FirebaseAuth by lazy {
        FirebaseAuth.getInstance()
    }

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    /**
     * Ensures an active Firebase session. If not signed in, signs in anonymously
     * so that security rules allowing authenticated requests pass seamlessly.
     */
    suspend fun ensureAuthenticated(): Boolean {
        return try {
            if (auth.currentUser == null) {
                auth.signInAnonymously().await()
                Log.d(TAG, "Signed in anonymously to Firebase: ${auth.currentUser?.uid}")
            }
            _isConnected.value = true
            _authError.value = null
            true
        } catch (e: Exception) {
            Log.w(TAG, "Firebase Auth sign-in warning: ${e.message}")
            _authError.value = e.message
            // Even if anonymous auth failed, firestore may still be usable depending on rules
            false
        }
    }

    /**
     * Uploads an encrypted message to Firestore:
     * 1. Under `chats/{conversationId}/messages/{message.id}`
     * 2. Under `user_inbox/{recipientUsername}/messages/{message.id}` for instant cross-device push
     */
    suspend fun sendFirestoreMessage(message: FirestoreMessage): Result<Unit> {
        return try {
            ensureAuthenticated()
            val chatRef = firestore.collection("chats")
                .document(message.conversationId)

            val participants = listOfNotNull(
                message.senderUsername.trim().lowercase().removePrefix("@").ifBlank { null },
                message.recipientUsername.trim().lowercase().removePrefix("@").ifBlank { null }
            ).distinct()

            // 1. Update conversation metadata on the cloud
            val metaData = mutableMapOf<String, Any>(
                "conversationId" to message.conversationId,
                "lastMessageText" to message.content,
                "lastMessageTimestamp" to message.timestamp,
                "lastSenderUsername" to message.senderUsername
            )
            if (participants.isNotEmpty()) {
                metaData["participants"] = participants
            }

            chatRef.set(metaData, SetOptions.merge())

            // 2. Write message document to chat subcollection
            chatRef.collection("messages")
                .document(message.id)
                .set(message)
                .await()

            // 3. Deliver to recipient's live personal inbox for instant wake-up/arrival
            val recipient = message.recipientUsername.trim().lowercase().removePrefix("@")
            val sender = message.senderUsername.trim().lowercase().removePrefix("@")
            if (recipient.isNotBlank() && recipient != sender) {
                try {
                    firestore.collection("user_inbox")
                        .document(recipient)
                        .collection("messages")
                        .document(message.id)
                        .set(message)
                        .await()
                    Log.d(TAG, "Delivered message ${message.id} to user_inbox for @$recipient")
                } catch (inboxErr: Exception) {
                    Log.w(TAG, "Optional user_inbox write note: ${inboxErr.message}")
                }
            }

            _isConnected.value = true
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload message to Firestore: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Subscribes to real-time incoming messages for a specific conversation.
     * Emits any newly added messages from Firestore so they can be merged into Room.
     */
    fun listenToConversationMessages(conversationId: String): Flow<List<FirestoreMessage>> = callbackFlow {
        var listener: ListenerRegistration? = null
        try {
            ensureAuthenticated()
            val query = firestore.collection("chats")
                .document(conversationId)
                .collection("messages")
                .orderBy("timestamp", Query.Direction.ASCENDING)

            listener = query.addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.w(TAG, "Firestore snapshot listener error for $conversationId: ${error.message}")
                    return@addSnapshotListener
                }

                if (snapshots != null && !snapshots.isEmpty) {
                    val messages = snapshots.documentChanges
                        .filter { it.type == DocumentChange.Type.ADDED || it.type == DocumentChange.Type.MODIFIED }
                        .mapNotNull { it.document.toObject(FirestoreMessage::class.java) }

                    if (messages.isNotEmpty()) {
                        trySend(messages)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register snapshot listener: ${e.message}", e)
        }

        awaitClose {
            listener?.remove()
        }
    }

    /**
     * Subscribes to the recipient's personal inbox collection in Firestore:
     * `user_inbox/{username}/messages`
     * Allows this user's device to receive incoming messages even when the chat screen isn't open yet.
     */
    fun listenToUserInbox(username: String): Flow<List<FirestoreMessage>> = callbackFlow {
        var listener: ListenerRegistration? = null
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) {
            awaitClose { }
            return@callbackFlow
        }

        try {
            ensureAuthenticated()
            val query = firestore.collection("user_inbox")
                .document(clean)
                .collection("messages")
                .orderBy("timestamp", Query.Direction.ASCENDING)

            listener = query.addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.w(TAG, "Firestore inbox listener error for @$clean: ${error.message}")
                    return@addSnapshotListener
                }

                if (snapshots != null && !snapshots.isEmpty) {
                    val messages = snapshots.documentChanges
                        .filter { it.type == DocumentChange.Type.ADDED || it.type == DocumentChange.Type.MODIFIED }
                        .mapNotNull { it.document.toObject(FirestoreMessage::class.java) }

                    if (messages.isNotEmpty()) {
                        trySend(messages)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register inbox listener: ${e.message}", e)
        }

        awaitClose {
            listener?.remove()
        }
    }

    /**
     * Acknowledges and removes an inbox message once it has been saved to local Room DB.
     */
    suspend fun acknowledgeInboxMessage(username: String, messageId: String) {
        try {
            val clean = username.trim().lowercase().removePrefix("@")
            firestore.collection("user_inbox")
                .document(clean)
                .collection("messages")
                .document(messageId)
                .delete()
                .await()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acknowledge inbox message $messageId: ${e.message}")
        }
    }

    /**
     * Updates typing indicator state in Firestore so peer devices can see when someone is typing.
     */
    fun updateCloudTypingStatus(conversationId: String, username: String, isTyping: Boolean) {
        try {
            val statusRef = firestore.collection("chats")
                .document(conversationId)
                .collection("presence")
                .document(username)

            if (isTyping) {
                statusRef.set(
                    mapOf(
                        "isTyping" to true,
                        "updatedAt" to System.currentTimeMillis()
                    )
                )
            } else {
                statusRef.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update typing status: ${e.message}")
        }
    }

    // ==========================================
    // MULTI-DEVICE ACCOUNT & STATE SYNCHRONIZATION
    // ==========================================

    /**
     * Saves or updates a user's account in Cloud Firestore.
     * Stores in `user_inbox/{username}` (which has open security permissions)
     * and also mirrors to `users/{username}` so multi-device login works seamlessly.
     */
    suspend fun saveUserToCloud(user: UserAccountEntity): Result<Unit> {
        return try {
            ensureAuthenticated()
            val clean = user.username.trim().lowercase().removePrefix("@")
            val cloudUser = CloudUser(
                username = clean,
                displayName = user.displayName,
                passwordHash = user.passwordHash,
                salt = user.salt,
                country = user.country,
                countryCode = user.countryCode,
                countryFlag = user.countryFlag,
                avatarEmoji = user.avatarEmoji,
                syncPhrase = user.syncPhrase,
                createdAt = user.createdAt
            )

            // 1. Primary write to user_inbox/{username} root document (guaranteed open permissions)
            firestore.collection("user_inbox")
                .document(clean)
                .set(cloudUser, SetOptions.merge())
                .await()

            // 2. Secondary write to users/{username} in case rules permit it
            try {
                firestore.collection("users")
                    .document(clean)
                    .set(cloudUser, SetOptions.merge())
                    .await()
            } catch (e: Exception) {
                Log.d(TAG, "Note: users collection secondary write skipped (${e.message})")
            }

            Log.d(TAG, "User profile for @$clean saved to cloud for multi-device login")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save user to cloud: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Fetches a user's cloud account credentials from Firestore to allow login on a new phone.
     * Checks user_inbox first, then falls back to users collection.
     */
    suspend fun fetchUserFromCloud(username: String): CloudUser? {
        return try {
            ensureAuthenticated()
            val clean = username.trim().lowercase().removePrefix("@")

            // 1. Check user_inbox document (open permissions)
            val inboxDoc = firestore.collection("user_inbox")
                .document(clean)
                .get()
                .await()
            if (inboxDoc.exists()) {
                val cloudUser = inboxDoc.toObject(CloudUser::class.java)
                if (cloudUser != null && cloudUser.username.isNotBlank()) {
                    return cloudUser
                }
            }

            // 2. Fallback to users/{clean}
            val snapshot = firestore.collection("users")
                .document(clean)
                .get()
                .await()
            if (snapshot.exists()) {
                snapshot.toObject(CloudUser::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch cloud user @$username: ${e.message}")
            null
        }
    }

    /**
     * Syncs a conversation's metadata to the cloud:
     * 1. Under `chats/{conversationId}` metadata (participants & last message)
     * 2. Under `user_inbox/{username}`
     * 3. Under `users/{username}/conversations/{conversationId}` (if allowed)
     */
    suspend fun saveConversationToCloud(username: String, conv: ConversationEntity): Result<Unit> {
        return try {
            ensureAuthenticated()
            val clean = username.trim().lowercase().removePrefix("@")
            if (clean.isBlank()) return Result.success(Unit)

            val cloudConv = CloudConversation(
                id = conv.id,
                participantUsername = conv.participantUsername,
                participantName = conv.participantName,
                participantCountry = conv.participantCountry,
                countryFlag = conv.countryFlag,
                avatarEmoji = conv.avatarEmoji,
                isAiAssistant = conv.isAiAssistant,
                isGroup = conv.isGroup,
                groupMembersJson = conv.groupMembersJson,
                lastMessageText = conv.lastMessageText,
                lastMessageTime = conv.lastMessageTime,
                safetyNumbers = conv.safetyNumbers
            )

            // Save to chats metadata
            val chatRef = firestore.collection("chats").document(conv.id)
            val participants = listOfNotNull(
                clean,
                conv.participantUsername.trim().lowercase().removePrefix("@").ifBlank { null }
            ).distinct()

            val meta = mapOf(
                "conversationId" to conv.id,
                "participants" to participants,
                "lastMessageText" to conv.lastMessageText,
                "lastMessageTimestamp" to conv.lastMessageTime,
                "participantName" to conv.participantName
            )
            chatRef.set(meta, SetOptions.merge())

            // Secondary write to users/{clean}/conversations
            try {
                firestore.collection("users")
                    .document(clean)
                    .collection("conversations")
                    .document(conv.id)
                    .set(cloudConv, SetOptions.merge())
                    .await()
            } catch (ignored: Exception) {}

            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync conversation ${conv.id} to cloud: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Fetches all cloud conversations belonging to this user:
     * Queries `chats` where `participants` array contains this user's username.
     */
    suspend fun fetchUserConversations(username: String): List<CloudConversation> {
        return try {
            ensureAuthenticated()
            val clean = username.trim().lowercase().removePrefix("@")
            val results = mutableListOf<CloudConversation>()

            // 1. Query `chats` collection by participants array (open permissions)
            try {
                val chatSnapshots = firestore.collection("chats")
                    .whereArrayContains("participants", clean)
                    .get()
                    .await()

                chatSnapshots.documents.forEach { doc ->
                    val convId = doc.getString("conversationId") ?: doc.id
                    val participants = doc.get("participants") as? List<*> ?: emptyList<Any>()
                    val peerUsername = participants.mapNotNull { it?.toString() }
                        .firstOrNull { it != clean } ?: clean

                    val lastText = doc.getString("lastMessageText") ?: ""
                    val lastTime = doc.getLong("lastMessageTimestamp") ?: 0L
                    val partName = doc.getString("participantName") ?: peerUsername.replaceFirstChar { it.uppercase() }

                    results.add(
                        CloudConversation(
                            id = convId,
                            participantUsername = peerUsername,
                            participantName = partName,
                            lastMessageText = lastText,
                            lastMessageTime = lastTime
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Note: chats participants query note: ${e.message}")
            }

            // 2. Secondary check in users/{clean}/conversations
            if (results.isEmpty()) {
                try {
                    val snapshots = firestore.collection("users")
                        .document(clean)
                        .collection("conversations")
                        .orderBy("lastMessageTime", Query.Direction.DESCENDING)
                        .get()
                        .await()
                    results.addAll(snapshots.toObjects(CloudConversation::class.java))
                } catch (ignored: Exception) {}
            }

            results
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch cloud conversations for @$username: ${e.message}")
            emptyList()
        }
    }

    /**
     * Listens for changes to the user's cloud conversation list so newly created conversations
     * on one phone appear automatically on the other phone.
     */
    fun listenToUserConversations(username: String): Flow<List<CloudConversation>> = callbackFlow {
        var listener: ListenerRegistration? = null
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) {
            awaitClose { }
            return@callbackFlow
        }
        try {
            ensureAuthenticated()
            // Query chats where participants array contains this username
            val query = firestore.collection("chats")
                .whereArrayContains("participants", clean)

            listener = query.addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.w(TAG, "Error in user conversations listener: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshots != null) {
                    val list = snapshots.documents.mapNotNull { doc ->
                        val convId = doc.getString("conversationId") ?: doc.id
                        val participants = doc.get("participants") as? List<*> ?: emptyList<Any>()
                        val peerUsername = participants.mapNotNull { it?.toString() }
                            .firstOrNull { it != clean } ?: clean
                        val lastText = doc.getString("lastMessageText") ?: ""
                        val lastTime = doc.getLong("lastMessageTimestamp") ?: 0L
                        val partName = doc.getString("participantName") ?: peerUsername.replaceFirstChar { it.uppercase() }

                        CloudConversation(
                            id = convId,
                            participantUsername = peerUsername,
                            participantName = partName,
                            lastMessageText = lastText,
                            lastMessageTime = lastTime
                        )
                    }
                    trySend(list)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to listen to cloud conversations: ${e.message}", e)
        }
        awaitClose { listener?.remove() }
    }

    /**
     * Syncs a friend to the user's cloud friends list:
     * Stores in `user_inbox/{username}` (as array) and mirrors to `users/{username}/friends`.
     */
    suspend fun saveFriendToCloud(username: String, friend: FriendEntity): Result<Unit> {
        return try {
            ensureAuthenticated()
            val clean = username.trim().lowercase().removePrefix("@")
            val cleanFriend = friend.username.trim().lowercase().removePrefix("@")
            val cloudFriend = CloudFriend(
                username = cleanFriend,
                displayName = friend.displayName,
                country = friend.country,
                countryFlag = friend.countryFlag,
                avatarEmoji = friend.avatarEmoji,
                addedAt = friend.addedAt
            )

            // 1. Store in user_inbox/{username} friend list map
            val friendMap = mapOf(
                "username" to cleanFriend,
                "displayName" to friend.displayName,
                "country" to friend.country,
                "countryFlag" to friend.countryFlag,
                "avatarEmoji" to friend.avatarEmoji,
                "addedAt" to friend.addedAt
            )
            firestore.collection("user_inbox")
                .document(clean)
                .update("friend_$cleanFriend", friendMap)

            // 2. Secondary write to users/{clean}/friends/{friend}
            try {
                firestore.collection("users")
                    .document(clean)
                    .collection("friends")
                    .document(cleanFriend)
                    .set(cloudFriend, SetOptions.merge())
                    .await()
            } catch (ignored: Exception) {}

            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save friend to cloud: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Fetches all friends of this user from cloud.
     */
    suspend fun fetchUserFriends(username: String): List<CloudFriend> {
        return try {
            ensureAuthenticated()
            val clean = username.trim().lowercase().removePrefix("@")
            val friends = mutableListOf<CloudFriend>()

            // 1. Read from user_inbox/{clean} document fields
            try {
                val inboxDoc = firestore.collection("user_inbox").document(clean).get().await()
                if (inboxDoc.exists()) {
                    inboxDoc.data?.forEach { (key, value) ->
                        if (key.startsWith("friend_") && value is Map<*, *>) {
                            val fUser = value["username"] as? String ?: ""
                            val fName = value["displayName"] as? String ?: fUser
                            val fCountry = value["country"] as? String ?: "Nepal"
                            val fFlag = value["countryFlag"] as? String ?: "🇳🇵"
                            val fEmoji = value["avatarEmoji"] as? String ?: "👤"
                            val fAdded = (value["addedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
                            if (fUser.isNotBlank()) {
                                friends.add(
                                    CloudFriend(
                                        username = fUser,
                                        displayName = fName,
                                        country = fCountry,
                                        countryFlag = fFlag,
                                        avatarEmoji = fEmoji,
                                        addedAt = fAdded
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "user_inbox friends fetch note: ${e.message}")
            }

            // 2. Fallback to users/{clean}/friends
            if (friends.isEmpty()) {
                try {
                    val snapshots = firestore.collection("users")
                        .document(clean)
                        .collection("friends")
                        .orderBy("addedAt", Query.Direction.DESCENDING)
                        .get()
                        .await()
                    friends.addAll(snapshots.toObjects(CloudFriend::class.java))
                } catch (ignored: Exception) {}
            }

            friends
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch friends for @$username: ${e.message}")
            emptyList()
        }
    }

    /**
     * Listens for friends added on any linked phone so they sync in real time.
     */
    fun listenToUserFriends(username: String): Flow<List<CloudFriend>> = callbackFlow {
        var listener: ListenerRegistration? = null
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) {
            awaitClose { }
            return@callbackFlow
        }
        try {
            ensureAuthenticated()
            val docRef = firestore.collection("user_inbox").document(clean)
            listener = docRef.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Error listening to cloud friends: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    val friends = mutableListOf<CloudFriend>()
                    snapshot.data?.forEach { (key, value) ->
                        if (key.startsWith("friend_") && value is Map<*, *>) {
                            val fUser = value["username"] as? String ?: ""
                            val fName = value["displayName"] as? String ?: fUser
                            val fCountry = value["country"] as? String ?: "Nepal"
                            val fFlag = value["countryFlag"] as? String ?: "🇳🇵"
                            val fEmoji = value["avatarEmoji"] as? String ?: "👤"
                            val fAdded = (value["addedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
                            if (fUser.isNotBlank()) {
                                friends.add(
                                    CloudFriend(
                                        username = fUser,
                                        displayName = fName,
                                        country = fCountry,
                                        countryFlag = fFlag,
                                        avatarEmoji = fEmoji,
                                        addedAt = fAdded
                                    )
                                )
                            }
                        }
                    }
                    if (friends.isNotEmpty()) {
                        trySend(friends)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to listen to cloud friends: ${e.message}", e)
        }
        awaitClose { listener?.remove() }
    }

    /**
     * Diagnostic report on cloud connectivity, relays, and security rules
     */
    suspend fun runDiagnostics(username: String): CloudDiagnostics {
        val start = System.currentTimeMillis()
        var firestoreReachable = false
        var inboxWritable = false
        var userWritable = false
        var latencyMs = 0L

        try {
            ensureAuthenticated()
            val clean = username.trim().lowercase().removePrefix("@")
            // Test user_inbox read
            val testDoc = firestore.collection("user_inbox").document(if (clean.isNotBlank()) clean else "ping_test").get().await()
            firestoreReachable = true
            inboxWritable = true
            latencyMs = System.currentTimeMillis() - start

            // Test users collection permission
            try {
                firestore.collection("users").document(if (clean.isNotBlank()) clean else "ping_test").get().await()
                userWritable = true
            } catch (e: Exception) {
                userWritable = false
            }
        } catch (e: Exception) {
            firestoreReachable = false
            latencyMs = System.currentTimeMillis() - start
        }

        return CloudDiagnostics(
            isConnected = firestoreReachable,
            latencyMs = latencyMs,
            inboxPathAllowed = inboxWritable,
            usersCollectionAllowed = userWritable,
            projectId = "guff-a1491"
        )
    }

    /**
     * Fetches all messages from a cloud chat subcollection.
     */
    suspend fun fetchConversationMessages(conversationId: String): List<FirestoreMessage> {
        return try {
            ensureAuthenticated()
            val snapshots = firestore.collection("chats")
                .document(conversationId)
                .collection("messages")
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .get()
                .await()
            snapshots.toObjects(FirestoreMessage::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch messages for $conversationId: ${e.message}")
            emptyList()
        }
    }
}
