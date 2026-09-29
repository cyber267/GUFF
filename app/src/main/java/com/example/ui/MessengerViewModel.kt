package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.GlobalDirectory
import com.example.data.GlobalUser
import com.example.data.MessengerRepository
import com.example.data.model.AppSettingsEntity
import com.example.data.model.ConversationEntity
import com.example.data.model.FriendEntity
import com.example.data.model.MessageEntity
import com.example.data.model.UserAccountEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class Screen {
    data object ChatList : Screen()
    data class ChatDetail(val conversationId: String) : Screen()
    data object GlobalDiscover : Screen()
    data object Auth : Screen()
    data object SyncSecurity : Screen()
    data object ProfileSettings : Screen()
}

data class MediaPreviewState(
    val mediaUri: String,
    val title: String,
    val sizeKb: Int,
    val isDownloaded: Boolean,
    val messageId: String
)

data class ContactSearchResult(
    val username: String,
    val displayName: String,
    val country: String,
    val countryFlag: String,
    val avatarEmoji: String,
    val conversationId: String? = null
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessengerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MessengerRepository.getInstance(application, viewModelScope)

    val currentUser: StateFlow<UserAccountEntity?> = repository.currentUserFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allUsers: StateFlow<List<UserAccountEntity>> = repository.allUsersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conversations: StateFlow<List<ConversationEntity>> = repository.conversationsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val friends: StateFlow<List<FriendEntity>> = repository.friendsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<AppSettingsEntity> = kotlinx.coroutines.flow.flow {
        repository.settingsFlow.collect {
            emit(it ?: AppSettingsEntity())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettingsEntity())

    val typingStatus: StateFlow<Map<String, String?>> = repository.typingStatusFlow.asStateFlow()

    private val _currentScreen = MutableStateFlow<Screen>(Screen.ChatList)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    val activeTypingIndicator: StateFlow<String?> = combine(_activeConversationId, repository.typingStatusFlow) { convId, map ->
        if (convId != null) map[convId] else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeConversation: StateFlow<ConversationEntity?> = _activeConversationId
        .flatMapLatest { id ->
            if (id != null) repository.getConversationFlow(id) else flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeMessages: StateFlow<List<MessageEntity>> = _activeConversationId
        .flatMapLatest { id ->
            if (id != null) repository.getMessagesFlow(id) else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val searchedMessages: StateFlow<List<MessageEntity>> = _searchQuery
        .flatMapLatest { query ->
            val q = query.trim()
            if (q.length >= 2) repository.searchMessagesFlow(q) else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val searchedContacts: StateFlow<List<ContactSearchResult>> = combine(_searchQuery, conversations) { query, convs ->
        if (query.isBlank()) return@combine emptyList<ContactSearchResult>()
        val q = query.trim().removePrefix("@").lowercase()
        val matched = mutableListOf<ContactSearchResult>()
        for (c in convs) {
            if (c.participantUsername.lowercase().contains(q) || c.participantName.lowercase().contains(q)) {
                matched.add(
                    ContactSearchResult(
                        username = c.participantUsername,
                        displayName = c.participantName,
                        country = c.participantCountry,
                        countryFlag = c.countryFlag,
                        avatarEmoji = c.avatarEmoji,
                        conversationId = c.id
                    )
                )
            }
        }
        for (gu in GlobalDirectory.GLOBAL_USERS) {
            if (gu.username.lowercase().contains(q) || gu.displayName.lowercase().contains(q)) {
                if (matched.none { it.username == gu.username }) {
                    val existingConv = convs.firstOrNull { it.participantUsername == gu.username }
                    matched.add(
                        ContactSearchResult(
                            username = gu.username,
                            displayName = gu.displayName,
                            country = gu.country,
                            countryFlag = gu.countryFlag,
                            avatarEmoji = gu.avatarEmoji,
                            conversationId = existingConv?.id
                        )
                    )
                }
            }
        }
        matched
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isCreateGroupOpen = MutableStateFlow(false)
    val isCreateGroupOpen: StateFlow<Boolean> = _isCreateGroupOpen.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _selectedMedia = MutableStateFlow<MediaPreviewState?>(null)
    val selectedMedia: StateFlow<MediaPreviewState?> = _selectedMedia.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncNotice = MutableStateFlow<String?>(null)
    val syncNotice: StateFlow<String?> = _syncNotice.asStateFlow()

    private val _replyingToMessage = MutableStateFlow<MessageEntity?>(null)
    val replyingToMessage: StateFlow<MessageEntity?> = _replyingToMessage.asStateFlow()

    fun setReplyingToMessage(message: MessageEntity) {
        _replyingToMessage.value = message
    }

    fun clearReplyingToMessage() {
        _replyingToMessage.value = null
    }

    fun openCreateGroup() {
        _isCreateGroupOpen.value = true
    }

    fun closeCreateGroup() {
        _isCreateGroupOpen.value = false
    }

    fun createGroup(name: String, emoji: String, members: List<String>) {
        if (name.isBlank()) return
        viewModelScope.launch {
            _isCreateGroupOpen.value = false
            val convId = repository.createGroupChat(name.trim(), emoji, members)
            openChat(convId)
        }
    }

    fun navigateTo(screen: Screen) {
        if (screen is Screen.ChatDetail) {
            _activeConversationId.value = screen.conversationId
        }
        _currentScreen.value = screen
    }

    fun openChat(conversationId: String) {
        _activeConversationId.value = conversationId
        _replyingToMessage.value = null
        _currentScreen.value = Screen.ChatDetail(conversationId)
    }

    fun backToChatList() {
        _activeConversationId.value = null
        _replyingToMessage.value = null
        _currentScreen.value = Screen.ChatList
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleDarkMode() {
        viewModelScope.launch {
            val isDark = !(settings.value.isDarkMode)
            repository.toggleDarkMode(isDark)
        }
    }

    fun toggleDataSaver() {
        viewModelScope.launch {
            val isDataSaver = !(settings.value.isDataSaverEnabled)
            repository.toggleDataSaver(isDataSaver)
        }
    }

    fun sendMessage(
        content: String,
        mediaUri: String? = null,
        mediaType: String? = null,
        mediaSizeKb: Int = 0
    ) {
        val convId = _activeConversationId.value ?: return
        if (content.isBlank() && mediaUri == null) return

        val replying = _replyingToMessage.value
        _replyingToMessage.value = null

        viewModelScope.launch {
            repository.sendMessage(
                conversationId = convId,
                content = content.ifBlank { "High-Resolution Image" },
                mediaUri = mediaUri,
                mediaType = mediaType,
                mediaSizeKb = mediaSizeKb,
                replyToMessageId = replying?.id,
                replyToSenderName = replying?.let {
                    if (it.isFromMe) "You"
                    else it.senderDisplayName.ifBlank { it.senderUsername }
                },
                replyToText = replying?.content
            )
        }
    }

    fun translateMessage(message: MessageEntity) {
        viewModelScope.launch {
            repository.translateMessage(message.id, message.content, "English")
        }
    }

    fun downloadHighResMedia(messageId: String) {
        viewModelScope.launch {
            repository.markMediaDownloaded(messageId)
            _selectedMedia.value?.let { current ->
                if (current.messageId == messageId) {
                    _selectedMedia.value = current.copy(isDownloaded = true)
                }
            }
        }
    }

    fun previewMedia(media: MediaPreviewState) {
        _selectedMedia.value = media
    }

    fun closeMediaPreview() {
        _selectedMedia.value = null
    }

    fun verifySafetyNumber(conversationId: String, verified: Boolean) {
        viewModelScope.launch {
            repository.verifySafetyNumber(conversationId, verified)
        }
    }

    fun startChatWithGlobalUser(user: GlobalUser) {
        viewModelScope.launch {
            val convId = repository.startChatWithGlobalUser(user)
            openChat(convId)
        }
    }

    fun openDirectChatWithFriend(friend: FriendEntity) {
        viewModelScope.launch {
            val convId = repository.getOrCreateDirectConversation(
                peerUsername = friend.username,
                displayName = friend.displayName,
                country = friend.country,
                countryFlag = friend.countryFlag,
                avatarEmoji = friend.avatarEmoji
            )
            openChat(convId)
        }
    }

    fun openDirectChatWithUsername(peerUsername: String, displayName: String = "") {
        viewModelScope.launch {
            val clean = peerUsername.trim().lowercase().removePrefix("@")
            val convId = repository.getOrCreateDirectConversation(
                peerUsername = clean,
                displayName = displayName.ifBlank { clean.replaceFirstChar { it.uppercase() } }
            )
            openChat(convId)
        }
    }

    private val _cloudDiagnostics = MutableStateFlow<com.example.data.firebase.CloudDiagnostics?>(null)
    val cloudDiagnostics: StateFlow<com.example.data.firebase.CloudDiagnostics?> = _cloudDiagnostics.asStateFlow()

    fun refreshDiagnostics() {
        viewModelScope.launch {
            _cloudDiagnostics.value = repository.runDiagnostics()
        }
    }

    fun triggerCrossPlatformSync() {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncNotice.value = "Establishing peer-to-peer encrypted relay..."
            kotlinx.coroutines.delay(1200)
            val result = repository.triggerCrossPlatformSync()
            _isSyncing.value = false
            _syncNotice.value = result
            kotlinx.coroutines.delay(2500)
            _syncNotice.value = null
        }
    }

    fun signup(
        username: String,
        password: String,
        displayName: String,
        country: String,
        countryFlag: String
    ) {
        viewModelScope.launch {
            _authError.value = null
            val result = repository.signupUser(username, password, displayName, country, countryFlag)
            result.onSuccess {
                _currentScreen.value = Screen.ChatList
            }.onFailure { e ->
                _authError.value = e.message ?: "Signup failed"
            }
        }
    }

    fun login(username: String, password: String) {
        viewModelScope.launch {
            _authError.value = null
            val result = repository.loginUser(username, password)
            result.onSuccess {
                _currentScreen.value = Screen.ChatList
            }.onFailure { e ->
                _authError.value = e.message ?: "Login failed"
            }
        }
    }

    fun switchUser(userId: Long) {
        viewModelScope.launch {
            repository.switchUser(userId)
            _currentScreen.value = Screen.ChatList
        }
    }

    fun updateProfile(displayName: String, avatarEmoji: String, country: String, countryFlag: String) {
        viewModelScope.launch {
            repository.updateProfile(displayName, avatarEmoji, country, countryFlag)
        }
    }

    fun logout() {
        viewModelScope.launch {
            repository.logoutUser()
            _currentScreen.value = Screen.Auth
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            repository.deleteConversation(conversationId)
            if (_activeConversationId.value == conversationId) {
                backToChatList()
            }
        }
    }

    fun toggleFollow(
        username: String,
        displayName: String,
        country: String,
        countryFlag: String,
        avatarEmoji: String,
        bio: String = "",
        language: String = ""
    ) {
        viewModelScope.launch {
            val followed = repository.toggleFollowFriend(
                username = username,
                displayName = displayName,
                country = country,
                countryFlag = countryFlag,
                avatarEmoji = avatarEmoji,
                bio = bio,
                language = language
            )
            _syncNotice.value = if (followed) "Followed @$username" else "Unfollowed @$username"
            kotlinx.coroutines.delay(2000)
            _syncNotice.value = null
        }
    }

    fun addFriend(username: String, onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            val result = repository.addFriendByUsername(username)
            result.onSuccess {
                _syncNotice.value = "Added @${it.username} as friend!"
                onSuccess()
                kotlinx.coroutines.delay(2000)
                _syncNotice.value = null
            }.onFailure {
                onError(it.message ?: "Failed to add friend")
            }
        }
    }

    fun removeFriend(username: String) {
        viewModelScope.launch {
            repository.removeFriend(username)
            _syncNotice.value = "Removed @$username from friends"
            kotlinx.coroutines.delay(2000)
            _syncNotice.value = null
        }
    }
}
