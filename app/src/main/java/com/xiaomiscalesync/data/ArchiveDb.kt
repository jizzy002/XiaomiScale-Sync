package com.xiaomiscalesync.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** The local weigh-in archive database. */
@Database(
    entities = [WeighInRecord::class, ScaleCleanupItem::class],
    version = 1,
    exportSchema = false,
)
abstract class ArchiveDb : RoomDatabase() {
    abstract fun archiveDao(): ArchiveDao

    companion object {
        fun build(context: Context): ArchiveDb =
            Room.databaseBuilder(context.applicationContext, ArchiveDb::class.java, "archive.db").build()
    }
}
