package it.fabiodirauso.shutappchat.database

import androidx.room.TypeConverter
import it.fabiodirauso.shutappchat.model.MessageType
import it.fabiodirauso.shutappchat.model.MessageStatus
import it.fabiodirauso.shutappchat.model.ContactRequestStatus
import java.util.Date

class Converters {

    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }

    @TypeConverter
    fun fromMessageType(messageType: MessageType): String {
        return messageType.name
    }

    @TypeConverter
    fun toMessageType(messageType: String): MessageType {
        return MessageType.valueOf(messageType)
    }
    
    @TypeConverter
    fun fromMessageStatus(status: MessageStatus): String {
        return status.name
    }
    
    @TypeConverter
    fun toMessageStatus(value: String): MessageStatus {
        return MessageStatus.valueOf(value)
    }
    
    @TypeConverter
    fun fromContactRequestStatus(status: ContactRequestStatus): String {
        return status.name
    }
    
    @TypeConverter
    fun toContactRequestStatus(value: String): ContactRequestStatus {
        return ContactRequestStatus.valueOf(value)
    }
}
