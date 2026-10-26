package com.xiaomiscalesync.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real v1→v3 Room migration against a synthetic legacy archive. */
@RunWith(AndroidJUnit4::class)
class ArchiveMigrationTest {
    @Test
    fun versionOneArchiveMigratesAndActivityHistoryStaysBounded() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-${System.nanoTime()}.db"
        context.deleteDatabase(name)

        val legacy = object : SQLiteOpenHelper(context, name, null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE `weigh_ins` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`weightGrams` INTEGER NOT NULL, " +
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
                    "CREATE UNIQUE INDEX `index_weigh_ins_recordedAt_weightGrams` " +
                        "ON `weigh_ins` (`recordedAt`, `weightGrams`)"
                )
                db.execSQL(
                    "CREATE TABLE `scale_cleanup` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`recordNo` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX `index_scale_cleanup_recordNo` " +
                        "ON `scale_cleanup` (`recordNo`)"
                )
                val row = ContentValues().apply {
                    put("weightGrams", 8025)
                    put("recordedAt", 1_800_000_000L)
                    put("source", WeighInSource.STORED.name)
                    put("recordNo", 42L)
                    put("scaleFlag", 0)
                    put("memberMid", 1L)
                    put("category", Category.MINE.name)
                    put("garminStatus", GarminStatus.PUSHED.name)
                    put("pushedAt", 1_800_000_010L)
                    put("createdAt", 1_800_000_000L)
                }
                assertTrue(db.insertOrThrow("weigh_ins", null, row) > 0)
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }

        legacy.writableDatabase
        legacy.close()

        val migrated = Room.databaseBuilder(context, ArchiveDb::class.java, name)
            .addMigrations(ArchiveDb.MIGRATION_1_2, ArchiveDb.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val rows = migrated.archiveDao().all()
            assertEquals(1, rows.size)
            assertEquals(8025, rows.single().weightCentiKg)
            assertEquals(1_800_000_000L, rows.single().recordedAt)
            assertEquals(GarminStatus.PUSHED, rows.single().garminStatus)

            repeat(120) { index ->
                migrated.syncActivityDao().insertAndTrim(
                    SyncActivityEvent(
                        occurredAt = index.toLong(),
                        service = SyncService.SCALE,
                        outcome = SyncEventOutcome.COMPLETED,
                    ),
                    SyncActivityMapper.MAX_EVENTS,
                )
            }
            val events = migrated.syncActivityDao().observeRecent().first()
            assertEquals(100, events.size)
            assertEquals(119L, events.first().occurredAt)
            assertEquals(20L, events.last().occurredAt)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
