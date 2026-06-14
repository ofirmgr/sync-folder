package com.ofir.syncfolder.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [FileRecord::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun fileRecordDao(): FileRecordDao

    companion object {
        @Volatile private var instance: AppDb? = null

        fun getInstance(context: Context): AppDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDb::class.java,
                    "sync_folder.db"
                ).build().also { instance = it }
            }
    }
}
