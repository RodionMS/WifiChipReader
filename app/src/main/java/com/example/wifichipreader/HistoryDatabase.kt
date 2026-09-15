package com.example.wifichipreader

import android.content.Context
import androidx.room.*

@Entity(tableName = "wifi_history")
data class WifiLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    val ssid: String,
    val rssi: Int,
    val linkSpeed: Int,
    val pingMs: Long
)

@Dao
interface WifiLogDao {
    @Insert
    fun insertLog(log: WifiLog)

    @Query("SELECT * FROM wifi_history ORDER BY timestamp DESC LIMIT 100")
    fun getLastLogs(): List<WifiLog>

    @Query("DELETE FROM wifi_history")
    fun clearHistory()
}

@Database(entities = [WifiLog::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun wifiLogDao(): WifiLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "wifi_history_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}