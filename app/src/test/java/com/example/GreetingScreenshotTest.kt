package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.data.model.ConversationEntity
import com.example.ui.screens.ConversationRowItem
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun greeting_screenshot() {
    val testConv = ConversationEntity(
      id = "chat_test",
      participantUsername = "yuki_jp",
      participantName = "Yuki Tanaka",
      participantCountry = "Japan",
      countryFlag = "🇯🇵",
      avatarEmoji = "🌸",
      lastMessageText = "End-to-End encrypted message verified."
    )
    composeTestRule.setContent {
      MyApplicationTheme {
        ConversationRowItem(conversation = testConv, onClick = {})
      }
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
  }
}

