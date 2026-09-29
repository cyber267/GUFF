package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.example.ui.MessengerViewModel
import com.example.ui.Screen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.ChatDetailScreen
import com.example.ui.screens.ChatListScreen
import com.example.ui.screens.GlobalDiscoverScreen
import com.example.ui.screens.MediaViewerModal
import com.example.ui.screens.ProfileSettingsScreen
import com.example.ui.screens.SyncSecurityScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MessengerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by viewModel.settings.collectAsState()

            MyApplicationTheme(darkTheme = settings.isDarkMode) {
                MainAppContent(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun MainAppContent(viewModel: MessengerViewModel) {
    val currentScreen by viewModel.currentScreen.collectAsState()
    val selectedMedia by viewModel.selectedMedia.collectAsState()

    // Handle back button when inside child screens
    if (currentScreen !is Screen.ChatList) {
        BackHandler {
            viewModel.backToChatList()
        }
    }

    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "ScreenTransition",
        modifier = Modifier
            .fillMaxSize()
            .testTag("app_main_container")
    ) { screen ->
        when (screen) {
            is Screen.ChatList -> {
                ChatListScreen(viewModel = viewModel)
            }
            is Screen.ChatDetail -> {
                ChatDetailScreen(viewModel = viewModel)
            }
            is Screen.GlobalDiscover -> {
                GlobalDiscoverScreen(viewModel = viewModel)
            }
            is Screen.Auth -> {
                AuthScreen(viewModel = viewModel)
            }
            is Screen.SyncSecurity -> {
                SyncSecurityScreen(viewModel = viewModel)
            }
            is Screen.ProfileSettings -> {
                ProfileSettingsScreen(viewModel = viewModel)
            }
        }
    }

    // High-Resolution Media Modal
    selectedMedia?.let { media ->
        MediaViewerModal(
            media = media,
            onDismiss = { viewModel.closeMediaPreview() },
            onDownload = { viewModel.downloadHighResMedia(media.messageId) }
        )
    }
}

