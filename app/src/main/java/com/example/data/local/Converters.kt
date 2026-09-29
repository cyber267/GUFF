package com.example.data.local

import androidx.room.TypeConverter
import com.example.data.model.MessageStatus

class Converters {
    @TypeConverter
    fun fromMessageStatus(status: MessageStatus?): String {
        return status?.name ?: MessageStatus.READ.name
    }

    @TypeConverter
    fun toMessageStatus(value: String?): MessageStatus {
        return try {
            if (value != null) MessageStatus.valueOf(value) else MessageStatus.READ
        } catch (e: Exception) {
            MessageStatus.READ
        }
    }
}
