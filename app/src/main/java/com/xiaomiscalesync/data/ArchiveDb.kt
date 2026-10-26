package com.xiaomiscalesync.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** The local weigh-in archive database. */
@Database(
    entities = [WeighInRecord::class, ScaleCleanupItem::class, SyncActivityEvent::class],
    version = 3,
    exportSchema = false,
)
abstract class ArchiveDb : RoomDatabase() {
    abstract fun archiveDao(): ArchiveDao
    abstract fun syncActivityDao(): SyncActivityDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_activity` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`occurredAt` INTEGER NOT NULL, " +
                        "`service` TEXT NOT NULL, " +
                        "`outcome` TEXT NOT NULL, " +
                        "`failure` TEXT NOT NULL, " +
                        "`attempts` INTEGER NOT NULL, " +
                        "`readings` INTEGER NOT NULL, " +
                        "`pushed` INTEGER NOT NULL, " +
                        "`skipped` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sync_activity_occurredAt` " +
                        "ON `sync_activity` (`occurredAt`)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Rebuild instead of ALTER RENAME COLUMN: the latter is not
                // available in every SQLite version supported by minSdk 26.
                db.execSQL(
                    "CREATE TABLE `weigh_ins_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`weightCentiKg` INTEGER NOT NULL, " +
                        "`recordedAt` INTEGER NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "`recordNo` INTEGER, " +
                        "`scaleFlag` INTEGER NOT NULL, " +
                        "`memberMid` INTEGER NOT NULL, " +
                        "`category` TEXT NOT NULL, " +
                        "`garminStatus` TEXT NOT NULL, " +
                        "`pushedAt` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `weigh_ins_new` (`id`, `weightCentiKg`, `recordedAt`, `source`, " +
                        "`recordNo`, `scaleFlag`, `memberMid`, `category`, `garminStatus`, " +
                        "`pushedAt`, `createdAt`) " +
                        "SELECT `id`, `weightGrams`, `recordedAt`, `source`, `recordNo`, " +
                        "`scaleFlag`, `memberMid`, `category`, `garminStatus`, `pushedAt`, `createdAt` " +
                        "FROM `weigh_ins`"
                )
                db.execSQL("DROP TABLE `weigh_ins`")
                db.execSQL("ALTER TABLE `weigh_ins_new` RENAME TO `weigh_ins`")
                db.execSQL(
                    "CREATE UNIQUE INDEX `index_weigh_ins_recordedAt_weightCentiKg` " +
                        "ON `weigh_ins` (`recordedAt`, `weightCentiKg`)"
                )
            }
        }

        fun build(context: Context): ArchiveDb =
            Room.databaseBuilder(context.applicationContext, ArchiveDb::class.java, "archive.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
