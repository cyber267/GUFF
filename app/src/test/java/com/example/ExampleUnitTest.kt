package com.example

import com.example.data.model.MessageEntity
import com.example.data.model.MessageStatus
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun messageEntity_replyMetadata_preservesFields() {
    val original = MessageEntity(
      id = "msg-1",
      conversationId = "conv-1",
      senderUsername = "alice",
      senderDisplayName = "Alice",
      content = "Hello there!",
      isFromMe = false,
      timestamp = System.currentTimeMillis(),
      status = MessageStatus.READ
    )

    val replyMessage = MessageEntity(
      id = "msg-2",
      conversationId = "conv-1",
      senderUsername = "bob",
      senderDisplayName = "Bob",
      content = "General Kenobi!",
      isFromMe = true,
      timestamp = System.currentTimeMillis(),
      replyToMessageId = original.id,
      replyToSenderName = original.senderDisplayName,
      replyToText = original.content,
      status = MessageStatus.SENT
    )

    assertEquals("msg-1", replyMessage.replyToMessageId)
    assertEquals("Alice", replyMessage.replyToSenderName)
    assertEquals("Hello there!", replyMessage.replyToText)
  }
}
