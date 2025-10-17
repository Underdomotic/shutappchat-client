package it.fabiodirauso.shutappchat.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Database Migrations Manager
 * 
 * Questo file contiene tutte le migrazioni del database Room.
 * Ogni migrazione preserva i dati esistenti durante l'aggiornamento dello schema.
 * 
 * IMPORTANTE: 
 * - NON rimuovere mai una migrazione una volta pubblicata
 * - Testare sempre le migrazioni prima del rilascio
 * - Documentare ogni modifica con commenti chiari
 * 
 * Cronologia versioni database:
 * - v1 → v6: Versioni iniziali (non documentate, pre-v1.0.0)
 * - v7: Aggiunto campo autoDownloadVideos a privacy_settings (v1.1.0)
 * - v8: Aggiunti campi reply (replyToMessageId, replyToContent, replyToSenderId) a Message (v1.2.0)
 * - v9: Aggiunta tabella system_notifications per notifiche di sistema dall'admin (v1.2.1)
 */
object DatabaseMigrations {

    /**
     * Migration 7 → 8 (v1.2.0)
     * 
     * Aggiunge supporto per la funzione "Rispondi ai messaggi":
     * - replyToMessageId: ID del messaggio originale citato
     * - replyToContent: Contenuto del messaggio citato (cache per UI)
     * - replyToSenderId: ID del mittente del messaggio citato
     * 
     * STRATEGIA:
     * - Usa ALTER TABLE per aggiungere nuove colonne
     * - Valori NULL per default (messaggi esistenti non hanno reply)
     * - Nessuna perdita di dati
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Aggiungi campo replyToMessageId (riferimento al messaggio citato)
            database.execSQL(
                "ALTER TABLE Message ADD COLUMN replyToMessageId TEXT DEFAULT NULL"
            )
            
            // Aggiungi campo replyToContent (contenuto del messaggio citato)
            database.execSQL(
                "ALTER TABLE Message ADD COLUMN replyToContent TEXT DEFAULT NULL"
            )
            
            // Aggiungi campo replyToSenderId (ID del mittente del messaggio citato)
            database.execSQL(
                "ALTER TABLE Message ADD COLUMN replyToSenderId INTEGER DEFAULT NULL"
            )
            
            android.util.Log.i("DatabaseMigration", "✅ Migration 7→8 completata: Aggiunti campi reply")
        }
    }

    /**
     * Migration 8 → 9 (v1.2.1)
     * 
     * Aggiunge supporto per le notifiche di sistema inviate dall'admin:
     * - Crea tabella system_notifications con campi:
     *   - id: ID univoco della notifica (Long, Primary Key)
     *   - title: Titolo della notifica
     *   - description: Descrizione/contenuto della notifica
     *   - url: URL opzionale da aprire al click
     *   - timestamp: Data/ora di ricezione (milliseconds)
     *   - read: Flag se la notifica è stata letta (Boolean)
     * 
     * STRATEGIA:
     * - Crea nuova tabella system_notifications
     * - Nessun impatto su tabelle esistenti
     * - Nessuna perdita di dati
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Crea tabella system_notifications
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS system_notifications (
                    id INTEGER PRIMARY KEY NOT NULL,
                    title TEXT,
                    description TEXT,
                    url TEXT,
                    timestamp INTEGER NOT NULL,
                    read INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            
            android.util.Log.i("DatabaseMigration", "✅ Migration 8→9 completata: Aggiunta tabella system_notifications")
        }
    }

    /**
     * MIGRATION 9 → 10: Aggiunta campo participantUsername alla tabella conversations
     * 
     * STRATEGIA:
     * - Aggiunge colonna participantUsername alla tabella conversations
     * - Nessuna perdita di dati (colonna nullable)
     * - Le conversazioni esistenti avranno participantUsername = NULL
     */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Aggiungi colonna participantUsername alla tabella conversations
            database.execSQL(
                """
                ALTER TABLE conversations 
                ADD COLUMN participantUsername TEXT DEFAULT NULL
                """.trimIndent()
            )
            
            android.util.Log.i("DatabaseMigration", "✅ Migration 9→10 completata: Aggiunta colonna participantUsername a conversations")
        }
    }

    /**
     * MIGRATION 10 → 11: Aggiunta campo senderUsername alla tabella messages
     * 
     * STRATEGIA:
     * - Aggiunge colonna senderUsername alla tabella messages
     * - Nessuna perdita di dati (colonna nullable)
     * - I messaggi esistenti avranno senderUsername = NULL
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Aggiungi colonna senderUsername alla tabella messages
            database.execSQL(
                """
                ALTER TABLE messages 
                ADD COLUMN senderUsername TEXT DEFAULT NULL
                """.trimIndent()
            )
            
            android.util.Log.i("DatabaseMigration", "✅ Migration 10→11 completata: Aggiunta colonna senderUsername a messages")
        }
    }

    /**
     * Migration 11 → 12 (v1.3.0)
     * 
     * Aggiunge supporto per il sistema di Force Update con download integrato:
     * - Tabella force_updates: traccia versioni con aggiornamenti obbligatori
     * - Campi per gestire stato download e installazione
     * 
     * STRATEGIA:
     * - Crea nuova tabella force_updates
     * - Nessuna modifica a tabelle esistenti
     * - Nessuna perdita di dati
     */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Crea tabella force_updates per gestire aggiornamenti obbligatori
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS force_updates (
                    version TEXT PRIMARY KEY NOT NULL,
                    message TEXT NOT NULL,
                    downloadUrl TEXT NOT NULL,
                    receivedAt INTEGER NOT NULL,
                    isDownloading INTEGER NOT NULL DEFAULT 0,
                    isInstalling INTEGER NOT NULL DEFAULT 0,
                    downloadProgress INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            
            android.util.Log.i("DatabaseMigrations", "✅ Migration 11→12 completata: tabella force_updates creata")
        }
    }

    /**
     * Restituisce tutte le migrazioni disponibili nell'ordine corretto.
     * Usato da AppDatabase per configurare Room.
     */
    fun getAllMigrations(): Array<Migration> {
        return arrayOf(
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12
            // Aggiungi qui future migrazioni (es. MIGRATION_12_13, MIGRATION_13_14, ecc.)
        )
    }

    /**
     * Verifica se esiste un percorso di migrazione dalla versione FROM alla versione TO.
     * Utile per debug e logging.
     */
    fun canMigrate(from: Int, to: Int): Boolean {
        val migrations = getAllMigrations()
        return migrations.any { it.startVersion == from && it.endVersion == to }
    }
}
