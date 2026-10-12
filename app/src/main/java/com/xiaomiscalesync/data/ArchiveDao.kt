package com.xiaomiscalesync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Room access for the archive — thin on purpose: every classification,
 * dedupe and backfill decision lives in [ArchiveMerger]; this DAO only
 * stores, updates and observes.
 */
@Dao
interface ArchiveDao {

    @Query("SELECT * FROM weigh_ins ORDER BY recordedAt DESC")
    fun observeAll(): Flow<List<WeighInRecord>>

    @Query("SELECT * FROM weigh_ins WHERE category = :category ORDER BY recordedAt DESC")
    fun observeByCategory(category: Category): Flow<List<WeighInRecord>>

    @Query("SELECT * FROM weigh_ins")
    suspend fun all(): List<WeighInRecord>

    // IGNORE (not the @Insert default ABORT): the (recordedAt, weightGrams)
    // unique index means a concurrent merge planning the same key must drop
    // the duplicate row, not throw and abort the batch.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: WeighInRecord): Long

    @Query("UPDATE weigh_ins SET recordNo = :recordNo WHERE id = :id AND recordNo IS NULL")
    suspend fun fillRecordNo(id: Long, recordNo: Long)

    @Query("UPDATE weigh_ins SET category = :category WHERE id = :id")
    suspend fun setCategory(id: Long, category: Category)

    @Query("DELETE FROM weigh_ins WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM weigh_ins")
    suspend fun deleteAll()

    @Query("SELECT * FROM weigh_ins WHERE garminStatus = :status AND category = :category ORDER BY recordedAt ASC")
    suspend fun rowsWith(status: GarminStatus, category: Category): List<WeighInRecord>

    @Query("UPDATE weigh_ins SET garminStatus = :status WHERE id IN (:ids)")
    suspend fun setGarminStatus(ids: List<Long>, status: GarminStatus)

    /** One-write variant for the success path: status flip plus the push-time stamp. */
    @Query("UPDATE weigh_ins SET garminStatus = :status, pushedAt = :pushedAt WHERE id IN (:ids)")
    suspend fun setGarminStatusAt(ids: List<Long>, status: GarminStatus, pushedAt: Long)

    // --- scale-cleanup queue (explicit user action; never automatic) -----------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun queueCleanup(items: List<ScaleCleanupItem>)

    @Query("SELECT * FROM scale_cleanup ORDER BY id")
    suspend fun queuedCleanup(): List<ScaleCleanupItem>

    @Query("DELETE FROM scale_cleanup")
    suspend fun clearCleanup()

    /** Reads and clears the queue in one transaction: each entry is handed out exactly once. */
    @Transaction
    suspend fun takeScaleCleanup(): List<Long> {
        val items = queuedCleanup()
        clearCleanup()
        return items.map { it.recordNo }
    }
}
