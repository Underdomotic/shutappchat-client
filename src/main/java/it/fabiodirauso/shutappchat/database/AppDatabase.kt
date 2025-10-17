package it.fabiodirauso.shutappchat.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import android.content.Context
import it.fabiodirauso.shutappchat.model.Message
import it.fabiodirauso.shutappchat.model.User
import it.fabiodirauso.shutappchat.model.ConversationEntity
import it.fabiodirauso.shutappchat.model.PrivacySettingsEntity
import it.fabiodirauso.shutappchat.model.ContactRequest
import it.fabiodirauso.shutappchat.model.GroupEntity
import it.fabiodirauso.shutappchat.model.GroupMemberEntity
import it.fabiodirauso.shutappchat.model.SystemNotification
import it.fabiodirauso.shutappchat.model.ForceUpdateEntity
import it.fabiodirauso.shutappchat.dao.PrivacySettingsDao
import it.fabiodirauso.shutappchat.dao.ForceUpdateDao
import it.fabiodirauso.shutappchat.config.AppConfig

@Database(
    entities = [
        Message::class, 
        User::class, 
        ConversationEntity::class, 
        PrivacySettingsEntity::class, 
        ContactRequest::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        SystemNotification::class,
        ForceUpdateEntity::class
    ],
    version = AppConfig.DATABASE_VERSION,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun userDao(): UserDao
    abstract fun conversationDao(): ConversationDao
    abstract fun privacySettingsDao(): PrivacySettingsDao
    abstract fun contactRequestDao(): ContactRequestDao
    abstract fun groupDao(): GroupDao
    abstract fun groupMemberDao(): GroupMemberDao
    abstract fun systemNotificationDao(): SystemNotificationDao
    abstract fun forceUpdateDao(): ForceUpdateDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    AppConfig.DATABASE_NAME
                )
                // Aggiungi tutte le migrazioni per preservare i dati durante gli aggiornamenti
                .addMigrations(*DatabaseMigrations.getAllMigrations())
                // Fallback SOLO per sviluppo/test - RIMUOVERE in produzione
                // .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
